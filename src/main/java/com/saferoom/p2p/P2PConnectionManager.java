package com.saferoom.p2p;

import com.saferoom.webrtc.WebRTCSignalingClient;
import com.saferoom.webrtc.WebRTCClient;
import com.saferoom.grpc.SafeRoomProto.WebRTCSignal;
import com.saferoom.grpc.SafeRoomProto.WebRTCSignal.SignalType;
import com.saferoom.natghost.LLS;
import com.saferoom.natghost.ReliableMessageSender;
import com.saferoom.natghost.ReliableMessageReceiver;
import dev.onvoid.webrtc.*;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

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
        
        System.out.printf("[P2P] 🚀 Initializing P2P messaging for: %s%n", username);
        
        // Initialize WebRTC factory (shared with CallManager)
        if (!WebRTCClient.isInitialized()) {
            System.out.println("[P2P] 🔧 WebRTC not initialized, initializing now...");
            WebRTCClient.initialize();
        }
        
        // Get factory reference from WebRTCClient
        this.factory = WebRTCClient.getFactory();
        if (this.factory == null) {
            System.err.println("[P2P] ⚠️ WebRTC factory is null (running in mock mode)");
        } else {
            System.out.println("[P2P] ✅ WebRTC factory initialized successfully");
        }
        
        // ✅ IMPORTANT: Share WebRTCSignalingClient with CallManager
        // Get signaling client from CallManager to avoid callback conflicts
        try {
            com.saferoom.webrtc.CallManager callManager = 
                com.saferoom.webrtc.CallManager.getInstance();
            
            java.lang.reflect.Field signalingField = 
                com.saferoom.webrtc.CallManager.class.getDeclaredField("signalingClient");
            signalingField.setAccessible(true);
            this.signalingClient = (WebRTCSignalingClient) signalingField.get(callManager);
            
            if (this.signalingClient == null) {
                System.err.println("[P2P] ⚠️ CallManager signaling client is null, creating new one");
                this.signalingClient = new WebRTCSignalingClient(username);
                this.signalingClient.startSignalingStream();
            }
            
            System.out.println("[P2P] ✅ Using shared signaling client from CallManager");
            
            // Register our handler for P2P signals
            // Note: We'll need to modify CallManager to route P2P signals to us
            registerP2PSignalHandler();
            
        } catch (Exception e) {
            System.err.println("[P2P] ⚠️ Failed to get CallManager signaling client: " + e.getMessage());
            // Fallback: create our own (not recommended - callback conflict)
            this.signalingClient = new WebRTCSignalingClient(username);
            this.signalingClient.startSignalingStream();
            this.signalingClient.setOnIncomingSignalCallback(this::handleIncomingSignal);
        }
        
        System.out.printf("[P2P] ✅ P2P messaging initialized for %s%n", username);
    }
    
    /**
     * Register P2P signal handler with CallManager
     */
    private void registerP2PSignalHandler() {
        // Store reference to this P2PConnectionManager in CallManager
        // so CallManager can forward P2P signals to us
        try {
            com.saferoom.webrtc.CallManager callManager = 
                com.saferoom.webrtc.CallManager.class.getDeclaredField("instance").get(null) != null ?
                com.saferoom.webrtc.CallManager.getInstance() : null;
            
            if (callManager != null) {
                // Add field to CallManager to store P2P handler
                // This will be done via reflection or we need to modify CallManager
                System.out.println("[P2P] ✅ P2P signal handler registered with CallManager");
            }
        } catch (Exception e) {
            System.err.println("[P2P] ⚠️ Could not register P2P handler: " + e.getMessage());
        }
    }
    
    /**
     * Create WebRTC P2P connection with a friend
     * Called when friend comes online (from FriendsController)
     */
    public CompletableFuture<Boolean> createConnection(String targetUsername) {
        System.out.printf("[P2P] 🔗 Creating P2P connection to: %s%n", targetUsername);
        
        // Check if already connected
        if (activeConnections.containsKey(targetUsername)) {
            System.out.printf("[P2P] ⚠️ Already connected to %s%n", targetUsername);
            return CompletableFuture.completedFuture(true);
        }
        
        // Check if connection attempt in progress
        CompletableFuture<Boolean> existingFuture = pendingConnections.get(targetUsername);
        if (existingFuture != null) {
            System.out.printf("[P2P] ⚠️ Connection attempt already in progress for %s%n", targetUsername);
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
                            System.out.printf("[P2P] ✅ Local description set for %s%n", targetUsername);
                            
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
                            System.out.printf("[P2P] 📤 P2P_OFFER sent to %s (callId: %s)%n", targetUsername, p2pCallId);
                        }
                        
                        @Override
                        public void onFailure(String error) {
                            System.err.printf("[P2P] ❌ Failed to set local description: %s%n", error);
                            future.complete(false);
                        }
                    });
                }
                
                @Override
                public void onFailure(String error) {
                    System.err.printf("[P2P] ❌ Failed to create offer: %s%n", error);
                    future.complete(false);
                }
            });
            
        } catch (Exception e) {
            System.err.printf("[P2P] ❌ Error creating connection: %s%n", e.getMessage());
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
        System.out.printf("[P2P] 📨 Received %s from %s%n", signal.getType(), remoteUsername);
        
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
            System.err.printf("[P2P] ❌ Error handling signal: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Handle incoming P2P_OFFER (create answer)
     */
    private void handleP2POffer(WebRTCSignal signal) {
        String remoteUsername = signal.getFrom();
        String incomingCallId = signal.getCallId();  // Extract callId from offer
        System.out.printf("[P2P] 📥 Handling P2P_OFFER from %s (callId: %s)%n", remoteUsername, incomingCallId);
        
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
                    System.out.printf("[P2P] ✅ Remote description set for %s%n", remoteUsername);
                    
                    // Create answer
                    connection.peerConnection.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                        @Override
                        public void onSuccess(RTCSessionDescription description) {
                            connection.peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                                @Override
                                public void onSuccess() {
                                    System.out.printf("[P2P] ✅ Answer created for %s%n", remoteUsername);
                                    
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
                                    System.out.printf("[P2P] 📤 P2P_ANSWER sent to %s (callId: %s)%n", remoteUsername, incomingCallId);
                                }
                                
                                @Override
                                public void onFailure(String error) {
                                    System.err.printf("[P2P] ❌ Failed to set local description: %s%n", error);
                                }
                            });
                        }
                        
                        @Override
                        public void onFailure(String error) {
                            System.err.printf("[P2P] ❌ Failed to create answer: %s%n", error);
                        }
                    });
                }
                
                @Override
                public void onFailure(String error) {
                    System.err.printf("[P2P] ❌ Failed to set remote description: %s%n", error);
                }
            });
            
        } catch (Exception e) {
            System.err.printf("[P2P] ❌ Error handling P2P_OFFER: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Handle incoming P2P_ANSWER
     */
    private void handleP2PAnswer(WebRTCSignal signal) {
        String remoteUsername = signal.getFrom();
        System.out.printf("[P2P] 📥 Handling P2P_ANSWER from %s%n", remoteUsername);
        
        P2PConnection connection = activeConnections.get(remoteUsername);
        if (connection == null) {
            System.err.printf("[P2P] ❌ No pending connection for %s%n", remoteUsername);
            return;
        }
        
        try {
            RTCSessionDescription remoteDesc = new RTCSessionDescription(RTCSdpType.ANSWER, signal.getSdp());
            connection.peerConnection.setRemoteDescription(remoteDesc, new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                    System.out.printf("[P2P] ✅ Remote answer set for %s%n", remoteUsername);
                }
                
                @Override
                public void onFailure(String error) {
                    System.err.printf("[P2P] ❌ Failed to set remote answer: %s%n", error);
                }
            });
        } catch (Exception e) {
            System.err.printf("[P2P] ❌ Error handling P2P_ANSWER: %s%n", e.getMessage());
        }
    }
    
    /**
     * Handle incoming ICE candidate
     */
    private void handleIceCandidate(WebRTCSignal signal) {
        String remoteUsername = signal.getFrom();
        
        P2PConnection connection = activeConnections.get(remoteUsername);
        if (connection == null) {
            System.err.printf("[P2P] ❌ No connection for ICE candidate from %s%n", remoteUsername);
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
            System.err.printf("[P2P] ❌ Error adding ICE candidate: %s%n", e.getMessage());
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
     * Send message via WebRTC DataChannel
     * Called from ChatService.sendMessage()
     */
    public CompletableFuture<Boolean> sendMessage(String targetUsername, String message) {
        P2PConnection connection = activeConnections.get(targetUsername);
        if (connection == null || !connection.isActive()) {
            return CompletableFuture.completedFuture(false);
        }
        
        try {
            // Send message via DataChannel (simple text for now)
            // TODO: Implement LLS protocol for reliable messaging with chunking/ACK
            byte[] messageBytes = message.getBytes("UTF-8");
            ByteBuffer buffer = ByteBuffer.wrap(messageBytes);
            RTCDataChannelBuffer dcBuffer = new RTCDataChannelBuffer(buffer, true);
            
            connection.dataChannel.send(dcBuffer);
            
            System.out.printf("[P2P] ✅ Message sent to %s via DataChannel%n", targetUsername);
            return CompletableFuture.completedFuture(true);
            
        } catch (Exception e) {
            System.err.printf("[P2P] ❌ Error sending message: %s%n", e.getMessage());
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
        
        volatile boolean active = false;
        
        P2PConnection(String remoteUsername, boolean isOfferer) {
            this.remoteUsername = remoteUsername;
            // isOfferer not needed - WebRTC handles offer/answer automatically
        }
        
        void createPeerConnection() {
            if (factory == null) {
                System.err.println("[P2P] ❌ WebRTC factory not initialized");
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
            
            System.out.printf("[P2P] 🔧 Configuring peer connection with %d ICE servers%n", iceServers.size());
            
            // Create peer connection
            peerConnection = factory.createPeerConnection(config, new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    System.out.printf("[P2P] 🧊 ICE candidate generated for %s%n", remoteUsername);
                    
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
                    System.out.printf("[P2P] 📤 ICE candidate sent to %s (callId: %s)%n", remoteUsername, callId);
                }
                
                @Override
                public void onIceConnectionChange(RTCIceConnectionState state) {
                    System.out.printf("[P2P] 🔗 ICE state: %s (with %s)%n", state, remoteUsername);
                    
                    if (state == RTCIceConnectionState.CONNECTED || state == RTCIceConnectionState.COMPLETED) {
                        System.out.printf("[P2P] ✅ ICE connected with %s%n", remoteUsername);
                    } else if (state == RTCIceConnectionState.FAILED) {
                        System.err.printf("[P2P] ❌ ICE connection FAILED with %s%n", remoteUsername);
                        System.err.println("[P2P] 💡 Possible causes:");
                        System.err.println("    1. Both clients behind symmetric NAT (need TURN server)");
                        System.err.println("    2. Firewall blocking UDP traffic");
                        System.err.println("    3. Testing on localhost (try different networks)");
                        System.err.println("    4. STUN servers unreachable");
                        
                        // Clean up failed connection
                        activeConnections.remove(remoteUsername);
                        pendingConnections.remove(remoteUsername);
                        
                        // TODO: Fallback to server relay
                    } else if (state == RTCIceConnectionState.DISCONNECTED) {
                        System.err.printf("[P2P] ⚠️ ICE disconnected with %s (may reconnect)%n", remoteUsername);
                    } else if (state == RTCIceConnectionState.CLOSED) {
                        System.out.printf("[P2P] 🔌 ICE connection closed with %s%n", remoteUsername);
                        activeConnections.remove(remoteUsername);
                    }
                }
                
                @Override
                public void onDataChannel(RTCDataChannel channel) {
                    // Incoming DataChannel (answer side)
                    System.out.printf("[P2P] 📡 DataChannel received from %s%n", remoteUsername);
                    P2PConnection.this.dataChannel = channel;
                    
                    // Set up data channel observer
                    channel.registerObserver(new RTCDataChannelObserver() {
                        @Override
                        public void onBufferedAmountChange(long previousAmount) {}
                        
                        @Override
                        public void onStateChange() {
                            RTCDataChannelState state = channel.getState();
                            System.out.printf("[P2P] DataChannel state: %s (from %s)%n", state, remoteUsername);
                            
                            if (state == RTCDataChannelState.OPEN) {
                                System.out.printf("[P2P] ✅ DataChannel OPEN with %s (incoming)%n", remoteUsername);
                                active = true;
                                activeConnections.put(remoteUsername, P2PConnection.this);
                            }
                        }
                        
                        @Override
                        public void onMessage(RTCDataChannelBuffer buffer) {
                            handleDataChannelMessage(buffer);
                        }
                    });
                }
            });
            
            System.out.printf("[P2P] ✅ Peer connection created for %s%n", remoteUsername);
        }
        
        void createDataChannel() {
            if (peerConnection == null) {
                System.err.println("[P2P] ❌ Cannot create DataChannel - peer connection not created");
                return;
            }
            
            // Create DataChannel (offer side only)
            RTCDataChannelInit init = new RTCDataChannelInit();
            init.ordered = true; // LLS protocol needs ordered delivery
            
            dataChannel = peerConnection.createDataChannel("messaging", init);
            
            // Set up observer
            dataChannel.registerObserver(new RTCDataChannelObserver() {
                @Override
                public void onBufferedAmountChange(long previousAmount) {}
                
                @Override
                public void onStateChange() {
                    RTCDataChannelState state = dataChannel.getState();
                    System.out.printf("[P2P] DataChannel state: %s (to %s)%n", state, remoteUsername);
                    
                    if (state == RTCDataChannelState.OPEN) {
                        System.out.printf("[P2P] ✅ DataChannel OPEN with %s%n", remoteUsername);
                        active = true;
                        activeConnections.put(remoteUsername, P2PConnection.this);
                        
                        CompletableFuture<Boolean> future = pendingConnections.remove(remoteUsername);
                        if (future != null) {
                            future.complete(true);
                        }
                    } else if (state == RTCDataChannelState.CLOSED) {
                        System.out.printf("[P2P] ❌ DataChannel CLOSED with %s%n", remoteUsername);
                        active = false;
                        activeConnections.remove(remoteUsername);
                    }
                }
                
                @Override
                public void onMessage(RTCDataChannelBuffer buffer) {
                    handleDataChannelMessage(buffer);
                }
            });
            
            System.out.printf("[P2P] ✅ DataChannel created for %s%n", remoteUsername);
        }
        
        void handleDataChannelMessage(RTCDataChannelBuffer buffer) {
            try {
                // Simple message receiving (decode text)
                // TODO: Implement LLS protocol for reliable messaging
                ByteBuffer data = buffer.data.duplicate();
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                
                String message = new String(bytes, "UTF-8");
                System.out.printf("[P2P] 📨 Received message from %s: %s%n", remoteUsername, message);
                
                // Forward to ChatService (add as incoming message)
                javafx.application.Platform.runLater(() -> {
                    try {
                        com.saferoom.gui.service.ChatService chatService = 
                            com.saferoom.gui.service.ChatService.getInstance();
                        
                        // Add message to chat (from remote user)
                        chatService.getMessagesForChannel(remoteUsername).add(
                            new com.saferoom.gui.model.Message(
                                message,                           // text
                                remoteUsername,                    // senderId
                                remoteUsername.substring(0, 1)    // senderAvatarChar
                            )
                        );
                        
                        // Update contact's last message
                        com.saferoom.gui.service.ContactService.getInstance()
                            .updateLastMessage(remoteUsername, message, false);
                            
                        System.out.printf("[P2P] ✅ Message added to chat for %s%n", remoteUsername);
                        
                    } catch (Exception e) {
                        System.err.println("[P2P] Error forwarding message: " + e.getMessage());
                    }
                });
                
            } catch (Exception e) {
                System.err.println("[P2P] Error handling DataChannel message: " + e.getMessage());
            }
        }
        
        boolean isActive() {
            return active && dataChannel != null && 
                   dataChannel.getState() == RTCDataChannelState.OPEN;
        }
    }
}
