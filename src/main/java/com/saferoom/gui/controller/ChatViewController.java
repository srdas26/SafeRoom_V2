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
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon; // FontIcon hala dialog'daki "x" için gerekli

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

public class ChatViewController {

    @FXML private BorderPane chatPane;

    // Header Components
    @FXML private Label chatPartnerAvatar;
    @FXML private Label chatPartnerName;
    @FXML private Label chatPartnerStatus;

    // Main Content
    @FXML private ListView<Message> messageListView;

    // Input Area
    @FXML private TextField messageInputField;
    @FXML private Button sendButton;
    @FXML private Button attachmentButton;

    // Header Buttons (userInfoButton kaldırıldı)
    @FXML private Button phoneButton;
    @FXML private Button videoButton;

    // YENİ: Sağ Panel (User Info) Bileşenleri
    @FXML private VBox userInfoSidebar;
    @FXML private Label infoAvatar;
    @FXML private Label infoName;
    @FXML private Label infoStatus;

    // UI Areas
    @FXML private HBox chatHeader;
    @FXML private HBox chatInputArea;

    // Placeholders
    @FXML private VBox emptyChatPlaceholder;   // Welcome Screen
    @FXML private VBox noMessagesPlaceholder;  // In-Chat Empty Screen

    private User currentUser;
    private String currentChannelId;
    private ChatService chatService;
    private ObservableList<Message> messages;

    // WebRTC variables
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
        }

        // Global mesaj listener
        chatService.newMessageProperty().addListener((obs, oldMsg, newMsg) -> {
            if (newMsg != null && messages != null && messages.contains(newMsg)) {
                if (newMsg.getSenderId().equals(currentUser.getId())) {
                    messageListView.scrollTo(messages.size() - 1);
                }
            }
        });

        if (messageInputField != null) {
            messageInputField.setOnKeyPressed(this::handleKeyPressed);
        }

        // Buton Tanımlamaları
        if (phoneButton != null) phoneButton.setOnAction(e -> handlePhoneCall());
        if (videoButton != null) videoButton.setOnAction(e -> handleVideoCall());

        // YENİ: Avatara ve isme tıklayınca paneli aç
        if (chatPartnerAvatar != null) {
            chatPartnerAvatar.setOnMouseClicked(e -> handleUserInfoToggle());
        }
        if (chatPartnerName != null) {
            chatPartnerName.setOnMouseClicked(e -> handleUserInfoToggle());
        }

        // Başlangıçta Welcome Screen göster
        showWelcomeScreen();
    }

    // --- User Info Toggle Metodu ---
    @FXML
    private void handleUserInfoToggle() {
        if (userInfoSidebar == null) return;

        boolean isVisible = userInfoSidebar.isVisible();

        if (!isVisible) {
            // --- AÇILIŞ ---
            updateInfoPanel(); // Bilgileri doldur
            userInfoSidebar.setVisible(true);
            userInfoSidebar.setManaged(true);

            // İkon değişimi kaldırıldı (artık ayrı bir buton yok)

            // Animasyon (Sağdan sola kayarak gelir)
            userInfoSidebar.setTranslateX(320);
            TranslateTransition openSlide = new TranslateTransition(Duration.millis(250), userInfoSidebar);
            openSlide.setToX(0);
            openSlide.play();

        } else {
            // --- KAPANIŞ ---

            // İkon değişimi kaldırıldı

            // Animasyon (Sola doğru kayarak gizlenir)
            TranslateTransition closeSlide = new TranslateTransition(Duration.millis(250), userInfoSidebar);
            closeSlide.setToX(320);
            closeSlide.setOnFinished(e -> {
                userInfoSidebar.setVisible(false);
                userInfoSidebar.setManaged(false);
            });
            closeSlide.play();
        }
    }

    // Sağ paneldeki bilgileri güncelle
    private void updateInfoPanel() {
        if (infoName != null && chatPartnerName != null)
            infoName.setText(chatPartnerName.getText());

        if (infoStatus != null && chatPartnerStatus != null)
            infoStatus.setText(chatPartnerStatus.getText());

        if (infoAvatar != null && chatPartnerAvatar != null)
            infoAvatar.setText(chatPartnerAvatar.getText());
    }

    public void initChannel(String channelId) {
        this.currentChannelId = channelId;
        this.messages = chatService.getMessagesForChannel(channelId);

        messageListView.setItems(messages);
        messageListView.setCellFactory(param -> new MessageCell(currentUser.getId()));

        if (chatHeader != null) {
            chatHeader.setVisible(true);
            chatHeader.setManaged(true);
        }
        if (chatInputArea != null) {
            chatInputArea.setVisible(true);
            chatInputArea.setManaged(true);
        }

        updateViewVisibility();

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

    private void updateViewVisibility() {
        if (emptyChatPlaceholder != null) {
            emptyChatPlaceholder.setVisible(false);
            emptyChatPlaceholder.setManaged(false);
        }

        boolean isChatEmpty = (messages == null || messages.isEmpty());

        if (noMessagesPlaceholder != null) {
            noMessagesPlaceholder.setVisible(isChatEmpty);
            noMessagesPlaceholder.setManaged(isChatEmpty);
        }

        messageListView.setVisible(!isChatEmpty);
        messageListView.setManaged(!isChatEmpty);
    }

    public void showWelcomeScreen() {
        if (emptyChatPlaceholder != null) {
            emptyChatPlaceholder.setVisible(true);
            emptyChatPlaceholder.setManaged(true);
        }

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

        // Eğer info paneli açıksa onu da kapat
        if (userInfoSidebar != null) {
            userInfoSidebar.setVisible(false);
            userInfoSidebar.setManaged(false);
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

        // Chat değiştiğinde info panel açıksa güncelle
        if (userInfoSidebar != null && userInfoSidebar.isVisible()) {
            updateInfoPanel();
        }
    }

    @FXML
    private void handleSendMessage() {
        String text = messageInputField.getText();
        if (text == null || text.trim().isEmpty()) return;

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
        alert.setContentText(String.format("File: %s\nSize: %s\n\nDo you want to send this file?", file.getName(), fileSizeStr));
        alert.getDialogPane().getStylesheets().add(getClass().getResource("/styles/styles.css").toExternalForm());

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
        String fileMsg = String.format("📎 Sending file: %s (%s)", filePath.getFileName(), formatFileSize(filePath.toFile().length()));
        chatService.sendMessage(currentChannelId, fileMsg, currentUser);
        chatService.sendFile(currentChannelId, filePath);
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        else if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        else return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
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
        if (currentChannelId == null || currentChannelId.isEmpty()) return;
        showCallConfirmation("Audio Call", "Start audio call?", false);
    }

    @FXML
    private void handleVideoCall() {
        if (currentChannelId == null || currentChannelId.isEmpty()) return;
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

            if (!callManager.isInitialized()) callManager.initialize(myUsername);
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

    private void setupCallManagerCallbacks(CallManager callManager) {
        if (callbacksSetup) return;
        callbacksSetup = true;

        callManager.setOnIncomingCallCallback(info -> {
            Platform.runLater(() -> {
                currentCallVideoEnabled = info.videoEnabled;
                IncomingCallDialog incomingDialog = new IncomingCallDialog(info.callerUsername, info.callId, info.videoEnabled);
                incomingDialog.show().thenAccept(accepted -> {
                    if (accepted) callManager.acceptCall(info.callId);
                    else callManager.rejectCall(info.callId);
                });
            });
        });

        callManager.setOnCallAcceptedCallback(callId -> {
            Platform.runLater(() -> {
                chatService.sendMessage(currentChannelId, "✅ Call accepted - connecting...", currentUser);
                if (currentOutgoingDialog != null) { currentOutgoingDialog.close(); currentOutgoingDialog = null; }

                if (currentActiveCallDialog == null) {
                    currentActiveCallDialog = new ActiveCallDialog(currentChannelId, callId, currentCallVideoEnabled, callManager);
                    currentActiveCallDialog.show();
                    if (currentCallVideoEnabled) {
                        VideoTrack localVideo = callManager.getLocalVideoTrack();
                        if (localVideo != null) currentActiveCallDialog.attachLocalVideo(localVideo);
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
                if (currentOutgoingDialog != null) { currentOutgoingDialog.close(); currentOutgoingDialog = null; }
                if (currentIncomingDialog != null) { currentIncomingDialog.close(); currentIncomingDialog = null; }
                if (currentActiveCallDialog != null) { currentActiveCallDialog.close(); currentActiveCallDialog = null; }
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
                if (currentActiveCallDialog != null) currentActiveCallDialog.onRemoteScreenShareStopped();
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