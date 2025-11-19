package com.saferoom.p2p;

import com.saferoom.webrtc.WebRTCSignalingClient;
import com.saferoom.webrtc.WebRTCClient;
import com.saferoom.grpc.SafeRoomProto.WebRTCSignal;
import com.saferoom.grpc.SafeRoomProto.WebRTCSignal.SignalType;
import dev.onvoid.webrtc.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * P2P Connection Manager for WebRTC-based messaging/file transfer
 * 
 * SCOPE: ONLY messaging and file transfer (NOT voice/video calls)
 * 
 * Architecture:
 * 1. WebRTC ICE negotiation → Establishes encrypted DataChannel
 * 2. DataChannel → LLS protocol packets (DNS keep-alive, reliable messaging)
 * 3. ReliableMessageSender/Receiver → Handles chunked messaging with ACK/NACK
 * 
 * This REPLACES the legacy UDP punch hole system (NatAnalyzer) for messaging.
 * Voice/video calls still use CallManager (separate WebRTC peer connection).
 */
public class P2PConnectionManager {
    
    private static P2PConnectionManager instance;
    
    private static final String FILE_CTRL_PREFIX = "__FT_CTRL__";
    private static final String CTRL_UR_RECEIVER = "UR_RECEIVER";
    private static final String CTRL_OK_SNDFILE = "OK_SNDFILE";
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();
    private static final long RECEIVER_READY_TIMEOUT_SEC = 30;
    
    private String myUsername;
    private WebRTCSignalingClient signalingClient;
    private PeerConnectionFactory factory;
    
    // Active P2P connections (username → connection)
    private final Map<String, P2PConnection> activeConnections = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> pendingConnections = new ConcurrentHashMap<>();
    
    private P2PConnectionManager() {}
    
    public static synchronized P2PConnectionManager getInstance() {
        if (instance == null) {
            instance = new P2PConnectionManager();
        }
        return instance;
    }
    
    /**
     * Initialize P2P system for messaging/file transfer
     * Called from ClientMenu.registerUserForP2P()
     */
    public void initialize(String username) {
        this.myUsername = username;
        
        System.out.printf("[P2P] Initializing P2P messaging for: %s%n", username);
        
        // Initialize WebRTC factory (shared with CallManager)
        if (!WebRTCClient.isInitialized()) {
            System.out.println("[P2P] WebRTC not initialized, initializing now...");
            WebRTCClient.initialize();
        }
        
        // Get factory reference from WebRTCClient
        this.factory = WebRTCClient.getFactory();
        if (this.factory == null) {
            System.err.println("[P2P] WebRTC factory is null (running in mock mode)");
        } else {
            System.out.println("[P2P] WebRTC factory initialized successfully");
        }
        
        // IMPORTANT: Share WebRTCSignalingClient with CallManager
        // Get signaling client from CallManager to avoid callback conflicts
        try {
            com.saferoom.webrtc.CallManager callManager = 
                com.saferoom.webrtc.CallManager.getInstance();
            
            // FIX: Use public getter instead of reflection
            this.signalingClient = callManager.getSignalingClient();
            
            if (this.signalingClient == null) {
                System.err.println("[P2P] CallManager signaling client is null, creating new one");
                this.signalingClient = new WebRTCSignalingClient(username);
                this.signalingClient.startSignalingStream();
            }
            
            System.out.println("[P2P] Using shared signaling client from CallManager");
            
            // Register our handler for P2P signals
            registerP2PSignalHandler();
            
        } catch (Exception e) {
            System.err.println("[P2P] Failed to get CallManager signaling client: " + e.getMessage());
            e.printStackTrace();
            // Fallback: create our own (not recommended - callback conflict)
            this.signalingClient = new WebRTCSignalingClient(username);
            this.signalingClient.startSignalingStream();
            this.signalingClient.setOnIncomingSignalCallback(this::handleIncomingSignal);
        }
        
        System.out.printf("[P2P] P2P messaging initialized for %s%n", username);
    }
    
    /**
     * Register P2P signal handler with CallManager
     */
    private void registerP2PSignalHandler() {
        // Store reference to this P2PConnectionManager in CallManager
        // so CallManager can forward P2P signals to us
        try {
            com.saferoom.webrtc.CallManager callManager = 
                com.saferoom.webrtc.CallManager.getInstance();
            
            if (callManager != null) {
                // Add P2P signal handler to the shared signaling client
                // P2P signals have types: P2P_OFFER, P2P_ANSWER
                if (signalingClient != null) {
                    // Add handlers for P2P signal types
                    signalingClient.addSignalHandler(SignalType.P2P_OFFER, this::handleP2POffer);
                    signalingClient.addSignalHandler(SignalType.P2P_ANSWER, this::handleP2PAnswer);
                    // Note: ICE candidates use MESH_ICE_CANDIDATE type
                    signalingClient.addSignalHandler(SignalType.MESH_ICE_CANDIDATE, this::handleIceCandidate);
                    
                    System.out.println("[P2P] ✅ P2P signal handlers registered successfully");
                }
            }
        } catch (Exception e) {
            System.err.println("[P2P] Could not register P2P handler: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Create WebRTC P2P connection with a friend
     * Called when friend comes online (from FriendsController)
     */
    public CompletableFuture<Boolean> createConnection(String targetUsername) {
        System.out.printf("[P2P] Creating P2P connection to: %s%n", targetUsername);
        
        // Check if already connected
        if (activeConnections.containsKey(targetUsername)) {
            System.out.printf("[P2P] Already connected to %s%n", targetUsername);
            return CompletableFuture.completedFuture(true);
        }
        
        // Check if connection attempt in progress
        CompletableFuture<Boolean> existingFuture = pendingConnections.get(targetUsername);
        if (existingFuture != null) {
            System.out.printf("[P2P] Connection attempt already in progress for %s%n", targetUsername);
            return existingFuture;
        }
        
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingConnections.put(targetUsername, future);
        
        try {
            // Create P2P connection (offer side)
            P2PConnection connection = new P2PConnection(targetUsername, true);
            connection.createPeerConnection();
            connection.createDataChannel();
            
            // Store in activeConnections immediately so we can handle P2P_ANSWER and ICE candidates
            activeConnections.put(targetUsername, connection);
            
            // Create offer and send via signaling
            connection.peerConnection.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    connection.peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                        @Override
                        public void onSuccess() {
                            System.out.printf("[P2P] Local description set for %s%n", targetUsername);
                            
                            // Send P2P_OFFER via signaling with "p2p-" callId prefix
                            String p2pCallId = "p2p-" + targetUsername + "-" + System.currentTimeMillis();
                            connection.callId = p2pCallId;  // Store callId for ICE candidates
                            
                            WebRTCSignal signal = WebRTCSignal.newBuilder()
                                .setType(SignalType.P2P_OFFER)
                                .setFrom(myUsername)
                                .setTo(targetUsername)
                                .setCallId(p2pCallId)  // P2P-specific callId
                                .setSdp(description.sdp)
                                .setTimestamp(System.currentTimeMillis())
                                .build();
                            
                            signalingClient.sendSignalViaStream(signal);
                            System.out.printf("[P2P] P2P_OFFER sent to %s (callId: %s)%n", targetUsername, p2pCallId);
                        }
                        
                        @Override
                        public void onFailure(String error) {
                            System.err.printf("[P2P] Failed to set local description: %s%n", error);
                            future.complete(false);
                        }
                    });
                }
                
                @Override
                public void onFailure(String error) {
                    System.err.printf("[P2P] Failed to create offer: %s%n", error);
                    future.complete(false);
                }
            });
            
        } catch (Exception e) {
            System.err.printf("[P2P] Error creating connection: %s%n", e.getMessage());
            e.printStackTrace();
            future.complete(false);
            pendingConnections.remove(targetUsername);
        }
        
        return future;
    }
    
    /**
     * Handle incoming WebRTC signals (P2P_OFFER, P2P_ANSWER, ICE_CANDIDATE)
     */
    private void handleIncomingSignal(WebRTCSignal signal) {
        // ONLY handle P2P messaging signals (NOT call signals)
        if (signal.getType() != SignalType.P2P_OFFER && 
            signal.getType() != SignalType.P2P_ANSWER && 
            signal.getType() != SignalType.ICE_CANDIDATE) {
            return; // Ignore call signals (handled by CallManager)
        }
        
        String remoteUsername = signal.getFrom();
        System.out.printf("[P2P] Received %s from %s%n", signal.getType(), remoteUsername);
        
        try {
            switch (signal.getType()) {
                case P2P_OFFER:
                    handleP2POffer(signal);
                    break;
                    
                case P2P_ANSWER:
                    handleP2PAnswer(signal);
                    break;
                    
                case ICE_CANDIDATE:
                    handleIceCandidate(signal);
                    break;
                    
                default:
                    break;
            }
        } catch (Exception e) {
            System.err.printf("[P2P] Error handling signal: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Handle incoming P2P_OFFER (create answer)
     */
    private void handleP2POffer(WebRTCSignal signal) {
        String remoteUsername = signal.getFrom();
        String incomingCallId = signal.getCallId();  // Extract callId from offer
        System.out.printf("[P2P] Handling P2P_OFFER from %s (callId: %s)%n", remoteUsername, incomingCallId);
        
        try {
            // Create P2P connection (answer side)
            P2PConnection connection = new P2PConnection(remoteUsername, false);
            connection.callId = incomingCallId;  // Store callId for ICE candidates
            connection.createPeerConnection();
            
            // Store in activeConnections for ICE candidate handling
            activeConnections.put(remoteUsername, connection);
            
            // Set remote description (offer)
            RTCSessionDescription remoteDesc = new RTCSessionDescription(RTCSdpType.OFFER, signal.getSdp());
            connection.peerConnection.setRemoteDescription(remoteDesc, new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                    System.out.printf("[P2P] Remote description set for %s%n", remoteUsername);
                    
                    // Create answer
                    connection.peerConnection.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                        @Override
                        public void onSuccess(RTCSessionDescription description) {
                            connection.peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                                @Override
                                public void onSuccess() {
                                    System.out.printf("[P2P] Answer created for %s%n", remoteUsername);
                                    
                                    // Send P2P_ANSWER via signaling with matching callId from offer
                                    WebRTCSignal answerSignal = WebRTCSignal.newBuilder()
                                        .setType(SignalType.P2P_ANSWER)
                                        .setFrom(myUsername)
                                        .setTo(remoteUsername)
                                        .setCallId(incomingCallId)  // Use same callId from P2P_OFFER
                                        .setSdp(description.sdp)
                                        .setTimestamp(System.currentTimeMillis())
                                        .build();
                                    
                                    signalingClient.sendSignalViaStream(answerSignal);
                                    System.out.printf("[P2P] P2P_ANSWER sent to %s (callId: %s)%n", remoteUsername, incomingCallId);
                                }
                                
                                @Override
                                public void onFailure(String error) {
                                    System.err.printf("[P2P] Failed to set local description: %s%n", error);
                                }
                            });
                        }
                        
                        @Override
                        public void onFailure(String error) {
                            System.err.printf("[P2P] Failed to create answer: %s%n", error);
                        }
                    });
                }
                
                @Override
                public void onFailure(String error) {
                    System.err.printf("[P2P] Failed to set remote description: %s%n", error);
                }
            });
            
        } catch (Exception e) {
            System.err.printf("[P2P] Error handling P2P_OFFER: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Handle incoming P2P_ANSWER
     */
    private void handleP2PAnswer(WebRTCSignal signal) {
        String remoteUsername = signal.getFrom();
        System.out.printf("[P2P] Handling P2P_ANSWER from %s%n", remoteUsername);
        
        P2PConnection connection = activeConnections.get(remoteUsername);
        if (connection == null) {
            System.err.printf("[P2P] No pending connection for %s%n", remoteUsername);
            return;
        }
        
        try {
            RTCSessionDescription remoteDesc = new RTCSessionDescription(RTCSdpType.ANSWER, signal.getSdp());
            connection.peerConnection.setRemoteDescription(remoteDesc, new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                    System.out.printf("[P2P] Remote answer set for %s%n", remoteUsername);
                }
                
                @Override
                public void onFailure(String error) {
                    System.err.printf("[P2P] Failed to set remote answer: %s%n", error);
                }
            });
        } catch (Exception e) {
            System.err.printf("[P2P] Error handling P2P_ANSWER: %s%n", e.getMessage());
        }
    }
    
    /**
     * Handle incoming ICE candidate
     */
    private void handleIceCandidate(WebRTCSignal signal) {
        String remoteUsername = signal.getFrom();
        
        P2PConnection connection = activeConnections.get(remoteUsername);
        if (connection == null) {
            System.err.printf("[P2P] No connection for ICE candidate from %s%n", remoteUsername);
            return;
        }
        
        try {
            RTCIceCandidate candidate = new RTCIceCandidate(
                signal.getSdpMid(),
                signal.getSdpMLineIndex(),
                signal.getCandidate()
            );
            connection.peerConnection.addIceCandidate(candidate);
        } catch (Exception e) {
            System.err.printf("[P2P] Error adding ICE candidate: %s%n", e.getMessage());
        }
    }
    
    /**
     * Check if we have active P2P connection with user
     */
    public boolean hasActiveConnection(String username) {
        P2PConnection conn = activeConnections.get(username);
        return conn != null && conn.isActive();
    }
    
    /**
     * Send message via WebRTC DataChannel with reliable messaging protocol
     * Called from ChatService.sendMessage()
     */
    public CompletableFuture<Boolean> sendMessage(String targetUsername, String message) {
        P2PConnection connection = activeConnections.get(targetUsername);
        if (connection == null || !connection.isActive()) {
            System.err.printf("[P2P] No active connection to %s%n", targetUsername);
            return CompletableFuture.completedFuture(false);
        }
        
        if (connection.reliableMessaging == null) {
            System.err.printf("[P2P] Reliable messaging not initialized for %s%n", targetUsername);
            return CompletableFuture.completedFuture(false);
        }
        
        try {
            // Send via reliable messaging protocol (with chunking, ACK, NACK, CRC)
            System.out.printf("[P2P] Sending reliable message to %s: %s%n", targetUsername, message);
            return connection.reliableMessaging.sendMessage(targetUsername, message);
            
        } catch (Exception e) {
            System.err.printf("[P2P] Error sending message: %s%n", e.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }
    
    /**
     * Send file via WebRTC DataChannel with file transfer protocol
     */
    public CompletableFuture<Boolean> sendFile(String targetUsername, java.nio.file.Path filePath) {
        P2PConnection connection = activeConnections.get(targetUsername);
        if (connection == null || !connection.isActive()) {
            System.err.printf("[P2P] No active connection to %s%n", targetUsername);
            return CompletableFuture.completedFuture(false);
        }
        
        if (connection.fileTransfer == null) {
            connection.initializeFileTransfer();
        }
        if (connection.fileTransfer == null) {
            System.err.printf("[P2P] File transfer not initialized for %s%n", targetUsername);
            return CompletableFuture.completedFuture(false);
        }
        
        try {
            long fileSize = Files.size(filePath);
            long fileId = System.currentTimeMillis();
            
            CompletableFuture<Void> readyFuture = connection.fileTransfer.awaitReceiverReady(fileId);
            connection.sendControlMessage(connection.buildUrReceiverControl(
                fileId, fileSize, filePath.getFileName().toString()));
            
            return readyFuture.orTimeout(RECEIVER_READY_TIMEOUT_SEC, TimeUnit.SECONDS)
                .thenCompose(v -> connection.fileTransfer.sendFile(filePath, fileId))
                .exceptionally(ex -> {
                    System.err.printf("[P2P] File transfer failed: %s%n", ex.getMessage());
                    return false;
                });
        } catch (Exception e) {
            System.err.printf("[P2P] Error preparing file transfer: %s%n", e.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }
    
    /**
     * P2P Connection class (inner class)
     * Represents one WebRTC peer connection with DataChannel
     */
    private class P2PConnection {
        final String remoteUsername;
        String callId;  // P2P-specific callId for signal routing
        
        RTCPeerConnection peerConnection;
        RTCDataChannel dataChannel;
        RTCDataChannel fileDataChannel;
        DataChannelReliableMessaging reliableMessaging;  // Reliable messaging protocol
        
        // File transfer: DataChannelFileTransfer coordinates roles, uses original file_transfer/
        DataChannelFileTransfer fileTransfer;
        
        volatile boolean active = false;
        
        P2PConnection(String remoteUsername, boolean isOfferer) {
            this.remoteUsername = remoteUsername;
            // isOfferer not needed - WebRTC handles offer/answer automatically
        }
        
        void createPeerConnection() {
            if (factory == null) {
                System.err.println("[P2P] WebRTC factory not initialized");
                return;
            }
            
            // Configure STUN/TURN servers for NAT traversal
            List<RTCIceServer> iceServers = new ArrayList<>();
            
            // Google STUN servers
            RTCIceServer stunServer = new RTCIceServer();
            stunServer.urls.add("stun:stun.l.google.com:19302");
            stunServer.urls.add("stun:stun1.l.google.com:19302");
            stunServer.urls.add("stun:stun2.l.google.com:19302");
            iceServers.add(stunServer);
            
            // TODO: Add TURN server for symmetric NAT
            // RTCIceServer turnServer = new RTCIceServer();
            // turnServer.urls.add("turn:your-turn-server.com:3478");
            // turnServer.username = "username";
            // turnServer.password = "password";
            // iceServers.add(turnServer);
            
            RTCConfiguration config = new RTCConfiguration();
            config.iceServers = iceServers;
            config.iceTransportPolicy = RTCIceTransportPolicy.ALL;  // Try all candidates (relay, srflx, host)
            
            System.out.printf("[P2P] Configuring peer connection with %d ICE servers%n", iceServers.size());
            
            // Create peer connection
            peerConnection = factory.createPeerConnection(config, new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    System.out.printf("[P2P] ICE candidate generated for %s%n", remoteUsername);
                    
                    // Send ICE candidate via signaling with "p2p-" callId
                    WebRTCSignal signal = WebRTCSignal.newBuilder()
                        .setType(SignalType.ICE_CANDIDATE)
                        .setFrom(myUsername)
                        .setTo(remoteUsername)
                        .setCallId(callId)  // Use stored P2P callId for routing
                        .setCandidate(candidate.sdp)
                        .setSdpMid(candidate.sdpMid)
                        .setSdpMLineIndex(candidate.sdpMLineIndex)
                        .setTimestamp(System.currentTimeMillis())
                        .build();
                    
                    signalingClient.sendSignalViaStream(signal);
                    System.out.printf("[P2P] ICE candidate sent to %s (callId: %s)%n", remoteUsername, callId);
                }
                
                @Override
                public void onIceConnectionChange(RTCIceConnectionState state) {
                    System.out.printf("[P2P] ICE state: %s (with %s)%n", state, remoteUsername);
                    
                    if (state == RTCIceConnectionState.CONNECTED || state == RTCIceConnectionState.COMPLETED) {
                        System.out.printf("[P2P] ICE connected with %s%n", remoteUsername);
                    } else if (state == RTCIceConnectionState.FAILED) {
                        System.err.printf("[P2P] ICE connection FAILED with %s%n", remoteUsername);
                        System.err.println("[P2P] Possible causes:");
                        System.err.println("    1. Both clients behind symmetric NAT (need TURN server)");
                        System.err.println("    2. Firewall blocking UDP traffic");
                        System.err.println("    3. Testing on localhost (try different networks)");
                        System.err.println("    4. STUN servers unreachable");
                        
                        // Clean up failed connection
                        activeConnections.remove(remoteUsername);
                        pendingConnections.remove(remoteUsername);
                        
                        // TODO: Fallback to server relay
                    } else if (state == RTCIceConnectionState.DISCONNECTED) {
                        System.err.printf("[P2P] ICE disconnected with %s (may reconnect)%n", remoteUsername);
                    } else if (state == RTCIceConnectionState.CLOSED) {
                        System.out.printf("[P2P] ICE connection closed with %s%n", remoteUsername);
                        activeConnections.remove(remoteUsername);
                    }
                }
                
                @Override
                public void onDataChannel(RTCDataChannel channel) {
                    String label = channel.getLabel();
                    System.out.printf("[P2P] DataChannel received (%s) from %s%n", label, remoteUsername);
                    if ("file-transfer".equals(label)) {
                        attachFileChannel(channel);
                    } else {
                        attachMessagingChannel(channel);
                    }
                }
            });
            
            System.out.printf("[P2P] Peer connection created for %s%n", remoteUsername);
        }
        
        void createDataChannel() {
            if (peerConnection == null) {
                System.err.println("[P2P] Cannot create DataChannel - peer connection not created");
                return;
            }
            
            // Create messaging channel (offer side)
            RTCDataChannelInit init = new RTCDataChannelInit();
            init.ordered = true; // LLS protocol needs ordered delivery
            RTCDataChannel messagingChannel = peerConnection.createDataChannel("messaging", init);
            attachMessagingChannel(messagingChannel);
            
            // Create dedicated file-transfer channel
            RTCDataChannelInit ftInit = new RTCDataChannelInit();
            ftInit.ordered = true;
            RTCDataChannel ftChannel = peerConnection.createDataChannel("file-transfer", ftInit);
            attachFileChannel(ftChannel);
            
            System.out.printf("[P2P] DataChannels created for %s%n", remoteUsername);
        }
        
        private void attachMessagingChannel(RTCDataChannel channel) {
            this.dataChannel = channel;
            channel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long previousAmount) {}
                
                @Override
                public void onStateChange() {
                    RTCDataChannelState state = channel.getState();
                    System.out.printf("[P2P] DataChannel state (messaging): %s (with %s)%n",
                        state, remoteUsername);
                    
                    if (state == RTCDataChannelState.OPEN) {
                        active = true;
                        activeConnections.put(remoteUsername, P2PConnection.this);
                        initializeReliableMessaging();
                        
                        CompletableFuture<Boolean> future = pendingConnections.remove(remoteUsername);
                        if (future != null) {
                            future.complete(true);
                        }
                    } else if (state == RTCDataChannelState.CLOSED) {
                        active = false;
                        activeConnections.remove(remoteUsername);
                    }
                }
                
                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    handleMessagingChannelMessage(buffer);
                }
            });
        }
        
        private void attachFileChannel(RTCDataChannel channel) {
            this.fileDataChannel = channel;
            channel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long previousAmount) {}
                
                @Override
                public void onStateChange() {
                    RTCDataChannelState state = channel.getState();
                    System.out.printf("[P2P] DataChannel state (file-transfer): %s (with %s)%n",
                        state, remoteUsername);
                    
                    if (state == RTCDataChannelState.OPEN) {
                        initializeFileTransfer();
                    }
                }
                
                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    if (fileTransfer != null) {
                        fileTransfer.handleIncomingMessage(buffer);
                    } else {
                        System.err.printf("[P2P] File transfer handler not ready for %s%n", remoteUsername);
                    }
                }
            });
        }
        
        /**
         * Initialize reliable messaging protocol over DataChannel
         */
        void initializeReliableMessaging() {
            if (dataChannel == null || dataChannel.getState() != RTCDataChannelState.OPEN) {
                System.err.println("[P2P] Cannot initialize reliable messaging - DataChannel not open");
                return;
            }
            
            reliableMessaging = new DataChannelReliableMessaging(myUsername, dataChannel);
            
            // Set callback for completed messages
            reliableMessaging.setCompletionCallback((sender, messageId, messageBytes) -> {
                try {
                    String messageText = new String(messageBytes, StandardCharsets.UTF_8);
                    System.out.printf("[P2P] Reliable message complete from %s: %s%n", 
                        remoteUsername, messageText);
                    
                    if (handleFileTransferControl(messageText)) {
                        return;
                    }
                    
                    // Forward to ChatService
                    javafx.application.Platform.runLater(() -> {
                        try {
                            com.saferoom.gui.service.ChatService chatService = 
                                com.saferoom.gui.service.ChatService.getInstance();
                            
                            chatService.getMessagesForChannel(remoteUsername).add(
                                new com.saferoom.gui.model.Message(
                                    messageText,
                                    remoteUsername,
                                    remoteUsername.substring(0, 1)
                                )
                            );
                            
                            com.saferoom.gui.service.ContactService.getInstance()
                                .updateLastMessage(remoteUsername, messageText, false);
                                
                            System.out.printf("[P2P] Message added to chat for %s%n", remoteUsername);
                            
                        } catch (Exception e) {
                            System.err.println("[P2P] Error forwarding message: " + e.getMessage());
                        }
                    });
                    
                } catch (Exception e) {
                    System.err.printf("[P2P] Error processing completed message: %s%n", e.getMessage());
                }
            });
            
            System.out.printf("[P2P] Reliable messaging initialized for %s%n", remoteUsername);
            
            // Also initialize file transfer (if not already initialized)
            if (fileTransfer == null) {
                initializeFileTransfer();
            }
        }
        
        /**
         * Initialize file transfer protocol over DataChannel
         * Uses DataChannelFileTransfer which coordinates roles and uses original file_transfer/
         */
        void initializeFileTransfer() {
            if (fileDataChannel == null || fileDataChannel.getState() != RTCDataChannelState.OPEN) {
                System.err.println("[P2P] Cannot initialize file transfer - file DataChannel not open");
                return;
            }
            
            try {
                // Create DataChannelFileTransfer (handles wrapper + original classes)
                fileTransfer = new DataChannelFileTransfer(myUsername, fileDataChannel, remoteUsername);
                
                // DON'T start receiver here! It will start LAZY on first SYN
                System.out.printf("[P2P] File transfer initialized for %s (receiver will start on SYN)%n", 
                    remoteUsername);
                
            } catch (Exception e) {
                System.err.printf("[P2P] Failed to initialize file transfer: %s%n", e.getMessage());
                e.printStackTrace();
            }
        }
        
        void handleMessagingChannelMessage(RTCDataChannelBuffer buffer) {
            try {
                ByteBuffer data = buffer.data.duplicate();
                if (data.remaining() < 1) return;
                
                byte signal = data.get(0);
                
                // Messaging signals only (0x20-0x23)
                if (signal >= 0x20 && signal <= 0x23) {
                    if (reliableMessaging != null) {
                        reliableMessaging.handleIncomingMessage(buffer);
                    } else {
                        System.err.println("[P2P] Received message but reliable messaging not initialized");
                    }
                } else {
                    System.err.printf("[P2P] Unexpected signal on messaging channel: 0x%02X%n", signal);
                }
                
            } catch (Exception e) {
                System.err.println("[P2P] Error handling DataChannel message: " + e.getMessage());
            }
        }
        
        private boolean handleFileTransferControl(String messageText) {
            if (!messageText.startsWith(FILE_CTRL_PREFIX)) {
                return false;
            }
            
            String[] parts = messageText.split("\\|");
            if (parts.length < 3) {
                return true;
            }
            
            String type = parts[1];
            if (CTRL_UR_RECEIVER.equals(type)) {
                if (parts.length < 5) {
                    return true;
                }
                
                long fileId = parseLongSafe(parts[2]);
                String fileName = decodeFileName(parts[4]);
                
                if (fileTransfer == null) {
                    initializeFileTransfer();
                }
                
                if (fileTransfer != null) {
                    fileTransfer.prepareIncomingFile(fileId, fileName);
                    fileTransfer.startPreparedReceiver(fileId);
                    sendControlMessage(buildOkControl(fileId));
                }
            } else if (CTRL_OK_SNDFILE.equals(type)) {
                long fileId = parseLongSafe(parts[2]);
                if (fileTransfer != null) {
                    fileTransfer.markReceiverReady(fileId);
                }
            }
            
            return true;
        }
        
        private void sendControlMessage(String payload) {
            if (reliableMessaging == null) {
                System.err.println("[P2P] Reliable messaging not ready for control message");
                return;
            }
            reliableMessaging.sendMessage(remoteUsername, payload);
        }
        
        private String buildUrReceiverControl(long fileId, long fileSize, String fileName) {
            String encodedName = BASE64_ENCODER.encodeToString(fileName.getBytes(StandardCharsets.UTF_8));
            return FILE_CTRL_PREFIX + "|" + CTRL_UR_RECEIVER + "|" + fileId + "|" + fileSize + "|" + encodedName;
        }
        
        private String buildOkControl(long fileId) {
            return FILE_CTRL_PREFIX + "|" + CTRL_OK_SNDFILE + "|" + fileId;
        }
        
        private long parseLongSafe(String value) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ex) {
                return 0L;
            }
        }
        
        private String decodeFileName(String encoded) {
            try {
                return new String(BASE64_DECODER.decode(encoded), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                return "file.bin";
            }
        }
        
        boolean isActive() {
            return active && dataChannel != null && 
                   dataChannel.getState() == RTCDataChannelState.OPEN;
        }
    }
}
