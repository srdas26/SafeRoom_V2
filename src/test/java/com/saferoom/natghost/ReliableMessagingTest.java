package com.saferoom.natghost;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for Reliable Messaging Protocol packet structures
 */
public class ReliableMessagingTest {
    
    @Test
    public void testDataChunkPacket_SmallPayload() {
        // Test data
        String sender = "alice";
        String receiver = "bob";
        long messageId = 12345L;
        int chunkId = 0;
        int totalChunks = 3;
        byte[] data = "Hello, World!".getBytes();
        
        // Create packet
        ByteBuffer packet = LLS.New_ReliableMessage_Chunk(
            sender, receiver, messageId, chunkId, totalChunks, 
            data, 0, data.length
        );
        
        // Verify packet size
        int expectedSize = 67 + data.length; // 67 header + payload
        assertEquals(expectedSize, packet.limit());
        
        // Parse packet
        Object[] parsed = LLS.parseReliableMessageChunk(packet);
        
        // Verify fields
        assertEquals(LLS.SIG_RMSG_DATA, (byte) parsed[0]);
        assertEquals(sender, parsed[2]);
        assertEquals(receiver, parsed[3]);
        assertEquals(messageId, (long) parsed[4]);
        assertEquals(chunkId, (int) parsed[5]);
        assertEquals(totalChunks, (int) parsed[6]);
        assertEquals(data.length, (int) parsed[7]);
        
        // Verify payload
        byte[] payload = (byte[]) parsed[10];
        assertArrayEquals(data, payload);
        
        System.out.println("✅ Data Chunk Packet Test: PASSED");
    }
    
    @Test
    public void testDataChunkPacket_MaxPayload() {
        // Test with maximum payload (1133 bytes)
        String sender = "alice";
        String receiver = "bob";
        long messageId = 99999L;
        int chunkId = 5;
        int totalChunks = 10;
        byte[] data = new byte[1133];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        
        // Create packet
        ByteBuffer packet = LLS.New_ReliableMessage_Chunk(
            sender, receiver, messageId, chunkId, totalChunks, 
            data, 0, data.length
        );
        
        // Verify total size is exactly 1200 bytes (MTU-safe)
        assertEquals(1200, packet.limit());
        
        // Parse and verify
        Object[] parsed = LLS.parseReliableMessageChunk(packet);
        byte[] payload = (byte[]) parsed[10];
        assertArrayEquals(data, payload);
        
        System.out.println("✅ Max Payload Test: PASSED (1200 bytes MTU-safe)");
    }
    
    @Test
    public void testACKPacket_AllReceived() {
        // Test ACK with all chunks received
        String sender = "bob";
        String receiver = "alice";
        long messageId = 12345L;
        int highestConsecutive = 7; // Received chunks 0-7
        long bitmap = 0b11111111L; // All 8 chunks received
        
        // Create packet
        ByteBuffer packet = LLS.New_ReliableMessage_ACK(
            sender, receiver, messageId, highestConsecutive, bitmap
        );
        
        // Verify size
        assertEquals(61, packet.limit());
        
        // Parse
        Object[] parsed = LLS.parseReliableMessageACK(packet);
        
        // Verify
        assertEquals(LLS.SIG_RMSG_ACK, (byte) parsed[0]);
        assertEquals(sender, parsed[2]);
        assertEquals(receiver, parsed[3]);
        assertEquals(messageId, (long) parsed[4]);
        assertEquals(highestConsecutive, (int) parsed[5]);
        assertEquals(bitmap, (long) parsed[6]);
        
        System.out.println("✅ ACK Packet Test (All Received): PASSED");
    }
    
    @Test
    public void testACKPacket_WithGaps() {
        // Test ACK with missing chunks
        String sender = "bob";
        String receiver = "alice";
        long messageId = 12345L;
        int highestConsecutive = 2; // Received 0,1,2 consecutively
        long bitmap = 0b00011111L; // Received: 0,1,2,3,4 (missing 5,6,7...)
        
        // Create packet
        ByteBuffer packet = LLS.New_ReliableMessage_ACK(
            sender, receiver, messageId, highestConsecutive, bitmap
        );
        
        // Parse
        Object[] parsed = LLS.parseReliableMessageACK(packet);
        
        // Verify bitmap
        long receivedBitmap = (long) parsed[6];
        
        // Check specific bits
        assertTrue((receivedBitmap & (1L << 0)) != 0); // Chunk 0 received
        assertTrue((receivedBitmap & (1L << 1)) != 0); // Chunk 1 received
        assertTrue((receivedBitmap & (1L << 2)) != 0); // Chunk 2 received
        assertTrue((receivedBitmap & (1L << 3)) != 0); // Chunk 3 received
        assertTrue((receivedBitmap & (1L << 4)) != 0); // Chunk 4 received
        assertFalse((receivedBitmap & (1L << 5)) != 0); // Chunk 5 missing
        
        System.out.println("✅ ACK Packet Test (With Gaps): PASSED");
    }
    
    @Test
    public void testNACKPacket() {
        // Test NACK for fast retransmission
        String sender = "bob";
        String receiver = "alice";
        long messageId = 12345L;
        int[] missingChunks = {2, 5, 7}; // Missing chunks 2, 5, 7
        
        // Create packet
        ByteBuffer packet = LLS.New_ReliableMessage_NACK(
            sender, receiver, messageId, missingChunks
        );
        
        // Parse
        Object[] parsed = LLS.parseReliableMessageNACK(packet);
        
        // Verify
        assertEquals(LLS.SIG_RMSG_NACK, (byte) parsed[0]);
        assertEquals(messageId, (long) parsed[4]);
        
        int[] receivedMissing = (int[]) parsed[5];
        assertArrayEquals(missingChunks, receivedMissing);
        
        System.out.println("✅ NACK Packet Test: PASSED");
    }
    
    @Test
    public void testFINPacket() {
        // Test FIN signal
        String sender = "bob";
        String receiver = "alice";
        long messageId = 12345L;
        
        // Create packet
        ByteBuffer packet = LLS.New_ReliableMessage_FIN(
            sender, receiver, messageId
        );
        
        // Verify size
        assertEquals(51, packet.limit());
        
        // Parse
        Object[] parsed = LLS.parseReliableMessageFIN(packet);
        
        // Verify
        assertEquals(LLS.SIG_RMSG_FIN, (byte) parsed[0]);
        assertEquals(sender, parsed[2]);
        assertEquals(receiver, parsed[3]);
        assertEquals(messageId, (long) parsed[4]);
        
        System.out.println("✅ FIN Packet Test: PASSED");
    }
    
    @Test
    public void testChunking_LargeMessage() {
        // Test chunking a 5KB message
        byte[] largeMessage = new byte[5000];
        for (int i = 0; i < largeMessage.length; i++) {
            largeMessage[i] = (byte) (i % 256);
        }
        
        int chunkSize = 1133; // Max payload per chunk
        int totalChunks = (largeMessage.length + chunkSize - 1) / chunkSize;
        
        System.out.println("📦 Chunking 5000 bytes into " + totalChunks + " chunks:");
        
        // Create all chunks
        for (int i = 0; i < totalChunks; i++) {
            int offset = i * chunkSize;
            int length = Math.min(chunkSize, largeMessage.length - offset);
            
            ByteBuffer chunk = LLS.New_ReliableMessage_Chunk(
                "alice", "bob", 99999L, i, totalChunks,
                largeMessage, offset, length
            );
            
            System.out.println("  Chunk " + i + ": " + length + " bytes, packet size: " + chunk.limit());
            
            // Verify MTU-safe
            assertTrue(chunk.limit() <= 1200, "Chunk must be MTU-safe (<=1200 bytes)");
        }
        
        System.out.println("✅ Large Message Chunking Test: PASSED");
    }
    
    @Test
    public void testCRC32_Validation() {
        // Test CRC32 integrity check
        String sender = "alice";
        String receiver = "bob";
        byte[] data = "Test data for CRC validation".getBytes();
        
        // Create packet
        ByteBuffer packet = LLS.New_ReliableMessage_Chunk(
            sender, receiver, 12345L, 0, 1, data, 0, data.length
        );
        
        // Parse
        Object[] parsed = LLS.parseReliableMessageChunk(packet);
        int receivedCRC = (int) parsed[9];
        byte[] receivedPayload = (byte[]) parsed[10];
        
        // Calculate expected CRC
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(receivedPayload);
        int expectedCRC = (int) crc.getValue();
        
        // Verify
        assertEquals(expectedCRC, receivedCRC);
        
        System.out.println("✅ CRC32 Validation Test: PASSED");
    }
}
