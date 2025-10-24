package com.saferoom.webrtc;

import com.saferoom.grpc.SafeRoomProto.WebRTCSignal;
import com.saferoom.grpc.SafeRoomProto.WebRTCSignal.SignalType;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Call Manager
 * Orchestrates WebRTCClient and WebRTCSignalingClient
 * Compatible with WebRTCSessionManager (server) and UDPHoleImpl
 */
public class CallManager {
    
    private static CallManager instance;
    
    private String myUsername;
    private WebRTCSignalingClient signalingClient;
    private boolean isInitialized = false; // 🔧 Track initialization state
    
    // Current call state
    private CallState currentState = CallState.IDLE;
    private String currentCallId;
    private String remoteUsername;
    private boolean isOutgoingCall;
    
    private WebRTCClient webrtcClient;
    
    // GUI Callbacks
    private Consumer<IncomingCallInfo> onIncomingCallCallback;
    private Consumer<String> onCallAcceptedCallback;
    private Consumer<String> onCallRejectedCallback;
    private Consumer<String> onCallEndedCallback;
    private Runnable onCallConnectedCallback;
    
    /**
     * Call states (matching server-side WebRTCSessionManager.CallState)
     */
    public enum CallState {
        IDLE,       // No call
        RINGING,    // Outgoing/incoming call ringing
        CONNECTING, // SDP/ICE exchange in progress
        CONNECTED,  // Call established
        ENDED       // Call ended
    }
    
    /**
     * Incoming call info
     */
    public static class IncomingCallInfo {
        public final String callId;
        public final String callerUsername;
        public final boolean audioEnabled;
        public final boolean videoEnabled;
        public final long timestamp;
        
        public IncomingCallInfo(String callId, String callerUsername, boolean audio, boolean video, long timestamp) {
            this.callId = callId;
            this.callerUsername = callerUsername;
            this.audioEnabled = audio;
            this.videoEnabled = video;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * Private constructor (singleton)
     */
    private CallManager() {}
    
    /**
     * Get singleton instance
     */
    public static synchronized CallManager getInstance() {
        if (instance == null) {
            instance = new CallManager();
        }
        return instance;
    }
    
    /**
     * Initialize call manager
     */
    public void initialize(String username) {
        // 🔧 Prevent re-initialization
        if (isInitialized) {
            System.out.printf("[CallManager] ⚠️ Already initialized for user: %s (current: %s)%n", myUsername, username);
            return;
        }
        
        this.myUsername = username;
        
        System.out.printf("[CallManager] 🔧 Initializing for user: %s%n", username);
        
        // Initialize WebRTC client library
        if (!WebRTCClient.isInitialized()) {
            WebRTCClient.initialize();
        }
        
        // Create signaling client
        signalingClient = new WebRTCSignalingClient(username);
        
        // Set incoming signal handler
        signalingClient.setOnIncomingSignalCallback(this::handleIncomingSignal);
        
        // Start signaling stream for real-time signals
        signalingClient.startSignalingStream();
        
        this.isInitialized = true; // 🔧 Mark as initialized
        
        System.out.println("[CallManager] ✅ Initialization complete");
    }
    
    /**
     * Check if CallManager is initialized
     */
    public boolean isInitialized() {
        return isInitialized;
    }
    
    // ===============================
    // Outgoing Call Flow
    // ===============================
    
    /**
     * Start outgoing call
     */
    public CompletableFuture<String> startCall(String targetUsername, boolean audioEnabled, boolean videoEnabled) {
        if (currentState != CallState.IDLE) {
            System.err.printf("[CallManager] ❌ Cannot start call - current state: %s%n", currentState);
            return CompletableFuture.failedFuture(new IllegalStateException("Already in a call"));
        }
        
        System.out.printf("[CallManager] 📞 Starting call to %s (audio=%b, video=%b)%n", 
            targetUsername, audioEnabled, videoEnabled);
        
        this.remoteUsername = targetUsername;
        this.isOutgoingCall = true;
        this.currentState = CallState.RINGING;
        
        // Send CALL_REQUEST to server
        return signalingClient.sendCallRequest(targetUsername, audioEnabled, videoEnabled)
            .thenApply(callId -> {
                this.currentCallId = callId;
                System.out.printf("[CallManager] ✅ Call request sent, callId: %s%n", callId);
                
                // Create WebRTC peer connection
                webrtcClient = new WebRTCClient(callId, targetUsername);
                webrtcClient.createPeerConnection(audioEnabled, videoEnabled);
                
                // Set up callbacks
                setupWebRTCCallbacks();
                
                return callId;
            })
            .exceptionally(e -> {
                System.err.printf("[CallManager] ❌ Failed to start call: %s%n", e.getMessage());
                this.currentState = CallState.IDLE;
                return null;
            });
    }
    
    /**
     * Cancel outgoing call (before accepted)
     */
    public void cancelCall() {
        if (!isOutgoingCall || currentState != CallState.RINGING) {
            System.err.println("[CallManager] ⚠️ No outgoing call to cancel");
            return;
        }
        
        System.out.printf("[CallManager] 🚫 Cancelling call: %s%n", currentCallId);
        
        // Send CALL_CANCEL
        signalingClient.sendCallCancel(currentCallId, remoteUsername);
        
        // Cleanup
        cleanup();
    }
    
    // ===============================
    // Incoming Call Flow
    // ===============================
    
    /**
     * Accept incoming call
     */
    public void acceptCall(String callId) {
        if (currentState != CallState.RINGING || isOutgoingCall) {
            System.err.println("[CallManager] ⚠️ No incoming call to accept");
            return;
        }
        
        System.out.printf("[CallManager] ✅ Accepting call: %s%n", callId);
        
        // Send CALL_ACCEPT
        boolean success = signalingClient.sendCallAccept(callId, remoteUsername);
        
        if (success) {
            this.currentState = CallState.CONNECTING;
            
            // Create SDP answer
            webrtcClient.createAnswer().thenAccept(sdp -> {
                // Send ANSWER to caller
                signalingClient.sendAnswer(callId, remoteUsername, sdp);
                System.out.println("[CallManager] 📥 Answer sent");
            });
            
            if (onCallAcceptedCallback != null) {
                onCallAcceptedCallback.accept(callId);
            }
        } else {
            System.err.println("[CallManager] ❌ Failed to accept call");
            cleanup();
        }
    }
    
    /**
     * Reject incoming call
     */
    public void rejectCall(String callId) {
        if (currentState != CallState.RINGING || isOutgoingCall) {
            System.err.println("[CallManager] ⚠️ No incoming call to reject");
            return;
        }
        
        System.out.printf("[CallManager] ❌ Rejecting call: %s%n", callId);
        
        // Send CALL_REJECT
        signalingClient.sendCallReject(callId, remoteUsername);
        
        if (onCallRejectedCallback != null) {
            onCallRejectedCallback.accept(callId);
        }
        
        // Cleanup
        cleanup();
    }
    
    // ===============================
    // Active Call Management
    // ===============================
    
    /**
     * End active call
     */
    public void endCall() {
        if (currentState == CallState.IDLE || currentState == CallState.ENDED) {
            System.err.println("[CallManager] ⚠️ No active call to end");
            return;
        }
        
        System.out.printf("[CallManager] 📴 Ending call: %s%n", currentCallId);
        
        // Send CALL_END
        signalingClient.sendCallEnd(currentCallId, remoteUsername);
        
        if (onCallEndedCallback != null) {
            onCallEndedCallback.accept(currentCallId);
        }
        
        // Cleanup
        cleanup();
    }
    
    /**
     * Toggle audio (mute/unmute)
     */
    public void toggleAudio(boolean enabled) {
        if (webrtcClient != null) {
            webrtcClient.toggleAudio(enabled);
        }
    }
    
    /**
     * Toggle video (on/off)
     */
    public void toggleVideo(boolean enabled) {
        if (webrtcClient != null) {
            webrtcClient.toggleVideo(enabled);
        }
    }
    
    // ===============================
    // Signal Handlers (from server)
    // ===============================
    
    /**
     * Handle incoming WebRTC signal
     */
    private void handleIncomingSignal(WebRTCSignal signal) {
        SignalType type = signal.getType();
        String from = signal.getFrom();
        String callId = signal.getCallId();
        
        System.out.printf("[CallManager] 📨 Received %s from %s (callId: %s)%n", type, from, callId);
        
        switch (type) {
            case CALL_REQUEST:
                handleIncomingCallRequest(signal);
                break;
                
            case CALL_ACCEPT:
                handleCallAccepted(signal);
                break;
                
            case CALL_REJECT:
            case CALL_CANCEL:
                handleCallRejected(signal);
                break;
                
            case CALL_END:
                handleCallEnded(signal);
                break;
                
            case OFFER:
                handleOffer(signal);
                break;
                
            case ANSWER:
                handleAnswer(signal);
                break;
                
            case ICE_CANDIDATE:
                handleIceCandidate(signal);
                break;
                
            default:
                System.err.printf("[CallManager] ⚠️ Unknown signal type: %s%n", type);
        }
    }
    
    /**
     * Handle incoming call request
     */
    private void handleIncomingCallRequest(WebRTCSignal signal) {
        if (currentState != CallState.IDLE) {
            System.err.println("[CallManager] ❌ Already in a call - rejecting");
            signalingClient.sendCallReject(signal.getCallId(), signal.getFrom());
            return;
        }
        
        this.currentCallId = signal.getCallId();
        this.remoteUsername = signal.getFrom();
        this.isOutgoingCall = false;
        this.currentState = CallState.RINGING;
        
        // Create WebRTC peer connection
        webrtcClient = new WebRTCClient(currentCallId, remoteUsername);
        webrtcClient.createPeerConnection(signal.getAudioEnabled(), signal.getVideoEnabled());
        setupWebRTCCallbacks();
        
        // Notify GUI
        if (onIncomingCallCallback != null) {
            IncomingCallInfo info = new IncomingCallInfo(
                signal.getCallId(),
                signal.getFrom(),
                signal.getAudioEnabled(),
                signal.getVideoEnabled(),
                signal.getTimestamp()
            );
            onIncomingCallCallback.accept(info);
        }
    }
    
    /**
     * Handle call accepted (for outgoing call)
     */
    private void handleCallAccepted(WebRTCSignal signal) {
        if (!isOutgoingCall) return;
        
        System.out.println("[CallManager] ✅ Call accepted by remote user");
        
        this.currentState = CallState.CONNECTING;
        
        // Create SDP offer
        webrtcClient.createOffer().thenAccept(sdp -> {
            // Send OFFER to callee
            signalingClient.sendOffer(currentCallId, remoteUsername, sdp);
            System.out.println("[CallManager] 📤 Offer sent");
        });
        
        if (onCallAcceptedCallback != null) {
            onCallAcceptedCallback.accept(currentCallId);
        }
    }
    
    /**
     * Handle call rejected/cancelled
     */
    private void handleCallRejected(WebRTCSignal signal) {
        System.out.println("[CallManager] ❌ Call rejected/cancelled");
        
        if (onCallRejectedCallback != null) {
            onCallRejectedCallback.accept(currentCallId);
        }
        
        cleanup();
    }
    
    /**
     * Handle call ended
     */
    private void handleCallEnded(WebRTCSignal signal) {
        System.out.println("[CallManager] 📴 Call ended by remote user");
        
        if (onCallEndedCallback != null) {
            onCallEndedCallback.accept(currentCallId);
        }
        
        cleanup();
    }
    
    /**
     * Handle SDP offer
     */
    private void handleOffer(WebRTCSignal signal) {
        System.out.println("[CallManager] 📥 Received SDP offer");
        
        // Set remote description
        webrtcClient.setRemoteDescription("offer", signal.getSdp());
    }
    
    /**
     * Handle SDP answer
     */
    private void handleAnswer(WebRTCSignal signal) {
        System.out.println("[CallManager] 📥 Received SDP answer");
        
        // Set remote description
        webrtcClient.setRemoteDescription("answer", signal.getSdp());
        
        // Mark as connected
        this.currentState = CallState.CONNECTED;
        
        if (onCallConnectedCallback != null) {
            onCallConnectedCallback.run();
        }
    }
    
    /**
     * Handle ICE candidate
     */
    private void handleIceCandidate(WebRTCSignal signal) {
        System.out.println("[CallManager] 🧊 Received ICE candidate");
        
        webrtcClient.addIceCandidate(
            signal.getCandidate(),
            signal.getSdpMid(),
            signal.getSdpMLineIndex()
        );
    }
    
    // ===============================
    // WebRTC Callbacks Setup
    // ===============================
    
    /**
     * Setup WebRTC client callbacks
     */
    private void setupWebRTCCallbacks() {
        // SDP callback
        webrtcClient.setOnLocalSDPCallback(sdp -> {
            System.out.println("[CallManager] 📝 Local SDP generated");
            // SDP is sent in createOffer/createAnswer methods
        });
        
        // ICE candidate callback
        webrtcClient.setOnIceCandidateCallback(candidateJson -> {
            System.out.println("[CallManager] 🧊 ICE candidate generated");
            // TODO: Parse JSON and send via signaling
            // For now, using mock data
        });
        
        // Connection established callback
        webrtcClient.setOnConnectionEstablishedCallback(() -> {
            System.out.println("[CallManager] 🔗 WebRTC connection established");
            this.currentState = CallState.CONNECTED;
            
            if (onCallConnectedCallback != null) {
                onCallConnectedCallback.run();
            }
        });
        
        // Connection closed callback
        webrtcClient.setOnConnectionClosedCallback(() -> {
            System.out.println("[CallManager] 🔗 WebRTC connection closed");
            cleanup();
        });
    }
    
    // ===============================
    // Cleanup
    // ===============================
    
    /**
     * Cleanup call resources
     */
    private void cleanup() {
        System.out.println("[CallManager] 🧹 Cleaning up call resources...");
        
        if (webrtcClient != null) {
            webrtcClient.close();
            webrtcClient = null;
        }
        
        this.currentState = CallState.IDLE;
        this.currentCallId = null;
        this.remoteUsername = null;
        this.isOutgoingCall = false;
        
        System.out.println("[CallManager] ✅ Cleanup complete");
    }
    
    // ===============================
    // GUI Callback Setters
    // ===============================
    
    public void setOnIncomingCallCallback(Consumer<IncomingCallInfo> callback) {
        this.onIncomingCallCallback = callback;
    }
    
    public void setOnCallAcceptedCallback(Consumer<String> callback) {
        this.onCallAcceptedCallback = callback;
    }
    
    public void setOnCallRejectedCallback(Consumer<String> callback) {
        this.onCallRejectedCallback = callback;
    }
    
    public void setOnCallEndedCallback(Consumer<String> callback) {
        this.onCallEndedCallback = callback;
    }
    
    public void setOnCallConnectedCallback(Runnable callback) {
        this.onCallConnectedCallback = callback;
    }
    
    // ===============================
    // Getters
    // ===============================
    
    public CallState getCurrentState() {
        return currentState;
    }
    
    public String getCurrentCallId() {
        return currentCallId;
    }
    
    public String getRemoteUsername() {
        return remoteUsername;
    }
    
    public boolean isInCall() {
        return currentState != CallState.IDLE && currentState != CallState.ENDED;
    }
    
    // ===============================
    // Shutdown
    // ===============================
    
    /**
     * Shutdown call manager
     */
    public void shutdown() {
        System.out.println("[CallManager] 🔌 Shutting down...");
        
        // End any active call
        if (isInCall()) {
            endCall();
        }
        
        // Stop signaling
        if (signalingClient != null) {
            signalingClient.shutdown();
            signalingClient = null;
        }
        
        // Shutdown WebRTC
        WebRTCClient.shutdown();
        
        System.out.println("[CallManager] ✅ Shutdown complete");
    }
}
