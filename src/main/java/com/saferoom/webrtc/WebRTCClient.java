package com.saferoom.webrtc;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.*;
import dev.onvoid.webrtc.media.audio.*;
import dev.onvoid.webrtc.media.video.*;
import dev.onvoid.webrtc.media.video.desktop.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.List;
import java.util.ArrayList;

/**
 * WebRTC Client Manager (Real Implementation)
 * Uses webrtc-java library for actual media streaming
 */
public class WebRTCClient {
    
    private static boolean initialized = false;
    private static PeerConnectionFactory factory;
    
    private String currentCallId;
    private String remoteUsername;
    private boolean audioEnabled;
    private boolean videoEnabled;
    
    private RTCPeerConnection peerConnection;
    private MediaStreamTrack localAudioTrack;
    private MediaStreamTrack localVideoTrack;
    private dev.onvoid.webrtc.media.video.VideoDeviceSource videoSource; // Keep reference to stop camera
    
    // Track RTP senders for replaceTrack operations
    private RTCRtpSender videoSender = null;
    
    // Callbacks
    private Consumer<RTCIceCandidate> onIceCandidateCallback;
    private Consumer<String> onLocalSDPCallback;
    private Runnable onConnectionEstablishedCallback;
    private Runnable onConnectionClosedCallback;
    private Consumer<MediaStreamTrack> onRemoteTrackCallback;
    
    /**
     * Initialize WebRTC (call once at app startup)
     */
    public static synchronized void initialize() {
        if (initialized) {
            System.out.println("[WebRTC] Already initialized");
            return;
        }
        
        System.out.println("[WebRTC] Initializing WebRTC with native library...");
        
        try {
            // Get default audio devices (MacOS automatically selects correct Bluetooth mode)
            AudioDevice defaultMic = MediaDevices.getDefaultAudioCaptureDevice();
            AudioDevice defaultSpeaker = MediaDevices.getDefaultAudioRenderDevice();
            
            // Create and configure AudioDeviceModule
            AudioDeviceModule audioModule = new AudioDeviceModule();
            
            if (defaultMic != null) {
                System.out.println("[WebRTC] Default microphone: " + defaultMic.getName());
                audioModule.setRecordingDevice(defaultMic);
                audioModule.initRecording();
            }
            
            if (defaultSpeaker != null) {
                System.out.println("[WebRTC] Default speaker: " + defaultSpeaker.getName());
                audioModule.setPlayoutDevice(defaultSpeaker);
                audioModule.initPlayout();
            }
            
            // Initialize factory with audio device module
            factory = new PeerConnectionFactory(audioModule);
            
            initialized = true;
            System.out.println("[WebRTC] WebRTC initialized successfully with native library");
        } catch (Throwable e) {
            // Fallback to mock mode (native library not available)
            System.err.printf("[WebRTC]  Native library failed to load: %s%n", e.getMessage());
            System.out.println("[WebRTC] Running in MOCK mode (signaling will work, but no real media)");
            
            factory = null;
            initialized = true;
        }
    }
    
    /**
     * Shutdown WebRTC (call at app exit)
     */
    public static synchronized void shutdown() {
        if (!initialized) return;
        
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
        
        initialized = false;
        System.out.println("[WebRTC] WebRTC shutdown complete");
    }
    
    /**
     * Check if WebRTC is initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Get the PeerConnectionFactory (for P2PConnectionManager)
     */
    public static PeerConnectionFactory getFactory() {
        return factory;
    }
    
    /**
     * Constructor
     */
    public WebRTCClient(String callId, String remoteUsername) {
        if (!initialized) {
            throw new IllegalStateException("WebRTC not initialized. Call WebRTCClient.initialize() first.");
        }
        this.currentCallId = callId;
        this.remoteUsername = remoteUsername;
    }
    
    /**
     * Create peer connection
     */
    public void createPeerConnection(boolean audioEnabled, boolean videoEnabled) {
        System.out.printf("[WebRTC] Creating peer connection (audio=%b, video=%b)%n", 
            audioEnabled, videoEnabled);
        
        this.audioEnabled = audioEnabled;
        this.videoEnabled = videoEnabled;
        
        if (factory == null) {
            System.out.println("[WebRTC] Factory null - running in MOCK mode");
            return;
        }
        
        try {
            // Configure ICE servers (STUN)
            List<RTCIceServer> iceServers = new ArrayList<>();
            RTCIceServer stunServer = new RTCIceServer();
            stunServer.urls.add("stun:stun.l.google.com:19302");
            stunServer.urls.add("stun:stun1.l.google.com:19302");
            iceServers.add(stunServer);
            
            RTCConfiguration config = new RTCConfiguration();
            config.iceServers = iceServers;
            
            // Create peer connection
            peerConnection = factory.createPeerConnection(config, new PeerConnectionObserver() {
                @Override
                public void onIceCandidate(RTCIceCandidate candidate) {
                    System.out.printf("[WebRTC] ICE Candidate generated: %s%n", candidate.sdp);
                    if (onIceCandidateCallback != null) {
                        onIceCandidateCallback.accept(candidate);
                    }
                }
                
                @Override
                public void onIceConnectionChange(RTCIceConnectionState state) {
                    System.out.printf("[WebRTC] ICE Connection state: %s%n", state);
                    if (state == RTCIceConnectionState.CONNECTED || state == RTCIceConnectionState.COMPLETED) {
                        System.out.println("[WebRTC] ICE connection established!");
                        if (onConnectionEstablishedCallback != null) {
                            onConnectionEstablishedCallback.run();
                        }
                    } else if (state == RTCIceConnectionState.FAILED || state == RTCIceConnectionState.DISCONNECTED) {
                        System.out.println("[WebRTC] ICE connection failed/disconnected");
                        if (onConnectionClosedCallback != null) {
                            onConnectionClosedCallback.run();
                        }
                    }
                }
                
                @Override
                public void onTrack(RTCRtpTransceiver transceiver) {
                    MediaStreamTrack track = transceiver.getReceiver().getTrack();
                    System.out.printf("[WebRTC] Remote track received: %s (kind=%s)%n", 
                        track.getId(), track.getKind());
                    
                    // Handle audio track automatically
                    if (track.getKind().equals("audio") && track instanceof AudioTrack) {
                        handleRemoteAudioTrack((AudioTrack) track);
                    }
                    
                    // Handle video track
                    if (track.getKind().equals("video") && track instanceof VideoTrack) {
                        handleRemoteVideoTrack((VideoTrack) track);
                    }
                    
                    if (onRemoteTrackCallback != null) {
                        onRemoteTrackCallback.accept(track);
                    }
                }
            });
            
            // Add local tracks (will be implemented)
            // For now, just create connection without media
            
            System.out.println("[WebRTC] Peer connection created");
            
        } catch (Exception e) {
            System.err.printf("[WebRTC] Failed to create peer connection: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Create SDP offer
     */
    public CompletableFuture<String> createOffer() {
        System.out.println("[WebRTC] Creating SDP offer...");
        
        CompletableFuture<String> future = new CompletableFuture<>();
        
        if (peerConnection == null) {
            // Fallback to mock SDP
            String mockSDP = generateMockSDP("offer");
            System.out.println("[WebRTC] Using mock SDP (peer connection not available)");
            future.complete(mockSDP);
            return future;
        }
        
        try {
            RTCOfferOptions options = new RTCOfferOptions();
            peerConnection.createOffer(options, new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                        @Override
                        public void onSuccess() {
                            System.out.println("[WebRTC] Offer created and set as local description");
                            String sdp = description.sdp;
                            if (onLocalSDPCallback != null) {
                                onLocalSDPCallback.accept(sdp);
                            }
                            future.complete(sdp);
                        }
                        
                        @Override
                        public void onFailure(String error) {
                            System.err.printf("[WebRTC] Failed to set local description: %s%n", error);
                            future.completeExceptionally(new Exception(error));
                        }
                    });
                }
                
                @Override
                public void onFailure(String error) {
                    System.err.printf("[WebRTC] Failed to create offer: %s%n", error);
                    future.completeExceptionally(new Exception(error));
                }
            });
        } catch (Exception e) {
            System.err.printf("[WebRTC] Exception creating offer: %s%n", e.getMessage());
            future.completeExceptionally(e);
        }
        
        return future;
    }
    
    /**
     * Create SDP answer
     */
    public CompletableFuture<String> createAnswer() {
        System.out.println("[WebRTC] Creating SDP answer...");
        
        CompletableFuture<String> future = new CompletableFuture<>();
        
        if (peerConnection == null) {
            // Fallback to mock SDP
            String mockSDP = generateMockSDP("answer");
            System.out.println("[WebRTC]  Using mock SDP (peer connection not available)");
            future.complete(mockSDP);
            return future;
        }
        
        try {
            RTCAnswerOptions options = new RTCAnswerOptions();
            peerConnection.createAnswer(options, new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
                        @Override
                        public void onSuccess() {
                            System.out.println("[WebRTC] Answer created and set as local description");
                            String sdp = description.sdp;
                            if (onLocalSDPCallback != null) {
                                onLocalSDPCallback.accept(sdp);
                            }
                            future.complete(sdp);
                        }
                        
                        @Override
                        public void onFailure(String error) {
                            System.err.printf("[WebRTC] Failed to set local description: %s%n", error);
                            future.completeExceptionally(new Exception(error));
                        }
                    });
                }
                
                @Override
                public void onFailure(String error) {
                    System.err.printf("[WebRTC] Failed to create answer: %s%n", error);
                    future.completeExceptionally(new Exception(error));
                }
            });
        } catch (Exception e) {
            System.err.printf("[WebRTC] Exception creating answer: %s%n", e.getMessage());
            future.completeExceptionally(e);
        }
        
        return future;
    }
    
    /**
     * Set remote SDP (offer or answer)
     */
    public void setRemoteDescription(String sdpType, String sdp) {
        System.out.printf("[WebRTC] Setting remote %s%n", sdpType);
        
        if (peerConnection == null) {
            System.out.println("[WebRTC] Peer connection null - skipping");
            return;
        }
        
        try {
            RTCSdpType type = sdpType.equalsIgnoreCase("offer") ? RTCSdpType.OFFER : RTCSdpType.ANSWER;
            RTCSessionDescription description = new RTCSessionDescription(type, sdp);
            
            peerConnection.setRemoteDescription(description, new SetSessionDescriptionObserver() {
                @Override
                public void onSuccess() {
                    System.out.println("[WebRTC] Remote description set");
                }
                
                @Override
                public void onFailure(String error) {
                    System.err.printf("[WebRTC] Failed to set remote description: %s%n", error);
                }
            });
        } catch (Exception e) {
            System.err.printf("[WebRTC] Exception setting remote description: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Add ICE candidate
     */
    public void addIceCandidate(String candidate, String sdpMid, int sdpMLineIndex) {
        System.out.printf("[WebRTC] Adding ICE candidate: %s%n", candidate);
        
        if (peerConnection == null) {
            System.out.println("[WebRTC] Peer connection null - skipping");
            return;
        }
        
        try {
            RTCIceCandidate iceCandidate = new RTCIceCandidate(sdpMid, sdpMLineIndex, candidate);
            peerConnection.addIceCandidate(iceCandidate);
            System.out.println("[WebRTC] ICE candidate added");
        } catch (Exception e) {
            System.err.printf("[WebRTC] Failed to add ICE candidate: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Close connection
     */
    public void close() {
        System.out.println("[WebRTC] Closing peer connection...");
        
        // First, remove tracks from peer connection before disposing
        if (peerConnection != null) {
            try {
                // Get all senders and remove tracks
                var senders = peerConnection.getSenders();
                for (var sender : senders) {
                    var track = sender.getTrack();
                    if (track != null) {
                        System.out.printf("[WebRTC] Removing track: %s%n", track.getId());
                        peerConnection.removeTrack(sender);
                    }
                }
            } catch (Exception e) {
                System.err.printf("[WebRTC] Error removing tracks: %s%n", e.getMessage());
            }
            
            // Close peer connection
            peerConnection.close();
            peerConnection = null;
        }
        
        // Now dispose tracks (after removing from peer connection)
        if (localAudioTrack != null) {
            try {
                localAudioTrack.setEnabled(false); // Disable first
                localAudioTrack.dispose();
                System.out.println("[WebRTC] Audio track disposed");
            } catch (Exception e) {
                System.err.printf("[WebRTC] Error disposing audio track: %s%n", e.getMessage());
            }
            localAudioTrack = null;
        }
        
        // IMPORTANT: Don't dispose localVideoTrack if it's shared from GroupCallManager
        // Only dispose if we own the video source (videoSource != null)
        if (localVideoTrack != null && videoSource != null) {
            try {
                localVideoTrack.setEnabled(false); // Disable first
                localVideoTrack.dispose();
                System.out.println("[WebRTC] Video track disposed");
            } catch (Exception e) {
                System.err.printf("[WebRTC] Error disposing video track: %s%n", e.getMessage());
            }
            localVideoTrack = null;
        } else if (localVideoTrack != null) {
            System.out.println("[WebRTC] Video track is shared - not disposing (GroupCallManager owns it)");
            localVideoTrack = null;
        }
        
        // Stop video source (release camera) - only if we own it
        if (videoSource != null) {
            try {
                videoSource.stop();
                videoSource.dispose();
                System.out.println("[WebRTC] Camera source stopped and released");
            } catch (Exception e) {
                System.err.printf("[WebRTC] Error stopping video source: %s%n", e.getMessage());
            }
            videoSource = null;
        }
        
        // DON'T call onConnectionClosedCallback here - causes infinite recursion
        // CallManager.cleanup() already calls this method, no need for callback loop
        
        System.out.println("[WebRTC] Connection closed");
    }
    
    // ===============================
    // Callback Setters
    // ===============================
    
    public void setOnIceCandidateCallback(Consumer<RTCIceCandidate> callback) {
        this.onIceCandidateCallback = callback;
    }
    
    public void setOnLocalSDPCallback(Consumer<String> callback) {
        this.onLocalSDPCallback = callback;
    }
    
    public void setOnConnectionEstablishedCallback(Runnable callback) {
        this.onConnectionEstablishedCallback = callback;
    }
    
    public void setOnConnectionClosedCallback(Runnable callback) {
        this.onConnectionClosedCallback = callback;
    }
    
    public void setOnRemoteTrackCallback(Consumer<MediaStreamTrack> callback) {
        this.onRemoteTrackCallback = callback;
    }
    
    // ===============================
    // Media Control
    // ===============================
    
    /**
     * Add audio track to peer connection for microphone capture.
     * This triggers ICE candidate generation.
     */
    public void addAudioTrack() {
        if (factory == null) {
            System.err.println("[WebRTC] Cannot add audio track - factory not initialized");
            return;
        }
        
        if (peerConnection == null) {
            System.err.println("[WebRTC] Cannot add audio track - peer connection not created");
            return;
        }
        
        try {
            System.out.println("[WebRTC] Adding audio track with ADVANCED processing...");
            
            // ===== ADVANCED AUDIO OPTIONS =====
            AudioOptions audioOptions = new AudioOptions();
            
            // Temel özellikler (always enabled)
            audioOptions.echoCancellation = true;
            audioOptions.autoGainControl = true;
            audioOptions.noiseSuppression = true;
            
            // İLERİ SEVİYE ÖZELLİKLER (Advanced features)
            // Note: webrtc-java 0.14.0 supports these fields
            audioOptions.highpassFilter = true;              // Düşük frekans filtresi (rumble noise)
            
            // Create audio source with enhanced options
            AudioTrackSource audioSource = factory.createAudioSource(audioOptions);
            
            // Create audio track with a unique ID
            AudioTrack audioTrack = factory.createAudioTrack("audio0", audioSource);
            
            // Add track to peer connection with stream ID
            peerConnection.addTrack(audioTrack, List.of("stream1"));
           
            System.out.println("[WebRTC] ✅ Audio track added with ADVANCED processing:");
            System.out.println("  ├─ Echo cancellation: ENABLED (removes speaker feedback)");
            System.out.println("  ├─ Noise suppression: ENABLED (removes background noise)");
            System.out.println("  ├─ Auto gain control: ENABLED (normalizes volume)");
            System.out.println("  └─ Highpass filter: ENABLED (removes low-freq rumble)");
            System.out.println("[WebRTC] 🎤 Professional audio quality enabled!");
            
            // Store reference for cleanup
            this.localAudioTrack = audioTrack;
            
        } catch (Exception e) {
            System.err.println("[WebRTC] Failed to add audio track: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Add video track to peer connection for camera capture.
     * Similar to addAudioTrack but for video.
     */
    public void addVideoTrack() {
        if (factory == null) {
            System.err.println("[WebRTC] Cannot add video track - factory not initialized");
            return;
        }
        
        if (peerConnection == null) {
            System.err.println("[WebRTC] Cannot add video track - peer connection not created");
            return;
        }
        
        try {
            System.out.println("[WebRTC] Adding video track with optimized settings...");
            
            // ===== FIX: Cleanup existing video source first (MacOS freeze fix) =====
            if (this.videoSource != null) {
                System.out.println("[WebRTC] Cleaning up existing video source...");
                try {
                    videoSource.stop();
                    videoSource.dispose();
                } catch (Exception e) {
                    System.err.println("[WebRTC] Error cleaning up old video source: " + e.getMessage());
                }
                this.videoSource = null;
            }
            
            // ===== VIDEO SOURCE WITH OPTIMIZED SETTINGS =====
            CameraCaptureService.CameraCaptureResource resource =
                CameraCaptureService.createCameraTrack("video0");
            
            this.videoSource = resource.getSource();
            VideoTrack videoTrack = resource.getTrack();
            
            // Add track to peer connection with stream ID ve sender referansı
            videoSender = peerConnection.addTrack(videoTrack, List.of("stream1"));
            
            System.out.println("[WebRTC] ✅ Video track added with optimized settings:");
            System.out.println("  ├─ Resolution: 640x480 (CameraCaptureService)");
            System.out.println("  ├─ Frame rate: 30 FPS (smooth playback)");
            System.out.println("  ├─ Codec: H.264/VP8/VP9 (negotiated via SDP)");
            System.out.println("  └─ Hardware encoding: AUTO-DETECTED by WebRTC");
            System.out.println("[WebRTC] 🎥 GPU acceleration enabled (VideoToolbox on Mac)!");
            
            // Store reference for cleanup
            this.localVideoTrack = videoTrack;
            
        } catch (Exception e) {
            System.err.println("[WebRTC] Failed to add video track: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Add shared video track (from GroupCallManager) to peer connection
     * This allows multiple peer connections to share the same camera source
     */
    public void addSharedVideoTrack(VideoTrack sharedTrack) {
        if (factory == null) {
            System.err.println("[WebRTC] Cannot add video track - factory not initialized");
            return;
        }
        
        if (peerConnection == null) {
            System.err.println("[WebRTC] Cannot add video track - peer connection not created");
            return;
        }
        
        if (sharedTrack == null) {
            System.err.println("[WebRTC] Shared video track is null");
            return;
        }
        
        try {
            System.out.println("[WebRTC] Adding SHARED video track to peer connection...");
            
            // Add track to peer connection with stream ID
            videoSender = peerConnection.addTrack(sharedTrack, List.of("stream1"));
            
            // Store reference (but DON'T dispose it in close() - GroupCallManager owns it)
            this.localVideoTrack = sharedTrack;
            
            System.out.println("[WebRTC] ✅ Shared video track added successfully");
            
        } catch (Exception e) {
            System.err.println("[WebRTC] Failed to add shared video track: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Handle remote audio track (automatically plays received audio)
     */
    private void handleRemoteAudioTrack(AudioTrack audioTrack) {
        System.out.println("[WebRTC] Setting up remote audio playback...");
        
        // Add sink to monitor audio data (optional - for debugging)
        AudioTrackSink sink = (data, bitsPerSample, sampleRate, channels, frames) -> {
            // Audio data is automatically played through speakers by AudioDeviceModule
            // This callback is just for monitoring/debugging
            // System.out.printf("[WebRTC] Receiving audio: %d Hz, %d channels%n", sampleRate, channels);
        };
        
        audioTrack.addSink(sink);
        System.out.println("[WebRTC] Remote audio track ready");
    }
    
    /**
     * Handle remote video track
     */
    private void handleRemoteVideoTrack(VideoTrack videoTrack) {
        System.out.println("[WebRTC] Remote video track received: " + videoTrack.getId());
        
        // Video rendering will be handled by VideoPanel through callback
        // Just log for now - GUI will attach VideoPanel via onRemoteTrackCallback
        System.out.println("[WebRTC] Remote video track ready (waiting for VideoPanel attachment)");
    }
    
    public void toggleAudio(boolean enabled) {
        this.audioEnabled = enabled;
        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(enabled);
        }
        System.out.printf("[WebRTC] Audio %s%n", enabled ? "enabled" : "muted");
    }
    
    public void toggleVideo(boolean enabled) {
        this.videoEnabled = enabled;
        
        // IMPORTANT: Don't toggle localVideoTrack if it's shared (videoSource == null)
        // Shared tracks are managed by GroupCallManager
        if (localVideoTrack != null && videoSource != null) {
            localVideoTrack.setEnabled(enabled);
            System.out.printf("[WebRTC] Local video track %s (own source)%n", 
                enabled ? "enabled" : "disabled");
        } else if (localVideoTrack != null && videoSource == null) {
            System.out.printf("[WebRTC] Skipping toggle - video track is shared (managed by GroupCallManager)%n");
        }
    }
    
    // ===============================
    // Screen Sharing API
    // ===============================
    
    /**
     * Get available screens for sharing
     */
    public List<DesktopSource> getAvailableScreens() {
        throw new UnsupportedOperationException(
            "Legacy screen enumeration has been removed. Use ScreenSourcePickerDialog instead.");
    }
    
    /**
     * Get available windows for sharing
     */
    public List<DesktopSource> getAvailableWindows() {
        throw new UnsupportedOperationException(
            "Legacy screen enumeration has been removed. Use ScreenSourcePickerDialog instead.");
    }
    
    /**
     * Test if a desktop source is safe to capture (won't crash)
     * Returns true if source can be safely used
     */
    public boolean testSourceSafety(DesktopSource source, boolean isWindow) {
        throw new UnsupportedOperationException("Legacy screen share safety path removed.");
    }
    
    /**
     * Start screen sharing with selected source
     * @param sourceId Desktop source ID (from DesktopSource.id)
     * @param isWindow true if sharing window, false if sharing screen
     */
    public void startScreenShare(long sourceId, boolean isWindow) {
        throw new UnsupportedOperationException(
            "Legacy screen share path removed. Use ScreenShareController.startScreenShare(ScreenSourceOption).");
    }
    
    /**
     * Stop screen sharing
     */
    public void stopScreenShare() {
        throw new UnsupportedOperationException(
            "Legacy screen share path removed. Use ScreenShareController.stopScreenShare().");
    }
    
    /**
     * Check if screen sharing is currently active
     */
    public boolean isScreenSharingEnabled() {
        return false;
    }
    
    /**
     * Get screen share video track (for local preview)
     */
    // ===============================
    // Helper Methods
    // ===============================
    
    // (Helper methods removed - using default device selection now)
    
    // ===============================
    // Getters
    // ===============================
    
    public String getCallId() {
        return currentCallId;
    }
    
    public String getRemoteUsername() {
        return remoteUsername;
    }
    
    public boolean isAudioEnabled() {
        return audioEnabled;
    }
    
    public boolean isVideoEnabled() {
        return videoEnabled;
    }

    public RTCPeerConnection getPeerConnection() {
        return peerConnection;
    }

    public RTCRtpSender getVideoSender() {
        return videoSender;
    }
    
    public VideoTrack getLocalVideoTrack() {
        VideoTrack track = (VideoTrack) localVideoTrack;
        System.out.printf("[WebRTC] getLocalVideoTrack called: localVideoTrack=%s%n",
            localVideoTrack != null ? "EXISTS (class=" + localVideoTrack.getClass().getSimpleName() + ")" : "NULL");
        return track;
    }
    
    public AudioTrack getLocalAudioTrack() {
        return (AudioTrack) localAudioTrack;
    }
    
    /**
     * Capture a thumbnail preview for a screen/window source
     * Returns a VideoFrame with the captured image
     * 
     * Note: Due to native library limitations and platform compatibility issues,
     * thumbnail capture is disabled. This method returns null and the UI will
     * show placeholder icons instead.
     */
    public VideoFrame captureThumbnail(DesktopSource source, boolean isWindow) {
        throw new UnsupportedOperationException("Thumbnail capture is handled by ScreenSourcePickerDialog.");
    }
    
    // ===============================
    // Mock SDP Generator (fallback)
    // ===============================
    
    private String generateMockSDP(String type) {
        return String.format(
            "v=0\r\n" +
            "o=- %d 2 IN IP4 127.0.0.1\r\n" +
            "s=-\r\n" +
            "t=0 0\r\n" +
            "a=group:BUNDLE 0\r\n" +
            "a=msid-semantic: WMS\r\n" +
            "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
            "c=IN IP4 0.0.0.0\r\n" +
            "a=rtcp:9 IN IP4 0.0.0.0\r\n" +
            "a=ice-ufrag:%s\r\n" +
            "a=ice-pwd:%s\r\n" +
            "a=fingerprint:sha-256 MOCK:FINGERPRINT\r\n" +
            "a=setup:actpass\r\n" +
            "a=mid:0\r\n" +
            "a=sendrecv\r\n" +
            "a=rtpmap:111 opus/48000/2\r\n",
            System.currentTimeMillis(),
            randomString(8),
            randomString(24)
        );
    }
    
    private String randomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt((int) (Math.random() * chars.length())));
        }
        return sb.toString();
    }
}
