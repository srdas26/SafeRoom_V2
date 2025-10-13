package com.saferoom.natghost;

import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reliable Message Sender - QUIC-inspired protocol
 * 
 * Features:
 * - Automatic chunking (1131 bytes per chunk, MTU-safe)
 * - Selective ACK with 64-bit bitmap
 * - Hybrid retransmission (NACK-triggered + Timer-based)
 * - RTT estimation and adaptive RTO
 * - Concurrent message sending
 * 
 * State Machine:
 *   IDLE → SENDING → WAITING_ACK → [RETRANSMITTING] → COMPLETE/FAILED
 */
public class ReliableMessageSender {
    
    // Constants
    private static final int MAX_CHUNK_SIZE = 1131; // MTU 1200 - 69 header
    private static final int INITIAL_RTO_MS = 1000; // Initial retransmission timeout
    private static final int MAX_RETRIES = 5;       // Max retransmission attempts per chunk
    private static final int MAX_CONCURRENT_CHUNKS = 16; // Window size
    
    // Message ID generator
    private static final AtomicLong messageIdGenerator = new AtomicLong(System.currentTimeMillis());
    
    // Sender state
    private final String senderUsername;
    private final DatagramChannel channel;
    private final ScheduledExecutorService scheduler;
    
    // Active messages being sent
    private final ConcurrentHashMap<Long, MessageState> activeMessages = new ConcurrentHashMap<>();
    
    // RTT estimation (simple exponential moving average)
    private volatile long smoothedRTT = INITIAL_RTO_MS;
    private volatile long rttVariance = INITIAL_RTO_MS / 2;
    
    /**
     * Message state machine
     */
    enum State {
        IDLE,           // Message not started
        SENDING,        // Sending initial chunks
        WAITING_ACK,    // Waiting for ACK/NACK
        RETRANSMITTING, // Resending missing chunks
        COMPLETE,       // All chunks ACKed
        FAILED          // Max retries exceeded
    }
    
    /**
     * Tracks state of a single message being sent
     */
    private static class MessageState {
        final long messageId;
        final String receiver;
        final byte[] fullMessage;
        final List<ChunkInfo> chunks;
        final BitSet ackedChunks;
        final BitSet inFlightChunks;
        final InetSocketAddress targetAddress; // Store target for retransmission
        
        State state;
        int totalRetries;
        long lastActivityTime;
        CompletableFuture<Boolean> completionFuture;
        
        MessageState(long messageId, String receiver, byte[] message, InetSocketAddress target) {
            this.messageId = messageId;
            this.receiver = receiver;
            this.fullMessage = message;
            this.targetAddress = target;
            
            // Calculate chunks
            int totalChunks = (message.length + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE;
            this.chunks = new ArrayList<>(totalChunks);
            
            for (int i = 0; i < totalChunks; i++) {
                int offset = i * MAX_CHUNK_SIZE;
                int length = Math.min(MAX_CHUNK_SIZE, message.length - offset);
                chunks.add(new ChunkInfo(i, offset, length));
            }
            
            this.ackedChunks = new BitSet(totalChunks);
            this.inFlightChunks = new BitSet(totalChunks);
            this.state = State.IDLE;
            this.totalRetries = 0;
            this.lastActivityTime = System.currentTimeMillis();
            this.completionFuture = new CompletableFuture<>();
        }
        
        boolean isComplete() {
            return ackedChunks.cardinality() == chunks.size();
        }
        
        int getMissingChunkCount() {
            return chunks.size() - ackedChunks.cardinality();
        }
    }
    
    /**
     * Chunk metadata
     */
    private static class ChunkInfo {
        final int chunkId;
        final int offset;
        final int length;
        int retries;
        long lastSentTime;
        
        ChunkInfo(int chunkId, int offset, int length) {
            this.chunkId = chunkId;
            this.offset = offset;
            this.length = length;
            this.retries = 0;
            this.lastSentTime = 0;
        }
    }
    
    /**
     * Constructor
     */
    public ReliableMessageSender(String senderUsername, DatagramChannel channel) {
        this.senderUsername = senderUsername;
        this.channel = channel;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "ReliableMsgSender-" + senderUsername);
            t.setDaemon(true);
            return t;
        });
        
        // Start periodic retry checker
        startRetryTimer();
    }
    
    /**
     * Send message reliably
     * @return CompletableFuture that completes when all chunks are ACKed
     */
    public CompletableFuture<Boolean> sendMessage(String receiver, byte[] message, InetSocketAddress target) {
        long messageId = messageIdGenerator.incrementAndGet();
        MessageState state = new MessageState(messageId, receiver, message, target);
        activeMessages.put(messageId, state);
        
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("[RMSG-SEND] 📤 Starting reliable send:%n");
        System.out.printf("  - Message ID: %d%n", messageId);
        System.out.printf("  - Receiver: %s%n", receiver);
        System.out.printf("  - Target Address: %s%n", target);
        System.out.printf("  - Message Size: %d bytes%n", message.length);
        System.out.printf("  - Total Chunks: %d (chunk size: 1131 bytes max)%n", state.chunks.size());
        System.out.printf("  - Window Size: %d concurrent chunks%n", MAX_CONCURRENT_CHUNKS);
        
        // Log chunk details
        for (int i = 0; i < state.chunks.size(); i++) {
            ChunkInfo chunk = state.chunks.get(i);
            System.out.printf("    [Chunk %d] offset=%d, length=%d bytes%n", 
                chunk.chunkId, chunk.offset, chunk.length);
        }
        System.out.println("═══════════════════════════════════════════════════════════════");
        
        // Start sending chunks
        state.state = State.SENDING;
        sendInitialChunks(state, target);
        
        return state.completionFuture;
    }
    
    /**
     * Send initial wave of chunks (respecting window size)
     */
    private void sendInitialChunks(MessageState state, InetSocketAddress target) {
        System.out.printf("[RMSG-SEND] 🚀 Starting initial chunk send (window: %d)%n", MAX_CONCURRENT_CHUNKS);
        
        int sent = 0;
        for (ChunkInfo chunk : state.chunks) {
            if (sent >= MAX_CONCURRENT_CHUNKS) {
                System.out.printf("[RMSG-SEND] ⏸️  Paused at %d chunks (window limit reached)%n", sent);
                break; // Respect window size
            }
            
            System.out.printf("[RMSG-SEND] 📨 Sending chunk %d/%d (%d bytes)...%n", 
                chunk.chunkId, state.chunks.size() - 1, chunk.length);
            sendChunk(state, chunk, target);
            state.inFlightChunks.set(chunk.chunkId);
            sent++;
        }
        
        state.state = State.WAITING_ACK;
        state.lastActivityTime = System.currentTimeMillis();
        
        System.out.printf("[RMSG-SEND] ✅ Initial send complete: %d chunks sent%n", sent);
        System.out.println("───────────────────────────────────────────────────────────────");
    }
    
    /**
     * Send a single chunk
     */
    private void sendChunk(MessageState state, ChunkInfo chunk, InetSocketAddress target) {
        try {
            ByteBuffer packet = LLS.New_ReliableMessage_Chunk(
                senderUsername,
                state.receiver,
                state.messageId,
                chunk.chunkId,
                state.chunks.size(),
                state.fullMessage,
                chunk.offset,
                chunk.length
            );
            
            int bytesSent = channel.send(packet, target);
            
            chunk.lastSentTime = System.nanoTime();
            chunk.retries++;
            
            // Debug log
            System.out.printf("[RMSG-SEND]   ✉️  Chunk %d: %d bytes → UDP packet %d bytes → sent %d bytes to %s%n", 
                chunk.chunkId, chunk.length, packet.limit(), bytesSent, target);
            
        } catch (Exception e) {
            System.err.printf("[RMSG-SEND] ❌ Failed to send chunk %d: %s%n", 
                chunk.chunkId, e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Handle incoming ACK packet
     */
    public void handleACK(long messageId, int highestConsecutive, long chunkBitmap) {
        MessageState state = activeMessages.get(messageId);
        if (state == null) {
            return; // Unknown message (probably already completed)
        }
        
        // Update RTT based on timestamp
        updateRTT(state);
        
        // Mark chunks as ACKed based on bitmap
        int newlyAcked = 0;
        for (int i = 0; i <= highestConsecutive && i < state.chunks.size(); i++) {
            if (!state.ackedChunks.get(i)) {
                state.ackedChunks.set(i);
                state.inFlightChunks.clear(i);
                newlyAcked++;
            }
        }
        
        // Check bitmap for chunks beyond highestConsecutive
        for (int i = highestConsecutive + 1; i < Math.min(64, state.chunks.size()); i++) {
            if ((chunkBitmap & (1L << i)) != 0) {
                if (!state.ackedChunks.get(i)) {
                    state.ackedChunks.set(i);
                    state.inFlightChunks.clear(i);
                    newlyAcked++;
                }
            }
        }
        
        System.out.printf("[RMSG-SEND] ✅ ACK received: msgId=%d, newlyAcked=%d, total=%d/%d, bitmap=%s%n",
            messageId, newlyAcked, state.ackedChunks.cardinality(), state.chunks.size(),
            String.format("%64s", Long.toBinaryString(chunkBitmap)).replace(' ', '0'));
        
        // Check completion
        if (state.isComplete()) {
            completeMessage(state, true);
        } else {
            state.lastActivityTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Handle incoming NACK packet (fast retransmission)
     */
    public void handleNACK(long messageId, int[] missingChunks, InetSocketAddress target) {
        MessageState state = activeMessages.get(messageId);
        if (state == null) {
            return;
        }
        
        System.out.printf("[RMSG-SEND] ⚠️  NACK received: msgId=%d, missing=%d chunks%n",
            messageId, missingChunks.length);
        
        state.state = State.RETRANSMITTING;
        
        // Immediately resend missing chunks
        for (int chunkId : missingChunks) {
            if (chunkId < state.chunks.size()) {
                ChunkInfo chunk = state.chunks.get(chunkId);
                System.out.printf("  ↻ Retransmitting chunk %d (NACK-triggered)%n", chunkId);
                sendChunk(state, chunk, target);
                state.inFlightChunks.set(chunkId);
            }
        }
        
        state.state = State.WAITING_ACK;
        state.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * Periodic retry timer (fallback for lost ACKs)
     */
    private void startRetryTimer() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkTimeouts();
            } catch (Exception e) {
                System.err.println("[RMSG-SEND] ❌ Retry timer error: " + e.getMessage());
            }
        }, INITIAL_RTO_MS, INITIAL_RTO_MS / 2, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Check for timeout and retry missing chunks
     */
    private void checkTimeouts() {
        long now = System.currentTimeMillis();
        long rto = calculateRTO();
        
        for (MessageState state : activeMessages.values()) {
            if (state.state == State.COMPLETE || state.state == State.FAILED) {
                continue;
            }
            
            // Check if message timed out
            if (now - state.lastActivityTime > rto) {
                retryMissingChunks(state);
            }
        }
    }
    
    /**
     * Retry all missing chunks
     */
    private void retryMissingChunks(MessageState state) {
        System.out.printf("[RMSG-SEND] ⏱️  Timeout: msgId=%d, missing=%d chunks (RTO=%dms)%n",
            state.messageId, state.getMissingChunkCount(), calculateRTO());
        
        state.state = State.RETRANSMITTING;
        state.totalRetries++;
        
        // Check global retry limit
        if (state.totalRetries >= MAX_RETRIES) {
            System.err.printf("[RMSG-SEND] ❌ Max retries exceeded: msgId=%d%n", state.messageId);
            completeMessage(state, false);
            return;
        }
        
        // Resend missing chunks
        int resent = 0;
        
        for (int i = 0; i < state.chunks.size(); i++) {
            if (!state.ackedChunks.get(i)) {
                ChunkInfo chunk = state.chunks.get(i);
                if (chunk.retries >= MAX_RETRIES) {
                    System.err.printf("[RMSG-SEND] ❌ Chunk %d exceeded max retries%n", i);
                    completeMessage(state, false);
                    return;
                }
                
                sendChunk(state, chunk, state.targetAddress);
                resent++;
            }
        }
        
        System.out.printf("  ↻ Retransmitted %d chunks (timer-triggered)%n", resent);
        state.state = State.WAITING_ACK;
        state.lastActivityTime = System.currentTimeMillis();
    }
    
    /**
     * Update RTT estimation (Exponential Moving Average)
     */
    private void updateRTT(MessageState state) {
        // Find first ACKed chunk to calculate RTT
        for (ChunkInfo chunk : state.chunks) {
            if (chunk.lastSentTime > 0 && state.ackedChunks.get(chunk.chunkId)) {
                long rtt = (System.nanoTime() - chunk.lastSentTime) / 1_000_000; // Convert to ms
                
                // SRTT = 0.875 * SRTT + 0.125 * RTT
                smoothedRTT = (7 * smoothedRTT + rtt) / 8;
                
                // RTTVAR = 0.75 * RTTVAR + 0.25 * |RTT - SRTT|
                long diff = Math.abs(rtt - smoothedRTT);
                rttVariance = (3 * rttVariance + diff) / 4;
                
                break; // Only calculate once per ACK
            }
        }
    }
    
    /**
     * Calculate RTO (Retransmission Timeout)
     * RTO = SRTT + 4 * RTTVAR
     */
    private long calculateRTO() {
        return Math.max(INITIAL_RTO_MS, smoothedRTT + 4 * rttVariance);
    }
    
    /**
     * Complete message (success or failure)
     */
    private void completeMessage(MessageState state, boolean success) {
        if (success) {
            state.state = State.COMPLETE;
            System.out.printf("[RMSG-SEND] ✅ Message complete: msgId=%d, chunks=%d, totalRetries=%d%n",
                state.messageId, state.chunks.size(), state.totalRetries);
            
            // Send FIN signal
            sendFIN(state);
        } else {
            state.state = State.FAILED;
            System.err.printf("[RMSG-SEND] ❌ Message failed: msgId=%d%n", state.messageId);
        }
        
        state.completionFuture.complete(success);
        
        // Remove from active messages after delay (allow FIN to be sent)
        try {
            scheduler.schedule(() -> activeMessages.remove(state.messageId), 2, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Scheduler already shut down, just cleanup now
            activeMessages.remove(state.messageId);
        }
    }
    
    /**
     * Send FIN signal
     */
    private void sendFIN(MessageState state) {
        try {
            ByteBuffer fin = LLS.New_ReliableMessage_FIN(
                senderUsername,
                state.receiver,
                state.messageId
            );
            
            channel.send(fin, state.targetAddress);
            System.out.printf("[RMSG-SEND] 🏁 FIN sent: msgId=%d%n", state.messageId);
            
        } catch (Exception e) {
            System.err.println("[RMSG-SEND] ❌ Failed to send FIN: " + e.getMessage());
        }
    }
    
    /**
     * Shutdown sender
     */
    public void shutdown() {
        System.out.println("[RMSG-SEND] 🛑 Shutting down sender...");
        scheduler.shutdownNow();
        activeMessages.clear();
    }
    
    /**
     * Get sender statistics
     */
    public String getStats() {
        return String.format(
            "Active messages: %d | SRTT: %dms | RTO: %dms",
            activeMessages.size(), smoothedRTT, calculateRTO()
        );
    }
}
