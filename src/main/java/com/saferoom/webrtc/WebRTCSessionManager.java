package com.saferoom.webrtc;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;
import io.grpc.stub.StreamObserver;
import com.saferoom.grpc.SafeRoomProto.WebRTCSignal;

/**
 * WebRTC Session Manager
 * Manages active WebRTC calls and signaling connections
 */
public class WebRTCSessionManager {
    
    // Active calls: callId -> CallSession
    private static final Map<String, CallSession> activeCalls = new ConcurrentHashMap<>();
    
    // User signaling streams: username -> StreamObserver
    private static final Map<String, StreamObserver<WebRTCSignal>> signalingStreams = new ConcurrentHashMap<>();
    
    // User to current call mapping: username -> callId
    private static final Map<String, String> userToCall = new ConcurrentHashMap<>();
    
    /**
     * Call Session info
     */
    public static class CallSession {
        public final String callId;
        public final String caller;
        public final String callee;
        public final long startTime;
        public boolean audioEnabled;
        public boolean videoEnabled;
        public CallState state;
        
        public CallSession(String callId, String caller, String callee, boolean audio, boolean video) {
            this.callId = callId;
            this.caller = caller;
            this.callee = callee;
            this.startTime = System.currentTimeMillis();
            this.audioEnabled = audio;
            this.videoEnabled = video;
            this.state = CallState.RINGING;
        }
    }
    
    /**
     * Call states
     */
    public enum CallState {
        RINGING,    // Arıyor
        CONNECTED,  // Bağlandı
        ENDED       // Bitti
    }
    
    /**
     * Generate unique call ID
     */
    public static String generateCallId() {
        return "call_" + UUID.randomUUID().toString();
    }
    
    /**
     * Create new call session
     */
    public static CallSession createCall(String caller, String callee, boolean audio, boolean video) {
        String callId = generateCallId();
        CallSession session = new CallSession(callId, caller, callee, audio, video);
        activeCalls.put(callId, session);
        userToCall.put(caller, callId);
        userToCall.put(callee, callId);
        
        System.out.printf("[WebRTC] 📞 New call created: %s (%s -> %s)%n", callId, caller, callee);
        return session;
    }
    
    /**
     * Get call session
     */
    public static CallSession getCall(String callId) {
        return activeCalls.get(callId);
    }
    
    /**
     * Get user's current call
     */
    public static CallSession getUserCall(String username) {
        String callId = userToCall.get(username);
        return callId != null ? activeCalls.get(callId) : null;
    }
    
    /**
     * Check if user is in a call
     */
    public static boolean isUserInCall(String username) {
        return userToCall.containsKey(username);
    }
    
    /**
     * End call
     */
    public static void endCall(String callId) {
        CallSession session = activeCalls.get(callId);
        if (session != null) {
            session.state = CallState.ENDED;
            userToCall.remove(session.caller);
            userToCall.remove(session.callee);
            activeCalls.remove(callId);
            
            long duration = (System.currentTimeMillis() - session.startTime) / 1000;
            System.out.printf("[WebRTC] 📴 Call ended: %s (duration: %d seconds)%n", callId, duration);
        }
    }
    
    /**
     * Register signaling stream for user
     */
    public static void registerSignalingStream(String username, StreamObserver<WebRTCSignal> stream) {
        signalingStreams.put(username, stream);
        System.out.printf("[WebRTC] 🔌 Signaling stream registered for: %s%n", username);
    }
    
    /**
     * Unregister signaling stream
     */
    public static void unregisterSignalingStream(String username) {
        signalingStreams.remove(username);
        System.out.printf("[WebRTC] 🔌 Signaling stream unregistered for: %s%n", username);
    }
    
    /**
     * Send signal to user
     */
    public static boolean sendSignalToUser(String username, WebRTCSignal signal) {
        StreamObserver<WebRTCSignal> stream = signalingStreams.get(username);
        if (stream != null) {
            try {
                stream.onNext(signal);
                System.out.printf("[WebRTC] 📤 Signal sent to %s: %s%n", username, signal.getType());
                return true;
            } catch (Exception e) {
                System.err.printf("[WebRTC] ❌ Failed to send signal to %s: %s%n", username, e.getMessage());
                return false;
            }
        } else {
            System.err.printf("[WebRTC] ❌ No signaling stream for user: %s%n", username);
            return false;
        }
    }
    
    /**
     * Check if user has signaling stream
     */
    public static boolean hasSignalingStream(String username) {
        return signalingStreams.containsKey(username);
    }
    
    /**
     * Get list of registered users (for debugging)
     */
    public static String getRegisteredUsers() {
        return String.join(", ", signalingStreams.keySet());
    }
    
    /**
     * Get active calls count
     */
    public static int getActiveCallsCount() {
        return activeCalls.size();
    }
    
    /**
     * Get all active calls (for monitoring)
     */
    public static Map<String, CallSession> getAllActiveCalls() {
        return new ConcurrentHashMap<>(activeCalls);
    }
}
