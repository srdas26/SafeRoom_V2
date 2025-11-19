package com.saferoom.p2p;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelState;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.MembershipKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * Wraps WebRTC RTCDataChannel as a DatagramChannel
 * 
 * This allows reusing the existing file_transfer/ code (EnhancedFileTransferSender/Receiver)
 * with DataChannel instead of UDP!
 * 
 * Design:
 * - send() → dataChannel.send()
 * - receive() → polls from inbound queue (filled by onMessage callback)
 * - No real socket address needed (P2P is already established)
 */
public class DataChannelWrapper extends DatagramChannel {
    
    private final RTCDataChannel dataChannel;
    private final String remoteUsername;
    
    // Inbound message deque (handshake packets go to front, data packets to back)
    private final BlockingDeque<ByteBuffer> inboundQueue = new LinkedBlockingDeque<>();
    
    // Fake addresses for compatibility
    private final FakeSocketAddress localAddress;
    private final FakeSocketAddress remoteAddress;
    
    public DataChannelWrapper(RTCDataChannel dataChannel, String localUsername, String remoteUsername) {
        super(SelectorProvider.provider());
        this.dataChannel = dataChannel;
        this.remoteUsername = remoteUsername;
        this.localAddress = new FakeSocketAddress(localUsername);
        this.remoteAddress = new FakeSocketAddress(remoteUsername);
    }
    
    /**
     * Called by P2PConnectionManager when DataChannel receives message
     */
    public void onDataChannelMessage(RTCDataChannelBuffer buffer) {
        ByteBuffer src = buffer.data;
        ByteBuffer duplicate = src.duplicate();
        ByteBuffer copy = ByteBuffer.allocateDirect(duplicate.remaining());
        copy.put(duplicate);
        copy.flip();
        
        if (copy.remaining() > 0) {
            byte signal = copy.get(copy.position());
            
            // CRITICAL: Handshake packets (SYN, ACK, SYN_ACK) go to FRONT of queue
            // This ensures FileTransferReceiver.handshake() reads these BEFORE data flood
            if (signal == 0x01 || signal == 0x10 || signal == 0x11) {
                System.out.printf("[Wrapper] 📥 PRIORITY signal 0x%02X (%d bytes) from %s → FRONT of queue%n",
                    signal, copy.remaining(), remoteUsername);
                inboundQueue.offerFirst(copy);  // Add to front
            } else {
                System.out.printf("[Wrapper] 📥 Received signal 0x%02X (%d bytes) from %s → queue (size: %d)%n",
                    signal, copy.remaining(), remoteUsername, inboundQueue.size());
                inboundQueue.offerLast(copy);   // Add to back
            }
        } else {
            inboundQueue.offerLast(copy);
        }
    }
    
    // ========== DatagramChannel Implementation ==========
    
    @Override
    public int send(ByteBuffer src, SocketAddress target) throws IOException {
        if (dataChannel.getState() != RTCDataChannelState.OPEN) {
            throw new IOException("DataChannel not open");
        }
        
        int remaining = src.remaining();
        
        // DEBUG: Log what we're sending
        if (remaining > 0) {
            byte signal = src.get(src.position());
            System.out.printf("[Wrapper] 📤 Sending signal 0x%02X (%d bytes) to %s%n",
                signal, remaining, remoteUsername);
        }
        
        // Send via DataChannel
        try {
            RTCDataChannelBuffer buffer = new RTCDataChannelBuffer(src.duplicate(), true);
            dataChannel.send(buffer);
            
            // Mark buffer as consumed
            src.position(src.limit());
            
            return remaining;
        } catch (Exception e) {
            throw new IOException("DataChannel send failed: " + e.getMessage(), e);
        }
    }
    
    @Override
    public SocketAddress receive(ByteBuffer dst) throws IOException {
        try {
            // Poll from front (handshake packets inserted via offerFirst)
            ByteBuffer data = inboundQueue.pollFirst(50, TimeUnit.MILLISECONDS);
            if (data == null) {
                return null; // No data available
            }
            
            // DEBUG: Log what we're reading from queue
            if (data.remaining() > 0) {
                byte signal = data.get(data.position());
                System.out.printf("[Wrapper] 📖 Reading signal 0x%02X (%d bytes) from queue (remaining: %d)%n",
                    signal, data.remaining(), inboundQueue.size());
            }
            
            // Copy to destination buffer
            int toCopy = Math.min(dst.remaining(), data.remaining());
            ByteBuffer slice = data.duplicate();
            slice.limit(slice.position() + toCopy);
            dst.put(slice);
            
            return remoteAddress;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
    
    @Override
    public DatagramChannel bind(SocketAddress local) throws IOException {
        // No-op for DataChannel
        return this;
    }
    
    @Override
    public DatagramChannel connect(SocketAddress remote) throws IOException {
        // Already connected via WebRTC
        return this;
    }
    
    @Override
    public DatagramChannel disconnect() throws IOException {
        // No-op
        return this;
    }
    
    @Override
    public boolean isConnected() {
        return dataChannel.getState() == RTCDataChannelState.OPEN;
    }
    
    @Override
    public SocketAddress getRemoteAddress() throws IOException {
        return remoteAddress;
    }
    
    @Override
    public SocketAddress getLocalAddress() throws IOException {
        return localAddress;
    }
    
    @Override
    protected void implCloseSelectableChannel() throws IOException {
        inboundQueue.clear();
    }
    
    @Override
    protected void implConfigureBlocking(boolean block) throws IOException {
        // DataChannel is always non-blocking from our perspective
    }
    
    @Override
    public java.net.DatagramSocket socket() {
        // Not used anymore - EnhancedFileTransferSender uses getRemoteAddress() directly
        return null;
    }
    
    // ========== Fake SocketAddress ==========
    
    private static class FakeSocketAddress extends SocketAddress {
        private final String username;
        
        FakeSocketAddress(String username) {
            this.username = username;
        }
        
        @Override
        public String toString() {
            return "datachannel://" + username;
        }
    }
    
    // ========== Unsupported Operations ==========
    
    @Override
    public MembershipKey join(java.net.InetAddress group, java.net.NetworkInterface interf) throws IOException {
        throw new UnsupportedOperationException("Multicast not supported");
    }
    
    @Override
    public MembershipKey join(java.net.InetAddress group, java.net.NetworkInterface interf, 
                             java.net.InetAddress source) throws IOException {
        throw new UnsupportedOperationException("Multicast not supported");
    }
    
    @Override
    public <T> DatagramChannel setOption(SocketOption<T> name, T value) throws IOException {
        // Ignore socket options
        return this;
    }
    
    @Override
    public <T> T getOption(SocketOption<T> name) throws IOException {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public Set<SocketOption<?>> supportedOptions() {
        return Set.of();
    }
    
    @Override
    public int read(ByteBuffer dst) throws IOException {
        SocketAddress addr = receive(dst);
        return addr != null ? dst.position() : 0;
    }
    
    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        if (length == 0) return 0;
        return read(dsts[offset]);
    }
    
    @Override
    public int write(ByteBuffer src) throws IOException {
        return send(src, remoteAddress);
    }
    
    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        if (length <= 0) {
            return 0;
        }
        int totalBytes = 0;
        for (int i = 0; i < length; i++) {
            ByteBuffer buffer = srcs[offset + i];
            if (buffer != null) {
                totalBytes += buffer.remaining();
            }
        }
        if (totalBytes == 0) {
            return 0;
        }
        
        // Merge all buffers into one packet for DataChannel
        ByteBuffer merged = ByteBuffer.allocateDirect(totalBytes);
        for (int i = 0; i < length; i++) {
            ByteBuffer buffer = srcs[offset + i];
            if (buffer == null) {
                continue;
            }
            merged.put(buffer);  // Consumes original buffer position
        }
        merged.flip();
        send(merged, remoteAddress);
        return totalBytes;
    }
}
