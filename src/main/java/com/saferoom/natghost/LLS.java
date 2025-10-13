package com.saferoom.natghost;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class LLS {

    // ---- PACKET TYPES (signal byte) ----
    public static final byte SIG_HELLO     = 0x10; // client -> server (port header'dan okunacak)
    public static final byte SIG_FIN       = 0x11; // client -> server ("tüm portları yolladım")
    public static final byte SIG_PORT      = 0x12; // server -> client (tek bir karşı port bilgisi)
    public static final byte SIG_ALL_DONE  = 0x13; // server -> client ("from tarafı için bitti")
    public static final byte SIG_DNS_QUERY = 0x14; // DNS query for firewall bypass
    public static final byte SIG_HOLE      = 0x15; // client -> server (hole punch request with IP/port)
    public static final byte SIG_MESSAGE   = 0x16; // client <-> client (P2P text message)
    public static final byte SIG_MSG_ACK   = 0x17; // client <-> client (message acknowledgment)
    public static final byte SIG_REGISTER  = 0x18; // client -> server (register user with NAT info)
    public static final byte SIG_P2P_REQUEST = 0x19; // client -> server (request P2P connection to target)
    public static final byte SIG_P2P_NOTIFY = 0x1A; // server -> client (notify about incoming P2P request)
    public static final byte SIG_NAT_PROFILE = 0x1B; // client -> server (NAT type + port range profile)
    public static final byte SIG_PUNCH_INSTRUCT = 0x1C; // server -> client (coordinated hole punch instructions)
    public static final byte SIG_PUNCH_BURST = 0x1D; // client <-> client (P2P hole punch burst packet)
    
    // ---- RELIABLE MESSAGING PROTOCOL (QUIC-inspired) ----
    public static final byte SIG_RMSG_DATA   = 0x20; // Reliable message data chunk (with seq, CRC)
    public static final byte SIG_RMSG_ACK    = 0x21; // Selective ACK with bitmap (SACK)
    public static final byte SIG_RMSG_NACK   = 0x22; // NACK for fast retransmission (optional)
    public static final byte SIG_RMSG_FIN    = 0x23; // Message transfer complete signal

    // ---- COMMON HELPERS ----
    private static void putFixedString(ByteBuffer buf, String str, int len) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > len) {
            buf.put(bytes, 0, len);
        } else {
            buf.put(bytes);
            for (int i = bytes.length; i < len; i++) buf.put((byte) 0);
        }
    }

    private static String getFixedString(ByteBuffer buf, int len) {
        byte[] bytes = new byte[len];
        buf.get(bytes);
        int realLen = 0;
        while (realLen < len && bytes[realLen] != 0) realLen++;
        return new String(bytes, 0, realLen, StandardCharsets.UTF_8);
    }

    // ---- LENGTHS ----
    // Multiplex packet: type(1) + len(2) + user(20) + target(20) = 43
    private static final int MULTIPLEX_LEN = 43;
    // LLS packet with IP/Port: type(1) + len(2) + user(20) + target(20) + ip(4) + port(4) = 51
    private static final int LLS_LEN       = 51;
    // Extended packet: type(1) + len(2) + user(20) + target(20) + publicIP(4) + publicPort(4) + localIP(4) + localPort(4) = 59
    private static final int EXTENDED_LEN  = 59;

    // ---- QUICK CHECKS ----
    public static boolean hasWholeFrame(ByteBuffer bb) {
        int pos = bb.position();
        if (bb.remaining() < 3) return false;
        bb.get(); // type byte - not used here
        short l = bb.getShort();
        boolean ok = bb.remaining() >= l - 3; // already consumed 3
        bb.position(pos);
        return ok;
    }

    public static byte peekType(ByteBuffer bb) {
        int pos = bb.position();
        byte t = bb.get();
        bb.position(pos);
        return t;
    }

    public static short peekLen(ByteBuffer bb) {
        int pos = bb.position();
        bb.get(); // type
        short l = bb.getShort();
        bb.position(pos);
        return l;
    }

    // ---- PACKET BUILDERS ----
    // HELLO / FIN / ALL_DONE ==> Multiplex tip paket (43 byte)
    public static ByteBuffer New_Hello_Packet(String username, String target, byte signal /* SIG_HELLO */) {
        ByteBuffer packet = ByteBuffer.allocate(MULTIPLEX_LEN);
        packet.put(signal);
        packet.putShort((short) MULTIPLEX_LEN);
        putFixedString(packet, username, 20);
        putFixedString(packet, target, 20);
        packet.flip();
        return packet;
    }

    public static ByteBuffer New_Fin_Packet(String username, String target) {
        ByteBuffer packet = ByteBuffer.allocate(MULTIPLEX_LEN);
        packet.put(SIG_FIN);
        packet.putShort((short) MULTIPLEX_LEN);
        putFixedString(packet, username, 20);
        putFixedString(packet, target, 20);
        packet.flip();
        return packet;
    }

    public static ByteBuffer New_AllDone_Packet(String fromHost, String toHost) {
        ByteBuffer packet = ByteBuffer.allocate(MULTIPLEX_LEN);
        packet.put(SIG_ALL_DONE);
        packet.putShort((short) MULTIPLEX_LEN);
        putFixedString(packet, fromHost, 20);
        putFixedString(packet, toHost, 20);
        packet.flip();
        return packet;
    }

    // PORT_INFO (server->client) ==> IP ve Port içerir (51 byte)
    public static ByteBuffer New_PortInfo_Packet(String username, String target, byte signal,
                                                 InetAddress publicIp, int port) {
        // signal çoğunlukla SIG_PORT olacak
        ByteBuffer buffer = ByteBuffer.allocate(LLS_LEN);
        buffer.put(signal);
        buffer.putShort((short) LLS_LEN);
        putFixedString(buffer, username, 20);
        putFixedString(buffer, target, 20);
        buffer.put(publicIp.getAddress());
        buffer.putInt(port);
        buffer.flip();
        return buffer;
    }

    // Eski isimler (geri uyum):
    public static ByteBuffer New_LLS_Packet(byte signal, String username, String target,
                                            InetAddress publicIp, int port) {
        return New_PortInfo_Packet(username, target, signal, publicIp, port);
    }

    public static ByteBuffer New_Multiplex_Packet(byte signal, String username, String target) {
        // signal => SIG_HELLO veya SIG_FIN veya SIG_ALL_DONE gibi kullanılabilir
        ByteBuffer packet = ByteBuffer.allocate(MULTIPLEX_LEN);
        packet.put(signal);
        packet.putShort((short) MULTIPLEX_LEN);
        putFixedString(packet, username, 20);
        putFixedString(packet, target, 20);
        packet.flip();
        return packet;
    }
    
    // NEW: Hole punch request packet - includes public IP/port info
    public static ByteBuffer New_Hole_Packet(String username, String target, 
                                             InetAddress publicIp, int publicPort) {
        ByteBuffer packet = ByteBuffer.allocate(LLS_LEN);
        packet.put(SIG_HOLE);
        packet.putShort((short) LLS_LEN);
        putFixedString(packet, username, 20);
        putFixedString(packet, target, 20);
        packet.put(publicIp.getAddress()); // 4 bytes
        packet.putInt(publicPort);          // 4 bytes
        packet.flip();
        return packet;
    }
    
    // NEW: Extended hole punch packet - includes both public and local IP/port
    public static ByteBuffer New_Extended_Hole_Packet(String username, String target,
                                                      InetAddress publicIp, int publicPort,
                                                      InetAddress localIp, int localPort) {
        ByteBuffer packet = ByteBuffer.allocate(EXTENDED_LEN);
        packet.put(SIG_HOLE);
        packet.putShort((short) EXTENDED_LEN);
        putFixedString(packet, username, 20);
        putFixedString(packet, target, 20);
        packet.put(publicIp.getAddress()); // 4 bytes - public IP
        packet.putInt(publicPort);          // 4 bytes - public port
        packet.put(localIp.getAddress());  // 4 bytes - local IP
        packet.putInt(localPort);           // 4 bytes - local port
        packet.flip();
        return packet;
    }
    
    // NEW: P2P Burst packet - hole punching burst with payload
    public static ByteBuffer New_Burst_Packet(String username, String target, String payload) {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        int totalLen = 1 + 2 + 20 + 20 + payloadBytes.length; // type + len + username + target + payload
        
        ByteBuffer packet = ByteBuffer.allocate(totalLen);
        packet.put(SIG_PUNCH_BURST);
        packet.putShort((short) totalLen);
        putFixedString(packet, username, 20);
        putFixedString(packet, target, 20);
        packet.put(payloadBytes);
        packet.flip();
        return packet;
    }
    
    // NEW: P2P Message packet - simple text messaging
    public static ByteBuffer New_Message_Packet(String sender, String receiver, String message) {
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        int totalLen = 1 + 2 + 20 + 20 + 4 + messageBytes.length; // type + len + sender + receiver + msgLen + message
        
        ByteBuffer packet = ByteBuffer.allocate(totalLen);
        packet.put(SIG_MESSAGE);
        packet.putShort((short) totalLen);
        putFixedString(packet, sender, 20);
        putFixedString(packet, receiver, 20);
        packet.putInt(messageBytes.length);
        packet.put(messageBytes);
        packet.flip();
        return packet;
    }
    
    // NEW: Message acknowledgment packet
    public static ByteBuffer New_MessageAck_Packet(String sender, String receiver, long messageId) {
        ByteBuffer packet = ByteBuffer.allocate(MULTIPLEX_LEN + 8); // Basic + messageId
        packet.put(SIG_MSG_ACK);
        packet.putShort((short) (MULTIPLEX_LEN + 8));
        putFixedString(packet, sender, 20);
        putFixedString(packet, receiver, 20);
        packet.putLong(messageId);
        packet.flip();
        return packet;
    }
    
    // NEW: User registration packet - client registers with server on startup
    public static ByteBuffer New_Register_Packet(String username, 
                                                 InetAddress publicIp, int publicPort,
                                                 InetAddress localIp, int localPort) {
        ByteBuffer packet = ByteBuffer.allocate(EXTENDED_LEN);
        packet.put(SIG_REGISTER);
        packet.putShort((short) EXTENDED_LEN);
        putFixedString(packet, username, 20);
        putFixedString(packet, "", 20); // target field empty for registration
        packet.put(publicIp.getAddress()); // 4 bytes - public IP
        packet.putInt(publicPort);          // 4 bytes - public port
        packet.put(localIp.getAddress());  // 4 bytes - local IP
        packet.putInt(localPort);           // 4 bytes - local port
        packet.flip();
        return packet;
    }
    
    // NEW: P2P connection request packet - client requests connection to target
    public static ByteBuffer New_P2PRequest_Packet(String requester, String target) {
        ByteBuffer packet = ByteBuffer.allocate(MULTIPLEX_LEN);
        packet.put(SIG_P2P_REQUEST);
        packet.putShort((short) MULTIPLEX_LEN);
        putFixedString(packet, requester, 20);
        putFixedString(packet, target, 20);
        packet.flip();
        return packet;
    }
    
    // NEW: P2P notification packet - server notifies target about incoming request
    public static ByteBuffer New_P2PNotify_Packet(String requester, String target,
                                                   InetAddress requesterPublicIp, int requesterPublicPort,
                                                   InetAddress requesterLocalIp, int requesterLocalPort) {
        ByteBuffer packet = ByteBuffer.allocate(EXTENDED_LEN);
        packet.put(SIG_P2P_NOTIFY);
        packet.putShort((short) EXTENDED_LEN);
        putFixedString(packet, requester, 20);
        putFixedString(packet, target, 20);
        packet.put(requesterPublicIp.getAddress()); // 4 bytes
        packet.putInt(requesterPublicPort);          // 4 bytes
        packet.put(requesterLocalIp.getAddress());  // 4 bytes
        packet.putInt(requesterLocalPort);           // 4 bytes
        packet.flip();
        return packet;
    }


    // ---- PARSERS ----
    // Multiplex: [type][len][sender][target]
    public static List<Object> parseMultiple_Packet(ByteBuffer buffer) {
        final int MIN_LENGTH = 1 + 2 + 20 + 20;
        if (buffer.remaining() < MIN_LENGTH) {
            throw new IllegalArgumentException("Packet too short for multiplex: " + buffer.remaining());
        }
        List<Object> parsed = new ArrayList<>(4);
        byte type = buffer.get();
        short len = buffer.getShort();
        parsed.add(type);
        parsed.add(len);
        String sender = getFixedString(buffer, 20);
        String target = getFixedString(buffer, 20);
        parsed.add(sender);
        parsed.add(target);
        return parsed;
    }

    // LLS with IP/port: [type][len][sender][target][ip4][int port]
    public static List<Object> parseLLSPacket(ByteBuffer buffer) throws UnknownHostException {
        List<Object> parsed = new ArrayList<>(6);

        byte type = buffer.get();
        short len = buffer.getShort();
        parsed.add(type);
        parsed.add(len);

        String sender = getFixedString(buffer, 20);
        String target = getFixedString(buffer, 20);
        parsed.add(sender);
        parsed.add(target);

        if (buffer.remaining() < 4 + 4)
            throw new IllegalArgumentException("Packet too short for IP/Port. rem=" + buffer.remaining());

        byte[] ipBytes = new byte[4];
        buffer.get(ipBytes);
        InetAddress ip = InetAddress.getByAddress(ipBytes);
        parsed.add(ip);

        int port = buffer.getInt();
        parsed.add(port);

        return parsed;
    }
    public static final byte SIG_KEEP = 0x1E; // client <-> client/server keepalive ping

    // 2) Builder metotların yanına
    public static ByteBuffer New_KeepAlive_Packet() {
        // Sadece tip + len (payload yok) -> 3 byte
        ByteBuffer b = ByteBuffer.allocate(3);
        b.put(SIG_KEEP);
        b.putShort((short) 3);
        b.flip();
        return b;
    }

    // DNS Query packet for firewall bypass - looks like legitimate DNS traffic
    public static ByteBuffer New_DNSQuery_Packet() {
        ByteBuffer packet = ByteBuffer.allocate(29);
        
        // DNS Header (12 bytes)
        packet.putShort((short) 0x1234);        // Transaction ID
        packet.putShort((short) 0x0100);        // Flags: Standard query
        packet.putShort((short) 1);             // Questions: 1
        packet.putShort((short) 0);             // Answer RRs: 0
        packet.putShort((short) 0);             // Authority RRs: 0
        packet.putShort((short) 0);             // Additional RRs: 0
        
        // Question Section: "a.com" (13 bytes)
        packet.put((byte) 1);                   // Length of "a"
        packet.put((byte) 'a');                 // "a"
        packet.put((byte) 3);                   // Length of "com"
        packet.put("com".getBytes());           // "com"
        packet.put((byte) 0);                   // Null terminator
        
        // Query Type and Class (4 bytes)
        packet.putShort((short) 1);             // Type: A (IPv4 address)
        packet.putShort((short) 1);             // Class: IN (Internet)
        
        packet.flip();
        return packet;
    }

    // Convenience for client side parsing:
    public static List<Object> parsePortInfo(ByteBuffer buffer) throws UnknownHostException {
        // same as parseLLSPacket but returns [InetAddress, Integer]
        List<Object> full = parseLLSPacket(buffer);
        List<Object> out = new ArrayList<>(2);
        out.add(full.get(4));
        out.add(full.get(5));
        return out;
    }

    public static List<Object> parseAllDone(ByteBuffer buffer) {
        // [SIG_ALL_DONE][len][fromUser][toUser]
        List<Object> p = parseMultiple_Packet(buffer);
        // return [fromUser, toUser]
        List<Object> out = new ArrayList<>(2);
        out.add(p.get(2));
        out.add(p.get(3));
        return out;
    }
    
    // NEW: Parse P2P message packet
    public static List<Object> parseMessagePacket(ByteBuffer buffer) {
        List<Object> parsed = new ArrayList<>(4);
        
        byte type = buffer.get();
        short len = buffer.getShort();
        parsed.add(type);
        parsed.add(len);
        
        String sender = getFixedString(buffer, 20);
        String receiver = getFixedString(buffer, 20);
        parsed.add(sender);
        parsed.add(receiver);
        
        int messageLen = buffer.getInt();
        byte[] messageBytes = new byte[messageLen];
        buffer.get(messageBytes);
        String message = new String(messageBytes, StandardCharsets.UTF_8);
        parsed.add(message);
        
        return parsed; // [type, len, sender, receiver, message]
    }
    
    // NEW: Parse message acknowledgment packet
    public static List<Object> parseMessageAck(ByteBuffer buffer) {
        List<Object> parsed = new ArrayList<>(4);
        
        byte type = buffer.get();
        short len = buffer.getShort();
        parsed.add(type);
        parsed.add(len);
        
        String sender = getFixedString(buffer, 20);
        String receiver = getFixedString(buffer, 20);
        parsed.add(sender);
        parsed.add(receiver);
        
        long messageId = buffer.getLong();
        parsed.add(messageId);
        
        return parsed; // [type, len, sender, receiver, messageId]
    }
    
    // NEW: Parse extended hole punch packet with both public and local IP/port
    public static List<Object> parseExtendedHolePacket(ByteBuffer buffer) throws UnknownHostException {
        List<Object> parsed = new ArrayList<>(8);
        
        byte type = buffer.get();
        short len = buffer.getShort();
        parsed.add(type);
        parsed.add(len);
        
        String sender = getFixedString(buffer, 20);
        String target = getFixedString(buffer, 20);
        parsed.add(sender);
        parsed.add(target);
        
        // Public IP/Port
        byte[] publicIpBytes = new byte[4];
        buffer.get(publicIpBytes);
        InetAddress publicIp = InetAddress.getByAddress(publicIpBytes);
        parsed.add(publicIp);
        
        int publicPort = buffer.getInt();
        parsed.add(publicPort);
        
        // Local IP/Port
        byte[] localIpBytes = new byte[4];
        buffer.get(localIpBytes);
        InetAddress localIp = InetAddress.getByAddress(localIpBytes);
        parsed.add(localIp);
        
        int localPort = buffer.getInt();
        parsed.add(localPort);
        
        return parsed; // [type, len, sender, target, publicIP, publicPort, localIP, localPort]
    }
    
    // NEW: Parse user registration packet
    public static List<Object> parseRegisterPacket(ByteBuffer buffer) throws UnknownHostException {
        // Same structure as extended hole packet but used for registration
        return parseExtendedHolePacket(buffer); // [type, len, username, "", publicIP, publicPort, localIP, localPort]
    }
    
    // NEW: Parse P2P request packet
    public static List<Object> parseP2PRequestPacket(ByteBuffer buffer) {
        // Same structure as multiplex packet
        return parseMultiple_Packet(buffer); // [type, len, requester, target]
    }
    
    // NEW: Parse P2P notification packet
    public static List<Object> parseP2PNotifyPacket(ByteBuffer buffer) throws UnknownHostException {
        // Same structure as extended hole packet
        return parseExtendedHolePacket(buffer); // [type, len, requester, target, publicIP, publicPort, localIP, localPort]
    }

    // NEW: Create NAT profile packet
    // Structure: type(1) + len(2) + user(20) + natType(1) + minPort(4) + maxPort(4) + profiledPorts(4) = 36 bytes
    public static byte[] createNATProfilePacket(String username, byte natType, int minPort, int maxPort, int profiledPorts) {
        ByteBuffer buffer = ByteBuffer.allocate(36);
        buffer.put(SIG_NAT_PROFILE);
        buffer.putShort((short) 36);
        putFixedString(buffer, username, 20);
        buffer.put(natType);
        buffer.putInt(minPort);
        buffer.putInt(maxPort);
        buffer.putInt(profiledPorts);
        return buffer.array();
    }

    // NEW: Parse NAT profile packet
    public static List<Object> parseNATProfilePacket(ByteBuffer buffer) {
        List<Object> parsed = new ArrayList<>();
        byte type = buffer.get();
        parsed.add(type);
        short len = buffer.getShort();
        parsed.add((int) len);
        String user = getFixedString(buffer, 20);
        parsed.add(user);
        byte natType = buffer.get();
        parsed.add(natType);
        int minPort = buffer.getInt();
        parsed.add(minPort);
        int maxPort = buffer.getInt();
        parsed.add(maxPort);
        int profiledPorts = buffer.getInt();
        parsed.add(profiledPorts);
        return parsed; // [type, len, user, natType, minPort, maxPort, profiledPorts]
    }

    // NEW: Create punch instruction packet for symmetric NAT side
    // Structure: type(1) + len(2) + user(20) + target(20) + targetIP(4) + targetPort(4) + strategy(1) + numPorts(4) = 56 bytes
    public static byte[] createPunchInstructPacket(String username, String target, InetAddress targetIP, int targetPort, byte strategy, int numPorts) {
        ByteBuffer buffer = ByteBuffer.allocate(56);
        buffer.put(SIG_PUNCH_INSTRUCT);
        buffer.putShort((short) 56);
        putFixedString(buffer, username, 20);
        putFixedString(buffer, target, 20);
        buffer.put(targetIP.getAddress());
        buffer.putInt(targetPort);
        buffer.put(strategy); // 0x01 = symmetric burst, 0x02 = asymmetric scan
        buffer.putInt(numPorts);
        return buffer.array();
    }

    // NEW: Parse punch instruction packet
    public static List<Object> parsePunchInstructPacket(ByteBuffer buffer) throws UnknownHostException {
        List<Object> parsed = new ArrayList<>();
        byte type = buffer.get();
        parsed.add(type);
        short len = buffer.getShort();
        parsed.add((int) len);
        String user = getFixedString(buffer, 20);
        parsed.add(user);
        String target = getFixedString(buffer, 20);
        parsed.add(target);
        byte[] ipBytes = new byte[4];
        buffer.get(ipBytes);
        InetAddress targetIP = InetAddress.getByAddress(ipBytes);
        parsed.add(targetIP);
        int targetPort = buffer.getInt();
        parsed.add(targetPort);
        byte strategy = buffer.get();
        parsed.add(strategy);
        int numPorts = buffer.getInt();
        parsed.add(numPorts);
        return parsed; // [type, len, user, target, targetIP, targetPort, strategy, numPorts]
    }
    
    /**
     * Parse SIG_PUNCH_BURST packet
     * Format: type(1) + len(2) + sender(20) + receiver(20) + payload(variable)
     * @return [type, len, sender, receiver, payload]
     */
    public static List<Object> parseBurstPacket(ByteBuffer buffer) {
        List<Object> parsed = new ArrayList<>();
        byte type = buffer.get();
        parsed.add(type);
        short len = buffer.getShort();
        parsed.add((int) len);
        String sender = getFixedString(buffer, 20);
        parsed.add(sender);
        String receiver = getFixedString(buffer, 20);
        parsed.add(receiver);
        
        // Remaining bytes are payload
        byte[] payloadBytes = new byte[buffer.remaining()];
        buffer.get(payloadBytes);
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        parsed.add(payload);
        
        return parsed; // [type, len, sender, receiver, payload]
    }

    // ========================================================================
    // RELIABLE MESSAGING PROTOCOL (QUIC-inspired UDP Reliability)
    // ========================================================================
    
    /**
     * Reliable Message Data Chunk - MTU-safe (1200 bytes max)
     * 
     * Structure (69 bytes header + max 1131 payload):
     * ┌─────────────────────────────────────────────────────────────────┐
     * │ Type(1) │ Len(2) │ Sender(20) │ Receiver(20)                   │ 43
     * ├─────────────────────────────────────────────────────────────────┤
     * │ MessageID(8) │ ChunkID(2) │ TotalChunks(2)                     │ 12
     * ├─────────────────────────────────────────────────────────────────┤
     * │ ChunkSize(2) │ Timestamp(8) │ CRC32(4)                         │ 14
     * ├─────────────────────────────────────────────────────────────────┤
     * │ Payload(variable, max 1131)...                                  │
     * └─────────────────────────────────────────────────────────────────┘
     * Total: 43 + 12 + 14 = 69 bytes header + payload
     * 
     * @param sender       Sender username (20 chars max)
     * @param receiver     Receiver username (20 chars max)
     * @param messageId    Unique message ID (8 bytes)
     * @param chunkId      Zero-based chunk index
     * @param totalChunks  Total number of chunks in message
     * @param chunkData    Source data array
     * @param offset       Offset in source array
     * @param length       Number of bytes to read (max 1131)
     * @return ByteBuffer ready to send (position=0, limit=totalSize)
     */
    public static ByteBuffer New_ReliableMessage_Chunk(
        String sender,
        String receiver,
        long messageId,
        int chunkId,
        int totalChunks,
        byte[] chunkData,
        int offset,
        int length
    ) {
        // Validation
        if (length > 1131) {
            throw new IllegalArgumentException("Chunk data too large: " + length + " bytes (max 1131)");
        }
        if (offset + length > chunkData.length) {
            throw new IllegalArgumentException("Invalid offset/length: offset=" + offset + 
                ", length=" + length + ", array=" + chunkData.length);
        }
        
        // Calculate packet size
        int headerSize = 69; // Fixed header size (43 + 12 + 14)
        int totalSize = headerSize + length;
        
        ByteBuffer packet = ByteBuffer.allocate(totalSize);
        
        // Header: type + len + sender + receiver (43 bytes)
        packet.put(SIG_RMSG_DATA);
        packet.putShort((short) totalSize);
        putFixedString(packet, sender, 20);
        putFixedString(packet, receiver, 20);
        
        // Message metadata (12 bytes)
        packet.putLong(messageId);
        packet.putShort((short) chunkId);
        packet.putShort((short) totalChunks);
        
        // Chunk metadata + integrity (14 bytes)
        packet.putShort((short) length);
        packet.putLong(System.nanoTime()); // Timestamp for RTT measurement
        
        // Calculate CRC32 of payload for integrity check
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(chunkData, offset, length);
        packet.putInt((int) crc.getValue());
        
        // Payload (variable, max 1133 bytes)
        packet.put(chunkData, offset, length);
        
        packet.flip();
        return packet;
    }
    
    /**
     * Selective ACK with Bitmap (SACK - QUIC-style)
     * 
     * Structure (61 bytes):
     * ┌─────────────────────────────────────────────────────────────────┐
     * │ Type(1) │ Len(2) │ Sender(20) │ Receiver(20)                   │ 43
     * ├─────────────────────────────────────────────────────────────────┤
     * │ MessageID(8) │ HighestConsecutive(2) │ Bitmap(8)               │ 18
     * └─────────────────────────────────────────────────────────────────┘
     * 
     * Bitmap: 64-bit bitmask where bit N indicates if chunk N was received
     *   - Bit 0 = chunk 0, Bit 1 = chunk 1, etc.
     *   - 1 = received, 0 = missing
     *   - For messages >64 chunks, use multiple ACKs with sliding window
     * 
     * HighestConsecutive: Highest chunk number received without gaps
     *   - Example: received [0,1,2,4,5] → highestConsecutive = 2
     *   - This allows fast detection of missing chunks
     * 
     * @param sender                   ACK sender (original receiver)
     * @param receiver                 ACK receiver (original sender)
     * @param messageId                Message ID being acknowledged
     * @param highestConsecutiveChunk  Highest chunk received without gaps
     * @param chunkBitmap              64-bit bitmask for chunks [0-63]
     * @return ByteBuffer ready to send
     */
    public static ByteBuffer New_ReliableMessage_ACK(
        String sender,
        String receiver,
        long messageId,
        int highestConsecutiveChunk,
        long chunkBitmap
    ) {
        ByteBuffer packet = ByteBuffer.allocate(61);
        
        // Header: type + len + sender + receiver (43 bytes)
        packet.put(SIG_RMSG_ACK);
        packet.putShort((short) 61);
        putFixedString(packet, sender, 20);
        putFixedString(packet, receiver, 20);
        
        // ACK data (18 bytes)
        packet.putLong(messageId);
        packet.putShort((short) highestConsecutiveChunk);
        packet.putLong(chunkBitmap);
        
        packet.flip();
        return packet;
    }
    
    /**
     * NACK for Fast Retransmission (optional, aggressive recovery)
     * 
     * Structure (varies based on missing chunk count):
     * ┌─────────────────────────────────────────────────────────────────┐
     * │ Type(1) │ Len(2) │ Sender(20) │ Receiver(20)                   │ 43
     * ├─────────────────────────────────────────────────────────────────┤
     * │ MessageID(8) │ MissingCount(2) │ MissingChunkIDs(2*N)...       │
     * └─────────────────────────────────────────────────────────────────┘
     * 
     * Used when receiver detects gaps and wants immediate retransmission
     * instead of waiting for timer-based retry.
     * 
     * @param sender           NACK sender (original receiver)
     * @param receiver         NACK receiver (original sender)
     * @param messageId        Message ID
     * @param missingChunkIds  Array of missing chunk IDs
     * @return ByteBuffer ready to send
     */
    public static ByteBuffer New_ReliableMessage_NACK(
        String sender,
        String receiver,
        long messageId,
        int[] missingChunkIds
    ) {
        int totalSize = 43 + 8 + 2 + (missingChunkIds.length * 2);
        ByteBuffer packet = ByteBuffer.allocate(totalSize);
        
        // Header: type + len + sender + receiver (43 bytes)
        packet.put(SIG_RMSG_NACK);
        packet.putShort((short) totalSize);
        putFixedString(packet, sender, 20);
        putFixedString(packet, receiver, 20);
        
        // NACK data
        packet.putLong(messageId);
        packet.putShort((short) missingChunkIds.length);
        for (int chunkId : missingChunkIds) {
            packet.putShort((short) chunkId);
        }
        
        packet.flip();
        return packet;
    }
    
    /**
     * Message Transfer Complete Signal
     * 
     * Sent by receiver after successfully receiving and reassembling
     * all chunks. This allows sender to clean up state and confirm delivery.
     * 
     * Structure (51 bytes):
     * ┌─────────────────────────────────────────────────────────────────┐
     * │ Type(1) │ Len(2) │ Sender(20) │ Receiver(20)                   │ 43
     * ├─────────────────────────────────────────────────────────────────┤
     * │ MessageID(8)                                                    │ 8
     * └─────────────────────────────────────────────────────────────────┘
     * 
     * @param sender     FIN sender (original receiver)
     * @param receiver   FIN receiver (original sender)
     * @param messageId  Completed message ID
     * @return ByteBuffer ready to send
     */
    public static ByteBuffer New_ReliableMessage_FIN(
        String sender,
        String receiver,
        long messageId
    ) {
        ByteBuffer packet = ByteBuffer.allocate(51);
        
        // Header: type + len + sender + receiver (43 bytes)
        packet.put(SIG_RMSG_FIN);
        packet.putShort((short) 51);
        putFixedString(packet, sender, 20);
        putFixedString(packet, receiver, 20);
        
        // FIN data (8 bytes)
        packet.putLong(messageId);
        
        packet.flip();
        return packet;
    }
    
    // ========================================================================
    // RELIABLE MESSAGING PARSERS
    // ========================================================================
    
    /**
     * Parse Reliable Message Data Chunk
     * 
     * @param buffer ByteBuffer positioned at start of packet
     * @return Object array: [type, len, sender, receiver, msgId, chunkId, 
     *                        totalChunks, chunkSize, timestamp, crc32, payload]
     */
    public static Object[] parseReliableMessageChunk(ByteBuffer buffer) {
        byte type = buffer.get();
        short len = buffer.getShort();
        String sender = getFixedString(buffer, 20);
        String receiver = getFixedString(buffer, 20);
        long messageId = buffer.getLong();
        int chunkId = Short.toUnsignedInt(buffer.getShort());
        int totalChunks = Short.toUnsignedInt(buffer.getShort());
        int chunkSize = Short.toUnsignedInt(buffer.getShort());
        long timestamp = buffer.getLong();
        int crc32 = buffer.getInt();
        
        // Extract payload
        byte[] payload = new byte[chunkSize];
        buffer.get(payload);
        
        return new Object[] {
            type, len, sender, receiver, messageId, chunkId, 
            totalChunks, chunkSize, timestamp, crc32, payload
        };
    }
    
    /**
     * Parse Reliable Message ACK
     * 
     * @param buffer ByteBuffer positioned at start of packet
     * @return Object array: [type, len, sender, receiver, msgId, 
     *                        highestConsecutive, bitmap]
     */
    public static Object[] parseReliableMessageACK(ByteBuffer buffer) {
        byte type = buffer.get();
        short len = buffer.getShort();
        String sender = getFixedString(buffer, 20);
        String receiver = getFixedString(buffer, 20);
        long messageId = buffer.getLong();
        int highestConsecutive = Short.toUnsignedInt(buffer.getShort());
        long bitmap = buffer.getLong();
        
        return new Object[] {
            type, len, sender, receiver, messageId, highestConsecutive, bitmap
        };
    }
    
    /**
     * Parse Reliable Message NACK
     * 
     * @param buffer ByteBuffer positioned at start of packet
     * @return Object array: [type, len, sender, receiver, msgId, missingChunkIds[]]
     */
    public static Object[] parseReliableMessageNACK(ByteBuffer buffer) {
        byte type = buffer.get();
        short len = buffer.getShort();
        String sender = getFixedString(buffer, 20);
        String receiver = getFixedString(buffer, 20);
        long messageId = buffer.getLong();
        int missingCount = Short.toUnsignedInt(buffer.getShort());
        
        int[] missingChunkIds = new int[missingCount];
        for (int i = 0; i < missingCount; i++) {
            missingChunkIds[i] = Short.toUnsignedInt(buffer.getShort());
        }
        
        return new Object[] {
            type, len, sender, receiver, messageId, missingChunkIds
        };
    }
    
    /**
     * Parse Reliable Message FIN
     * 
     * @param buffer ByteBuffer positioned at start of packet
     * @return Object array: [type, len, sender, receiver, msgId]
     */
    public static Object[] parseReliableMessageFIN(ByteBuffer buffer) {
        byte type = buffer.get();
        short len = buffer.getShort();
        String sender = getFixedString(buffer, 20);
        String receiver = getFixedString(buffer, 20);
        long messageId = buffer.getLong();
        
        return new Object[] {
            type, len, sender, receiver, messageId
        };
    }

}
