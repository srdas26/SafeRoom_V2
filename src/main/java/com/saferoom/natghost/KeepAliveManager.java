package com.saferoom.natghost;

import java.io.IOException;
import java.net.InetAddress;
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
    private DatagramChannel activeChannel = null; // 🆕 Store channel reference for buffer flushing
    
    // 🔒 SERVER IP BLACKLIST - Never accept burst packets from signaling server!
    private static InetAddress SERVER_IP = null;
    static {
        try {
            SERVER_IP = InetAddress.getByName("35.198.64.68"); // Google Cloud signaling server
        } catch (Exception e) {
            System.err.println("⚠️ Failed to resolve server IP for blacklist: " + e.getMessage());
        }
    }

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
        // 🔧 FIX: Check if thread is actually alive, not just non-null
        if (listening && messageListenerThread != null && messageListenerThread.isAlive()) {
            System.out.println("[KA] ⚠️ Message listener already running");
            return; // Already listening
        }
        
        // Clean up dead thread if exists
        if (messageListenerThread != null && !messageListenerThread.isAlive()) {
            System.out.println("[KA] 🧹 Cleaning up dead message listener thread");
            messageListenerThread = null;
        }
        
        // Store channel reference for later buffer flushing
        this.activeChannel = channel;
        
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
                        ByteBuffer buf = ByteBuffer.allocate(2048); // Increased buffer
                        SocketAddress from = dc.receive(buf);
                        if (from == null) {
                            System.out.println("[KA] ⚠️ receive() returned null");
                            continue;
                        }
                        
                        buf.flip();
                        
                        System.out.printf("[KA] 📦 RAW packet received: %d bytes from %s%n", buf.remaining(), from);
                        
                        if (!LLS.hasWholeFrame(buf)) {
                            System.out.printf("[KA] ⚠️ Incomplete frame: %d bytes%n", buf.remaining());
                            continue;
                        }
                        byte type = LLS.peekType(buf);
                        
                        System.out.printf("[KA] 📡 Received packet type: 0x%02X from %s%n", type, from);
                        
                        if (type == LLS.SIG_MESSAGE) {
                            System.out.println("[KA] 🎯 SIG_MESSAGE detected - forwarding to NatAnalyzer");
                            
                            // 🔄 UPDATE PEER ADDRESS - Peer may have switched to different port!
                            try {
                                // Parse message to get sender username
                                ByteBuffer msgBuf = buf.duplicate();
                                msgBuf.get(); // Skip type
                                msgBuf.getShort(); // Skip length
                                byte[] senderBytes = new byte[20];
                                msgBuf.get(senderBytes);
                                String sender = new String(senderBytes).trim();
                                
                                // Update activePeers with new address
                                java.lang.reflect.Field activePeersField = Class.forName("com.saferoom.natghost.NatAnalyzer")
                                    .getDeclaredField("activePeers");
                                activePeersField.setAccessible(true);
                                @SuppressWarnings("unchecked")
                                Map<String, InetSocketAddress> activePeers = 
                                    (Map<String, InetSocketAddress>) activePeersField.get(null);
                                
                                InetSocketAddress oldAddr = activePeers.get(sender);
                                InetSocketAddress newAddr = (InetSocketAddress) from;
                                
                                if (oldAddr == null || !oldAddr.equals(newAddr)) {
                                    activePeers.put(sender, newAddr);
                                    System.out.printf("[KA-UPDATE] 🔄 Updated %s address: %s → %s%n", 
                                        sender, oldAddr, newAddr);
                                }
                                
                                // Update last activity
                                java.lang.reflect.Field lastActivityField = Class.forName("com.saferoom.natghost.NatAnalyzer")
                                    .getDeclaredField("lastActivity");
                                lastActivityField.setAccessible(true);
                                @SuppressWarnings("unchecked")
                                Map<String, Long> lastActivity = 
                                    (Map<String, Long>) lastActivityField.get(null);
                                lastActivity.put(sender, System.currentTimeMillis());
                                
                            } catch (Exception e) {
                                System.err.println("[KA] Warning: Could not update peer address: " + e.getMessage());
                            }
                            
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
                            // Keep-alive DNS packet - UPDATE PEER ADDRESS if needed
                            System.out.printf("[KA] 🔄 Keep-alive DNS from %s%n", from);
                            
                            // 🔄 Try to identify sender and update address
                            try {
                                java.lang.reflect.Field activePeersField = Class.forName("com.saferoom.natghost.NatAnalyzer")
                                    .getDeclaredField("activePeers");
                                activePeersField.setAccessible(true);
                                @SuppressWarnings("unchecked")
                                Map<String, InetSocketAddress> activePeers = 
                                    (Map<String, InetSocketAddress>) activePeersField.get(null);
                                
                                // Find which peer this address belongs to by IP
                                InetSocketAddress fromAddr = (InetSocketAddress) from;
                                for (Map.Entry<String, InetSocketAddress> entry : activePeers.entrySet()) {
                                    if (entry.getValue().getAddress().equals(fromAddr.getAddress())) {
                                        // Same IP, but port may have changed
                                        if (!entry.getValue().equals(fromAddr)) {
                                            InetSocketAddress oldAddr = entry.getValue();
                                            activePeers.put(entry.getKey(), fromAddr);
                                            System.out.printf("[KA-UPDATE] 🔄 Updated %s address: %s → %s (from DNS keepalive)%n", 
                                                entry.getKey(), oldAddr, fromAddr);
                                        }
                                        
                                        // Update last activity
                                        java.lang.reflect.Field lastActivityField = Class.forName("com.saferoom.natghost.NatAnalyzer")
                                            .getDeclaredField("lastActivity");
                                        lastActivityField.setAccessible(true);
                                        @SuppressWarnings("unchecked")
                                        Map<String, Long> lastActivity = 
                                            (Map<String, Long>) lastActivityField.get(null);
                                        lastActivity.put(entry.getKey(), System.currentTimeMillis());
                                        break;
                                    }
                                }
                            } catch (Exception e) {
                                System.err.println("[KA] Warning: Could not update peer from DNS: " + e.getMessage());
                            }
                        } else if (type == LLS.SIG_PUNCH_BURST) {
                            System.out.printf("[KA] 🎯 SIG_PUNCH_BURST detected from %s%n", from);
                            
                            // 🔒 BLACKLIST CHECK: NEVER accept burst packets from signaling server!
                            InetSocketAddress senderAddr = (InetSocketAddress) from;
                            if (SERVER_IP != null && senderAddr.getAddress().equals(SERVER_IP)) {
                                System.out.printf("[KA-BURST] ⛔ BLOCKED: Burst from SIGNALING SERVER %s - THIS SHOULD NEVER HAPPEN!%n", from);
                                System.out.println("[KA-BURST] ⛔ Server should COORDINATE, not send burst packets!");
                                continue; // Ignore server burst packets
                            }
                            
                            System.out.println("[KA-BURST] ✅ Source validated - AUTO-RESPONDING");
                            
                            // Auto-respond to burst packets to establish bidirectional NAT mapping
                            try {
                                // Parse burst packet to get usernames
                                java.util.List<Object> parsed = LLS.parseBurstPacket(buf.duplicate());
                                String senderUsername = (String) parsed.get(2);
                                String receiverUsername = (String) parsed.get(3);
                                String payload = (String) parsed.get(4);
                                
                                System.out.printf("[KA-BURST] Burst from %s -> %s: %s%n", 
                                    senderUsername, receiverUsername, payload);
                                
                                // Send immediate response to establish NAT hole
                                ByteBuffer response = LLS.New_Burst_Packet(
                                    receiverUsername,  // Me
                                    senderUsername,    // Them
                                    "BURST-ACK"
                                );
                                dc.send(response, (InetSocketAddress) from);
                                
                                System.out.printf("[KA-BURST] ✅ Auto-responded to %s - NAT hole established%n", 
                                    senderUsername);
                                
                                // Register peer in NatAnalyzer for messaging
                                try {
                                    java.lang.reflect.Field activePeersField = Class.forName("com.saferoom.natghost.NatAnalyzer")
                                        .getDeclaredField("activePeers");
                                    activePeersField.setAccessible(true);
                                    @SuppressWarnings("unchecked")
                                    Map<String, InetSocketAddress> activePeers = 
                                        (Map<String, InetSocketAddress>) activePeersField.get(null);
                                    activePeers.put(senderUsername, (InetSocketAddress) from);
                                    
                                    java.lang.reflect.Field lastActivityField = Class.forName("com.saferoom.natghost.NatAnalyzer")
                                        .getDeclaredField("lastActivity");
                                    lastActivityField.setAccessible(true);
                                    @SuppressWarnings("unchecked")
                                    Map<String, Long> lastActivity = 
                                        (Map<String, Long>) lastActivityField.get(null);
                                    lastActivity.put(senderUsername, System.currentTimeMillis());
                                    
                                    System.out.printf("[KA-BURST] 📝 Registered %s for P2P messaging%n", senderUsername);
                                } catch (Exception e) {
                                    System.err.println("[KA-BURST] Warning: Could not register peer: " + e.getMessage());
                                }
                                
                            } catch (Exception e) {
                                System.err.println("[KA-BURST] ❌ Error handling burst: " + e.getMessage());
                                e.printStackTrace();
                            }
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
    
    /**
     * Stop message listening (for temporary selector changes during hole punch)
     */
    public void stopMessageListening() {
        if (!listening) return;
        
        listening = false;
        if (messageListenerThread != null) {
            messageListenerThread.interrupt();
            try {
                messageListenerThread.join(1000); // Wait up to 1 second
            } catch (InterruptedException e) {
                // Ignore
            }
            messageListenerThread = null;
        }
        
        // ⚡ CRITICAL: Flush any pending packets in the channel buffer!
        // When KeepAliveManager stops, there might be stale SERVER packets (punch instructions)
        // that were already processed but still in UDP buffer. If executeStandardHolePunch
        // reads these old packets, it thinks server is responding during hole punch!
        if (activeChannel != null && activeChannel.isOpen()) {
            try {
                activeChannel.configureBlocking(false);
                ByteBuffer flushBuffer = ByteBuffer.allocate(2048);
                int flushedCount = 0;
                
                // Drain all pending packets from buffer
                while (activeChannel.receive(flushBuffer) != null) {
                    flushedCount++;
                    flushBuffer.clear();
                }
                
                if (flushedCount > 0) {
                    System.out.printf("[KA] 🧹 Flushed %d stale packet(s) from channel buffer%n", flushedCount);
                }
            } catch (Exception e) {
                System.err.println("[KA] ⚠️ Warning: Could not flush channel buffer: " + e.getMessage());
            }
        }
        
        System.out.println("[KA] 📡 Message listener stopped");
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
