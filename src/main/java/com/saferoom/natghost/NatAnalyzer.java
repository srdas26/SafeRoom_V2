package com.saferoom.natghost;

import com.saferoom.server.SafeRoomServer;

import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ClosedByInterruptException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.Enumeration;

public class NatAnalyzer {

    public static final List<Integer> Public_PortList = new ArrayList<>();
    public static String myPublicIP;
    public static byte   signal;
    
    // Keep the STUN channel open for hole punching and messaging
    private static DatagramChannel stunChannel = null;
    private static int localPort = 0;
    
    // Global KeepAliveManager instance (ONE per application)
    private static KeepAliveManager globalKeepAlive = null;
    
    // 🆕 Dedicated thread pool for P2P operations (prevents ForkJoinPool exhaustion)
    private static final ExecutorService P2P_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "P2P-Worker");
        t.setDaemon(true);
        return t;
    });
    
    // 🆕 P2P Connection Futures - for async coordination
    private static final Map<String, CompletableFuture<Boolean>> pendingP2PConnections = new ConcurrentHashMap<>();
    
    // 🆕 Active punch threads - prevent duplicate punch execution
    private static final Map<String, Thread> activePunchThreads = new ConcurrentHashMap<>();
    
    // Multiple peer connections support
    private static final Map<String, InetSocketAddress> activePeers = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastActivity = new ConcurrentHashMap<>();
    
    // 🆕 Pending file transfers - store filename from chat message
    private static final Map<String, String> pendingFileNames = new ConcurrentHashMap<>();
    
    // 🆕 Reliable Messaging components
    private static ReliableMessageSender reliableSender = null;
    private static ReliableMessageReceiver reliableReceiver = null;
    private static String currentUsername = null;
    
    // 🆕 Callback for received reliable messages
    @FunctionalInterface
    public interface ReliableMessageCallback {
        void onMessageReceived(String sender, String message);
    }
    private static ReliableMessageCallback messageCallback = null;
    
    // NAT profile data
    public static class NATProfile {
        public byte natType;
        public int minPort;
        public int maxPort;
        public int profiledPorts;
        public List<Integer> observedPorts;
        
        public NATProfile(byte natType, int minPort, int maxPort, int profiledPorts, List<Integer> observedPorts) {
            this.natType = natType;
            this.minPort = minPort;
            this.maxPort = maxPort;
            this.profiledPorts = profiledPorts;
            this.observedPorts = observedPorts;
        }
    }
    
    private static NATProfile cachedProfile = null;

    private static final SecureRandom RNG = new SecureRandom();
    public static final String[][] stunServers = {
            {"stun.l.google.com", "19302"},
            {"stun.l.google.com", "5349"},
            {"stun1.l.google.com", "3478"}
    };

    private static final long  STUN_TIMEOUT_MS    = 5_000;
    private static final long  HOLE_TIMEOUT_MS    = 10_000;
    private static final long  RESEND_INTERVAL_MS = 1_000;
    private static final long  SELECT_BLOCK_MS    = 50;

    public static ByteBuffer stunPacket() {
        ByteBuffer p = ByteBuffer.allocate(20);
        p.putShort((short) ((0x0001) & 0x3FFF));
        p.putShort((short) 0);
        p.putInt(0x2112A442);
        byte[] tid = new byte[12];
        RNG.nextBytes(tid);
        p.put(tid);
        p.flip();
        return p;
    }

    public static void parseStunResponse(ByteBuffer buffer, List<Integer> list) {
        System.out.println("[STUN] Parsing response, buffer size: " + buffer.remaining());
        
        if (buffer.remaining() < 20) {
            System.err.println("[STUN] Buffer too small for STUN header");
            return;
        }
        
        buffer.position(20);
        while (buffer.remaining() >= 4) {
            short attrType = buffer.getShort();
            short attrLen = buffer.getShort();
            System.out.printf("[STUN] Attribute: type=0x%04X, len=%d%n", attrType, attrLen);
            
            if (attrType == 0x0001 || attrType == 0x0020) { // MAPPED-ADDRESS or XOR-MAPPED-ADDRESS
                if (buffer.remaining() < attrLen) {
                    System.err.printf("[STUN] Not enough bytes for attribute 0x%04X%n", attrType);
                    break;
                }
                buffer.get(); // ignore
                buffer.get(); // family
                int port = buffer.getShort() & 0xFFFF;
                byte[] addrBytes = new byte[4];
                buffer.get(addrBytes);
                
                if (attrType == 0x0020) { // XOR-MAPPED-ADDRESS - need to XOR with magic cookie
                    // XOR port with upper 16 bits of magic cookie (0x2112)
                    port ^= 0x2112;
                    // XOR IP with magic cookie (0x2112A442)
                    int magicCookie = 0x2112A442;
                    for (int i = 0; i < 4; i++) {
                        addrBytes[i] ^= (magicCookie >> (24 - i * 8)) & 0xFF;
                    }
                }
                
                String ip = (addrBytes[0] & 0xFF) + "." + (addrBytes[1] & 0xFF) + "." +
                            (addrBytes[2] & 0xFF) + "." + (addrBytes[3] & 0xFF);
                myPublicIP = ip;
                list.add(port);
                System.out.printf("[STUN] Parsed (type=0x%04X): IP=%s, Port=%d%n", attrType, ip, port);
            } else {
                // Skip unknown attributes
                if (buffer.remaining() >= attrLen) {
                    buffer.position(buffer.position() + attrLen);
                } else {
                    System.err.printf("[STUN] Not enough bytes to skip attribute (need %d, have %d)%n", 
                        attrLen, buffer.remaining());
                    break;
                }
            }
        }
    }

    private static <T> boolean allEqual(List<T> list) {
        if (list.isEmpty()) return true;
        T f = list.get(0);
        for (int i = 1; i < list.size(); i++)
            if (!Objects.equals(f, list.get(i))) return false;
        return true;
    }
    
    /**
     * Get real local IP address (not localhost/loopback/docker/vm)
     */
    private static InetAddress getRealLocalIP() throws Exception {
        InetAddress bestCandidate = null;
        
        // Try to find the best network interface
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            
            // Skip loopback and inactive interfaces
            if (ni.isLoopback() || !ni.isUp()) continue;
            
            String ifName = ni.getName().toLowerCase();
            String displayName = ni.getDisplayName().toLowerCase();
            
            // Skip Docker, VM, and virtual interfaces
            if (ifName.contains("docker") || ifName.contains("veth") || ifName.contains("br-") ||
                ifName.contains("virbr") || ifName.contains("vmnet") || ifName.contains("vbox") ||
                displayName.contains("docker") || displayName.contains("virtual")) {
                System.out.printf("[NAT] Skipping virtual interface: %s (%s)%n", ifName, displayName);
                continue;
            }
            
            Enumeration<InetAddress> addresses = ni.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                
                // Skip IPv6 and loopback addresses
                if (addr instanceof Inet6Address || addr.isLoopbackAddress()) continue;
                
                String ip = addr.getHostAddress();
                
                // Skip Docker/VM networks
                if (ip.startsWith("172.17.") || ip.startsWith("172.18.") || ip.startsWith("172.19.") ||
                    ip.startsWith("172.20.") || ip.startsWith("10.0.2.") || ip.startsWith("169.254.")) {
                    System.out.printf("[NAT] Skipping virtual network IP: %s%n", ip);
                    continue;
                }
                
                // Prefer 192.168.x.x (home networks) or 10.x.x.x (corporate)
                if (ip.startsWith("192.168.") || ip.startsWith("10.")) {
                    System.out.printf("[NAT] ✅ Found preferred local IP: %s (interface: %s)%n", 
                        ip, ni.getDisplayName());
                    return addr;
                }
                
                // Keep as backup candidate
                if (bestCandidate == null) {
                    bestCandidate = addr;
                    System.out.printf("[NAT] Found candidate local IP: %s (interface: %s)%n", 
                        ip, ni.getDisplayName());
                }
            }
        }
        
        // Use best candidate if found
        if (bestCandidate != null) {
            System.out.printf("[NAT] Using candidate local IP: %s%n", bestCandidate.getHostAddress());
            return bestCandidate;
        }
        
        // Fallback to localhost if nothing found
        System.err.println("[NAT] ⚠️ Could not find real local IP, using localhost");
        return InetAddress.getLocalHost();
    }

    /**
     * Modern single-port NAT analysis - tests if NAT is symmetric
     * CRITICAL: Keeps the channel OPEN for hole punching!
     * Returns: 0x00 = Full Cone/Restricted, 0x11 = Symmetric, 0xFE = Error
     */
    public static byte analyzeSinglePort(String[][] servers) throws Exception {
        System.out.println("[NAT] Starting single-port NAT analysis...");
        
        // Close previous channel if exists and create new one
        if (stunChannel != null) {
            try { 
                stunChannel.close(); 
                System.out.println("[NAT] Closed previous STUN channel");
            } catch (Exception ignored) {}
            stunChannel = null;
        }
        
        Selector selector = Selector.open();
        stunChannel = DatagramChannel.open(); // KEEP THIS OPEN!
        stunChannel.configureBlocking(false);
        stunChannel.bind(new InetSocketAddress(0));
        stunChannel.register(selector, SelectionKey.OP_READ);
        
        InetSocketAddress localAddr = (InetSocketAddress) stunChannel.getLocalAddress();
        localPort = localAddr.getPort();
        System.out.println("[NAT] Bound to local port: " + localPort + " (KEEPING OPEN FOR HOLE PUNCH)");

        // Send STUN requests to multiple servers in parallel
        int sentCount = 0;
        for (String[] server : servers) {
            try {
                InetAddress.getByName(server[0]);
                stunChannel.send(stunPacket().duplicate(), 
                    new InetSocketAddress(server[0], Integer.parseInt(server[1])));
                sentCount++;
                System.out.println("[NAT] STUN request sent to " + server[0] + ":" + server[1]);
            } catch (UnknownHostException e) {
                System.err.println("[NAT] Invalid STUN server: " + server[0]);
            }
        }

        if (sentCount == 0) {
            System.err.println("[NAT] No valid STUN servers found");
            stunChannel.close();
            stunChannel = null;
            selector.close();
            return (byte)0xFE;
        }

        // Wait for responses
        long deadline = System.currentTimeMillis() + STUN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && Public_PortList.size() < sentCount) {
            if (selector.select(SELECT_BLOCK_MS) == 0) continue;
            
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next(); 
                it.remove();
                
                if (!key.isReadable()) continue;
                
                DatagramChannel rc = (DatagramChannel) key.channel();
                ByteBuffer recv = ByteBuffer.allocate(512);
                SocketAddress from = rc.receive(recv);
                if (from == null) continue;
                
                recv.flip();
                System.out.printf("[NAT] STUN response received from %s, buffer size: %d%n", from, recv.remaining());
                
                // Debug: Show first few bytes of response
                if (recv.remaining() >= 20) {
                    recv.mark();
                    System.out.printf("[NAT] STUN header: %02X %02X %02X %02X%n", 
                        recv.get(), recv.get(), recv.get(), recv.get());
                    recv.reset();
                }
                
                parseStunResponse(recv, Public_PortList);
                System.out.printf("[NAT] After parsing - Public_PortList size: %d%n", Public_PortList.size());
            }
        }

        // DON'T close the channel! Keep it for hole punching
        selector.close();
        System.out.println("[NAT] STUN analysis complete - Channel remains OPEN for hole punch");

        // Analyze results
        int totalResponses = Public_PortList.size(); // Save original count before dedup
        List<Integer> uniquePorts = new ArrayList<>(new LinkedHashSet<>(Public_PortList));
        Public_PortList.clear();
        Public_PortList.addAll(uniquePorts);

        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("[NAT-DETECT] 🔍 NAT Type Detection Analysis:");
        System.out.println("[NAT-DETECT] Sent STUN queries: " + sentCount);
        System.out.println("[NAT-DETECT] Received responses: " + totalResponses);
        System.out.println("[NAT-DETECT] Unique ports count: " + uniquePorts.size());
        System.out.println("[NAT-DETECT] Unique ports list: " + uniquePorts);
        System.out.println("[NAT-DETECT] Public IP: " + myPublicIP);

        if (uniquePorts.isEmpty()) {
            System.err.println("[NAT-DETECT] ❌ No STUN responses received - network error");
            signal = (byte)0xFE;
        } else if (uniquePorts.size() == 1) {
            System.out.println("[NAT-DETECT] ✅ All " + totalResponses + " responses used SAME port (" + uniquePorts.get(0) + ")");
            System.out.println("[NAT-DETECT] → NAT Type: NON-SYMMETRIC (Full Cone or Restricted)");
            signal = (byte)0x00;
        } else {
            System.out.println("[NAT-DETECT] ⚠️ Different ports detected across " + totalResponses + " responses");
            System.out.println("[NAT-DETECT] → NAT Type: SYMMETRIC (port per destination)");
            signal = (byte)0x11;
        }
        System.out.println("[NAT-DETECT] Final Signal: 0x" + String.format("%02X", signal));
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // 🆕 Cache basic profile for later profiling (avoids re-running detection)
        if (!uniquePorts.isEmpty()) {
            if (signal == 0x00) {
                // Non-Symmetric: Single stable port (minPort = maxPort)
                int port = uniquePorts.get(0);
                cachedProfile = new NATProfile(signal, port, port, 1, uniquePorts);
                System.out.println("[NAT-DETECT] 📝 Cached NON-SYMMETRIC profile (single port: " + port + ")");
            } else if (signal == 0x11) {
                // Symmetric: Multiple ports detected, cache min/max range for now
                int minPort = Collections.min(uniquePorts);
                int maxPort = Collections.max(uniquePorts);
                cachedProfile = new NATProfile(signal, minPort, maxPort, uniquePorts.size(), uniquePorts);
                System.out.println("[NAT-DETECT] 📝 Cached SYMMETRIC basic profile (range: " + minPort + "-" + maxPort + ")");
                System.out.println("[NAT-DETECT] ⚠️ Deep profiling recommended for accurate range!");
            }
        }
        
        return signal;
    }
    
    // Legacy method for backward compatibility
    public static byte analyzer(String[][] servers) throws Exception {
        return analyzeSinglePort(servers);
    }

    /**
     * Modern single-port hole punching - FIXED to use same channel
     * 1. Analyze NAT type with single port (keeps channel open)
     * 2. Use SAME channel to send hole punch request
     * 3. Receive peer info and start DNS packet exchange
     */
    public static boolean performHolePunch(String myUsername, String targetUsername, 
                                          InetSocketAddress signalingServer) throws Exception {
        System.out.println("[P2P] Starting hole punch: " + myUsername + " -> " + targetUsername);
        
        // Step 1: NAT Analysis with single port (keeps channel open!)
        byte natType = analyzeSinglePort(stunServers);
        if (natType == (byte)0xFE) {
            System.err.println("[P2P] NAT analysis failed");
            return false;
        }
        
        if (myPublicIP == null || Public_PortList.isEmpty() || stunChannel == null) {
            System.err.println("[P2P] No public IP/port discovered or channel closed");
            return false;
        }
        
        int myPublicPort = Public_PortList.get(0);
        System.out.println("[P2P] My public endpoint: " + myPublicIP + ":" + myPublicPort);
        System.out.println("[P2P] Using SAME channel from STUN analysis (local port: " + localPort + ")");
        
        // Get real local IP address (not localhost)
        InetAddress localIP = getRealLocalIP();
        System.out.println("[P2P] My local endpoint: " + localIP.getHostAddress() + ":" + localPort);
        
        // Step 2: Send extended hole punch request with both public and local info
        ByteBuffer holeRequest = LLS.New_Extended_Hole_Packet(myUsername, targetUsername, 
                                                             InetAddress.getByName(myPublicIP), myPublicPort,
                                                             localIP, localPort);
        stunChannel.send(holeRequest, signalingServer);
        System.out.println("[P2P] Extended hole punch request sent (public + local) using local port: " + localPort);
        
        // Step 3: Wait for peer info from signaling server
        Selector peerSelector = Selector.open();
        stunChannel.register(peerSelector, SelectionKey.OP_READ);
        
        long deadline = System.currentTimeMillis() + HOLE_TIMEOUT_MS;
        InetAddress peerIP = null;
        int peerPort = 0;
        boolean peerInfoReceived = false;
        
        while (System.currentTimeMillis() < deadline && !peerInfoReceived) {
            if (peerSelector.select(SELECT_BLOCK_MS) == 0) continue;
            
            Iterator<SelectionKey> it = peerSelector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
                
                if (!key.isReadable()) continue;
                
                DatagramChannel dc = (DatagramChannel) key.channel();
                ByteBuffer buf = ByteBuffer.allocate(512);
                SocketAddress from = dc.receive(buf);
                if (from == null) continue;
                buf.flip();
                
                if (!LLS.hasWholeFrame(buf)) continue;
                byte type = LLS.peekType(buf);
                
                if (type == LLS.SIG_PORT) {
                    List<Object> info = LLS.parsePortInfo(buf.duplicate());
                    peerIP = (InetAddress) info.get(0);
                    peerPort = (Integer) info.get(1);
                    peerInfoReceived = true;
                    System.out.println("[P2P] Peer info received: " + peerIP + ":" + peerPort);
                    break;
                }
            }
        }
        
        if (!peerInfoReceived) {
            System.err.println("[P2P] Timeout waiting for peer info");
            if (stunChannel != null) {
                try {
                    stunChannel.close();
                } catch (Exception e) {
                    System.err.println("[P2P] Error closing stunChannel: " + e.getMessage());
                }
                stunChannel = null;
            }
            peerSelector.close();
            return false;
        }
        
        // Step 4: Start DNS packet exchange using SAME channel WITH response listening
        InetSocketAddress peerAddr = new InetSocketAddress(peerIP, peerPort);
        System.out.println("[P2P] Starting 10-second STUN Binding burst with response listening on port: " + localPort);
        
        // Setup selector for concurrent burst + response listening
        Selector burstSelector = Selector.open();
        stunChannel.register(burstSelector, SelectionKey.OP_READ);
        
        long burstStart = System.currentTimeMillis();
        long burstDuration = 10000; // 10 seconds - much longer for hole punch
        long burstInterval = 100;    // Every 100ms
        long lastSend = 0;
        int packetCount = 0;
        boolean responseReceived = false;
        
        // CONCURRENT: Send STUN Binding burst + Listen for response (ICE-like approach)
        while ((System.currentTimeMillis() - burstStart) < burstDuration && !responseReceived) {
            // Send STUN Binding packet if it's time
            if ((System.currentTimeMillis() - lastSend) >= burstInterval) {
                ByteBuffer stunBinding = stunPacket(); // Use STUN Binding Request instead of DNS
                stunChannel.send(stunBinding, peerAddr);
                packetCount++;
                System.out.println("[P2P] STUN Binding burst #" + packetCount + " sent to " + peerAddr + " from port " + localPort);
                lastSend = System.currentTimeMillis();
            }
            
            // Check for response (non-blocking)
            if (burstSelector.selectNow() > 0) {
                Iterator<SelectionKey> it = burstSelector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    
                    if (!key.isReadable()) continue;
                    
                    DatagramChannel dc = (DatagramChannel) key.channel();
                    ByteBuffer buf = ByteBuffer.allocate(1024);
                    SocketAddress from = dc.receive(buf);
                    if (from == null) continue;
                    
                    buf.flip();
                    System.out.printf("[P2P] 📥 Response received during burst from %s - hole punch confirmed!%n", from);
                    responseReceived = true;
                    break;
                }
            }
            
            Thread.sleep(10); // Small sleep to prevent busy loop
        }
        
        burstSelector.close();
        
        System.out.println("[P2P] STUN Binding burst complete: " + packetCount + " packets sent over " + (System.currentTimeMillis() - burstStart) + "ms");
        
        // Check if response was received during burst
        if (!responseReceived) {
            System.err.println("[P2P] ❌ No STUN response received during burst - hole punch failed");
            if (stunChannel != null) {
                try {
                    stunChannel.close();
                } catch (Exception e) {}
                stunChannel = null;
            }
            return false;
        }
        
        // Initialize global KeepAliveManager ONCE if not already started
        if (globalKeepAlive == null) {
            System.out.println("[P2P] 🔧 Initializing global KeepAliveManager");
            globalKeepAlive = new KeepAliveManager(20_000); // 20 seconds keep-alive
            globalKeepAlive.installShutdownHook();
            globalKeepAlive.startMessageListening(stunChannel); // Start integrated message listening
        }
        
        // Register this peer with global KeepAliveManager
        globalKeepAlive.register(stunChannel, peerAddr);
        
        peerSelector.close();
        
        // Store peer address for messaging (multiple peer support)
        activePeers.put(targetUsername, peerAddr);
        lastActivity.put(targetUsername, System.currentTimeMillis());
        
        System.out.println("[P2P] ✅ Hole punch confirmed! P2P connection established with " + targetUsername);
        System.out.println("[P2P] Connection details: Local:" + localPort + " -> Peer:" + peerAddr);
        System.out.println("[P2P] 🎉 P2P messaging now available with " + targetUsername + "!");
        return true;
    }
    
    // Legacy method - now requires parameters
    public static void multiplexer(InetSocketAddress serverAddr, String myUsername, String targetUsername) throws Exception {
        if (myUsername == null || targetUsername == null) {
            throw new IllegalStateException("Username/Target null!");
        }
        
        boolean success = performHolePunch(myUsername, targetUsername, serverAddr);
        if (!success) {
            System.err.println("[P2P] Hole punch failed - falling back to server relay");
        }
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: NatAnalyzer <myUsername> <targetUsername>");
            return;
        }
        
        InetSocketAddress serverAddr =
                new InetSocketAddress(SafeRoomServer.ServerIP, SafeRoomServer.udpPort1);
        try {
            multiplexer(serverAddr, args[0], args[1]);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // ============================================
    // USER REGISTRATION AND UNIDIRECTIONAL P2P
    // ============================================
    
    /**
     * Register user with signaling server on application startup
     * Performs STUN discovery and sends registration packet to server
     * @param username Username to register
     * @param signalingServer Signaling server address
     * @return true if registration successful
     */
    public static boolean registerWithServer(String username, InetSocketAddress signalingServer) {
        try {
            System.out.println("[P2P] Registering user: " + username);
            
            // Step 1: Perform STUN discovery to get NAT info
            byte natType = analyzeSinglePort(stunServers);
            if (natType == (byte)0xFE) {
                System.err.println("[P2P] NAT analysis failed during registration");
                return false;
            }
            
            if (myPublicIP == null || Public_PortList.isEmpty() || stunChannel == null) {
                System.err.println("[P2P] No public IP/port discovered during registration");
                return false;
            }
            
            int myPublicPort = Public_PortList.get(0);
            InetAddress localIP = getRealLocalIP();
            
            System.out.printf("[P2P] Registration info - Public: %s:%d, Local: %s:%d%n", 
                myPublicIP, myPublicPort, localIP.getHostAddress(), localPort);
            
            // Step 2: Send registration packet to server
            ByteBuffer registerPacket = LLS.New_Register_Packet(username, 
                InetAddress.getByName(myPublicIP), myPublicPort, localIP, localPort);
            stunChannel.send(registerPacket, signalingServer);
            
            System.out.println("[P2P] Registration packet sent to server");
            
            // Step 3: Wait for acknowledgment from server
            // CRITICAL: Ensure channel is non-blocking and properly registered
            stunChannel.configureBlocking(false);
            Selector regSelector = Selector.open();
            stunChannel.register(regSelector, SelectionKey.OP_READ);
            
            long deadline = System.currentTimeMillis() + 5000; // 5 second timeout
            boolean ackReceived = false;
            
            System.out.println("[P2P] ⏳ Waiting for registration ACK from server...");
            
            while (System.currentTimeMillis() < deadline && !ackReceived) {
                if (regSelector.select(500) == 0) continue; // 500ms wait
                
                Iterator<SelectionKey> it = regSelector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    
                    if (!key.isReadable()) continue;
                    
                    DatagramChannel dc = (DatagramChannel) key.channel();
                    ByteBuffer buf = ByteBuffer.allocate(512);
                    SocketAddress from = dc.receive(buf);
                    if (from == null) continue;
                    
                    buf.flip();
                    
                    System.out.printf("[P2P] 📦 Received packet during registration wait (size: %d, from: %s)%n", 
                        buf.remaining(), from);
                    
                    if (!LLS.hasWholeFrame(buf)) {
                        System.err.println("[P2P] ⚠️ Incomplete frame, skipping");
                        continue;
                    }
                    
                    byte type = LLS.peekType(buf);
                    System.out.printf("[P2P] 📨 Packet type: 0x%02X%n", type);
                    
                    if (type == LLS.SIG_ALL_DONE) {
                        ackReceived = true;
                        System.out.println("[P2P] ✅ Registration acknowledged by server");
                        break;
                    } else {
                        System.out.printf("[P2P] ⚠️ Unexpected packet type during registration: 0x%02X (expected SIG_ALL_DONE=0x%02X)%n", 
                            type, LLS.SIG_ALL_DONE);
                    }
                }
            }
            
            regSelector.close();
            
            if (!ackReceived) {
                System.err.println("[P2P] Registration timeout - no acknowledgment from server");
                return false;
            }
            
            // CRITICAL: Start listening for incoming P2P notifications after registration
            // 🔧 FIX: Use globalKeepAlive so executeStandardHolePunch() uses SAME channel!
            System.out.println("[P2P] 🎧 Starting notification listener after registration...");
            if (globalKeepAlive == null) {
                globalKeepAlive = new KeepAliveManager(20_000); // 20 seconds keep-alive
                globalKeepAlive.installShutdownHook();
                globalKeepAlive.startMessageListening(stunChannel);
                System.out.println("[P2P] 📡 Global KeepAliveManager started - ready for incoming P2P requests");
            } else {
                System.out.println("[P2P] 📡 Global KeepAliveManager already active");
            }
            
            System.out.println("[P2P] ✅ User registration complete: " + username);
            return true;
            
        } catch (Exception e) {
            System.err.println("[P2P] Registration error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * 🆕 ASYNC NON-BLOCKING P2P Connection Request
     * Returns CompletableFuture instead of blocking with get()
     * 
     * @param myUsername Current user's username
     * @param targetUsername Target user to connect to
     * @param signalingServer Signaling server address
     * @return CompletableFuture<Boolean> that completes when P2P establishes (or times out)
     */
    public static CompletableFuture<Boolean> requestP2PConnectionAsync(String myUsername, String targetUsername, 
                                             InetSocketAddress signalingServer) {
        try {
            System.out.printf("[P2P] 🚀 Requesting P2P connection (ASYNC): %s -> %s%n", myUsername, targetUsername);
            
            // Check if already connected
            if (activePeers.containsKey(targetUsername)) {
                System.out.println("[P2P] ✅ Already connected to " + targetUsername);
                return CompletableFuture.completedFuture(true);
            }
            
            // Check if request already in progress
            CompletableFuture<Boolean> existingFuture = pendingP2PConnections.get(targetUsername);
            if (existingFuture != null && !existingFuture.isDone()) {
                System.out.println("[P2P] ⏳ P2P request already in progress for " + targetUsername);
                return existingFuture;
            }
            
            // Ensure we have an active channel
            if (stunChannel == null || !stunChannel.isOpen()) {
                System.err.println("[P2P] ❌ No active channel - user not registered?");
                return CompletableFuture.completedFuture(false);
            }
            
            // Create a CompletableFuture for this connection
            CompletableFuture<Boolean> connectionFuture = new CompletableFuture<>();
            pendingP2PConnections.put(targetUsername, connectionFuture);
            
            // Send P2P request to server
            ByteBuffer requestPacket = LLS.New_P2PRequest_Packet(myUsername, targetUsername);
            stunChannel.send(requestPacket, signalingServer);
            System.out.println("[P2P] 📤 P2P connection request sent to server");
            
            // Set timeout on the future (completes after 10s if not resolved)
            CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS, P2P_EXECUTOR).execute(() -> {
                if (!connectionFuture.isDone()) {
                    System.out.println("[P2P] ⏰ Connection timeout for " + targetUsername);
                    pendingP2PConnections.remove(targetUsername);
                    
                    // Check if peer registered anyway (late response)
                    boolean connected = activePeers.containsKey(targetUsername);
                    connectionFuture.complete(connected);
                }
            });
            
            return connectionFuture;
            
        } catch (Exception e) {
            System.err.printf("[P2P] ❌ P2P request error: %s%n", e.getMessage());
            e.printStackTrace();
            pendingP2PConnections.remove(targetUsername);
            return CompletableFuture.completedFuture(false);
        }
    }
    
    /**
     * 🔴 DEPRECATED BLOCKING VERSION - Use requestP2PConnectionAsync() instead
     * This method causes ForkJoinPool exhaustion when called from async context
     * 
     * @deprecated Use {@link #requestP2PConnectionAsync(String, String, InetSocketAddress)} instead
     */
    @Deprecated
    public static boolean requestP2PConnection(String myUsername, String targetUsername, 
                                             InetSocketAddress signalingServer) {
        try {
            System.out.println("[P2P] ⚠️ Using deprecated BLOCKING P2P request - consider using async version");
            
            // Ensure we have an active channel (from registration)
            if (stunChannel == null || !stunChannel.isOpen()) {
                System.err.println("[P2P] No active channel - user not registered?");
                return false;
            }
            
            // Delegate to async version but BLOCK on our own executor (not ForkJoinPool)
            CompletableFuture<Boolean> future = requestP2PConnectionAsync(myUsername, targetUsername, signalingServer);
            
            // Block with timeout - but at least we're using our dedicated pool
            return future.get(10, TimeUnit.SECONDS);
            
        } catch (TimeoutException e) {
            System.out.println("[P2P] ⏰ Blocking request timeout");
            return activePeers.containsKey(targetUsername);
        } catch (Exception e) {
            System.err.printf("[P2P] ❌ Blocking request error: %s%n", e.getMessage());
            return false;
        }
    }
    
    /**
     * Handle incoming P2P notification from server
     * Called when another user wants to establish P2P connection
     */
    public static void handleIncomingP2PNotification(ByteBuffer buf, SocketAddress from) {
        try {
            System.out.printf("[P2P] 🔔 INCOMING P2P NOTIFICATION from %s (buffer size: %d)%n", from, buf.remaining());
            
            List<Object> parsed = LLS.parseP2PNotifyPacket(buf);
            String requester = (String) parsed.get(2);
            String target = (String) parsed.get(3);
            InetAddress requesterPublicIP = (InetAddress) parsed.get(4);
            int requesterPublicPort = (Integer) parsed.get(5);
            InetAddress requesterLocalIP = (InetAddress) parsed.get(6);
            int requesterLocalPort = (Integer) parsed.get(7);
            
            System.out.printf("[P2P] 📢 P2P NOTIFICATION PARSED: %s wants to connect to %s%n", requester, target);
            System.out.printf("[P2P] Requester info - Public: %s:%d, Local: %s:%d%n", 
                requesterPublicIP.getHostAddress(), requesterPublicPort,
                requesterLocalIP.getHostAddress(), requesterLocalPort);
            
            // Choose appropriate IP based on NAT situation
            InetAddress targetIP;
            int targetPort;
            
            // Simple same-NAT detection - compare with our public IP
            if (myPublicIP != null && requesterPublicIP.getHostAddress().equals(myPublicIP)) {
                // Same NAT - use local IP
                targetIP = requesterLocalIP;
                targetPort = requesterLocalPort;
                System.out.println("[P2P] Same NAT detected - using local IP for connection");
            } else {
                // Different NAT - use public IP
                targetIP = requesterPublicIP;
                targetPort = requesterPublicPort;
                System.out.println("[P2P] Different NAT detected - using public IP for connection");
            }
            
            // Start hole punching to requester
            InetSocketAddress requesterAddr = new InetSocketAddress(targetIP, targetPort);
            boolean success = performDirectHolePunching(requesterAddr, requester);
            
            if (success) {
                System.out.printf("[P2P] ✅ P2P connection established with %s (incoming request)%n", requester);
                
                // Notify GUI about new P2P connection and switch to Messages
                try {
                    javafx.application.Platform.runLater(() -> {
                        try {
                            // First, switch to Messages tab in MainController
                            com.saferoom.gui.controller.MainController mainController = 
                                com.saferoom.gui.controller.MainController.getInstance();
                            if (mainController != null) {
                                System.out.printf("[P2P] 📱 Switching to Messages tab for incoming P2P from %s%n", requester);
                                mainController.switchToMessages(); // This method needs to be added
                            }
                            
                            // Then open chat with the requester (no bot message)
                            System.out.printf("[P2P] 💬 Opening chat with requester: %s%n", requester);
                            com.saferoom.gui.controller.MessagesController.openChatWithUser(requester);
                                
                        } catch (Exception e) {
                            System.err.println("[P2P] Error in GUI notification: " + e.getMessage());
                            e.printStackTrace();
                        }
                    });
                } catch (Exception e) {
                    System.err.println("[P2P] Error notifying GUI: " + e.getMessage());
                }
            } else {
                System.err.printf("[P2P] ❌ Failed to establish P2P connection with %s%n", requester);
            }
            
        } catch (Exception e) {
            System.err.printf("[P2P] Error handling P2P notification from %s: %s%n", from, e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Handle incoming punch instruction from server (target side).
     * This is called when another peer initiates a P2P connection and the server
     * sends intelligent punch instructions to BOTH peers simultaneously.
     * 
     * This method:
     * 1. Parses the instruction (strategy, target address, numPorts)
     * 2. Executes the coordinated strategy
     * 3. Handles GUI updates (switch to Messages, open chat)
     * 
     * @param buf Buffer containing SIG_PUNCH_INSTRUCT packet
     * @param from Server address that sent the instruction
     */
    public static void handleIncomingPunchInstruction(ByteBuffer buf, SocketAddress from) {
        try {
            System.out.printf("[P2P-INCOMING] 🧠 Received punch instruction from server %s%n", from);
            
            List<Object> parsed = LLS.parsePunchInstructPacket(buf);
            String username = (String) parsed.get(2);
            String target = (String) parsed.get(3);
            InetAddress targetIP = (InetAddress) parsed.get(4);
            int targetPort = (Integer) parsed.get(5);
            byte strategy = (Byte) parsed.get(6);
            int numPorts = (Integer) parsed.get(7);
            
            System.out.printf("[P2P-INCOMING] 📨 Punch instruction parsed:%n");
            System.out.printf("  Requester: %s → Me: %s%n", target, username);
            System.out.printf("  Requester IP: %s:%d%n", targetIP.getHostAddress(), targetPort);
            System.out.printf("  Strategy: 0x%02X, Ports: %d%n", strategy, numPorts);
            
            // 🆕 Check if punch already in progress for this target
            Thread existingThread = activePunchThreads.get(target);
            if (existingThread != null && existingThread.isAlive()) {
                System.out.printf("[P2P-INCOMING] ⏳ Punch already in progress for %s - ignoring duplicate instruction%n", target);
                return;
            }
            
            // 🆕 Check if already connected
            if (activePeers.containsKey(target)) {
                System.out.printf("[P2P-INCOMING] ✅ Already connected to %s - ignoring duplicate instruction%n", target);
                return;
            }
            
            // 🆕 IMMEDIATELY notify GUI about incoming P2P request (don't wait for punch to complete!)
            javafx.application.Platform.runLater(() -> {
                try {
                    // First, switch to Messages tab
                    com.saferoom.gui.controller.MainController mainController = 
                        com.saferoom.gui.controller.MainController.getInstance();
                    if (mainController != null) {
                        System.out.printf("[P2P-INCOMING] 📱 Switching to Messages tab for incoming P2P from %s%n", target);
                        mainController.switchToMessages();
                    }
                    
                    // Then open chat with the requester
                    System.out.printf("[P2P-INCOMING] 💬 Opening chat with requester: %s%n", target);
                    com.saferoom.gui.controller.MessagesController.openChatWithUser(target);
                } catch (Exception e) {
                    System.err.printf("[P2P-INCOMING] ❌ GUI notification error: %s%n", e.getMessage());
                }
            });
            
            // Execute the coordinated strategy asynchronously
            Thread punchThread = new Thread(() -> {
                try {
                    switch (strategy) {
                        case 0x00 -> {
                            // STANDARD: Basic hole punch
                            System.out.println("[P2P-INCOMING] ⚡ Executing STANDARD hole punch");
                            executeStandardHolePunch(targetIP, targetPort, username, target);
                        }
                        case 0x01 -> {
                            // SYMMETRIC_BURST: Open port pool and burst
                            System.out.println("[P2P-INCOMING] 🔥 Executing SYMMETRIC BURST strategy");
                            System.out.printf("  Opening %d ports, bursting to %s:%d%n", 
                                numPorts, targetIP.getHostAddress(), targetPort);
                            symmetricPortPoolExpansion(targetIP, targetPort, numPorts, username, target);
                        }
                        case 0x02 -> {
                            // ASYMMETRIC_SCAN: Scan port range
                            System.out.println("[P2P-INCOMING] 🔍 Executing ASYMMETRIC SCAN strategy");
                            int maxPort = targetPort + numPorts - 1;
                            System.out.printf("  Scanning port range %d-%d on %s%n", 
                                targetPort, maxPort, targetIP.getHostAddress());
                            scanPortRange(targetIP, targetPort, maxPort, username, target);
                        }
                        case 0x03 -> {
                            // SYMMETRIC_MIDPOINT_BURST: Birthday Paradox
                            System.out.println("[P2P-INCOMING] 🎯 Executing SYMMETRIC MIDPOINT BURST (Birthday Paradox)");
                            System.out.printf("  Opening %d ports, bursting to peer's midpoint %s:%d%n", 
                                numPorts, targetIP.getHostAddress(), targetPort);
                            symmetricMidpointBurst(targetIP, targetPort, numPorts, username, target);
                        }
                        default -> {
                            System.err.printf("[P2P-INCOMING] ❌ Unknown strategy: 0x%02X%n", strategy);
                            return;
                        }
                    }
                    
                    System.out.printf("[P2P-INCOMING] ✅ Connection established with %s (incoming request)%n", target);
                    
                } catch (Exception e) {
                    System.err.printf("[P2P-INCOMING] ❌ Strategy execution failed: %s%n", e.getMessage());
                    e.printStackTrace();
                }
                
                // 🆕 Cleanup: remove from active punch threads
                finally {
                    activePunchThreads.remove(target);
                    System.out.printf("[P2P-INCOMING] 🧹 Removed punch thread for %s%n", target);
                }
            }, "IncomingPunch-" + target);
            
            // 🆕 Register thread before starting
            activePunchThreads.put(target, punchThread);
            
            punchThread.setDaemon(true);
            punchThread.start();
            
        } catch (Exception e) {
            System.err.printf("[P2P-INCOMING] ❌ Error handling punch instruction: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Perform direct hole punching to specific peer address
     */
    private static boolean performDirectHolePunching(InetSocketAddress peerAddr, String targetUsername) {
        try {
            System.out.printf("[P2P] Starting direct hole punching to %s%n", peerAddr);
            
            // Setup selector for concurrent burst + response listening
            Selector burstSelector = Selector.open();
            stunChannel.register(burstSelector, SelectionKey.OP_READ);
            
            long burstStart = System.currentTimeMillis();
            long burstDuration = 10000; // 10 seconds
            long burstInterval = 100;    // Every 100ms
            long lastSend = 0;
            int packetCount = 0;
            boolean responseReceived = false;
            
            // CONCURRENT: Send STUN Binding burst + Listen for response
            while ((System.currentTimeMillis() - burstStart) < burstDuration && !responseReceived) {
                // Send STUN Binding packet if it's time
                if ((System.currentTimeMillis() - lastSend) >= burstInterval) {
                    ByteBuffer stunBinding = stunPacket();
                    stunChannel.send(stunBinding, peerAddr);
                    packetCount++;
                    System.out.printf("[P2P] STUN burst #%d sent to %s from port %d%n", 
                        packetCount, peerAddr, localPort);
                    lastSend = System.currentTimeMillis();
                }
                
                // Check for response (non-blocking)
                if (burstSelector.selectNow() > 0) {
                    Iterator<SelectionKey> it = burstSelector.selectedKeys().iterator();
                    while (it.hasNext()) {
                        SelectionKey key = it.next();
                        it.remove();
                        
                        if (!key.isReadable()) continue;
                        
                        DatagramChannel dc = (DatagramChannel) key.channel();
                        ByteBuffer buf = ByteBuffer.allocate(1024);
                        SocketAddress from = dc.receive(buf);
                        if (from == null) continue;
                        
                        buf.flip();
                        System.out.printf("[P2P] 📥 Response received during burst from %s - hole punch confirmed!%n", from);
                        responseReceived = true;
                        break;
                    }
                }
                
                Thread.sleep(10); // Small sleep to prevent busy loop
            }
            
            burstSelector.close();
            
            if (!responseReceived) {
                System.err.printf("[P2P] ❌ No response received during burst to %s%n", peerAddr);
                return false;
            }
            
            // Initialize global KeepAliveManager if not already started
            if (globalKeepAlive == null) {
                System.out.println("[P2P] 🔧 Initializing global KeepAliveManager");
                globalKeepAlive = new KeepAliveManager(20_000); // 20 seconds keep-alive
                globalKeepAlive.installShutdownHook();
                globalKeepAlive.startMessageListening(stunChannel);
            }
            
            // Register this peer with global KeepAliveManager
            globalKeepAlive.register(stunChannel, peerAddr);
            
            // Store peer address for messaging
            activePeers.put(targetUsername, peerAddr);
            lastActivity.put(targetUsername, System.currentTimeMillis());
            
            System.out.printf("[P2P] ✅ Direct hole punching successful to %s%n", targetUsername);
            return true;
            
        } catch (Exception e) {
            System.err.printf("[P2P] Direct hole punching error: %s%n", e.getMessage());
            return false;
        }
    }
    
    // ============================================
    // P2P MESSAGING FUNCTIONS
    // ============================================
    
    // Message listening now integrated with KeepAliveManager
    
    /**
     * Handle incoming P2P message (called by KeepAliveManager)
     */
    public static void handleIncomingMessage(ByteBuffer buf, SocketAddress from) {
        try {
            List<Object> parsed = LLS.parseMessagePacket(buf);
            String sender = (String) parsed.get(2);
            String receiver = (String) parsed.get(3);
            String message = (String) parsed.get(4);
            
            System.out.printf("[P2P] 📥 Message received: %s -> %s: \"%s\"%n", sender, receiver, message);
            
            // Update last activity
            lastActivity.put(sender, System.currentTimeMillis());
            
            // Forward to ChatService to display in GUI
            try {
                javafx.application.Platform.runLater(() -> {
                    com.saferoom.gui.service.ChatService.getInstance().receiveP2PMessage(sender, receiver, message);
                });
            } catch (Exception e) {
                System.err.println("[P2P] Error forwarding to ChatService: " + e.getMessage());
            }
            
        } catch (Exception e) {
            System.err.printf("[P2P] Error parsing incoming message from %s: %s%n", from, e.getMessage());
        }
    }
    
    /**
     * Send P2P message to specific peer
     */
    public static boolean sendP2PMessage(String sender, String receiver, String message) {
        InetSocketAddress peerAddr = activePeers.get(receiver);
        if (peerAddr == null || stunChannel == null) {
            System.err.printf("[P2P] No active P2P connection with %s%n", receiver);
            return false;
        }
        
        try {
            ByteBuffer messagePacket = LLS.New_Message_Packet(sender, receiver, message);
            stunChannel.send(messagePacket, peerAddr);
            
            // Update last activity
            lastActivity.put(receiver, System.currentTimeMillis());
            
            System.out.printf("[P2P] 📤 Message sent: %s -> %s: \"%s\"%n", sender, receiver, message);
            return true;
        } catch (Exception e) {
            System.err.printf("[P2P] ❌ Failed to send message to %s: %s%n", receiver, e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if P2P connection is active with specific peer
     */
    public static boolean isP2PActive(String username) {
        return activePeers.containsKey(username) && stunChannel != null;
    }
    
    /**
     * Check if any P2P connection is active
     */
    public static boolean isP2PActive() {
        return !activePeers.isEmpty() && stunChannel != null;
    }
    
    /**
     * Get peer address for specific user
     */
    public static InetSocketAddress getPeerAddress(String username) {
        return activePeers.get(username);
    }
    
    /**
     * Get all active peer connections
     */
    public static Set<String> getActivePeers() {
        return new HashSet<>(activePeers.keySet());
    }
    
    /**
     * Close P2P connection with specific peer
     */
    public static void closeP2PConnection(String username) {
        activePeers.remove(username);
        lastActivity.remove(username);
        System.out.println("[P2P] Connection closed with " + username);
        
        // If no more peers, close the channel
        if (activePeers.isEmpty() && stunChannel != null) {
            try {
                stunChannel.close();
                stunChannel = null;
                System.out.println("[P2P] All connections closed - channel closed");
            } catch (Exception e) {
                System.err.println("[P2P] Error closing channel: " + e.getMessage());
            }
        }
    }
    
    /**
     * Close all P2P connections
     */
    public static void closeAllP2PConnections() {
        activePeers.clear();
        lastActivity.clear();
        try {
            if (stunChannel != null) {
                stunChannel.close();
                stunChannel = null;
            }
            System.out.println("[P2P] All connections closed");
        } catch (Exception e) {
            System.err.println("[P2P] Error closing all connections: " + e.getMessage());
        }
    }
    
    // ============= NAT PORT PROFILING SYSTEM =============
    
    /**
     * Profiles the NAT behavior intelligently:
     * 1. First detects NAT type using existing analyzeSinglePort() method
     * 2. If NON-SYMMETRIC: Returns profile with single port (no need for 1000 queries)
     * 3. If SYMMETRIC: Performs deep profiling with 1000+ queries to map port range
     * 
     * This avoids unnecessary STUN traffic for non-symmetric NAT users.
     * 
     * @param maxProbeCount Maximum number of STUN queries (only used for symmetric NAT)
     * @return NATProfile containing NAT type and port range information
     */
    public static NATProfile profileNATBehavior(int maxProbeCount) {
        System.out.println("[NAT-PROFILE] Starting intelligent NAT profiling...");
        
        try {
            // PHASE 1: Use cached profile if available (avoids re-running detection and closing stunChannel!)
            byte natType;
            if (cachedProfile != null) {
                natType = cachedProfile.natType;
                System.out.println("[NAT-PROFILE] ✅ Using cached NAT type from previous detection: 0x" + 
                    String.format("%02X", natType) + " (" + (natType == 0x11 ? "SYMMETRIC" : "NON-SYMMETRIC") + ")");
                System.out.println("[NAT-PROFILE] ⚡ Skipping re-detection to keep stunChannel open for hole punching!");
            } else {
                System.out.println("[NAT-PROFILE] Phase 1: Detecting NAT type...");
                natType = analyzeSinglePort(stunServers);
                
                if (natType == (byte)0xFE) {
                    System.err.println("[NAT-PROFILE] NAT detection failed!");
                    return null;
                }
                
                String natTypeStr = (natType == 0x11) ? "SYMMETRIC" : "NON-SYMMETRIC";
                System.out.println("[NAT-PROFILE] Detected NAT type: " + natTypeStr);
            }
            
            // PHASE 2: If NON-SYMMETRIC, no need for deep profiling
            if (natType == 0x00) {
                System.out.println("[NAT-PROFILE] Non-Symmetric NAT detected - using single stable port");
                
                // Use the public port from the detection phase
                if (Public_PortList.isEmpty()) {
                    System.err.println("[NAT-PROFILE] No public port available!");
                    return null;
                }
                
                int stablePort = Public_PortList.get(0);
                System.out.println("[NAT-PROFILE] Stable port: " + stablePort);
                
                // Create profile with single port (min = max = stable port)
                NATProfile profile = new NATProfile(
                    natType, 
                    stablePort, 
                    stablePort, 
                    1, // Only 1 probe needed
                    Collections.singletonList(stablePort)
                );
                
                cachedProfile = profile;
                System.out.println("[NAT-PROFILE] ✅ Profile complete (Non-Symmetric - no deep profiling needed)");
                return profile;
            }
            
            // PHASE 3: SYMMETRIC NAT - perform deep profiling to map port range
            System.out.println("[NAT-PROFILE] Symmetric NAT detected - performing deep port profiling with " + maxProbeCount + " probes...");
            
            List<Integer> observedPorts = new ArrayList<>();
            ExecutorService executor = Executors.newFixedThreadPool(20); // Parallel STUN queries
            List<Future<Integer>> futures = new ArrayList<>();
            
            // Launch parallel STUN queries
            for (int i = 0; i < maxProbeCount; i++) {
                final int probeIndex = i;
                futures.add(executor.submit(() -> {
                    try {
                        return querySingleSTUNPort(probeIndex);
                    } catch (Exception e) {
                        System.err.println("[NAT-PROFILE] Probe " + probeIndex + " failed: " + e.getMessage());
                        return null;
                    }
                }));
            }
            
            // Collect results
            int successCount = 0;
            for (Future<Integer> future : futures) {
                try {
                    Integer port = future.get(10, TimeUnit.SECONDS);
                    if (port != null && port > 0) {
                        synchronized (observedPorts) {
                            observedPorts.add(port);
                        }
                        successCount++;
                        
                        // Progress indicator every 100 samples
                        if (successCount % 100 == 0) {
                            System.out.println("[NAT-PROFILE] Collected " + successCount + "/" + maxProbeCount + " samples...");
                        }
                    }
                } catch (TimeoutException e) {
                    System.err.println("[NAT-PROFILE] Probe timed out");
                } catch (Exception e) {
                    System.err.println("[NAT-PROFILE] Error collecting probe result: " + e.getMessage());
                }
            }
            
            executor.shutdown();
            
            if (observedPorts.isEmpty()) {
                System.err.println("[NAT-PROFILE] Failed to collect any port samples!");
                return null;
            }
            
            // Analyze port behavior for symmetric NAT
            NATProfile profile = analyzePortBehavior(observedPorts);
            profile.natType = 0x11; // Force to symmetric (we already detected it)
            cachedProfile = profile;
            
            System.out.println("[NAT-PROFILE] ✅ Symmetric NAT profile complete:");
            System.out.println("  Port Range: " + profile.minPort + " - " + profile.maxPort);
            System.out.println("  Sampled Ports: " + profile.profiledPorts);
            System.out.println("  Unique Ports: " + new HashSet<>(observedPorts).size());
            
            return profile;
            
        } catch (Exception e) {
            System.err.println("[NAT-PROFILE] Profiling failed: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Sends a single STUN query on a new ephemeral port and returns the observed external port.
     */
    private static Integer querySingleSTUNPort(int probeIndex) throws Exception {
        DatagramChannel channel = null;
        try {
            // Create ephemeral socket (OS assigns local port)
            channel = DatagramChannel.open();
            channel.socket().setReuseAddress(true);
            channel.bind(new InetSocketAddress(0)); // Bind to random port
            channel.configureBlocking(false);
            
            // Select random STUN server
            String[] stunServer = stunServers[probeIndex % stunServers.length];
            InetSocketAddress stunAddr = new InetSocketAddress(
                InetAddress.getByName(stunServer[0]), 
                Integer.parseInt(stunServer[1])
            );
            
            // Send STUN binding request
            ByteBuffer request = stunPacket();
            channel.send(request, stunAddr);
            
            // Wait for response
            Selector selector = Selector.open();
            channel.register(selector, SelectionKey.OP_READ);
            
            long deadline = System.currentTimeMillis() + 3000; // 3 second timeout
            while (System.currentTimeMillis() < deadline) {
                if (selector.select(500) > 0) {
                    ByteBuffer response = ByteBuffer.allocate(512);
                    channel.receive(response);
                    response.flip();
                    
                    // Parse MAPPED-ADDRESS or XOR-MAPPED-ADDRESS
                    Integer port = extractPortFromSTUN(response);
                    if (port != null) {
                        selector.close();
                        return port;
                    }
                }
            }
            
            selector.close();
            return null;
            
        } finally {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        }
    }
    
    /**
     * Extracts the external port from a STUN response packet.
     */
    private static Integer extractPortFromSTUN(ByteBuffer buffer) {
        if (buffer.remaining() < 20) return null;
        
        buffer.position(20); // Skip STUN header
        
        while (buffer.remaining() >= 4) {
            short attrType = buffer.getShort();
            short attrLen = buffer.getShort();
            
            if (attrLen < 0 || attrLen > buffer.remaining()) break;
            
            // MAPPED-ADDRESS (0x0001) or XOR-MAPPED-ADDRESS (0x0020)
            if (attrType == 0x0001 || attrType == 0x0020) {
                if (attrLen >= 8) {
                    buffer.get(); // Skip reserved byte
                    byte family = buffer.get();
                    int port = buffer.getShort() & 0xFFFF;
                    
                    // XOR port if needed
                    if (attrType == 0x0020) {
                        port ^= 0x2112; // XOR with magic cookie high 16 bits
                    }
                    
                    return port;
                }
            } else {
                // Skip unknown attribute
                buffer.position(buffer.position() + attrLen);
            }
        }
        
        return null;
    }
    
    /**
     * Analyzes the list of observed ports to determine NAT type and port range.
     */
    private static NATProfile analyzePortBehavior(List<Integer> ports) {
        Set<Integer> uniquePorts = new HashSet<>(ports);
        int minPort = Collections.min(ports);
        int maxPort = Collections.max(ports);
        
        // Heuristic: If >5% of probes resulted in unique ports, classify as symmetric
        double uniqueRatio = (double) uniquePorts.size() / ports.size();
        byte natType;
        
        if (uniqueRatio > 0.05) {
            natType = 0x11; // SYMMETRIC NAT
            System.out.println("[NAT-PROFILE] Detected SYMMETRIC NAT (unique ratio: " 
                + String.format("%.2f%%", uniqueRatio * 100) + ")");
        } else {
            natType = 0x00; // NON-SYMMETRIC NAT
            System.out.println("[NAT-PROFILE] Detected NON-SYMMETRIC NAT (unique ratio: " 
                + String.format("%.2f%%", uniqueRatio * 100) + ")");
        }
        
        return new NATProfile(natType, minPort, maxPort, ports.size(), 
            new ArrayList<>(uniquePorts).stream().sorted().collect(Collectors.toList()));
    }
    
    /**
     * Sends the NAT profile to the server for coordinated hole punching.
     */
    public static void sendNATProfileToServer(String username, InetSocketAddress serverAddr) {
        if (cachedProfile == null) {
            System.err.println("[NAT-PROFILE] No cached profile available! Run profileNATBehavior() first.");
            return;
        }
        
        try {
            byte[] packet = LLS.createNATProfilePacket(
                username,
                cachedProfile.natType,
                cachedProfile.minPort,
                cachedProfile.maxPort,
                cachedProfile.profiledPorts
            );
            
            if (stunChannel != null && stunChannel.isOpen()) {
                stunChannel.send(ByteBuffer.wrap(packet), serverAddr);
                System.out.println("[NAT-PROFILE] Sent profile to server: " + serverAddr);
            } else {
                System.err.println("[NAT-PROFILE] STUN channel not available!");
            }
        } catch (Exception e) {
            System.err.println("[NAT-PROFILE] Failed to send profile to server: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Symmetric NAT side: Opens a pool of UDP sockets and sends CONTINUOUS burst packets to target.
     * 
     * CRITICAL CHANGES:
     * - Each port continuously bursts until peer response received (not just 5 packets!)
     * - Listens concurrently for incoming packets (collision detection)
     * - Keeps successful channel open, closes others
     * - Establishes keep-alive after connection
     * 
     * @param targetIP The peer's public IP
     * @param targetPort The peer's port (stable for non-symmetric, or midpoint for symmetric)
     * @param numPorts Number of ports to open (typically N/2 from profiled range)
     */
    public static void symmetricPortPoolExpansion(InetAddress targetIP, int targetPort, int numPorts, String localUsername, String targetUsername) {
        System.out.println("\n[SYMMETRIC-PUNCH] 🔥 Starting CONTINUOUS port pool expansion");
        System.out.printf("  Target: %s:%d (username: %s)%n", targetIP.getHostAddress(), targetPort, targetUsername);
        System.out.printf("  Opening %d local ports for continuous burst...%n", numPorts);
        
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(numPorts, 50));
        List<DatagramChannel> channels = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean connectionEstablished = new AtomicBoolean(false);
        AtomicReference<DatagramChannel> successfulChannel = new AtomicReference<>(null);
        AtomicReference<InetSocketAddress> peerAddress = new AtomicReference<>(null);
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Phase 1: Open port pool and start CONTINUOUS bursting
            for (int i = 0; i < numPorts; i++) {
                final int portIndex = i;
                executor.submit(() -> {
                    try {
                        DatagramChannel channel = DatagramChannel.open();
                        channel.configureBlocking(false);
                        channel.socket().setReuseAddress(true);
                        channel.bind(new InetSocketAddress(0)); // Ephemeral port
                        
                        channels.add(channel);
                        
                        InetSocketAddress target = new InetSocketAddress(targetIP, targetPort);
                        
                        int burstCount = 0;
                        
                        // CONTINUOUS bursting until collision detected
                        while (!connectionEstablished.get()) {
                            ByteBuffer burstPayload = LLS.New_Burst_Packet(
                                localUsername,
                                targetUsername,
                                "SYM-BURST-" + portIndex + "-" + burstCount
                            );
                            
                            channel.send(burstPayload, target);
                            burstCount++;
                            
                            // Check for incoming response (collision detection)
                            ByteBuffer receiveBuffer = ByteBuffer.allocate(1024);
                            InetSocketAddress sender = (InetSocketAddress) channel.receive(receiveBuffer);
                            
                            if (sender != null) {
                                // ⚠️ VALIDATE: Response must be from target IP
                                boolean isFromTarget = sender.getAddress().equals(targetIP);
                                
                                if (!isFromTarget) {
                                    System.out.printf("[SYMMETRIC-PUNCH] ⚠️ Port %d: Response from WRONG source: %s (expected: %s)%n", 
                                        portIndex, sender, targetIP.getHostAddress());
                                    continue; // Ignore non-target responses
                                }
                                
                                if (!connectionEstablished.getAndSet(true)) {
                                    // 🎉 COLLISION DETECTED!
                                    long collisionTime = System.currentTimeMillis() - startTime;
                                    System.out.printf("\n[SYMMETRIC-PUNCH] 🎉 COLLISION! Port %d received response after %d ms%n",
                                        portIndex, collisionTime);
                                    System.out.printf("  Local port: %d%n", channel.socket().getLocalPort());
                                    System.out.printf("  Peer responded from: %s (VALIDATED ✅)%n", sender);
                                    System.out.printf("  Total bursts sent: %d%n", burstCount);
                                    
                                    successfulChannel.set(channel);
                                    peerAddress.set(sender);
                                    return; // Keep this channel alive
                                }
                            }
                            
                            Thread.sleep(50); // 50ms between bursts = 20 bursts/sec
                        }
                        
                    } catch (ClosedByInterruptException | InterruptedException e) {
                        // Expected when connection established
                    } catch (Exception e) {
                        if (!connectionEstablished.get()) {
                            System.err.printf("[SYMMETRIC-PUNCH] Port %d error: %s%n", 
                                portIndex, e.getMessage());
                        }
                    }
                });
            }
            
            // Phase 2: Wait for collision (max 30 seconds)
            System.out.println("[SYMMETRIC-PUNCH] ⏳ All ports bursting... waiting for collision...");
            
            for (int i = 0; i < 300; i++) { // 30 seconds timeout
                Thread.sleep(100);
                
                if (connectionEstablished.get()) {
                    break;
                }
                
                // Progress indicator every 5 seconds
                if ((i + 1) % 50 == 0) {
                    System.out.printf("[SYMMETRIC-PUNCH] Still bursting... (%d seconds elapsed)%n", 
                        (i + 1) / 10);
                }
            }
            
            // Phase 3: Cleanup and connection establishment
            executor.shutdownNow();
            
            if (connectionEstablished.get()) {
                DatagramChannel workingChannel = successfulChannel.get();
                InetSocketAddress peer = peerAddress.get();
                
                System.out.println("\n[SYMMETRIC-PUNCH] ✅ Connection Established!");
                System.out.printf("  Using local port: %d%n", 
                    workingChannel.socket().getLocalPort());
                System.out.printf("  Peer address: %s%n", peer);
                
                // Close all other channels
                for (DatagramChannel ch : channels) {
                    if (ch != workingChannel && ch.isOpen()) {
                        try { ch.close(); } catch (Exception e) {}
                    }
                }
                
                // Register peer with activePeers map
                System.out.println("[SYMMETRIC-PUNCH] 💓 Registering peer");
                activePeers.put(targetUsername, peer);
                lastActivity.put(targetUsername, System.currentTimeMillis());
                
                // WARNING: workingChannel != stunChannel!
                // Start dedicated keep-alive thread for this channel
                startKeepAlive(workingChannel, peer, targetUsername);
                System.out.println("[SYMMETRIC-PUNCH] ✅ Connection ready for messaging");
                
            } else {
                System.err.println("\n[SYMMETRIC-PUNCH] ❌ TIMEOUT: No collision detected after 30 seconds");
                System.err.println("  Possible causes:");
                System.err.println("  - Both sides might have strict firewalls");
                System.err.println("  - Port range calculation mismatch");
                System.err.println("  - Network congestion dropping burst packets");
                
                // Close all channels
                for (DatagramChannel ch : channels) {
                    try { ch.close(); } catch (Exception e) {}
                }
            }
            
        } catch (Exception e) {
            System.err.println("[SYMMETRIC-PUNCH] ❌ Fatal error: " + e.getMessage());
            e.printStackTrace();
            
            // Emergency cleanup
            executor.shutdownNow();
            for (DatagramChannel ch : channels) {
                try { ch.close(); } catch (Exception ex) {}
            }
        }
    }
    
    /**
     * Asymmetric NAT side: CONTINUOUSLY scans the symmetric peer's port range.
     * 
     * Strategy:
     * - Use SINGLE stable port (non-symmetric advantage)
     * - Continuously burst to ALL ports in symmetric peer's range
     * - Listen concurrently for peer response (collision detection)
     * - Stop when response received and establish connection
     * 
     * @param targetIP The symmetric peer's public IP
     * @param minPort Start of the symmetric peer's port range
     * @param maxPort End of the symmetric peer's port range
     */
    public static void scanPortRange(InetAddress targetIP, int minPort, int maxPort, String localUsername, String targetUsername) {
        System.out.println("\n[ASYMMETRIC-SCAN] 🔍 Starting CONTINUOUS range scan");
        System.out.printf("  Target: %s (username: %s)%n", targetIP.getHostAddress(), targetUsername);
        System.out.printf("  Port range: %d-%d (%d ports)%n", minPort, maxPort, (maxPort - minPort + 1));
        System.out.println("  Using stable local port for scanning");
        
        if (stunChannel == null || !stunChannel.isOpen()) {
            System.err.println("[ASYMMETRIC-SCAN] ❌ No active STUN channel!");
            return;
        }
        
        try {
            // Configure channel for non-blocking
            stunChannel.configureBlocking(false);
            Selector selector = Selector.open();
            stunChannel.register(selector, SelectionKey.OP_READ);
            
            int rangeSize = maxPort - minPort + 1;
            long startTime = System.currentTimeMillis();
            long timeout = 30000; // 30 seconds timeout
            boolean connectionEstablished = false;
            int scanCycle = 0;
            
            System.out.println("[ASYMMETRIC-SCAN] ⏳ Starting continuous range scan...");
            
            // Continuous range scanning until peer response or timeout
            while (!connectionEstablished && (System.currentTimeMillis() - startTime) < timeout) {
                // Scan entire port range in this cycle
                for (int port = minPort; port <= maxPort && !connectionEstablished; port++) {
                    // Send burst packet to this port with proper LLS format
                    InetSocketAddress target = new InetSocketAddress(targetIP, port);
                    ByteBuffer burstPayload = LLS.New_Burst_Packet(
                        localUsername,
                        targetUsername,
                        "ASYM-SCAN-" + scanCycle + "-" + port
                    );
                    
                    stunChannel.send(burstPayload, target);
                    
                    // Check for peer response every few packets (non-blocking)
                    if (port % 10 == 0 && selector.select(1) > 0) { // Check every 10 ports
                        selector.selectedKeys().clear();
                        
                        ByteBuffer receiveBuffer = ByteBuffer.allocate(1024);
                        InetSocketAddress sender = (InetSocketAddress) stunChannel.receive(receiveBuffer);
                        
                        if (sender != null) {
                            // ⚠️ VALIDATE: Response must be from target IP
                            boolean isFromTarget = sender.getAddress().equals(targetIP);
                            
                            if (!isFromTarget) {
                                System.out.printf("[ASYMMETRIC-SCAN] ⚠️ Response from WRONG source: %s (expected target IP: %s)%n", 
                                    sender, targetIP.getHostAddress());
                                System.out.println("[ASYMMETRIC-SCAN] ⚠️ Likely server echo - ignoring, continuing scan...");
                                continue; // Ignore non-target responses
                            }
                            
                            long responseTime = System.currentTimeMillis() - startTime;
                            System.out.printf("\n[ASYMMETRIC-SCAN] 🎉 COLLISION! Response received after %d ms%n", responseTime);
                            System.out.printf("  Peer responded from: %s (VALIDATED ✅)%n", sender);
                            System.out.printf("  Total scan cycles: %d%n", scanCycle);
                            System.out.printf("  Current port in scan: %d%n", port);
                            
                            connectionEstablished = true;
                            
                            // Register peer with global KeepAliveManager
                            System.out.println("[ASYMMETRIC-SCAN] 💓 Registering peer with KeepAliveManager");
                            if (globalKeepAlive == null) {
                                System.out.println("[ASYMMETRIC-SCAN] 🔧 Initializing global KeepAliveManager");
                                globalKeepAlive = new KeepAliveManager(20_000); // 20 seconds keep-alive
                                globalKeepAlive.installShutdownHook();
                                globalKeepAlive.startMessageListening(stunChannel);
                            }
                            globalKeepAlive.register(stunChannel, sender);
                            
                            activePeers.put(targetUsername, sender);
                            lastActivity.put(targetUsername, System.currentTimeMillis());
                            System.out.println("[ASYMMETRIC-SCAN] ✅ Connection ready for messaging");
                            
                            // 🆕 Complete the pending P2P connection future
                            CompletableFuture<Boolean> future = pendingP2PConnections.get(targetUsername);
                            if (future != null && !future.isDone()) {
                                future.complete(true);
                                System.out.println("[ASYMMETRIC-SCAN] ✅ Notified waiting thread");
                            }
                            
                            break;
                        }
                    }
                }
                
                scanCycle++;
                
                // Progress indicator every 5 cycles
                if (scanCycle % 5 == 0) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    System.out.printf("[ASYMMETRIC-SCAN] Still scanning... cycle %d (%.1f seconds)%n", 
                        scanCycle, elapsed / 1000.0);
                }
                
                // Small delay between full range scans
                Thread.sleep(10); // 10ms delay between range cycles
            }
            
            selector.close();
            
            if (!connectionEstablished) {
                System.err.println("\n[ASYMMETRIC-SCAN] ❌ TIMEOUT: No response after 30 seconds");
                System.err.printf("  Total scan cycles completed: %d%n", scanCycle);
                System.err.printf("  Total ports scanned: %d%n", scanCycle * rangeSize);
                
                // 🆕 Complete the pending future with failure
                CompletableFuture<Boolean> future = pendingP2PConnections.get(targetUsername);
                if (future != null && !future.isDone()) {
                    future.complete(false);
                }
            }
            
        } catch (Exception e) {
            System.err.println("[ASYMMETRIC-SCAN] ❌ Failed: " + e.getMessage());
            e.printStackTrace();
            
            // 🆕 Complete the pending future with failure
            CompletableFuture<Boolean> future = pendingP2PConnections.get(targetUsername);
            if (future != null && !future.isDone()) {
                future.complete(false);
            }
        }
    }
    
    /**
     * Returns the cached NAT profile (if available).
     */
    public static NATProfile getCachedProfile() {
        return cachedProfile;
    }
    
    // ============= CLIENT-SIDE PUNCH INSTRUCTION HANDLER =============
    
    /**
     * Handles SIG_PUNCH_INSTRUCT packet from server.
     * Server coordinates hole punching based on NAT types of both peers.
     * 
     * Strategy codes:
     * - 0x00: STANDARD (both non-symmetric)
     * - 0x01: SYMMETRIC_BURST (symmetric side)
     * - 0x02: ASYMMETRIC_SCAN (non-symmetric side scanning symmetric peer)
     */
    public static void handlePunchInstruction(ByteBuffer buffer) {
        try {
            List<Object> parsed = LLS.parsePunchInstructPacket(buffer);
            String username = (String) parsed.get(2);
            String target = (String) parsed.get(3);
            InetAddress targetIP = (InetAddress) parsed.get(4);
            int targetPort = (Integer) parsed.get(5);
            byte strategy = (Byte) parsed.get(6);
            int numPorts = (Integer) parsed.get(7);
            
            System.out.printf("[P2P-INSTRUCT] 📨 Received punch instruction from server%n");
            System.out.printf("  User: %s → Target: %s%n", username, target);
            System.out.printf("  Target IP: %s:%d%n", targetIP.getHostAddress(), targetPort);
            System.out.printf("  Strategy: 0x%02X, Ports: %d%n", strategy, numPorts);
            
            switch (strategy) {
                case 0x01 -> {
                    // SYMMETRIC_BURST: Open port pool and send bursts
                    System.out.println("[P2P-INSTRUCT] 🔥 Executing SYMMETRIC BURST strategy");
                    System.out.printf("  Opening %d ports, sending bursts to %s:%d%n", 
                        numPorts, targetIP.getHostAddress(), targetPort);
                    symmetricPortPoolExpansion(targetIP, targetPort, numPorts, username, target);
                }
                case 0x02 -> {
                    // ASYMMETRIC_SCAN: Scan port range
                    System.out.println("[P2P-INSTRUCT] 🔍 Executing ASYMMETRIC SCAN strategy");
                    int maxPort = targetPort + numPorts - 1;
                    System.out.printf("  Scanning port range %d-%d on %s%n", 
                        targetPort, maxPort, targetIP.getHostAddress());
                    scanPortRange(targetIP, targetPort, maxPort, username, target);
                }
                case 0x03 -> {
                    // SYMMETRIC_MIDPOINT_BURST: Birthday Paradox for Symmetric ↔ Symmetric
                    System.out.println("[P2P-INSTRUCT] 🎯 Executing SYMMETRIC MIDPOINT BURST (Birthday Paradox)");
                    System.out.printf("  Opening %d ports, bursting to peer's midpoint %s:%d%n", 
                        numPorts, targetIP.getHostAddress(), targetPort);
                    System.out.println("  Strategy: Continuous burst until connection established");
                    symmetricMidpointBurst(targetIP, targetPort, numPorts, username, target);
                }
                case 0x00 -> {
                    // STANDARD: Basic hole punch
                    System.out.println("[P2P-INSTRUCT] ⚡ Executing STANDARD hole punch");
                    System.out.printf("  Punching to %s:%d%n", 
                        targetIP.getHostAddress(), targetPort);
                    executeStandardHolePunch(targetIP, targetPort, username, target);
                }
                default -> {
                    System.err.printf("[P2P-INSTRUCT] ❌ Unknown strategy: 0x%02X%n", strategy);
                }
            }
            
        } catch (Exception e) {
            System.err.println("[P2P-INSTRUCT] ❌ Failed to handle punch instruction: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * BIRTHDAY PARADOX STRATEGY for Symmetric ↔ Symmetric NAT traversal.
     * 
     * Both peers open N local ports and continuously burst to the calculated
     * midpoint of the peer's port range. This creates many crossing NAT mappings,
     * statistically guaranteeing a collision (successful hole punch).
     * 
     * Once ANY port receives a response, we stop all bursting and establish
     * the connection using that specific port pair.
     * 
     * @param targetIP Target peer's public IP
     * @param targetPort Calculated midpoint of target's port range
     * @param numPorts Number of local ports to open (100-500)
     * @param targetUsername Target peer's username for registration
     */
    private static void symmetricMidpointBurst(InetAddress targetIP, int targetPort, 
                                                int numPorts, String localUsername, String targetUsername) {
        System.out.println("\n[BIRTHDAY-PARADOX] 🎯 Starting Symmetric Midpoint Burst");
        System.out.printf("  Target: %s:%d (~midpoint)%n", targetIP.getHostAddress(), targetPort);
        System.out.printf("  Opening %d local ports for burst...%n", numPorts);
        
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(numPorts, 50));
        List<DatagramChannel> channels = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean connectionEstablished = new AtomicBoolean(false);
        AtomicReference<DatagramChannel> successfulChannel = new AtomicReference<>(null);
        AtomicReference<InetSocketAddress> peerAddress = new AtomicReference<>(null);
        
        long startTime = System.currentTimeMillis();
        
        try {
            // Phase 1: Open port pool and start continuous bursting
            for (int i = 0; i < numPorts; i++) {
                final int portIndex = i;
                executor.submit(() -> {
                    try {
                        DatagramChannel channel = DatagramChannel.open();
                        channel.configureBlocking(false);
                        channel.socket().setReuseAddress(true);
                        channel.bind(new InetSocketAddress(0)); // Ephemeral port
                        
                        channels.add(channel);
                        
                        InetSocketAddress target = new InetSocketAddress(targetIP, targetPort);
                        
                        int burstCount = 0;
                        
                        // Continuous bursting until collision detected
                        while (!connectionEstablished.get()) {
                            ByteBuffer burstPayload = LLS.New_Burst_Packet(
                                localUsername,
                                targetUsername,
                                "MIDPOINT-BURST-" + portIndex + "-" + burstCount
                            );
                            
                            channel.send(burstPayload, target);
                            burstCount++;
                            
                            // Check for incoming response (collision detection)
                            ByteBuffer receiveBuffer = ByteBuffer.allocate(1024);
                            InetSocketAddress sender = (InetSocketAddress) channel.receive(receiveBuffer);
                            
                            if (sender != null) {
                                // ✅ VALIDATE: Response must be from target peer IP
                                boolean isFromTarget = sender.getAddress().equals(targetIP);
                                
                                if (!isFromTarget) {
                                    System.out.printf("[BIRTHDAY-PARADOX] ⚠️ Response from WRONG source: %s (expected: %s)%n",
                                        sender, target);
                                    continue; // Ignore server echo or wrong peer, keep bursting
                                }
                                
                                if (!connectionEstablished.getAndSet(true)) {
                                    // 🎉 COLLISION DETECTED!
                                    long collisionTime = System.currentTimeMillis() - startTime;
                                    System.out.printf("\n[BIRTHDAY-PARADOX] 🎉 COLLISION! Port %d received response after %d ms%n",
                                        portIndex, collisionTime);
                                    System.out.printf("  Peer responded from: %s (VALIDATED ✅)%n", sender);
                                    System.out.printf("  Total bursts sent: %d%n", burstCount);
                                    
                                    successfulChannel.set(channel);
                                    peerAddress.set(sender);
                                    return; // Keep this channel alive
                                }
                            }
                            
                            Thread.sleep(50); // 50ms between bursts = 20 bursts/sec
                        }
                        
                    } catch (ClosedByInterruptException | InterruptedException e) {
                        // Expected when connection established
                    } catch (Exception e) {
                        if (!connectionEstablished.get()) {
                            System.err.printf("[BIRTHDAY-PARADOX] Port %d error: %s%n", 
                                portIndex, e.getMessage());
                        }
                    }
                });
            }
            
            // Phase 2: Wait for collision (max 30 seconds)
            System.out.println("[BIRTHDAY-PARADOX] ⏳ All ports bursting... waiting for collision...");
            
            for (int i = 0; i < 300; i++) { // 30 seconds timeout
                Thread.sleep(100);
                
                if (connectionEstablished.get()) {
                    break;
                }
                
                // Progress indicator every 5 seconds
                if ((i + 1) % 50 == 0) {
                    System.out.printf("[BIRTHDAY-PARADOX] Still bursting... (%d seconds elapsed)%n", 
                        (i + 1) / 10);
                }
            }
            
            // Phase 3: Cleanup and connection establishment
            executor.shutdownNow();
            
            if (connectionEstablished.get()) {
                DatagramChannel workingChannel = successfulChannel.get();
                InetSocketAddress peer = peerAddress.get();
                
                System.out.println("\n[BIRTHDAY-PARADOX] ✅ Connection Established!");
                System.out.printf("  Using local port: %d%n", 
                    workingChannel.socket().getLocalPort());
                System.out.printf("  Peer address: %s%n", peer);
                
                // Close all other channels
                for (DatagramChannel ch : channels) {
                    if (ch != workingChannel && ch.isOpen()) {
                        try { ch.close(); } catch (Exception e) {}
                    }
                }
                
                // Register peer with activePeers map
                activePeers.put(targetUsername, peer);
                lastActivity.put(targetUsername, System.currentTimeMillis());
                
                // WARNING: workingChannel != stunChannel!
                // We need to ensure messages use the same channel as keep-alive
                // For now, start a dedicated keep-alive thread for this channel
                System.out.println("[BIRTHDAY-PARADOX] 💓 Starting dedicated keep-alive for working channel");
                startKeepAlive(workingChannel, peer, targetUsername);
                
            } else {
                System.err.println("\n[BIRTHDAY-PARADOX] ❌ TIMEOUT: No collision detected after 30 seconds");
                System.err.println("  Possible causes:");
                System.err.println("  - Both sides might have strict firewalls");
                System.err.println("  - Port range midpoint calculation mismatch");
                System.err.println("  - Network congestion dropping burst packets");
                
                // Close all channels
                for (DatagramChannel ch : channels) {
                    try { ch.close(); } catch (Exception e) {}
                }
            }
            
        } catch (Exception e) {
            System.err.println("[BIRTHDAY-PARADOX] ❌ Fatal error: " + e.getMessage());
            e.printStackTrace();
            
            // Emergency cleanup
            executor.shutdownNow();
            for (DatagramChannel ch : channels) {
                try { ch.close(); } catch (Exception ex) {}
            }
        }
    }
    
    /**
     * Starts keep-alive mechanism on established P2P channel.
     * Sends periodic heartbeat messages to maintain NAT mapping.
     */
    private static void startKeepAlive(DatagramChannel channel, InetSocketAddress peer, String username) {
        Thread keepAliveThread = new Thread(() -> {
            try {
                ByteBuffer keepAlivePayload = ByteBuffer.allocate(64);
                int sequenceNumber = 0;
                
                while (channel.isOpen() && !Thread.interrupted()) {
                    keepAlivePayload.clear();
                    keepAlivePayload.put(LLS.SIG_KEEP);
                    keepAlivePayload.put(("KEEPALIVE-" + sequenceNumber++).getBytes());
                    keepAlivePayload.flip();
                    
                    channel.send(keepAlivePayload, peer);
                    
                    // Update activity timestamp
                    lastActivity.put(username, System.currentTimeMillis());
                    
                    Thread.sleep(15000); // 15 seconds interval
                }
                
            } catch (InterruptedException e) {
                System.out.println("[KEEP-ALIVE] Thread interrupted for: " + username);
            } catch (Exception e) {
                System.err.println("[KEEP-ALIVE] Error for " + username + ": " + e.getMessage());
            }
        });
        
        keepAliveThread.setName("KeepAlive-" + username);
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }
    
    /**
     * Executes standard hole punch (for non-symmetric ↔ non-symmetric).
     * Sends multiple packets to establish NAT mapping and listens for peer response.
     * Uses continuous burst until peer response or timeout (30 seconds).
     * 
     * 🔧 REVERTED: Using own Selector for immediate peer response (like old working version)
     * ⚠️ CRITICAL: Temporarily stops KeepAliveManager to avoid Selector conflict!
     */
    private static void executeStandardHolePunch(InetAddress targetIP, int targetPort, String localUsername, String targetUsername) {
        try {
            if (stunChannel == null || !stunChannel.isOpen()) {
                System.err.println("[P2P-INSTRUCT] ❌ No active STUN channel!");
                return;
            }
            
            InetSocketAddress targetAddr = new InetSocketAddress(targetIP, targetPort);
            System.out.printf("[STANDARD-PUNCH] 📤 Starting continuous burst to %s%n", targetAddr);
            System.out.println("[STANDARD-PUNCH] Will burst until peer response or 30s timeout");
            System.out.printf("[STANDARD-PUNCH] 🔍 DEBUG: targetIP=%s, targetPort=%d%n", targetIP.getHostAddress(), targetPort);
            System.out.printf("[STANDARD-PUNCH] 🔍 DEBUG: stunChannel local=%s, connected=%b%n", 
                stunChannel.getLocalAddress(), stunChannel.isConnected());
            
            // ⚠️ CRITICAL: Stop KeepAliveManager temporarily to avoid Selector conflict
            // (A channel can only be registered with ONE Selector at a time!)
            boolean keepAliveWasActive = false;
            if (globalKeepAlive != null) {
                System.out.println("[STANDARD-PUNCH] ⏸️ Temporarily stopping KeepAliveManager for punch...");
                globalKeepAlive.stopMessageListening();
                keepAliveWasActive = true;
            }
            
            // 🔧 REVERTED: Use own Selector for immediate response detection
            stunChannel.configureBlocking(false);
            Selector selector = Selector.open();
            stunChannel.register(selector, SelectionKey.OP_READ);
            
            long startTime = System.currentTimeMillis();
            long timeout = 30000; // 30 seconds timeout
            boolean peerResponseReceived = false;
            int burstCount = 0;
            int selectorWakeups = 0;
            int serverPackets = 0;
            int otherPackets = 0;
            
            System.out.println("[STANDARD-PUNCH] 🎯 Starting burst loop...");
            System.out.printf("[STANDARD-PUNCH] 📍 Listening on LOCAL: %s%n", stunChannel.getLocalAddress());
            System.out.printf("[STANDARD-PUNCH] 📍 Sending to REMOTE: %s%n", targetAddr);
            
            // Continuous burst with immediate response listening
            while (!peerResponseReceived && (System.currentTimeMillis() - startTime) < timeout) {
                // Send burst packet with proper LLS format
                ByteBuffer burstPayload = LLS.New_Burst_Packet(
                    localUsername, 
                    targetUsername, 
                    "STANDARD-BURST-" + burstCount
                );
                
                int bytesSent = stunChannel.send(burstPayload, targetAddr);
                if (burstCount == 0) {
                    System.out.printf("[STANDARD-PUNCH] 📤 First burst sent: %d bytes to %s%n", bytesSent, targetAddr);
                }
                burstCount++;
                
                // Check for peer response (non-blocking, immediate)
                int readyKeys = selector.select(50); // 50ms wait
                if (readyKeys > 0) {
                    selectorWakeups++;
                    System.out.printf("[STANDARD-PUNCH] 🔔 Selector wakeup #%d (ready keys: %d)%n", selectorWakeups, readyKeys);
                    
                    selector.selectedKeys().clear();
                    
                    // ⚡ CRITICAL FIX: Read ALL available packets in buffer!
                    // UDP buffer can contain MULTIPLE packets (server + peer)
                    // If we only read ONE packet and it's from server, peer packet is lost!
                    int packetsRead = 0;
                    while (true) {
                        ByteBuffer receiveBuffer = ByteBuffer.allocate(1024);
                        InetSocketAddress sender = (InetSocketAddress) stunChannel.receive(receiveBuffer);
                        
                        if (sender == null) {
                            // No more packets available
                            break;
                        }
                        
                        packetsRead++;
                        receiveBuffer.flip();
                        System.out.printf("[STANDARD-PUNCH] 📦 Packet #%d: %d bytes from %s%n", 
                            packetsRead, receiveBuffer.remaining(), sender);
                        
                        long responseTime = System.currentTimeMillis() - startTime;
                        
                        // Track packet source
                        boolean isFromTarget = sender.getAddress().equals(targetIP);
                        boolean isFromServer = sender.getAddress().getHostAddress().equals("35.198.64.68");
                        
                        if (isFromServer) {
                            serverPackets++;
                            System.out.printf("[STANDARD-PUNCH] 📊 SERVER packet (ignoring, checking next)%n");
                            continue; // Skip server, check next packet in buffer
                        }
                        
                        if (!isFromTarget) {
                            otherPackets++;
                            System.out.printf("[STANDARD-PUNCH] ⚠️ Unknown source: %s (ignoring)%n", sender);
                            continue; // Skip unknown, check next
                        }
                        
                        // ✅ PEER PACKET FOUND!
                        receiveBuffer.position(0);
                        byte msgType = receiveBuffer.get();
                        
                        if (msgType == LLS.SIG_PUNCH_BURST) {
                            System.out.printf("\n[STANDARD-PUNCH] ✅ PEER RESPONSE RECEIVED after %d ms!%n", responseTime);
                            System.out.printf("  Peer address: %s (VALIDATED ✅)%n", sender);
                            System.out.printf("  Total bursts sent: %d%n", burstCount);
                            System.out.printf("  Packets in buffer: %d (server=%d, peer=1)%n", 
                                packetsRead, serverPackets);
                            
                            peerResponseReceived = true;
                            
                            // Register peer connection info
                            activePeers.put(targetUsername, sender);
                            lastActivity.put(targetUsername, System.currentTimeMillis());
                            
                            System.out.println("[STANDARD-PUNCH] 💓 Peer connection registered");
                            
                            // 🆕 Complete the pending P2P connection future
                            CompletableFuture<Boolean> future = pendingP2PConnections.get(targetUsername);
                            if (future != null && !future.isDone()) {
                                future.complete(true);
                                System.out.println("[STANDARD-PUNCH] ✅ Notified waiting thread - connection established");
                            }
                            
                            break; // Exit packet reading loop
                        }
                    }
                    
                    if (packetsRead > 0 && !peerResponseReceived) {
                        System.out.printf("[STANDARD-PUNCH] 📊 Stats: wakeup=%d, packets=%d, server=%d, other=%d%n",
                            selectorWakeups, packetsRead, serverPackets, otherPackets);
                    }
                    
                    if (peerResponseReceived) {
                        break; // Exit burst loop
                    }
                }
                
                // Progress logging every 5 seconds
                long elapsed = System.currentTimeMillis() - startTime;
                if (burstCount % 100 == 0 && burstCount > 0) {
                    System.out.printf("[STANDARD-PUNCH] Still bursting... %d packets sent (%.1f seconds)%n", 
                        burstCount, elapsed / 1000.0);
                }
            }
            
            selector.close();
            
            // ⚠️ CRITICAL: Restart KeepAliveManager after punch completes
            if (keepAliveWasActive && globalKeepAlive != null) {
                System.out.println("[STANDARD-PUNCH] ▶️ Restarting KeepAliveManager...");
                globalKeepAlive.startMessageListening(stunChannel);
            }
            
            // Timeout check
            if (!peerResponseReceived) {
                System.err.println("\n[STANDARD-PUNCH] ❌ TIMEOUT: No peer response after 30 seconds");
                System.err.printf("  Total bursts sent: %d%n", burstCount);
                System.err.printf("  Selector wakeups: %d (server packets: %d, other: %d)%n", 
                    selectorWakeups, serverPackets, otherPackets);
                System.err.println("  ⚠️ Peer burst packets NEVER arrived at this socket!");
                System.err.printf("  🔍 Was listening on: %s%n", stunChannel.getLocalAddress());
                System.err.printf("  🔍 Was sending to: %s%n", targetAddr);
                
                // 🆕 Complete the pending future with failure
                CompletableFuture<Boolean> future = pendingP2PConnections.get(targetUsername);
                if (future != null && !future.isDone()) {
                    future.complete(false);
                }
            }
            
        } catch (Exception e) {
            System.err.println("[STANDARD-PUNCH] ❌ Failed: " + e.getMessage());
            e.printStackTrace();
            
            // Ensure KeepAliveManager is restarted even on error
            try {
                if (globalKeepAlive != null) {
                    System.out.println("[STANDARD-PUNCH] 🔧 Restarting KeepAliveManager after error...");
                    globalKeepAlive.startMessageListening(stunChannel);
                }
            } catch (Exception restartEx) {
                System.err.println("[STANDARD-PUNCH] ⚠️ Failed to restart KeepAliveManager: " + restartEx.getMessage());
            }
            
            // 🆕 Complete the pending future with failure
            CompletableFuture<Boolean> future = pendingP2PConnections.get(targetUsername);
            if (future != null && !future.isDone()) {
                future.complete(false);
            }
        }
    }
    
    // ============================================
    // 🆕 RELIABLE MESSAGING API
    // ============================================
    
    /**
     * Initialize reliable messaging (must be called after NAT analysis)
     * @param username Current user's username
     */
    public static void initializeReliableMessaging(String username) {
        if (stunChannel == null) {
            throw new IllegalStateException("STUN channel not initialized! Run NAT analysis first.");
        }
        
        currentUsername = username;
        
        // Create sender
        reliableSender = new ReliableMessageSender(username, stunChannel);
        
        // Create receiver with callback
        reliableReceiver = new ReliableMessageReceiver(
            username,
            stunChannel,
            (sender, msgId, message) -> {
                // Callback when message received
                String messageText = new String(message);
                System.out.printf("\n📨 [RELIABLE-MSG] Received from %s: \"%s\" (%d bytes)%n",
                    sender, messageText, message.length);
                
                // TODO: Forward to GUI/application layer
                onReliableMessageReceived(sender, messageText);
            }
        );
        
        System.out.println("[NAT] ✅ Reliable messaging initialized for user: " + username);
    }
    
    /**
     * Send reliable message to peer
     * @param targetUsername Recipient username
     * @param message Message text
     * @return CompletableFuture that completes when message is ACKed
     */
    public static CompletableFuture<Boolean> sendReliableMessage(String targetUsername, String message) {
        if (reliableSender == null) {
            throw new IllegalStateException("Reliable messaging not initialized! Call initializeReliableMessaging() first.");
        }
        
        InetSocketAddress targetAddr = activePeers.get(targetUsername);
        if (targetAddr == null) {
            System.err.println("[NAT] ❌ No P2P connection to " + targetUsername);
            return CompletableFuture.completedFuture(false);
        }
        
        byte[] messageBytes = message.getBytes();
        
        System.out.println("\n╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║         SENDING RELIABLE MESSAGE (NatAnalyzer)                ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");
        System.out.printf("[NAT] 📤 Target: %s (%s)%n", targetUsername, targetAddr);
        System.out.printf("[NAT] 📝 Message: \"%s\"%n", message);
        System.out.printf("[NAT] 📏 Size: %d bytes (raw text)%n", messageBytes.length);
        System.out.printf("[NAT] 🔢 Expected chunks: %d (chunk size: 1131 bytes)%n", 
            (messageBytes.length + 1130) / 1131);
        System.out.println("───────────────────────────────────────────────────────────────");
        
        return reliableSender.sendMessage(targetUsername, messageBytes, targetAddr);
    }
    
    /**
     * Callback for received reliable messages (override in application)
     */
    private static void onReliableMessageReceived(String sender, String message) {
        // Extract filename from file transfer message
        if (message.startsWith("📎 Sending file: ")) {
            try {
                // Parse: "📎 Sending file: filename.ext (size)"
                int filenameStart = "📎 Sending file: ".length();
                int filenameEnd = message.lastIndexOf(" (");
                if (filenameEnd > filenameStart) {
                    String filename = message.substring(filenameStart, filenameEnd).trim();
                    pendingFileNames.put(sender, filename);
                    System.out.printf("[NAT] 📎 Extracted filename from %s: %s%n", sender, filename);
                }
            } catch (Exception e) {
                System.err.println("[NAT] ⚠️ Failed to parse filename: " + e.getMessage());
            }
        }
        
        // Forward to registered callback if available
        if (messageCallback != null) {
            messageCallback.onMessageReceived(sender, message);
        } else {
            // Default implementation - just log
            System.out.println("[NAT] 💬 Message from " + sender + ": " + message);
        }
    }
    
    /**
     * Set callback for received reliable messages
     * @param callback Callback to handle received messages
     */
    public static void setReliableMessageCallback(ReliableMessageCallback callback) {
        messageCallback = callback;
        System.out.println("[NAT] 📞 Reliable message callback registered");
    }
    
    /**
     * Shutdown reliable messaging
     */
    public static void shutdownReliableMessaging() {
        if (reliableSender != null) {
            reliableSender.shutdown();
            reliableSender = null;
        }
        if (reliableReceiver != null) {
            reliableReceiver.shutdown();
            reliableReceiver = null;
        }
        System.out.println("[NAT] 🛑 Reliable messaging shut down");
    }
    
    /**
     * Get reliable messaging statistics
     */
    public static String getReliableMessagingStats() {
        if (reliableSender == null || reliableReceiver == null) {
            return "Reliable messaging not active";
        }
        return String.format("Sender: %s | Receiver: %s",
            reliableSender.getStats(),
            reliableReceiver.getStats());
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // FILE TRANSFER MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * File transfer callback interface
     */
    public interface FileTransferCallback {
        void onFileTransferRequest(String sender, long fileId, String fileName, long fileSize, int totalChunks);
        void onFileTransferComplete(String peer, long fileId, java.nio.file.Path filePath);
        void onFileTransferError(String peer, long fileId, Exception error);
        void onFileTransferProgress(String peer, long fileId, int current, int total);
    }
    
    private static FileTransferCallback fileTransferCallback = null;
    
    /**
     * Set file transfer callback
     */
    public static void setFileTransferCallback(FileTransferCallback callback) {
        fileTransferCallback = callback;
        System.out.println("[NAT] 📁 File transfer callback registered");
    }
    
    // File transfer sessions - track active transfers
    private static final Map<Long, FileTransferSession> fileTransferSessions = new ConcurrentHashMap<>();
    
    /**
     * File transfer session tracking
     */
    private static class FileTransferSession {
        final String peerUsername;
        final long fileId;
        final InetSocketAddress peerAddress;
        final Role role;
        
        // Instances (only one will be non-null based on role)
        com.saferoom.file_transfer.EnhancedFileTransferSender sender;
        com.saferoom.file_transfer.FileTransferReceiver receiver;
        
        // Progress
        volatile boolean active = true;
        
        enum Role { SENDER, RECEIVER }
        
        FileTransferSession(String peer, long fileId, InetSocketAddress addr, Role role) {
            this.peerUsername = peer;
            this.fileId = fileId;
            this.peerAddress = addr;
            this.role = role;
        }
    }
    
    /**
     * Send file to peer (SENDER role)  
     */
    public static CompletableFuture<Void> sendFile(String targetUser, java.nio.file.Path filePath) {
        InetSocketAddress peerAddr = activePeers.get(targetUser);
        if (peerAddr == null) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("No P2P connection to " + targetUser)
            );
        }
        
        return CompletableFuture.runAsync(() -> {
            long fileId = System.currentTimeMillis();
            
            try {
                System.out.printf("[FILE-SEND] 📤 Sending %s to %s%n",
                    filePath.getFileName(), targetUser);
                
                // STOP KeepAliveManager
                if (globalKeepAlive != null) {
                    System.out.println("[FILE-SEND] ⏸️  Stopping KeepAliveManager");
                    globalKeepAlive.stopMessageListening();
                    Thread.sleep(100);
                }
                
                // Connect stunChannel to peer for file transfer
                synchronized (stunChannel) {
                    stunChannel.connect(peerAddr);
                    System.out.printf("[FILE-SEND] 🔗 Connected to %s%n", peerAddr);
                
                    try {
                        // Create sender - will do normal handshake
                        com.saferoom.file_transfer.EnhancedFileTransferSender sender = 
                            new com.saferoom.file_transfer.EnhancedFileTransferSender(stunChannel);
                        
                        // Send file - sender does handshake + data transfer
                        sender.sendFile(filePath, fileId);
                        
                        System.out.printf("[FILE-SEND] ✅ File sent successfully%n");
                        
                        // NOTE: Don't call onFileTransferComplete here!
                        // That callback is for RECEIVER side only (to send confirmation message)
                        // Sender doesn't need to send any confirmation message
                    } finally {
                        // Disconnect after transfer
                        stunChannel.disconnect();
                        System.out.println("[FILE-SEND] 🔌 Disconnected");
                    }
                }
                
            } catch (Exception e) {
                System.err.printf("[FILE-SEND] ❌ Failed: %s%n", e.getMessage());
                e.printStackTrace();
                if (fileTransferCallback != null) {
                    fileTransferCallback.onFileTransferError(targetUser, fileId, e);
                }
                throw new RuntimeException(e);
            } finally {
                // RESTART KeepAliveManager
                if (globalKeepAlive != null) {
                    System.out.println("[FILE-SEND] ▶️  Resuming KeepAliveManager");
                    globalKeepAlive.startMessageListening(stunChannel);
                }
            }
        }, P2P_EXECUTOR);
    }
    
    /**
     * Accept incoming file transfer (DEPRECATED - now auto-accepts)
     * Kept for compatibility
     */
    public static void acceptFileTransfer(String senderUsername, long fileId, java.nio.file.Path savePath) {
        System.out.println("[FILE-RECV] ⚠️ acceptFileTransfer() called but receiver already auto-started!");
        // Receiver is already running automatically when SYN was received
    }
    
    /**
     * File transfer packet dispatcher
     * Receives packets from KeepAliveManager and forwards to appropriate handler
     */
    private static class FileTransferDispatcher {
        
        // Not needed anymore - receiver auto-starts on SYN
        // Keeping for compatibility
        
        static void forwardPacket(InetSocketAddress senderAddr, ByteBuffer packet) {
            int packetSize = packet.remaining();
            
            // Handshake detection
            if (packetSize == com.saferoom.file_transfer.HandShake_Packet.HEADER_SIZE) {
                byte signal = packet.get(0);
                
                if (signal == com.saferoom.file_transfer.HandShake_Packet.SYN) {
                    // NEW FILE TRANSFER REQUEST - Auto-start receiver!
                    handleIncomingFileTransferSYN(senderAddr, packet);
                    return;
                }
                
                // Other handshake packets (ACK, SYN_ACK) handled by FileTransferSender/Receiver
                return;
            }
            
            // Other packets ignored - receiver is directly connected
        }
        
        private static void handleIncomingFileTransferSYN(InetSocketAddress senderAddr, ByteBuffer synPacket) {
            
            long fileId = com.saferoom.file_transfer.HandShake_Packet.get_file_Id(synPacket);
            long fileSize = com.saferoom.file_transfer.HandShake_Packet.get_file_size(synPacket);
            int totalSeq = com.saferoom.file_transfer.HandShake_Packet.get_total_seq(synPacket);
            
            // Find peer username
            String senderUsername = findUsernameByAddress(senderAddr);
            
            // Get filename from pending map (set by chat message callback)
            String extractedName = pendingFileNames.remove(senderUsername);
            final String fileName;
            if (extractedName == null) {
                // Fallback to generic name
                fileName = "file_" + fileId + ".bin";
                System.out.println("[FILE-RECV] ⚠️ No filename found, using: " + fileName);
            } else {
                fileName = extractedName;
                System.out.printf("[FILE-RECV] ✅ Using filename: %s%n", fileName);
            }
            
            System.out.printf("[FILE-RECV] 📥 SYN received from %s: size=%d bytes, chunks=%d (fileId=%d)%n",
                senderUsername, fileSize, totalSeq, fileId);
            
            // **AUTO-START RECEIVER** - No GUI prompt!
            System.out.println("[FILE-RECV] 🚀 Auto-starting FileTransferReceiver...");
            
            // Stop KeepAliveManager
            if (globalKeepAlive != null) {
                try {
                    globalKeepAlive.stopMessageListening();
                    Thread.sleep(100);
                } catch (Exception e) {
                    System.err.println("[FILE-RECV] ⚠️ Error stopping KeepAliveManager: " + e.getMessage());
                }
            }
            
            // Start receiver in background
            P2P_EXECUTOR.submit(() -> {
                java.nio.file.Path savePath = java.nio.file.Paths.get(
                    System.getProperty("user.home"), "Downloads", fileName);
                
                try {
                    System.out.printf("[FILE-RECV] � Saving to: %s%n", savePath);
                    
                    // Connect to sender
                    synchronized (stunChannel) {
                        stunChannel.connect(senderAddr);
                        System.out.printf("[FILE-RECV] 🔗 Connected to %s%n", senderAddr);
                        
                        try {
                            // Create receiver and start receiving
                            com.saferoom.file_transfer.FileTransferReceiver receiver = 
                                new com.saferoom.file_transfer.FileTransferReceiver();
                            receiver.channel = stunChannel;
                            receiver.filePath = savePath;
                            
                            // Receive file - receiver does handshake + data transfer!
                            receiver.ReceiveData();
                            
                            System.out.printf("[FILE-RECV] ✅ File received successfully: %s%n", savePath);
                            
                            if (fileTransferCallback != null) {
                                fileTransferCallback.onFileTransferComplete(senderUsername, fileId, savePath);
                            }
                        } finally {
                            stunChannel.disconnect();
                            System.out.println("[FILE-RECV] � Disconnected");
                        }
                    }
                } catch (Exception e) {
                    System.err.printf("[FILE-RECV] ❌ Failed: %s%n", e.getMessage());
                    e.printStackTrace();
                    if (fileTransferCallback != null) {
                        fileTransferCallback.onFileTransferError(senderUsername, fileId, e);
                    }
                } finally {
                    // RESTART KeepAliveManager
                    if (globalKeepAlive != null) {
                        System.out.println("[FILE-RECV] ▶️  Restarting KeepAliveManager");
                        globalKeepAlive.startMessageListening(stunChannel);
                    }
                }
            });
        }
    }
    
    /**
     * Called by KeepAliveManager when file transfer packet detected
     */
    public static void onFileTransferPacket(ByteBuffer packet, SocketAddress senderAddr) {
        // Don't log every packet - too much spam
        // Only dispatcher will log important events
        FileTransferDispatcher.forwardPacket((InetSocketAddress) senderAddr, packet);
    }
    
    private static String findUsernameByAddress(InetSocketAddress addr) {
        for (Map.Entry<String, InetSocketAddress> entry : activePeers.entrySet()) {
            if (entry.getValue().equals(addr)) {
                return entry.getKey();
            }
        }
        return "Unknown";
    }
}
