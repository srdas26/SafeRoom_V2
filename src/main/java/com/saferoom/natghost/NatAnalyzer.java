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

public class NatAnalyzer {

    public static final List<Integer> Public_PortList = new ArrayList<>();
    public static String myPublicIP;
    public static byte   signal;

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

    private static final int   MIN_CHANNELS       = 4;
    private static final long  MATCH_TIMEOUT_MS   = 20_000;
    private static final long  RESEND_INTERVAL_MS = 1_000;
    private static final long  SELECT_BLOCK_MS    = 50;

    private static ByteBuffer stunPacket() {
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

    private static void parseStunResponse(ByteBuffer buffer, List<Integer> list) {
        buffer.position(20);
        while (buffer.remaining() >= 4) {
            short attrType = buffer.getShort();
            short attrLen  = buffer.getShort();
            if (attrType == 0x0001) {
                buffer.get(); // ignore
                buffer.get(); // family
                int port = buffer.getShort() & 0xFFFF;
                byte[] addrBytes = new byte[4];
                buffer.get(addrBytes);
                String ip = (addrBytes[0] & 0xFF) + "." + (addrBytes[1] & 0xFF) + "." +
                            (addrBytes[2] & 0xFF) + "." + (addrBytes[3] & 0xFF);
                myPublicIP = ip;
                list.add(port);
            } else {
                buffer.position(buffer.position() + attrLen);
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

    public static byte analyzer(String[][] servers) throws Exception {
        Selector selector = Selector.open();
        DatagramChannel ch = DatagramChannel.open();
        ch.configureBlocking(false);
        ch.bind(new InetSocketAddress(0));
        ch.register(selector, SelectionKey.OP_READ);

        for (String[] s : servers) {
            try { InetAddress.getByName(s[0]); } catch (UnknownHostException e) { continue; }
            ch.send(stunPacket().duplicate(), new InetSocketAddress(s[0], Integer.parseInt(s[1])));
        }

        long deadline = System.nanoTime() + 100_000_000L;
        while (System.nanoTime() < deadline) {
            if (selector.selectNow() == 0) continue;
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next(); it.remove();
                DatagramChannel rc = (DatagramChannel) key.channel();
                ByteBuffer recv = ByteBuffer.allocate(512);
                rc.receive(recv);
                recv.flip();
                parseStunResponse(recv, Public_PortList);
            }
        }

        List<Integer> uniq = new ArrayList<>(new LinkedHashSet<>(Public_PortList));
        Public_PortList.clear();
        Public_PortList.addAll(uniq);

        signal = (Public_PortList.size() >= 2)
                ? (allEqual(Public_PortList) ? (byte)0x00 : (byte)0x11)
                : (byte)0xFE;

        System.out.println("[STUN] NAT signal = 0x" + String.format("%02X", signal));
        return signal;
    }

    // ---------- HOLE PUNCH ----------
    public static void multiplexer(InetSocketAddress serverAddr) throws Exception {
        byte sig = analyzer(stunServers);
        if (ClientMenu.myUsername == null || ClientMenu.target_username == null)
            throw new IllegalStateException("Username/Target null!");

        int holeCount = Math.max(Public_PortList.size(), MIN_CHANNELS);

        Selector selector = Selector.open();
        List<DatagramChannel> channels = new ArrayList<>(holeCount);

        // KeepAliveManager kuruluyor
        KeepAliveManager KAM = new KeepAliveManager(2_000);
        KAM.installShutdownHook();

        ByteBuffer hello = LLS.New_Hello_Packet(ClientMenu.myUsername, ClientMenu.target_username, LLS.SIG_HELLO);

        // 1) HELLO
        for (int i = 0; i < holeCount; i++) {
            DatagramChannel dc = DatagramChannel.open();
            dc.configureBlocking(false);
            dc.bind(new InetSocketAddress(0));
            dc.send(hello.duplicate(), serverAddr);
            dc.register(selector, SelectionKey.OP_READ);
            channels.add(dc);

            InetSocketAddress local = (InetSocketAddress) dc.getLocalAddress();
            System.out.println("[Client] HELLO sent from local port: " + local.getPort());
        }

        // 2) FIN
        channels.get(0).send(
            LLS.New_Fin_Packet(ClientMenu.myUsername, ClientMenu.target_username).duplicate(),
            serverAddr
        );

        long start = System.currentTimeMillis();
        long lastSend = start;
        boolean allDone = false;

        Set<Integer> remotePorts = new LinkedHashSet<>();
        InetAddress  remoteIP    = null;

        // 1:1 local→remote eşlemek için rr
        int rrIdx = 0;

        while (System.currentTimeMillis() - start < MATCH_TIMEOUT_MS && !allDone) {
            selector.select(SELECT_BLOCK_MS);

            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next(); it.remove();
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
                    InetAddress pIP   = (InetAddress) info.get(0);
                    int        pPort  = (Integer)     info.get(1);

                    if (remoteIP == null) remoteIP = pIP;
                    if (remotePorts.add(pPort)) {
                        System.out.printf("[Client] <<< PORT %s:%d\n", pIP.getHostAddress(), pPort);

                        DatagramChannel chosen = channels.get(rrIdx % channels.size());
                        rrIdx++;

                        KAM.register(chosen, new InetSocketAddress(pIP, pPort));
                    }
                } else if (type == LLS.SIG_ALL_DONE) {
                    List<Object> info = LLS.parseAllDone(buf.duplicate());
                    String who = (String) info.get(0);
                    System.out.println("[Client] <<< ALL_DONE from " + who);
                    allDone = true;
                } else {
                    // SIG_KEEP vb. -> ignore
                }
            }

            // Cevap yoksa resend
            if (!allDone && remotePorts.isEmpty() &&
                (System.currentTimeMillis() - lastSend) > RESEND_INTERVAL_MS) {

                for (DatagramChannel dc : channels) {
                    dc.send(hello.duplicate(), serverAddr);
                }
                channels.get(0).send(LLS.New_Fin_Packet(ClientMenu.myUsername, ClientMenu.target_username).duplicate(),
                                     serverAddr);
                lastSend = System.currentTimeMillis();
            }
        }

        if (!allDone) {
            System.out.println("[Client] Timeout without ALL_DONE.");
            KAM.close();
        } else {
            System.out.println("[Client] Remote ports learned: " + remotePorts);
            KAM.printSummary();
            System.out.println("[Client] KeepAlives running. Press Ctrl+C to exit...");
            KAM.blockMain();      // Ana thread burada kalır
        }
    }

    public static void main(String[] args) {
        InetSocketAddress serverAddr =
                new InetSocketAddress(SafeRoomServer.ServerIP, SafeRoomServer.udpPort1);
        try {
            multiplexer(serverAddr);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
