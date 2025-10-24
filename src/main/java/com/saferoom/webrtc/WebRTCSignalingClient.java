package com.saferoom.webrtc;

import com.saferoom.grpc.SafeRoomProto;
import com.saferoom.grpc.SafeRoomProto.WebRTCSignal;
import com.saferoom.grpc.SafeRoomProto.WebRTCSignal.SignalType;
import com.saferoom.grpc.SafeRoomProto.WebRTCResponse;
import com.saferoom.grpc.UDPHoleGrpc;
import com.saferoom.server.SafeRoomServer;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * WebRTC Signaling Client
 * Handles gRPC communication with server for WebRTC signaling
 * Compatible with UDPHoleImpl.java and stun.proto
 */
public class WebRTCSignalingClient {
    
    private static final String SERVER_HOST = SafeRoomServer.ServerIP;
    private static final int SERVER_PORT = SafeRoomServer.grpcPort;
    
    private ManagedChannel channel;
    private UDPHoleGrpc.UDPHoleBlockingStub blockingStub;
    private UDPHoleGrpc.UDPHoleStub asyncStub;
    
    private StreamObserver<WebRTCSignal> signalingStreamOut;
    private boolean streamActive = false;
    
    private String myUsername;
    
    // Callbacks for incoming signals
    private Consumer<WebRTCSignal> onIncomingSignalCallback;
    
    /**
     * Constructor
     */
    public WebRTCSignalingClient(String username) {
        this.myUsername = username;
        initializeChannel();
    }
    
    /**
     * Initialize gRPC channel
     */
    private void initializeChannel() {
        try {
            System.out.printf("[SignalingClient] 🔌 Connecting to %s:%d%n", SERVER_HOST, SERVER_PORT);
            
            channel = ManagedChannelBuilder.forAddress(SERVER_HOST, SERVER_PORT)
                .usePlaintext()
                .build();
            
            blockingStub = UDPHoleGrpc.newBlockingStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS);
            
            asyncStub = UDPHoleGrpc.newStub(channel);
            
            System.out.println("[SignalingClient] ✅ Channel initialized");
            
        } catch (Exception e) {
            System.err.printf("[SignalingClient] ❌ Failed to initialize channel: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }
    
    // ===============================
    // One-way Signal Sending
    // ===============================
    
    /**
     * Send CALL_REQUEST to initiate a call
     */
    public CompletableFuture<String> sendCallRequest(String targetUsername, boolean audioEnabled, boolean videoEnabled) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        try {
            System.out.printf("[SignalingClient] 📞 Sending call request to %s (audio=%b, video=%b)%n", 
                targetUsername, audioEnabled, videoEnabled);
            
            // Generate unique call ID
            String callId = java.util.UUID.randomUUID().toString();
            
            WebRTCSignal signal = WebRTCSignal.newBuilder()
                .setType(SignalType.CALL_REQUEST)
                .setFrom(myUsername)
                .setTo(targetUsername)
                .setCallId(callId)
                .setAudioEnabled(audioEnabled)
                .setVideoEnabled(videoEnabled)
                .setTimestamp(System.currentTimeMillis())
                .build();
            
            // 🔧 FIX: Use stream instead of blocking stub!
            if (streamActive && signalingStreamOut != null) {
                System.out.printf("[SignalingClient] 📤 Sending CALL_REQUEST via stream (callId: %s)%n", callId);
                signalingStreamOut.onNext(signal);
                future.complete(callId);
            } else {
                System.err.println("[SignalingClient] ❌ Stream not active, falling back to unary RPC");
                WebRTCResponse response = blockingStub.sendWebRTCSignal(signal);
                
                if (response.getSuccess()) {
                    String responseCallId = response.getCallId();
                    System.out.printf("[SignalingClient] ✅ Call request sent (unary), callId: %s%n", responseCallId);
                    future.complete(responseCallId);
                } else {
                    System.err.printf("[SignalingClient] ❌ Call request failed: %s%n", response.getMessage());
                    future.completeExceptionally(new Exception(response.getMessage()));
                }
            }
            
        } catch (Exception e) {
            System.err.printf("[SignalingClient] ❌ Error sending call request: %s%n", e.getMessage());
            future.completeExceptionally(e);
        }
        
        return future;
    }
    
    /**
     * Send CALL_ACCEPT to accept incoming call
     */
    public boolean sendCallAccept(String callId, String targetUsername) {
        try {
            System.out.printf("[SignalingClient] ✅ Accepting call: %s%n", callId);
            
            WebRTCSignal signal = WebRTCSignal.newBuilder()
                .setType(SignalType.CALL_ACCEPT)
                .setFrom(myUsername)
                .setTo(targetUsername)
                .setCallId(callId)
                .setTimestamp(System.currentTimeMillis())
                .build();
            
            // 🔧 FIX: Use stream instead of blocking stub!
            if (streamActive && signalingStreamOut != null) {
                System.out.println("[SignalingClient] 📤 Sending CALL_ACCEPT via stream");
                signalingStreamOut.onNext(signal);
                return true;
            } else {
                System.err.println("[SignalingClient] ❌ Stream not active, falling back to unary RPC");
                WebRTCResponse response = blockingStub.sendWebRTCSignal(signal);
                
                if (response.getSuccess()) {
                    System.out.println("[SignalingClient] ✅ Call accepted successfully (unary)");
                    return true;
                } else {
                    System.err.printf("[SignalingClient] ❌ Accept failed: %s%n", response.getMessage());
                    return false;
                }
            }
            
        } catch (Exception e) {
            System.err.printf("[SignalingClient] ❌ Error accepting call: %s%n", e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Send CALL_REJECT to reject incoming call
     */
    public boolean sendCallReject(String callId, String targetUsername) {
        try {
            System.out.printf("[SignalingClient] ❌ Rejecting call: %s%n", callId);
            
            WebRTCSignal signal = WebRTCSignal.newBuilder()
                .setType(SignalType.CALL_REJECT)
                .setFrom(myUsername)
                .setTo(targetUsername)
                .setCallId(callId)
                .setTimestamp(System.currentTimeMillis())
                .build();
            
            // 🔧 FIX: Use stream instead of blocking stub!
            if (streamActive && signalingStreamOut != null) {
                System.out.println("[SignalingClient] 📤 Sending CALL_REJECT via stream");
                signalingStreamOut.onNext(signal);
                return true;
            } else {
                System.err.println("[SignalingClient] ❌ Stream not active, falling back to unary RPC");
                WebRTCResponse response = blockingStub.sendWebRTCSignal(signal);
                return response.getSuccess();
            }
            
        } catch (Exception e) {
            System.err.printf("[SignalingClient] ❌ Error rejecting call: %s%n", e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Send CALL_CANCEL to cancel outgoing call
     */
    public boolean sendCallCancel(String callId, String targetUsername) {
        try {
            System.out.printf("[SignalingClient] 🚫 Cancelling call: %s%n", callId);
            
            WebRTCSignal signal = WebRTCSignal.newBuilder()
                .setType(SignalType.CALL_CANCEL)
                .setFrom(myUsername)
                .setTo(targetUsername)
                .setCallId(callId)
                .setTimestamp(System.currentTimeMillis())
                .build();
            
            // 🔧 FIX: Use stream instead of blocking stub!
            if (streamActive && signalingStreamOut != null) {
                System.out.println("[SignalingClient] 📤 Sending CALL_CANCEL via stream");
                signalingStreamOut.onNext(signal);
                return true;
            } else {
                System.err.println("[SignalingClient] ❌ Stream not active, falling back to unary RPC");
                WebRTCResponse response = blockingStub.sendWebRTCSignal(signal);
                return response.getSuccess();
            }
            
        } catch (Exception e) {
            System.err.printf("[SignalingClient] ❌ Error cancelling call: %s%n", e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Send CALL_END to end active call
     */
    public boolean sendCallEnd(String callId, String targetUsername) {
        try {
            System.out.printf("[SignalingClient] 📴 Ending call: %s%n", callId);
            
            WebRTCSignal signal = WebRTCSignal.newBuilder()
                .setType(SignalType.CALL_END)
                .setFrom(myUsername)
                .setTo(targetUsername)
                .setCallId(callId)
                .setTimestamp(System.currentTimeMillis())
                .build();
            
            // 🔧 FIX: Use stream instead of blocking stub!
            if (streamActive && signalingStreamOut != null) {
                System.out.println("[SignalingClient] 📤 Sending CALL_END via stream");
                signalingStreamOut.onNext(signal);
                return true;
            } else {
                System.err.println("[SignalingClient] ❌ Stream not active, falling back to unary RPC");
                WebRTCResponse response = blockingStub.sendWebRTCSignal(signal);
                return response.getSuccess();
            }
            
        } catch (Exception e) {
            System.err.printf("[SignalingClient] ❌ Error ending call: %s%n", e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Send SDP OFFER
     */
    public boolean sendOffer(String callId, String targetUsername, String sdp) {
        try {
            System.out.printf("[SignalingClient] 📤 Sending SDP offer to %s%n", targetUsername);
            
            WebRTCSignal signal = WebRTCSignal.newBuilder()
                .setType(SignalType.OFFER)
                .setFrom(myUsername)
                .setTo(targetUsername)
                .setCallId(callId)
                .setSdp(sdp)
                .setTimestamp(System.currentTimeMillis())
                .build();
            
            // 🔧 FIX: Use stream for real-time signaling!
            if (streamActive && signalingStreamOut != null) {
                System.out.println("[SignalingClient] 📤 Sending OFFER via stream");
                signalingStreamOut.onNext(signal);
                return true;
            } else {
                System.err.println("[SignalingClient] ❌ Stream not active, falling back to unary RPC");
                WebRTCResponse response = blockingStub.sendWebRTCSignal(signal);
                
                if (response.getSuccess()) {
                    System.out.println("[SignalingClient] ✅ Offer sent successfully (unary)");
                    return true;
                } else {
                    System.err.printf("[SignalingClient] ❌ Offer failed: %s%n", response.getMessage());
                    return false;
                }
            }
            
        } catch (Exception e) {
            System.err.printf("[SignalingClient] ❌ Error sending offer: %s%n", e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Send SDP ANSWER
     */
    public boolean sendAnswer(String callId, String targetUsername, String sdp) {
        try {
            System.out.printf("[SignalingClient] 📥 Sending SDP answer to %s%n", targetUsername);
            
            WebRTCSignal signal = WebRTCSignal.newBuilder()
                .setType(SignalType.ANSWER)
                .setFrom(myUsername)
                .setTo(targetUsername)
                .setCallId(callId)
                .setSdp(sdp)
                .setTimestamp(System.currentTimeMillis())
                .build();
            
            // 🔧 FIX: Use stream for real-time signaling!
            if (streamActive && signalingStreamOut != null) {
                System.out.println("[SignalingClient] 📤 Sending ANSWER via stream");
                signalingStreamOut.onNext(signal);
                return true;
            } else {
                System.err.println("[SignalingClient] ❌ Stream not active, falling back to unary RPC");
                WebRTCResponse response = blockingStub.sendWebRTCSignal(signal);
                
                if (response.getSuccess()) {
                    System.out.println("[SignalingClient] ✅ Answer sent successfully (unary)");
                    return true;
                } else {
                    System.err.printf("[SignalingClient] ❌ Answer failed: %s%n", response.getMessage());
                    return false;
                }
            }
            
        } catch (Exception e) {
            System.err.printf("[SignalingClient] ❌ Error sending answer: %s%n", e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Send ICE CANDIDATE
     */
    public boolean sendIceCandidate(String callId, String targetUsername, String candidate, String sdpMid, int sdpMLineIndex) {
        try {
            System.out.printf("[SignalingClient] 🧊 Sending ICE candidate to %s%n", targetUsername);
            
            WebRTCSignal signal = WebRTCSignal.newBuilder()
                .setType(SignalType.ICE_CANDIDATE)
                .setFrom(myUsername)
                .setTo(targetUsername)
                .setCallId(callId)
                .setCandidate(candidate)
                .setSdpMid(sdpMid)
                .setSdpMLineIndex(sdpMLineIndex)
                .setTimestamp(System.currentTimeMillis())
                .build();
            
            // 🔧 FIX: Use stream for real-time ICE signaling!
            if (streamActive && signalingStreamOut != null) {
                signalingStreamOut.onNext(signal);
                return true;
            } else {
                System.err.println("[SignalingClient] ❌ Stream not active for ICE");
                WebRTCResponse response = blockingStub.sendWebRTCSignal(signal);
                return response.getSuccess();
            }
            
        } catch (Exception e) {
            System.err.printf("[SignalingClient] ❌ Error sending ICE candidate: %s%n", e.getMessage());
            return false;
        }
    }
    
    // ===============================
    // Bi-directional Streaming
    // ===============================
    
    /**
     * Start signaling stream (bi-directional)
     * This keeps a persistent connection for real-time signaling
     */
    public void startSignalingStream() {
        if (streamActive) {
            System.out.println("[SignalingClient] ⚠️ Stream already active");
            return;
        }
        
        try {
            System.out.println("[SignalingClient] 🔌 Starting signaling stream...");
            
            StreamObserver<WebRTCSignal> streamIn = new StreamObserver<WebRTCSignal>() {
                @Override
                public void onNext(WebRTCSignal signal) {
                    System.out.printf("[SignalingClient] 📨 Received signal: %s from %s%n", 
                        signal.getType(), signal.getFrom());
                    
                    // Forward to callback
                    if (onIncomingSignalCallback != null) {
                        onIncomingSignalCallback.accept(signal);
                    }
                }
                
                @Override
                public void onError(Throwable t) {
                    System.err.printf("[SignalingClient] ❌ Stream error: %s%n", t.getMessage());
                    streamActive = false;
                    signalingStreamOut = null;
                }
                
                @Override
                public void onCompleted() {
                    System.out.println("[SignalingClient] 🔌 Stream completed");
                    streamActive = false;
                    signalingStreamOut = null;
                }
            };
            
            signalingStreamOut = asyncStub.streamWebRTCSignals(streamIn);
            streamActive = true;
            
            // Send initial registration signal
            WebRTCSignal registrationSignal = WebRTCSignal.newBuilder()
                .setType(SignalType.REGISTRATION)
                .setFrom(myUsername)
                .setTo("")  // Empty for registration
                .setTimestamp(System.currentTimeMillis())
                .build();
            
            signalingStreamOut.onNext(registrationSignal);
            
            System.out.println("[SignalingClient] ✅ Signaling stream started");
            
        } catch (Exception e) {
            System.err.printf("[SignalingClient] ❌ Failed to start stream: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Stop signaling stream
     */
    public void stopSignalingStream() {
        if (!streamActive || signalingStreamOut == null) {
            return;
        }
        
        try {
            System.out.println("[SignalingClient] 🔌 Stopping signaling stream...");
            signalingStreamOut.onCompleted();
            streamActive = false;
            signalingStreamOut = null;
            System.out.println("[SignalingClient] ✅ Stream stopped");
        } catch (Exception e) {
            System.err.printf("[SignalingClient] ❌ Error stopping stream: %s%n", e.getMessage());
        }
    }
    
    /**
     * Send signal through stream (if active)
     */
    public boolean sendSignalViaStream(WebRTCSignal signal) {
        if (!streamActive || signalingStreamOut == null) {
            System.err.println("[SignalingClient] ❌ Stream not active");
            return false;
        }
        
        try {
            signalingStreamOut.onNext(signal);
            return true;
        } catch (Exception e) {
            System.err.printf("[SignalingClient] ❌ Error sending via stream: %s%n", e.getMessage());
            return false;
        }
    }
    
    // ===============================
    // Callback Setter
    // ===============================
    
    /**
     * Set callback for incoming signals
     */
    public void setOnIncomingSignalCallback(Consumer<WebRTCSignal> callback) {
        this.onIncomingSignalCallback = callback;
    }
    
    // ===============================
    // Channel Management
    // ===============================
    
    /**
     * Check if stream is active
     */
    public boolean isStreamActive() {
        return streamActive;
    }
    
    /**
     * Shutdown client
     */
    public void shutdown() {
        try {
            System.out.println("[SignalingClient] 🔌 Shutting down...");
            
            stopSignalingStream();
            
            if (channel != null && !channel.isShutdown()) {
                channel.shutdown();
                channel.awaitTermination(5, TimeUnit.SECONDS);
            }
            
            System.out.println("[SignalingClient] ✅ Shutdown complete");
            
        } catch (Exception e) {
            System.err.printf("[SignalingClient] ❌ Error during shutdown: %s%n", e.getMessage());
        }
    }
}
