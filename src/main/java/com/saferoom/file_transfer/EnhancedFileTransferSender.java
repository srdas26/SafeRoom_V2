package com.saferoom.file_transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

import com.saferoom.p2p.DataChannelWrapper;

public class EnhancedFileTransferSender {
	    private final DatagramChannel channel;
	    private volatile boolean stopRequested = false;
	    
	    // QUIC-inspired congestion control
	    private HybridCongestionController hybridControl;
	    private EnhancedNackListener enhancedNackListener;
	    private Thread statsThread;
	    private Thread nackThread;
	    private Thread retransmissionThread;
	    private ChunkManager chunkManager;
	    
	    private static final ExecutorService threadPool = 
	        Executors.newCachedThreadPool(r -> {
	            Thread t = new Thread(r);
	            t.setDaemon(true);
	            t.setName("enhanced-transfer-" + t.getId());
	            return t;
	        });

	    public static final long TURBO_MAX  = 256L << 20; // 256 MB
	    public static final int  SLICE_SIZE = 1450; // Maximum payload without fragmentation
	    public static final int  MAX_TRY    = 4;
	    public static final int  BACKOFF_NS = 0; // HİÇ BEKLEME YOK!
	
	    public EnhancedFileTransferSender(DatagramChannel ch){
		this.channel = ch;
	    }
	    
	    public void requestStop() {
	        this.stopRequested = true;
	    }

	public boolean handshake(long fileId, long file_size, int total_seq) throws IOException {
	System.out.println("[SENDER-HANDSHAKE] ╔════════════════════════════════════════════════");
	System.out.println("[SENDER-HANDSHAKE] ║ handshake() ENTERED");
	System.out.printf("[SENDER-HANDSHAKE] ║ Thread: %s%n", Thread.currentThread().getName());
	System.out.printf("[SENDER-HANDSHAKE] ║ fileId=%d, size=%d, chunks=%d%n", 
		fileId, file_size, total_seq);
	System.out.printf("[SENDER-HANDSHAKE] ║ Channel connected: %s%n", 
		channel != null ? channel.isConnected() : "NULL");
	System.out.println("[SENDER-HANDSHAKE] ╚════════════════════════════════════════════════");
	
	if(channel == null) throw new IllegalStateException("Datagram Channel is null you must bind and connect first");
		long candidate_file_Id = -1;
		HandShake_Packet pkt = new HandShake_Packet();
		pkt.make_SYN(fileId, file_size, total_seq);
	
		System.out.printf("[FILE-HANDSHAKE] 🤝 Sending SYN for fileId=%d, size=%d, chunks=%d%n", 
			fileId, file_size, total_seq);
		
		// Send initial SYN
		int bytesSent = channel.write(pkt.get_header().duplicate());
		System.out.printf("[FILE-HANDSHAKE] 📤 SYN sent: %d bytes%n", bytesSent);
		
		ByteBuffer buffer = ByteBuffer.allocateDirect(HandShake_Packet.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN);
		
		long ackDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30); // 30 saniye timeout
		long lastSynTime = System.nanoTime();
		int r;
		int synRetryCount = 0;
		
		do{
			if(System.nanoTime() > ackDeadline) {
				System.err.println("Handshake ACK timeout after 30 seconds");
				return false;
			}
			
			// Her 250ms'de bir SYN tekrar gönder (receiver başlayana kadar)
			long now = System.nanoTime();
			if (now - lastSynTime > TimeUnit.MILLISECONDS.toNanos(250)) {
				pkt.resetForRetransmitter();
				channel.write(pkt.get_header().duplicate());
				synRetryCount++;
				if (synRetryCount % 10 == 0) {
					System.out.printf("[FILE-HANDSHAKE] 🔄 SYN retry #%d (waiting for receiver...)%n", synRetryCount);
				}
				lastSynTime = now;
			}
			
			// CRITICAL: Clear buffer before each read attempt!
			buffer.clear();
			r = channel.read(buffer);
			if(r <= 0) LockSupport.parkNanos(1_000_000); // 1ms bekleme
		}while( r <= 0);
		
		buffer.flip();
		if(r >= HandShake_Packet.HEADER_SIZE && buffer.get(0) == 0x10){
			buffer.position(1); // Position'ı 1'e set et
			candidate_file_Id = buffer.getLong(); // Relative okuma
			
			System.out.printf("[SENDER-HANDSHAKE] ✅ ACK received: fileId=%d (after %d SYN retries)%n", 
				candidate_file_Id, synRetryCount);
		} else {
			System.err.printf("[SENDER-HANDSHAKE] ❌ Invalid ACK: size=%d, type=0x%02X%n", 
				r, buffer.get(0));
		}

		if(candidate_file_Id == fileId)
		{
			pkt.make_SYN_ACK(fileId);
			try{
				// SYN_ACK için de timeout ekle
				long synAckDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
				while(channel.write(pkt.get_header().duplicate()) == 0)
				{
					if(System.nanoTime() > synAckDeadline) {
						System.err.println("SYN_ACK send timeout");
						return false;
					}
					pkt.resetForRetransmitter();
					LockSupport.parkNanos(1_000_000); // 1ms bekleme
				}
				System.out.println("[SENDER-HANDSHAKE] ✅ SYN_ACK sent successfully");
				
				// Give receiver time to process SYN_ACK before data flood
				LockSupport.parkNanos(5_000_000); // 5ms bekle
				
			}catch(IOException e){
				System.err.println("SYN+ACK Signal Error: " + e);
				return false;
			}
			return true;
		}
		return false;
	}
	    
	    public void sendOne(CRC32C crc, CRC32C_Packet pkt,
                MappedByteBuffer mem, long fileId,
                int seqNo, int totalSeq, int take, int off) throws IOException{
	    	
	    	ByteBuffer payload = mem.duplicate();
	    	payload.position(off).limit(off + take);
	    	payload = payload.slice();
	    	crc.reset();
	    	crc.update(payload.duplicate());
	    	int crc32c = (int) crc.getValue();
	    	
	    	pkt.fillHeader(fileId, seqNo, totalSeq, take, crc32c);
	    	
	        ByteBuffer[] frame = new ByteBuffer[]{ pkt.headerBuffer(), payload.position(0).limit(take) };
		
	        // Enhanced: RTT measurement için timestamp kaydet (retransmission için)
	        if (enhancedNackListener != null) {
	        	enhancedNackListener.recordPacketSendTime(seqNo);
	        }
	        
	        // QUIC-style congestion control
	        if (hybridControl != null) {
	        	hybridControl.rateLimitSend(); // Rate pacing
	        }
	        
	        // Send packet
			try{
				final int packetBytes = CRC32C_Packet.HEADER_SIZE + take;
				waitForDataChannelDrain(packetBytes);
	        	channel.write(frame);
	        	
	        	// Notify congestion controller
	        	if (hybridControl != null) {
	        		hybridControl.onPacketSent(packetBytes);
	        	}
			}catch(IOException e){
				System.err.println("Frame sending error: " + e);
			}
	    }
	    
	    public void sendFile(Path filePath, long fileId) throws IOException{
	    	sendFileInternal(filePath, fileId, true); // With handshake
	    }
	    
	    /**
	     * Send file WITHOUT handshake - handshake already done by KeepAliveManager!
	     */
	    public void sendFileWithoutHandshake(Path filePath, long fileId) throws IOException{
	    	sendFileInternal(filePath, fileId, false); // Skip handshake
	    }
	      private void sendFileInternal(Path filePath, long fileId, boolean doHandshake) throws IOException{
    	System.out.println("[SEND-INTERNAL] ╔════════════════════════════════════════════════");
    	System.out.printf("[SEND-INTERNAL] ║ sendFileInternal() ENTERED%n");
    	System.out.printf("[SEND-INTERNAL] ║ Thread: %s%n", Thread.currentThread().getName());
    	System.out.printf("[SEND-INTERNAL] ║ filePath: %s%n", filePath.getFileName());
    	System.out.printf("[SEND-INTERNAL] ║ fileId: %d%n", fileId);
    	System.out.printf("[SEND-INTERNAL] ║ doHandshake: %s%n", doHandshake);
    	System.out.println("[SEND-INTERNAL] ╚════════════════════════════════════════════════");
    	
    	if(channel == null) throw new IllegalStateException("Datagram Channel is null you must bind and connect first");
    	if(stopRequested) throw new IllegalStateException("Transfer was stopped");
    	
    	try(FileChannel fc = FileChannel.open(filePath, StandardOpenOption.READ)){
    		long fileSize = fc.size();
    		
    		System.out.printf("[SEND-INTERNAL] File opened: size=%d bytes%n", fileSize);
    		
    		// Initialize ChunkManager for unlimited file size support
    		this.chunkManager = new ChunkManager(filePath, SLICE_SIZE);
    		int totalSeq = chunkManager.getTotalSequenceCount();
    		
    		System.out.printf("[SEND-INTERNAL] ChunkManager initialized: %d chunks%n", totalSeq);
    		
    		// Thread-safe için her thread kendi instance'larını kullanacak
    		CRC32C initialCrc = new CRC32C();
    		CRC32C_Packet initialPkt = new CRC32C_Packet();
    		
		System.out.printf("[SEND-INTERNAL] ⚙️ doHandshake check: %s%n", doHandshake);
		if (doHandshake) {
			System.out.println("[SEND-INTERNAL] ✅ Handshake WILL BE PERFORMED");
				long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
				final long MAX_BACKOFF = 10_000_000L;
				long backoff  = 1_000_000L;
			boolean hand_shaking;
			do{
				hand_shaking = handshake(fileId, fileSize, totalSeq);  // Changed: long fileSize (no cast)
				if(hand_shaking) break;				if(Thread.currentThread().isInterrupted()){
						throw new IllegalStateException("Handshake Thread interrupted");
					}
					if(System.nanoTime() > deadline){
						throw new IllegalStateException("Handshake timeout");
					}
					LockSupport.parkNanos(backoff);
					 if (backoff < MAX_BACKOFF) {
						   backoff = Math.min(MAX_BACKOFF, backoff << 1);
						}
				}while(!hand_shaking);
			} else {
				System.out.println("[FILE-SEND] ⏩ Skipping handshake - already done by KeepAliveManager");
			}

	    	ConcurrentLinkedQueue<Integer> retxQueue = new ConcurrentLinkedQueue<>();
	    	
	    	// Transfer completion için latch
	    	final CountDownLatch transferCompleteLatch = new CountDownLatch(1);
	    	 
	    	// Enhanced NACK listener'ı başlat
	    	this.enhancedNackListener = new EnhancedNackListener(channel, fileId, totalSeq, retxQueue, BACKOFF_NS);
	    	
	    	// Completion callback ayarla
	    	enhancedNackListener.onTransferComplete = () -> {
	    		System.out.println("Sender: Transfer completion detected!");
	    		transferCompleteLatch.countDown();
	    	};
	    	
	    	 this.nackThread = new Thread(enhancedNackListener, "enhanced-nack-listener");
	    	 if (this.nackThread == null) {
	    	 	System.err.println("Enhanced NackThread creation failed!");
	    	 	return;
	    	 }
	    	 this.nackThread.setDaemon(true);
	    	 this.nackThread.start();
	    	
	    	// QUIC-inspired hybrid congestion control
	    	this.hybridControl = new HybridCongestionController();
	    	
	    	// Enhanced NACK listener'a congestion control referansını ver
	    	enhancedNackListener.hybridControl = hybridControl;
	    	
	    	// Network türüne göre optimize et
	    	String targetHost = channel.getRemoteAddress().toString();
	    	boolean isLocalNetwork = targetHost.contains("127.0.0.1") || targetHost.contains("localhost") || 
	    	    targetHost.contains("192.168.") || targetHost.contains("10.");
	    	    
	    	if (isLocalNetwork) {
	    		hybridControl.enableLocalNetworkMode();
	    		System.out.println(" Local network detected - enabling aggressive mode");
	    	} else {
	    		hybridControl.enableWanMode();
	    		System.out.println(" WAN detected - packet-by-packet conservative mode");
	    	}
	    	
	    	// Enhanced statistics display thread
	    	this.statsThread = new Thread(() -> {
	    		while (!Thread.currentThread().isInterrupted()) {
	    			try {
	    				Thread.sleep(2000); // Her 2 saniyede bir stats göster
	    				System.out.println(" " + hybridControl.getStats());
	    				System.out.println(" " + enhancedNackListener.getRttStats());
	    			} catch (InterruptedException e) {
	    				break;
	    			}
	    		}
	    	}, "enhanced-stats");
	    	if (this.statsThread == null) {
	    		System.err.println("Enhanced StatsThread creation failed!");
	    		return;
	    	}
	    	this.statsThread.setDaemon(true);
	    	this.statsThread.start();
	    	
	    	// Enhanced retransmission thread with congestion awareness
	    	final boolean[] initialTransmissionDone = {false};
	    	
			this.retransmissionThread = new Thread( () -> {
				CRC32C retxCrc = new CRC32C();
				CRC32C_Packet retxPkt = new CRC32C_Packet();
				
				while(!Thread.currentThread().isInterrupted() && !stopRequested){
	    			Integer miss = retxQueue.poll();
	    			if(miss == null) {
	    				if(initialTransmissionDone[0]) {
	    					LockSupport.parkNanos(1_000_000); // 1ms bekle
	    					continue;
	    				}
	    				LockSupport.parkNanos(50_000); // 50μs hızlı polling
	    				continue;
	    			}
    			
    			if(miss < 0 || miss >= totalSeq) {
    				System.err.println("Invalid sequence number: " + miss);
    				continue;
    			}
    			
    			// Congestion control check before retransmission
    			if (hybridControl != null && !hybridControl.canSendPacket()) {
    				// Window full, put back and wait
    				retxQueue.offer(miss);
    				LockSupport.parkNanos(100_000); // 100μs bekle
    				continue;
    			}
    			
    			// Chunk-aware retransmission: find which chunk contains this sequence
    			try {
    				int chunkIdx = chunkManager.findChunkForSequence(miss);
    				if (chunkIdx < 0) {
    					System.err.println("No chunk found for sequence: " + miss);
    					continue;
    				}
    				
    				ChunkMetadata chunkMeta = chunkManager.getChunkMetadata(chunkIdx);
    				MappedByteBuffer chunkBuffer = chunkManager.getChunk(chunkIdx);
    				int localSeq = chunkMeta.toLocalSequence(miss);
    				int localOff = chunkMeta.getLocalOffset(localSeq, SLICE_SIZE);
    				int take = chunkMeta.getPayloadSize(localSeq, SLICE_SIZE);
    				
    				if(take > 0) {
    					sendOne(retxCrc, retxPkt, chunkBuffer, fileId, miss, totalSeq, take, localOff);
    				}
    			} catch(IOException e) {
    				System.err.println("Retransmission error for seq " + miss + ": " + e);
    			}
    		}
	}, "enhanced-retransmission");
		if (this.retransmissionThread == null) {
			System.err.println(" Enhanced RetransmissionThread creation failed!");
			return;
		}
		this.retransmissionThread.setDaemon(true);
		this.retransmissionThread.start();	
		
		// ENHANCED WINDOWED TRANSMISSION - QUIC-style with Chunk Support
		System.out.println("Starting QUIC-inspired windowed transmission with chunked I/O...");
		int seqNo = 0;
		long startTime = System.currentTimeMillis();
		long lastProgressTime = startTime;
		
		// Chunk-based sequential transmission
		int chunkCount = chunkManager.getChunkCount();
		for(int chunkIdx = 0; chunkIdx < chunkCount; chunkIdx++) {
			ChunkMetadata chunkMeta = chunkManager.getChunkMetadata(chunkIdx);
			MappedByteBuffer chunkBuffer = chunkManager.getChunk(chunkIdx);
			
			// Send all sequences in this chunk
			int localSeq = 0;
			for(int off = 0; off < chunkBuffer.capacity(); ) {
				int remaining = chunkBuffer.capacity() - off;
				int take = Math.min(SLICE_SIZE, remaining);
				
				// DYNAMIC RTT-BASED PACING - Controller'ın hesapladığı değeri kullan
				sendOne(initialCrc, initialPkt, chunkBuffer, fileId, seqNo, totalSeq, take, off);
				
				// Controller'dan dynamic pacing al - RTT'ye göre adaptive
				// rateLimitSend() zaten internal pacing yapıyor, ekstra sabit pacing yok!
				
				off += take;
				seqNo++;
				localSeq++;
				
				// Enhanced progress display
				if (System.currentTimeMillis() - lastProgressTime > 1000) {
					double progress = (double)seqNo / totalSeq * 100;
					long elapsed = System.currentTimeMillis() - startTime;
					double throughputMbps = (seqNo * SLICE_SIZE * 8.0) / (elapsed * 1000.0);
					System.out.printf(" Progress: %.1f%% (Chunk %d/%d), Throughput: %.1f Mbps\n", 
						progress, chunkIdx + 1, chunkCount, throughputMbps);
					System.out.println(" " + hybridControl.getStats());
					lastProgressTime = System.currentTimeMillis();
				}
			}
		}
		
		initialTransmissionDone[0] = true;
	    	System.out.println("Initial transmission completed, waiting for retransmissions...");
	    	
	    	// Transfer completion bekle
	    	try {
	    		boolean completed = transferCompleteLatch.await(300, TimeUnit.SECONDS);
	    		if(completed) {
	    			System.out.println(" File transfer completed successfully!");
	    			System.out.println(" Final stats: " + hybridControl.getStats());
	    		} else {
	    			System.err.println(" Transfer timeout - network issue or very large file");
	    		}
	    	} catch(InterruptedException e) {
	    		System.err.println("Transfer interrupted");
	    		Thread.currentThread().interrupt();
	    	}
	    	}finally {
	    		// Enhanced cleanup
	    		System.out.println(" Cleaning up enhanced transfer threads...");
	    		
	    		if(nackThread != null && nackThread.isAlive()) {
	    			nackThread.interrupt();
	    			try {
	    				nackThread.join(2000); 
	    			} catch (InterruptedException e) {
	    				Thread.currentThread().interrupt();
	    			}
	    		}
	    		
	    		if(retransmissionThread != null && retransmissionThread.isAlive()) {
	    			retransmissionThread.interrupt();
	    			try {
	    				retransmissionThread.join(2000); 
	    			} catch (InterruptedException e) {
	    				Thread.currentThread().interrupt();
	    			}
	    		}
	    		
	    		if(statsThread != null && statsThread.isAlive()) {
	    			statsThread.interrupt();
	    		}
	    		
	    		// Reset controller
	    		if (hybridControl != null) {
	    			System.out.println(" Transfer summary: " + hybridControl.getStats());
	    		}
	    	}
	    }
	    
	    public static void shutdownThreadPool() {
	        threadPool.shutdown();
	        try {
	            if (!threadPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
	                threadPool.shutdownNow();
	            }
	        } catch (InterruptedException e) {
	            threadPool.shutdownNow();
	            Thread.currentThread().interrupt();
	        }
	    }
	    
	    private void waitForDataChannelDrain(int bytesToSend) throws IOException {
	    	if (!(channel instanceof DataChannelWrapper wrapper)) {
	    		return;
	    	}
	    	final long maxBuffered = Long.getLong("saferoom.transfer.buffer.maxBytes", 8L << 20);
	    	while (wrapper.getBufferedAmountSafe() + bytesToSend > maxBuffered) {
	    		if (!wrapper.isConnected()) {
	    			throw new IOException("DataChannel closed");
	    		}
	    		LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
	    	}
	    }
	}
