package com.saferoom.natghost;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.MembershipKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Virtual DatagramChannel that reads from queue instead of socket.
 * Writes go to real stunChannel via send().
 * 
 * Used to multiplex file transfer packets over the same P2P channel
 * without blocking the main KeepAliveManager selector loop.
 */
public class VirtualFileChannel extends DatagramChannel {
    
    private final DatagramChannel realChannel;
    private final SocketAddress targetAddress;
    private final BlockingQueue<ByteBuffer> receiveQueue;
    private boolean connected = true;
    
    public VirtualFileChannel(DatagramChannel realChannel, 
                             SocketAddress targetAddress,
                             BlockingQueue<ByteBuffer> receiveQueue) {
        super(realChannel.provider());
        this.realChannel = realChannel;
        this.targetAddress = targetAddress;
        this.receiveQueue = receiveQueue;
    }
    
    @Override
    public DatagramChannel bind(SocketAddress local) throws IOException {
        // Already bound via realChannel
        return this;
    }
    
    @Override
    public <T> DatagramChannel setOption(SocketOption<T> name, T value) throws IOException {
        // Delegate to real channel
        realChannel.setOption(name, value);
        return this;
    }
    
    @Override
    public <T> T getOption(SocketOption<T> name) throws IOException {
        return realChannel.getOption(name);
    }
    
    @Override
    public Set<SocketOption<?>> supportedOptions() {
        return realChannel.supportedOptions();
    }
    
    @Override
    public DatagramChannel connect(SocketAddress remote) throws IOException {
        // Virtual connection (already connected via targetAddress)
        this.connected = true;
        return this;
    }
    
    @Override
    public DatagramChannel disconnect() throws IOException {
        this.connected = false;
        return this;
    }
    
    @Override
    public boolean isConnected() {
        return connected;
    }
    
    @Override
    public SocketAddress getRemoteAddress() throws IOException {
        return targetAddress;
    }
    
    @Override
    public SocketAddress receive(ByteBuffer dst) throws IOException {
        // Read from queue instead of socket
        try {
            ByteBuffer packet = receiveQueue.poll(100, TimeUnit.MILLISECONDS);
            
            if (packet == null) {
                return null; // Timeout - no packet available
            }
            
            // Copy packet data to destination buffer
            int bytesToCopy = Math.min(dst.remaining(), packet.remaining());
            
            // Create a slice view of the packet
            ByteBuffer slice = packet.duplicate();
            slice.limit(slice.position() + bytesToCopy);
            
            dst.put(slice);
            
            return targetAddress; // Return sender address
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
    
    @Override
    public int read(ByteBuffer dst) throws IOException {
        // Read from queue
        try {
            ByteBuffer packet = receiveQueue.poll(100, TimeUnit.MILLISECONDS);
            
            if (packet == null) {
                return 0; // No data available
            }
            
            int bytesToCopy = Math.min(dst.remaining(), packet.remaining());
            
            ByteBuffer slice = packet.duplicate();
            slice.limit(slice.position() + bytesToCopy);
            
            dst.put(slice);
            
            return bytesToCopy;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }
    
    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        // Simple implementation - read into first buffer
        if (length > 0 && offset < dsts.length) {
            return read(dsts[offset]);
        }
        return 0;
    }
    
    @Override
    public int send(ByteBuffer src, SocketAddress target) throws IOException {
        // Write directly to real channel
        SocketAddress actualTarget = (target != null ? target : targetAddress);
        int bytesSent = realChannel.send(src, actualTarget);
        
        System.out.printf("[VirtualFileChannel] 📤 Sent %d bytes to %s (via stunChannel)%n", 
            bytesSent, actualTarget);
        
        return bytesSent;
    }
    
    @Override
    public int write(ByteBuffer src) throws IOException {
        // Write to targetAddress via send
        return send(src, targetAddress);
    }
    
    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        // Simple implementation - write first buffer
        if (length > 0 && offset < srcs.length) {
            return write(srcs[offset]);
        }
        return 0;
    }
    
    @Override
    public SocketAddress getLocalAddress() throws IOException {
        return realChannel.getLocalAddress();
    }
    
    @Override
    public java.net.DatagramSocket socket() {
        return realChannel.socket();
    }
    
    @Override
    protected void implCloseSelectableChannel() throws IOException {
        // Don't close real channel - it's shared
        connected = false;
    }
    
    @Override
    protected void implConfigureBlocking(boolean block) throws IOException {
        // Virtual channel doesn't use selector
        // Queue-based read is always "blocking" with timeout
    }
    
    @Override
    public MembershipKey join(java.net.InetAddress group, java.net.NetworkInterface interf) throws IOException {
        throw new UnsupportedOperationException("Multicast not supported on VirtualFileChannel");
    }
    
    @Override
    public MembershipKey join(java.net.InetAddress group, java.net.NetworkInterface interf, java.net.InetAddress source) throws IOException {
        throw new UnsupportedOperationException("Multicast not supported on VirtualFileChannel");
    }
}
