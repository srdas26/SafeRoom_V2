package com.saferoom.natghost;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class LLS {

    // ---- PACKET TYPES (signal byte) ----
    public static final byte SIG_HELLO    = 0x10; // client -> server (port header'dan okunacak)
    public static final byte SIG_FIN      = 0x11; // client -> server ("tüm portları yolladım")
    public static final byte SIG_PORT     = 0x12; // server -> client (tek bir karşı port bilgisi)
    public static final byte SIG_ALL_DONE = 0x13; // server -> client ("from tarafı için bitti")
    public static final byte SIG_DNS_QUERY= 0x14; // DNS query for firewall bypass

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

    // ---- QUICK CHECKS ----
    public static boolean hasWholeFrame(ByteBuffer bb) {
        int pos = bb.position();
        if (bb.remaining() < 3) return false;
        byte  t = bb.get();
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
 // 1) Sabitlerin yanına
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

}
