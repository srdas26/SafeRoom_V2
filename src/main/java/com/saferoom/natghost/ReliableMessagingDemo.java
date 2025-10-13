package com.saferoom.natghost;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * Manual test/demo for Reliable Messaging Protocol packet structures
 */
public class ReliableMessagingDemo {
    
    public static void main(String[] args) {
        System.out.println("🧪 RELIABLE MESSAGING PROTOCOL - PACKET STRUCTURE TESTS\n");
        
        testDataChunk();
        testACK();
        testNACK();
        testFIN();
        testLargeMessage();
        testCRCValidation();
        
        System.out.println("\n✅ ALL TESTS PASSED!");
    }
    
    static void testDataChunk() {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("TEST 1: Data Chunk Packet");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Create test data
        String message = "Hello, this is a test message!";
        byte[] data = message.getBytes();
        
        // Create chunk packet
        ByteBuffer packet = LLS.New_ReliableMessage_Chunk(
            "alice", "bob", 12345L, 0, 1, data, 0, data.length
        );
        
        // Verify size
        int expectedSize = 69 + data.length; // 69 byte header + payload
        System.out.println("✓ Expected size: " + expectedSize + " bytes");
        System.out.println("✓ Actual size: " + packet.limit() + " bytes");
        assert packet.limit() == expectedSize : "Size mismatch!";
        
        // Parse packet
        Object[] parsed = LLS.parseReliableMessageChunk(packet);
        
        // Verify fields
        assert (byte) parsed[0] == LLS.SIG_RMSG_DATA : "Wrong packet type!";
        assert parsed[2].equals("alice") : "Wrong sender!";
        assert parsed[3].equals("bob") : "Wrong receiver!";
        assert (long) parsed[4] == 12345L : "Wrong message ID!";
        assert (int) parsed[5] == 0 : "Wrong chunk ID!";
        assert (int) parsed[6] == 1 : "Wrong total chunks!";
        
        // Verify payload
        byte[] payload = (byte[]) parsed[10];
        String receivedMessage = new String(payload);
        System.out.println("✓ Sent: \"" + message + "\"");
        System.out.println("✓ Received: \"" + receivedMessage + "\"");
        assert message.equals(receivedMessage) : "Payload mismatch!";
        
        System.out.println("✅ Data Chunk Test PASSED\n");
    }
    
    static void testACK() {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("TEST 2: ACK Packet with Bitmap");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Create ACK with bitmap
        // Received chunks: 0,1,2,3,4,5,6,7 (all 8 chunks)
        long bitmap = 0b11111111L;
        
        ByteBuffer packet = LLS.New_ReliableMessage_ACK(
            "bob", "alice", 12345L, 7, bitmap
        );
        
        System.out.println("✓ ACK packet size: " + packet.limit() + " bytes");
        assert packet.limit() == 61 : "Wrong ACK size!";
        
        // Parse
        Object[] parsed = LLS.parseReliableMessageACK(packet);
        
        assert (byte) parsed[0] == LLS.SIG_RMSG_ACK : "Wrong packet type!";
        assert (long) parsed[4] == 12345L : "Wrong message ID!";
        assert (int) parsed[5] == 7 : "Wrong highest consecutive!";
        
        long receivedBitmap = (long) parsed[6];
        System.out.println("✓ Sent bitmap: " + Long.toBinaryString(bitmap));
        System.out.println("✓ Received bitmap: " + Long.toBinaryString(receivedBitmap));
        assert bitmap == receivedBitmap : "Bitmap mismatch!";
        
        // Test individual bits
        for (int i = 0; i < 8; i++) {
            boolean bitSet = (receivedBitmap & (1L << i)) != 0;
            System.out.println("  Chunk " + i + ": " + (bitSet ? "✓ received" : "✗ missing"));
            assert bitSet : "Chunk " + i + " should be received!";
        }
        
        System.out.println("✅ ACK Test PASSED\n");
    }
    
    static void testNACK() {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("TEST 3: NACK Packet");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Missing chunks: 2, 5, 7
        int[] missingChunks = {2, 5, 7};
        
        ByteBuffer packet = LLS.New_ReliableMessage_NACK(
            "bob", "alice", 12345L, missingChunks
        );
        
        System.out.println("✓ NACK packet size: " + packet.limit() + " bytes");
        
        // Parse
        Object[] parsed = LLS.parseReliableMessageNACK(packet);
        
        assert (byte) parsed[0] == LLS.SIG_RMSG_NACK : "Wrong packet type!";
        assert (long) parsed[4] == 12345L : "Wrong message ID!";
        
        int[] receivedMissing = (int[]) parsed[5];
        System.out.println("✓ Missing chunks requested: " + receivedMissing.length);
        for (int i = 0; i < receivedMissing.length; i++) {
            System.out.println("  Missing chunk: " + receivedMissing[i]);
            assert receivedMissing[i] == missingChunks[i] : "Missing chunk mismatch!";
        }
        
        System.out.println("✅ NACK Test PASSED\n");
    }
    
    static void testFIN() {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("TEST 4: FIN Packet");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        ByteBuffer packet = LLS.New_ReliableMessage_FIN(
            "bob", "alice", 12345L
        );
        
        System.out.println("✓ FIN packet size: " + packet.limit() + " bytes");
        assert packet.limit() == 51 : "Wrong FIN size!";
        
        // Parse
        Object[] parsed = LLS.parseReliableMessageFIN(packet);
        
        assert (byte) parsed[0] == LLS.SIG_RMSG_FIN : "Wrong packet type!";
        assert parsed[2].equals("bob") : "Wrong sender!";
        assert parsed[3].equals("alice") : "Wrong receiver!";
        assert (long) parsed[4] == 12345L : "Wrong message ID!";
        
        System.out.println("✓ Message ID: " + parsed[4]);
        System.out.println("✅ FIN Test PASSED\n");
    }
    
    static void testLargeMessage() {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("TEST 5: Large Message Chunking (5KB)");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Create 5KB message
        byte[] largeMessage = new byte[5000];
        for (int i = 0; i < largeMessage.length; i++) {
            largeMessage[i] = (byte) (i % 256);
        }
        
        int chunkSize = 1131; // Max payload per chunk (MTU 1200 - 69 header)
        int totalChunks = (largeMessage.length + chunkSize - 1) / chunkSize;
        
        System.out.println("✓ Message size: " + largeMessage.length + " bytes");
        System.out.println("✓ Chunk size: " + chunkSize + " bytes");
        System.out.println("✓ Total chunks needed: " + totalChunks);
        
        // Create all chunks
        for (int i = 0; i < totalChunks; i++) {
            int offset = i * chunkSize;
            int length = Math.min(chunkSize, largeMessage.length - offset);
            
            ByteBuffer chunk = LLS.New_ReliableMessage_Chunk(
                "alice", "bob", 99999L, i, totalChunks,
                largeMessage, offset, length
            );
            
            System.out.println("  Chunk " + i + ": " + length + " bytes → packet: " + chunk.limit() + " bytes");
            
            // Verify MTU-safe
            assert chunk.limit() <= 1200 : "Chunk too large! MTU exceeded!";
        }
        
        System.out.println("✅ Large Message Chunking Test PASSED\n");
    }
    
    static void testCRCValidation() {
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("TEST 6: CRC32 Integrity Check");
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        byte[] data = "CRC integrity test data".getBytes();
        
        // Create packet
        ByteBuffer packet = LLS.New_ReliableMessage_Chunk(
            "alice", "bob", 12345L, 0, 1, data, 0, data.length
        );
        
        // Parse
        Object[] parsed = LLS.parseReliableMessageChunk(packet);
        int receivedCRC = (int) parsed[9];
        byte[] receivedPayload = (byte[]) parsed[10];
        
        // Calculate expected CRC
        CRC32 crc = new CRC32();
        crc.update(receivedPayload);
        int expectedCRC = (int) crc.getValue();
        
        System.out.println("✓ Sent CRC32: " + Integer.toHexString(receivedCRC));
        System.out.println("✓ Calculated CRC32: " + Integer.toHexString(expectedCRC));
        assert receivedCRC == expectedCRC : "CRC mismatch! Data corrupted!";
        
        System.out.println("✅ CRC32 Validation Test PASSED\n");
    }
}
