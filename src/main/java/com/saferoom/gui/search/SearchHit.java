package com.saferoom.gui.search;

/**
 * Search Hit Model
 * 
 * Represents a single search result from FTS5 query.
 * Used by MessageSearchPanel to display results.
 */
public class SearchHit {
    
    private final String messageId;
    private final long timestamp;
    private final String previewText;      // Raw text (for display)
    private final String highlightedText;  // Text with <b> tags for matched words
    private final boolean isOutgoing;
    
    public SearchHit(String messageId, long timestamp, String previewText, 
                     String highlightedText, boolean isOutgoing) {
        this.messageId = messageId;
        this.timestamp = timestamp;
        this.previewText = previewText;
        this.highlightedText = highlightedText;
        this.isOutgoing = isOutgoing;
    }
    
    public String getMessageId() {
        return messageId;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public String getPreviewText() {
        return previewText;
    }
    
    public String getHighlightedText() {
        return highlightedText;
    }
    
    public boolean isOutgoing() {
        return isOutgoing;
    }
    
    /**
     * Get formatted date string from timestamp
     */
    public String getFormattedDate() {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(timestamp);
        java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(
            instant, java.time.ZoneId.systemDefault());
        
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate messageDate = dateTime.toLocalDate();
        
        if (messageDate.equals(today)) {
            return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        } else if (messageDate.equals(today.minusDays(1))) {
            return "Yesterday " + dateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        } else {
            return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        }
    }
    
    /**
     * Get formatted time string
     */
    public String getFormattedTime() {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(timestamp);
        java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(
            instant, java.time.ZoneId.systemDefault());
        return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }
    
    @Override
    public String toString() {
        return "SearchHit{" +
                "messageId='" + messageId + '\'' +
                ", timestamp=" + timestamp +
                ", preview='" + previewText + '\'' +
                '}';
    }
}

