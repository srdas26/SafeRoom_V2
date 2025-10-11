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
    
    // Multiple peer connections support
    private static final Map<String, InetSocketAddress> activePeers = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastActivity = new ConcurrentHashMap<>();
    
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
        
        // Setup keep-alive manager with integrated message listening
        KeepAliveManager keepAlive = new KeepAliveManager(3_000);
        keepAlive.installShutdownHook();
        keepAlive.register(stunChannel, peerAddr);
        keepAlive.startMessageListening(stunChannel); // Integrated message listening
        
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
            Selector regSelector = Selector.open();
            stunChannel.register(regSelector, SelectionKey.OP_READ);
            
            long deadline = System.currentTimeMillis() + 5000; // 5 second timeout
            boolean ackReceived = false;
            
            while (System.currentTimeMillis() < deadline && !ackReceived) {
                if (regSelector.select(100) == 0) continue;
                
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
                    if (!LLS.hasWholeFrame(buf)) continue;
                    
                    byte type = LLS.peekType(buf);
                    if (type == LLS.SIG_ALL_DONE) {
                        ackReceived = true;
                        System.out.println("[P2P] ✅ Registration acknowledged by server");
                        break;
                    }
                }
            }
            
            regSelector.close();
            
            if (!ackReceived) {
                System.err.println("[P2P] Registration timeout - no acknowledgment from server");
                return false;
            }
            
            // CRITICAL: Start listening for incoming P2P notifications after registration
            System.out.println("[P2P] 🎧 Starting notification listener after registration...");
            KeepAliveManager keepAlive = new KeepAliveManager(3_000);
            keepAlive.installShutdownHook();
            keepAlive.startMessageListening(stunChannel);
            System.out.println("[P2P] 📡 Notification listener active - ready for incoming P2P requests");
            
            System.out.println("[P2P] ✅ User registration complete: " + username);
            return true;
            
        } catch (Exception e) {
            System.err.println("[P2P] Registration error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Request P2P connection to target user - UNIDIRECTIONAL initiation
     * @param myUsername Current user's username
     * @param targetUsername Target user to connect to
     * @param signalingServer Signaling server address
     * @return true if P2P connection established successfully
     */
    public static boolean requestP2PConnection(String myUsername, String targetUsername, 
                                             InetSocketAddress signalingServer) {
        try {
            System.out.printf("[P2P] Requesting P2P connection: %s -> %s%n", myUsername, targetUsername);
            
            // Ensure we have an active channel (from registration)
            if (stunChannel == null || !stunChannel.isOpen()) {
                System.err.println("[P2P] No active channel - user not registered?");
                return false;
            }
            
            // Step 1: Send P2P request to server
            ByteBuffer requestPacket = LLS.New_P2PRequest_Packet(myUsername, targetUsername);
            stunChannel.send(requestPacket, signalingServer);
            System.out.println("[P2P] P2P connection request sent to server");
            
            // Step 2: Wait for peer info from server
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
                    
                    if (type == LLS.SIG_PUNCH_INSTRUCT) {
                        // Server sent intelligent hole punch instruction
                        System.out.println("[P2P] 🧠 Received intelligent punch instruction from server");
                        handlePunchInstruction(buf.duplicate());
                        peerInfoReceived = true; // Consider instruction as coordination complete
                        peerSelector.close();
                        return true; // Strategy already executed, no need for legacy hole punch
                    } else if (type == LLS.SIG_PORT) {
                        // Legacy: Server sent peer info directly
                        List<Object> info = LLS.parsePortInfo(buf.duplicate());
                        peerIP = (InetAddress) info.get(0);
                        peerPort = (Integer) info.get(1);
                        peerInfoReceived = true;
                        System.out.printf("[P2P] Legacy peer info received: %s:%d%n", peerIP, peerPort);
                        break;
                    }
                }
            }
            
            if (!peerInfoReceived) {
                System.err.println("[P2P] Timeout waiting for peer info");
                peerSelector.close();
                return false;
            }
            
            // Step 3: Start hole punching process (ONLY for legacy path)
            InetSocketAddress peerAddr = new InetSocketAddress(peerIP, peerPort);
            boolean success = performDirectHolePunching(peerAddr, targetUsername);
            
            peerSelector.close();
            return success;
            
        } catch (Exception e) {
            System.err.printf("[P2P] P2P request error: %s%n", e.getMessage());
            e.printStackTrace();
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
            
            // Setup keep-alive and messaging
            KeepAliveManager keepAlive = new KeepAliveManager(3_000);
            keepAlive.installShutdownHook();
            keepAlive.register(stunChannel, peerAddr);
            keepAlive.startMessageListening(stunChannel);
            
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
            // PHASE 1: Detect NAT type using existing method
            System.out.println("[NAT-PROFILE] Phase 1: Detecting NAT type...");
            byte natType = analyzeSinglePort(stunServers);
            
            if (natType == (byte)0xFE) {
                System.err.println("[NAT-PROFILE] NAT detection failed!");
                return null;
            }
            
            String natTypeStr = (natType == 0x11) ? "SYMMETRIC" : "NON-SYMMETRIC";
            System.out.println("[NAT-PROFILE] Detected NAT type: " + natTypeStr);
            
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
     * Symmetric NAT side: Opens a pool of UDP sockets and sends burst packets to target.
     * This creates multiple NAT mapping holes that converge on the target's stable port.
     * 
     * @param targetIP The non-symmetric peer's public IP
     * @param targetPort The non-symmetric peer's stable port
     * @param numPorts Number of ports to open (typically N/2 from profiled range)
     */
    public static void symmetricPortPoolExpansion(InetAddress targetIP, int targetPort, int numPorts) {
        System.out.println("[SYMMETRIC-PUNCH] Starting port pool expansion: " + numPorts + " ports -> " 
            + targetIP.getHostAddress() + ":" + targetPort);
        
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(numPorts, 50));
        List<DatagramChannel> channels = new ArrayList<>();
        
        try {
            // Phase 1: Open pool of sockets
            for (int i = 0; i < numPorts; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        DatagramChannel channel = DatagramChannel.open();
                        channel.socket().setReuseAddress(true);
                        channel.bind(new InetSocketAddress(0)); // Ephemeral port
                        synchronized (channels) {
                            channels.add(channel);
                        }
                        
                        // Phase 2: Send burst packets
                        InetSocketAddress target = new InetSocketAddress(targetIP, targetPort);
                        ByteBuffer payload = ByteBuffer.allocate(64);
                        payload.put(LLS.SIG_HOLE);
                        payload.put(("SYM-BURST-" + index).getBytes());
                        payload.flip();
                        
                        for (int burst = 0; burst < 5; burst++) {
                            payload.rewind();
                            channel.send(payload, target);
                            Thread.sleep(20); // 20ms between bursts
                        }
                        
                        System.out.println("[SYMMETRIC-PUNCH] Port " + index + " sent burst");
                        
                    } catch (Exception e) {
                        System.err.println("[SYMMETRIC-PUNCH] Port " + index + " failed: " + e.getMessage());
                    }
                });
            }
            
            // Wait for all bursts to complete
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            
            System.out.println("[SYMMETRIC-PUNCH] Port pool expansion complete. " + channels.size() + " channels opened.");
            
        } catch (Exception e) {
            System.err.println("[SYMMETRIC-PUNCH] Pool expansion failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cleanup channels
            for (DatagramChannel channel : channels) {
                try {
                    if (channel.isOpen()) channel.close();
                } catch (Exception ignored) {}
            }
        }
    }
    
    /**
     * Asymmetric NAT side: Scans the symmetric peer's port range to find the active mapping.
     * Sends discovery packets across the predicted port range.
     * 
     * @param targetIP The symmetric peer's public IP
     * @param minPort Start of the port range
     * @param maxPort End of the port range
     */
    public static void scanPortRange(InetAddress targetIP, int minPort, int maxPort) {
        System.out.println("[ASYMMETRIC-SCAN] Scanning port range: " + minPort + "-" + maxPort 
            + " on " + targetIP.getHostAddress());
        
        int rangeSize = maxPort - minPort + 1;
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(rangeSize, 100));
        
        try {
            for (int port = minPort; port <= maxPort; port++) {
                final int targetPort = port;
                executor.submit(() -> {
                    try {
                        if (stunChannel != null && stunChannel.isOpen()) {
                            InetSocketAddress target = new InetSocketAddress(targetIP, targetPort);
                            ByteBuffer payload = ByteBuffer.allocate(64);
                            payload.put(LLS.SIG_HOLE);
                            payload.put(("ASYM-SCAN-" + targetPort).getBytes());
                            payload.flip();
                            
                            stunChannel.send(payload, target);
                        }
                    } catch (Exception e) {
                        // Ignore individual failures (port might not be open)
                    }
                });
            }
            
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            
            System.out.println("[ASYMMETRIC-SCAN] Port range scan complete: " + rangeSize + " ports probed");
            
        } catch (Exception e) {
            System.err.println("[ASYMMETRIC-SCAN] Range scan failed: " + e.getMessage());
            e.printStackTrace();
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
                    symmetricPortPoolExpansion(targetIP, targetPort, numPorts);
                }
                case 0x02 -> {
                    // ASYMMETRIC_SCAN: Scan port range
                    System.out.println("[P2P-INSTRUCT] 🔍 Executing ASYMMETRIC SCAN strategy");
                    int maxPort = targetPort + numPorts - 1;
                    System.out.printf("  Scanning port range %d-%d on %s%n", 
                        targetPort, maxPort, targetIP.getHostAddress());
                    scanPortRange(targetIP, targetPort, maxPort);
                }
                case 0x03 -> {
                    // SYMMETRIC_MIDPOINT_BURST: Birthday Paradox for Symmetric ↔ Symmetric
                    System.out.println("[P2P-INSTRUCT] 🎯 Executing SYMMETRIC MIDPOINT BURST (Birthday Paradox)");
                    System.out.printf("  Opening %d ports, bursting to peer's midpoint %s:%d%n", 
                        numPorts, targetIP.getHostAddress(), targetPort);
                    System.out.println("  Strategy: Continuous burst until connection established");
                    symmetricMidpointBurst(targetIP, targetPort, numPorts, target);
                }
                case 0x00 -> {
                    // STANDARD: Basic hole punch
                    System.out.println("[P2P-INSTRUCT] ⚡ Executing STANDARD hole punch");
                    System.out.printf("  Punching to %s:%d%n", 
                        targetIP.getHostAddress(), targetPort);
                    executeStandardHolePunch(targetIP, targetPort, target);
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
                                                int numPorts, String targetUsername) {
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
                        ByteBuffer burstPayload = ByteBuffer.allocate(128);
                        
                        int burstCount = 0;
                        
                        // Continuous bursting until collision detected
                        while (!connectionEstablished.get()) {
                            burstPayload.clear();
                            burstPayload.put(LLS.SIG_HOLE);
                            burstPayload.put(("MIDPOINT-BURST-" + portIndex + "-" + burstCount).getBytes());
                            burstPayload.flip();
                            
                            channel.send(burstPayload, target);
                            burstCount++;
                            
                            // Check for incoming response (collision detection)
                            ByteBuffer receiveBuffer = ByteBuffer.allocate(1024);
                            InetSocketAddress sender = (InetSocketAddress) channel.receive(receiveBuffer);
                            
                            if (sender != null && !connectionEstablished.getAndSet(true)) {
                                // 🎉 COLLISION DETECTED!
                                long collisionTime = System.currentTimeMillis() - startTime;
                                System.out.printf("\n[BIRTHDAY-PARADOX] 🎉 COLLISION! Port %d received response after %d ms%n",
                                    portIndex, collisionTime);
                                System.out.printf("  Peer responded from: %s%n", sender);
                                System.out.printf("  Total bursts sent: %d%n", burstCount);
                                
                                successfulChannel.set(channel);
                                peerAddress.set(sender);
                                return; // Keep this channel alive
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
                
                // Register peer
                activePeers.put(targetUsername, peer);
                lastActivity.put(targetUsername, System.currentTimeMillis());
                
                // Start keep-alive
                System.out.println("[BIRTHDAY-PARADOX] 💓 Starting keep-alive on established channel");
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
     */
    private static void executeStandardHolePunch(InetAddress targetIP, int targetPort, String targetUsername) {
        try {
            if (stunChannel == null || !stunChannel.isOpen()) {
                System.err.println("[P2P-INSTRUCT] ❌ No active STUN channel!");
                return;
            }
            
            InetSocketAddress targetAddr = new InetSocketAddress(targetIP, targetPort);
            System.out.printf("[STANDARD-PUNCH] 📤 Starting continuous burst to %s%n", targetAddr);
            System.out.println("[STANDARD-PUNCH] Will burst until peer response or 30s timeout");
            
            // Configure channel for non-blocking
            stunChannel.configureBlocking(false);
            Selector selector = Selector.open();
            stunChannel.register(selector, SelectionKey.OP_READ);
            
            long startTime = System.currentTimeMillis();
            long timeout = 30000; // 30 seconds timeout
            boolean peerResponseReceived = false;
            int burstCount = 0;
            
            // Continuous burst with response listening
            while (!peerResponseReceived && (System.currentTimeMillis() - startTime) < timeout) {
                // Send burst packet
                ByteBuffer burstPayload = ByteBuffer.allocate(128);
                burstPayload.put(LLS.SIG_HOLE);
                burstPayload.put(("STANDARD-BURST-" + burstCount).getBytes());
                burstPayload.flip();
                
                stunChannel.send(burstPayload, targetAddr);
                burstCount++;
                
                // Check for peer response (non-blocking)
                if (selector.select(50) > 0) { // 50ms wait
                    selector.selectedKeys().clear();
                    
                    ByteBuffer receiveBuffer = ByteBuffer.allocate(1024);
                    InetSocketAddress sender = (InetSocketAddress) stunChannel.receive(receiveBuffer);
                    
                    if (sender != null) {
                        long responseTime = System.currentTimeMillis() - startTime;
                        System.out.printf("\n[STANDARD-PUNCH] ✅ Peer response received after %d ms!%n", responseTime);
                        System.out.printf("  Peer address: %s%n", sender);
                        System.out.printf("  Total bursts sent: %d%n", burstCount);
                        
                        peerResponseReceived = true;
                        
                        // Register peer
                        activePeers.put(targetUsername, sender);
                        lastActivity.put(targetUsername, System.currentTimeMillis());
                        
                        System.out.println("[STANDARD-PUNCH] 💓 Starting keep-alive mechanism");
                        startKeepAlive(stunChannel, sender, targetUsername);
                        break;
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
            
            if (!peerResponseReceived) {
                System.err.println("\n[STANDARD-PUNCH] ❌ TIMEOUT: No peer response after 30 seconds");
                System.err.printf("  Total bursts sent: %d%n", burstCount);
                System.err.println("  Check Wireshark to verify UDP packets are being sent");
            }
            
        } catch (Exception e) {
            System.err.println("[STANDARD-PUNCH] ❌ Failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
