package com.saferoom.gui.search;

import com.saferoom.storage.FTS5SearchService;
import com.saferoom.storage.LocalDatabase;
import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;

/**
 * WhatsApp Web Style Message Search Panel
 * 
 * Features:
 * - Slide-in animation from right
 * - Dark theme with green accents
 * - Real-time FTS5 search with debounce
 * - Click to scroll to message
 * - Highlight matched text
 */
public class MessageSearchPanel extends VBox {
    
    private static final double PANEL_WIDTH = 380;
    private static final Duration SLIDE_DURATION = Duration.millis(200);
    
    private TextField searchField;
    private ListView<SearchHit> resultsListView;
    private Label statusLabel;
    private final ObservableList<SearchHit> searchResults;
    
    private FTS5SearchService searchService;
    private String currentConversationId;
    private String currentUsername;
    private Timer debounceTimer;
    
    // Callback when a result is clicked
    private Consumer<SearchHit> onResultSelected;
    // Callback when panel is closed
    private Runnable onClose;
    
    public MessageSearchPanel() {
        this.searchResults = FXCollections.observableArrayList();
        
        // Panel styling
        getStyleClass().add("search-panel");
        setPrefWidth(PANEL_WIDTH);
        setMinWidth(PANEL_WIDTH);
        setMaxWidth(PANEL_WIDTH);
        setSpacing(0);
        
        // Initially hidden (translated off-screen)
        setTranslateX(PANEL_WIDTH);
        setVisible(false);
        
        // === HEADER ===
        HBox header = createHeader();
        
        // === SEARCH BAR ===
        VBox searchBarContainer = createSearchBar();
        
        // === RESULTS LIST ===
        resultsListView = createResultsList();
        VBox.setVgrow(resultsListView, Priority.ALWAYS);
        
        // === STATUS LABEL ===
        statusLabel = new Label("Search messages in this conversation");
        statusLabel.getStyleClass().add("search-status");
        statusLabel.setWrapText(true);
        statusLabel.setPadding(new Insets(20));
        statusLabel.setAlignment(Pos.CENTER);
        
        // Stack results and status
        StackPane contentArea = new StackPane(statusLabel, resultsListView);
        VBox.setVgrow(contentArea, Priority.ALWAYS);
        
        getChildren().addAll(header, searchBarContainer, contentArea);
        
        // Initialize search service
        initializeSearchService();
    }
    
    private HBox createHeader() {
        HBox header = new HBox();
        header.getStyleClass().add("search-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(15, 15, 15, 15));
        header.setSpacing(15);
        
        // Close button
        Button closeButton = new Button("✕");
        closeButton.getStyleClass().add("search-close-button");
        closeButton.setOnAction(e -> hide());
        
        // Title
        Label title = new Label("Search Messages");
        title.getStyleClass().add("search-title");
        HBox.setHgrow(title, Priority.ALWAYS);
        
        header.getChildren().addAll(closeButton, title);
        return header;
    }
    
    private VBox createSearchBar() {
        VBox container = new VBox();
        container.getStyleClass().add("search-bar-container");
        container.setPadding(new Insets(10, 15, 10, 15));
        
        // Search field with icon
        HBox searchBox = new HBox();
        searchBox.getStyleClass().add("search-bar");
        searchBox.setAlignment(Pos.CENTER_LEFT);
        searchBox.setSpacing(10);
        
        Label searchIcon = new Label("🔍");
        searchIcon.getStyleClass().add("search-icon");
        
        searchField = new TextField();
        searchField.setPromptText("Search...");
        searchField.getStyleClass().add("search-input");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        
        // Clear button
        Button clearButton = new Button("✕");
        clearButton.getStyleClass().add("search-clear-button");
        clearButton.setVisible(false);
        clearButton.setOnAction(e -> {
            searchField.clear();
            searchField.requestFocus();
        });
        
        // Search on text change with debounce
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            clearButton.setVisible(newVal != null && !newVal.isEmpty());
            debounceSearch(newVal);
        });
        
        // ESC to close
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                hide();
            }
        });
        
        searchBox.getChildren().addAll(searchIcon, searchField, clearButton);
        container.getChildren().add(searchBox);
        return container;
    }
    
    private ListView<SearchHit> createResultsList() {
        ListView<SearchHit> listView = new ListView<>(searchResults);
        listView.getStyleClass().add("search-results-list");
        listView.setPlaceholder(new Label(""));
        
        // Custom cell factory
        listView.setCellFactory(lv -> new SearchResultCell());
        
        // Handle selection
        listView.setOnMouseClicked(e -> {
            SearchHit selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null && onResultSelected != null) {
                onResultSelected.accept(selected);
            }
        });
        
        return listView;
    }
    
    private void initializeSearchService() {
        try {
            LocalDatabase db = LocalDatabase.getInstance();
            if (db != null && db.getConnection() != null) {
                searchService = new FTS5SearchService();
                searchService.initialize(db.getConnection());
            }
        } catch (Exception e) {
            System.err.println("[SearchPanel] Failed to initialize search service: " + e.getMessage());
        }
    }
    
    /**
     * Debounce search to avoid excessive queries
     */
    private void debounceSearch(String query) {
        if (debounceTimer != null) {
            debounceTimer.cancel();
        }
        
        if (query == null || query.trim().length() < 2) {
            Platform.runLater(() -> {
                searchResults.clear();
                statusLabel.setText("Enter at least 2 characters to search");
                statusLabel.setVisible(true);
                resultsListView.setVisible(false);
            });
            return;
        }
        
        debounceTimer = new Timer();
        debounceTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                performSearch(query.trim());
            }
        }, 200); // 200ms debounce
    }
    
    /**
     * Execute FTS5 search
     */
    private void performSearch(String query) {
        if (searchService == null || currentConversationId == null) {
            Platform.runLater(() -> {
                statusLabel.setText("Search not available");
                statusLabel.setVisible(true);
                resultsListView.setVisible(false);
            });
            return;
        }
        
        Platform.runLater(() -> {
            statusLabel.setText("Searching...");
            statusLabel.setVisible(true);
        });
        
        try {
            List<SearchHit> results = searchService.searchWithSnippets(
                currentConversationId, query, currentUsername);
            
            Platform.runLater(() -> {
                searchResults.clear();
                
                if (results.isEmpty()) {
                    statusLabel.setText("No messages found for \"" + query + "\"");
                    statusLabel.setVisible(true);
                    resultsListView.setVisible(false);
                } else {
                    searchResults.addAll(results);
                    statusLabel.setVisible(false);
                    resultsListView.setVisible(true);
                }
            });
            
        } catch (Exception e) {
            System.err.println("[SearchPanel] Search error: " + e.getMessage());
            Platform.runLater(() -> {
                statusLabel.setText("Search error: " + e.getMessage());
                statusLabel.setVisible(true);
                resultsListView.setVisible(false);
            });
        }
    }
    
    /**
     * Show panel with slide-in animation
     */
    public void show(String conversationId, String currentUsername) {
        this.currentConversationId = conversationId;
        this.currentUsername = currentUsername;
        
        // Re-initialize search service if needed
        if (searchService == null) {
            initializeSearchService();
        }
        
        setVisible(true);
        
        TranslateTransition slideIn = new TranslateTransition(SLIDE_DURATION, this);
        slideIn.setFromX(PANEL_WIDTH);
        slideIn.setToX(0);
        slideIn.setInterpolator(javafx.animation.Interpolator.EASE_BOTH);
        slideIn.setOnFinished(e -> {
            searchField.requestFocus();
        });
        slideIn.play();
        
        // Clear previous search
        searchField.clear();
        searchResults.clear();
        statusLabel.setText("Search messages in this conversation");
        statusLabel.setVisible(true);
        resultsListView.setVisible(false);
    }
    
    /**
     * Hide panel with slide-out animation
     */
    public void hide() {
        TranslateTransition slideOut = new TranslateTransition(SLIDE_DURATION, this);
        slideOut.setFromX(0);
        slideOut.setToX(PANEL_WIDTH);
        slideOut.setInterpolator(javafx.animation.Interpolator.EASE_BOTH);
        slideOut.setOnFinished(e -> {
            setVisible(false);
            if (onClose != null) {
                onClose.run();
            }
        });
        slideOut.play();
        
        // Cancel any pending search
        if (debounceTimer != null) {
            debounceTimer.cancel();
        }
    }
    
    /**
     * Check if panel is currently visible
     */
    public boolean isShowing() {
        return isVisible() && getTranslateX() == 0;
    }
    
    /**
     * Set callback for when a search result is selected
     */
    public void setOnResultSelected(Consumer<SearchHit> callback) {
        this.onResultSelected = callback;
    }
    
    /**
     * Set callback for when panel is closed
     */
    public void setOnClose(Runnable callback) {
        this.onClose = callback;
    }
    
    /**
     * Custom cell for search results
     */
    private static class SearchResultCell extends ListCell<SearchHit> {
        
        private final VBox container;
        private final Label dateLabel;
        private final TextFlow messageFlow;
        private final HBox iconRow;
        
        public SearchResultCell() {
            container = new VBox();
            container.getStyleClass().add("search-result-item");
            container.setSpacing(5);
            container.setPadding(new Insets(12, 15, 12, 15));
            
            // Date row
            dateLabel = new Label();
            dateLabel.getStyleClass().add("search-result-date");
            
            // Icon for outgoing messages
            iconRow = new HBox();
            iconRow.setSpacing(5);
            iconRow.setAlignment(Pos.CENTER_LEFT);
            
            // Message text with highlights
            messageFlow = new TextFlow();
            messageFlow.getStyleClass().add("search-result-text");
            
            container.getChildren().addAll(dateLabel, iconRow, messageFlow);
            
            // Hover effect
            setOnMouseEntered(e -> {
                if (!isEmpty()) {
                    container.getStyleClass().add("search-result-hover");
                }
            });
            setOnMouseExited(e -> {
                container.getStyleClass().remove("search-result-hover");
            });
        }
        
        @Override
        protected void updateItem(SearchHit item, boolean empty) {
            super.updateItem(item, empty);
            
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
            } else {
                dateLabel.setText(item.getFormattedDate());
                
                // Clear previous content
                iconRow.getChildren().clear();
                messageFlow.getChildren().clear();
                
                // Add outgoing indicator
                if (item.isOutgoing()) {
                    Label checkIcon = new Label("✓✓");
                    checkIcon.getStyleClass().add("search-result-check");
                    iconRow.getChildren().add(checkIcon);
                }
                
                // Parse highlighted text (convert <b> tags to styled Text nodes)
                parseHighlightedText(item.getHighlightedText(), messageFlow);
                
                setGraphic(container);
                setText(null);
            }
        }
        
        /**
         * Parse HTML-like <b> tags and create styled Text nodes
         */
        private void parseHighlightedText(String html, TextFlow flow) {
            if (html == null || html.isEmpty()) {
                return;
            }
            
            // Simple parser for <b>...</b> tags
            String remaining = html;
            while (!remaining.isEmpty()) {
                int boldStart = remaining.indexOf("<b>");
                
                if (boldStart == -1) {
                    // No more bold tags, add remaining as normal text
                    if (!remaining.isEmpty()) {
                        Text normalText = new Text(remaining);
                        normalText.getStyleClass().add("search-result-normal");
                        flow.getChildren().add(normalText);
                    }
                    break;
                }
                
                // Add text before bold tag
                if (boldStart > 0) {
                    Text normalText = new Text(remaining.substring(0, boldStart));
                    normalText.getStyleClass().add("search-result-normal");
                    flow.getChildren().add(normalText);
                }
                
                // Find closing tag
                int boldEnd = remaining.indexOf("</b>", boldStart);
                if (boldEnd == -1) {
                    // Malformed, add rest as normal
                    Text normalText = new Text(remaining.substring(boldStart));
                    normalText.getStyleClass().add("search-result-normal");
                    flow.getChildren().add(normalText);
                    break;
                }
                
                // Extract bold text
                String boldContent = remaining.substring(boldStart + 3, boldEnd);
                Text boldText = new Text(boldContent);
                boldText.getStyleClass().add("search-result-highlight");
                flow.getChildren().add(boldText);
                
                // Continue with rest
                remaining = remaining.substring(boldEnd + 4);
            }
        }
    }
}

