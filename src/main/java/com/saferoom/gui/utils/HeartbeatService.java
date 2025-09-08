package com.saferoom.gui.utils;

import com.saferoom.client.ClientMenu;
import javafx.application.Platform;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * Heartbeat servisi - kullanıcının online durumunu takip eder
 */
public class HeartbeatService {
    
    private static HeartbeatService instance;
    private ScheduledExecutorService scheduler;
    private String sessionId;
    private boolean isRunning = false;
    
    private HeartbeatService() {
        this.sessionId = UUID.randomUUID().toString();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HeartbeatService");
            t.setDaemon(true);
            return t;
        });
    }
    
    public static synchronized HeartbeatService getInstance() {
        if (instance == null) {
            instance = new HeartbeatService();
        }
        return instance;
    }
    
    /**
     * Heartbeat servisini başlat
     */
    public void startHeartbeat(String username) {
        if (isRunning) {
            return;
        }
        
        System.out.println("💓 Starting heartbeat service for: " + username);
        isRunning = true;
        
        // Her 15 saniyede bir heartbeat gönder
        scheduler.scheduleAtFixedRate(() -> {
            try {
                com.saferoom.grpc.SafeRoomProto.HeartbeatResponse response = 
                    ClientMenu.sendHeartbeat(username, sessionId);
                
                if (!response.getSuccess()) {
                    System.err.println("❌ Heartbeat failed: " + response.getMessage());
                }
            } catch (Exception e) {
                System.err.println("❌ Heartbeat error: " + e.getMessage());
            }
        }, 0, 15, TimeUnit.SECONDS);
        
        // Her 2 dakikada bir cleanup
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // DBManager.cleanupOldSessions(); // Server tarafında da yapılabilir
            } catch (Exception e) {
                System.err.println("❌ Cleanup error: " + e.getMessage());
            }
        }, 0, 120, TimeUnit.SECONDS);
    }
    
    /**
     * Heartbeat servisini durdur
     */
    public void stopHeartbeat() {
        stopHeartbeat(null);
    }
    
    /**
     * Heartbeat servisini durdur ve session'ı temizle
     */
    public void stopHeartbeat(String username) {
        if (!isRunning) {
            return;
        }
        
        System.out.println("💔 Stopping heartbeat service");
        
        // Session'ı sunucudan temizle
        if (username != null) {
            try {
                com.saferoom.client.ClientMenu.endUserSession(username, sessionId);
                System.out.println("🗑️ Session ended for: " + username);
            } catch (Exception e) {
                System.err.println("❌ Failed to end session: " + e.getMessage());
            }
        }
        isRunning = false;
        
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public boolean isRunning() {
        return isRunning;
    }
}
