package com.saferoom.chat;

import com.saferoom.gui.model.Message;
import com.saferoom.storage.LocalMessageRepository;
import com.saferoom.storage.SqlCipherHelper;
import javafx.collections.ObservableList;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Persistent Chat Loader
 * 
 * Loads message history from disk into RAM at startup
 * 
 * Architecture Flow:
 * 1. User logs in
 * 2. Database is unlocked with user's password
 * 3. For each active conversation:
 *    - Load messages from SQLite
 *    - Hydrate RAM ObservableList
 *    - UI automatically updates via bindings
 * 
 * This class is the bridge that loads disk → RAM
 */
public class PersistentChatLoader {
    
    private static final Logger LOGGER = Logger.getLogger(PersistentChatLoader.class.getName());
    private final LocalMessageRepository repository;
    
    public PersistentChatLoader(LocalMessageRepository repository) {
        this.repository = repository;
    }
    
    /**
     * Load message history for a single conversation
     * 
     * @param username Remote username (conversation partner)
     * @param currentUsername Current user's username
     * @param messagesObservableList ObservableList to populate
     * @return CompletableFuture<Integer> Number of messages loaded
     */
    public CompletableFuture<Integer> loadConversationHistory(
            String username, 
            String currentUsername,
            ObservableList<Message> messagesObservableList) {
        
        return CompletableFuture.supplyAsync(() -> {
            // Skip if already loaded (prevent duplicate loading)
            if (!messagesObservableList.isEmpty()) {
                LOGGER.info("History already loaded for: " + username + " (skipping)");
                return messagesObservableList.size();
            }
            
            String conversationId = SqlCipherHelper.generateConversationId(currentUsername, username);
            
            LOGGER.info("Loading history for conversation: " + username);
            
            // Load messages from database
            List<Message> messages = repository.loadMessages(conversationId);
            
            // Populate ObservableList on JavaFX thread
            javafx.application.Platform.runLater(() -> {
                // Double-check to prevent race conditions
                if (messagesObservableList.isEmpty() && !messages.isEmpty()) {
                    messagesObservableList.addAll(messages);
                    LOGGER.fine("Loaded " + messages.size() + " messages into RAM for: " + username);
                }
            });
            
            return messages.size();
        });
    }
    
    /**
     * Load message history for multiple conversations (bulk)
     * Used during startup to load all active chats
     * 
     * @param channelMessages Map of channelId → ObservableList<Message>
     * @param currentUsername Current user's username
     * @return CompletableFuture<Integer> Total messages loaded
     */
    public CompletableFuture<Integer> loadAllConversations(
            Map<String, ObservableList<Message>> channelMessages,
            String currentUsername) {
        
        return CompletableFuture.supplyAsync(() -> {
            int totalLoaded = 0;
            
            for (Map.Entry<String, ObservableList<Message>> entry : channelMessages.entrySet()) {
                String remoteUsername = entry.getKey();
                ObservableList<Message> messagesList = entry.getValue();
                
                // Skip if already loaded
                if (!messagesList.isEmpty()) {
                    totalLoaded += messagesList.size();
                    continue;
                }
                
                String conversationId = SqlCipherHelper.generateConversationId(
                    currentUsername, remoteUsername);
                
                List<Message> messages = repository.loadMessages(conversationId);
                
                final int count = messages.size();
                javafx.application.Platform.runLater(() -> {
                    if (messagesList.isEmpty() && !messages.isEmpty()) {
                        messagesList.addAll(messages);
                    }
                });
                
                totalLoaded += count;
            }
            
            LOGGER.info("Total messages loaded into RAM: " + totalLoaded);
            return totalLoaded;
        });
    }
    
    /**
     * Load messages with pagination (lazy loading)
     * For long conversations, load in chunks
     * 
     * @param username Remote username
     * @param currentUsername Current user's username
     * @param limit Number of messages to load
     * @param offset Offset for pagination
     * @return CompletableFuture<List<Message>> Loaded messages
     */
    public CompletableFuture<List<Message>> loadMessagesPaginated(
            String username,
            String currentUsername,
            int limit,
            int offset) {
        
        String conversationId = SqlCipherHelper.generateConversationId(currentUsername, username);
        return repository.loadMessagesPaginatedAsync(conversationId, limit, offset);
    }
    
    /**
     * Load only recent N messages (for quick preview)
     * 
     * @param username Remote username
     * @param currentUsername Current user's username
     * @param count Number of recent messages
     * @return CompletableFuture<List<Message>> Recent messages
     */
    public CompletableFuture<List<Message>> loadRecentMessages(
            String username,
            String currentUsername,
            int count) {
        
        return loadMessagesPaginated(username, currentUsername, count, 0);
    }
}

