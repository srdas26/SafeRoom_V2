package com.saferoom.gui.controller;

import com.saferoom.gui.view.cell.ContactCell;
import com.saferoom.gui.service.ContactService;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessagesController {

    @FXML
    private SplitPane mainSplitPane;
    @FXML
    private ListView<Contact> contactListView;
    @FXML
    private TextField searchField;

    // Boş durum bileşenleri
    @FXML
    private VBox emptyContactsPlaceholder;
    @FXML
    private Button addFriendButton;

    @FXML
    private ChatViewController chatViewController;

    private static MessagesController instance;
    private ChangeListener<Contact> contactSelectionListener;
    private final Map<String, String> connectionStatus = new ConcurrentHashMap<>();
    private final ContactService contactService = ContactService.getInstance();

    // YENİ: Sayfa değişimi için sinyalci (Callback)
    private Runnable onNavigateToFriendsRequest;

    @FXML
    public void initialize() {
        instance = this;

        mainSplitPane.setDividerPositions(0.30);

        setupModelAndListViews();
        setupContactSelectionListener();
        loadFriendsAsContacts();

        // Sağ tarafı başlangıçta boş ekran yap
        if (chatViewController != null) {
            chatViewController.showWelcomeScreen();
        }

        // Liste boş/dolu kontrolü (Placeholder yönetimi)
        contactListView.getItems().addListener((ListChangeListener<Contact>) c -> {
            updateContactListVisibility();
        });

        // İlk açılışta kontrol et
        updateContactListVisibility();
    }

    /**
     * Ana Kontrolcüden (ClientMenu) bu metot çağrılarak yönlendirme işlemi
     * tanımlanır.
     */
    public void setOnNavigateToFriendsRequest(Runnable handler) {
        this.onNavigateToFriendsRequest = handler;
    }

    /**
     * "Add Friend" butonuna basılınca çalışır. Artık Pop-up AÇMAZ, sadece
     * Friends sayfasına gitme isteği gönderir.
     */
    @FXML
    private void handleAddFriendClick() {
        if (onNavigateToFriendsRequest != null) {
            System.out.println("[MessagesController] ➡️ Yönlendirme isteği gönderiliyor: Friends Tab");
            onNavigateToFriendsRequest.run();
        } else {
            System.err.println("[MessagesController] ⚠️ Yönlendirme işleyicisi (Handler) ayarlanmamış!");
        }
    }

    private void updateContactListVisibility() {
        boolean isEmpty = contactListView.getItems().isEmpty();

        if (emptyContactsPlaceholder != null) {
            emptyContactsPlaceholder.setVisible(isEmpty);
            emptyContactsPlaceholder.setManaged(isEmpty);
        }

        contactListView.setVisible(!isEmpty);
        contactListView.setManaged(!isEmpty);
    }

    // ... (Geri kalan metotlar aynen korunuyor) ...
    private void loadFriendsAsContacts() {
        try {
            String currentUser = com.saferoom.gui.utils.UserSession.getInstance().getDisplayName();
            if (currentUser != null && !currentUser.isEmpty()) {
                contactService.loadFriendsAsContacts(currentUser);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupModelAndListViews() {
        contactListView.setItems(contactService.getContactList());
        contactListView.setCellFactory(param -> new ContactCell());
    }

    private void setupContactSelectionListener() {
        contactSelectionListener = (obs, oldSelection, newSelection) -> {
            if (oldSelection != null) {
                contactService.clearActiveChat();
            }

            if (newSelection != null && chatViewController != null) {
                contactService.setActiveChat(newSelection.getId());
                chatViewController.initChannel(newSelection.getId());
                chatViewController.setHeader(
                        newSelection.getName(),
                        newSelection.getStatus(),
                        newSelection.getAvatarChar(),
                        newSelection.isGroup()
                );
                tryP2PConnection(newSelection.getId());
            }
        };
        contactListView.getSelectionModel().selectedItemProperty().addListener(contactSelectionListener);
    }

    public static void openChatWithUser(String username) {
        if (instance != null) {
            Platform.runLater(() -> instance.selectOrAddUser(username));
        }
    }

    public static void openChatWithUserAndNotify(String username, String notificationMessage) {
        if (instance != null) {
            Platform.runLater(() -> {
                instance.selectOrAddUser(username);
                if (instance.chatViewController != null) {
                    try {
                        Thread.sleep(100);
                        com.saferoom.gui.service.ChatService chatService = com.saferoom.gui.service.ChatService.getInstance();
                        com.saferoom.gui.model.User systemUser = new com.saferoom.gui.model.User("system", "System");
                        chatService.sendMessage(username, notificationMessage, systemUser);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    private void selectOrAddUser(String username) {
        if (contactService.hasContact(username)) {
            Contact existingContact = contactService.getContact(username);
            contactListView.getSelectionModel().select(existingContact);
        } else {
            contactService.addNewContact(username);
            Platform.runLater(() -> {
                Contact newContact = contactService.getContact(username);
                if (newContact != null) {
                    contactListView.getSelectionModel().select(newContact);
                }
            });
        }
    }

    // Contact Inner Class
    public static class Contact {

        private final String id, name, status, lastMessage, time;
        private final int unreadCount;
        private final boolean isGroup;

        public Contact(String id, String name, String status, String lastMessage, String time, int unreadCount, boolean isGroup) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.lastMessage = lastMessage;
            this.time = time;
            this.unreadCount = unreadCount;
            this.isGroup = isGroup;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getStatus() {
            return status;
        }

        public String getLastMessage() {
            return lastMessage;
        }

        public String getTime() {
            return time;
        }

        public int getUnreadCount() {
            return unreadCount;
        }

        public boolean isGroup() {
            return isGroup;
        }

        public String getAvatarChar() {
            return name.isEmpty() ? "" : name.substring(0, 1);
        }

        public boolean isOnline() {
            return status.equalsIgnoreCase("online");
        }
    }

    // P2P Management
    private void tryP2PConnection(String username) {
        if (username.contains("Grubu") || username.equals("meeting_phoenix")) {
            return;
        }

        com.saferoom.p2p.P2PConnectionManager p2pManager = com.saferoom.p2p.P2PConnectionManager.getInstance();
        if (p2pManager.hasActiveConnection(username)) {
            connectionStatus.put(username, "P2P Active");
            // Status güncelleme mantığı eklenebilir
            return;
        }

        if ("P2P Active".equals(connectionStatus.get(username)) || "Connecting...".equals(connectionStatus.get(username))) {
            return;
        }

        connectionStatus.put(username, "Connecting...");

        p2pManager.createConnection(username)
                .thenAcceptAsync(success -> {
                    Platform.runLater(() -> {
                        if (success) {
                            connectionStatus.put(username, "P2P Active");
                        }else {
                            connectionStatus.put(username, "Server Relay");
                        }
                    });
                });
    }

    public String getConnectionStatus(String username) {
        return connectionStatus.getOrDefault(username, "Unknown");
    }

    public boolean hasP2PConnection(String username) {
        return "P2P Active".equals(connectionStatus.get(username));
    }
}
