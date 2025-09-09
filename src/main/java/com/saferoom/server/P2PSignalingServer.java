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
    
    private static final long CLEANUP_INTERVAL_MS = 30_000;
    private long lastCleanup = System.currentTimeMillis();

    @Override
    public void run() {
        try (Selector selector = Selector.open();
             DatagramChannel channel = DatagramChannel.open()) {

            channel.configureBlocking(false);
            channel.bind(new InetSocketAddress(SIGNALING_PORT));
            channel.register(selector, SelectionKey.OP_READ);

            System.out.println("🎯 P2P Signaling Server running on port " + SIGNALING_PORT);

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

                    buf.flip();
                    if (!LLS.hasWholeFrame(buf)) continue;

                    InetSocketAddress inet = (InetSocketAddress) from;
                    InetAddress ip = inet.getAddress();
                    int port = inet.getPort();

                    byte sig = LLS.peekType(buf);

                    switch (sig) {
                        case LLS.SIG_HELLO, LLS.SIG_FIN -> {
                            List<Object> p = LLS.parseMultiple_Packet(buf.duplicate());
                            String sender = (String) p.get(2);
                            String target = (String) p.get(3);
                            byte signal = sig;

                            // DEBUG: Packet içeriğini logla
                            System.out.printf("🔍 DEBUG Packet: sender='%s' target='%s' (sig=%d)%n", 
                                sender, target, signal);

                            // Sender için state oluştur/güncelle
                            PeerState me = STATES.compute(sender, (k, old) -> {
                                if (old == null) {
                                    return new PeerState(sender, target, signal, ip, port);
                                }
                                // ÖNEMLI: Target değişmişse yeni state oluştur!
                                if (!old.target.equals(target)) {
                                    System.out.printf("🔄 Target changed %s: %s -> %s (creating new state)%n", 
                                        sender, old.target, target);
                                    return new PeerState(sender, target, signal, ip, port);
                                }
                                // Target aynıysa sadece port ekle
                                old.add(ip, port);
                                return old;
                            });

                            if (sig == LLS.SIG_FIN) {
                                me.finished = true;
                                System.out.printf("🏁 FIN from %s (ports=%s)%n", sender, me.ports);
                            } else {
                                System.out.printf("👋 HELLO %s @ %s:%d (ports=%s)%n",
                                        sender, ip.getHostAddress(), port, me.ports);
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
        if (!from.finished) return;
        if (from.allDoneSentToTarget) return;
        if (to.ports.isEmpty()) return;

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
