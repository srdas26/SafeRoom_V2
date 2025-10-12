package com.saferoom.server;

import com.saferoom.natghost.LLS;
import com.saferoom.natghost.RegisteredUser;

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
    public static final int SIGNALING_PORT = SafeRoomServer.udpPort1;
    
    // Modern hashmap for peer matching: <host_username, target_username> -> PeerInfo
    private static final Map<String, PeerInfo> PEER_REQUESTS = new ConcurrentHashMap<>();
    
    // NEW: Registered users for unidirectional P2P initiation
    private static final Map<String, RegisteredUser> REGISTERED_USERS = new ConcurrentHashMap<>();
    private static final long USER_REGISTRATION_TIMEOUT_MS = 300_000; // 5 minutes
    
    // NEW: NAT profiles for coordinated hole punching
    static class NATProfile {
        byte natType;
        int minPort;
        int maxPort;
        int profiledPorts;
        long timestamp;
        
        NATProfile(byte natType, int minPort, int maxPort, int profiledPorts) {
            this.natType = natType;
            this.minPort = minPort;
            this.maxPort = maxPort;
            this.profiledPorts = profiledPorts;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    private static final Map<String, NATProfile> NAT_PROFILES = new ConcurrentHashMap<>();
    
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
     * Handle user registration (SIG_REGISTER)
     * Client registers with server on startup with NAT info
     */
    private void handleUserRegistration(ByteBuffer buf, DatagramChannel channel, InetSocketAddress from) {
        try {
            List<Object> parsed = LLS.parseRegisterPacket(buf);
            String username = (String) parsed.get(2);
            InetAddress publicIP = (InetAddress) parsed.get(4);
            int publicPort = (Integer) parsed.get(5);
            InetAddress localIP = (InetAddress) parsed.get(6);
            int localPort = (Integer) parsed.get(7);
            
            RegisteredUser user = new RegisteredUser(username, publicIP, publicPort, localIP, localPort, from);
            REGISTERED_USERS.put(username, user);
            
            System.out.printf("📝 USER_REGISTERED: %s%n", user);
            
            // Send acknowledgment back to client
            ByteBuffer ack = LLS.New_Multiplex_Packet(LLS.SIG_ALL_DONE, "server", username);
            channel.send(ack, from);
            
        } catch (Exception e) {
            System.err.printf("❌ Error handling user registration from %s: %s%n", from, e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Handle P2P connection request (SIG_P2P_REQUEST)
     * Client requests P2P connection to target - INTELLIGENT coordination based on NAT profiles
     */
    private void handleP2PRequest(ByteBuffer buf, DatagramChannel channel, InetSocketAddress from) {
        try {
            List<Object> parsed = LLS.parseP2PRequestPacket(buf);
            String requester = (String) parsed.get(2);
            String target = (String) parsed.get(3);
            
            System.out.printf("🎯 P2P_REQUEST: %s wants to connect to %s%n", requester, target);
            
            // Check if both users are registered
            RegisteredUser requesterUser = REGISTERED_USERS.get(requester);
            RegisteredUser targetUser = REGISTERED_USERS.get(target);
            
            if (requesterUser == null) {
                System.err.printf("❌ Requester %s not registered%n", requester);
                return;
            }
            
            if (targetUser == null) {
                System.err.printf("❌ Target %s not registered%n", target);
                return;
            }
            
            // Check if registrations are still valid
            if (!requesterUser.isValid(USER_REGISTRATION_TIMEOUT_MS)) {
                System.err.printf("❌ Requester %s registration expired%n", requester);
                REGISTERED_USERS.remove(requester);
                return;
            }
            
            if (!targetUser.isValid(USER_REGISTRATION_TIMEOUT_MS)) {
                System.err.printf("❌ Target %s registration expired%n", target);
                REGISTERED_USERS.remove(target);
                return;
            }
            
            System.out.printf("✅ Both users registered and valid%n");
            
            // 🔑 STORE P2P REQUEST - Needed for NAT profile coordination
            String requestKey = requester + "->" + target;
            PeerInfo requesterInfo = new PeerInfo(
                requester, target, 
                requesterUser.publicIP, requesterUser.publicPort,
                requesterUser.localIP, requesterUser.localPort,
                from
            );
            PEER_REQUESTS.put(requestKey, requesterInfo);
            System.out.printf("📝 Stored P2P request: %s%n", requestKey);
            
            // CHECK FOR NAT PROFILES - Use intelligent coordination if available
            NATProfile requesterProfile = NAT_PROFILES.get(requester);
            NATProfile targetProfile = NAT_PROFILES.get(target);
            
            if (requesterProfile != null && targetProfile != null) {
                System.out.println("🧠 NAT PROFILES AVAILABLE - Using intelligent coordination");
                coordinateIntelligentHolePunch(requesterProfile, targetProfile, 
                    requesterUser, targetUser, channel);
                return;
            }
            
            // PARTIAL: Only requester has profile - wait for target's profile
            if (requesterProfile != null && targetProfile == null) {
                System.out.printf("⏳ Waiting for %s's NAT profile (requester profile ready)%n", target);
                return;
            }
            
            // PARTIAL: Only target has profile - coordinate now
            if (requesterProfile == null && targetProfile != null) {
                System.out.printf("⏳ Waiting for %s's NAT profile (target profile ready)%n", requester);
                return;
            }
            
            // NO PROFILES: Wait for both to submit NAT profiles
            System.out.println("⏳ Waiting for NAT profiles from both peers");
            System.out.println("   Will coordinate automatically when both profiles are received");
            
        } catch (Exception e) {
            System.err.printf("❌ Error handling P2P request: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Intelligent hole punch coordination based on NAT profiles
     */
    private void coordinateIntelligentHolePunch(NATProfile requesterProfile, NATProfile targetProfile,
                                                RegisteredUser requesterUser, RegisteredUser targetUser,
                                                DatagramChannel channel) {
        try {
            // 🆕 CHECK SAME LAN FIRST - If same public IP, use local addresses directly
            boolean sameNAT = requesterUser.publicIP.equals(targetUser.publicIP);
            System.out.printf("🔍 Same NAT detection: %s (Requester=%s, Target=%s)%n", 
                sameNAT ? "YES - using local IPs" : "NO - using intelligent NAT strategy",
                requesterUser.publicIP.getHostAddress(),
                targetUser.publicIP.getHostAddress());
            
            if (sameNAT && requesterUser.localIP != null && targetUser.localIP != null) {
                // Same LAN - use local addresses with STANDARD hole punch strategy
                System.out.println("🏠 SAME LAN detected - using local IPs with STANDARD burst strategy");
                
                // Send STANDARD punch instruction to requester (burst to target's LOCAL address)
                byte[] requesterInstruction = LLS.createPunchInstructPacket(
                    requesterUser.username,
                    targetUser.username,
                    targetUser.localIP,      // Use LOCAL IP
                    targetUser.localPort,    // Use LOCAL port
                    (byte) 0x00,             // STANDARD strategy
                    1                        // numPorts (not used for standard)
                );
                channel.send(ByteBuffer.wrap(requesterInstruction), 
                    new InetSocketAddress(requesterUser.publicIP, requesterUser.publicPort));
                
                // Send STANDARD punch instruction to target (burst to requester's LOCAL address)
                byte[] targetInstruction = LLS.createPunchInstructPacket(
                    targetUser.username,
                    requesterUser.username,
                    requesterUser.localIP,   // Use LOCAL IP
                    requesterUser.localPort, // Use LOCAL port
                    (byte) 0x00,             // STANDARD strategy
                    1                        // numPorts (not used for standard)
                );
                channel.send(ByteBuffer.wrap(targetInstruction), 
                    new InetSocketAddress(targetUser.publicIP, targetUser.publicPort));
                
                System.out.println("✅ Same LAN coordination complete - sent LOCAL addresses with STANDARD burst instructions");
                System.out.printf("   Requester will burst to: %s:%d%n", targetUser.localIP.getHostAddress(), targetUser.localPort);
                System.out.printf("   Target will burst to: %s:%d%n", requesterUser.localIP.getHostAddress(), requesterUser.localPort);
                return;
            }
            
            boolean requesterSymmetric = (requesterProfile.natType == 0x11);
            boolean targetSymmetric = (targetProfile.natType == 0x11);
            
            System.out.printf("🎯 NAT Strategy: %s (%s) ↔ %s (%s)%n",
                requesterUser.username, requesterSymmetric ? "SYMMETRIC" : "NON-SYMMETRIC",
                targetUser.username, targetSymmetric ? "SYMMETRIC" : "NON-SYMMETRIC");
            
            if (requesterSymmetric && !targetSymmetric) {
                // Case 1: Requester symmetric, Target non-symmetric
                sendSymmetricBurstInstruction(requesterUser, targetUser, requesterProfile, targetProfile, channel);
                sendAsymmetricScanInstruction(targetUser, requesterUser, requesterProfile, channel);
                
            } else if (!requesterSymmetric && targetSymmetric) {
                // Case 2: Requester non-symmetric, Target symmetric
                sendStandardHolePunchInstruction(requesterUser, targetUser, targetProfile, channel);
                sendSymmetricBurstInstruction(targetUser, requesterUser, targetProfile, requesterProfile, channel);
                
            } else if (!requesterSymmetric && !targetSymmetric) {
                // Case 3: Both non-symmetric - standard hole punch
                sendStandardHolePunchInstruction(requesterUser, targetUser, targetProfile, channel);
                sendStandardHolePunchInstruction(targetUser, requesterUser, requesterProfile, channel);
                
            } else {
                // Case 4: Both symmetric - Birthday Paradox Strategy
                System.out.println("🎯 Both peers symmetric - using Birthday Paradox burst coordination");
                
                // Calculate midpoint ports for both peers
                int requesterMidpoint = (requesterProfile.minPort + requesterProfile.maxPort) / 2;
                int targetMidpoint = (targetProfile.minPort + targetProfile.maxPort) / 2;
                
                System.out.printf("   Requester midpoint: %d (range: %d-%d)%n", 
                    requesterMidpoint, requesterProfile.minPort, requesterProfile.maxPort);
                System.out.printf("   Target midpoint: %d (range: %d-%d)%n", 
                    targetMidpoint, targetProfile.minPort, targetProfile.maxPort);
                
                // Send mutual burst instructions with midpoint targets
                sendSymmetricMidpointBurstInstruction(requesterUser, targetUser, 
                    requesterProfile, targetMidpoint, channel);
                sendSymmetricMidpointBurstInstruction(targetUser, requesterUser, 
                    targetProfile, requesterMidpoint, channel);
            }
            
            System.out.println("✅ Intelligent hole punch coordination complete");
            
        } catch (Exception e) {
            System.err.println("❌ Failed to coordinate intelligent hole punch: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Legacy hole punch coordination (fallback when NAT profiles not available)
     */
    private void coordinateLegacyHolePunch(RegisteredUser requesterUser, RegisteredUser targetUser,
                                          DatagramChannel channel, InetSocketAddress from) {
        try {
            // Same NAT detection
            boolean sameNAT = requesterUser.publicIP.equals(targetUser.publicIP);
            System.out.printf("🔍 Same NAT detection: %s%n", sameNAT ? "YES - using local IPs" : "NO - using public IPs");
            
            if (sameNAT) {
                // Use local IPs for same-NAT communication
                ByteBuffer targetInfoPacket = LLS.New_PortInfo_Packet(
                    targetUser.username, requesterUser.username, LLS.SIG_PORT, 
                    targetUser.localIP, targetUser.localPort
                );
                channel.send(targetInfoPacket, from);
                System.out.printf("📤 Sent %s's LOCAL info (%s:%d) to %s%n", 
                    targetUser.username, targetUser.localIP.getHostAddress(), targetUser.localPort, requesterUser.username);
            } else {
                // Use public IPs for different NATs
                ByteBuffer targetInfoPacket = LLS.New_PortInfo_Packet(
                    targetUser.username, requesterUser.username, LLS.SIG_PORT, 
                    targetUser.publicIP, targetUser.publicPort
                );
                channel.send(targetInfoPacket, from);
                System.out.printf("📤 Sent %s's PUBLIC info (%s:%d) to %s%n", 
                    targetUser.username, targetUser.publicIP.getHostAddress(), targetUser.publicPort, requesterUser.username);
            }
            
            // Send notification to target about incoming P2P request
            ByteBuffer notificationPacket;
            if (sameNAT) {
                notificationPacket = LLS.New_P2PNotify_Packet(
                    requesterUser.username, targetUser.username,
                    requesterUser.localIP, requesterUser.localPort,
                    requesterUser.localIP, requesterUser.localPort
                );
            } else {
                notificationPacket = LLS.New_P2PNotify_Packet(
                    requesterUser.username, targetUser.username,
                    requesterUser.publicIP, requesterUser.publicPort,
                    requesterUser.localIP, requesterUser.localPort
                );
            }
            
            System.out.printf("🔔 Sending legacy P2P notification to %s at %s%n", 
                targetUser.username, targetUser.clientAddress);
            channel.send(notificationPacket, targetUser.clientAddress);
            System.out.printf("📤 Legacy P2P notification sent to %s about %s's request%n", 
                targetUser.username, requesterUser.username);
            
            System.out.printf("🎉 Legacy P2P initiation complete for %s -> %s%n", 
                requesterUser.username, targetUser.username);
            
        } catch (Exception e) {
            System.err.printf("❌ Error in legacy hole punch coordination: %s%n", e.getMessage());
            e.printStackTrace();
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
            // SO_REUSEADDR aktif et
            channel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
            channel.bind(new InetSocketAddress(SIGNALING_PORT));
            channel.register(selector, SelectionKey.OP_READ);

            System.out.println("🎯 P2P Signaling Server running on port " + SIGNALING_PORT + " (SO_REUSEADDR enabled)");
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
                        case LLS.SIG_REGISTER -> {
                            // User registration - client registers with server on startup
                            handleUserRegistration(buf.duplicate(), channel, inet);
                        }
                        case LLS.SIG_P2P_REQUEST -> {
                            // P2P connection request - unidirectional initiation
                            handleP2PRequest(buf.duplicate(), channel, inet);
                        }
                        case LLS.SIG_NAT_PROFILE -> {
                            // NAT profile from client - store for coordinated hole punching
                            handleNATProfile(buf.duplicate(), channel, inet);
                        }
                        case LLS.SIG_HOLE -> {
                            // Modern hole punch request - single packet with IP/port info
                            handleHolePunchRequest(buf.duplicate(), channel, inet);
                        }
                        case LLS.SIG_PUNCH_BURST -> {
                            // 🔒 P2P burst packets - SERVER SHOULD NEVER HANDLE THESE!
                            // These packets are for DIRECT peer-to-peer communication
                            // Server's role is COORDINATION ONLY, not packet forwarding
                            System.out.printf("⚠️ Received SIG_PUNCH_BURST from %s - IGNORING (P2P packets should NOT reach server!)%n", from);
                            System.out.println("⚠️ This indicates possible NAT traversal misconfiguration!");
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

                // Cleanup old states and expired registrations
                long now = System.currentTimeMillis();
                if (now - lastCleanup > CLEANUP_INTERVAL_MS) {
                    // Clean up old peer states
                    int oldStatesSize = STATES.size();
                    STATES.entrySet().removeIf(e -> (now - e.getValue().lastSeenMs) > 120_000);
                    int newStatesSize = STATES.size();
                    if (oldStatesSize > newStatesSize) {
                        System.out.printf("🧹 Cleaned up %d old peer states%n", oldStatesSize - newStatesSize);
                    }
                    
                    // Clean up expired user registrations
                    int oldUsersSize = REGISTERED_USERS.size();
                    REGISTERED_USERS.entrySet().removeIf(e -> !e.getValue().isValid(USER_REGISTRATION_TIMEOUT_MS));
                    int newUsersSize = REGISTERED_USERS.size();
                    if (oldUsersSize > newUsersSize) {
                        System.out.printf("🧹 Cleaned up %d expired user registrations%n", oldUsersSize - newUsersSize);
                    }
                    
                    // Clean up old peer requests
                    int oldRequestsSize = PEER_REQUESTS.size();
                    PEER_REQUESTS.entrySet().removeIf(e -> (now - e.getValue().timestamp) > 120_000);
                    int newRequestsSize = PEER_REQUESTS.size();
                    if (oldRequestsSize > newRequestsSize) {
                        System.out.printf("🧹 Cleaned up %d old peer requests%n", oldRequestsSize - newRequestsSize);
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
    
    // ============= NAT PROFILING HANDLERS =============
    
    /**
     * Handles NAT profile packet from client.
     * Stores the profile and checks if both peers have registered for coordinated hole punching.
     */
    private void handleNATProfile(ByteBuffer buffer, DatagramChannel channel, InetSocketAddress from) {
        try {
            List<Object> parsed = LLS.parseNATProfilePacket(buffer);
            String username = (String) parsed.get(2);
            byte natType = (Byte) parsed.get(3);
            int minPort = (Integer) parsed.get(4);
            int maxPort = (Integer) parsed.get(5);
            int profiledPorts = (Integer) parsed.get(6);
            
            System.out.printf("📊 NAT Profile received: user=%s, type=%s, range=%d-%d, probes=%d%n",
                username, 
                (natType == 0x11 ? "SYMMETRIC" : "NON-SYMMETRIC"),
                minPort, maxPort, profiledPorts);
            
            // Store profile
            NAT_PROFILES.put(username, new NATProfile(natType, minPort, maxPort, profiledPorts));
            
            // Check if this completes a pending P2P request
            checkAndCoordinateHolePunching(username, channel);
            
        } catch (Exception e) {
            System.err.println("❌ Failed to parse NAT profile: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Checks if both peers in a P2P connection have submitted NAT profiles.
     * If so, sends coordinated hole punching instructions based on NAT types.
     */
    private void checkAndCoordinateHolePunching(String username, DatagramChannel channel) {
        // Find all pending P2P requests involving this user
        for (Map.Entry<String, PeerInfo> entry : PEER_REQUESTS.entrySet()) {
            PeerInfo peerInfo = entry.getValue();
            
            // Check if both requester and target have profiles
            if (peerInfo.username.equals(username) || peerInfo.targetUsername.equals(username)) {
                String requester = peerInfo.username;
                String target = peerInfo.targetUsername;
                
                NATProfile requesterProfile = NAT_PROFILES.get(requester);
                NATProfile targetProfile = NAT_PROFILES.get(target);
                
                if (requesterProfile != null && targetProfile != null) {
                    System.out.printf("🔗 Both profiles available for %s ↔ %s - coordinating hole punch%n", 
                        requester, target);
                    
                    // Get registered user info for IP/port
                    RegisteredUser requesterUser = REGISTERED_USERS.get(requester);
                    RegisteredUser targetUser = REGISTERED_USERS.get(target);
                    
                    if (requesterUser == null || targetUser == null) {
                        System.err.println("❌ Missing registration for users - cannot coordinate");
                        continue;
                    }
                    
                    // Use intelligent coordination
                    try {
                        coordinateIntelligentHolePunch(requesterProfile, targetProfile, 
                            requesterUser, targetUser, channel);
                    } catch (Exception e) {
                        System.err.printf("❌ Failed to coordinate hole punch: %s%n", e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        }
    }
    
    /**
     * Coordinates hole punching strategy based on peer NAT types.
     * Strategies:
     * 1. Symmetric ↔ Non-Symmetric: Burst from symmetric + scan from non-symmetric
     * 2. Non-Symmetric ↔ Non-Symmetric: Standard hole punch (both sides single port)
     * 3. Symmetric ↔ Symmetric: Both sides burst (experimental)
     */
    private void coordinateByNATType(NATProfile profile1, NATProfile profile2,
                                     RegisteredUser user1, RegisteredUser user2,
                                     PeerInfo peerInfo) {
        try {
            DatagramChannel channel = DatagramChannel.open();
            channel.socket().setReuseAddress(true);
            channel.bind(new InetSocketAddress(SIGNALING_PORT));
            
            boolean user1Symmetric = (profile1.natType == 0x11);
            boolean user2Symmetric = (profile2.natType == 0x11);
            
            System.out.printf("🎯 NAT Strategy: %s (%s) ↔ %s (%s)%n",
                user1.username, user1Symmetric ? "SYM" : "ASYM",
                user2.username, user2Symmetric ? "SYM" : "ASYM");
            
            if (user1Symmetric && !user2Symmetric) {
                // Case 1: User1 symmetric, User2 non-symmetric
                // User1: Burst from port pool
                // User2: Scan User1's port range
                sendSymmetricBurstInstruction(user1, user2, profile1, profile2, channel);
                sendAsymmetricScanInstruction(user2, user1, profile1, channel);
                
            } else if (!user1Symmetric && user2Symmetric) {
                // Case 2: User1 non-symmetric, User2 symmetric (reverse of case 1)
                sendSymmetricBurstInstruction(user2, user1, profile2, profile1, channel);
                sendAsymmetricScanInstruction(user1, user2, profile2, channel);
                
            } else if (!user1Symmetric && !user2Symmetric) {
                // Case 3: Both non-symmetric - standard hole punch
                sendStandardHolePunchInstruction(user1, user2, profile2, channel);
                sendStandardHolePunchInstruction(user2, user1, profile1, channel);
                
            } else {
                // Case 4: Both symmetric - experimental dual burst
                System.out.println("⚠️ Both peers symmetric - using experimental dual burst strategy");
                sendSymmetricBurstInstruction(user1, user2, profile1, profile2, channel);
                sendSymmetricBurstInstruction(user2, user1, profile2, profile1, channel);
            }
            
            channel.close();
            System.out.println("✅ Hole punch coordination complete");
            
        } catch (Exception e) {
            System.err.println("❌ Failed to coordinate hole punching: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Sends burst instruction to symmetric NAT peer.
     */
    private void sendSymmetricBurstInstruction(RegisteredUser symUser, RegisteredUser targetUser, 
                                               NATProfile symProfile, NATProfile targetProfile,
                                               DatagramChannel channel) throws Exception {
        int numPorts = (symProfile.maxPort - symProfile.minPort + 1) / 2; // Open N/2 ports
        numPorts = Math.min(numPorts, 100); // Cap at 100 ports
        
        // Target port: use NAT-detected port for non-symmetric targets
        int targetPort = targetProfile.minPort; // For non-symmetric, minPort = maxPort = detected port
        
        byte[] packet = LLS.createPunchInstructPacket(
            symUser.username,
            targetUser.username,
            targetUser.publicIP,
            targetPort,  // Use NAT-detected port, NOT registration port!
            (byte) 0x01, // SYMMETRIC_BURST strategy
            numPorts
        );
        
        InetSocketAddress symAddr = new InetSocketAddress(symUser.publicIP, symUser.publicPort);
        channel.send(ByteBuffer.wrap(packet), symAddr);
        
        System.out.printf("📤 SYMMETRIC BURST instruction → %s (open %d ports → %s:%d [NAT-detected])%n",
            symUser.username, numPorts, 
            targetUser.publicIP.getHostAddress(), targetPort);
    }
    
    /**
     * Sends scan instruction to non-symmetric NAT peer.
     */
    private void sendAsymmetricScanInstruction(RegisteredUser asymUser, RegisteredUser targetUser,
                                               NATProfile targetProfile, DatagramChannel channel) throws Exception {
        int rangeSize = targetProfile.maxPort - targetProfile.minPort + 1;
        
        byte[] packet = LLS.createPunchInstructPacket(
            asymUser.username,
            targetUser.username,
            targetUser.publicIP,
            targetProfile.minPort, // Send min port (client will scan min-max)
            (byte) 0x02, // ASYMMETRIC_SCAN strategy
            rangeSize
        );
        
        // Embed max port in the packet (need to extend packet format)
        // For now, client will calculate: maxPort = minPort + numPorts - 1
        
        InetSocketAddress asymAddr = new InetSocketAddress(asymUser.publicIP, asymUser.publicPort);
        channel.send(ByteBuffer.wrap(packet), asymAddr);
        
        System.out.printf("📤 ASYMMETRIC SCAN instruction → %s (scan %d-%d on %s)%n",
            asymUser.username, 
            targetProfile.minPort, targetProfile.maxPort,
            targetUser.publicIP.getHostAddress());
    }
    
    /**
     * Sends symmetric midpoint burst instruction for Symmetric ↔ Symmetric NAT.
     * Birthday Paradox Strategy: Both peers burst to each other's port range midpoint.
     * 
     * @param symUser The symmetric NAT user (sender)
     * @param targetUser The target symmetric NAT user
     * @param symProfile The sender's NAT profile
     * @param targetMidpoint The target's port range midpoint to burst towards
     * @param channel Communication channel
     */
    private void sendSymmetricMidpointBurstInstruction(RegisteredUser symUser, RegisteredUser targetUser,
                                                       NATProfile symProfile, int targetMidpoint,
                                                       DatagramChannel channel) throws Exception {
        // Calculate number of ports to open (use full range for birthday paradox)
        int numPorts = (symProfile.maxPort - symProfile.minPort + 1) / 2;
        numPorts = Math.max(100, Math.min(numPorts, 500)); // Min 100, max 500 ports
        
        byte[] packet = LLS.createPunchInstructPacket(
            symUser.username,
            targetUser.username,
            targetUser.publicIP,
            targetMidpoint, // Target the midpoint of peer's port range
            (byte) 0x03, // NEW STRATEGY: SYMMETRIC_MIDPOINT_BURST
            numPorts
        );
        
        InetSocketAddress symAddr = new InetSocketAddress(symUser.publicIP, symUser.publicPort);
        channel.send(ByteBuffer.wrap(packet), symAddr);
        
        System.out.printf("📤 SYMMETRIC MIDPOINT BURST → %s (open %d ports → %s:~%d midpoint)%n",
            symUser.username, numPorts,
            targetUser.publicIP.getHostAddress(), targetMidpoint);
    }
    
    /**
     * Sends standard hole punch instruction (for non-symmetric ↔ non-symmetric).
     */
    private void sendStandardHolePunchInstruction(RegisteredUser user, RegisteredUser targetUser,
                                                  NATProfile targetProfile,
                                                  DatagramChannel channel) throws Exception {
        // For non-symmetric NAT, use the port from NAT detection (minPort = maxPort = detected port)
        int targetNATPort = targetProfile.minPort;
        
        byte[] packet = LLS.createPunchInstructPacket(
            user.username,
            targetUser.username,
            targetUser.publicIP,
            targetNATPort,  // Use NAT-detected port, NOT registration port!
            (byte) 0x00, // STANDARD strategy
            1 // Single port
        );
        
        InetSocketAddress userAddr = new InetSocketAddress(user.publicIP, user.publicPort);
        channel.send(ByteBuffer.wrap(packet), userAddr);
        
        System.out.printf("📤 STANDARD hole punch instruction → %s (target: %s:%d [NAT-detected])%n",
            user.username, 
            targetUser.publicIP.getHostAddress(), targetNATPort);
    }
}
