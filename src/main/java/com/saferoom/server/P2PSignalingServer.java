package com.saferoom.server;

import com.saferoom.natghost.LLS;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P2P Signaling Server - Eski PeerListener mantığı ile cross-matching
 * HELLO/FIN paketleri ile sender/target eşleştirme yapar
 */
public class P2PSignalingServer extends Thread {

    static final class PeerState {
        final String host;
        final String target;
        final byte signal;
        InetAddress ip;
        
        final List<Integer> ports = Collections.synchronizedList(new ArrayList<>());
        final Set<Integer> sentToTarget = Collections.synchronizedSet(new HashSet<>());
        int rrCursor = 0;
        
        volatile boolean finished = false;
        volatile boolean allDoneSentToTarget = false;
        volatile long lastSeenMs = System.currentTimeMillis();
        
        PeerState(String host, String target, byte signal, InetAddress ip, int port) {
            this.host = host;
            this.target = target;
            this.signal = signal;
            this.ip = ip;
            this.ports.add(port);
        }
        
        void add(InetAddress ip, int port) {
            this.ip = ip;
            // Duplicate port kontrolü - aynı portu tekrar ekleme
            if (!this.ports.contains(port)) {
                this.ports.add(port);
            }
            this.lastSeenMs = System.currentTimeMillis();
        }
    }

    private static final Map<String, PeerState> STATES = new ConcurrentHashMap<>();
    public static final int SIGNALING_PORT = 45001;
    
    // Modern hashmap for peer matching: <host_username, target_username> -> PeerInfo
    private static final Map<String, PeerInfo> PEER_REQUESTS = new ConcurrentHashMap<>();
    
    private static final long CLEANUP_INTERVAL_MS = 30_000;
    private long lastCleanup = System.currentTimeMillis();
    
    // Enhanced peer info for same-NAT detection
    static class PeerInfo {
        final String username;
        final String targetUsername;
        final InetAddress publicIP;
        final int publicPort;
        final InetAddress localIP;
        final int localPort;
        final InetSocketAddress clientAddress; // Track where to send response
        final long timestamp;
        
        PeerInfo(String username, String targetUsername, InetAddress publicIP, int publicPort, 
                InetAddress localIP, int localPort, InetSocketAddress clientAddress) {
            this.username = username;
            this.targetUsername = targetUsername;
            this.publicIP = publicIP;
            this.publicPort = publicPort;
            this.localIP = localIP;
            this.localPort = localPort;
            this.clientAddress = clientAddress;
            this.timestamp = System.currentTimeMillis();
        }
        
        // Legacy constructor for backward compatibility
        PeerInfo(String username, String targetUsername, InetAddress publicIP, int publicPort, InetSocketAddress clientAddress) {
            this(username, targetUsername, publicIP, publicPort, null, 0, clientAddress);
        }
        
        @Override
        public String toString() {
            if (localIP != null) {
                return username + "->" + targetUsername + " (public: " + publicIP + ":" + publicPort + 
                       ", local: " + localIP + ":" + localPort + ")";
            } else {
                return username + "->" + targetUsername + " (public: " + publicIP + ":" + publicPort + ")";
            }
        }
    }
    
    /**
     * Handle modern hole punch request (SIG_HOLE)
     * Implements the hashmap-based peer matching system
     */
    private void handleHolePunchRequest(ByteBuffer buf, DatagramChannel channel, InetSocketAddress from) {
        try {
            // Check packet length to determine if it's extended
            short len = LLS.peekLen(buf);
            List<Object> parsed;
            
            String sender, target;
            InetAddress publicIP, localIP = null;
            int publicPort, localPort = 0;
            
            if (len == 59) { // Extended packet with local IP/port
                parsed = LLS.parseExtendedHolePacket(buf);
                sender = (String) parsed.get(2);
                target = (String) parsed.get(3);
                publicIP = (InetAddress) parsed.get(4);
                publicPort = (Integer) parsed.get(5);
                localIP = (InetAddress) parsed.get(6);
                localPort = (Integer) parsed.get(7);
                
                System.out.printf("🎯 EXTENDED_HOLE_PUNCH: %s -> %s%n", sender, target);
                System.out.printf("   Public: %s:%d, Local: %s:%d%n", 
                    publicIP.getHostAddress(), publicPort, localIP.getHostAddress(), localPort);
            } else { // Standard packet
                parsed = LLS.parseLLSPacket(buf);
                sender = (String) parsed.get(2);
                target = (String) parsed.get(3);
                publicIP = (InetAddress) parsed.get(4);
                publicPort = (Integer) parsed.get(5);
                
                System.out.printf("🎯 HOLE_PUNCH: %s -> %s (public: %s:%d)%n", 
                    sender, target, publicIP.getHostAddress(), publicPort);
            }
            
            // Store this peer's info with client address
            String senderKey = sender + "->" + target;
            PeerInfo senderInfo = new PeerInfo(sender, target, publicIP, publicPort, localIP, localPort, from);
            PEER_REQUESTS.put(senderKey, senderInfo);
            
            // Check if target is also waiting for this sender
            String targetKey = target + "->" + sender;
            PeerInfo targetInfo = PEER_REQUESTS.get(targetKey);
            
            if (targetInfo != null) {
                // MATCH FOUND! Both peers are ready for hole punching
                System.out.printf("✅ PEER MATCH: %s <-> %s%n", sender, target);
                
                // Check if same NAT (same public IP)
                boolean sameNAT = senderInfo.publicIP.equals(targetInfo.publicIP);
                System.out.printf("🔍 Same NAT detection: %s (sender: %s, target: %s)%n", 
                    sameNAT ? "YES - using local IPs" : "NO - using public IPs",
                    senderInfo.publicIP.getHostAddress(), targetInfo.publicIP.getHostAddress());
                
                if (sameNAT && senderInfo.localIP != null && targetInfo.localIP != null) {
                    // Use local IPs for same-NAT communication
                    ByteBuffer targetInfoPacket = LLS.New_PortInfo_Packet(
                        target, sender, LLS.SIG_PORT, 
                        targetInfo.localIP, targetInfo.localPort
                    );
                    channel.send(targetInfoPacket, from);
                    System.out.printf("📤 Sent %s's LOCAL info (%s:%d) to %s%n", 
                        target, targetInfo.localIP.getHostAddress(), targetInfo.localPort, sender);
                    
                    // Send sender's local info to target
                    ByteBuffer senderInfoPacket = LLS.New_PortInfo_Packet(
                        sender, target, LLS.SIG_PORT,
                        senderInfo.localIP, senderInfo.localPort
                    );
                    channel.send(senderInfoPacket, targetInfo.clientAddress);
                    System.out.printf("📤 Sent %s's LOCAL info (%s:%d) to %s%n", 
                        sender, senderInfo.localIP.getHostAddress(), senderInfo.localPort, target);
                } else {
                    // Use public IPs for different NATs
                    ByteBuffer targetInfoPacket = LLS.New_PortInfo_Packet(
                        target, sender, LLS.SIG_PORT, 
                        targetInfo.publicIP, targetInfo.publicPort
                    );
                    channel.send(targetInfoPacket, from);
                    System.out.printf("📤 Sent %s's PUBLIC info (%s:%d) to %s%n", 
                        target, targetInfo.publicIP.getHostAddress(), targetInfo.publicPort, sender);
                    
                    // Send sender's public info to target
                    ByteBuffer senderInfoPacket = LLS.New_PortInfo_Packet(
                        sender, target, LLS.SIG_PORT,
                        senderInfo.publicIP, senderInfo.publicPort
                    );
                    channel.send(senderInfoPacket, targetInfo.clientAddress);
                    System.out.printf("📤 Sent %s's PUBLIC info (%s:%d) to %s%n", 
                        sender, senderInfo.publicIP.getHostAddress(), senderInfo.publicPort, target);
                }
                
                // Clean up the matched pair
                PEER_REQUESTS.remove(senderKey);
                PEER_REQUESTS.remove(targetKey);
                
                System.out.printf("🎉 Hole punch coordination complete for %s <-> %s%n", sender, target);
                
            } else {
                // Target not ready yet, just wait
                System.out.printf("⏳ %s waiting for %s to request hole punch%n", sender, target);
            }
            
        } catch (Exception e) {
            System.err.printf("❌ Error handling hole punch from %s: %s%n", from, e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try (Selector selector = Selector.open();
             DatagramChannel channel = DatagramChannel.open()) {

            channel.configureBlocking(false);
            channel.bind(new InetSocketAddress(SIGNALING_PORT));
            channel.register(selector, SelectionKey.OP_READ);

            System.out.println("🎯 P2P Signaling Server running on port " + SIGNALING_PORT);
            System.out.println("🔍 Waiting for client packets...");

            while (true) {
                selector.select(5);

                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next(); 
                    it.remove();
                    if (!key.isReadable()) continue;

                    ByteBuffer buf = ByteBuffer.allocate(1024);
                    SocketAddress from = channel.receive(buf);
                    if (from == null) continue;

                    System.out.printf("📥 Received packet from %s (size=%d)%n", from, buf.position());

                    buf.flip();
                    if (!LLS.hasWholeFrame(buf)) {
                        System.out.printf("❌ Incomplete frame from %s (remaining=%d)%n", from, buf.remaining());
                        continue;
                    }

                    InetSocketAddress inet = (InetSocketAddress) from;
                    InetAddress ip = inet.getAddress();
                    int port = inet.getPort();

                    byte sig = LLS.peekType(buf);

                    switch (sig) {
                        case LLS.SIG_HOLE -> {
                            // Modern hole punch request - single packet with IP/port info
                            handleHolePunchRequest(buf.duplicate(), channel, inet);
                        }
                        case LLS.SIG_HELLO, LLS.SIG_FIN -> {
                            String tempSender;
                            String tempTarget;
                            InetAddress tempClientIP; 
                            
                            // Packet boyutuna göre parse et
                            if (buf.remaining() >= 51) { // LLS packet (IP içerir)
                                try {
                                    List<Object> p = LLS.parseLLSPacket(buf.duplicate());
                                    tempSender = (String) p.get(2);
                                    tempTarget = (String) p.get(3);
                                    tempClientIP = (InetAddress) p.get(4); // STUN'dan gelen public IP
                                    System.out.printf("🌐 LLS packet: sender='%s' target='%s' publicIP=%s%n", 
                                        tempSender, tempTarget, tempClientIP.getHostAddress());
                                } catch (Exception e) {
                                    System.err.println("❌ LLS parse error, falling back to basic: " + e.getMessage());
                                    List<Object> p2 = LLS.parseMultiple_Packet(buf.duplicate());
                                    tempSender = (String) p2.get(2);
                                    tempTarget = (String) p2.get(3);
                                    tempClientIP = ip; // Packet geldiği IP
                                }
                            } else { // Basic packet (IP yok)
                                List<Object> p = LLS.parseMultiple_Packet(buf.duplicate());
                                tempSender = (String) p.get(2);
                                tempTarget = (String) p.get(3);
                                tempClientIP = ip; // Packet geldiği IP
                                System.out.printf("📦 Basic packet: sender='%s' target='%s' localIP=%s%n", 
                                    tempSender, tempTarget, tempClientIP.getHostAddress());
                            }
                            
                            final String sender = tempSender;
                            final String target = tempTarget;
                            final InetAddress clientIP = tempClientIP;
                            
                            final byte signal = sig;
                            final InetAddress finalClientIP = clientIP;
                            final String finalSender = sender;
                            final String finalTarget = target;
                            
                            // DEBUG: Packet içeriğini logla
                            System.out.printf("🔍 DEBUG Packet: sender='%s' target='%s' (sig=%d)%n", 
                                finalSender, finalTarget, signal);

                            // Sender için state oluştur/güncelle
                            PeerState me = STATES.compute(finalSender, (k, old) -> {
                                if (old == null) {
                                    return new PeerState(finalSender, finalTarget, signal, finalClientIP, port);
                                }
                                // ÖNEMLI: Target değişmişse yeni state oluştur!
                                if (!old.target.equals(finalTarget)) {
                                    System.out.printf("🔄 Target changed %s: %s -> %s (creating new state)%n", 
                                        finalSender, old.target, finalTarget);
                                    return new PeerState(finalSender, finalTarget, signal, finalClientIP, port);
                                }
                                // Target aynıysa sadece port ekle
                                old.add(finalClientIP, port);
                                return old;
                            });                            if (sig == LLS.SIG_FIN) {
                                me.finished = true;
                                System.out.printf("🏁 FIN from %s (ports=%s)%n", finalSender, me.ports);
                            } else {
                                System.out.printf("👋 HELLO %s @ %s:%d (ports=%s)%n",
                                        finalSender, finalClientIP.getHostAddress(), port, me.ports);
                            }

                            // Cross-matching: MUTUAL TARGETING kontrolü
                            PeerState tgt = STATES.get(me.target);
                            if (tgt != null && tgt.target.equals(me.host)) {
                                // TRUE CROSS-MATCH: A->B && B->A
                                System.out.printf("🎯 TRUE Cross-match: %s ↔ %s (mutual targeting)%n", me.host, tgt.host);
                                System.out.printf("📡 Sharing ports - %s: %s | %s: %s%n", 
                                    me.host, me.ports, tgt.host, tgt.ports);
                                pushAllIfReady(channel, me, tgt);
                                pushAllIfReady(channel, tgt, me);
                            } else if (tgt != null) {
                                System.out.printf("❌ One-way targeting: %s->%s but %s->%s (not mutual!)%n", 
                                    me.host, me.target, tgt.host, tgt.target);
                            } else {
                                System.out.printf("⏰ Waiting for target: %s (requested by %s)%n", me.target, sender);
                            }
                        }

                        default -> {
                            System.out.printf("❓ Unknown signal: 0x%02X%n", sig);
                        }
                    }
                }

                // Cleanup old states
                long now = System.currentTimeMillis();
                if (now - lastCleanup > CLEANUP_INTERVAL_MS) {
                    int oldSize = STATES.size();
                    STATES.entrySet().removeIf(e -> (now - e.getValue().lastSeenMs) > 120_000);
                    int newSize = STATES.size();
                    if (oldSize > newSize) {
                        System.out.printf("🧹 Cleaned up %d old peer states%n", oldSize - newSize);
                    }
                    lastCleanup = now;
                }
            }

        } catch (Exception e) {
            System.err.println("❌ P2P Signaling Server ERROR: " + e);
            e.printStackTrace();
        }
    }

    /**
     * Cross-match bulunduktan sonra PORT_INFO ve ALL_DONE paketlerini gönder
     */
    private void pushAllIfReady(DatagramChannel ch, PeerState from, PeerState to) throws Exception {
        System.out.printf("🔍 pushAllIfReady: %s → %s (finished=%b, allDoneSent=%b, toPorts=%s)%n", 
            from.host, to.host, from.finished, from.allDoneSentToTarget, to.ports);
            
        if (!from.finished) {
            System.out.printf("⏳ %s not finished yet, waiting for FIN%n", from.host);
            return;
        }
        if (from.allDoneSentToTarget) {
            System.out.printf("✅ %s already sent ALL_DONE to %s%n", from.host, to.host);
            return;
        }
        if (to.ports.isEmpty()) {
            System.out.printf("❌ %s has no ports to send to%n", to.host);
            return;
        }

        // Henüz gönderilmemiş portları bul
        List<Integer> unsent = new ArrayList<>();
        for (int p : from.ports) {
            if (!from.sentToTarget.contains(p)) unsent.add(p);
        }

        List<Integer> toPorts = new ArrayList<>(to.ports);
        
        // PORT_INFO paketlerini gönder
        for (int p : unsent) {
            int idx = from.rrCursor % toPorts.size();
            int toPort = toPorts.get(idx);
            from.rrCursor++;

            ByteBuffer portPkt = LLS.New_PortInfo_Packet(from.host, from.target, LLS.SIG_PORT, from.ip, p);
            ch.send(portPkt, new InetSocketAddress(to.ip, toPort));
            from.sentToTarget.add(p);
            
            System.out.printf("📤 PORT_INFO: %s:%d → %s:%d%n", 
                from.host, p, to.host, toPort);
        }

        // ALL_DONE paketlerini gönder
        for (int toPort : toPorts) {
            ByteBuffer donePkt = LLS.New_AllDone_Packet(from.host, to.host);
            ch.send(donePkt, new InetSocketAddress(to.ip, toPort));
        }
        from.allDoneSentToTarget = true;

        System.out.printf("✅ ALL_DONE sent (%s → %s). Ports=%s%n", from.host, to.host, from.ports);
    }
}
