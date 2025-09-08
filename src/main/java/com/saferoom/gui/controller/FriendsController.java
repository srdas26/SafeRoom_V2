// ✅ FriendsController.java
package com.saferoom.gui.controller;

import com.jfoenix.controls.JFXButton;
import com.saferoom.client.ClientMenu;
import com.saferoom.gui.model.Friend;
import com.saferoom.gui.utils.UserSession;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class FriendsController {

    @FXML private VBox friendsContainer;
    @FXML private HBox filterBar;
    @FXML private Label totalFriendsLabel;
    @FXML private Label onlineFriendsLabel;
    @FXML private Label pendingFriendsLabel;
    @FXML private TextField searchField;
    @FXML private ListView<Map<String, Object>> searchResultsList;

    private final ObservableList<Friend> allFriends = FXCollections.observableArrayList();
    private final ObservableList<Friend> pendingFriends = FXCollections.observableArrayList();
    private Timeline searchDebouncer;
    private Timeline friendsRefresher;

    @FXML
    public void initialize() {
        // Search functionality setup
        setupSearchFunctionality();
        
        // Load real data from backend
        loadFriendsData();
        loadStats();

        // Setup filter buttons
        setupFilterButtons();

        // Set "All" as the default active tab on initialization
        filterBar.getChildren().get(1).getStyleClass().add("active-tab");
        loadAllFriends();
        
        // Setup auto-refresh for friends list every 30 seconds
        setupAutoRefresh();
    }

    /**
     * Gerçek arkadaş verilerini backend'den yükle
     */
    private void loadFriendsData() {
        CompletableFuture.supplyAsync(() -> {
            try {
                String currentUser = UserSession.getInstance().getDisplayName();
                
                // Arkadaş listesini getir
                com.saferoom.grpc.SafeRoomProto.FriendsListResponse friendsResponse = 
                    ClientMenu.getFriendsList(currentUser);
                
                // Pending istekleri getir
                com.saferoom.grpc.SafeRoomProto.PendingRequestsResponse pendingResponse = 
                    ClientMenu.getPendingFriendRequests(currentUser);
                
                if (friendsResponse.getSuccess()) {
                    // Friends listesini güncelle
                    allFriends.clear();
                    for (com.saferoom.grpc.SafeRoomProto.FriendInfo friendInfo : friendsResponse.getFriendsList()) {
                        String status = friendInfo.getIsOnline() ? "Online" : 
                                       (friendInfo.getIsVerified() ? "Offline" : "Not Verified");
                        String lastSeen = friendInfo.getLastSeen().isEmpty() ? "Never" : friendInfo.getLastSeen();
                        String activity = friendInfo.getIsOnline() ? "Active now" : "Last seen: " + lastSeen;
                        
                        Friend friend = new Friend(
                            friendInfo.getUsername(), 
                            status,
                            activity, 
                            friendInfo.getIsOnline() ? "🟢" : "🔴"
                        );
                        allFriends.add(friend);
                    }
                }
                
                if (pendingResponse.getSuccess()) {
                    // Pending listesini güncelle
                    pendingFriends.clear();
                    for (com.saferoom.grpc.SafeRoomProto.FriendRequestInfo requestInfo : pendingResponse.getRequestsList()) {
                        Friend pendingFriend = new Friend(
                            requestInfo.getSender(),
                            "Friend Request",
                            "Sent: " + requestInfo.getSentAt(),
                            "Pending",
                            requestInfo.getRequestId() // requestId'yi ekle
                        );
                        pendingFriends.add(pendingFriend);
                    }
                }
                
                return true;
            } catch (Exception e) {
                System.err.println("❌ Error loading friends data: " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        }).thenAcceptAsync(success -> {
            Platform.runLater(() -> {
                if (success) {
                    System.out.println("✅ Friends data loaded successfully");
                    updateStats();
                } else {
                    System.out.println("❌ Failed to load friends data");
                }
            });
        });
    }

    /**
     * İstatistikleri yükle
     */
    private void loadStats() {
        CompletableFuture.supplyAsync(() -> {
            try {
                String currentUser = UserSession.getInstance().getDisplayName();
                com.saferoom.grpc.SafeRoomProto.FriendshipStatsResponse statsResponse = 
                    ClientMenu.getFriendshipStats(currentUser);
                
                if (statsResponse.getSuccess()) {
                    return statsResponse.getStats();
                }
                return null;
            } catch (Exception e) {
                System.err.println("❌ Error loading stats: " + e.getMessage());
                return null;
            }
        }).thenAcceptAsync(stats -> {
            Platform.runLater(() -> {
                if (stats != null) {
                    totalFriendsLabel.setText(String.valueOf(stats.getTotalFriends()));
                    pendingFriendsLabel.setText(String.valueOf(stats.getPendingRequests()));
                    // Online friends için basit hesaplama (gerçek implementation'da online tracking gerekir)
                    onlineFriendsLabel.setText("0"); // TODO: Implement online tracking
                }
            });
        });
    }

    /**
     * İstatistikleri güncelle
     */
    private void updateStats() {
        totalFriendsLabel.setText(String.valueOf(allFriends.size()));
        pendingFriendsLabel.setText(String.valueOf(pendingFriends.size()));
        
        // Online friends hesapla
        long onlineCount = allFriends.stream()
            .filter(Friend::isOnline)
            .count();
        onlineFriendsLabel.setText(String.valueOf(onlineCount));
    }

    /**
     * Filter buttonlarını ayarla
     */
    private void setupFilterButtons() {
        for (Node node : filterBar.getChildren()) {
            JFXButton button = (JFXButton) node;
            button.setOnAction(event -> {
                // Remove 'active-tab' from all buttons
                filterBar.getChildren().forEach(btn -> btn.getStyleClass().remove("active-tab"));
                // Add 'active-tab' to the clicked button
                button.getStyleClass().add("active-tab");

                switch (button.getText()) {
                    case "Online":
                        loadOnlineFriends();
                        break;
                    case "All":
                        loadAllFriends();
                        break;
                    case "Pending":
                        loadPendingRequests();
                        break;
                    case "Blocked":
                        loadBlockedUsers();
                        break;
                    default:
                        friendsContainer.getChildren().clear();
                        break;
                }
            });
        }
    }

    /**
     * Tüm arkadaşları göster
     */
    private void loadAllFriends() {
        updateFriendsList(allFriends, "All Friends");
    }

    /**
     * Online arkadaşları göster
     */
    private void loadOnlineFriends() {
        ObservableList<Friend> onlineFriends = allFriends.stream()
            .filter(Friend::isOnline)
            .collect(Collectors.toCollection(FXCollections::observableArrayList));
        updateFriendsList(onlineFriends, "Online Friends");
    }

    /**
     * Bekleyen istekleri göster
     */
    private void loadPendingRequests() {
        // Pending requests için özel bir cell factory kullan
        updatePendingRequestsList();
    }

    /**
     * Pending requests için özel liste
     */
    private void updatePendingRequestsList() {
        friendsContainer.getChildren().clear();
        if (!pendingFriends.isEmpty()) {
            ListView<Friend> listView = new ListView<>(pendingFriends);
            listView.getStyleClass().add("friends-list-view");
            listView.setCellFactory(param -> new PendingRequestCell());
            VBox.setVgrow(listView, Priority.ALWAYS);
            friendsContainer.getChildren().add(listView);
        } else {
            Label noRequestsLabel = new Label("No pending friend requests");
            noRequestsLabel.getStyleClass().add("no-data-label");
            friendsContainer.getChildren().add(noRequestsLabel);
        }
    }

    /**
     * Blocked users (placeholder)
     */
    private void loadBlockedUsers() {
        friendsContainer.getChildren().clear();
        Label noBlockedLabel = new Label("No blocked users");
        noBlockedLabel.getStyleClass().add("no-data-label");
        friendsContainer.getChildren().add(noBlockedLabel);
    }

    private void setupSearchFunctionality() {
        // Debouncing - 500ms bekle, sonra ara
        searchDebouncer = new Timeline(new KeyFrame(Duration.millis(500), e -> performSearch()));
        
        searchField.textProperty().addListener((obs, oldText, newText) -> {
            searchDebouncer.stop();
            if (newText != null && newText.trim().length() >= 2) {
                searchDebouncer.play();
                searchResultsList.setVisible(true);
            } else {
                searchResultsList.getItems().clear();
                searchResultsList.setVisible(false);
            }
        });
        
        // Search results cell factory
        searchResultsList.setCellFactory(listView -> new SearchResultCell());
        
        // Başka yere tıklayınca search results'ı gizle
        searchField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused) {
                Timeline hideDelay = new Timeline(new KeyFrame(Duration.millis(100), e -> {
                    if (!searchResultsList.isFocused()) {
                        searchResultsList.setVisible(false);
                    }
                }));
                hideDelay.play();
            }
        });
    }

    private void performSearch() {
        String searchTerm = searchField.getText().trim();
        if (searchTerm.length() < 2) return;
        
        System.out.println("🔍 Searching for: '" + searchTerm + "'");
        
        // Background thread'de arama yap
        CompletableFuture.supplyAsync(() -> {
            try {
                String currentUser = UserSession.getInstance().getDisplayName();
                System.out.println("📡 Sending search request to server...");
                List<Map<String, Object>> results = ClientMenu.searchUsers(searchTerm, currentUser);
                System.out.println("✅ Got " + results.size() + " results from server");
                return results;
            } catch (Exception e) {
                System.err.println("❌ Search error: " + e.getMessage());
                e.printStackTrace();
                return Collections.<Map<String, Object>>emptyList();
            }
        }).thenAcceptAsync(results -> {
            Platform.runLater(() -> {
                System.out.println("🎨 Updating UI with " + results.size() + " results");
                searchResultsList.getItems().clear();
                searchResultsList.getItems().addAll(results);
            });
        });
    }

    private void updateFriendsList(ObservableList<Friend> friends, String header) {
        friendsContainer.getChildren().clear();
        if (friends != null && !friends.isEmpty()) {
            ListView<Friend> listView = new ListView<>(friends);
            listView.getStyleClass().add("friends-list-view");
            listView.setCellFactory(param -> new FriendCell());
            VBox.setVgrow(listView, Priority.ALWAYS);
            friendsContainer.getChildren().add(listView);
        }
    }

    // Search result cell for user search
    static class SearchResultCell extends ListCell<Map<String, Object>> {
        private final HBox hbox = new HBox(10);
        private final Circle avatar = new Circle(15);
        private final VBox userInfo = new VBox(2);
        private final Label nameLabel = new Label();
        private final Label emailLabel = new Label();
        private final Pane spacer = new Pane();
        private final JFXButton addButton = new JFXButton("Add Friend");

        public SearchResultCell() {
            super();
            avatar.setFill(Color.LIGHTBLUE);
            nameLabel.getStyleClass().add("search-result-name");
            emailLabel.getStyleClass().add("search-result-email");
            addButton.getStyleClass().add("search-add-button");
            
            userInfo.getChildren().addAll(nameLabel, emailLabel);
            HBox.setHgrow(spacer, Priority.ALWAYS);
            hbox.getChildren().addAll(avatar, userInfo, spacer, addButton);
            hbox.setAlignment(Pos.CENTER_LEFT);
            hbox.setPadding(new Insets(5));
        }

        @Override
        protected void updateItem(Map<String, Object> user, boolean empty) {
            super.updateItem(user, empty);
            if (empty || user == null) {
                setGraphic(null);
                setText(null);
            } else {
                String username = (String) user.get("username");
                String email = (String) user.get("email");
                
                nameLabel.setText(username);
                emailLabel.setText(email);
                
                // Avatar letter
                if (username != null && !username.isEmpty()) {
                    // Create a label for avatar text instead of circle
                    Label avatarText = new Label(username.substring(0, 1).toUpperCase());
                    avatarText.getStyleClass().add("search-avatar");
                    avatarText.setMinSize(30, 30);
                    avatarText.setMaxSize(30, 30);
                    avatarText.setAlignment(Pos.CENTER);
                    
                    // Replace circle with label in hbox
                    hbox.getChildren().set(0, avatarText);
                }
                
                // Add click handler for profile view
                setOnMouseClicked(e -> {
                    if (e.getClickCount() == 1) { // Single click to view profile
                        openProfile(username);
                    }
                });
                
                addButton.setOnAction(e -> sendFriendRequest(username));
                setGraphic(hbox);
            }
        }
    }

    private static void sendFriendRequest(String username) {
        System.out.println("Sending friend request to: " + username);
        CompletableFuture.supplyAsync(() -> {
            try {
                String currentUser = UserSession.getInstance().getDisplayName();
                com.saferoom.grpc.SafeRoomProto.FriendResponse response = 
                    ClientMenu.sendFriendRequest(currentUser, username);
                return response;
            } catch (Exception e) {
                System.err.println("❌ Error sending friend request: " + e.getMessage());
                return null;
            }
        }).thenAcceptAsync(response -> {
            Platform.runLater(() -> {
                if (response != null && response.getSuccess()) {
                    System.out.println("✅ Friend request sent successfully!");
                    // TODO: Show success message to user
                } else {
                    System.out.println("❌ Failed to send friend request");
                    // TODO: Show error message to user
                }
            });
        });
    }
    
    /**
     * Arkadaşlık isteğini kabul et
     */
    private static void acceptFriendRequest(int requestId, String senderUsername) {
        System.out.println("Accepting friend request from: " + senderUsername);
        CompletableFuture.supplyAsync(() -> {
            try {
                String currentUser = UserSession.getInstance().getDisplayName();
                com.saferoom.grpc.SafeRoomProto.Status response = 
                    ClientMenu.acceptFriendRequest(requestId, currentUser);
                return response;
            } catch (Exception e) {
                System.err.println("❌ Error accepting friend request: " + e.getMessage());
                e.printStackTrace();
                return null;
            }
        }).thenAcceptAsync(response -> {
            Platform.runLater(() -> {
                if (response != null && response.getCode() == 0) {
                    System.out.println("✅ Friend request accepted successfully!");
                    // Refresh the friends list
                    FriendsController instance = getCurrentInstance();
                    if (instance != null) {
                        instance.loadFriendsData();
                    }
                } else {
                    String errorMsg = response != null ? response.getMessage() : "Unknown error";
                    System.out.println("❌ Failed to accept friend request: " + errorMsg);
                }
            });
        });
    }
    
    /**
     * Arkadaşlık isteğini reddet
     */
    private static void rejectFriendRequest(int requestId, String senderUsername) {
        System.out.println("Rejecting friend request from: " + senderUsername);
        CompletableFuture.supplyAsync(() -> {
            try {
                String currentUser = UserSession.getInstance().getDisplayName();
                com.saferoom.grpc.SafeRoomProto.Status response = 
                    ClientMenu.rejectFriendRequest(requestId, currentUser);
                return response;
            } catch (Exception e) {
                System.err.println("❌ Error rejecting friend request: " + e.getMessage());
                e.printStackTrace();
                return null;
            }
        }).thenAcceptAsync(response -> {
            Platform.runLater(() -> {
                if (response != null && response.getCode() == 0) {
                    System.out.println("✅ Friend request rejected successfully!");
                    // Refresh the friends list
                    FriendsController instance = getCurrentInstance();
                    if (instance != null) {
                        instance.loadFriendsData();
                    }
                } else {
                    String errorMsg = response != null ? response.getMessage() : "Unknown error";
                    System.out.println("❌ Failed to reject friend request: " + errorMsg);
                }
            });
        });
    }
    
    private static FriendsController currentInstance;
    
    public FriendsController() {
        currentInstance = this;
    }
    
    private static FriendsController getCurrentInstance() {
        return currentInstance;
    }

    // ===============================
    // CELL FACTORIES
    // ===============================

    // ✅ Boş hücrelerde hover engellendi ve görünüm optimize edildi
    static class FriendCell extends ListCell<Friend> {
        private final HBox hbox = new HBox(15);
        private final Label avatar = new Label();
        private final VBox infoBox = new VBox(2);
        private final Label nameLabel = new Label();
        private final Label statusLabel = new Label();
        private final HBox activityBox = new HBox(5);
        private final Label activityTypeLabel = new Label();
        private final Label activityLabel = new Label();
        private final Pane spacer = new Pane();
        private final HBox actionButtons = new HBox(10);

        public FriendCell() {
            super();
            avatar.getStyleClass().add("friend-avatar");
            nameLabel.getStyleClass().add("friend-name");
            statusLabel.getStyleClass().add("friend-status");
            activityTypeLabel.getStyleClass().add("friend-activity-type");
            activityLabel.getStyleClass().add("friend-activity");

            FontIcon messageIcon = new FontIcon("fas-comment-dots");
            FontIcon callIcon = new FontIcon("fas-phone");
            messageIcon.getStyleClass().add("action-icon");
            callIcon.getStyleClass().add("action-icon");

            actionButtons.getChildren().addAll(messageIcon, callIcon);
            actionButtons.setAlignment(Pos.CENTER);

            activityBox.getChildren().addAll(activityTypeLabel, activityLabel);
            infoBox.getChildren().addAll(nameLabel, statusLabel, activityBox);

            HBox.setHgrow(spacer, Priority.ALWAYS);
            hbox.getChildren().addAll(avatar, infoBox, spacer, actionButtons);
            hbox.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Friend friend, boolean empty) {
            super.updateItem(friend, empty);
            if (empty || friend == null) {
                setGraphic(null);
                setText(null);
                setStyle("");
                setDisable(true);
                setMouseTransparent(true);
                setOnMouseEntered(null);
                setOnMouseExited(null);
            } else {
                avatar.setText(friend.getAvatarChar());
                nameLabel.setText(friend.getName());
                statusLabel.setText(friend.getStatus());
                activityTypeLabel.setText(friend.getActivityType());
                activityLabel.setText(friend.getActivity());
                activityBox.setVisible(!friend.getActivity().isEmpty());

                setGraphic(hbox);
                setDisable(false);
                setMouseTransparent(false);
            }
        }
    }

    // Pending friend requests cell
    static class PendingRequestCell extends ListCell<Friend> {
        private final HBox hbox = new HBox(15);
        private final Label avatar = new Label();
        private final VBox infoBox = new VBox(2);
        private final Label nameLabel = new Label();
        private final Label statusLabel = new Label();
        private final Pane spacer = new Pane();
        private final HBox actionButtons = new HBox(10);
        private final JFXButton acceptButton = new JFXButton("Accept");
        private final JFXButton rejectButton = new JFXButton("Reject");

        public PendingRequestCell() {
            super();
            avatar.getStyleClass().add("friend-avatar");
            nameLabel.getStyleClass().add("friend-name");
            statusLabel.getStyleClass().add("friend-status");
            
            acceptButton.getStyleClass().add("accept-button");
            rejectButton.getStyleClass().add("reject-button");

            actionButtons.getChildren().addAll(acceptButton, rejectButton);
            actionButtons.setAlignment(Pos.CENTER);

            infoBox.getChildren().addAll(nameLabel, statusLabel);

            HBox.setHgrow(spacer, Priority.ALWAYS);
            hbox.getChildren().addAll(avatar, infoBox, spacer, actionButtons);
            hbox.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Friend friend, boolean empty) {
            super.updateItem(friend, empty);
            if (empty || friend == null) {
                setGraphic(null);
                setText(null);
                setStyle("");
                setDisable(true);
                setMouseTransparent(true);
            } else {
                avatar.setText(friend.getAvatarChar());
                nameLabel.setText(friend.getName());
                statusLabel.setText(friend.getStatus());

                // Button actions - gerçek requestId kullan
                int requestId = friend.getRequestId();
                acceptButton.setOnAction(e -> {
                    // Butonu devre dışı bırak
                    acceptButton.setDisable(true);
                    rejectButton.setDisable(true);
                    acceptFriendRequest(requestId, friend.getName());
                });
                rejectButton.setOnAction(e -> {
                    // Butonu devre dışı bırak
                    acceptButton.setDisable(true);
                    rejectButton.setDisable(true);
                    rejectFriendRequest(requestId, friend.getName());
                });

                setGraphic(hbox);
                setDisable(false);
                setMouseTransparent(false);
            }
        }
    }
    
    private static void openProfile(String username) {
        System.out.println("Opening profile for: " + username);
        try {
            MainController mainController = MainController.getInstance();
            if (mainController != null) {
                mainController.handleProfile(username);
            }
        } catch (Exception e) {
            System.err.println("Error opening profile: " + e.getMessage());
        }
    }
    
    /**
     * Auto-refresh setup for friends list
     */
    private void setupAutoRefresh() {
        // Her 30 saniyede bir friends listesini yenile
        friendsRefresher = new Timeline(new KeyFrame(Duration.seconds(30), e -> {
            System.out.println("🔄 Auto-refreshing friends list...");
            loadFriendsData();
        }));
        friendsRefresher.setCycleCount(Timeline.INDEFINITE);
        friendsRefresher.play();
    }
}