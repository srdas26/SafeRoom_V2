package com.saferoom.p2p;

import com.saferoom.client.ClientMenu;
import com.saferoom.natghost.KeepAliveManager;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * P2P Hole Puncher - Eski cross-matching LLS protokolü (HELLO/FIN)
 * Server ile HELLO paketleri gönderir, cross-matching olduğunda PORT_INFO alır
 */
public class P2PHolePuncher {
    
    private static final int MIN_CHANNELS = 4;
    private static final long SIGNALING_TIMEOUT_MS = 10_000;
    private static final long PUNCH_TIMEOUT_MS = 15_000;
    private static final long RESEND_INTERVAL_MS = 200; // Daha hızlı burst
    private static final long SELECT_BLOCK_MS = 50;
    
    // LLS Protocol signals (server ile)
    private static final byte SIG_HELLO = 0x10;
    private static final byte SIG_FIN = 0x11;
    private static final byte SIG_PORT = 0x12;
    private static final byte SIG_ALL_DONE = 0x13;
    
    // P2P Protocol signals (peer-to-peer direkt)
    private static final byte SIG_P2P_HELLO = 0x20;
    private static final byte SIG_P2P_HELLO_ACK = 0x21;
    private static final byte SIG_P2P_ESTABLISHED = 0x22;
    
    /**
     * P2P bağlantı kurar - Eski LLS HELLO/FIN cross-matching protokolü
     */
    public static P2PConnection establishConnection(String targetUsername, InetSocketAddress serverAddr) {
        try {
            System.out.println("🚀 Starting P2P connection to: " + targetUsername);
            
            // 1) SIGNALING PHASE: Server'a HELLO paketleri gönder (eski cross-matching)
            List<Integer> targetPorts = performSignaling(targetUsername, serverAddr);
            if (targetPorts == null || targetPorts.isEmpty()) {
                System.err.println("❌ P2P signaling failed for: " + targetUsername);
                return null;
            }
            
            System.out.printf("📡 Got target ports for %s: %s%n", targetUsername, targetPorts);
            
            // 2) P2P PHASE: Direct hole punching başlat
            P2PConnection connection = performDirectHolePunching(targetUsername, targetPorts);
            if (connection != null) {
                System.out.println("✅ P2P connection established with: " + targetUsername);
                return connection;
            } else {
                System.err.println("❌ P2P hole punching failed - timeout");
                return null;
            }
            
        } catch (Exception e) {
            System.err.println("❌ P2P connection error: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * LLS HELLO/FIN cross-matching signaling - eski protokol
     */
    private static List<Integer> performSignaling(String targetUsername, InetSocketAddress serverAddr) {
        try (DatagramChannel channel = DatagramChannel.open()) {
            
            channel.configureBlocking(false);
            channel.bind(new InetSocketAddress(0));
            
            // HELLO packet oluştur: sender=biz, target=hedef
            ByteBuffer hello = createHelloPacket(ClientMenu.myUsername, targetUsername);
            channel.send(hello, serverAddr);
            
            System.out.printf("📤 Sending HELLO to server: %s -> %s%n", ClientMenu.myUsername, targetUsername);
            
            Selector selector = Selector.open();
            channel.register(selector, SelectionKey.OP_READ);
            
            long start = System.currentTimeMillis();
            long lastHello = start;
            
            while (System.currentTimeMillis() - start < SIGNALING_TIMEOUT_MS) {
                
                // Her 1 saniyede HELLO gönder (server'a sadece 1 paket!)
                if (System.currentTimeMillis() - lastHello > 1000) {
                    hello.rewind();
                    channel.send(hello, serverAddr);
                    lastHello = System.currentTimeMillis();
                    System.out.println("🔄 Resending HELLO to server...");
                }
                
                selector.select(SELECT_BLOCK_MS);
                
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next(); 
                    it.remove();
                    
                    if (!key.isReadable()) continue;
                    
                    ByteBuffer buf = ByteBuffer.allocate(1024);
                    SocketAddress from = channel.receive(buf);
                    if (from == null) continue;
                    
                    buf.flip();
                    if (buf.remaining() < 1) continue;
                    
                    byte signal = buf.get(0);
                    
                    if (signal == SIG_PORT) { // PORT_INFO geldi!
                        System.out.println("📡 Received PORT_INFO from server");
                        List<Integer> ports = parsePortInfo(buf);
                        
                        // FIN gönder (signaling tamamlandı)
                        ByteBuffer fin = createFinPacket(ClientMenu.myUsername, targetUsername);
                        channel.send(fin, serverAddr);
                        System.out.println("📤 Sent FIN to server");
                        
                        return ports;
                        
                    } else if (signal == SIG_ALL_DONE) { // Karşı taraf da hazır
                        System.out.println("✅ ALL_DONE received - both peers ready");
                        return parsePortInfo(buf);
                    }
                }
            }
            
            System.err.println("⏰ LLS signaling timeout");
            return null;
            
        } catch (Exception e) {
            System.err.println("❌ LLS signaling error: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * PORT_INFO paketini parse et
     */
    private static List<Integer> parsePortInfo(ByteBuffer buf) {
        try {
            buf.position(1); // Skip signal
            
            // Port sayısı
            short portCount = buf.getShort();
            List<Integer> ports = new ArrayList<>();
            
            for (int i = 0; i < portCount; i++) {
                ports.add(buf.getInt());
            }
            
            return ports;
            
        } catch (Exception e) {
            System.err.println("❌ Error parsing PORT_INFO: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * LLS protokol paketleri oluştur
     */
    private static ByteBuffer createHelloPacket(String sender, String target) {
        return createLLSPacket(SIG_HELLO, sender, target);
    }
    
    private static ByteBuffer createFinPacket(String sender, String target) {
        return createLLSPacket(SIG_FIN, sender, target);
    }
    
    private static ByteBuffer createLLSPacket(byte signal, String sender, String target) {
        // LLS Fixed-Length Format: [type][len][sender_20][target_20]
        ByteBuffer packet = ByteBuffer.allocate(1 + 2 + 20 + 20);
        packet.put(signal);
        packet.putShort((short) 40); // Fixed: 20 + 20
        
        // Sender - fixed 20 bytes
        byte[] senderBytes = sender.getBytes();
        packet.put(senderBytes);
        for (int i = senderBytes.length; i < 20; i++) packet.put((byte) 0);
        
        // Target - fixed 20 bytes  
        byte[] targetBytes = target.getBytes();
        packet.put(targetBytes);
        for (int i = targetBytes.length; i < 20; i++) packet.put((byte) 0);
        
        packet.flip();
        return packet;
    }
    
    /**
     * Direct peer-to-peer hole punching (portları biliyoruz)
     */
    private static P2PConnection performDirectHolePunching(String targetUsername, List<Integer> targetPorts) {
        try {
            System.out.println("🔫 Starting direct P2P hole punching...");
            
            List<DatagramChannel> channels = new ArrayList<>();
            Selector selector = Selector.open();
            KeepAliveManager KAM = new KeepAliveManager(2_000);
            
            // Her target port için bir channel oluştur  
            for (int i = 0; i < Math.max(targetPorts.size(), MIN_CHANNELS); i++) {
                DatagramChannel dc = DatagramChannel.open();
                dc.configureBlocking(false);
                dc.bind(new InetSocketAddress(0));
                dc.register(selector, SelectionKey.OP_READ);
                channels.add(dc);
            }
            
            long start = System.currentTimeMillis();
            long lastSend = start;
            boolean connectionEstablished = false;
            DatagramChannel successfulChannel = null;
            InetSocketAddress successfulTarget = null;
            
            System.out.printf("📡 Target ports: %s%n", targetPorts);
            
            while (System.currentTimeMillis() - start < PUNCH_TIMEOUT_MS && !connectionEstablished) {
                
                // Her 200ms'de P2P HELLO paketleri gönder (AGGRESSIVE BURST MODE)
                if (System.currentTimeMillis() - lastSend > RESEND_INTERVAL_MS) {
                    for (int i = 0; i < channels.size() && i < targetPorts.size(); i++) {
                        DatagramChannel dc = channels.get(i);
                        int targetPort = targetPorts.get(i);
                        
                        // Hedef peer'ın external IP'sini tahmin et (aynı subnet varsay)
                        InetSocketAddress targetAddr = new InetSocketAddress("192.168.1.100", targetPort); // TODO: Real IP detection
                        
                        // AGGRESSIVE BURST: Her port için 5 paket gönder (NAT hole punch için)
                        for (int burst = 0; burst < 5; burst++) {
                            ByteBuffer hello = createP2PHelloPacket(ClientMenu.myUsername, targetUsername);
                            dc.send(hello, targetAddr);
                        }
                        
                        System.out.printf("📤 Sent 5 P2P HELLO bursts to %s%n", targetAddr);
                    }
                    lastSend = System.currentTimeMillis();
                }
                
                selector.select(SELECT_BLOCK_MS);
                
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next(); 
                    it.remove();
                    
                    if (!key.isReadable()) continue;
                    
                    DatagramChannel dc = (DatagramChannel) key.channel();
                    ByteBuffer buf = ByteBuffer.allocate(512);
                    SocketAddress from = dc.receive(buf);
                    if (from == null) continue;
                    
                    buf.flip();
                    if (buf.remaining() < 1) continue;
                    
                    byte signal = buf.get(0);
                    
                    if (signal == SIG_P2P_HELLO) {
                        // HELLO ACK gönder
                        ByteBuffer ack = createP2PHelloAckPacket(ClientMenu.myUsername, targetUsername);
                        dc.send(ack, from);
                        
                        System.out.printf("✅ P2P HELLO received from %s, sending ACK%n", from);
                        
                    } else if (signal == SIG_P2P_HELLO_ACK) {
                        // Bağlantı kuruldu!
                        ByteBuffer established = createP2PEstablishedPacket(ClientMenu.myUsername, targetUsername);
                        dc.send(established, from);
                        
                        connectionEstablished = true;
                        successfulChannel = dc;
                        successfulTarget = (InetSocketAddress) from;
                        
                        System.out.printf("🎉 P2P connection established with %s via %s%n", targetUsername, from);
                        break;
                        
                    } else if (signal == SIG_P2P_ESTABLISHED) {
                        connectionEstablished = true;
                        successfulChannel = dc;
                        successfulTarget = (InetSocketAddress) from;
                        
                        System.out.printf("🎉 P2P connection confirmed with %s via %s%n", targetUsername, from);
                        break;
                    }
                }
            }
            
            // Sonuç
            if (connectionEstablished && successfulChannel != null) {
                // Diğer kanalları kapat
                for (DatagramChannel dc : channels) {
                    if (dc != successfulChannel) {
                        try { dc.close(); } catch (Exception e) {}
                    }
                }
                
                // P2P connection oluştur
                P2PConnection connection = new P2PConnection(targetUsername, successfulTarget, successfulChannel);
                connection.setKeepAliveManager(KAM);
                
                System.out.println("✅ P2P hole punching successful!");
                return connection;
                
            } else {
                System.err.println("❌ P2P hole punching failed - timeout");
                KAM.close();
                for (DatagramChannel dc : channels) {
                    try { dc.close(); } catch (Exception e) {}
                }
                return null;
            }
            
        } catch (Exception e) {
            System.err.println("❌ Hole punching error: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * P2P protokol paketleri
     */
    private static ByteBuffer createP2PHelloPacket(String sender, String target) {
        return createP2PPacket(SIG_P2P_HELLO, sender, target);
    }
    
    private static ByteBuffer createP2PHelloAckPacket(String sender, String target) {
        return createP2PPacket(SIG_P2P_HELLO_ACK, sender, target);
    }
    
    private static ByteBuffer createP2PEstablishedPacket(String sender, String target) {
        return createP2PPacket(SIG_P2P_ESTABLISHED, sender, target);
    }
    
    private static ByteBuffer createP2PPacket(byte signal, String sender, String target) {
        byte[] senderBytes = sender.getBytes();
        byte[] targetBytes = target.getBytes();
        
        ByteBuffer packet = ByteBuffer.allocate(1 + 2 + senderBytes.length + 2 + targetBytes.length);
        packet.put(signal);
        packet.putShort((short) senderBytes.length);
        packet.put(senderBytes);
        packet.putShort((short) targetBytes.length);
        packet.put(targetBytes);
        
        packet.flip();
        return packet;
    }
    
    /**
     * Async P2P bağlantı kurar
     */
    public static CompletableFuture<P2PConnection> establishConnectionAsync(String targetUsername, InetSocketAddress serverAddr) {
        return CompletableFuture.supplyAsync(() -> establishConnection(targetUsername, serverAddr))
                .orTimeout(30, TimeUnit.SECONDS);
    }
}
