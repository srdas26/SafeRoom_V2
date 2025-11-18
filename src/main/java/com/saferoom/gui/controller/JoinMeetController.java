package com.saferoom.gui.controller;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXSlider;
import com.jfoenix.controls.JFXToggleButton;
import com.saferoom.gui.MainApp;
import com.saferoom.gui.components.VideoPanel;
import com.saferoom.gui.model.Meeting;
import com.saferoom.gui.model.UserRole;
import com.saferoom.gui.utils.WindowStateManager;
import com.saferoom.webrtc.WebRTCClient;
import dev.onvoid.webrtc.media.video.*;
import dev.onvoid.webrtc.media.MediaDevices;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.util.Duration;

import java.io.IOException;
import java.util.Objects;
import java.util.Random;

public class JoinMeetController {

    @FXML private JFXButton backButton;
    @FXML private TextField roomIdField;
    @FXML private Button joinButton;
    @FXML private JFXComboBox<String> audioInputBox;
    @FXML private JFXComboBox<String> audioOutputBox;
    @FXML private JFXComboBox<String> cameraSourceBox;
    @FXML private JFXToggleButton cameraToggle;
    @FXML private JFXToggleButton micToggle;
    @FXML private ProgressBar micTestBar;
    @FXML private JFXSlider inputVolumeSlider;
    @FXML private JFXSlider outputVolumeSlider;
    @FXML private javafx.scene.layout.StackPane cameraView; // Kamera preview alanı

    // Window state manager for dragging functionality
    private WindowStateManager windowStateManager = new WindowStateManager();
    private MainController mainController;

    private Timeline micAnimation;
    private Scene returnScene;
    
    // Camera preview
    private VideoPanel cameraPreview;
    private VideoDeviceSource videoSource;
    private VideoTrack videoTrack;

    /**
     * Ana controller referansını ayarlar (geri dönüş için)
     */
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    public void setReturnScene(Scene scene) {
        this.returnScene = scene;
    }

    @FXML
    public void initialize() {
        // Root pane'i bulup sürükleme işlevini etkinleştir
        // Bu view'da herhangi bir pane referansı olmadığı için, scene yüklendikten sonra eklenecek
        
        audioInputBox.getItems().addAll("Default - MacBook Pro Microphone", "External USB Mic");
        audioOutputBox.getItems().addAll("Default - MacBook Pro Speakers", "Bluetooth Headphones");
        cameraSourceBox.getItems().addAll("FaceTime HD Camera", "External Webcam");

        audioInputBox.getSelectionModel().selectFirst();
        audioOutputBox.getSelectionModel().selectFirst();
        cameraSourceBox.getSelectionModel().selectFirst();

        backButton.setOnAction(event -> handleBack());
        joinButton.setOnAction(event -> handleJoin());

        startMicTestAnimation();
        
        // Initialize camera preview
        initializeCameraPreview();
        
        // Camera toggle listener
        cameraToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
            toggleCameraPreview(newVal);
        });
        
        // Scene yüklendiğinde root pane'i bul ve sürükleme ekle
        backButton.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null && newScene.getRoot() instanceof javafx.scene.layout.Pane) {
                windowStateManager.setupBasicWindowDrag((javafx.scene.layout.Pane) newScene.getRoot());
            }
        });
    }
    
    /**
     * Initialize camera preview in Join Room
     */
    private void initializeCameraPreview() {
        try {
            System.out.println("[JoinMeet] Initializing camera preview...");
            
            // Check if cameraView is injected
            if (cameraView == null) {
                System.err.println("[JoinMeet] cameraView is null - check FXML fx:id");
                return;
            }
            
            // Initialize WebRTC if not already done
            if (!WebRTCClient.isInitialized()) {
                WebRTCClient.initialize();
            }
            
            // Create VideoPanel with initial size
            cameraPreview = new VideoPanel(640, 480);
            
            // CRITICAL FIX: Wrap Canvas in custom Pane that controls Canvas size
            // Pane's layoutChildren prevents Canvas resize from affecting parent
            javafx.scene.layout.Pane canvasWrapper = new javafx.scene.layout.Pane() {
                @Override
                protected void layoutChildren() {
                    super.layoutChildren();
                    // Resize Canvas to match Pane size (Pane size is controlled by StackPane)
                    double w = getWidth();
                    double h = getHeight();
                    if (w > 0 && h > 0 && cameraPreview != null) {
                        cameraPreview.setWidth(w);
                        cameraPreview.setHeight(h);
                    }
                }
            };
            
            // Add Canvas to wrapper
            canvasWrapper.getChildren().add(cameraPreview);
            
            // Make wrapper fill StackPane (managed by parent)
            canvasWrapper.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            
            // Add wrapper to cameraView
            cameraView.getChildren().clear();
            cameraView.getChildren().add(canvasWrapper);
            
            System.out.println("[JoinMeet] Camera preview wrapped in Pane (feedback-proof)");
            
            // Start camera if toggle is ON
            if (cameraToggle.isSelected()) {
                startCameraPreview();
            } else {
                // Show placeholder when camera is OFF
                showCameraPlaceholder();
            }
            
        } catch (Exception e) {
            System.err.println("[JoinMeet] Failed to initialize camera preview: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Show placeholder when camera is disabled
     */
    private void showCameraPlaceholder() {
        javafx.scene.layout.VBox placeholder = new javafx.scene.layout.VBox(10);
        placeholder.setAlignment(javafx.geometry.Pos.CENTER);
        
        org.kordamp.ikonli.javafx.FontIcon icon = new org.kordamp.ikonli.javafx.FontIcon("fas-video-slash");
        icon.setIconSize(64);
        icon.setStyle("-fx-icon-color: #6b7280;");
        
        javafx.scene.control.Label label = new javafx.scene.control.Label("Camera is disabled");
        label.setStyle("-fx-text-fill: #9ca3af; -fx-font-size: 14px;");
        
        placeholder.getChildren().addAll(icon, label);
        
        // Add on top of VideoPanel
        if (!cameraView.getChildren().contains(placeholder)) {
            cameraView.getChildren().add(placeholder);
        }
    }
    
    /**
     * Hide placeholder
     */
    private void hideCameraPlaceholder() {
        // Remove any VBox (placeholder) from cameraView, keep only VideoPanel
        cameraView.getChildren().removeIf(node -> node instanceof javafx.scene.layout.VBox);
    }
    
    /**
     * Start camera preview
     */
    private void startCameraPreview() {
        try {
            System.out.println("[JoinMeet] Starting camera preview...");
            
            var factory = WebRTCClient.getFactory();
            if (factory == null) {
                System.err.println("[JoinMeet] Factory not available");
                return;
            }
            
            // Get available cameras
            java.util.List<VideoDevice> cameras = MediaDevices.getVideoCaptureDevices();
            if (cameras.isEmpty()) {
                System.err.println("[JoinMeet] No cameras found!");
                return;
            }
            
            // Use first camera
            VideoDevice camera = cameras.get(0);
            System.out.println("[JoinMeet] Using camera: " + camera.getName());
            
            // Create video source
            videoSource = new VideoDeviceSource();
            videoSource.setVideoCaptureDevice(camera);
            
            // Set capability
            VideoCaptureCapability capability = new VideoCaptureCapability(640, 480, 30);
            videoSource.setVideoCaptureCapability(capability);
            
            // Create video track
            videoTrack = factory.createVideoTrack("preview_video", videoSource);
            videoTrack.setEnabled(true); // CRITICAL: Enable track
            
            // Start capturing
            videoSource.start();
            
            // Attach to VideoPanel
            if (cameraPreview != null) {
                cameraPreview.attachVideoTrack(videoTrack);
            }
            
            // Hide placeholder
            hideCameraPlaceholder();
            
            System.out.println("[JoinMeet] ✅ Camera preview started");
            
        } catch (Exception e) {
            System.err.println("[JoinMeet] Failed to start camera: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Stop camera preview
     */
    private void stopCameraPreview() {
        try {
            System.out.println("[JoinMeet] Stopping camera preview...");
            
            // Detach from VideoPanel
            if (cameraPreview != null) {
                cameraPreview.detachVideoTrack();
            }
            
            // Stop and dispose video source
            if (videoSource != null) {
                videoSource.stop();
                videoSource.dispose();
                videoSource = null;
            }
            
            // Dispose video track
            if (videoTrack != null) {
                videoTrack.setEnabled(false);
                videoTrack.dispose();
                videoTrack = null;
            }
            
            // Show placeholder
            showCameraPlaceholder();
            
            System.out.println("[JoinMeet] Camera preview stopped");
            
        } catch (Exception e) {
            System.err.println("[JoinMeet] Error stopping camera: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Toggle camera preview on/off
     */
    private void toggleCameraPreview(boolean enabled) {
        if (enabled) {
            startCameraPreview();
        } else {
            stopCameraPreview();
        }
    }

    private void handleJoin() {
        if (micAnimation != null) micAnimation.stop();
        
        // DON'T stop camera preview here - only stop if join is successful
        // Camera should stay active if validation fails

        // =========================================================================
        // Room ID validation
        // =========================================================================
        String roomId = roomIdField.getText();
        if (roomId == null || roomId.trim().isEmpty()) {
            roomIdField.getStyleClass().remove("error-field");
            roomIdField.getStyleClass().add("error-field");
            showErrorAlert("Invalid Room ID", "Please enter a valid Room ID to join.");
            return;
        }

        roomIdField.getStyleClass().remove("error-field");
        
        // =========================================================================
        // VALIDATE ROOM BEFORE OPENING MEETING PANEL
        // Send ROOM_JOIN with JOIN_MODE to check if room exists
        // =========================================================================
        boolean withCamera = cameraToggle.isSelected();
        boolean withMic = micToggle.isSelected();
        
        try {
            System.out.printf("[JoinMeet] Validating room: %s%n", roomId);
            
            // Show loading indicator
            javafx.scene.control.ProgressIndicator progressIndicator = new javafx.scene.control.ProgressIndicator();
            progressIndicator.setPrefSize(50, 50);
            
            javafx.scene.layout.StackPane loadingPane = new javafx.scene.layout.StackPane(progressIndicator);
            loadingPane.setStyle("-fx-background-color: rgba(0, 0, 0, 0.5);");
            
            // Add loading overlay to the parent container (not root)
            // Store references for removal in callbacks
            javafx.scene.Parent root = roomIdField.getScene().getRoot();
            final javafx.scene.layout.StackPane[] overlayWrapper = {null};
            final javafx.scene.layout.Pane[] overlayParent = {null};
            
            if (root instanceof javafx.scene.layout.Pane) {
                overlayParent[0] = (javafx.scene.layout.Pane) root;
                overlayParent[0].getChildren().add(loadingPane);
            } else if (root instanceof javafx.scene.layout.BorderPane) {
                // For BorderPane, overlay on center with wrapper
                overlayWrapper[0] = new javafx.scene.layout.StackPane();
                javafx.scene.Node center = ((javafx.scene.layout.BorderPane) root).getCenter();
                overlayWrapper[0].getChildren().addAll(center, loadingPane);
                ((javafx.scene.layout.BorderPane) root).setCenter(overlayWrapper[0]);
            }
            
            // Initialize WebRTC for validation
            if (!com.saferoom.webrtc.WebRTCClient.isInitialized()) {
                com.saferoom.webrtc.WebRTCClient.initialize();
            }
            
            // Get current username
            String currentUsername = getCurrentUsername();
            
            // Create signaling client for validation
            com.saferoom.webrtc.WebRTCSignalingClient tempSignalingClient = 
                new com.saferoom.webrtc.WebRTCSignalingClient(currentUsername);
            tempSignalingClient.startSignalingStream();
            
            // Create GroupCallManager for validation
            com.saferoom.webrtc.GroupCallManager tempManager = 
                com.saferoom.webrtc.GroupCallManager.getInstance();
            tempManager.initialize(tempSignalingClient, currentUsername);
            
            // Setup error callback for validation
            final boolean[] roomValidated = {false};
            final String[] errorMessage = {null};
            
            tempManager.setOnRoomErrorCallback((errorType, roomIdError) -> {
                javafx.application.Platform.runLater(() -> {
                    // Remove loading overlay
                    if (overlayParent[0] != null) {
                        overlayParent[0].getChildren().remove(loadingPane);
                    } else if (overlayWrapper[0] != null) {
                        // Restore original center content
                        javafx.scene.Parent rootForRemoval = roomIdField.getScene().getRoot();
                        if (rootForRemoval instanceof javafx.scene.layout.BorderPane) {
                            javafx.scene.Node originalCenter = overlayWrapper[0].getChildren().get(0);
                            ((javafx.scene.layout.BorderPane) rootForRemoval).setCenter(originalCenter);
                        }
                    }
                    
                    // Show error
                    if ("ROOM_NOT_FOUND".equals(errorType)) {
                        errorMessage[0] = "Room not found: " + roomIdError + "\nThis room does not exist or has been closed.";
                    } else if ("ROOM_FULL".equals(errorType)) {
                        errorMessage[0] = "Room is full: " + roomIdError + "\nMaximum 4 participants allowed.";
                    } else {
                        errorMessage[0] = "Cannot join room: " + errorType;
                    }
                    
                    showErrorAlert("Room Join Error", errorMessage[0]);
                    
                    // Cleanup temp resources
                    tempManager.leaveRoom();
                    tempSignalingClient.stopSignalingStream();
                });
            });
            
            // Setup success callback
            tempManager.setOnRoomJoinedCallback(() -> {
                javafx.application.Platform.runLater(() -> {
                    roomValidated[0] = true;
                    
                    // Remove loading overlay
                    if (overlayParent[0] != null) {
                        overlayParent[0].getChildren().remove(loadingPane);
                    } else if (overlayWrapper[0] != null) {
                        // Restore original center content
                        javafx.scene.Parent rootForRemoval = roomIdField.getScene().getRoot();
                        if (rootForRemoval instanceof javafx.scene.layout.BorderPane) {
                            javafx.scene.Node originalCenter = overlayWrapper[0].getChildren().get(0);
                            ((javafx.scene.layout.BorderPane) rootForRemoval).setCenter(originalCenter);
                        }
                    }
                    
                    // Leave temporary connection
                    tempManager.leaveRoom();
                    tempSignalingClient.stopSignalingStream();
                    
                    // STOP camera preview ONLY on successful validation
                    stopCameraPreview();
                    
                    // NOW open meeting panel (room validated)
                    openMeetingPanel(roomId, withCamera, withMic);
                });
            });
            
            // Attempt to join room (validation only - no media streaming)
            // Pass false for both audio and video since we're only validating room existence
            tempManager.joinRoom(roomId, false, false, "JOIN_MODE");
            
        } catch (Exception e) {
            System.err.printf("[JoinMeet] Error validating room: %s%n", e.getMessage());
            e.printStackTrace();
            showErrorAlert("Connection Error", "Failed to connect to server: " + e.getMessage());
        }
    }
    
    /**
     * Open meeting panel after successful room validation
     */
    private void openMeetingPanel(String roomId, boolean withCamera, boolean withMic) {
        if (mainController != null) {
            try {
                FXMLLoader meetingLoader = new FXMLLoader(MainApp.class.getResource("/view/MeetingPanelView.fxml"));
                Parent meetingRoot = meetingLoader.load();

                MeetingPanelController meetingController = meetingLoader.getController();
                meetingController.setMainController(mainController);

                String roomName = roomId.isEmpty() ? "Joined Room" : roomId;
                Meeting meetingToJoin = new Meeting(roomId, roomName);

                // Meeting controller'ı JOIN_MODE ile başlat
                meetingController.initData(meetingToJoin, UserRole.USER, withCamera, withMic, "JOIN_MODE");

                // Ana controller'ın content area'sına meeting panel'i yükle
                mainController.contentArea.getChildren().setAll(meetingRoot);

            } catch (IOException e) {
                e.printStackTrace();
                showErrorAlert("UI Error", "Failed to load meeting panel: " + e.getMessage());
            }
        }
    }
    
    /**
     * Show error alert dialog
     */
    private void showErrorAlert(String title, String message) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText("Cannot Join Room");
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    /**
     * Get current username from session
     */
    private String getCurrentUsername() {
        try {
            // Get from UserSession
            String username = com.saferoom.gui.utils.UserSession.getInstance().getDisplayName();
            if (username != null && !username.isEmpty() && !"Username".equals(username)) {
                return username;
            }
        } catch (Exception e) {
            System.err.println("[JoinMeet] Failed to get username from session: " + e.getMessage());
        }
        
        // Fallback: generate temporary username
        return "User_" + System.currentTimeMillis();
    }

    private void startMicTestAnimation() {
        micAnimation = new Timeline(new KeyFrame(Duration.millis(100), event -> {
            if (micToggle.isSelected()) {
                micTestBar.setProgress(new Random().nextDouble() * 0.7);
            } else {
                micTestBar.setProgress(0);
            }
        }));
        micAnimation.setCycleCount(Animation.INDEFINITE);
        micAnimation.play();
    }

    private void handleBack() {
        if (micAnimation != null) micAnimation.stop();
        
        // CRITICAL: Stop camera preview before leaving
        stopCameraPreview();
        
        // Ana controller üzerinden ana görünüme geri dön
        if (mainController != null) {
            mainController.returnToMainView();
        }
    }
}