package com.saferoom.natghost;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class KeepAliveManager implements AutoCloseable {

    private final ScheduledExecutorService exec;
    private final long intervalMs;
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

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

    @Override
    public void close() {
        tasks.values().forEach(f -> f.cancel(true));
        exec.shutdownNow();
    }
}
