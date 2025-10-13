package com.saferoom.natghost;

import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

/**
 * Reliable Message Receiver - QUIC-inspired protocol
 * 
 * Features:
 * - Out-of-order chunk reassembly
 * - Selective ACK generation with bitmap
 * - Fast NACK for missing chunks
 * - CRC32 integrity validation
 * - Automatic duplicate detection
 * 
 * State Machine:
 *   RECEIVING → [SENDING_NACK] → ASSEMBLING → COMPLETE
 */
public class ReliableMessageReceiver {
    
    // Constants
    private static final int ACK_DELAY_MS = 50;      // Delay before sending ACK (allow more chunks to arrive)
    private static final int NACK_THRESHOLD_MS = 200; // Send NACK if gaps detected after this delay
    private static final int MESSAGE_TIMEOUT_MS = 30000; // 30s timeout for incomplete messages
    
    // Receiver state
    private final String receiverUsername;
    private final DatagramChannel channel;
    private final ScheduledExecutorService scheduler;
    
    // Active messages being received
    private final ConcurrentHashMap<Long, ReceiveState> activeMessages = new ConcurrentHashMap<>();
    
    // Callback for completed messages
    private final MessageCompletionCallback callback;
    
    /**
     * Callback interface for completed messages
     */
    @FunctionalInterface
    public interface MessageCompletionCallback {
        void onMessageComplete(String sender, long messageId, byte[] message);
    }
    
    /**
     * Tracks state of a message being received
     */
    private static class ReceiveState {
        final long messageId;
        final String sender;
        final int totalChunks;
        final InetSocketAddress senderAddress;
        
        final Map<Integer, ChunkData> receivedChunks; // chunkId → chunk data
        final BitSet receivedBitmap;
        
        long firstChunkTime;
        long lastChunkTime;
        boolean finReceived;
        
        ReceiveState(long messageId, String sender, int totalChunks, InetSocketAddress senderAddress) {
            this.messageId = messageId;
            this.sender = sender;
            this.totalChunks = totalChunks;
            this.senderAddress = senderAddress;
            
            this.receivedChunks = new ConcurrentHashMap<>();
            this.receivedBitmap = new BitSet(totalChunks);
            
            this.firstChunkTime = System.currentTimeMillis();
            this.lastChunkTime = this.firstChunkTime;
            this.finReceived = false;
        }
        
        boolean isComplete() {
            return receivedChunks.size() == totalChunks;
        }
        
        int getMissingChunkCount() {
            return totalChunks - receivedChunks.size();
        }
        
        /**
         * Get highest consecutive chunk received (for ACK)
         */
        int getHighestConsecutive() {
            for (int i = 0; i < totalChunks; i++) {
                if (!receivedChunks.containsKey(i)) {
                    return i - 1;
                }
            }
            return totalChunks - 1;
        }
        
        /**
         * Generate 64-bit bitmap for received chunks
         */
        long getBitmap() {
            long bitmap = 0;
            for (int i = 0; i < Math.min(64, totalChunks); i++) {
                if (receivedChunks.containsKey(i)) {
                    bitmap |= (1L << i);
                }
            }
            return bitmap;
        }
        
        /**
         * Get missing chunk IDs for NACK
         */
        int[] getMissingChunks() {
            List<Integer> missing = new ArrayList<>();
            for (int i = 0; i < totalChunks; i++) {
                if (!receivedChunks.containsKey(i)) {
                    missing.add(i);
                }
            }
            return missing.stream().mapToInt(Integer::intValue).toArray();
        }
        
        /**
         * Reassemble full message from chunks
         */
        byte[] reassemble() {
            // Calculate total size
            int totalSize = receivedChunks.values().stream()
                .mapToInt(c -> c.data.length)
                .sum();
            
            byte[] fullMessage = new byte[totalSize];
            
            // Copy chunks in order
            int offset = 0;
            for (int i = 0; i < totalChunks; i++) {
                ChunkData chunk = receivedChunks.get(i);
                if (chunk == null) {
                    throw new IllegalStateException("Missing chunk " + i + " during reassembly!");
                }
                System.arraycopy(chunk.data, 0, fullMessage, offset, chunk.data.length);
                offset += chunk.data.length;
            }
            
            return fullMessage;
        }
    }
    
    /**
     * Chunk data with metadata
     */
    private static class ChunkData {
        final int chunkId;
        final byte[] data;
        final int expectedCRC;
        final long receiveTime;
        
        ChunkData(int chunkId, byte[] data, int expectedCRC) {
            this.chunkId = chunkId;
            this.data = data;
            this.expectedCRC = expectedCRC;
            this.receiveTime = System.nanoTime();
        }
        
        /**
         * Validate CRC32 integrity
         */
        boolean validateCRC() {
            CRC32 crc = new CRC32();
            crc.update(data);
            int calculatedCRC = (int) crc.getValue();
            return calculatedCRC == expectedCRC;
        }
    }
    
    /**
     * Constructor
     */
    public ReliableMessageReceiver(String receiverUsername, DatagramChannel channel, 
                                   MessageCompletionCallback callback) {
        this.receiverUsername = receiverUsername;
        this.channel = channel;
        this.callback = callback;
        
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "ReliableMsgReceiver-" + receiverUsername);
            t.setDaemon(true);
            return t;
        });
        
        // Start periodic cleanup of stale messages
        startCleanupTimer();
    }
    
    /**
     * Handle incoming data chunk
     */
    public void handleDataChunk(Object[] parsed, InetSocketAddress senderAddress) {
        try {
            // Extract fields from parsed packet
            String sender = (String) parsed[2];
            long messageId = (long) parsed[4];
            int chunkId = (int) parsed[5];
            int totalChunks = (int) parsed[6];
            int chunkSize = (int) parsed[7];
            long timestamp = (long) parsed[8];
            int expectedCRC = (int) parsed[9];
            byte[] payload = (byte[]) parsed[10];
            
            // Get or create receive state
            ReceiveState state = activeMessages.computeIfAbsent(messageId, 
                id -> new ReceiveState(messageId, sender, totalChunks, senderAddress));
            
            // Check for duplicate
            if (state.receivedChunks.containsKey(chunkId)) {
                System.out.printf("[RMSG-RECV] 🔄 Duplicate chunk %d/%d (msgId=%d) - ignoring%n",
                    chunkId, totalChunks - 1, messageId);
                return;
            }
            
            // Validate CRC
            ChunkData chunkData = new ChunkData(chunkId, payload, expectedCRC);
            if (!chunkData.validateCRC()) {
                System.err.printf("[RMSG-RECV] ❌ CRC mismatch for chunk %d (msgId=%d) - discarding%n",
                    chunkId, messageId);
                // Send NACK for this chunk
                scheduleNACK(state, 0);
                return;
            }
            
            // Store chunk
            state.receivedChunks.put(chunkId, chunkData);
            state.receivedBitmap.set(chunkId);
            state.lastChunkTime = System.currentTimeMillis();
            
            System.out.printf("[RMSG-RECV] 📥 Chunk %d/%d received (msgId=%d, size=%d, progress=%d/%d)%n",
                chunkId, totalChunks - 1, messageId, chunkSize, 
                state.receivedChunks.size(), totalChunks);
            
            // Check completion
            if (state.isComplete()) {
                completeMessage(state);
            } else {
                // Schedule ACK (delayed to batch multiple chunks)
                scheduleACK(state);
                
                // Schedule NACK if gaps detected
                if (hasSignificantGaps(state)) {
                    scheduleNACK(state, NACK_THRESHOLD_MS);
                }
            }
            
        } catch (Exception e) {
            System.err.println("[RMSG-RECV] ❌ Error handling chunk: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Handle incoming FIN packet
     */
    public void handleFIN(long messageId) {
        ReceiveState state = activeMessages.get(messageId);
        if (state == null) {
            return; // Unknown message (probably already completed and removed)
        }
        
        // Skip if already processed (FIN already received)
        if (state.finReceived) {
            // Just send ACK again (idempotent operation)
            try {
                sendACK(state);
            } catch (Exception e) {
                // Ignore errors on duplicate FIN handling
            }
            return;
        }
        
        System.out.printf("[RMSG-RECV] 🏁 FIN received: msgId=%d%n", messageId);
        state.finReceived = true;
        
        // Check if we can complete now
        if (state.isComplete()) {
            completeMessage(state);
        } else {
            // Send NACK for missing chunks
            System.out.printf("[RMSG-RECV] ⚠️  FIN received but %d chunks missing - sending NACK%n",
                state.getMissingChunkCount());
            scheduleNACK(state, 0);
        }
    }
    
    /**
     * Check if message has significant gaps (heuristic for early NACK)
     */
    private boolean hasSignificantGaps(ReceiveState state) {
        int received = state.receivedChunks.size();
        int total = state.totalChunks;
        
        // If we've received >50% but still have gaps, consider it significant
        if (received > total / 2) {
            int highestConsecutive = state.getHighestConsecutive();
            return highestConsecutive < received - 2; // Gap of 2+ chunks
        }
        
        return false;
    }
    
    /**
     * Schedule ACK sending (delayed to batch)
     */
    private void scheduleACK(ReceiveState state) {
        scheduler.schedule(() -> {
            try {
                sendACK(state);
            } catch (Exception e) {
                System.err.println("[RMSG-RECV] ❌ Failed to send ACK: " + e.getMessage());
            }
        }, ACK_DELAY_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Send ACK packet
     */
    private void sendACK(ReceiveState state) {
        try {
            int highestConsecutive = state.getHighestConsecutive();
            long bitmap = state.getBitmap();
            
            ByteBuffer ack = LLS.New_ReliableMessage_ACK(
                receiverUsername,
                state.sender,
                state.messageId,
                highestConsecutive,
                bitmap
            );
            
            channel.send(ack, state.senderAddress);
            
            System.out.printf("[RMSG-RECV] ✅ ACK sent: msgId=%d, consecutive=%d, received=%d/%d, bitmap=%s%n",
                state.messageId, highestConsecutive, state.receivedChunks.size(), state.totalChunks,
                String.format("%16s", Long.toBinaryString(bitmap)).replace(' ', '0'));
            
        } catch (Exception e) {
            System.err.println("[RMSG-RECV] ❌ Failed to send ACK: " + e.getMessage());
        }
    }
    
    /**
     * Schedule NACK sending
     */
    private void scheduleNACK(ReceiveState state, long delayMs) {
        scheduler.schedule(() -> {
            try {
                // Only send NACK if still incomplete
                if (!state.isComplete()) {
                    sendNACK(state);
                }
            } catch (Exception e) {
                System.err.println("[RMSG-RECV] ❌ Failed to send NACK: " + e.getMessage());
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Send NACK packet
     */
    private void sendNACK(ReceiveState state) {
        try {
            int[] missingChunks = state.getMissingChunks();
            
            if (missingChunks.length == 0) {
                return; // Nothing missing
            }
            
            ByteBuffer nack = LLS.New_ReliableMessage_NACK(
                receiverUsername,
                state.sender,
                state.messageId,
                missingChunks
            );
            
            channel.send(nack, state.senderAddress);
            
            System.out.printf("[RMSG-RECV] ⚠️  NACK sent: msgId=%d, missing=%d chunks: %s%n",
                state.messageId, missingChunks.length, Arrays.toString(missingChunks));
            
        } catch (Exception e) {
            System.err.println("[RMSG-RECV] ❌ Failed to send NACK: " + e.getMessage());
        }
    }
    
    /**
     * Complete message reception
     */
    private void completeMessage(ReceiveState state) {
        try {
            // Reassemble full message
            byte[] fullMessage = state.reassemble();
            
            System.out.printf("[RMSG-RECV] ✅ Message complete: msgId=%d, sender=%s, size=%d bytes, chunks=%d%n",
                state.messageId, state.sender, fullMessage.length, state.totalChunks);
            
            // Send final ACK
            sendACK(state);
            
            // Notify callback
            if (callback != null) {
                callback.onMessageComplete(state.sender, state.messageId, fullMessage);
            }
            
            // Cleanup
            scheduler.schedule(() -> activeMessages.remove(state.messageId), 2, TimeUnit.SECONDS);
            
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Scheduler already shut down, just cleanup now
            activeMessages.remove(state.messageId);
        } catch (Exception e) {
            System.err.printf("[RMSG-RECV] ❌ Failed to complete message %d: %s%n",
                state.messageId, e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Periodic cleanup of stale messages
     */
    private void startCleanupTimer() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                cleanupStaleMessages();
            } catch (Exception e) {
                System.err.println("[RMSG-RECV] ❌ Cleanup timer error: " + e.getMessage());
            }
        }, MESSAGE_TIMEOUT_MS, MESSAGE_TIMEOUT_MS / 2, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Remove stale incomplete messages
     */
    private void cleanupStaleMessages() {
        long now = System.currentTimeMillis();
        
        activeMessages.entrySet().removeIf(entry -> {
            ReceiveState state = entry.getValue();
            long age = now - state.lastChunkTime;
            
            if (age > MESSAGE_TIMEOUT_MS && !state.isComplete()) {
                System.err.printf("[RMSG-RECV] 🗑️  Removing stale message: msgId=%d, age=%dms, received=%d/%d%n",
                    state.messageId, age, state.receivedChunks.size(), state.totalChunks);
                return true;
            }
            return false;
        });
    }
    
    /**
     * Shutdown receiver
     */
    public void shutdown() {
        System.out.println("[RMSG-RECV] 🛑 Shutting down receiver...");
        scheduler.shutdownNow();
        activeMessages.clear();
    }
    
    /**
     * Get receiver statistics
     */
    public String getStats() {
        int totalReceived = 0;
        int totalExpected = 0;
        
        for (ReceiveState state : activeMessages.values()) {
            totalReceived += state.receivedChunks.size();
            totalExpected += state.totalChunks;
        }
        
        return String.format(
            "Active messages: %d | Chunks: %d/%d (%.1f%%)",
            activeMessages.size(), totalReceived, totalExpected,
            totalExpected > 0 ? (100.0 * totalReceived / totalExpected) : 0.0
        );
    }
}
