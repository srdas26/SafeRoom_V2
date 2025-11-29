package com.saferoom.gui.controller;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.kordamp.ikonli.javafx.FontIcon;

import com.saferoom.gui.dialog.ActiveCallDialog;
import com.saferoom.gui.dialog.IncomingCallDialog;
import com.saferoom.gui.dialog.OutgoingCallDialog;
import com.saferoom.gui.model.Message;
import com.saferoom.gui.model.User;
import com.saferoom.gui.search.MessageSearchPanel;
import com.saferoom.gui.search.SearchHit;
import com.saferoom.gui.service.ChatService;
import com.saferoom.gui.view.cell.MessageCell;
import com.saferoom.storage.FTS5SearchService;
import com.saferoom.storage.LocalDatabase;
import com.saferoom.storage.SqlCipherHelper;
import com.saferoom.webrtc.CallManager;

import dev.onvoid.webrtc.media.video.VideoTrack;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

public class ChatViewController {

    @FXML
    private BorderPane chatPane;

    // Header Components
    @FXML
    private Label chatPartnerAvatar;
    @FXML
    private Label chatPartnerName;
    @FXML
    private Label chatPartnerStatus;

    // Main Content
    @FXML
    private ListView<Message> messageListView;

    // Input Area
    @FXML
    private TextField messageInputField;
    @FXML
    private Button sendButton;
    @FXML
    private Button attachmentButton; // Hedef butonumuz

    // Header Buttons
    @FXML
    private Button phoneButton;
    @FXML
    private Button videoButton;

    // Sağ Panel (User Info) Bileşenleri
    @FXML
    private VBox userInfoSidebar;
    @FXML
    private Label infoAvatar;
    @FXML
    private Label infoName;
    @FXML
    private Label infoStatus;
    @FXML
    private Button infoAudioButton;
    @FXML
    private Button infoVideoButton;
    @FXML
    private Button infoSearchButton;

    // UI Areas
    @FXML
    private HBox chatHeader;
    @FXML
    private HBox chatInputArea;

    // Placeholders
    @FXML
    private VBox emptyChatPlaceholder;   // Welcome Screen
    @FXML
    private VBox noMessagesPlaceholder;  // In-Chat Empty Screen

    private User currentUser;
    private String currentChannelId;
    private ChatService chatService;
    private ObservableList<Message> messages;
    private boolean autoScrollAtBottom = true;

    // WebRTC variables
    private OutgoingCallDialog currentOutgoingDialog;
    private IncomingCallDialog currentIncomingDialog;
    private ActiveCallDialog currentActiveCallDialog;
    private boolean callbacksSetup = false;
    private boolean currentCallVideoEnabled = false;
    
    // Search variables (legacy - keeping for compatibility)
    private FTS5SearchService searchService;
    private List<String> searchResultMessageIds;
    private int currentSearchIndex = -1;
    
    // NEW: WhatsApp-style search panel
    private MessageSearchPanel searchPanel;
    private String highlightedMessageId = null;

    @FXML
    public void initialize() {
        this.chatService = ChatService.getInstance();

        String username = chatService.getCurrentUsername();
        if (username != null) {
            this.currentUser = new User(username, username);
        } else {
            this.currentUser = new User("temp-id", "You");
        }

        // Global mesaj listener
        chatService.newMessageProperty().addListener((obs, oldMsg, newMsg) -> {
            if (newMsg != null && messages != null && messages.contains(newMsg) && autoScrollAtBottom) {
                messageListView.scrollTo(messages.size() - 1);
            }
        });

        if (messageInputField != null) {
            messageInputField.setOnKeyPressed(this::handleKeyPressed);
        }

        // Buton Tanımlamaları (Derleme hatası çözüldü)
        if (phoneButton != null) {
            phoneButton.setOnAction(e -> handlePhoneCall());
        }
        if (videoButton != null) {
            videoButton.setOnAction(e -> handleVideoCall());
        }

        // YENİ BAĞLANTI: Attachment butonu menüyü açar
        if (attachmentButton != null) {
            attachmentButton.setOnAction(e -> handleAttachmentMenu());
        }

        // Avatara ve isme tıklayınca paneli aç
        if (chatPartnerAvatar != null) {
            chatPartnerAvatar.setOnMouseClicked(e -> handleUserInfoToggle());
        }
        if (chatPartnerName != null) {
            chatPartnerName.setOnMouseClicked(e -> handleUserInfoToggle());
        }

        // Başlangıçta Welcome Screen göster
        showWelcomeScreen();
        setupAutoScrollBehavior();
    }

    // --- YARDIMCI METOT: Özel stilli menü elemanları oluşturur ---
    private MenuItem createAttachmentMenuItem(String text, String iconCode, String filterDesc, String... filterExts) {
        MenuItem item = new MenuItem(text);

        FontIcon icon = new FontIcon(iconCode);
        icon.setIconSize(18);
        item.setGraphic(icon);

        // Aksiyon: Dosya Seçiciyi aç ve filtreleri uygula
        item.setOnAction(e -> openFileExplorer(text, filterDesc, filterExts));

        // Özel stil (CSS)
        item.getStyleClass().add("chat-context-menu-item-custom");

        return item;
    }

    // --- YENİ ANA METOT: + Butonuna Tıklanınca Menüyü Açar ---
    @FXML
    private void handleAttachmentMenu() {
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("chat-context-menu");

        // Menüye elemanları ekle
        menu.getItems().addAll(
                createAttachmentMenuItem("Photo & Video", "fas-camera", "Media Files", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.mp4", "*.mov"),
                createAttachmentMenuItem("Document", "fas-file-alt", "Document Files", "*.pdf", "*.doc", "*.docx", "*.txt"),
                new SeparatorMenuItem(),
                createAttachmentMenuItem("Other File", "fas-paperclip", "All Files", "*.*")
        );

        // Menüyü + butonunun üstüne yaslayarak göster
        menu.show(attachmentButton, Side.TOP, 0, -5);
    }

    // --- FİLTRELİ DOSYA SEÇİCİ MANTIĞI (Menüden çağrılır) ---
    private void openFileExplorer(String title, String filterDesc, String... filterExts) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(title);

        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(filterDesc, filterExts));

        Stage stage = (Stage) attachmentButton.getScene().getWindow();
        File selectedFile = fileChooser.showOpenDialog(stage);

        if (selectedFile != null) {
            showFileSendConfirmation(selectedFile);
        }
    }

    // --- User Info Toggle Metodu ---
    @FXML
    private void handleUserInfoToggle() {
        if (userInfoSidebar == null) {
            return;
        }

        boolean isVisible = userInfoSidebar.isVisible();

        if (!isVisible) {
            // --- AÇILIŞ ---
            updateInfoPanel();
            userInfoSidebar.setVisible(true);
            userInfoSidebar.setManaged(true);

            userInfoSidebar.setTranslateX(320);
            TranslateTransition openSlide = new TranslateTransition(Duration.millis(250), userInfoSidebar);
            openSlide.setToX(0);
            openSlide.play();

        } else {
            // --- KAPANIŞ ---
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
        if (infoName != null && chatPartnerName != null) {
            infoName.setText(chatPartnerName.getText());
        }

        if (infoStatus != null && chatPartnerStatus != null) {
            infoStatus.setText(chatPartnerStatus.getText());
        }

        if (infoAvatar != null && chatPartnerAvatar != null) {
            infoAvatar.setText(chatPartnerAvatar.getText());
        }
    }

    // Sağ paneldeki "X" butonu da bu metodu kullanır
    @FXML
    private void toggleUserInfo() {
        handleUserInfoToggle();
    }

    // --- Standart Chat Metotları ---
    public void initChannel(String channelId) {
        this.currentChannelId = channelId;
        this.messages = chatService.getMessagesForChannel(channelId);

        // DEBUG: Check if messages are already loaded (from preload)
        System.out.println("[ChatView] initChannel: " + channelId + " - messages already in RAM: " + messages.size());

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
                if (c.wasAdded() && autoScrollAtBottom) {
                    messageListView.scrollTo(messages.size() - 1);
                }
            }
        });

        // Load from disk ONLY if not already loaded (preload may have done this)
        if (messages.isEmpty()) {
            System.out.println("[ChatView] Messages empty, loading from disk...");
            loadConversationHistoryAsync(channelId);
        } else {
            System.out.println("[ChatView] Messages already loaded, skipping disk load");
            // Force UI refresh
            messageListView.refresh();
            messageListView.scrollTo(messages.size() - 1);
        }
    }
    
    /**
     * Load conversation history from persistent storage (NEW)
     * Loads messages from disk and populates the ObservableList
     * 
     * @param channelId Remote username / channel ID
     */
    private void loadConversationHistoryAsync(String channelId) {
        // Load history in background
        chatService.loadConversationHistory(channelId)
            .thenAccept(count -> {
                Platform.runLater(() -> {
                    if (count > 0) {
                        System.out.println("[ChatView] Loaded " + count + " messages from history for: " + channelId);
                        // Scroll to bottom after loading
                        if (!messages.isEmpty()) {
                            messageListView.scrollTo(messages.size() - 1);
                        }
                    } else {
                        System.out.println("[ChatView] No history found for: " + channelId);
                    }
                });
            })
            .exceptionally(error -> {
                Platform.runLater(() -> {
                    System.err.println("[ChatView] Failed to load history: " + error.getMessage());
                });
                return null;
            });
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

        // Paneli gizle
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

        if (userInfoSidebar != null && userInfoSidebar.isVisible()) {
            updateInfoPanel();
        }
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
        chatService.sendFileMessage(currentChannelId, filePath, currentUser);
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

    private void setupAutoScrollBehavior() {
        messageListView.skinProperty().addListener((obs, oldSkin, newSkin) -> attachScrollListener());
        Platform.runLater(this::attachScrollListener);
    }

    private void attachScrollListener() {
        ScrollBar bar = (ScrollBar) messageListView.lookup(".scroll-bar:vertical");
        if (bar == null) {
            return;
        }
        bar.valueProperty().addListener((obs, oldVal, newVal) -> {
            double max = bar.getMax();
            if (Double.compare(max, 0) == 0) {
                autoScrollAtBottom = true;
                return;
            }
            autoScrollAtBottom = (max - newVal.doubleValue()) < 0.05;
        });
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
    // WebRTC Call Handlers (Kısaltıldı)
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
    
    // Contact Info panel'deki butonlar için handler'lar
    @FXML
    private void handleInfoAudioCall() {
        handlePhoneCall(); // Aynı mantığı kullan
    }
    
    @FXML
    private void handleInfoVideoCall() {
        handleVideoCall(); // Aynı mantığı kullan
    }
    
    /**
     * Contact Info Search Button Handler
     * Opens WhatsApp-style search panel
     */
    @FXML
    private void handleInfoSearch() {
        if (currentChannelId == null || currentChannelId.isEmpty()) {
            showAlert("Search", "No conversation selected.", Alert.AlertType.INFORMATION);
            return;
        }
        
        // Check if persistence is enabled
        try {
            LocalDatabase db = LocalDatabase.getInstance();
            if (db == null || db.getConnection() == null) {
                showAlert("Search Unavailable", 
                    "Search feature requires persistent storage to be enabled.\n" +
                    "Messages are currently stored in RAM only.", 
                    Alert.AlertType.INFORMATION);
                return;
            }
            
            // Show WhatsApp-style search panel
            showSearchPanel();
            
        } catch (IllegalStateException e) {
            showAlert("Search Unavailable", 
                "Persistent storage is not initialized.\n" +
                "Search will be available after enabling message history.", 
                Alert.AlertType.INFORMATION);
        }
    }
    
    /**
     * Show WhatsApp-style search panel with slide-in animation
     */
    private void showSearchPanel() {
        // Close Contact Info sidebar first (search panel replaces it)
        if (userInfoSidebar != null && userInfoSidebar.isVisible()) {
            hideUserInfoSidebar();
        }
        
        // Initialize search panel if needed
        if (searchPanel == null) {
            initializeSearchPanel();
        }
        
        // Generate conversation ID
        String conversationId = SqlCipherHelper.generateConversationId(
            chatService.getCurrentUsername(), 
            currentChannelId
        );
        
        // Show the panel
        searchPanel.show(conversationId, chatService.getCurrentUsername());
    }
    
    /**
     * Hide user info sidebar with animation
     */
    private void hideUserInfoSidebar() {
        if (userInfoSidebar != null) {
            TranslateTransition slideOut = new TranslateTransition(Duration.millis(200), userInfoSidebar);
            slideOut.setToX(userInfoSidebar.getWidth());
            slideOut.setOnFinished(e -> userInfoSidebar.setVisible(false));
            slideOut.play();
        }
    }
    
    /**
     * Hide search panel with slide-out animation
     */
    private void hideSearchPanel() {
        if (searchPanel != null && searchPanel.isShowing()) {
            searchPanel.hide();
        }
    }
    
    /**
     * Initialize the WhatsApp-style search panel
     */
    private void initializeSearchPanel() {
        searchPanel = new MessageSearchPanel();
        
        // Load CSS
        try {
            String cssPath = getClass().getResource("/css/search-panel.css").toExternalForm();
            searchPanel.getStylesheets().add(cssPath);
        } catch (Exception e) {
            System.err.println("[ChatView] Could not load search-panel.css: " + e.getMessage());
        }
        
        // Set callback for when a result is clicked
        searchPanel.setOnResultSelected(this::handleSearchResultSelected);
        
        // Set callback for when panel is closed
        searchPanel.setOnClose(() -> {
            // Clear any highlights
            if (highlightedMessageId != null) {
                clearMessageHighlight(highlightedMessageId);
                highlightedMessageId = null;
            }
        });
        
        // Add panel to the chat pane (overlay on right side)
        if (chatPane != null) {
            // Wrap existing content in StackPane if needed
            if (!(chatPane.getCenter() instanceof StackPane)) {
                javafx.scene.Node existingCenter = chatPane.getCenter();
                StackPane stackPane = new StackPane();
                stackPane.getChildren().add(existingCenter);
                stackPane.getChildren().add(searchPanel);
                
                // Align search panel to right
                StackPane.setAlignment(searchPanel, javafx.geometry.Pos.CENTER_RIGHT);
                
                chatPane.setCenter(stackPane);
            } else {
                StackPane stackPane = (StackPane) chatPane.getCenter();
                if (!stackPane.getChildren().contains(searchPanel)) {
                    stackPane.getChildren().add(searchPanel);
                    StackPane.setAlignment(searchPanel, javafx.geometry.Pos.CENTER_RIGHT);
                }
            }
        }
    }
    
    /**
     * Handle when a search result is clicked
     */
    private void handleSearchResultSelected(SearchHit hit) {
        if (hit == null || messages == null) return;
        
        // Find message index by ID
        int index = findMessageIndexById(hit.getMessageId());
        
        if (index >= 0) {
            // Scroll to message
            messageListView.scrollTo(index);
            
            // Highlight the message
            highlightMessage(hit.getMessageId());
        } else {
            System.err.println("[ChatView] Message not found in list: " + hit.getMessageId());
        }
    }
    
    /**
     * Find message index by ID
     */
    private int findMessageIndexById(String messageId) {
        if (messages == null || messageId == null) return -1;
        
        for (int i = 0; i < messages.size(); i++) {
            if (messageId.equals(messages.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }
    
    /**
     * Highlight a message with yellow flash animation (2 seconds)
     */
    private void highlightMessage(String messageId) {
        // Clear previous highlight
        if (highlightedMessageId != null) {
            clearMessageHighlight(highlightedMessageId);
        }
        
        highlightedMessageId = messageId;
        
        // Find the message and trigger highlight
        int index = findMessageIndexById(messageId);
        if (index >= 0) {
            // Select the item to ensure it's visible
            messageListView.getSelectionModel().select(index);
            
            // Schedule highlight removal after 2 seconds
            Timeline timeline = new Timeline(
                new KeyFrame(Duration.seconds(2), e -> {
                    clearMessageHighlight(messageId);
                    messageListView.getSelectionModel().clearSelection();
                })
            );
            timeline.play();
            
            // Refresh to apply highlight style
            messageListView.refresh();
        }
    }
    
    /**
     * Clear message highlight
     */
    private void clearMessageHighlight(String messageId) {
        highlightedMessageId = null;
        messageListView.refresh();
    }
    
    /**
     * Check if a message is currently highlighted
     */
    public boolean isMessageHighlighted(String messageId) {
        return messageId != null && messageId.equals(highlightedMessageId);
    }
    
    // ========== LEGACY SEARCH METHODS (kept for compatibility) ==========
    
    /**
     * @deprecated Use showSearchPanel() instead
     */
    @Deprecated
    private void showSearchDialog() {
        showSearchPanel();
    }
    
    /**
     * @deprecated Use handleSearchResultSelected() instead
     */
    @Deprecated
    private void performSearch(String query) {
        // Get conversation ID
        String conversationId = SqlCipherHelper.generateConversationId(
            chatService.getCurrentUsername(), 
            currentChannelId
        );
        
        // Search in background
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            if (searchService == null) {
                searchService = new FTS5SearchService(LocalDatabase.getInstance());
            }
            searchResultMessageIds = searchService.getMatchingMessageIds(query, conversationId);
            
            Platform.runLater(() -> {
                if (searchResultMessageIds.isEmpty()) {
                    showAlert("No Results", 
                        "No messages found containing \"" + query + "\"", 
                        Alert.AlertType.INFORMATION);
                } else {
                    currentSearchIndex = 0;
                    scrollToSearchResult(0);
                }
            });
        });
    }
    
    /**
     * Show search navigation dialog with Up/Down buttons
     */
    private void showSearchNavigationDialog(String query, int totalResults) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Search Results");
        alert.setHeaderText("Found " + totalResults + " results for \"" + query + "\"");
        alert.setContentText("Result " + (currentSearchIndex + 1) + " of " + totalResults);
        
        // Custom buttons
        ButtonType previousBtn = new ButtonType("⬆ Previous");
        ButtonType nextBtn = new ButtonType("⬇ Next");
        ButtonType closeBtn = new ButtonType("Close", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        
        alert.getButtonTypes().setAll(previousBtn, nextBtn, closeBtn);
        
        try {
            alert.getDialogPane().getStylesheets().add(
                getClass().getResource("/styles/styles.css").toExternalForm());
        } catch (Exception e) {
            // Ignore
        }
        
        // Handle button clicks
        alert.showAndWait().ifPresent(response -> {
            if (response == previousBtn) {
                navigateToPreviousResult(query, totalResults);
            } else if (response == nextBtn) {
                navigateToNextResult(query, totalResults);
            }
        });
    }
    
    /**
     * Navigate to next search result
     */
    private void navigateToNextResult(String query, int totalResults) {
        if (currentSearchIndex < totalResults - 1) {
            currentSearchIndex++;
            scrollToSearchResult(currentSearchIndex);
            showSearchNavigationDialog(query, totalResults);
        } else {
            showAlert("End of Results", "No more results", Alert.AlertType.INFORMATION);
        }
    }
    
    /**
     * Navigate to previous search result
     */
    private void navigateToPreviousResult(String query, int totalResults) {
        if (currentSearchIndex > 0) {
            currentSearchIndex--;
            scrollToSearchResult(currentSearchIndex);
            showSearchNavigationDialog(query, totalResults);
        } else {
            showAlert("Start of Results", "Already at first result", Alert.AlertType.INFORMATION);
        }
    }
    
    /**
     * Scroll to a specific search result
     */
    private void scrollToSearchResult(int index) {
        String targetMessageId = searchResultMessageIds.get(index);
        
        // Find message in ObservableList
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getId().equals(targetMessageId)) {
                final int messageIndex = i;
                Platform.runLater(() -> {
                    messageListView.scrollTo(messageIndex);
                    messageListView.getSelectionModel().select(messageIndex);
                    
                    // Highlight with animation
                    highlightMessage(messageIndex);
                });
                break;
            }
        }
    }
    
    /**
     * Highlight a message cell with animation
     */
    private void highlightMessage(int index) {
        Platform.runLater(() -> {
            // Flash selection
            messageListView.getSelectionModel().select(index);
            
            // Clear selection after delay
            javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(Duration.seconds(2));
            pause.setOnFinished(e -> messageListView.getSelectionModel().clearSelection());
            pause.play();
        });
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

    private void setupCallManagerCallbacks(CallManager callManager) {
        if (callbacksSetup) {
            return;
        }
        callbacksSetup = true;

        callManager.setOnIncomingCallCallback(info -> {
            Platform.runLater(() -> {
                currentCallVideoEnabled = info.videoEnabled;
                IncomingCallDialog incomingDialog = new IncomingCallDialog(info.callerUsername, info.callId, info.videoEnabled);

                // DÜZELTME: incomingDialog'u sınıf değişkenine atadık.
                // Böylece arama bittiğinde kapatabiliriz.
                this.currentIncomingDialog = incomingDialog;

                incomingDialog.show().thenAccept(accepted -> {
                    if (accepted) {
                        callManager.acceptCall(info.callId); 
                    }else {
                        callManager.rejectCall(info.callId);
                    }

                    // İşlem bittiğinde referansı temizle
                    this.currentIncomingDialog = null;
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
