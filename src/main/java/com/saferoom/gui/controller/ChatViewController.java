package com.saferoom.gui.controller;

import com.saferoom.gui.model.Message;
import com.saferoom.gui.model.User;
import com.saferoom.gui.service.ChatService;
import com.saferoom.gui.view.cell.MessageCell;
import com.saferoom.gui.dialog.IncomingCallDialog;
import com.saferoom.gui.dialog.OutgoingCallDialog;
import com.saferoom.gui.dialog.ActiveCallDialog;
import com.saferoom.webrtc.CallManager;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import javafx.application.Platform;
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

    @FXML
    private BorderPane chatPane;
    @FXML
    private Label chatPartnerAvatar;
    @FXML
    private Label chatPartnerName;
    @FXML
    private Label chatPartnerStatus;
    @FXML
    private ListView<Message> messageListView;
    @FXML
    private TextField messageInputField;
    @FXML
    private Button sendButton;
    @FXML
    private Button phoneButton;
    @FXML
    private Button videoButton;
    @FXML
    private Button screenShareButton;
    @FXML
    private Button attachmentButton;

    // UI Area Components
    @FXML
    private HBox chatHeader;
    @FXML
    private HBox chatInputArea;

    // Placeholders
    @FXML
    private VBox emptyChatPlaceholder;   // Welcome Screen (Initial)
    @FXML
    private VBox noMessagesPlaceholder;  // In-Chat Empty Screen

    private User currentUser;
    private String currentChannelId;
    private ChatService chatService;
    private ObservableList<Message> messages;

    // WebRTC call variables
    private OutgoingCallDialog currentOutgoingDialog;
    private IncomingCallDialog currentIncomingDialog;
    private ActiveCallDialog currentActiveCallDialog;
    private boolean callbacksSetup = false;
    private boolean currentCallVideoEnabled = false;

    @FXML
    public void initialize() {
        this.chatService = ChatService.getInstance();

        String username = chatService.getCurrentUsername();
        if (username != null) {
            this.currentUser = new User(username, username);
            System.out.printf("[ChatView] 👤 Current user initialized: %s%n", username);
        } else {
            this.currentUser = new User("temp-id", "You");
            System.out.println("[ChatView] ⚠️ Current username not set yet - using temp ID");
        }

        // Global mesaj listener
        chatService.newMessageProperty().addListener((obs, oldMsg, newMsg) -> {
            if (newMsg != null && messages != null && messages.contains(newMsg)) {
                // Eğer şu anki kanalın mesajıysa scroll yap
                if (newMsg.getSenderId().equals(currentUser.getId())) {
                    messageListView.scrollTo(messages.size() - 1);
                }
            }
        });

        if (messageInputField != null) {
            messageInputField.setOnKeyPressed(this::handleKeyPressed);
        }

        if (phoneButton != null) {
            phoneButton.setOnAction(e -> handlePhoneCall());
        }
        if (videoButton != null) {
            videoButton.setOnAction(e -> handleVideoCall());
        }
        if (screenShareButton != null) {
            screenShareButton.setOnAction(e -> handleScreenShare());
        }

        // Başlangıçta Welcome Screen'i göster
        showWelcomeScreen();
    }

    public void initChannel(String channelId) {
        this.currentChannelId = channelId;
        this.messages = chatService.getMessagesForChannel(channelId);

        messageListView.setItems(messages);
        messageListView.setCellFactory(param -> new MessageCell(currentUser.getId()));

        // SOHBET SEÇİLDİ: Header ve Input görünür olsun
        if (chatHeader != null) {
            chatHeader.setVisible(true);
            chatHeader.setManaged(true);
        }
        if (chatInputArea != null) {
            chatInputArea.setVisible(true);
            chatInputArea.setManaged(true);
        }

        // Doğru içeriği göster (Liste mi yoksa Boş Mesaj Uyarısı mı?)
        updateViewVisibility();

        // Liste değişimlerini dinle
        messages.addListener((ListChangeListener<Message>) c -> {
            while (c.next()) {
                updateViewVisibility();
                if (c.wasAdded()) {
                    messageListView.scrollTo(messages.size() - 1);
                }
            }
        });

        if (!messages.isEmpty()) {
            messageListView.scrollTo(messages.size() - 1);
        }
    }

    /**
     * Hangi ekranın (Liste veya Boş Chat Uyarısı) görüneceğine karar verir.
     */
    private void updateViewVisibility() {
        // 1. Bir kanaldayız, o yüzden Welcome (Shield) ekranını kesinlikle gizle
        if (emptyChatPlaceholder != null) {
            emptyChatPlaceholder.setVisible(false);
            emptyChatPlaceholder.setManaged(false);
        }

        boolean isChatEmpty = (messages == null || messages.isEmpty());

        // 2. Mesaj yoksa -> "Quiet here..." ekranını göster
        if (noMessagesPlaceholder != null) {
            noMessagesPlaceholder.setVisible(isChatEmpty);
            noMessagesPlaceholder.setManaged(isChatEmpty);
        }

        // 3. Mesaj varsa -> Listeyi göster
        messageListView.setVisible(!isChatEmpty);
        messageListView.setManaged(!isChatEmpty);
    }

    /**
     * Uygulama ilk açıldığında veya sohbet kapatıldığında çağrılır.
     */
    public void showWelcomeScreen() {
        // Sadece Welcome Ekranı görünür
        if (emptyChatPlaceholder != null) {
            emptyChatPlaceholder.setVisible(true);
            emptyChatPlaceholder.setManaged(true);
        }

        // Diğer her şeyi gizle
        if (noMessagesPlaceholder != null) {
            noMessagesPlaceholder.setVisible(false);
            noMessagesPlaceholder.setManaged(false);
        }

        messageListView.setVisible(false);
        messageListView.setManaged(false);

        if (chatHeader != null) {
            chatHeader.setVisible(false);
            chatHeader.setManaged(false);
        }

        if (chatInputArea != null) {
            chatInputArea.setVisible(false);
            chatInputArea.setManaged(false);
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

        chatPartnerStatus.getStyleClass().removeAll("status-online", "status-offline", "status-idle", "status-dnd", "status-busy");

        String statusLower = status.toLowerCase();
        if (statusLower.contains("online")) {
            chatPartnerStatus.getStyleClass().add("status-online");
        } else if (statusLower.contains("idle") || statusLower.contains("away")) {
            chatPartnerStatus.getStyleClass().add("status-idle");
        } else if (statusLower.contains("busy") || statusLower.contains("dnd")) {
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
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        chatService.sendMessage(currentChannelId, text, currentUser);
        messageInputField.clear();
    }

    @FXML
    private void handleAttachmentClick() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File to Send");

        Stage stage = (Stage) attachmentButton.getScene().getWindow();
        File selectedFile = fileChooser.showOpenDialog(stage);

        if (selectedFile != null) {
            showFileSendConfirmation(selectedFile);
        }
    }

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

        alert.getDialogPane().getStylesheets().add(
                getClass().getResource("/styles/styles.css").toExternalForm()
        );

        Optional<ButtonType> result = alert.showAndWait();

        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                sendFile(file.toPath());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void sendFile(Path filePath) {
        String fileMsg = String.format(
                "📎 Sending file: %s (%s)",
                filePath.getFileName(),
                formatFileSize(filePath.toFile().length())
        );
        chatService.sendMessage(currentChannelId, fileMsg, currentUser);
        chatService.sendFile(currentChannelId, filePath);
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }else {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
    }

    private void handleKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER) {
            if (!event.isShiftDown()) {
                handleSendMessage();
                event.consume();
            }
        }
    }

    // ===============================
    // WebRTC Call Handlers
    // ===============================
    @FXML
    private void handlePhoneCall() {
        if (currentChannelId == null || currentChannelId.isEmpty()) {
            return;
        }
        showCallConfirmation("Audio Call", "Start audio call?", false);
    }

    @FXML
    private void handleVideoCall() {
        if (currentChannelId == null || currentChannelId.isEmpty()) {
            return;
        }
        showCallConfirmation("Video Call", "Start video call?", true);
    }

    private void showCallConfirmation(String title, String header, boolean isVideo) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText("Do you want to call " + chatPartnerName.getText() + "?");
        alert.getDialogPane().getStylesheets().add(getClass().getResource("/styles/styles.css").toExternalForm());

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            startCall(isVideo);
        }
    }

    private void startCall(boolean videoEnabled) {
        try {
            String targetUsername = currentChannelId;
            String myUsername = currentUser.getId();
            CallManager callManager = CallManager.getInstance();

            if (!callManager.isInitialized()) {
                callManager.initialize(myUsername);
            }
            setupCallManagerCallbacks(callManager);

            if (callManager.isInCall()) {
                showAlert("Call In Progress", "You are already in a call.", Alert.AlertType.WARNING);
                return;
            }

            callManager.startCall(targetUsername, true, videoEnabled)
                    .thenAccept(callId -> {
                        Platform.runLater(() -> {
                            currentCallVideoEnabled = videoEnabled;
                            String callMsg = videoEnabled ? "📹 Starting video call..." : "📞 Starting audio call...";
                            chatService.sendMessage(currentChannelId, callMsg, currentUser);

                            currentOutgoingDialog = new OutgoingCallDialog(targetUsername, callId, videoEnabled);
                            currentOutgoingDialog.show();
                        });
                    })
                    .exceptionally(e -> {
                        Platform.runLater(() -> showAlert("Call Failed", "Failed to start call: " + e.getMessage(), Alert.AlertType.ERROR));
                        return null;
                    });
        } catch (Exception e) {
            e.printStackTrace();
            showAlert("Error", "Error starting call: " + e.getMessage(), Alert.AlertType.ERROR);
        }
    }

    @FXML
    private void handleScreenShare() {
        CallManager callManager = CallManager.getInstance();
        if (!callManager.isInCall()) {
            showAlert("Not In Call", "You must be in an active call to share your screen.", Alert.AlertType.WARNING);
            return;
        }

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/ImprovedScreenSharePickerDialog.fxml"));
            BorderPane dialogRoot = loader.load();
            com.saferoom.gui.dialog.ImprovedScreenSharePickerDialog pickerController = loader.getController();

            Stage dialogStage = new Stage();
            dialogStage.setTitle("Share Your Screen");
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            dialogStage.setScene(new Scene(dialogRoot));
            pickerController.setDialogStage(dialogStage);

            List<DesktopSource> screens = callManager.getWebRTCClient().getAvailableScreens();
            List<DesktopSource> windows = callManager.getWebRTCClient().getAvailableWindows();

            if (screens.isEmpty() && windows.isEmpty()) {
                showAlert("No Sources", "No screens or windows available for sharing.", Alert.AlertType.ERROR);
                return;
            }

            pickerController.setAvailableSources(screens, windows, callManager.getWebRTCClient());
            dialogStage.showAndWait();

            if (pickerController.isConfirmed()) {
                DesktopSource selectedSource = pickerController.getSelectedSource();
                boolean isWindow = pickerController.isWindowSelected();
                callManager.startScreenShare(selectedSource.id, isWindow);
                showAlert("Screen Sharing", "Screen sharing started: " + selectedSource.title, Alert.AlertType.INFORMATION);
            }
        } catch (Exception e) {
            e.printStackTrace();
            showAlert("Error", "Failed to start screen sharing: " + e.getMessage(), Alert.AlertType.ERROR);
        }
    }

    private void setupCallManagerCallbacks(CallManager callManager) {
        if (callbacksSetup) {
            return;
        }
        callbacksSetup = true;

        callManager.setOnIncomingCallCallback(info -> {
            Platform.runLater(() -> {
                currentCallVideoEnabled = info.videoEnabled;
                IncomingCallDialog incomingDialog = new IncomingCallDialog(info.callerUsername, info.callId, info.videoEnabled);
                incomingDialog.show().thenAccept(accepted -> {
                    if (accepted) {
                        callManager.acceptCall(info.callId);
                    }else {
                        callManager.rejectCall(info.callId);
                    }
                });
            });
        });

        callManager.setOnCallAcceptedCallback(callId -> {
            Platform.runLater(() -> {
                chatService.sendMessage(currentChannelId, "✅ Call accepted - connecting...", currentUser);
                if (currentOutgoingDialog != null) {
                    currentOutgoingDialog.close();
                    currentOutgoingDialog = null;
                }

                if (currentActiveCallDialog == null) {
                    currentActiveCallDialog = new ActiveCallDialog(currentChannelId, callId, currentCallVideoEnabled, callManager);
                    currentActiveCallDialog.show();
                    if (currentCallVideoEnabled) {
                        VideoTrack localVideo = callManager.getLocalVideoTrack();
                        if (localVideo != null) {
                            currentActiveCallDialog.attachLocalVideo(localVideo);
                        }
                    }
                }
            });
        });

        callManager.setOnCallRejectedCallback(callId -> {
            Platform.runLater(() -> chatService.sendMessage(currentChannelId, "❌ Call was rejected", currentUser));
        });

        callManager.setOnCallConnectedCallback(() -> {
            Platform.runLater(() -> chatService.sendMessage(currentChannelId, "🔗 Call connected!", currentUser));
        });

        callManager.setOnCallEndedCallback(callId -> {
            Platform.runLater(() -> {
                chatService.sendMessage(currentChannelId, "📴 Call ended", currentUser);
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
                currentCallVideoEnabled = false;
            });
        });

        callManager.setOnRemoteTrackCallback(track -> {
            Platform.runLater(() -> {
                if (track instanceof VideoTrack && currentActiveCallDialog != null) {
                    currentActiveCallDialog.attachRemoteVideo((VideoTrack) track);
                }
            });
        });

        callManager.setOnRemoteScreenShareStoppedCallback(() -> {
            Platform.runLater(() -> {
                if (currentActiveCallDialog != null) {
                    currentActiveCallDialog.onRemoteScreenShareStopped();
                }
            });
        });
    }

    private void showAlert(String title, String content, Alert.AlertType type) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.getDialogPane().getStylesheets().add(getClass().getResource("/styles/styles.css").toExternalForm());
        alert.show();
    }
}
