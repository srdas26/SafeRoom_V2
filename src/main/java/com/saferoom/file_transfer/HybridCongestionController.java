package com.saferoom.file_transfer;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * QUIC-inspired hybrid congestion control for NAK-based protocol
 * Combines QUIC's cubic congestion control with rate-based pacing
 */
public class HybridCongestionController {
    
    // QUIC-inspired congestion window (bytes)
    private volatile long congestionWindow = 32 * 1450; // 32 packets başlangıç
    private volatile long slowStartThreshold = Long.MAX_VALUE;
    private volatile long maxCongestionWindow = 256 * 1450; // 256 packets max
    
    // Bandwidth estimation - NACK-based delivery rate tracking
    private volatile long estimatedBandwidthBps = 10_000_000; // 10 Mbps başlangıç
    private volatile long maxBandwidthBps = 1_000_000_000; // 1 Gbps max for LAN
    
    // Delivery rate tracking for bandwidth estimation
    private final AtomicLong deliveredBytes = new AtomicLong(0);
    private volatile long deliveryRateStartTime = System.nanoTime();
    
    // Pacing rate (bytes per second)
    private volatile long pacingRate = estimatedBandwidthBps;
    private volatile long packetIntervalNs = 0;
    
    // RTT tracking (QUIC RttStats benzeri)
    private volatile long smoothedRtt = 100_000_000; // 100ms başlangıç
    private volatile long rttVar = 50_000_000; // 50ms variance
    private volatile long minRtt = Long.MAX_VALUE;
    
    // Congestion state
    private enum CongestionState {
        SLOW_START,
        CONGESTION_AVOIDANCE,
        RECOVERY
    }
    private volatile CongestionState state = CongestionState.SLOW_START;
    
    // In-flight tracking
    private final AtomicLong bytesInFlight = new AtomicLong(0);
    private final AtomicLong packetsInFlight = new AtomicLong(0);
    
    // Statistics
    private final AtomicLong totalPacketsSent = new AtomicLong(0);
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private final AtomicLong totalLossCount = new AtomicLong(0);
    private volatile long startTime = System.nanoTime();
    
    // Timing
    private volatile long lastSendTime = 0;
    private volatile long lastNackTime = 0;
    
    // Network type
    private volatile boolean isLocalNetwork = false;
    
    // Constants
    private static final int PACKET_SIZE = 1450;
    
    public HybridCongestionController() {
        updatePacingRate();
    }
    
    /**
     * NACK-based pacing with bandwidth awareness
     * LAN'da da mikro-pacing aktif - burst önleme
     */
    public void rateLimitSend() {
        // Congestion window kontrolü
        if (bytesInFlight.get() >= congestionWindow) {
            // Window dolu - biraz bekle ve tekrar kontrol et
            long waitTime = packetIntervalNs > 0 ? packetIntervalNs * 2 : 10_000; // Min 10μs
            if (waitTime > 100_000) { // Max 100μs bekle
                waitTime = 100_000;
            }
            LockSupport.parkNanos(waitTime);
            return;
        }
        
        // Pacing kontrolü - LAN dahil tüm networkler için
        if (packetIntervalNs > 0) {
            long now = System.nanoTime();
            long timeSinceLastSend = now - lastSendTime;
            
            if (timeSinceLastSend < packetIntervalNs) {
                long sleepTime = packetIntervalNs - timeSinceLastSend;
                LockSupport.parkNanos(sleepTime);
            }
            lastSendTime = System.nanoTime();
        }
    }
    
    /**
     * Packet sent notification - QUIC OnPacketSent benzeri
     */
    public void onPacketSent() {
        onPacketSent(PACKET_SIZE);
    }
    
    public void onPacketSent(int packetSize) {
        bytesInFlight.addAndGet(packetSize);
        packetsInFlight.incrementAndGet();
        totalPacketsSent.incrementAndGet();
        totalBytesSent.addAndGet(packetSize);
    }
    
	/**
	 * NACK-based bandwidth tracking - sadece delivery rate için
	 * In-flight tracking yapma, sadece bandwidth estimate
	 */
	public void onNackFrameReceived(int receivedPacketCount, int lostPacketCount) {
		if (receivedPacketCount > 0) {
			// Delivery rate tracking - sadece bandwidth için
			int deliveredBytes = receivedPacketCount * PACKET_SIZE;
			this.deliveredBytes.addAndGet(deliveredBytes);
			
			long now = System.nanoTime();
			updateBandwidthEstimate(now);
		}
		
		// Congestion window büyüt - SADECE loss yoksa
		if (lostPacketCount == 0 && receivedPacketCount > 0) {
			int ackedBytes = receivedPacketCount * PACKET_SIZE;
			
			if (state == CongestionState.SLOW_START) {
				// Exponential growth
				congestionWindow += ackedBytes;
				if (congestionWindow >= slowStartThreshold) {
					state = CongestionState.CONGESTION_AVOIDANCE;
					System.out.println("🔄 Switched to CONGESTION_AVOIDANCE");
				}
			} else if (state == CongestionState.CONGESTION_AVOIDANCE) {
				// Additive increase
				long increase = (PACKET_SIZE * PACKET_SIZE) / congestionWindow;
				congestionWindow += Math.max(1, increase * receivedPacketCount);
			}
			
			congestionWindow = Math.min(congestionWindow, maxCongestionWindow);
			updatePacingRate();
		}
	}
    
    /**
     * NACK-based loss detection - QUIC OnPacketLost benzeri
     */
    public void onPacketLoss(int lostPacketCount) {
        onPacketLoss(lostPacketCount, lostPacketCount * PACKET_SIZE);
    }
    
	public void onPacketLoss(int lostPacketCount, int lostBytes) {
		if (lostPacketCount <= 0) return;
		
		totalLossCount.addAndGet(lostPacketCount);
		lastNackTime = System.nanoTime();
        
        // NACK-based congestion response - sadece ilk loss'ta
        if (state != CongestionState.RECOVERY) {
            state = CongestionState.RECOVERY;
            
            // NACK-based congestion response
            if (isLocalNetwork) {
                // LAN - minimal backoff, fast recovery
                slowStartThreshold = (congestionWindow * 7) / 8;  // 87.5% minimal reduction
                congestionWindow = Math.max(slowStartThreshold, 64 * PACKET_SIZE); // Min 64 packets
                estimatedBandwidthBps = (long)(estimatedBandwidthBps * 0.9); // 10% reduction
            } else {
                // WAN - gentler backoff for better recovery
                slowStartThreshold = (congestionWindow * 3) / 4;  // 75% threshold
                congestionWindow = Math.max(slowStartThreshold, 8 * PACKET_SIZE); // Min 8 packets
                estimatedBandwidthBps = (long)(estimatedBandwidthBps * 0.8); // 20% reduction
            }
            
            updatePacingRate();
            
            System.out.printf("🔴 LOSS: %d packets, cwnd: %d -> %d bytes, bw: %.1f Mbps%n",
                lostPacketCount, 
                slowStartThreshold * 2, 
                congestionWindow,
                estimatedBandwidthBps / 1_000_000.0);
        }
    }
    
    /**
     * RTT measurement update - QUIC RttStats.UpdateRtt benzeri
     */
    public void updateRtt(long rttNs) {
        if (rttNs <= 0) return;
        
        // Min RTT güncelle
        if (rttNs < minRtt) {
            minRtt = rttNs;
        }
        
        // Smoothed RTT (EWMA)
        if (smoothedRtt == 0) {
            smoothedRtt = rttNs;
            rttVar = rttNs / 2;
        } else {
            long rttDelta = Math.abs(smoothedRtt - rttNs);
            rttVar = (3 * rttVar + rttDelta) / 4;
            smoothedRtt = (7 * smoothedRtt + rttNs) / 8;
        }
        
        // Recovery state'den çık eğer RTT iyileşmişse
        if (state == CongestionState.RECOVERY && 
            System.nanoTime() - lastNackTime > smoothedRtt * 2) {
            state = CongestionState.CONGESTION_AVOIDANCE;
            System.out.println("🟢 Exited RECOVERY state");
        }
    }
    
    /**
     * Bandwidth estimation update - NACK-based birikimli delivery rate
     */
    private void updateBandwidthEstimate(long now) {
        long elapsed = now - deliveryRateStartTime;
        
        // 100ms'de bir bandwidth güncelle
        if (elapsed > 100_000_000) { 
            long delivered = deliveredBytes.getAndSet(0); // Birikimli bytes'ı al ve sıfırla
            
            if (delivered > 0) {
                // Delivery rate hesapla: bytes/second
                long currentRate = (delivered * 1_000_000_000L) / elapsed;
                
                // EWMA ile bandwidth estimate
                estimatedBandwidthBps = (long)(0.7 * estimatedBandwidthBps + 0.3 * currentRate);
                estimatedBandwidthBps = Math.min(estimatedBandwidthBps, maxBandwidthBps);
            }
            
            deliveryRateStartTime = now;
        }
    }
    
    /**
     * Pacing rate calculation - NACK-based
     */
    private void updatePacingRate() {
        // Bandwidth-delay product aware pacing
        long bdp = (estimatedBandwidthBps * smoothedRtt) / 1_000_000_000L;
        long targetWindow = Math.max(congestionWindow, bdp);
        
        // Pacing rate = (window / RTT) * gain
        double pacingGain = (state == CongestionState.RECOVERY) ? 1.0 : 1.25;
        pacingRate = (long)((targetWindow * 1_000_000_000L * pacingGain) / smoothedRtt);
        pacingRate = Math.min(pacingRate, estimatedBandwidthBps * 2);
        
        // Mikro-pacing interval hesapla
        if (pacingRate > 0) {
            packetIntervalNs = (PACKET_SIZE * 1_000_000_000L) / pacingRate;
            
            // LAN vs WAN için farklı minimumlar
            long minInterval = isLocalNetwork ? 20_000 : 1_000; // 20μs LAN, 1μs WAN
            packetIntervalNs = Math.max(packetIntervalNs, minInterval);
        } else {
            packetIntervalNs = isLocalNetwork ? 20_000 : 10_000;
        }
    }
    
    /**
     * Network mode configuration - LAN optimized for NACK-based protocol
     */
    public void enableLocalNetworkMode() {
        isLocalNetwork = true;
        // LAN mode - büyük pencere, mikro-pacing
        maxCongestionWindow = 512 * PACKET_SIZE;  // 512 packets = 742KB max
        congestionWindow = 128 * PACKET_SIZE;     // 128 packets = 185KB start
        slowStartThreshold = 256 * PACKET_SIZE;   // 256 packets threshold
        estimatedBandwidthBps = 500_000_000;      // 500 Mbps başlangıç
        smoothedRtt = 2_000_000;                  // 2ms realistic LAN RTT
        packetIntervalNs = 20_000;                // 20μs mikro-pacing
        updatePacingRate();
        System.out.println("⚡ LAN MODE: mikro-pacing (20μs), large cwnd (512 pkts)");
    }
    
    public void enableWanMode() {
        isLocalNetwork = false;
        // Optimized WAN settings - more aggressive than before
        maxCongestionWindow = 128 * PACKET_SIZE;  // 128 packets (was 64)
        congestionWindow = 32 * PACKET_SIZE;      // 32 packets start (was 16)
        estimatedBandwidthBps = 50_000_000;       // 50 Mbps estimate
        updatePacingRate();
        System.out.println("📡 WAN MODE - Optimized settings for stability and performance");
    }
    
    /**
     * Current statistics - simplified for NACK-based
     */
    public String getStats() {
        long now = System.nanoTime();
        long elapsed = now - startTime;
        double throughputMbps = (totalBytesSent.get() * 8.0 * 1_000_000_000L) / (elapsed * 1_000_000.0);
        
        // Fix loss rate calculation - cap at 100%
        long totalSent = Math.max(1, totalPacketsSent.get());
        long totalLost = totalLossCount.get();
        double lossRate = Math.min(100.0, (totalLost * 100.0) / (totalSent + totalLost));
        
        return String.format(
            "State: %s, CWnd: %d pkts, BW: %.1f Mbps, RTT: %.1fms, " +
            "Loss: %.2f%%, Throughput: %.1f Mbps",
            state,
            congestionWindow / PACKET_SIZE,
            estimatedBandwidthBps / 1_000_000.0,
            smoothedRtt / 1_000_000.0,
            lossRate,
            throughputMbps
        );
    }
    
    /**
     * Get current sending capacity
     */
    public boolean canSendPacket() {
        return canSendPacket(PACKET_SIZE);
    }
    
    public boolean canSendPacket(int packetSize) {
        // Basitleştirilmiş: sadece congestion window check
        // In-flight tracking NACK-based protokolde zor, sadece window limiti
        return true; // Her zaman gönder, pacing kontrol eder
    }
    
    /**
     * Reset controller
     */
    public void reset() {
        congestionWindow = 32 * PACKET_SIZE;
        slowStartThreshold = Long.MAX_VALUE;
        state = CongestionState.SLOW_START;
        bytesInFlight.set(0);
        packetsInFlight.set(0);
        totalPacketsSent.set(0);
        totalBytesSent.set(0);
        totalLossCount.set(0);
        smoothedRtt = 100_000_000;
        rttVar = 50_000_000;
        minRtt = Long.MAX_VALUE;
        estimatedBandwidthBps = 10_000_000;
        startTime = System.nanoTime();
        deliveryRateStartTime = startTime;
        deliveredBytes.set(0);
        updatePacingRate();
    }
    
    // Getters
    public long getCongestionWindow() { return congestionWindow; }
    public long getSmoothedRtt() { return smoothedRtt; }
    public long getPacingInterval() { return packetIntervalNs; }
    public CongestionState getState() { return state; }
}
