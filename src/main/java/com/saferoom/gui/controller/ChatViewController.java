package com.saferoom.gui.controller;

import com.saferoom.gui.model.Message;
import com.saferoom.gui.model.User;
import com.saferoom.gui.service.ChatService;
import com.saferoom.gui.view.cell.MessageCell;
import com.saferoom.gui.dialog.IncomingCallDialog;
import com.saferoom.gui.dialog.OutgoingCallDialog;
import com.saferoom.gui.dialog.ActiveCallDialog;
import com.saferoom.gui.dialog.ScreenSharePickerDialog;
import com.saferoom.webrtc.CallManager;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Modality;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;

public class ChatViewController {

    @FXML private BorderPane chatPane;
    @FXML private Label chatPartnerAvatar;
    @FXML private Label chatPartnerName;
    @FXML private Label chatPartnerStatus;
    @FXML private ListView<Message> messageListView;
    @FXML private TextField messageInputField;
    @FXML private Button sendButton;
    @FXML private Button phoneButton;
    @FXML private Button videoButton;
    @FXML private Button screenShareButton;
    @FXML private Button attachmentButton;
    @FXML private HBox chatHeader;
    @FXML private VBox emptyChatPlaceholder;

    private User currentUser;
    private String currentChannelId;
    private ChatService chatService;
    private ObservableList<Message> messages;
    
    // WebRTC call dialog references
    private OutgoingCallDialog currentOutgoingDialog;
    private IncomingCallDialog currentIncomingDialog;
    private ActiveCallDialog currentActiveCallDialog;
    private boolean callbacksSetup = false; // Flag to prevent multiple callback registrations
    private boolean currentCallVideoEnabled = false; // Track if current call is video or audio-only

    @FXML
    public void initialize() {
        this.chatService = ChatService.getInstance();
        
        // Get current username from ChatService (set by ClientMenu)
        String username = chatService.getCurrentUsername();
        if (username != null) {
            this.currentUser = new User(username, username);
            System.out.printf("[ChatView] 👤 Current user initialized: %s%n", username);
        } else {
            this.currentUser = new User("temp-id", "You");
            System.out.println("[ChatView] ⚠️ Current username not set yet - using temp ID");
        }

        chatService.newMessageProperty().addListener((obs, oldMsg, newMsg) -> {
            if (newMsg != null && messages != null && messages.contains(newMsg)) {
                if (newMsg.getSenderId().equals(currentUser.getId())) {
                    messageListView.scrollTo(messages.size() - 1);
                }
            }
        });
        
        // Add Enter key handler for message input
        if (messageInputField != null) {
            messageInputField.setOnKeyPressed(this::handleKeyPressed);
        }
        
        // Setup WebRTC call buttons
        if (phoneButton != null) {
            phoneButton.setOnAction(e -> handlePhoneCall());
        }
        
        if (videoButton != null) {
            videoButton.setOnAction(e -> handleVideoCall());
        }
        
        if (screenShareButton != null) {
            screenShareButton.setOnAction(e -> handleScreenShare());
        }
    }

    public void initChannel(String channelId) {
        this.currentChannelId = channelId;
        this.messages = chatService.getMessagesForChannel(channelId);

        messageListView.setItems(messages);
        messageListView.setCellFactory(param -> new MessageCell(currentUser.getId()));
        
        // Show header when chat is selected
        if (chatHeader != null) {
            chatHeader.setVisible(true);
            chatHeader.setManaged(true);
        }

        updatePlaceholderVisibility();

        messages.addListener((ListChangeListener<Message>) c -> {
            while (c.next()) {
                updatePlaceholderVisibility();
            }
        });

        if (!messages.isEmpty()) {
            messageListView.scrollTo(messages.size() - 1);
        }
        
        // Ensure Enter key handler is set (in case initialize didn't work)
        if (messageInputField != null) {
            messageInputField.setOnKeyPressed(this::handleKeyPressed);
            System.out.println("[ChatView] ⌨️ Enter key handler set for message input");
        }
    }

    public void setWidthConstraint(double width) {
        if (chatPane != null) {
            chatPane.setMaxWidth(width);
            chatPane.setPrefWidth(width);
        }
    }

    public void setHeaderVisible(boolean visible) {
        if (chatHeader != null) {
            chatHeader.setVisible(visible);
            chatHeader.setManaged(visible);
        }
    }

    public void setHeader(String name, String status, String avatarChar, boolean isGroupChat) {
        chatPartnerName.setText(name);
        chatPartnerStatus.setText(status);
        chatPartnerAvatar.setText(avatarChar);

        // Remove all existing status classes
        chatPartnerStatus.getStyleClass().removeAll("status-online", "status-offline", "status-idle", "status-dnd", "status-busy");

        // Apply appropriate status class based on status text
        String statusLower = status.toLowerCase();
        if (statusLower.contains("online")) {
            chatPartnerStatus.getStyleClass().add("status-online");
        } else if (statusLower.contains("idle") || statusLower.contains("away")) {
            chatPartnerStatus.getStyleClass().add("status-idle");
        } else if (statusLower.contains("busy") || statusLower.contains("dnd") || statusLower.contains("do not disturb")) {
            chatPartnerStatus.getStyleClass().add("status-dnd");
        } else {
            chatPartnerStatus.getStyleClass().add("status-offline");
        }

        phoneButton.setVisible(!isGroupChat);
        videoButton.setVisible(!isGroupChat);
    }

    @FXML
    private void handleSendMessage() {
        String text = messageInputField.getText();
        if (text == null || text.trim().isEmpty()) return;

        chatService.sendMessage(currentChannelId, text, currentUser);

        messageInputField.clear();
    }
    
    /**
     * Handle attachment button click - open file chooser
     */
    @FXML
    private void handleAttachmentClick() {
        System.out.println("[ChatView] 📎 Attachment button clicked");
        
        // Create file chooser
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File to Send");
        
        // Add file type filters
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("All Files", "*.*"),
            new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"),
            new FileChooser.ExtensionFilter("Documents", "*.pdf", "*.doc", "*.docx", "*.txt", "*.md"),
            new FileChooser.ExtensionFilter("Archives", "*.zip", "*.rar", "*.7z", "*.tar", "*.gz"),
            new FileChooser.ExtensionFilter("Videos", "*.mp4", "*.avi", "*.mkv", "*.mov")
        );
        
        // Get stage from any node
        Stage stage = (Stage) attachmentButton.getScene().getWindow();
        
        // Show file chooser
        File selectedFile = fileChooser.showOpenDialog(stage);
        
        if (selectedFile != null) {
            showFileSendConfirmation(selectedFile);
        }
    }
    
    /**
     * Show confirmation dialog before sending file
     */
    private void showFileSendConfirmation(File file) {
        long fileSizeBytes = file.length();
        String fileSizeStr = formatFileSize(fileSizeBytes);
        
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Send File");
        alert.setHeaderText("Send file to " + chatPartnerName.getText() + "?");
        alert.setContentText(String.format(
            "File: %s\nSize: %s\n\nDo you want to send this file?",
            file.getName(),
            fileSizeStr
        ));
        
        // Style the alert
        alert.getDialogPane().getStylesheets().add(
            getClass().getResource("/styles/styles.css").toExternalForm()
        );
        
        Optional<ButtonType> result = alert.showAndWait();
        
        if (result.isPresent() && result.get() == ButtonType.OK) {
            System.out.println("[ChatView] ✅ User confirmed file send - calling sendFile()");
            try {
                sendFile(file.toPath());
            } catch (Exception e) {
                System.err.println("[ChatView] ❌ Error in sendFile(): " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("[ChatView] ❌ User cancelled file send");
        }
    }
    
    /**
     * Send file via P2P file transfer
     */
    private void sendFile(Path filePath) {
        System.out.println("[ChatView] 📤 Sending file: " + filePath.getFileName());
        
        // Show file send message in chat FIRST
        String fileMsg = String.format(
            "📎 Sending file: %s (%s)",
            filePath.getFileName(),
            formatFileSize(filePath.toFile().length())
        );
        
        chatService.sendMessage(currentChannelId, fileMsg, currentUser);
        
        // THEN call ChatService.sendFile() which routes to NatAnalyzer
        // This happens in background thread
        System.out.printf("[ChatView] 🚀 Initiating actual file transfer to %s%n", currentChannelId);
        chatService.sendFile(currentChannelId, filePath);
    }
    
    /**
     * Format file size in human-readable format
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * Handle key presses in message input field
     */
    private void handleKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER) {
            // Check if Shift+Enter (for new line) or just Enter (for send)
            if (!event.isShiftDown()) {
                handleSendMessage();
                event.consume(); // Prevent default behavior
            }
            // If Shift+Enter, allow default behavior (new line)
        }
    }

    private void updatePlaceholderVisibility() {
        boolean isListEmpty = (messages == null || messages.isEmpty());
        if (emptyChatPlaceholder != null) {
            emptyChatPlaceholder.setVisible(isListEmpty);
            emptyChatPlaceholder.setManaged(isListEmpty);
        }
        messageListView.setVisible(!isListEmpty);
    }
    
    /**
     * Show welcome screen (when no chat is selected)
     */
    public void showWelcomeScreen() {
        if (emptyChatPlaceholder != null) {
            emptyChatPlaceholder.setVisible(true);
            emptyChatPlaceholder.setManaged(true);
        }
        messageListView.setVisible(false);
        
        // Hide header and input when no chat selected
        if (chatHeader != null) {
            chatHeader.setVisible(false);
            chatHeader.setManaged(false);
        }
        
        System.out.println("[ChatView] 🎨 Showing welcome screen");
    }
    
    // ===============================
    // WebRTC Call Handlers
    // ===============================
    
    /**
     * Handle phone button click (audio-only call)
     */
    @FXML
    private void handlePhoneCall() {
        if (currentChannelId == null || currentChannelId.isEmpty()) {
            System.err.println("[ChatView] ❌ No chat selected");
            return;
        }
        
        System.out.printf("[ChatView] 📞 Starting audio call to %s%n", currentChannelId);
        
        // Show confirmation dialog
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Audio Call");
        alert.setHeaderText("Start audio call?");
        alert.setContentText("Do you want to call " + chatPartnerName.getText() + "?");
        
        // Style the alert
        alert.getDialogPane().getStylesheets().add(
            getClass().getResource("/styles/styles.css").toExternalForm()
        );
        
        Optional<ButtonType> result = alert.showAndWait();
        
        if (result.isPresent() && result.get() == ButtonType.OK) {
            startCall(false); // audio only, no video
        }
    }
    
    /**
     * Handle video button click (audio + video call)
     */
    @FXML
    private void handleVideoCall() {
        if (currentChannelId == null || currentChannelId.isEmpty()) {
            System.err.println("[ChatView] ❌ No chat selected");
            return;
        }
        
        System.out.printf("[ChatView] 📹 Starting video call to %s%n", currentChannelId);
        
        // Show confirmation dialog
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Video Call");
        alert.setHeaderText("Start video call?");
        alert.setContentText("Do you want to video call " + chatPartnerName.getText() + "?");
        
        // Style the alert
        alert.getDialogPane().getStylesheets().add(
            getClass().getResource("/styles/styles.css").toExternalForm()
        );
        
        Optional<ButtonType> result = alert.showAndWait();
        
        if (result.isPresent() && result.get() == ButtonType.OK) {
            startCall(true); // audio + video
        }
    }
    
    /**
     * Start WebRTC call
     */
    private void startCall(boolean videoEnabled) {
        try {
            String targetUsername = currentChannelId; // currentChannelId is the target username
            String myUsername = currentUser.getId();
            
            System.out.printf("[ChatView] 🚀 Initiating call to %s (video=%b)%n", targetUsername, videoEnabled);
            
            // Get CallManager instance
            CallManager callManager = CallManager.getInstance();
            
            // 🔧 FIX: Check if initialized instead of checking state
            if (!callManager.isInitialized()) {
                System.out.println("[ChatView] ⚠️ CallManager not initialized - initializing now");
                callManager.initialize(myUsername);
            }
            
            // Setup callbacks if not already done (safe to call multiple times)
            setupCallManagerCallbacks(callManager);
            
            // Check if not already in a call
            if (callManager.isInCall()) {
                showAlert("Call In Progress", "You are already in a call.", Alert.AlertType.WARNING);
                return;
            }
            
            // Start the call
            callManager.startCall(targetUsername, true, videoEnabled)
                .thenAccept(callId -> {
                    javafx.application.Platform.runLater(() -> {
                        System.out.printf("[ChatView] ✅ Call started: %s%n", callId);
                        
                        // Store video setting for this call
                        currentCallVideoEnabled = videoEnabled;
                        
                        // Show "Calling..." message in chat
                        String callMsg = videoEnabled ? "📹 Starting video call..." : "📞 Starting audio call...";
                        chatService.sendMessage(currentChannelId, callMsg, currentUser);
                        
                        // Open OutgoingCallDialog and store reference
                        currentOutgoingDialog = new OutgoingCallDialog(
                            targetUsername, callId, videoEnabled
                        );
                        
                        currentOutgoingDialog.show();
                    });
                })
                .exceptionally(e -> {
                    javafx.application.Platform.runLater(() -> {
                        System.err.printf("[ChatView] ❌ Failed to start call: %s%n", e.getMessage());
                        showAlert("Call Failed", "Failed to start call: " + e.getMessage(), Alert.AlertType.ERROR);
                    });
                    return null;
                });
            
        } catch (Exception e) {
            System.err.printf("[ChatView] ❌ Error starting call: %s%n", e.getMessage());
            e.printStackTrace();
            showAlert("Error", "Error starting call: " + e.getMessage(), Alert.AlertType.ERROR);
        }
    }
    
    /**
     * Handle screen share button click
     */
    @FXML
    private void handleScreenShare() {
        System.out.println("[ChatView] 🖥️ Screen share button clicked");
        
        // Check if we're in an active call
        CallManager callManager = CallManager.getInstance();
        if (!callManager.isInCall()) {
            showAlert("Not In Call", "You must be in an active call to share your screen.", Alert.AlertType.WARNING);
            return;
        }
        
        try {
            // Load improved screen share picker dialog (Google Meet style)
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/ImprovedScreenSharePickerDialog.fxml"));
            BorderPane dialogRoot = loader.load();
            com.saferoom.gui.dialog.ImprovedScreenSharePickerDialog pickerController = loader.getController();
            
            // Create dialog stage
            Stage dialogStage = new Stage();
            dialogStage.setTitle("Share Your Screen");
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            dialogStage.setScene(new Scene(dialogRoot));
            pickerController.setDialogStage(dialogStage);
            
            // Get available sources
            List<DesktopSource> screens = callManager.getWebRTCClient().getAvailableScreens();
            List<DesktopSource> windows = callManager.getWebRTCClient().getAvailableWindows();
            
            if (screens.isEmpty() && windows.isEmpty()) {
                showAlert("No Sources", "No screens or windows available for sharing.", Alert.AlertType.ERROR);
                return;
            }
            
            // Set sources with WebRTC client for thumbnail capture
            pickerController.setAvailableSources(screens, windows, callManager.getWebRTCClient());
            
            // Show dialog and wait for user selection
            dialogStage.showAndWait();
            
            // Check if user confirmed
            if (pickerController.isConfirmed()) {
                DesktopSource selectedSource = pickerController.getSelectedSource();
                boolean isWindow = pickerController.isWindowSelected();
                
                System.out.printf("[ChatView] 🎬 Starting screen share: source=%s, isWindow=%b%n", 
                    selectedSource.title, isWindow);
                
                // Start screen sharing with renegotiation
                callManager.startScreenShare(selectedSource.id, isWindow);
                
                System.out.println("[ChatView] ✅ Screen share initiated with renegotiation");
                
                showAlert("Screen Sharing", 
                    "Screen sharing started: " + selectedSource.title, 
                    Alert.AlertType.INFORMATION);
            } else {
                System.out.println("[ChatView] ❌ Screen share cancelled by user");
            }
            
        } catch (Exception e) {
            System.err.printf("[ChatView] ❌ Error starting screen share: %s%n", e.getMessage());
            e.printStackTrace();
            showAlert("Error", "Failed to start screen sharing: " + e.getMessage(), Alert.AlertType.ERROR);
        }
    }
    
    /**
     * Setup CallManager callbacks
     */
    private void setupCallManagerCallbacks(CallManager callManager) {
        // Only setup callbacks once
        if (callbacksSetup) {
            System.out.println("[ChatView] ⚠️ Callbacks already setup - skipping");
            return;
        }
        
        callbacksSetup = true;
        System.out.println("[ChatView] 🔧 Setting up CallManager callbacks (one-time setup)");
        
        // Incoming call
        callManager.setOnIncomingCallCallback(info -> {
            javafx.application.Platform.runLater(() -> {
                System.out.printf("[ChatView] 📞 Incoming call from %s (callId: %s, video=%b)%n", 
                    info.callerUsername, info.callId, info.videoEnabled);
                
                // 🔧 CRITICAL: Store video setting for incoming call
                currentCallVideoEnabled = info.videoEnabled;
                System.out.printf("[ChatView] 🎬 Video setting saved: %b%n", currentCallVideoEnabled);
                
                // Show IncomingCallDialog
                IncomingCallDialog incomingDialog = new IncomingCallDialog(
                    info.callerUsername, info.callId, info.videoEnabled
                );
                
                incomingDialog.show().thenAccept(accepted -> {
                    if (accepted) {
                        callManager.acceptCall(info.callId);
                    } else {
                        callManager.rejectCall(info.callId);
                    }
                });
            });
        });
        
        // Call accepted
        callManager.setOnCallAcceptedCallback(callId -> {
            javafx.application.Platform.runLater(() -> {
                System.out.printf("[ChatView] ✅ Call accepted: %s (video=%b)%n", callId, currentCallVideoEnabled);
                
                String msg = "✅ Call accepted - connecting...";
                chatService.sendMessage(currentChannelId, msg, currentUser);
                
                // Close OutgoingCallDialog if it exists
                if (currentOutgoingDialog != null) {
                    currentOutgoingDialog.close();
                    currentOutgoingDialog = null;
                }
                
                // Open ActiveCallDialog with correct video setting
                if (currentActiveCallDialog == null) {
                    currentActiveCallDialog = new ActiveCallDialog(
                        currentChannelId, 
                        callId, 
                        currentCallVideoEnabled, // Use saved video setting
                        callManager
                    );
                    currentActiveCallDialog.show();
                    System.out.printf("[ChatView] 📺 ActiveCallDialog opened (video=%b)%n", currentCallVideoEnabled);
                    
                    // Attach local video track if video enabled
                    if (currentCallVideoEnabled) {
                        VideoTrack localVideo = callManager.getLocalVideoTrack();
                        System.out.printf("[ChatView] 🔍 Local video track: %s%n", 
                            localVideo != null ? "EXISTS" : "NULL");
                        
                        if (localVideo != null) {
                            currentActiveCallDialog.attachLocalVideo(localVideo);
                            System.out.println("[ChatView] 📹 Local video attached to dialog");
                        } else {
                            System.err.println("[ChatView] ❌ ERROR: Local video track is NULL! Cannot attach.");
                        }
                    } else {
                        System.out.println("[ChatView] ℹ️ Video not enabled for this call");
                    }
                }
            });
        });
        
        // Call rejected
        callManager.setOnCallRejectedCallback(callId -> {
            javafx.application.Platform.runLater(() -> {
                System.out.printf("[ChatView] ❌ Call rejected: %s%n", callId);
                
                String msg = "❌ Call was rejected";
                chatService.sendMessage(currentChannelId, msg, currentUser);
                
                // TODO: Update OutgoingCallDialog to show rejection
            });
        });
        
        // Call connected
        callManager.setOnCallConnectedCallback(() -> {
            javafx.application.Platform.runLater(() -> {
                System.out.println("[ChatView] 🔗 Call connected!");
                
                String msg = "🔗 Call connected!";
                chatService.sendMessage(currentChannelId, msg, currentUser);
                
                // DON'T open ActiveCallDialog here - it's already opened in onCallAcceptedCallback
                // Just log the connection status
                System.out.println("[ChatView] ✅ P2P connection established - ActiveCallDialog already open");
            });
        });
        
        // Call ended
        callManager.setOnCallEndedCallback(callId -> {
            javafx.application.Platform.runLater(() -> {
                System.out.printf("[ChatView] 📴 Call ended: %s%n", callId);
                
                String msg = "📴 Call ended";
                chatService.sendMessage(currentChannelId, msg, currentUser);
                
                // Close all call dialogs
                if (currentOutgoingDialog != null) {
                    currentOutgoingDialog.close();
                    currentOutgoingDialog = null;
                }
                if (currentIncomingDialog != null) {
                    currentIncomingDialog.close();
                    currentIncomingDialog = null;
                }
                if (currentActiveCallDialog != null) {
                    currentActiveCallDialog.close();
                    currentActiveCallDialog = null;
                }
                
                // Reset call state
                currentCallVideoEnabled = false;
                
                System.out.println("[ChatView] ✅ All call dialogs closed and state reset");
            });
        });
        
        // Remote track received (for video)
        callManager.setOnRemoteTrackCallback(track -> {
            javafx.application.Platform.runLater(() -> {
                System.out.printf("[ChatView] 📺 Remote track received: kind=%s, id=%s%n", 
                    track.getKind(), track.getId());
                System.out.printf("[ChatView] 🔍 Debug: currentActiveCallDialog=%s, instanceof VideoTrack=%b%n",
                    currentActiveCallDialog != null ? "EXISTS" : "NULL",
                    track instanceof VideoTrack);
                
                // If it's a video track and we have an active call dialog, attach it
                if (track instanceof VideoTrack && currentActiveCallDialog != null) {
                    VideoTrack videoTrack = (VideoTrack) track;
                    currentActiveCallDialog.attachRemoteVideo(videoTrack);
                    System.out.println("[ChatView] 📹 Remote video attached to dialog");
                } else if (!(track instanceof VideoTrack)) {
                    System.out.println("[ChatView] 🎤 Remote audio track (handled by WebRTC)");
                } else if (currentActiveCallDialog == null) {
                    System.err.println("[ChatView] ❌ ERROR: Remote video track received but dialog is NULL!");
                }
            });
        });
        
        // Remote screen share stopped
        callManager.setOnRemoteScreenShareStoppedCallback(() -> {
            javafx.application.Platform.runLater(() -> {
                System.out.println("[ChatView] 🖥️ Remote screen share stopped");
                
                // Notify ActiveCallDialog to handle UI updates
                if (currentActiveCallDialog != null) {
                    currentActiveCallDialog.onRemoteScreenShareStopped();
                    System.out.println("[ChatView] ✅ ActiveCallDialog notified of screen share stop");
                } else {
                    System.err.println("[ChatView] ⚠️ Screen share stopped but no active dialog!");
                }
            });
        });
    }
    
    /**
     * Show alert dialog
     */
    private void showAlert(String title, String content, Alert.AlertType type) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        
        alert.getDialogPane().getStylesheets().add(
            getClass().getResource("/styles/styles.css").toExternalForm()
        );
        
        alert.show();
    }
}
