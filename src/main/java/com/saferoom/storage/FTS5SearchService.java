package com.saferoom.storage;

import com.saferoom.gui.search.SearchHit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Full-Text Search Service using SQLite FTS5
 * 
 * Features:
 * - Fast full-text search across all messages
 * - Ranked results (BM25 algorithm)
 * - Highlight support with snippets
 * - Conversation filtering
 * - WhatsApp-style search panel support
 * 
 * Usage:
 * - Search globally or within a conversation
 * - Get result positions for UI highlighting
 */
public class FTS5SearchService {
    
    private static final Logger LOGGER = Logger.getLogger(FTS5SearchService.class.getName());
    private LocalDatabase database;
    private Connection connection;
    
    /**
     * Constructor with LocalDatabase
     */
    public FTS5SearchService(LocalDatabase database) {
        this.database = database;
        this.connection = database.getConnection();
    }
    
    /**
     * Default constructor for lazy initialization
     */
    public FTS5SearchService() {
        // Will be initialized later via initialize()
    }
    
    /**
     * Initialize with a connection
     */
    public void initialize(Connection conn) {
        this.connection = conn;
    }
    
    /**
     * Get the active connection
     */
    private Connection getConnection() {
        if (connection != null) {
            return connection;
        }
        if (database != null) {
            return database.getConnection();
        }
        throw new IllegalStateException("FTS5SearchService not initialized");
    }
    
    /**
     * Search result item
     */
    public static class SearchResult {
        private final String messageId;
        private final String conversationId;
        private final String content;
        private final long timestamp;
        private final double rank;
        
        public SearchResult(String messageId, String conversationId, String content, 
                          long timestamp, double rank) {
            this.messageId = messageId;
            this.conversationId = conversationId;
            this.content = content;
            this.timestamp = timestamp;
            this.rank = rank;
        }
        
        public String getMessageId() { return messageId; }
        public String getConversationId() { return conversationId; }
        public String getContent() { return content; }
        public long getTimestamp() { return timestamp; }
        public double getRank() { return rank; }
    }
    
    /**
     * Search messages globally
     * 
     * @param query Search query (supports FTS5 syntax: "word1 AND word2", "phrase search", etc.)
     * @param limit Maximum results
     * @return List of search results, ranked by relevance
     */
    public List<SearchResult> searchGlobal(String query, int limit) {
        String sql = """
            SELECT 
                m.id,
                m.conversation_id,
                m.content,
                m.timestamp,
                fts.rank
            FROM messages_fts fts
            JOIN messages m ON m.rowid = fts.rowid
            WHERE messages_fts MATCH ?
            ORDER BY fts.rank
            LIMIT ?
            """;
        
        List<SearchResult> results = new ArrayList<>();
        
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, query);
            stmt.setInt(2, limit);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    SearchResult result = new SearchResult(
                        rs.getString("id"),
                        rs.getString("conversation_id"),
                        rs.getString("content"),
                        rs.getLong("timestamp"),
                        rs.getDouble("rank")
                    );
                    results.add(result);
                }
            }
            
            LOGGER.fine("Global search for '" + query + "' returned " + results.size() + " results");
            
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Search failed for query: " + query, e);
        }
        
        return results;
    }
    
    /**
     * Search messages within a specific conversation
     * 
     * @param query Search query
     * @param conversationId Conversation to search in
     * @param limit Maximum results
     * @return List of search results
     */
    public List<SearchResult> searchInConversation(String query, String conversationId, int limit) {
        String sql = """
            SELECT 
                m.id,
                m.conversation_id,
                m.content,
                m.timestamp,
                fts.rank
            FROM messages_fts fts
            JOIN messages m ON m.rowid = fts.rowid
            WHERE messages_fts MATCH ? 
            AND m.conversation_id = ?
            ORDER BY fts.rank
            LIMIT ?
            """;
        
        List<SearchResult> results = new ArrayList<>();
        
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, query);
            stmt.setString(2, conversationId);
            stmt.setInt(3, limit);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    SearchResult result = new SearchResult(
                        rs.getString("id"),
                        rs.getString("conversation_id"),
                        rs.getString("content"),
                        rs.getLong("timestamp"),
                        rs.getDouble("rank")
                    );
                    results.add(result);
                }
            }
            
            LOGGER.fine("Conversation search returned " + results.size() + " results");
            
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Conversation search failed", e);
        }
        
        return results;
    }
    
    /**
     * Get message IDs matching a query (for navigation)
     * Returns in chronological order for up/down navigation
     * 
     * @param query Search query
     * @param conversationId Conversation ID
     * @return List of message IDs in chronological order
     */
    public List<String> getMatchingMessageIds(String query, String conversationId) {
        String sql = """
            SELECT m.id
            FROM messages_fts fts
            JOIN messages m ON m.rowid = fts.rowid
            WHERE messages_fts MATCH ? 
            AND m.conversation_id = ?
            ORDER BY m.timestamp ASC
            """;
        
        List<String> messageIds = new ArrayList<>();
        
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, query);
            stmt.setString(2, conversationId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    messageIds.add(rs.getString("id"));
                }
            }
            
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to get matching message IDs", e);
        }
        
        return messageIds;
    }
    
    /**
     * Get snippet with highlighted matches
     * Uses FTS5 snippet() function
     * 
     * @param query Search query
     * @param messageId Message ID
     * @return Snippet with <mark> tags around matches, or null if not found
     */
    public String getHighlightedSnippet(String query, String messageId) {
        String sql = """
            SELECT snippet(messages_fts, 1, '<mark>', '</mark>', '...', 64) as snippet
            FROM messages_fts fts
            JOIN messages m ON m.rowid = fts.rowid
            WHERE messages_fts MATCH ? 
            AND m.id = ?
            """;
        
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, query);
            stmt.setString(2, messageId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("snippet");
                }
            }
            
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to get highlighted snippet", e);
        }
        
        return null;
    }
    
    /**
     * Count total matches for a query
     */
    public int countMatches(String query, String conversationId) {
        String sql;
        
        if (conversationId != null) {
            sql = """
                SELECT COUNT(*) as count
                FROM messages_fts fts
                JOIN messages m ON m.rowid = fts.rowid
                WHERE messages_fts MATCH ? 
                AND m.conversation_id = ?
                """;
        } else {
            sql = """
                SELECT COUNT(*) as count
                FROM messages_fts
                WHERE messages_fts MATCH ?
                """;
        }
        
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, query);
            if (conversationId != null) {
                stmt.setString(2, conversationId);
            }
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("count");
                }
            }
            
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to count matches", e);
        }
        
        return 0;
    }
    
    /**
     * Optimize FTS index (run periodically for performance)
     */
    public void optimizeIndex() {
        try (PreparedStatement stmt = getConnection().prepareStatement(
                "INSERT INTO messages_fts(messages_fts) VALUES('optimize')")) {
            stmt.execute();
            LOGGER.info("FTS5 index optimized");
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to optimize FTS index", e);
        }
    }
    
    /**
     * Search with snippets for WhatsApp-style search panel
     * Returns SearchHit objects with highlighted text
     * 
     * @param conversationId Conversation to search in
     * @param query Search query
     * @param currentUsername Current user (to determine outgoing status)
     * @return List of SearchHit results with snippets
     */
    public List<SearchHit> searchWithSnippets(String conversationId, String query, String currentUsername) {
        // Escape special FTS5 characters and prepare query
        String sanitizedQuery = sanitizeQuery(query);
        
        String sql = """
            SELECT 
                m.id,
                m.timestamp,
                m.content,
                m.sender_id,
                m.is_outgoing,
                snippet(messages_fts, 0, '<b>', '</b>', '...', 40) as snippet
            FROM messages_fts fts
            JOIN messages m ON m.rowid = fts.rowid
            WHERE messages_fts MATCH ? 
            AND m.conversation_id = ?
            ORDER BY m.timestamp DESC
            LIMIT 50
            """;
        
        List<SearchHit> results = new ArrayList<>();
        
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, sanitizedQuery);
            stmt.setString(2, conversationId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String messageId = rs.getString("id");
                    long timestamp = rs.getLong("timestamp");
                    String content = rs.getString("content");
                    String snippet = rs.getString("snippet");
                    boolean isOutgoing = rs.getInt("is_outgoing") == 1;
                    
                    // Use snippet if available, otherwise use content
                    String displayText = (snippet != null && !snippet.isEmpty()) ? snippet : content;
                    
                    // Decrypt content if needed (content might be encrypted)
                    String decryptedContent = decryptIfNeeded(content);
                    String decryptedSnippet = (snippet != null) ? snippet : decryptedContent;
                    
                    // If snippet doesn't have highlights, add them manually
                    if (!decryptedSnippet.contains("<b>")) {
                        decryptedSnippet = highlightManually(decryptedContent, query);
                    }
                    
                    SearchHit hit = new SearchHit(
                        messageId,
                        timestamp,
                        decryptedContent,
                        decryptedSnippet,
                        isOutgoing
                    );
                    results.add(hit);
                }
            }
            
            LOGGER.info("Search for '" + query + "' in " + conversationId + " returned " + results.size() + " results");
            
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Search with snippets failed", e);
            
            // Fallback: try simple LIKE search if FTS fails
            return searchWithLikeFallback(conversationId, query, currentUsername);
        }
        
        return results;
    }
    
    /**
     * Fallback search using LIKE when FTS5 fails
     */
    private List<SearchHit> searchWithLikeFallback(String conversationId, String query, String currentUsername) {
        String sql = """
            SELECT 
                id,
                timestamp,
                content,
                sender_id,
                is_outgoing
            FROM messages
            WHERE conversation_id = ?
            AND content LIKE ?
            ORDER BY timestamp DESC
            LIMIT 50
            """;
        
        List<SearchHit> results = new ArrayList<>();
        
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, conversationId);
            stmt.setString(2, "%" + query + "%");
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String messageId = rs.getString("id");
                    long timestamp = rs.getLong("timestamp");
                    String content = rs.getString("content");
                    boolean isOutgoing = rs.getInt("is_outgoing") == 1;
                    
                    String decryptedContent = decryptIfNeeded(content);
                    String highlighted = highlightManually(decryptedContent, query);
                    
                    SearchHit hit = new SearchHit(
                        messageId,
                        timestamp,
                        decryptedContent,
                        highlighted,
                        isOutgoing
                    );
                    results.add(hit);
                }
            }
            
            LOGGER.info("Fallback LIKE search returned " + results.size() + " results");
            
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Fallback search also failed", e);
        }
        
        return results;
    }
    
    /**
     * Sanitize query for FTS5 (escape special characters)
     */
    private String sanitizeQuery(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        
        // For simple searches, wrap in quotes for phrase matching
        // Remove special FTS5 operators for safety
        String sanitized = query.trim()
            .replace("\"", "")
            .replace("*", "")
            .replace("-", " ")
            .replace("OR", "or")
            .replace("AND", "and")
            .replace("NOT", "not");
        
        // Use prefix matching for partial word matches
        if (!sanitized.contains(" ")) {
            return "\"" + sanitized + "\"*";
        }
        
        return "\"" + sanitized + "\"";
    }
    
    /**
     * Decrypt content if it's encrypted
     */
    private String decryptIfNeeded(String content) {
        if (content == null) return "";
        
        // Check if content looks encrypted (hex string or base64)
        // For now, just return as-is since we store plain text
        // In production, integrate with SqlCipherHelper.decrypt()
        return content;
    }
    
    /**
     * Manually add highlight tags around matched text
     */
    private String highlightManually(String text, String query) {
        if (text == null || query == null || text.isEmpty() || query.isEmpty()) {
            return text;
        }
        
        // Case-insensitive replacement
        String lowerText = text.toLowerCase();
        String lowerQuery = query.toLowerCase().trim();
        
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        int index = lowerText.indexOf(lowerQuery);
        
        while (index >= 0) {
            // Add text before match
            result.append(text.substring(lastEnd, index));
            // Add highlighted match (preserve original case)
            result.append("<b>");
            result.append(text.substring(index, index + lowerQuery.length()));
            result.append("</b>");
            
            lastEnd = index + lowerQuery.length();
            index = lowerText.indexOf(lowerQuery, lastEnd);
        }
        
        // Add remaining text
        result.append(text.substring(lastEnd));
        
        return result.toString();
    }
}

