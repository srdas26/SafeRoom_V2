package com.saferoom.gui.controller;

import com.jfoenix.controls.JFXButton;
import com.saferoom.gui.MainApp;
import com.saferoom.gui.components.VideoPanel;
import com.saferoom.gui.controller.strategy.AdminRoleStrategy;
import com.saferoom.gui.controller.strategy.MeetingRoleStrategy;
import com.saferoom.gui.controller.strategy.UserRoleStrategy;
import com.saferoom.gui.model.Meeting;
import com.saferoom.gui.model.Participant;
import com.saferoom.gui.model.UserRole;
import com.saferoom.gui.utils.WindowStateManager;
import com.saferoom.webrtc.GroupCallManager;
import com.saferoom.webrtc.WebRTCClient;
import com.saferoom.webrtc.WebRTCSignalingClient;
import dev.onvoid.webrtc.media.video.VideoTrack;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MeetingPanelController {

    // FXML Değişkenleri
    @FXML private AnchorPane centerContentAnchorPane;
    @FXML private StackPane contentStackPane;
    @FXML private VBox rightPanelVBox;
    @FXML private ToggleButton participantsToggle;
    @FXML private ToggleButton chatToggle;
    @FXML private ScrollPane participantsScrollPane;
    @FXML private BorderPane meetingChatView;
    @FXML private ChatViewController meetingChatViewController;
    @FXML private GridPane videoGrid;
    @FXML private BorderPane speakerViewPane;
    @FXML private Label meetingNameLabel;
    @FXML private Label timerLabel;
    @FXML private JFXButton participantsButtonTop;
    @FXML private JFXButton gridViewButton;
    @FXML private JFXButton speakerViewButton;
    @FXML private VBox participantsListVBox;
    @FXML private JFXButton micButton;
    @FXML private JFXButton cameraButton;
    @FXML private JFXButton chatButtonBottom;
    @FXML private JFXButton screenShareButton;
    @FXML private JFXButton recordButton;
    @FXML private JFXButton leaveButton;

    // Window state manager for dragging functionality
    private WindowStateManager windowStateManager = new WindowStateManager();
    private MainController mainController;

    // Sınıf Değişkenleri
    private Meeting currentMeeting;
    private Participant currentUser;
    private MeetingRoleStrategy roleStrategy;
    private boolean isPanelOpen = false;
    private final Duration animationDuration = Duration.millis(350);
    private final DoubleProperty contentRightAnchorProperty = new SimpleDoubleProperty(0.0);
    
    // ===============================
    // GROUP CALL MANAGER
    // ===============================
    private GroupCallManager groupCallManager;
    private WebRTCSignalingClient signalingClient;
    private String currentUsername;
    
    // Video tile management: peerId → VideoTile
    private final Map<String, VideoTile> videoTiles = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Video tile container with VideoPanel
     */
    private static class VideoTile {
        StackPane container;
        VideoPanel videoPanel;
        Label nameLabel;
        FontIcon micIcon;
        String peerUsername;
        
        VideoTile(String username) {
            this.peerUsername = username;
            this.container = new StackPane();
            container.getStyleClass().add("video-tile");
            
            // Video panel for rendering
            videoPanel = new VideoPanel(320, 240);
            videoPanel.widthProperty().bind(container.widthProperty());
            videoPanel.heightProperty().bind(container.heightProperty());
            
            // Name tag overlay
            HBox nameTag = new HBox(6);
            nameTag.setAlignment(Pos.CENTER_LEFT);
            nameTag.getStyleClass().add("video-tile-nametag");
            
            micIcon = new FontIcon("fas-microphone");
            micIcon.getStyleClass().add("video-tile-mic-icon");
            
            nameLabel = new Label(username);
            nameLabel.getStyleClass().add("video-tile-name-label");
            
            nameTag.getChildren().addAll(micIcon, nameLabel);
            StackPane.setAlignment(nameTag, Pos.BOTTOM_LEFT);
            StackPane.setMargin(nameTag, new Insets(10));
            
            container.getChildren().addAll(videoPanel, nameTag);
        }
        
        void attachVideoTrack(VideoTrack track) {
            videoPanel.attachVideoTrack(track);
        }
        
        void detachVideoTrack() {
            videoPanel.detachVideoTrack();
        }
        
        void setMuted(boolean muted) {
            micIcon.setIconLiteral(muted ? "fas-microphone-slash" : "fas-microphone");
            if (muted) {
                micIcon.getStyleClass().add("icon-danger");
            } else {
                micIcon.getStyleClass().remove("icon-danger");
            }
        }
        
        void dispose() {
            videoPanel.dispose();
        }
    }

    @FXML
    public void initialize() {
        // Pencere sürükleme işlevini etkinleştir
        windowStateManager.setupBasicWindowDrag(centerContentAnchorPane);
        
        contentRightAnchorProperty.addListener((obs, oldValue, newValue) -> {
            AnchorPane.setRightAnchor(contentStackPane, newValue.doubleValue());
            AnchorPane.setRightAnchor(rightPanelVBox, newValue.doubleValue() - rightPanelVBox.getPrefWidth());
        });
        AnchorPane.setRightAnchor(contentStackPane, 0.0);
        AnchorPane.setRightAnchor(rightPanelVBox, -rightPanelVBox.getPrefWidth());
        rightPanelVBox.setVisible(false);
        rightPanelVBox.setManaged(false);
        micButton.setOnAction(e -> toggleMic());
        cameraButton.setOnAction(e -> toggleCamera());
        gridViewButton.setOnAction(e -> showGridView());
        speakerViewButton.setOnAction(e -> showSpeakerView());
        participantsToggle.setOnAction(e -> showParticipantsView());
        chatToggle.setOnAction(e -> showChatView());
    }

    public void initData(Meeting meeting, UserRole userRole) {
        this.currentMeeting = meeting;
        this.meetingNameLabel.setText(meeting.getMeetingName());

        if (userRole == UserRole.ADMIN) {
            this.roleStrategy = new AdminRoleStrategy();
            this.currentUser = new Participant("Admin User (You)", UserRole.ADMIN, true, false);
        } else {
            this.roleStrategy = new UserRoleStrategy();
            this.currentUser = new Participant("Standard User (You)", UserRole.USER, true, false);
        }

        // Don't add dummy participants - we'll use real peers
        currentMeeting.getParticipants().clear();
        currentMeeting.getParticipants().add(0, this.currentUser);
        
        updateParticipantsList();
        showGridView();

        if (meetingChatViewController != null) {
            meetingChatViewController.initChannel(currentMeeting.getMeetingId());
            meetingChatViewController.setHeader("Toplantı Sohbeti", "Online", "G", true);
            meetingChatViewController.setWidthConstraint(rightPanelVBox.getPrefWidth());
            meetingChatViewController.setHeaderVisible(false);
        }
        updateMicButtonState();
        updateCameraButtonState();
        
        // ===============================
        // INITIALIZE GROUP CALL
        // ===============================
        initializeGroupCall(meeting.getMeetingId(), userRole);
    }
    
    /**
     * Initialize group call manager and join room
     */
    private void initializeGroupCall(String roomId, UserRole userRole) {
        try {
            // Get current username (from login session)
            this.currentUsername = getCurrentUsername();
            
            System.out.printf("[MeetingPanel] Initializing group call for room: %s, user: %s%n", 
                roomId, currentUsername);
            
            // Initialize WebRTC if not already done
            if (!WebRTCClient.isInitialized()) {
                WebRTCClient.initialize();
            }
            
            // Create signaling client
            signalingClient = new WebRTCSignalingClient(currentUsername);
            signalingClient.startSignalingStream();
            
            // Get GroupCallManager instance
            groupCallManager = GroupCallManager.getInstance();
            groupCallManager.initialize(signalingClient, currentUsername);
            
            // Setup callbacks
            setupGroupCallCallbacks();
            
            // Join room
            boolean audio = !currentUser.isMuted();
            boolean video = currentUser.isCameraOn();
            
            groupCallManager.joinRoom(roomId, audio, video)
                .thenAccept(v -> {
                    System.out.println("[MeetingPanel] Successfully joined room");
                })
                .exceptionally(ex -> {
                    System.err.printf("[MeetingPanel] Failed to join room: %s%n", ex.getMessage());
                    ex.printStackTrace();
                    return null;
                });
            
        } catch (Exception e) {
            System.err.printf("[MeetingPanel] Error initializing group call: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Setup GroupCallManager callbacks
     */
    private void setupGroupCallCallbacks() {
        // Room joined callback
        groupCallManager.setOnRoomJoinedCallback(() -> {
            javafx.application.Platform.runLater(() -> {
                System.out.println("[MeetingPanel] Room joined callback");
                updateVideoGrid();
            });
        });
        
        // Peer joined callback
        groupCallManager.setOnPeerJoinedCallback(peerUsername -> {
            javafx.application.Platform.runLater(() -> {
                System.out.printf("[MeetingPanel] Peer joined: %s%n", peerUsername);
                
                // Add peer to participant list
                Participant peer = new Participant(peerUsername, UserRole.USER, false, true);
                currentMeeting.getParticipants().add(peer);
                
                updateParticipantsList();
                updateVideoGrid();
            });
        });
        
        // Peer left callback
        groupCallManager.setOnPeerLeftCallback(peerUsername -> {
            javafx.application.Platform.runLater(() -> {
                System.out.printf("[MeetingPanel] Peer left: %s%n", peerUsername);
                
                // Remove peer from participant list
                currentMeeting.getParticipants().removeIf(p -> p.getName().equals(peerUsername));
                
                // Remove video tile
                VideoTile tile = videoTiles.remove(peerUsername);
                if (tile != null) {
                    tile.dispose();
                }
                
                updateParticipantsList();
                updateVideoGrid();
            });
        });
        
        // Remote track callback (video/audio from peer)
        groupCallManager.setOnRemoteTrackCallback((peerUsername, track) -> {
            javafx.application.Platform.runLater(() -> {
                System.out.printf("[MeetingPanel] Remote track from %s: %s%n", 
                    peerUsername, track.getKind());
                
                if ("video".equals(track.getKind())) {
                    // Get or create video tile for this peer
                    VideoTile tile = videoTiles.get(peerUsername);
                    if (tile == null) {
                        tile = new VideoTile(peerUsername);
                        videoTiles.put(peerUsername, tile);
                    }
                    
                    // Attach video track
                    tile.attachVideoTrack((VideoTrack) track);
                    
                    // Update grid to show new video
                    updateVideoGrid();
                }
            });
        });
    }
    
    /**
     * Get current username from login session
     * TODO: Replace with actual session management
     */
    private String getCurrentUsername() {
        // For now, extract from currentUser name
        // In production, get from SessionManager or AuthService
        String name = currentUser.getName();
        if (name.contains("(You)")) {
            name = name.replace(" (You)", "").trim();
        }
        return name.isEmpty() ? "User-" + System.currentTimeMillis() : name;
    }

    /**
     * Ana controller referansını ayarlar (geri dönüş için)
     */
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
        // Meeting panel açıldığında da sidebar'ı gizle
        if (mainController != null) {
            mainController.hideSidebarForFullScreen();
        }
    }

    private Node createParticipantListItem(Participant participant) {
        HBox itemBox = new HBox(12);
        itemBox.setAlignment(Pos.CENTER_LEFT);
        itemBox.setPadding(new Insets(8, 16, 8, 16));
        itemBox.getStyleClass().add("participant-list-item");

        Label avatar = new Label(participant.getName().substring(0, 1));
        avatar.getStyleClass().add("participant-avatar");
        VBox nameBox = new VBox(-2);
        Label nameLabel = new Label(participant.getName());
        nameLabel.getStyleClass().add("participant-name");
        Label roleLabel = new Label(participant.getRole() == UserRole.ADMIN ? "Admin" : "Member");
        roleLabel.getStyleClass().add("participant-role");
        nameBox.getChildren().addAll(nameLabel, roleLabel);
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        FontIcon micIcon = new FontIcon(participant.isMuted() ? "fas-microphone-slash" : "fas-microphone");
        micIcon.getStyleClass().add("participant-list-icon");
        if (participant.isMuted()) micIcon.getStyleClass().add("icon-danger");
        FontIcon camIcon = new FontIcon(participant.isCameraOn() ? "fas-video" : "fas-video-slash");
        camIcon.getStyleClass().add("participant-list-icon");
        if (!participant.isCameraOn()) camIcon.getStyleClass().add("icon-danger");

        itemBox.getChildren().addAll(avatar, nameBox, spacer, micIcon, camIcon);

        List<MenuItem> menuItems = roleStrategy.createParticipantMenuItems(currentUser, participant, this::updateParticipantsList);

        if (menuItems.isEmpty()) {
            return itemBox;
        }

        // ## DÜZELTME: Her satır için kendi ContextMenu nesnesini oluşturuyoruz ##
        final ContextMenu contextMenu = new ContextMenu();
        contextMenu.getItems().addAll(menuItems);
        contextMenu.getStyleClass().add("context-menu");

        JFXButton optionsButton = new JFXButton();
        FontIcon icon = new FontIcon("fas-ellipsis-v");
        icon.getStyleClass().add("participant-action-icon");
        optionsButton.setGraphic(icon);
        optionsButton.getStyleClass().add("participant-action-button");

        // ## DÜZELTME: Kapanma mantığı eklendi ##
        optionsButton.setOnAction(event -> {
            if (contextMenu.isShowing()) {
                contextMenu.hide();
            } else {
                // Bu metot, pencere taşmalarına karşı daha güvenlidir.
                contextMenu.show(optionsButton, Side.BOTTOM, -120, 5);
            }
        });

        // ## DÜZELTME: Sağ tık için en güvenli gösterme metodu kullanıldı ##
        itemBox.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                if (contextMenu.isShowing()) {
                    contextMenu.hide();
                } else {
                    // Bu metot, JavaFX'in pencere sınırlarını otomatik olarak algılaması için en iyisidir.
                    contextMenu.show(itemBox.getScene().getWindow(), event.getScreenX(), event.getScreenY());
                }
            }
        });

        itemBox.getChildren().add(optionsButton);
        return itemBox;
    }

    public void updateParticipantsList() {
        participantsListVBox.getChildren().clear();
        for (Participant p : currentMeeting.getParticipants()) {
            participantsListVBox.getChildren().add(createParticipantListItem(p));
        }
    }

    @FXML
    private void toggleMic() {
        if (currentUser == null) return;
        boolean newMutedState = !currentUser.isMuted();
        currentUser.setMuted(newMutedState);
        updateMicButtonState();
        
        // Update group call audio
        if (groupCallManager != null) {
            groupCallManager.toggleAudio(!newMutedState); // unmuted = audio enabled
        }
    }

    @FXML
    private void toggleCamera() {
        if (currentUser == null) return;
        boolean newCameraState = !currentUser.isCameraOn();
        currentUser.setCameraOn(newCameraState);
        updateCameraButtonState();
        
        // Update group call video
        if (groupCallManager != null) {
            groupCallManager.toggleVideo(newCameraState);
            
            // Refresh local video tile
            updateVideoGrid();
        }
    }

    private void updateMicButtonState() {
        if (currentUser.isMuted()) {
            micButton.setText("Unmute");
            micButton.getStyleClass().add("active");
            if(micButton.getGraphic() instanceof FontIcon) {
                ((FontIcon) micButton.getGraphic()).setIconLiteral("fas-microphone-slash");
            }
        } else {
            micButton.setText("Mute");
            micButton.getStyleClass().remove("active");
            if(micButton.getGraphic() instanceof FontIcon) {
                ((FontIcon) micButton.getGraphic()).setIconLiteral("fas-microphone");
            }
        }
    }

    private void updateCameraButtonState() {
        if (!currentUser.isCameraOn()) {
            cameraButton.setText("Start Video");
            cameraButton.getStyleClass().add("active");
            if(cameraButton.getGraphic() instanceof FontIcon) {
                ((FontIcon) cameraButton.getGraphic()).setIconLiteral("fas-video-slash");
            }
        } else {
            cameraButton.setText("Stop Video");
            cameraButton.getStyleClass().remove("active");
            if(cameraButton.getGraphic() instanceof FontIcon) {
                ((FontIcon) cameraButton.getGraphic()).setIconLiteral("fas-video");
            }
        }
    }

    private void toggleRightPanel(Runnable onAnimationFinished) {
        final boolean closing = isPanelOpen;

        if (!closing) {
            rightPanelVBox.setManaged(true);
            rightPanelVBox.setVisible(true);
        }

        Timeline timeline = new Timeline();
        double targetAnchorValue = closing ? 0.0 : rightPanelVBox.getPrefWidth();
        KeyValue keyValue = new KeyValue(contentRightAnchorProperty, targetAnchorValue);
        KeyFrame keyFrame = new KeyFrame(animationDuration, keyValue);
        timeline.getKeyFrames().add(keyFrame);

        timeline.setOnFinished(e -> {
            if (closing) {
                rightPanelVBox.setVisible(false);
                rightPanelVBox.setManaged(false);
            }
            if (onAnimationFinished != null) {
                onAnimationFinished.run();
            }
        });

        timeline.play();
        isPanelOpen = !closing;
    }

    @FXML
    private void toggleRightPanelAndGoToParticipants() {
        Runnable switchToParticipants = () -> {
            participantsToggle.setSelected(true);
            showParticipantsView();
        };

        if (isPanelOpen) {
            toggleRightPanel(switchToParticipants);
        } else {
            switchToParticipants.run();
            toggleRightPanel(null);
        }
    }

    @FXML
    private void toggleRightPanelAndGoToChat() {
        if (isPanelOpen && chatToggle.isSelected()) {
            toggleRightPanel(null);
        } else {
            chatToggle.setSelected(true);
            showChatView();
            if (!isPanelOpen) {
                toggleRightPanel(null);
            }
        }
    }

    private void showParticipantsView() {
        participantsScrollPane.setVisible(true);
        if (meetingChatView != null) {
            meetingChatView.setVisible(false);
        }
    }

    private void showChatView() {
        participantsScrollPane.setVisible(false);
        if (meetingChatView != null) {
            meetingChatView.setVisible(true);
        }
    }

    private void showGridView() {
        videoGrid.setVisible(true);
        speakerViewPane.setVisible(false);
        updateVideoGrid();
        setActiveLayoutButton(gridViewButton);
    }

    private void showSpeakerView() {
        videoGrid.setVisible(false);
        speakerViewPane.setVisible(true);
        updateSpeakerView();
        setActiveLayoutButton(speakerViewButton);
    }

    private void setActiveLayoutButton(JFXButton activeButton) {
        gridViewButton.getStyleClass().remove("active");
        speakerViewButton.getStyleClass().remove("active");
        activeButton.getStyleClass().add("active");
    }

    private void updateSpeakerView() {
        // TODO: Implement speaker view with real video tracks
        // For now, just show grid view
        System.out.println("[MeetingPanel] Speaker view not yet implemented for group calls");
        showGridView();
    }

    private void updateVideoGrid() {
        videoGrid.getChildren().clear();
        videoGrid.getColumnConstraints().clear();
        videoGrid.getRowConstraints().clear();
        
        // Count: local user + remote peers with video
        int numVideos = 1 + videoTiles.size(); // 1 for local preview + N remote peers
        
        if (numVideos == 0) {
            System.out.println("[MeetingPanel] No videos to display");
            return;
        }
        
        System.out.printf("[MeetingPanel] Updating video grid: %d videos%n", numVideos);
        
        // Calculate grid dimensions (Zoom-style dynamic layout)
        int numColumns = (int) Math.ceil(Math.sqrt(numVideos));
        int numRows = (int) Math.ceil((double) numVideos / numColumns);
        
        // Setup grid constraints
        for (int i = 0; i < numColumns; i++) {
            ColumnConstraints colConst = new ColumnConstraints();
            colConst.setPercentWidth(100.0 / numColumns);
            colConst.setHgrow(Priority.ALWAYS);
            videoGrid.getColumnConstraints().add(colConst);
        }
        for (int i = 0; i < numRows; i++) {
            RowConstraints rowConst = new RowConstraints();
            rowConst.setPercentHeight(100.0 / numRows);
            rowConst.setVgrow(Priority.ALWAYS);
            videoGrid.getRowConstraints().add(rowConst);
        }
        
        int col = 0;
        int row = 0;
        
        // Add local video tile (self-preview)
        VideoTile localTile = createLocalVideoTile();
        GridPane.setHgrow(localTile.container, Priority.ALWAYS);
        GridPane.setVgrow(localTile.container, Priority.ALWAYS);
        videoGrid.add(localTile.container, col, row);
        
        col++;
        if (col >= numColumns) {
            col = 0;
            row++;
        }
        
        // Add remote peer video tiles
        for (Map.Entry<String, VideoTile> entry : videoTiles.entrySet()) {
            VideoTile tile = entry.getValue();
            GridPane.setHgrow(tile.container, Priority.ALWAYS);
            GridPane.setVgrow(tile.container, Priority.ALWAYS);
            videoGrid.add(tile.container, col, row);
            
            col++;
            if (col >= numColumns) {
                col = 0;
                row++;
            }
        }
        
        System.out.printf("[MeetingPanel] Grid layout: %dx%d for %d videos%n", 
            numColumns, numRows, numVideos);
    }
    
    /**
     * Create local video tile (self-preview)
     */
    private VideoTile createLocalVideoTile() {
        VideoTile tile = new VideoTile(currentUser.getName());
        
        // Attach local video track if available
        if (groupCallManager != null) {
            VideoTrack localTrack = groupCallManager.getLocalVideoTrack();
            if (localTrack != null) {
                tile.attachVideoTrack(localTrack);
            }
        }
        
        // Set mute state
        tile.setMuted(currentUser.isMuted());
        
        return tile;
    }

    // OLD METHOD - kept for reference, not used in group calls
    /*
    private StackPane createVideoTile(Participant participant) {
        StackPane tile = new StackPane();
        tile.getStyleClass().add("video-tile");
        if (!participant.isCameraOn()) {
            Label avatarLabel = new Label(participant.getName().substring(0, 1));
            avatarLabel.getStyleClass().add("video-tile-avatar-label");
            tile.getChildren().add(avatarLabel);
        }
        HBox nameTag = new HBox(6);
        nameTag.setAlignment(Pos.CENTER_LEFT);
        nameTag.getStyleClass().add("video-tile-nametag");
        FontIcon micIcon = new FontIcon(participant.isMuted() ? "fas-microphone-slash" : "fas-microphone");
        micIcon.getStyleClass().add("video-tile-mic-icon");
        if (participant.isMuted()) micIcon.getStyleClass().add("icon-danger");
        Label nameLabel = new Label(participant.getName());
        nameLabel.getStyleClass().add("video-tile-name-label");
        nameTag.getChildren().addAll(micIcon, nameLabel);
        StackPane.setAlignment(nameTag, Pos.BOTTOM_LEFT);
        StackPane.setMargin(nameTag, new Insets(10));
        tile.getChildren().add(nameTag);
        return tile;
    }
    */

    @FXML
    private void leaveMeeting() {
        System.out.println("[MeetingPanel] Leaving meeting...");
        
        // Cleanup group call
        if (groupCallManager != null) {
            groupCallManager.leaveRoom()
                .thenAccept(v -> {
                    System.out.println("[MeetingPanel] Left room successfully");
                })
                .exceptionally(ex -> {
                    System.err.printf("[MeetingPanel] Error leaving room: %s%n", ex.getMessage());
                    return null;
                });
        }
        
        // Dispose all video tiles
        for (VideoTile tile : videoTiles.values()) {
            tile.dispose();
        }
        videoTiles.clear();
        
        // Stop signaling
        if (signalingClient != null) {
            signalingClient.stopSignalingStream();
        }
        
        // Ana pencereye geri dön (content değiştirme yaklaşımı)
        if (mainController != null) {
            mainController.returnToMainView();
        }
    }
}