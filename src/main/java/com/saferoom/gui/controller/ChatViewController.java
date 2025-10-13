package com.saferoom.gui.controller;

import com.saferoom.gui.model.Message;
import com.saferoom.gui.model.User;
import com.saferoom.gui.service.ChatService;
import com.saferoom.gui.view.cell.MessageCell;
import com.saferoom.gui.dialog.FileTransferDialog;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
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

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

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
    @FXML private Button attachmentButton;
    @FXML private HBox chatHeader;
    @FXML private VBox emptyChatPlaceholder;

    private User currentUser;
    private String currentChannelId;
    private ChatService chatService;
    private ObservableList<Message> messages;

    @FXML
    public void initialize() {
        this.currentUser = new User("currentUser123", "You");
        this.chatService = ChatService.getInstance();

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
        
        // TEST: Video button simulates incoming file transfer
        if (videoButton != null) {
            videoButton.setOnAction(e -> testIncomingFileTransfer());
        }
    }

    public void initChannel(String channelId) {
        this.currentChannelId = channelId;
        this.messages = chatService.getMessagesForChannel(channelId);

        messageListView.setItems(messages);
        messageListView.setCellFactory(param -> new MessageCell(currentUser.getId()));

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
            sendFile(file.toPath());
        }
    }
    
    /**
     * Send file via P2P file transfer
     */
    private void sendFile(Path filePath) {
        System.out.println("[ChatView] 📤 Sending file: " + filePath.getFileName());
        
        // TODO: Call NatAnalyzer.sendFile() via ChatService
        // For now, show placeholder message
        String placeholderMsg = String.format(
            "📎 Sending file: %s (%s)...",
            filePath.getFileName(),
            formatFileSize(filePath.toFile().length())
        );
        
        chatService.sendMessage(currentChannelId, placeholderMsg, currentUser);
        
        // TODO: Implement actual file transfer
        // chatService.sendFile(currentChannelId, filePath);
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
     * TEST METHOD: Simulate incoming file transfer
     * (Remove this after implementing real backend)
     */
    private void testIncomingFileTransfer() {
        System.out.println("[ChatView] 🧪 Testing incoming file transfer dialog...");
        
        // Simulate incoming file transfer request
        String senderUsername = chatPartnerName.getText();
        long fileId = System.currentTimeMillis();
        String fileName = "project_report.pdf";
        long fileSize = 2_500_000; // 2.5 MB
        
        // Show dialog
        FileTransferDialog dialog = new FileTransferDialog(
            senderUsername,
            fileId,
            fileName,
            fileSize
        );
        
        Optional<Path> savePath = dialog.showAndWait();
        
        if (savePath.isPresent()) {
            System.out.println("[ChatView] ✅ File transfer accepted! Save path: " + savePath.get());
            
            // Show acceptance message
            String msg = String.format(
                "✅ Accepted file transfer: %s (%s)\nSaving to: %s",
                fileName,
                formatFileSize(fileSize),
                savePath.get().toString()
            );
            chatService.sendMessage(currentChannelId, msg, currentUser);
        } else {
            System.out.println("[ChatView] ❌ File transfer declined");
            
            // Show rejection message
            String msg = "❌ Declined file transfer from " + senderUsername;
            chatService.sendMessage(currentChannelId, msg, currentUser);
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
}