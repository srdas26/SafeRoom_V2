package com.saferoom.gui.service;

import com.saferoom.gui.controller.MessagesController.Contact;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Persistent contact and chat management service
 * Handles contact storage, last messages, unread counts, etc.
 */
public class ContactService {
    private static ContactService instance;
    
    // Persistent storage for contacts
    private final Map<String, Contact> contacts = new ConcurrentHashMap<>();
    private final ObservableList<Contact> contactList = FXCollections.observableArrayList();
    
    // Track unread messages per contact
    private final Map<String, Integer> unreadCounts = new ConcurrentHashMap<>();
    
    // Track last message per contact
    private final Map<String, String> lastMessages = new ConcurrentHashMap<>();
    private final Map<String, String> lastMessageTimes = new ConcurrentHashMap<>();
    
    // Track active chat (for read/unread logic)
    private String activeChat = null;
    
    private ContactService() {
        initializeDummyContacts();
    }
    
    public static ContactService getInstance() {
        if (instance == null) {
            instance = new ContactService();
        }
        return instance;
    }
    
    /**
     * Initialize with empty contact list - only real P2P users will be added
     */
    private void initializeDummyContacts() {
        // No dummy contacts - start with empty list
        // Real contacts will be added when P2P connections are made
        System.out.println("[ContactService] 🧹 Started with clean contact list - no dummy users");
    }
    
    /**
     * Load friends from backend and add them to contact list
     * This should be called when MessagesController initializes
     */
    public void loadFriendsAsContacts(String username) {
        System.out.printf("[ContactService] 📥 Loading friends for %s...%n", username);
        
        // Load friends asynchronously
        javafx.concurrent.Task<java.util.List<com.saferoom.grpc.SafeRoomProto.FriendInfo>> task = 
            new javafx.concurrent.Task<>() {
                @Override
                protected java.util.List<com.saferoom.grpc.SafeRoomProto.FriendInfo> call() throws Exception {
                    com.saferoom.grpc.SafeRoomProto.FriendsListResponse response = 
                        com.saferoom.client.ClientMenu.getFriendsList(username);
                    
                    if (response != null && response.getSuccess()) {
                        return response.getFriendsList();
                    }
                    return java.util.Collections.emptyList();
                }
            };
        
        task.setOnSucceeded(event -> {
            java.util.List<com.saferoom.grpc.SafeRoomProto.FriendInfo> friends = task.getValue();
            
            javafx.application.Platform.runLater(() -> {
                for (com.saferoom.grpc.SafeRoomProto.FriendInfo friend : friends) {
                    String friendUsername = friend.getUsername();
                    String status = friend.getIsOnline() ? "Online" : "Offline";
                    String lastMessage = "Click to start conversation";
                    String time = ""; // Empty for now
                    
                    // Add friend as contact
                    addOrUpdateContact(friendUsername, friendUsername, status, lastMessage, time, 0, false);
                }
                
                System.out.printf("[ContactService] ✅ Loaded %d friends as contacts%n", friends.size());
                
                // 🚀 AUTO-LOAD: Preload message history for ALL conversations at startup
                preloadAllConversationHistories(friends);
            });
        });
        
        task.setOnFailed(event -> {
            System.err.println("[ContactService] ❌ Failed to load friends: " + task.getException().getMessage());
        });
        
        // Run task in background thread
        new Thread(task).start();
    }
    
    /**
     * Add or update a contact
     */
    public void addOrUpdateContact(String id, String name, String status, String lastMessage, 
                                  String time, int unreadCount, boolean isGroup) {
        Contact contact = new Contact(id, name, status, lastMessage, time, unreadCount, isGroup);
        
        // Update internal storage
        contacts.put(id, contact);
        unreadCounts.put(id, unreadCount);
        lastMessages.put(id, lastMessage);
        lastMessageTimes.put(id, time);
        
        // Update observable list
        updateObservableList();
        
        System.out.printf("[ContactService] 📝 Updated contact: %s (unread: %d)%n", name, unreadCount);
    }
    
    /**
     * Add a new contact (for P2P connections)
     */
    public void addNewContact(String username) {
        if (!contacts.containsKey(username)) {
            String currentTime = getCurrentTimeString();
            addOrUpdateContact(username, username, "Online", "Starting conversation...", currentTime, 0, false);
            System.out.printf("[ContactService] ➕ Added new contact: %s%n", username);
        }
    }
    
    /**
     * Update last message for a contact
     */
    public void updateLastMessage(String contactId, String message, boolean isFromMe) {
        Contact existingContact = contacts.get(contactId);
        if (existingContact != null) {
            String currentTime = getCurrentTimeString();
            int currentUnread = unreadCounts.getOrDefault(contactId, 0);
            
            // If message is not from me and chat is not active, increment unread
            if (!isFromMe && !contactId.equals(activeChat)) {
                currentUnread++;
            }
            
            // Update contact with new last message
            addOrUpdateContact(contactId, existingContact.getName(), existingContact.getStatus(), 
                             message, currentTime, currentUnread, existingContact.isGroup());
            
            System.out.printf("[ContactService] 💬 Updated last message for %s: \"%s\" (unread: %d)%n", 
                contactId, message, currentUnread);
        }
    }
    
    /**
     * Mark all messages as read for a contact
     */
    public void markAsRead(String contactId) {
        Contact existingContact = contacts.get(contactId);
        if (existingContact != null && existingContact.getUnreadCount() > 0) {
            // Update contact with 0 unread count
            addOrUpdateContact(contactId, existingContact.getName(), existingContact.getStatus(), 
                             existingContact.getLastMessage(), existingContact.getTime(), 0, existingContact.isGroup());
            
            System.out.printf("[ContactService] ✅ Marked as read: %s%n", contactId);
        }
    }
    
    /**
     * Set active chat (for read/unread logic)
     */
    public void setActiveChat(String contactId) {
        this.activeChat = contactId;
        if (contactId != null) {
            markAsRead(contactId);
            System.out.printf("[ContactService] 👁️ Set active chat: %s%n", contactId);
        }
    }
    
    /**
     * Clear active chat
     */
    public void clearActiveChat() {
        this.activeChat = null;
        System.out.println("[ContactService] 👁️ Cleared active chat");
    }
    
    /**
     * Get contact by ID
     */
    public Contact getContact(String contactId) {
        return contacts.get(contactId);
    }
    
    /**
     * Get observable contact list for UI
     */
    public ObservableList<Contact> getContactList() {
        return contactList;
    }
    
    /**
     * Update observable list from internal storage
     */
    private void updateObservableList() {
        // Clear and rebuild list (maintaining order by last message time)
        contactList.clear();
        
        // Sort contacts by last message time (most recent first)
        contacts.values().stream()
            .sorted((c1, c2) -> {
                // Simple time comparison (in real app, use proper timestamp)
                String time1 = c1.getTime();
                String time2 = c2.getTime();
                
                // For now, just maintain insertion order
                // TODO: Implement proper timestamp sorting
                return 0;
            })
            .forEach(contactList::add);
    }
    
    /**
     * Get current time as string
     */
    private String getCurrentTimeString() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }
    
    /**
     * Get total unread message count
     */
    public int getTotalUnreadCount() {
        return unreadCounts.values().stream().mapToInt(Integer::intValue).sum();
    }
    
    /**
     * Check if contact exists
     */
    public boolean hasContact(String contactId) {
        return contacts.containsKey(contactId);
    }
    
    /**
     * 🚀 Preload message history for ALL conversations at app startup
     * This ensures messages are available immediately when user opens a chat
     */
    private void preloadAllConversationHistories(java.util.List<com.saferoom.grpc.SafeRoomProto.FriendInfo> friends) {
        System.out.println("[ContactService] 🚀 Preloading message history for all conversations...");
        
        ChatService chatService = ChatService.getInstance();
        
        for (com.saferoom.grpc.SafeRoomProto.FriendInfo friend : friends) {
            String friendUsername = friend.getUsername();
            
            // Load history asynchronously for each friend
            chatService.loadConversationHistory(friendUsername)
                .thenAccept(count -> {
                    if (count > 0) {
                        System.out.printf("[ContactService] 📂 Preloaded %d messages for: %s%n", count, friendUsername);
                        
                        // Update last message in contact list from loaded history
                        javafx.application.Platform.runLater(() -> {
                            updateLastMessageFromHistory(friendUsername);
                        });
                    }
                })
                .exceptionally(error -> {
                    System.err.printf("[ContactService] ❌ Failed to preload history for %s: %s%n", 
                        friendUsername, error.getMessage());
                    return null;
                });
        }
    }
    
    /**
     * Update contact's last message from loaded chat history
     */
    private void updateLastMessageFromHistory(String contactId) {
        ChatService chatService = ChatService.getInstance();
        javafx.collections.ObservableList<com.saferoom.gui.model.Message> messages = 
            chatService.getMessagesForChannel(contactId);
        
        if (messages != null && !messages.isEmpty()) {
            // Get the last message
            com.saferoom.gui.model.Message lastMsg = messages.get(messages.size() - 1);
            String lastMessageText = lastMsg.getText();
            
            // Truncate if too long
            if (lastMessageText != null && lastMessageText.length() > 30) {
                lastMessageText = lastMessageText.substring(0, 30) + "...";
            }
            
            // Update contact without incrementing unread (this is history)
            Contact existingContact = contacts.get(contactId);
            if (existingContact != null) {
                addOrUpdateContact(contactId, existingContact.getName(), existingContact.getStatus(), 
                    lastMessageText, getCurrentTimeString(), existingContact.getUnreadCount(), 
                    existingContact.isGroup());
                    
                System.out.printf("[ContactService] 💬 Updated last message for %s from history%n", contactId);
            }
        }
    }
}
