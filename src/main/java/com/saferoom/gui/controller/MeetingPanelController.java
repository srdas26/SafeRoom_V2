package com.saferoom.gui.controller;

import com.jfoenix.controls.JFXButton;
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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;
import java.util.Map;

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
    private static final double DEFAULT_RIGHT_PANEL_WIDTH = 320.0;
    private final DoubleProperty contentRightAnchorProperty = new SimpleDoubleProperty(0.0);
    
    // ===============================
    // GROUP CALL MANAGER
    // ===============================
    private GroupCallManager groupCallManager;
    private WebRTCSignalingClient signalingClient;
    private String currentUsername;
    
    // Video management: participantName → VideoPanel cache (tek instance)
    private final Map<String, VideoPanel> participantVideoPanels = new java.util.HashMap<>();
    private final Map<String, VideoTrack> remoteVideoTracks = new java.util.concurrent.ConcurrentHashMap<>();  // username → VideoTrack

    @FXML
    public void initialize() {
        // Pencere sürükleme işlevini etkinleştir
        windowStateManager.setupBasicWindowDrag(centerContentAnchorPane);
        
        contentRightAnchorProperty.addListener((obs, oldValue, newValue) -> {
            double panelWidth = getRightPanelWidth();
            AnchorPane.setRightAnchor(contentStackPane, newValue.doubleValue());
            AnchorPane.setRightAnchor(rightPanelVBox, newValue.doubleValue() - panelWidth);
        });
        AnchorPane.setRightAnchor(contentStackPane, 0.0);
        AnchorPane.setRightAnchor(rightPanelVBox, -getRightPanelWidth());
        rightPanelVBox.setVisible(false);
        rightPanelVBox.setManaged(false);
        
        // Set grid view as default active
        gridViewButton.getStyleClass().add("active");
        videoGrid.setVisible(true);
        videoGrid.setManaged(true);
        speakerViewPane.setVisible(false);
        speakerViewPane.setManaged(false);
        
        // Button handlers (override FXML onAction)
        gridViewButton.setOnAction(e -> showGridView());
        speakerViewButton.setOnAction(e -> showSpeakerView());
        
        participantsToggle.setOnAction(e -> showParticipantsView());
        chatToggle.setOnAction(e -> showChatView());
    }

    public void initData(Meeting meeting, UserRole userRole) {
        initData(meeting, userRole, true, true, "CREATE_MODE"); // Default: camera/mic ON, CREATE mode
    }
    
    public void initData(Meeting meeting, UserRole userRole, boolean withCamera, boolean withMic) {
        initData(meeting, userRole, withCamera, withMic, "CREATE_MODE"); // Default: CREATE mode
    }
    
    /**
     * Initialize meeting with join mode
     * @param joinMode "CREATE_MODE" (SecureRoom) or "JOIN_MODE" (Join Meeting)
     */
    public void initData(Meeting meeting, UserRole userRole, boolean withCamera, boolean withMic, String joinMode) {
        this.currentMeeting = meeting;
        this.meetingNameLabel.setText(meeting.getMeetingName());

        if (userRole == UserRole.ADMIN) {
            this.roleStrategy = new AdminRoleStrategy();
            // Constructor params: (name, role, cameraOn, muted)
            this.currentUser = new Participant("Admin User (You)", UserRole.ADMIN, withCamera, !withMic);
        } else {
            this.roleStrategy = new UserRoleStrategy();
            // Constructor params: (name, role, cameraOn, muted)
            this.currentUser = new Participant("Standard User (You)", UserRole.USER, withCamera, !withMic);
        }

        // ESKİ TASARIM: Keep participants list (real peers will be added/removed dynamically)
        currentMeeting.getParticipants().clear();
        currentMeeting.getParticipants().add(0, this.currentUser);
        
        updateParticipantsList();
        showGridView();  // ESKİ TASARIM: Immediately show grid (will show placeholder until video arrives)

        if (meetingChatViewController != null) {
            meetingChatViewController.initChannel(currentMeeting.getMeetingId());
            meetingChatViewController.setHeader("Toplantı Sohbeti", "Online", "G", true);
            meetingChatViewController.setWidthConstraint(rightPanelVBox.getPrefWidth());
            meetingChatViewController.setHeaderVisible(false);
        }
        updateMicButtonState();
        updateCameraButtonState();
        
        // ===============================
        // INITIALIZE GROUP CALL WITH MODE
        // ===============================
        initializeGroupCall(meeting.getMeetingId(), userRole, joinMode);
    }
    
    /**
     * Initialize group call manager and join room with mode
     */
    private void initializeGroupCall(String roomId, UserRole userRole, String joinMode) {
        try {
            // Get current username (from login session)
            this.currentUsername = getCurrentUsername();
            
            System.out.printf("[MeetingPanel] Initializing group call for room: %s, user: %s, mode: %s%n", 
                roomId, currentUsername, joinMode);
            
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
            
            // Setup callbacks (including error callback)
            setupGroupCallCallbacks();
            
            // Join room with mode
            boolean audio = !currentUser.isMuted();
            boolean video = currentUser.isCameraOn();
            
            System.out.printf("[MeetingPanel] Joining room with mode=%s, audio=%b, video=%b%n", 
                joinMode, audio, video);
            groupCallManager.joinRoom(roomId, audio, video, joinMode)
                .thenAccept(v -> {
                    System.out.println("[MeetingPanel] Successfully joined room");
                    // ESKİ TASARIM: Grid already showing, will update when tracks arrive
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
                updateVideoGrid();  // ESKİ TASARIM
            });
        });
        
        // Peer joined callback
        groupCallManager.setOnPeerJoinedCallback(peerUsername -> {
            javafx.application.Platform.runLater(() -> {
                System.out.printf("[MeetingPanel] Peer joined: %s%n", peerUsername);
                
                // ESKİ TASARIM: Add peer to participant list (camera OFF initially, will turn ON when track arrives)
                Participant peer = new Participant(peerUsername, UserRole.USER, false, true);
                currentMeeting.getParticipants().add(peer);
                
                updateParticipantsList();
                updateVideoGrid();  // ESKİ TASARIM: Refresh grid (will show avatar placeholder)
            });
        });
        
        // Peer left callback
        groupCallManager.setOnPeerLeftCallback(peerUsername -> {
            javafx.application.Platform.runLater(() -> {
                System.out.printf("[MeetingPanel] Peer left: %s%n", peerUsername);
                
                // ESKİ TASARIM: Remove peer from participant list
                Participant removedPeer = null;
                for (Participant p : currentMeeting.getParticipants()) {
                    if (p.getName().equals(peerUsername)) {
                        removedPeer = p;
                        break;
                    }
                }
                
                if (removedPeer != null) {
                    currentMeeting.getParticipants().remove(removedPeer);
                    
                    // Cleanup video panel
                    disposeVideoPanel(removedPeer.getName());
                    
                    // Remove video track
                    remoteVideoTracks.remove(peerUsername);
                }
                
                updateParticipantsList();
                updateVideoGrid();  // ESKİ TASARIM: Refresh grid
            });
        });
        
        // Remote track callback (video/audio from peer)
        groupCallManager.setOnRemoteTrackCallback((peerUsername, track) -> {
            javafx.application.Platform.runLater(() -> {
                System.out.printf("[MeetingPanel] Remote track from %s: %s%n", 
                    peerUsername, track.getKind());
                
                if ("video".equals(track.getKind())) {
                    // ESKİ TASARIM: Store video track
                    remoteVideoTracks.put(peerUsername, (VideoTrack) track);
                    
                    // Find participant and enable camera
                    Participant peer = findParticipantByUsername(peerUsername);
                    if (peer != null) {
                        peer.setCameraOn(true);
                        boolean attached = attachTrackIfPanelReady(peer.getName(), (VideoTrack) track);
                        if (!attached) {
                            updateVideoGrid();  // Panel henüz yoksa grid'i güncelle
                        }
                    }
                }
            });
        });
        
        // Room error callback (room not found, full, etc.)
        groupCallManager.setOnRoomErrorCallback((errorType, roomId) -> {
            javafx.application.Platform.runLater(() -> {
                System.err.printf("[MeetingPanel] ❌ Room error: %s (room=%s)%n", errorType, roomId);
                
                // Show error message based on error type
                String errorMessage;
                if ("ROOM_NOT_FOUND".equals(errorType)) {
                    errorMessage = "Room not found: " + roomId + "\nThis room does not exist or has been closed.";
                } else if ("ROOM_FULL".equals(errorType)) {
                    errorMessage = "Room is full: " + roomId + "\nMaximum 4 participants allowed (mesh topology).";
                } else {
                    errorMessage = "Failed to join room: " + roomId + "\nError: " + errorType;
                }
                
                // Show error alert
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR);
                alert.setTitle("Room Join Error");
                alert.setHeaderText("Cannot Join Room");
                alert.setContentText(errorMessage);
                alert.showAndWait();
                
                // Return to main view
                if (mainController != null) {
                    mainController.returnToMainView();
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
     * Helper: Find participant by username
     */
    private Participant findParticipantByUsername(String username) {
        for (Participant p : currentMeeting.getParticipants()) {
            if (p.getName().equals(username)) {
                return p;
            }
        }
        return null;
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
            
            // ESKİ TASARIM: Just refresh grid (createVideoTile will handle camera state)
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
        double panelWidth = getRightPanelWidth();
        double targetAnchorValue = closing ? 0.0 : panelWidth;
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
        meetingChatView.setVisible(true);
    }
    
    private void showGridView() {
        System.out.println("[MeetingPanel] 🔘 Switching to GRID view");
        videoGrid.setVisible(true);
        videoGrid.setManaged(true);
        speakerViewPane.setVisible(false);
        speakerViewPane.setManaged(false);
        updateVideoGrid();  // ESKİ TASARIM
        setActiveLayoutButton(gridViewButton);
    }

    private void showSpeakerView() {
        System.out.println("[MeetingPanel] 🔘 Switching to SPEAKER view");
        videoGrid.setVisible(false);
        videoGrid.setManaged(false);
        speakerViewPane.setVisible(true);
        speakerViewPane.setManaged(true);
        updateSpeakerView();
        setActiveLayoutButton(speakerViewButton);
    }

    private void setActiveLayoutButton(JFXButton activeButton) {
        gridViewButton.getStyleClass().remove("active");
        speakerViewButton.getStyleClass().remove("active");
        activeButton.getStyleClass().add("active");
    }

    private void updateSpeakerView() {
        // ESKİ TASARIM: Speaker view with participant-based layout
        speakerViewPane.getChildren().clear();
        if (currentMeeting.getParticipants().isEmpty()) return;
        
        Participant speaker = currentMeeting.getParticipants().get(0);
        StackPane speakerTile = createVideoTile(speaker);  // ESKİ METOD!
        speakerViewPane.setCenter(speakerTile);
        
        // ESKİ TASARIM: Other participants in right side strip
        VBox otherParticipantsVBox = new VBox(10);
        otherParticipantsVBox.setPadding(new Insets(0, 0, 0, 16));
        otherParticipantsVBox.setAlignment(Pos.TOP_CENTER);
        
        for (int i = 1; i < currentMeeting.getParticipants().size(); i++) {
            Participant p = currentMeeting.getParticipants().get(i);
            StackPane smallTile = createVideoTile(p);  // ESKİ METOD!
            smallTile.setPrefSize(160, 90);
            smallTile.setMaxSize(160, 90);
            otherParticipantsVBox.getChildren().add(smallTile);
        }
        
        ScrollPane scrollPane = new ScrollPane(otherParticipantsVBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.getStyleClass().add("sidebar-scroll-pane");
        speakerViewPane.setRight(scrollPane);
    }

    /**
     * ESKİ TASARIM: Update video grid layout (participant-based)
     * Her participant için tile oluşturur (camera on/off durumuna göre video veya avatar)
     */
    private void updateVideoGrid() {
        videoGrid.getChildren().clear();
        videoGrid.getColumnConstraints().clear();
        videoGrid.getRowConstraints().clear();
        
        int numParticipants = currentMeeting.getParticipants().size();
        if (numParticipants == 0) return;
        
        // ESKİ TASARIM: Dynamic grid calculation
        int numColumns = (int) Math.ceil(Math.sqrt(numParticipants));
        int numRows = (int) Math.ceil((double) numParticipants / numColumns);
        
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
        
        // ESKİ TASARIM: Participant-based layout
        for (Participant p : currentMeeting.getParticipants()) {
            StackPane tile = createVideoTile(p);  // ESKİ METOD!
            GridPane.setHgrow(tile, Priority.ALWAYS);
            GridPane.setVgrow(tile, Priority.ALWAYS);
            videoGrid.add(tile, col, row);
            
            col++;
            if (col >= numColumns) {
                col = 0;
                row++;
            }
        }
    }
    
    /**
     * ESKİ TASARIM: Create video tile for participant
     * YENİ: VideoPanel entegrasyonu eklendi
     */
    private StackPane createVideoTile(Participant participant) {
        StackPane tile = new StackPane();
        tile.getStyleClass().add("video-tile");
        
        String participantName = participant.getName();

        // Camera OFF: Avatar göster (ESKİ TASARIM)
        if (!participant.isCameraOn()) {
            detachPanelTrack(participantName);
            
            Label avatarLabel = new Label(participantName.substring(0, 1));
            avatarLabel.getStyleClass().add("video-tile-avatar-label");
            tile.getChildren().add(avatarLabel);
        } else {
            // Camera ON: VideoPanel ekle (YENİ) — cache kullan
            VideoPanel videoPanel = getOrCreateVideoPanel(participantName);
            mountVideoPanel(videoPanel, tile);
            
            // Attach video track if available
            VideoTrack track = getVideoTrackForParticipant(participant);
            if (track != null) {
                videoPanel.attachVideoTrack(track);
            } else {
                videoPanel.detachVideoTrack();
            }
            
            tile.getChildren().add(videoPanel);
        }
        
        // ESKİ TASARIM: Name tag overlay (DEĞİŞMEDİ)
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
    
    /**
     * Get video track for participant (local or remote)
     */
    private VideoTrack getVideoTrackForParticipant(Participant participant) {
        // Local user: get from GroupCallManager
        if (participant == currentUser) {
            if (groupCallManager != null) {
                return groupCallManager.getLocalVideoTrack();
            }
            return null;
        }
        
        // Remote peer: get from remoteVideoTracks map
        return remoteVideoTracks.get(participant.getName());
    }
    
    /**
     * Dispose all video panels (cleanup)
     */
    private void disposeAllVideoPanels() {
        for (VideoPanel panel : participantVideoPanels.values()) {
            if (panel != null) {
                panel.dispose();
            }
        }
        participantVideoPanels.clear();
    }

    private VideoPanel getOrCreateVideoPanel(String participantName) {
        return participantVideoPanels.computeIfAbsent(participantName, name -> new VideoPanel(320, 240));
    }

    private void mountVideoPanel(VideoPanel panel, StackPane container) {
        Parent parent = panel.getParent();
        if (parent instanceof Pane) {
            ((Pane) parent).getChildren().remove(panel);
        }
        panel.widthProperty().unbind();
        panel.heightProperty().unbind();
        panel.widthProperty().bind(container.widthProperty());
        panel.heightProperty().bind(container.heightProperty());
    }

    private void detachPanelTrack(String participantName) {
        VideoPanel panel = participantVideoPanels.get(participantName);
        if (panel != null) {
            panel.detachVideoTrack();
        }
    }

    private void disposeVideoPanel(String participantName) {
        VideoPanel panel = participantVideoPanels.remove(participantName);
        if (panel != null) {
            panel.dispose();
            Parent parent = panel.getParent();
            if (parent instanceof Pane) {
                ((Pane) parent).getChildren().remove(panel);
            }
        }
    }

    private double getRightPanelWidth() {
        double width = rightPanelVBox.getWidth();
        if (width <= 0) {
            width = rightPanelVBox.getPrefWidth();
        }
        if (width <= 0) {
            width = DEFAULT_RIGHT_PANEL_WIDTH;
        }
        return width;
    }

    private boolean attachTrackIfPanelReady(String participantName, VideoTrack track) {
        VideoPanel panel = participantVideoPanels.get(participantName);
        if (panel != null) {
            panel.attachVideoTrack(track);
            return true;
        }
        return false;
    }

    @FXML
    private void leaveMeeting() {
        System.out.println("[MeetingPanel] Leaving meeting...");
        
        // Dispose all video panels (ESKİ TASARIM uyumlu cleanup)
        disposeAllVideoPanels();
        
        // Cleanup group call (this closes all peer connections and releases camera)
        if (groupCallManager != null) {
            groupCallManager.leaveRoom()
                .thenAccept(v -> {
                    System.out.println("[MeetingPanel] ✅ Left room successfully - camera released");
                })
                .exceptionally(ex -> {
                    System.err.printf("[MeetingPanel] Error leaving room: %s%n", ex.getMessage());
                    return null;
                });
        }
        
        // Stop signaling
        if (signalingClient != null) {
            signalingClient.stopSignalingStream();
            System.out.println("[MeetingPanel] Signaling stream stopped");
        }
        
        // Clear remote tracks
        remoteVideoTracks.clear();
        
        // Ana pencereye geri dön (content değiştirme yaklaşımı)
        if (mainController != null) {
            mainController.returnToMainView();
        }
    }
}