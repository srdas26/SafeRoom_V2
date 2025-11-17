package com.saferoom.webrtc;

import com.saferoom.grpc.SafeRoomProto.WebRTCSignal;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.video.VideoTrack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * GroupCallManager - Manages mesh topology group calls (≤4 participants)
 * 
 * Architecture:
 * - Each participant maintains N-1 direct WebRTC peer connections (mesh)
 * - Server acts as signaling relay (distributes MESH_OFFER/MESH_ANSWER/MESH_ICE_CANDIDATE)
 * - Room-based: participants join via roomId
 * 
 * Flow:
 * 1. User creates/joins room → sends ROOM_JOIN
 * 2. Server responds with ROOM_JOINED + peerList
 * 3. For each peer: create WebRTCClient, exchange SDP offers/answers
 * 4. New peer joins → all receive ROOM_PEER_JOINED → create new connection
 * 5. Peer leaves → all receive ROOM_PEER_LEFT → close connection
 */
public class GroupCallManager {
    
    private static GroupCallManager instance;
    
    // Room state
    private String currentRoomId;
    private String localUsername;
    private boolean inRoom = false;
    
    // Mesh connections: peerId → WebRTCClient
    private final Map<String, WebRTCClient> peerConnections = new ConcurrentHashMap<>();
    
    // Signaling client
    private WebRTCSignalingClient signalingClient;
    
    // Room settings
    private boolean audioEnabled = true;
    private boolean videoEnabled = true;
    
    // Callbacks
    private Runnable onRoomJoinedCallback;
    private Runnable onRoomLeftCallback;
    private PeerJoinedCallback onPeerJoinedCallback;
    private PeerLeftCallback onPeerLeftCallback;
    private RemoteTrackCallback onRemoteTrackCallback;
    
    /**
     * Singleton pattern
     */
    public static synchronized GroupCallManager getInstance() {
        if (instance == null) {
            instance = new GroupCallManager();
        }
        return instance;
    }
    
    private GroupCallManager() {
        // Private constructor
    }
    
    /**
     * Initialize with signaling client
     */
    public void initialize(WebRTCSignalingClient signalingClient, String username) {
        this.signalingClient = signalingClient;
        this.localUsername = username;
        
        System.out.println("[GroupCallManager] Initialized for user: " + username);
        
        // Register signal handlers for group call signals
        setupSignalHandlers();
    }
    
    /**
     * Setup signal handlers for group call messages
     */
    private void setupSignalHandlers() {
        if (signalingClient == null) {
            System.err.println("[GroupCallManager] Cannot setup handlers - signaling client null");
            return;
        }
        
        // Handle ROOM_JOINED (server confirms join + sends peer list)
        signalingClient.addSignalHandler(WebRTCSignal.SignalType.ROOM_JOINED, signal -> {
            handleRoomJoined(signal);
        });
        
        // Handle ROOM_PEER_JOINED (new peer joined room)
        signalingClient.addSignalHandler(WebRTCSignal.SignalType.ROOM_PEER_JOINED, signal -> {
            handlePeerJoined(signal);
        });
        
        // Handle ROOM_PEER_LEFT (peer left room)
        signalingClient.addSignalHandler(WebRTCSignal.SignalType.ROOM_PEER_LEFT, signal -> {
            handlePeerLeft(signal);
        });
        
        // Handle MESH_OFFER (peer sending SDP offer)
        signalingClient.addSignalHandler(WebRTCSignal.SignalType.MESH_OFFER, signal -> {
            handleMeshOffer(signal);
        });
        
        // Handle MESH_ANSWER (peer sending SDP answer)
        signalingClient.addSignalHandler(WebRTCSignal.SignalType.MESH_ANSWER, signal -> {
            handleMeshAnswer(signal);
        });
        
        // Handle MESH_ICE_CANDIDATE (peer sending ICE candidate)
        signalingClient.addSignalHandler(WebRTCSignal.SignalType.MESH_ICE_CANDIDATE, signal -> {
            handleMeshIceCandidate(signal);
        });
    }
    
    /**
     * Join a room (creates room if doesn't exist)
     */
    public CompletableFuture<Void> joinRoom(String roomId, boolean audio, boolean video) {
        System.out.printf("[GroupCallManager] Joining room: %s (audio=%b, video=%b)%n", 
            roomId, audio, video);
        
        if (inRoom) {
            System.err.println("[GroupCallManager] Already in a room, leave first!");
            return CompletableFuture.failedFuture(new IllegalStateException("Already in room"));
        }
        
        this.currentRoomId = roomId;
        this.audioEnabled = audio;
        this.videoEnabled = video;
        this.inRoom = true;
        
        // Send ROOM_JOIN signal
        WebRTCSignal joinSignal = WebRTCSignal.newBuilder()
            .setType(WebRTCSignal.SignalType.ROOM_JOIN)
            .setFrom(localUsername)
            .setRoomId(roomId)
            .setAudioEnabled(audio)
            .setVideoEnabled(video)
            .setTimestamp(System.currentTimeMillis())
            .build();
        
        return signalingClient.sendSignal(joinSignal)
            .thenAccept(response -> {
                System.out.println("[GroupCallManager] Room join signal sent successfully");
            })
            .exceptionally(ex -> {
                System.err.printf("[GroupCallManager] Failed to join room: %s%n", ex.getMessage());
                inRoom = false;
                return null;
            });
    }
    
    /**
     * Leave current room
     */
    public CompletableFuture<Void> leaveRoom() {
        if (!inRoom) {
            System.out.println("[GroupCallManager] Not in a room");
            return CompletableFuture.completedFuture(null);
        }
        
        System.out.printf("[GroupCallManager] Leaving room: %s%n", currentRoomId);
        
        // Send ROOM_LEAVE signal
        WebRTCSignal leaveSignal = WebRTCSignal.newBuilder()
            .setType(WebRTCSignal.SignalType.ROOM_LEAVE)
            .setFrom(localUsername)
            .setRoomId(currentRoomId)
            .setTimestamp(System.currentTimeMillis())
            .build();
        
        return signalingClient.sendSignal(leaveSignal)
            .thenAccept(response -> {
                System.out.println("[GroupCallManager] Left room successfully");
                cleanup();
            })
            .exceptionally(ex -> {
                System.err.printf("[GroupCallManager] Error leaving room: %s%n", ex.getMessage());
                cleanup(); // Cleanup anyway
                return null;
            });
    }
    
    /**
     * Handle ROOM_JOINED signal (server sends peer list)
     */
    private void handleRoomJoined(WebRTCSignal signal) {
        System.out.println("[GroupCallManager] Room joined confirmation received");
        
        List<String> peerList = signal.getPeerListList();
        System.out.printf("[GroupCallManager] Current peers in room: %s%n", peerList);
        
        // Create peer connections for existing peers
        for (String peerUsername : peerList) {
            if (!peerUsername.equals(localUsername)) {
                createPeerConnection(peerUsername, true); // true = we initiate offer
            }
        }
        
        // Notify UI
        if (onRoomJoinedCallback != null) {
            onRoomJoinedCallback.run();
        }
    }
    
    /**
     * Handle ROOM_PEER_JOINED signal (new peer joined)
     */
    private void handlePeerJoined(WebRTCSignal signal) {
        String newPeerUsername = signal.getFrom();
        System.out.printf("[GroupCallManager] New peer joined: %s%n", newPeerUsername);
        
        if (newPeerUsername.equals(localUsername)) {
            // Ignore self
            return;
        }
        
        // Create peer connection (we DON'T initiate offer, new peer will)
        createPeerConnection(newPeerUsername, false);
        
        // Notify UI
        if (onPeerJoinedCallback != null) {
            onPeerJoinedCallback.onPeerJoined(newPeerUsername);
        }
    }
    
    /**
     * Handle ROOM_PEER_LEFT signal (peer left room)
     */
    private void handlePeerLeft(WebRTCSignal signal) {
        String peerUsername = signal.getFrom();
        System.out.printf("[GroupCallManager] Peer left: %s%n", peerUsername);
        
        // Close and remove peer connection
        WebRTCClient client = peerConnections.remove(peerUsername);
        if (client != null) {
            client.close();
            System.out.printf("[GroupCallManager] Connection to %s closed%n", peerUsername);
        }
        
        // Notify UI
        if (onPeerLeftCallback != null) {
            onPeerLeftCallback.onPeerLeft(peerUsername);
        }
    }
    
    /**
     * Handle MESH_OFFER signal (peer sending SDP offer)
     */
    private void handleMeshOffer(WebRTCSignal signal) {
        String peerUsername = signal.getFrom();
        String sdp = signal.getSdp();
        
        System.out.printf("[GroupCallManager] Received MESH_OFFER from %s%n", peerUsername);
        
        WebRTCClient client = peerConnections.get(peerUsername);
        if (client == null) {
            System.err.printf("[GroupCallManager] No connection found for peer: %s%n", peerUsername);
            return;
        }
        
        // Set remote SDP offer
        client.setRemoteDescription("offer", sdp);
        
        // Create and send answer
        client.createAnswer().thenAccept(answerSDP -> {
            System.out.printf("[GroupCallManager] Sending MESH_ANSWER to %s%n", peerUsername);
            
            WebRTCSignal answerSignal = WebRTCSignal.newBuilder()
                .setType(WebRTCSignal.SignalType.MESH_ANSWER)
                .setFrom(localUsername)
                .setTo(peerUsername)
                .setRoomId(currentRoomId)
                .setSdp(answerSDP)
                .setTimestamp(System.currentTimeMillis())
                .build();
            
            signalingClient.sendSignal(answerSignal);
        });
    }
    
    /**
     * Handle MESH_ANSWER signal (peer sending SDP answer)
     */
    private void handleMeshAnswer(WebRTCSignal signal) {
        String peerUsername = signal.getFrom();
        String sdp = signal.getSdp();
        
        System.out.printf("[GroupCallManager] Received MESH_ANSWER from %s%n", peerUsername);
        
        WebRTCClient client = peerConnections.get(peerUsername);
        if (client == null) {
            System.err.printf("[GroupCallManager] No connection found for peer: %s%n", peerUsername);
            return;
        }
        
        // Set remote SDP answer
        client.setRemoteDescription("answer", sdp);
    }
    
    /**
     * Handle MESH_ICE_CANDIDATE signal (peer sending ICE candidate)
     */
    private void handleMeshIceCandidate(WebRTCSignal signal) {
        String peerUsername = signal.getFrom();
        String candidate = signal.getCandidate();
        String sdpMid = signal.getSdpMid();
        int sdpMLineIndex = signal.getSdpMLineIndex();
        
        System.out.printf("[GroupCallManager] Received MESH_ICE_CANDIDATE from %s%n", peerUsername);
        
        WebRTCClient client = peerConnections.get(peerUsername);
        if (client == null) {
            System.err.printf("[GroupCallManager] No connection found for peer: %s%n", peerUsername);
            return;
        }
        
        // Add ICE candidate
        client.addIceCandidate(candidate, sdpMid, sdpMLineIndex);
    }
    
    /**
     * Create peer connection for a peer
     * @param peerUsername Peer's username
     * @param initiateOffer true if we should send offer, false if we wait for peer's offer
     */
    private void createPeerConnection(String peerUsername, boolean initiateOffer) {
        if (peerConnections.containsKey(peerUsername)) {
            System.out.printf("[GroupCallManager] Connection to %s already exists%n", peerUsername);
            return;
        }
        
        System.out.printf("[GroupCallManager] Creating peer connection to: %s (initiate=%b)%n", 
            peerUsername, initiateOffer);
        
        // Create unique callId for this peer connection
        String callId = UUID.randomUUID().toString();
        
        // Create WebRTCClient
        WebRTCClient client = new WebRTCClient(callId, peerUsername);
        peerConnections.put(peerUsername, client);
        
        // Setup callbacks
        setupPeerCallbacks(client, peerUsername);
        
        // Create peer connection
        client.createPeerConnection(audioEnabled, videoEnabled);
        
        // Add local tracks
        if (audioEnabled) {
            client.addAudioTrack();
        }
        if (videoEnabled) {
            client.addVideoTrack();
        }
        
        // If we initiate, create and send offer
        if (initiateOffer) {
            client.createOffer().thenAccept(offerSDP -> {
                System.out.printf("[GroupCallManager] Sending MESH_OFFER to %s%n", peerUsername);
                
                WebRTCSignal offerSignal = WebRTCSignal.newBuilder()
                    .setType(WebRTCSignal.SignalType.MESH_OFFER)
                    .setFrom(localUsername)
                    .setTo(peerUsername)
                    .setRoomId(currentRoomId)
                    .setSdp(offerSDP)
                    .setAudioEnabled(audioEnabled)
                    .setVideoEnabled(videoEnabled)
                    .setTimestamp(System.currentTimeMillis())
                    .build();
                
                signalingClient.sendSignal(offerSignal);
            });
        }
    }
    
    /**
     * Setup callbacks for a peer connection
     */
    private void setupPeerCallbacks(WebRTCClient client, String peerUsername) {
        // ICE candidate callback
        client.setOnIceCandidateCallback(candidate -> {
            System.out.printf("[GroupCallManager] Sending MESH_ICE_CANDIDATE to %s%n", peerUsername);
            
            WebRTCSignal iceSignal = WebRTCSignal.newBuilder()
                .setType(WebRTCSignal.SignalType.MESH_ICE_CANDIDATE)
                .setFrom(localUsername)
                .setTo(peerUsername)
                .setRoomId(currentRoomId)
                .setCandidate(candidate.sdp)
                .setSdpMid(candidate.sdpMid)
                .setSdpMLineIndex(candidate.sdpMLineIndex)
                .setTimestamp(System.currentTimeMillis())
                .build();
            
            signalingClient.sendSignal(iceSignal);
        });
        
        // Connection established callback
        client.setOnConnectionEstablishedCallback(() -> {
            System.out.printf("[GroupCallManager] Connection established with %s%n", peerUsername);
        });
        
        // Connection closed callback
        client.setOnConnectionClosedCallback(() -> {
            System.out.printf("[GroupCallManager] Connection closed with %s%n", peerUsername);
            peerConnections.remove(peerUsername);
        });
        
        // Remote track callback
        client.setOnRemoteTrackCallback(track -> {
            System.out.printf("[GroupCallManager] Remote track from %s: %s (%s)%n", 
                peerUsername, track.getId(), track.getKind());
            
            // Notify UI
            if (onRemoteTrackCallback != null) {
                onRemoteTrackCallback.onRemoteTrack(peerUsername, track);
            }
        });
    }
    
    /**
     * Cleanup all connections
     */
    private void cleanup() {
        System.out.println("[GroupCallManager] Cleaning up all connections...");
        
        // Close all peer connections
        for (Map.Entry<String, WebRTCClient> entry : peerConnections.entrySet()) {
            System.out.printf("[GroupCallManager] Closing connection to %s%n", entry.getKey());
            entry.getValue().close();
        }
        
        peerConnections.clear();
        inRoom = false;
        currentRoomId = null;
        
        // Notify UI
        if (onRoomLeftCallback != null) {
            onRoomLeftCallback.run();
        }
        
        System.out.println("[GroupCallManager] Cleanup complete");
    }
    
    /**
     * Toggle local audio
     */
    public void toggleAudio(boolean enabled) {
        this.audioEnabled = enabled;
        
        // Toggle audio for all peer connections
        for (WebRTCClient client : peerConnections.values()) {
            client.toggleAudio(enabled);
        }
        
        System.out.printf("[GroupCallManager] Audio %s for all peers%n", 
            enabled ? "enabled" : "muted");
    }
    
    /**
     * Toggle local video
     */
    public void toggleVideo(boolean enabled) {
        this.videoEnabled = enabled;
        
        // Toggle video for all peer connections
        for (WebRTCClient client : peerConnections.values()) {
            client.toggleVideo(enabled);
        }
        
        System.out.printf("[GroupCallManager] Video %s for all peers%n", 
            enabled ? "enabled" : "disabled");
    }
    
    /**
     * Get local video track (for self-preview)
     */
    public VideoTrack getLocalVideoTrack() {
        // Get track from any peer connection (all have same local track)
        for (WebRTCClient client : peerConnections.values()) {
            VideoTrack track = client.getLocalVideoTrack();
            if (track != null) {
                return track;
            }
        }
        return null;
    }
    
    /**
     * Get list of connected peer usernames
     */
    public List<String> getConnectedPeers() {
        return new ArrayList<>(peerConnections.keySet());
    }
    
    /**
     * Get peer connection for specific peer
     */
    public WebRTCClient getPeerConnection(String peerUsername) {
        return peerConnections.get(peerUsername);
    }
    
    // ===============================
    // Callback Setters
    // ===============================
    
    public void setOnRoomJoinedCallback(Runnable callback) {
        this.onRoomJoinedCallback = callback;
    }
    
    public void setOnRoomLeftCallback(Runnable callback) {
        this.onRoomLeftCallback = callback;
    }
    
    public void setOnPeerJoinedCallback(PeerJoinedCallback callback) {
        this.onPeerJoinedCallback = callback;
    }
    
    public void setOnPeerLeftCallback(PeerLeftCallback callback) {
        this.onPeerLeftCallback = callback;
    }
    
    public void setOnRemoteTrackCallback(RemoteTrackCallback callback) {
        this.onRemoteTrackCallback = callback;
    }
    
    // ===============================
    // Callback Interfaces
    // ===============================
    
    @FunctionalInterface
    public interface PeerJoinedCallback {
        void onPeerJoined(String peerUsername);
    }
    
    @FunctionalInterface
    public interface PeerLeftCallback {
        void onPeerLeft(String peerUsername);
    }
    
    @FunctionalInterface
    public interface RemoteTrackCallback {
        void onRemoteTrack(String peerUsername, MediaStreamTrack track);
    }
    
    // ===============================
    // Getters
    // ===============================
    
    public boolean isInRoom() {
        return inRoom;
    }
    
    public String getCurrentRoomId() {
        return currentRoomId;
    }
    
    public int getPeerCount() {
        return peerConnections.size();
    }
}
