package com.saferoom.natghost;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class KeepAliveManager implements AutoCloseable {

    private final ScheduledExecutorService exec;
    private final long intervalMs;
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    
    // Message listening support
    private Thread messageListenerThread = null;
    private volatile boolean listening = false;

    public KeepAliveManager(long intervalMs) {
        this.intervalMs = intervalMs;
        this.exec = Executors.newScheduledThreadPool(
                1,
                r -> { Thread t = new Thread(r, "KeepAliveScheduler"); t.setDaemon(false); return t; }
        );
    }

    /** Aynı (localPort->remotePort) için ikinci kez çağırırsan tekrar job açmaz. */
    public void register(DatagramChannel localChannel, InetSocketAddress remote) throws IOException {
        Objects.requireNonNull(localChannel, "localChannel");
        Objects.requireNonNull(remote, "remote");

        int localPort  = ((InetSocketAddress) localChannel.getLocalAddress()).getPort();
        int remotePort = remote.getPort();
        String key = localPort + "->" + remotePort;

        tasks.computeIfAbsent(key, k -> {
            AtomicLong seq = new AtomicLong();
            return exec.scheduleAtFixedRate(() -> {
                try {
                    // DNS Query for firewall bypass - looks like legitimate DNS traffic
                    ByteBuffer pkt = LLS.New_DNSQuery_Packet();
                    int sent = localChannel.send(pkt, remote);
                    System.out.printf("[KA-DNS] #%d  %d -> %d  (%d bytes)\n",
                            seq.getAndIncrement(), localPort, remotePort, sent);
                } catch (IOException e) {
                    System.err.println("[KeepAliveManager] send error (" + key + "): " + e);
                }
            }, 0, intervalMs, TimeUnit.MILLISECONDS);
        });
    }

    public void printSummary() {
        System.out.println("[KA] Active pairs: " + tasks.keySet());
    }

    /** Ctrl+C gelene kadar ana thread'i bloklamak için. */
    public void blockMain() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException ignored) {}
    }

    public void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "KA-ShutdownHook"));
    }

    /**
     * Start message listening on the channel (integrated with keep-alive)
     */
    public void startMessageListening(DatagramChannel channel) {
        if (listening || messageListenerThread != null) {
            return; // Already listening
        }
        
        listening = true;
        messageListenerThread = new Thread(() -> {
            System.out.println("[KA] 📡 Integrated message listener started");
            
            try (Selector selector = Selector.open()) {
                channel.register(selector, SelectionKey.OP_READ);
                
                while (listening && channel.isOpen()) {
                    if (selector.select(500) == 0) continue; // 500ms timeout
                    
                    Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                    while (it.hasNext()) {
                        SelectionKey key = it.next();
                        it.remove();
                        
                        if (!key.isReadable()) continue;
                        
                        DatagramChannel dc = (DatagramChannel) key.channel();
                        ByteBuffer buf = ByteBuffer.allocate(1024);
                        SocketAddress from = dc.receive(buf);
                        if (from == null) continue;
                        
                        buf.flip();
                        
                        if (!LLS.hasWholeFrame(buf)) continue;
                        byte type = LLS.peekType(buf);
                        
                        System.out.printf("[KA] 📡 Received packet type: 0x%02X from %s%n", type, from);
                        
                        if (type == LLS.SIG_MESSAGE) {
                            System.out.println("[KA] 🎯 SIG_MESSAGE detected - forwarding to NatAnalyzer");
                            // Forward to NatAnalyzer for processing
                            try {
                                java.lang.reflect.Method method = Class.forName("com.saferoom.natghost.NatAnalyzer")
                                    .getDeclaredMethod("handleIncomingMessage", ByteBuffer.class, SocketAddress.class);
                                method.setAccessible(true);
                                method.invoke(null, buf.duplicate(), from);
                            } catch (Exception e) {
                                System.err.println("[KA] Error forwarding message: " + e.getMessage());
                            }
                        } else if (type == LLS.SIG_PUNCH_INSTRUCT) {
                            System.out.printf("[KA] 🧠 SIG_PUNCH_INSTRUCT detected from %s - forwarding to NatAnalyzer%n", from);
                            // Forward intelligent punch instruction to NatAnalyzer for processing
                            try {
                                java.lang.reflect.Method method = Class.forName("com.saferoom.natghost.NatAnalyzer")
                                    .getDeclaredMethod("handleIncomingPunchInstruction", ByteBuffer.class, SocketAddress.class);
                                method.setAccessible(true);
                                method.invoke(null, buf.duplicate(), from);
                                System.out.println("[KA] ✅ Punch instruction forwarded successfully");
                            } catch (Exception e) {
                                System.err.println("[KA] ❌ Error forwarding punch instruction: " + e.getMessage());
                                e.printStackTrace();
                            }
                        } else if (type == LLS.SIG_P2P_NOTIFY) {
                            System.out.printf("[KA] 📢 SIG_P2P_NOTIFY detected from %s - forwarding to NatAnalyzer%n", from);
                            // Forward P2P notification to NatAnalyzer for processing
                            try {
                                java.lang.reflect.Method method = Class.forName("com.saferoom.natghost.NatAnalyzer")
                                    .getDeclaredMethod("handleIncomingP2PNotification", ByteBuffer.class, SocketAddress.class);
                                method.setAccessible(true);
                                method.invoke(null, buf.duplicate(), from);
                                System.out.println("[KA] ✅ P2P notification forwarded successfully");
                            } catch (Exception e) {
                                System.err.println("[KA] ❌ Error forwarding P2P notification: " + e.getMessage());
                                e.printStackTrace();
                            }
                        } else if (type == LLS.SIG_DNS_QUERY) {
                            // Keep-alive DNS packet - ignore
                            System.out.printf("[KA] 🔄 Keep-alive DNS from %s (ignoring)%n", from);
                        } else {
                            System.out.printf("[KA] ❓ Unknown packet type 0x%02X from %s%n", type, from);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[KA] Message listener error: " + e.getMessage());
            }
            
            System.out.println("[KA] 📡 Message listener stopped");
        }, "KA-MessageListener");
        
        messageListenerThread.setDaemon(true);
        messageListenerThread.start();
    }

    @Override
    public void close() {
        listening = false;
        if (messageListenerThread != null) {
            messageListenerThread.interrupt();
        }
        tasks.values().forEach(f -> f.cancel(true));
        exec.shutdownNow();
    }
}
