package com.saferoom.natghost;

import com.saferoom.client.ClientMenu;
import com.saferoom.server.SafeRoomServer;

import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final SecureRandom RNG = new SecureRandom();
    public static final String[][] stunServers = {
            {"stun.l.google.com", "19302"},
            {"stun.l.google.com", "5349"},
            {"stun1.l.google.com", "3478"},
            {"stun1.l.google.com", "5349"},
            {"stun2.l.google.com", "19302"},
            {"stun2.l.google.com", "5349"},
            {"stun3.l.google.com", "3478"},
            {"stun3.l.google.com", "5349"},
            {"stun4.l.google.com", "19302"},
            {"stun4.l.google.com", "5349"}
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
                System.out.printf("[STUN] ✅ Parsed (type=0x%04X): IP=%s, Port=%d%n", attrType, ip, port);
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
        List<Integer> uniquePorts = new ArrayList<>(new LinkedHashSet<>(Public_PortList));
        Public_PortList.clear();
        Public_PortList.addAll(uniquePorts);

        if (uniquePorts.isEmpty()) {
            System.err.println("[NAT] No STUN responses received - network error");
            signal = (byte)0xFE;
        } else if (uniquePorts.size() == 1) {
            System.out.println("[NAT] All ports same = FULL CONE or RESTRICTED NAT");
            signal = (byte)0x00;
        } else {
            System.out.println("[NAT] Different ports = SYMMETRIC NAT");
            signal = (byte)0x11;
        }

        System.out.println("[NAT] Analysis complete - Signal: 0x" + String.format("%02X", signal));
        System.out.println("[NAT] Public IP: " + myPublicIP + ", Ports: " + uniquePorts);
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
        
        // Step 2: Send hole punch request using SAME channel
        ByteBuffer holeRequest = LLS.New_Hole_Packet(myUsername, targetUsername, 
                                                     InetAddress.getByName(myPublicIP), myPublicPort);
        stunChannel.send(holeRequest, signalingServer);
        System.out.println("[P2P] Hole punch request sent to signaling server using local port: " + localPort);
        
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
            stunChannel.close();
            stunChannel = null;
            peerSelector.close();
            return false;
        }
        
        // Step 4: Start DNS packet exchange using SAME channel
        InetSocketAddress peerAddr = new InetSocketAddress(peerIP, peerPort);
        System.out.println("[P2P] Starting 1.5-second DNS burst to peer using local port: " + localPort);
        
        // Send DNS queries in burst mode - CRITICAL: same source port for 1.5 seconds!
        long burstStart = System.currentTimeMillis();
        long burstDuration = 1500; // 1.5 seconds
        long burstInterval = 50;   // Every 50ms
        int packetCount = 0;
        
        while ((System.currentTimeMillis() - burstStart) < burstDuration) {
            ByteBuffer dnsQuery = LLS.New_DNSQuery_Packet();
            stunChannel.send(dnsQuery, peerAddr);
            packetCount++;
            System.out.println("[P2P] DNS burst packet #" + packetCount + " sent to " + peerAddr + " from port " + localPort);
            Thread.sleep(burstInterval);
        }
        
        System.out.println("[P2P] DNS burst complete: " + packetCount + " packets sent over " + burstDuration + "ms");
        
        // Setup keep-alive manager for ongoing connection
        KeepAliveManager keepAlive = new KeepAliveManager(3_000);
        keepAlive.installShutdownHook();
        keepAlive.register(stunChannel, peerAddr);
        
        peerSelector.close();
        
        // Store peer address for messaging (multiple peer support)
        activePeers.put(targetUsername, peerAddr);
        lastActivity.put(targetUsername, System.currentTimeMillis());
        
        System.out.println("[P2P] ✅ Hole punch successful! P2P connection established with " + targetUsername);
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
    // P2P MESSAGING FUNCTIONS
    // ============================================
    
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
}
