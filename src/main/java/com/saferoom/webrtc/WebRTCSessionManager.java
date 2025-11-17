package com.saferoom.webrtc;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
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
    
    // ===============================
    // GROUP CALL (ROOM) MANAGEMENT
    // ===============================
    
    // Active rooms: roomId -> RoomSession
    private static final Map<String, RoomSession> activeRooms = new ConcurrentHashMap<>();
    
    // User to room mapping: username -> roomId
    private static final Map<String, String> userToRoom = new ConcurrentHashMap<>();
    
    /**
     * Room Session info (for mesh group calls)
     */
    public static class RoomSession {
        public final String roomId;
        public final String creator;
        public final long createdTime;
        public final Map<String, RoomParticipant> participants = new ConcurrentHashMap<>();
        
        public RoomSession(String roomId, String creator) {
            this.roomId = roomId;
            this.creator = creator;
            this.createdTime = System.currentTimeMillis();
        }
        
        public void addParticipant(String username, boolean audio, boolean video) {
            participants.put(username, new RoomParticipant(username, audio, video));
        }
        
        public void removeParticipant(String username) {
            participants.remove(username);
        }
        
        public List<String> getParticipantList() {
            return new java.util.ArrayList<>(participants.keySet());
        }
        
        public int getParticipantCount() {
            return participants.size();
        }
    }
    
    /**
     * Room Participant info
     */
    public static class RoomParticipant {
        public final String username;
        public final long joinedTime;
        public boolean audioEnabled;
        public boolean videoEnabled;
        
        public RoomParticipant(String username, boolean audio, boolean video) {
            this.username = username;
            this.joinedTime = System.currentTimeMillis();
            this.audioEnabled = audio;
            this.videoEnabled = video;
        }
    }
    
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
    
    // ===============================
    // GROUP CALL (ROOM) METHODS
    // ===============================
    
    /**
     * Create or join a room
     * @return RoomSession (newly created or existing)
     */
    public static RoomSession joinRoom(String roomId, String username, boolean audio, boolean video) {
        // Check if user is already in a room
        if (userToRoom.containsKey(username)) {
            String currentRoom = userToRoom.get(username);
            System.out.printf("[WebRTC] ⚠️ User %s already in room %s%n", username, currentRoom);
            return activeRooms.get(currentRoom);
        }
        
        // Get or create room
        RoomSession room = activeRooms.computeIfAbsent(roomId, id -> {
            System.out.printf("[WebRTC] 🏠 Creating new room: %s%n", roomId);
            return new RoomSession(roomId, username);
        });
        
        // Add participant
        room.addParticipant(username, audio, video);
        userToRoom.put(username, roomId);
        
        System.out.printf("[WebRTC] 🏠 User %s joined room %s (participants: %d)%n", 
            username, roomId, room.getParticipantCount());
        
        return room;
    }
    
    /**
     * Leave room
     */
    public static void leaveRoom(String username) {
        String roomId = userToRoom.remove(username);
        if (roomId == null) {
            System.out.printf("[WebRTC] ⚠️ User %s not in any room%n", username);
            return;
        }
        
        RoomSession room = activeRooms.get(roomId);
        if (room != null) {
            room.removeParticipant(username);
            System.out.printf("[WebRTC] 🏠 User %s left room %s (remaining: %d)%n", 
                username, roomId, room.getParticipantCount());
            
            // Remove empty rooms
            if (room.getParticipantCount() == 0) {
                activeRooms.remove(roomId);
                long duration = (System.currentTimeMillis() - room.createdTime) / 1000;
                System.out.printf("[WebRTC] 🏠 Room %s closed (duration: %d seconds)%n", roomId, duration);
            }
        }
    }
    
    /**
     * Get room by ID
     */
    public static RoomSession getRoom(String roomId) {
        return activeRooms.get(roomId);
    }
    
    /**
     * Get user's current room
     */
    public static RoomSession getUserRoom(String username) {
        String roomId = userToRoom.get(username);
        return roomId != null ? activeRooms.get(roomId) : null;
    }
    
    /**
     * Check if user is in a room
     */
    public static boolean isUserInRoom(String username) {
        return userToRoom.containsKey(username);
    }
    
    /**
     * Get room ID for user
     */
    public static String getUserRoomId(String username) {
        return userToRoom.get(username);
    }
    
    /**
     * Broadcast signal to all room participants except sender
     */
    public static void broadcastToRoom(String roomId, String senderUsername, WebRTCSignal signal) {
        RoomSession room = activeRooms.get(roomId);
        if (room == null) {
            System.err.printf("[WebRTC] ❌ Room not found: %s%n", roomId);
            return;
        }
        
        int successCount = 0;
        for (String participant : room.getParticipantList()) {
            if (!participant.equals(senderUsername)) {
                if (sendSignalToUser(participant, signal)) {
                    successCount++;
                }
            }
        }
        
        System.out.printf("[WebRTC] 📡 Broadcast %s to room %s: %d/%d peers%n", 
            signal.getType(), roomId, successCount, room.getParticipantCount() - 1);
    }
    
    /**
     * Get active rooms count
     */
    public static int getActiveRoomsCount() {
        return activeRooms.size();
    }
    
    /**
     * Get all active rooms (for monitoring)
     */
    public static Map<String, RoomSession> getAllActiveRooms() {
        return new ConcurrentHashMap<>(activeRooms);
    }
}
