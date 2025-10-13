package com.saferoom.gui.service;


import com.saferoom.gui.model.Message;
import com.saferoom.gui.model.User;
import com.saferoom.client.ClientMenu;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.HashMap;
import java.util.Map;

/**
 * Mesajları yöneten, gönderen ve alan servis.
 * Singleton deseni ile tasarlandı, yani uygulamanın her yerinden tek bir
 * nesnesine erişilebilir.
 */
public class ChatService {

    // Singleton deseni için statik nesne
    private static final ChatService instance = new ChatService();

    // Veri saklama alanı (eskiden kontrolcüdeydi)
    private final Map<String, ObservableList<Message>> channelMessages = new HashMap<>();

    // DİKKAT: Bu, yeni bir mesaj geldiğinde bunu dinleyenleri haberdar eden sihirli kısımdır.
    private final ObjectProperty<Message> newMessageProperty = new SimpleObjectProperty<>();

    // Constructor'ı private yaparak dışarıdan yeni nesne oluşturulmasını engelliyoruz.
    private ChatService() {
        // Başlangıç için sahte verileri yükle
        setupDummyMessages();
    }

    // Servisin tek nesnesine erişim metodu
    public static ChatService getInstance() {
        return instance;
    }

    /**
     * Belirtilen kanala yeni bir mesaj gönderir.
     * P2P bağlantı varsa P2P kullanır, yoksa server relay kullanır.
     * @param channelId Sohbet kanalının ID'si
     * @param text Gönderilecek mesaj metni
     * @param sender Mesajı gönderen kullanıcı
     */
    public void sendMessage(String channelId, String text, User sender) {
        if (text == null || text.trim().isEmpty()) return;

        Message newMessage = new Message(
                text,
                sender.getId(),
                sender.getName().isEmpty() ? "" : sender.getName().substring(0, 1)
        );

        // Mesajı ilgili kanalın listesine ekle
        ObservableList<Message> messages = getMessagesForChannel(channelId);
        messages.add(newMessage);

        // Try P2P messaging first (check if specific peer connection exists)
        boolean sentViaP2P = false;
        
        // Check if we have active P2P connection with this specific user
        if (ClientMenu.isP2PMessagingAvailable(channelId)) {
            // Use reliable messaging protocol (with chunking, ACK, retransmission)
            try {
                java.util.concurrent.CompletableFuture<Boolean> future = 
                    com.saferoom.natghost.NatAnalyzer.sendReliableMessage(channelId, text);
                
                // Wait for send completion (with timeout)
                sentViaP2P = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
                
                if (sentViaP2P) {
                    System.out.println("[Chat] ✅ Message sent via Reliable P2P to " + channelId);
                } else {
                    System.out.println("[Chat] ⚠️ Reliable P2P send failed to " + channelId);
                }
            } catch (Exception e) {
                System.err.println("[Chat] ❌ Reliable P2P error: " + e.getMessage());
                sentViaP2P = false;
            }
        }
        
        if (!sentViaP2P) {
            System.out.printf("[Chat] 📡 No P2P connection with %s - would use server relay%n", channelId);
            // TODO: Implement server relay messaging
        }

        // Update contact's last message (from me)
        try {
            com.saferoom.gui.service.ContactService.getInstance()
                .updateLastMessage(channelId, text, true);
        } catch (Exception e) {
            System.err.println("[Chat] Error updating contact last message: " + e.getMessage());
        }

        // Yeni mesaj geldiğini tüm dinleyenlere haber ver!
        newMessageProperty.set(newMessage);
    }

    /**
     * Belirtilen kanalın mesaj listesini döndürür.
     * @param channelId Sohbet kanalının ID'si
     * @return O kanala ait ObservableList<Message>
     */
    public ObservableList<Message> getMessagesForChannel(String channelId) {
        return channelMessages.computeIfAbsent(channelId, k -> FXCollections.observableArrayList());
    }

    // Yeni mesaj dinleyicisi için property'e erişim metodu
    public ObjectProperty<Message> newMessageProperty() {
        return newMessageProperty;
    }
    
    /**
     * P2P'den gelen mesajı al ve GUI'de göster
     */
    public void receiveP2PMessage(String sender, String receiver, String messageText) {
        System.out.printf("[Chat] 📥 P2P message received: %s -> %s: \"%s\"%n", sender, receiver, messageText);
        
        Message incomingMessage = new Message(
            messageText,
            sender,
            sender.isEmpty() ? "?" : sender.substring(0, 1).toUpperCase()
        );
        
        // Mesajı doğru channel'a ekle
        ObservableList<Message> messages = getMessagesForChannel(sender);
        messages.add(incomingMessage);
        
        // Update contact's last message (not from me - will increment unread if not active)
        try {
            com.saferoom.gui.service.ContactService contactService = 
                com.saferoom.gui.service.ContactService.getInstance();
            
            // Add contact if doesn't exist
            if (!contactService.hasContact(sender)) {
                contactService.addNewContact(sender);
            }
            
            // Update last message (isFromMe = false)
            contactService.updateLastMessage(sender, messageText, false);
            
            System.out.printf("[Chat] 📬 Updated contact last message for %s%n", sender);
            
        } catch (Exception e) {
            System.err.println("[Chat] Error updating contact for P2P message: " + e.getMessage());
        }
        
        // GUI'yi güncelle
        newMessageProperty.set(incomingMessage);
        
        System.out.printf("[Chat] ✅ P2P message added to channel: %s%n", sender);
    }

    // No dummy messages - start with clean slate
    private void setupDummyMessages() {
        // All chat channels start empty - real messages will be added via P2P
        System.out.println("[ChatService] 🧹 Started with clean message history - no dummy messages");
    }
}