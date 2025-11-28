package com.saferoom.gui.dialog;

import com.saferoom.gui.components.VideoPanel;
import com.saferoom.webrtc.CallManager;
import dev.onvoid.webrtc.media.video.VideoTrack;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import java.time.Duration;
import java.time.Instant;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;

/**
 * Dialog for active WebRTC call
 * Shows video preview, controls (mute, camera toggle, end call), and call duration
 */
public class ActiveCallDialog {
    
    private final Stage stage;
    private final String remoteUsername;
    private final String callId;
    private final boolean videoEnabled;
    private final CallManager callManager;
    
    // UI Components
    private Label durationLabel;
    private Button muteButton;
    private Button cameraButton;
    private Button shareScreenButton;     // Start/stop screen sharing
    private Button endCallButton;
    private Button screenToggleButton;  // Toggle between camera and screen share
    private VideoPanel localVideoPanel;   // Local camera preview
    private VideoPanel remoteVideoPanel;  // Remote user's video/camera
    private VideoPanel remoteScreenPanel; // Remote user's screen share
    private StackPane videoArea;          // Container for video panels
    
    // State
    private boolean isMuted = false;
    private boolean isCameraOn = true;
    private boolean isSharingScreen = false; // Currently sharing my screen
    private boolean isShowingScreen = false; // Currently showing screen vs camera
    private boolean hasRemoteScreen = false; // Remote peer is sharing screen
    private Instant callStartTime;
    private Timeline durationTimer;
    
    /**
     * Create active call dialog
     * 
     * @param remoteUsername Remote user's username
     * @param callId Unique call identifier
     * @param videoEnabled Whether video is enabled
     * @param callManager CallManager instance
     */
    public ActiveCallDialog(String remoteUsername, String callId, boolean videoEnabled, CallManager callManager) {
        this.remoteUsername = remoteUsername;
        this.callId = callId;
        this.videoEnabled = videoEnabled;
        this.callManager = callManager;
        this.callStartTime = Instant.now();
        this.stage = createDialog();
        
        // Register remote track callback to automatically attach remote video
        registerRemoteTrackCallback();
    }
    
    private Stage createDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.DECORATED);
        dialog.setTitle("Active Call - " + remoteUsername);
        dialog.setResizable(true);
        dialog.setMinWidth(640);
        dialog.setMinHeight(480);
        
        // Main container
        BorderPane mainContainer = new BorderPane();
        mainContainer.getStyleClass().add("dialog-container");
        
        // ========== TOP BAR ==========
        HBox topBar = new HBox(10);
        topBar.setPadding(new Insets(10, 15, 10, 15));
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.7);");
        
        Label userLabel = new Label(remoteUsername);
        userLabel.setStyle("-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold;");
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        durationLabel = new Label("00:00");
        durationLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");
        
        topBar.getChildren().addAll(userLabel, spacer, durationLabel);
        mainContainer.setTop(topBar);
        
        // ========== CENTER (VIDEO AREA) ==========
        videoArea = new StackPane();
        videoArea.setStyle("-fx-background-color: #1a1a1a;");
        
        // Remote video panel (full screen) - Canvas for actual video rendering
        remoteVideoPanel = new VideoPanel(640, 480);
        remoteVideoPanel.setStyle("-fx-background-color: #2c3e50;");
        // Bind size to video area
        remoteVideoPanel.widthProperty().bind(videoArea.widthProperty());
        remoteVideoPanel.heightProperty().bind(videoArea.heightProperty());
        
        // Remote screen share panel (initially hidden)
        remoteScreenPanel = new VideoPanel(640, 480);
        remoteScreenPanel.setStyle("-fx-background-color: #1a1a1a;");
        remoteScreenPanel.widthProperty().bind(videoArea.widthProperty());
        remoteScreenPanel.heightProperty().bind(videoArea.heightProperty());
        remoteScreenPanel.setVisible(false);
        remoteScreenPanel.setManaged(false);
        remoteScreenPanel.pauseRendering();
        remoteScreenPanel.pauseRendering();
        
        // Local video preview (small, bottom-right corner) - Canvas for local camera
        if (videoEnabled) {
            localVideoPanel = new VideoPanel(160, 120);
            localVideoPanel.setStyle("-fx-background-color: #34495e; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 10, 0, 0, 0);");
            
            // Position in bottom-right corner
            StackPane.setAlignment(localVideoPanel, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(localVideoPanel, new Insets(15));
            
            videoArea.getChildren().addAll(remoteVideoPanel, remoteScreenPanel, localVideoPanel);
        } else {
            videoArea.getChildren().addAll(remoteVideoPanel, remoteScreenPanel);
        }
        
        mainContainer.setCenter(videoArea);
        
        // ========== BOTTOM BAR (CONTROLS) ==========
        HBox controlBar = new HBox(20);
        controlBar.setPadding(new Insets(15));
        controlBar.setAlignment(Pos.CENTER);
        controlBar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.8);");
        
        // Mute/Unmute button
        muteButton = createControlButton(
            FontAwesomeSolid.MICROPHONE,
            "#3498db",
            "Mute"
        );
        muteButton.setOnAction(e -> toggleMute());
        
        // Camera toggle button (only for video calls)
        cameraButton = createControlButton(
            FontAwesomeSolid.VIDEO,
            "#9b59b6",
            "Turn Off Camera"
        );
        cameraButton.setOnAction(e -> toggleCamera());
        cameraButton.setVisible(videoEnabled);
        cameraButton.setManaged(videoEnabled);
        
        // Share screen button
        shareScreenButton = createControlButton(
            FontAwesomeSolid.DESKTOP,
            "#27ae60",
            "Share Screen"
        );
        shareScreenButton.setOnAction(e -> handleShareScreen());
        
        // Ensure screen share control is enabled across all platforms
        checkScreenCaptureCapability();
        
        // Screen share toggle button (initially hidden)
        screenToggleButton = createControlButton(
            FontAwesomeSolid.DESKTOP,
            "#2ecc71",
            "Switch to Screen"
        );
        screenToggleButton.setOnAction(e -> toggleScreenView());
        screenToggleButton.setVisible(false);
        screenToggleButton.setManaged(false);
        
        // End call button
        endCallButton = createControlButton(
            FontAwesomeSolid.PHONE_SLASH,
            "#e74c3c",
            "End Call"
        );
        endCallButton.setOnAction(e -> endCall());
        
        controlBar.getChildren().addAll(muteButton, cameraButton, shareScreenButton, screenToggleButton, endCallButton);
        mainContainer.setBottom(controlBar);
        
        // Create scene
        Scene scene = new Scene(mainContainer, 800, 600);
        try {
            scene.getStylesheets().add(
                getClass().getResource("/styles/styles.css").toExternalForm()
            );
        } catch (Exception e) {
            System.err.println("[ActiveCallDialog] Warning: Could not load styles.css");
        }
        
        dialog.setScene(scene);
        
        // Handle window close
        dialog.setOnCloseRequest(e -> {
            e.consume(); // Prevent default close
            endCall();
        });
        
        return dialog;
    }
    
    /**
     * Create a control button with icon
     */
    private Button createControlButton(FontAwesomeSolid icon, String color, String tooltip) {
        Button button = new Button();
        FontIcon buttonIcon = new FontIcon(icon);
        buttonIcon.setIconSize(24);
        button.setGraphic(buttonIcon);
        button.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: white; " +
            "-fx-pref-width: 60px; -fx-pref-height: 60px; " +
            "-fx-background-radius: 30px;",
            color
        ));
        
        // Hover effect
        String hoverColor = adjustBrightness(color, 0.8);
        button.setOnMouseEntered(e -> 
            button.setStyle(String.format(
                "-fx-background-color: %s; -fx-text-fill: white; " +
                "-fx-pref-width: 60px; -fx-pref-height: 60px; " +
                "-fx-background-radius: 30px;",
                hoverColor
            ))
        );
        button.setOnMouseExited(e -> 
            button.setStyle(String.format(
                "-fx-background-color: %s; -fx-text-fill: white; " +
                "-fx-pref-width: 60px; -fx-pref-height: 60px; " +
                "-fx-background-radius: 30px;",
                color
            ))
        );
        
        return button;
    }
    
    /**
     * Adjust color brightness (simple darkening)
     */
    private String adjustBrightness(String hexColor, double factor) {
        // Simple darkening by reducing RGB values
        try {
            Color color = Color.web(hexColor);
            return String.format("#%02x%02x%02x",
                (int)(color.getRed() * 255 * factor),
                (int)(color.getGreen() * 255 * factor),
                (int)(color.getBlue() * 255 * factor)
            );
        } catch (Exception e) {
            return hexColor;
        }
    }
    
    /**
     * Toggle microphone mute
     */
    private void toggleMute() {
        isMuted = !isMuted;
        
        if (callManager != null) {
            callManager.toggleAudio(!isMuted); // Pass the new state (true = enabled, false = disabled)
        }
        
        FontIcon icon = new FontIcon(isMuted ? 
            FontAwesomeSolid.MICROPHONE_SLASH : FontAwesomeSolid.MICROPHONE);
        icon.setIconSize(24);
        muteButton.setGraphic(icon);
        
        String color = isMuted ? "#e74c3c" : "#3498db";
        muteButton.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: white; " +
            "-fx-pref-width: 60px; -fx-pref-height: 60px; " +
            "-fx-background-radius: 30px;",
            color
        ));
        
        System.out.printf("[ActiveCallDialog] Microphone %s%n", isMuted ? "muted" : "unmuted");
    }
    
    /**
     * Toggle camera on/off
     */
    private void toggleCamera() {
        isCameraOn = !isCameraOn;
        
        if (callManager != null) {
            callManager.toggleVideo(isCameraOn); // Pass the new state
        }
        
        FontIcon icon = new FontIcon(isCameraOn ? 
            FontAwesomeSolid.VIDEO : FontAwesomeSolid.VIDEO_SLASH);
        icon.setIconSize(24);
        cameraButton.setGraphic(icon);
        
        String color = isCameraOn ? "#9b59b6" : "#e74c3c";
        cameraButton.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: white; " +
            "-fx-pref-width: 60px; -fx-pref-height: 60px; " +
            "-fx-background-radius: 30px;",
            color
        ));
        
        // Update local preview visibility
        if (localVideoPanel != null) {
            localVideoPanel.setVisible(isCameraOn);
            if (isCameraOn) {
                localVideoPanel.resumeRendering();
            } else {
                localVideoPanel.pauseRendering();
            }
        }
        
        System.out.printf("[ActiveCallDialog] Camera %s%n", isCameraOn ? "enabled" : "disabled");
    }
    
    /**
     * Check if screen capture is available on this system
     * Runs in background thread to avoid blocking UI
     * CRITICAL: Prevents native crashes by testing capability first
     * 
     * NOTE: On Linux, screen capture requires:
     * - PipeWire (modern audio/video server)
     * - XDG Desktop Portal (screen capture API)
     * - Portal implementation (xdg-desktop-portal-gtk/kde/wlr)
     * 
     * Check: pipewire --version && xdg-desktop-portal --version
     */
    private void checkScreenCaptureCapability() {
        javafx.application.Platform.runLater(() -> {
            if (shareScreenButton != null) {
                shareScreenButton.setDisable(false);
                shareScreenButton.setOpacity(1.0);
                shareScreenButton.setTooltip(new javafx.scene.control.Tooltip("Share Screen"));
            }
        });
    }
    
    /**
     * Handle share screen button click
     * Opens screen picker dialog and starts/stops screen sharing
     */
    private void handleShareScreen() {
        if (isSharingScreen) {
            // Stop screen sharing
            stopScreenSharing();
        } else {
            // Start screen sharing
            startScreenSharing();
        }
    }
    
    /**
     * Start screen sharing
     */
    private void startScreenSharing() {
        System.out.println("[ActiveCallDialog] Starting screen share...");
        
        try {
            // Get available sources - WRAPPED IN TRY-CATCH to prevent native crash
            java.util.List<dev.onvoid.webrtc.media.video.desktop.DesktopSource> screens = null;
            java.util.List<dev.onvoid.webrtc.media.video.desktop.DesktopSource> windows = null;
            
            try {
                System.out.println("[ActiveCallDialog] Attempting to enumerate screens...");
                screens = callManager.getWebRTCClient().getAvailableScreens();
                System.out.printf("[ActiveCallDialog] Found %d screens%n", screens != null ? screens.size() : 0);
                
                // Test safety of screens
                if (screens != null && !screens.isEmpty()) {
                    System.out.println("[ActiveCallDialog] Testing screen safety...");
                    java.util.List<dev.onvoid.webrtc.media.video.desktop.DesktopSource> safeScreens = new java.util.ArrayList<>();
                    for (dev.onvoid.webrtc.media.video.desktop.DesktopSource screen : screens) {
                        if (callManager.getWebRTCClient().testSourceSafety(screen, false)) {
                            safeScreens.add(screen);
                        }
                    }
                    screens = safeScreens;
                    System.out.printf("[ActiveCallDialog] ✅ %d safe screens found%n", screens.size());
                }
            } catch (Throwable t) {
                System.err.println("[ActiveCallDialog] ❌ Failed to enumerate screens (native error)");
                System.err.println("[ActiveCallDialog] Error: " + t.getMessage());
                screens = new java.util.ArrayList<>();
            }
            
            try {
                System.out.println("[ActiveCallDialog] Attempting to enumerate windows...");
                windows = callManager.getWebRTCClient().getAvailableWindows();
                System.out.printf("[ActiveCallDialog] Found %d windows (after filtering)%n", windows != null ? windows.size() : 0);
                
                // Test safety of windows
                if (windows != null && !windows.isEmpty()) {
                    System.out.println("[ActiveCallDialog] Testing window safety...");
                    java.util.List<dev.onvoid.webrtc.media.video.desktop.DesktopSource> safeWindows = new java.util.ArrayList<>();
                    for (dev.onvoid.webrtc.media.video.desktop.DesktopSource window : windows) {
                        if (callManager.getWebRTCClient().testSourceSafety(window, true)) {
                            safeWindows.add(window);
                        }
                    }
                    windows = safeWindows;
                    System.out.printf("[ActiveCallDialog] ✅ %d safe windows found%n", windows.size());
                }
            } catch (Throwable t) {
                System.err.println("[ActiveCallDialog] ❌ Failed to enumerate windows (native error)");
                System.err.println("[ActiveCallDialog] Error: " + t.getMessage());
                windows = new java.util.ArrayList<>();
            }
            
            // Check if we have any sources
            if ((screens == null || screens.isEmpty()) && (windows == null || windows.isEmpty())) {
                System.err.println("[ActiveCallDialog] ❌ No safe screens or windows available for sharing");
                
                // Show error dialog to user
                javafx.application.Platform.runLater(() -> {
                    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.ERROR
                    );
                    alert.setTitle("Screen Share Error");
                    alert.setHeaderText("No Safe Screen Sources Available");
                    alert.setContentText(
                        "Unable to find screens or windows that can be safely shared.\n\n" +
                        "This may be due to:\n" +
                        "• Missing screen recording permissions\n" +
                        "• System windows that cannot be captured\n" +
                        "• Native library compatibility issues\n\n" +
                        "Please check system permissions and try again."
                    );
                    alert.showAndWait();
                });
                
                return;
            }
            
            // Load improved screen share picker dialog (Google Meet style)
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                getClass().getResource("/view/ImprovedScreenSharePickerDialog.fxml")
            );
            javafx.scene.layout.BorderPane dialogRoot = loader.load();
            ImprovedScreenSharePickerDialog pickerController = loader.getController();
            
            // Create dialog stage
            Stage dialogStage = new Stage();
            dialogStage.setTitle("Share Your Screen");
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            dialogStage.setScene(new javafx.scene.Scene(dialogRoot));
            pickerController.setDialogStage(dialogStage);
            
            // Set available sources with WebRTC client for thumbnail capture
            pickerController.setAvailableSources(
                screens != null ? screens : new java.util.ArrayList<>(),
                windows != null ? windows : new java.util.ArrayList<>(),
                callManager.getWebRTCClient() // Pass client for thumbnail capture
            );
            
            // Show dialog and wait for user selection
            dialogStage.showAndWait();
            
            // Check if user confirmed
            if (pickerController.isConfirmed()) {
                dev.onvoid.webrtc.media.video.desktop.DesktopSource selectedSource = 
                    pickerController.getSelectedSource();
                boolean isWindow = pickerController.isWindowSelected();
                
                System.out.printf("[ActiveCallDialog] Selected source: id=%d, title=%s, isWindow=%b%n",
                    selectedSource.id, selectedSource.title, isWindow);
                
                // Start screen share via CallManager
                callManager.startScreenShare(selectedSource.id, isWindow);
                
                // Update UI state
                isSharingScreen = true;
                updateShareScreenButton();
                
                System.out.println("[ActiveCallDialog] ✅ Screen sharing started");
            } else {
                System.out.println("[ActiveCallDialog] Screen share cancelled");
            }
            
        } catch (Throwable e) {
            System.err.printf("[ActiveCallDialog] ❌ Error starting screen share: %s%n", e.getMessage());
            e.printStackTrace();
            
            // Show error to user
            javafx.application.Platform.runLater(() -> {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR
                );
                alert.setTitle("Screen Share Error");
                alert.setHeaderText("Failed to Start Screen Sharing");
                alert.setContentText("Error: " + e.getMessage());
                alert.showAndWait();
            });
        }
    }
    
    /**
     * Stop screen sharing
     */
    private void stopScreenSharing() {
        System.out.println("[ActiveCallDialog] Stopping screen share...");
        
        if (callManager != null) {
            callManager.stopScreenShare();
            
            // Update UI state
            isSharingScreen = false;
            updateShareScreenButton();
            
            System.out.println("[ActiveCallDialog] ✅ Screen sharing stopped");
        }
    }
    
    /**
     * Update share screen button appearance
     */
    private void updateShareScreenButton() {
        if (shareScreenButton == null) return;
        
        FontIcon icon = new FontIcon(isSharingScreen ? 
            FontAwesomeSolid.STOP : FontAwesomeSolid.DESKTOP);
        icon.setIconSize(24);
        shareScreenButton.setGraphic(icon);
        
        String color = isSharingScreen ? "#e74c3c" : "#27ae60";
        String tooltip = isSharingScreen ? "Stop Sharing" : "Share Screen";
        
        shareScreenButton.setStyle(String.format(
            "-fx-background-color: %s; -fx-text-fill: white; " +
            "-fx-pref-width: 60px; -fx-pref-height: 60px; " +
            "-fx-background-radius: 30px;",
            color
        ));
        
        shareScreenButton.setTooltip(new javafx.scene.control.Tooltip(tooltip));
    }
    
    /**
     * End the call
     */
    private void endCall() {
        System.out.printf("[ActiveCallDialog] Ending call: %s%n", callId);
        
        stopDurationTimer();
        
        if (callManager != null) {
            callManager.endCall();
        }
        
        close();
    }
    
    /**
     * Start duration timer
     */
    private void startDurationTimer() {
        durationTimer = new Timeline(
            new KeyFrame(javafx.util.Duration.seconds(1), e -> updateDuration())
        );
        durationTimer.setCycleCount(Timeline.INDEFINITE);
        durationTimer.play();
    }
    
    /**
     * Stop duration timer
     */
    private void stopDurationTimer() {
        if (durationTimer != null) {
            durationTimer.stop();
        }
    }
    
    /**
     * Update call duration label
     */
    private void updateDuration() {
        Duration duration = Duration.between(callStartTime, Instant.now());
        long seconds = duration.getSeconds();
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        String durationText;
        if (hours > 0) {
            durationText = String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60);
        } else {
            durationText = String.format("%02d:%02d", minutes, seconds % 60);
        }
        
        durationLabel.setText(durationText);
    }
    
    /**
     * Attach local video track for preview
     */
    public void attachLocalVideo(VideoTrack track) {
        if (localVideoPanel != null && track != null) {
            System.out.println("[ActiveCallDialog] Attaching local video track");
            localVideoPanel.attachVideoTrack(track);
        }
    }
    
    /**
     * Attach remote video track for display
     * With replaceTrack(), same track ID (video0) is used for camera and screen share
     * We simply display the current video track content (camera or screen)
     */
    public void attachRemoteVideo(VideoTrack track) {
        if (track == null) return;
        
        String trackId = track.getId();
        System.out.printf("[ActiveCallDialog] Attaching remote video track: %s%n", trackId);
        
        // With replaceTrack(), the track content changes but ID stays same (video0)
        // Simply update the main video panel with the current track
        System.out.println("[ActiveCallDialog] 📹 Updating video panel with current track");
        
        if (remoteVideoPanel != null) {
            // Detach old track first to ensure clean update
            remoteVideoPanel.detachVideoTrack();
            
            // Attach new track (will show camera or screen share depending on sender)
            remoteVideoPanel.attachVideoTrack(track);
            if (isShowingScreen) {
                remoteVideoPanel.pauseRendering();
            } else {
                remoteVideoPanel.resumeRendering();
            }
            
            System.out.printf("[ActiveCallDialog] ✅ Video track attached: %s%n", trackId);
        }
    }
    
    /**
     * Toggle between screen share and camera view
     */
    private void toggleScreenView() {
        if (isShowingScreen) {
            switchToCameraView();
        } else {
            switchToScreenView();
        }
    }
    
    /**
     * Switch to screen share view
     */
    private void switchToScreenView() {
        if (!hasRemoteScreen) {
            System.out.println("[ActiveCallDialog] ⚠️ No screen share available");
            return;
        }
        
        System.out.println("[ActiveCallDialog] 🖥️ Switching to screen view");
        
        remoteVideoPanel.setVisible(false);
        remoteVideoPanel.setManaged(false);
        remoteVideoPanel.pauseRendering();
        
        remoteScreenPanel.setVisible(true);
        remoteScreenPanel.setManaged(true);
        remoteScreenPanel.resumeRendering();
        
        isShowingScreen = true;
        
        // Update button
        if (screenToggleButton != null) {
            FontIcon icon = new FontIcon(FontAwesomeSolid.VIDEO);
            icon.setIconSize(24);
            screenToggleButton.setGraphic(icon);
        }
    }
    
    /**
     * Switch to camera view
     */
    private void switchToCameraView() {
        System.out.println("[ActiveCallDialog] 📹 Switching to camera view");
        
        remoteScreenPanel.setVisible(false);
        remoteScreenPanel.setManaged(false);
        remoteScreenPanel.pauseRendering();
        
        remoteVideoPanel.setVisible(true);
        remoteVideoPanel.setManaged(true);
        remoteVideoPanel.resumeRendering();
        
        isShowingScreen = false;
        
        // Update button
        if (screenToggleButton != null) {
            FontIcon icon = new FontIcon(FontAwesomeSolid.DESKTOP);
            icon.setIconSize(24);
            screenToggleButton.setGraphic(icon);
        }
    }
    
    /**
     * Detach all video tracks
     */
    private void detachVideoTracks() {
        if (localVideoPanel != null) {
            localVideoPanel.detachVideoTrack();
        }
        if (remoteVideoPanel != null) {
            remoteVideoPanel.detachVideoTrack();
        }
        if (remoteScreenPanel != null) {
            remoteScreenPanel.detachVideoTrack();
        }
    }
    
    /**
     * Handle remote screen share stop
     * Called when remote peer stops sharing screen
     */
    public void onRemoteScreenShareStopped() {
        System.out.println("[ActiveCallDialog] 🛑 Remote screen share stopped");
        
        javafx.application.Platform.runLater(() -> {
            hasRemoteScreen = false;
            
            // Hide screen toggle button
            if (screenToggleButton != null) {
                screenToggleButton.setVisible(false);
                screenToggleButton.setManaged(false);
            }
            
            // If currently showing screen, switch back to camera
            if (isShowingScreen) {
                switchToCameraView();
            }
            
            // Detach screen track
            if (remoteScreenPanel != null) {
                remoteScreenPanel.detachVideoTrack();
                remoteScreenPanel.pauseRendering();
            }
            
            if (remoteVideoPanel != null) {
                remoteVideoPanel.resumeRendering();
            }
        });
    }
    
    /**
     * Register callback to receive remote video tracks
     */
    private void registerRemoteTrackCallback() {
        callManager.setOnRemoteTrackCallback(track -> {
            javafx.application.Platform.runLater(() -> {
                System.out.printf("[ActiveCallDialog] 📺 Remote track received: kind=%s, id=%s%n", 
                    track.getKind(), track.getId());
                
                // Only attach video tracks
                if (track instanceof dev.onvoid.webrtc.media.video.VideoTrack) {
                    dev.onvoid.webrtc.media.video.VideoTrack videoTrack = 
                        (dev.onvoid.webrtc.media.video.VideoTrack) track;
                    attachRemoteVideo(videoTrack);
                    System.out.println("[ActiveCallDialog] 📹 Remote video attached");
                } else {
                    System.out.println("[ActiveCallDialog] 🎤 Remote audio track (auto-handled)");
                }
            });
        });
        System.out.println("[ActiveCallDialog] ✅ Remote track callback registered");
    }
    
    /**
     * Show the dialog
     */
    public void show() {
        stage.show();
        startDurationTimer();
    }
    
    /**
     * Close the dialog
     */
    public void close() {
        stopDurationTimer();
        detachVideoTracks(); // Clean up video resources
        callManager.setOnRemoteTrackCallback(null);
        if (stage.isShowing()) {
            stage.close();
        }
    }
    
    /**
     * Get the call ID
     */
    public String getCallId() {
        return callId;
    }
    
    /**
     * Get the remote username
     */
    public String getRemoteUsername() {
        return remoteUsername;
    }
    
    /**
     * Check if microphone is muted
     */
    public boolean isMuted() {
        return isMuted;
    }
    
    /**
     * Check if camera is on
     */
    public boolean isCameraOn() {
        return isCameraOn;
    }
}
