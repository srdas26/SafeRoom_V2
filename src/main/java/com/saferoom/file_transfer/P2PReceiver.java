package com.saferoom.file_transfer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * P2P File Transfer Receiver - Farklı bilgisayarlardan test için
 * Kullanım: java P2PReceiver <bind_ip> <bind_port> <output_file>
 * Örnek: java P2PReceiver 0.0.0.0 9999 received_file.txt
 */
public class P2PReceiver {
    
    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("=== P2P File Transfer Receiver ===");
            System.out.println("Kullanım: java P2PReceiver <bind_ip> <bind_port> <output_file>");
            System.out.println("");
            System.out.println("Parametreler:");
            System.out.println("  bind_ip     : Dinlenecek IP adresi (0.0.0.0 = tüm interface'ler)");
            System.out.println("  bind_port   : Dinlenecek port numarası");
            System.out.println("  output_file : Alınacak dosyanın kaydedileceği yer");
            System.out.println("");
            System.out.println("Örnekler:");
            System.out.println("  java P2PReceiver 0.0.0.0 9999 received_file.txt");
            System.out.println("  java P2PReceiver 192.168.1.100 8888 document.pdf");
            return;
        }
        
        String bindIp = args[0];
        int bindPort;
        String outputFile = args[2];
        
        try {
            bindPort = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("❌ Hata: Port numarası geçersiz: " + args[1]);
            return;
        }
        
        if (bindPort < 1 || bindPort > 65535) {
            System.err.println("❌ Hata: Port numarası 1-65535 arasında olmalı: " + bindPort);
            return;
        }
        
        DatagramChannel receiverChannel = null;
        
        try {
            System.out.println("=== P2P File Transfer Receiver ===");
            System.out.println("🔵 Receiver başlatılıyor...");
            System.out.println("🔵 Bind IP: " + bindIp);
            System.out.println("🔵 Bind Port: " + bindPort);
            System.out.println("🔵 Output File: " + outputFile);
            System.out.println("");
            
            // Channel setup with optimized buffers
            receiverChannel = DatagramChannel.open();
            
            // ULTRA BÜYÜK UDP BUFFER'LAR - Maximum throughput için
            receiverChannel.setOption(java.net.StandardSocketOptions.SO_SNDBUF, 16 * 1024 * 1024); // 16MB send buffer
            receiverChannel.setOption(java.net.StandardSocketOptions.SO_RCVBUF, 16 * 1024 * 1024); // 16MB receive buffer
            
            InetSocketAddress bindAddress = new InetSocketAddress(bindIp, bindPort);
            receiverChannel.bind(bindAddress);
            
            System.out.println("✅ Socket başarıyla bind edildi: " + bindAddress);
            System.out.println("🔵 Sender'dan bağlantı bekleniyor...");
            System.out.println("🔵 Handshake için maksimum 60 saniye beklenecek...");
            System.out.println("");
            
            // FileTransferReceiver kullan
            FileTransferReceiver receiver = new FileTransferReceiver();
            receiver.channel = receiverChannel;
            receiver.filePath = Paths.get(outputFile);
            
            // Transfer'i başlat (timing receiver içinde yapılacak)
            receiver.ReceiveData();
            
            double transferTime = receiver.getTransferTimeSeconds();
            
            // Sonuçları göster
            Path receivedFile = Paths.get(outputFile);
            if (Files.exists(receivedFile)) {
                long fileSize = Files.size(receivedFile);
                double fileSizeMB = fileSize / (1024.0 * 1024.0);
                double throughputMBps = fileSizeMB / transferTime;
                
                System.out.println("");
                System.out.println("=== Transfer Tamamlandı ===");
                System.out.println("✅ Dosya başarıyla alındı: " + receivedFile.toAbsolutePath());
                System.out.println("📁 Dosya boyutu: " + fileSize + " bytes (" + String.format("%.2f", fileSizeMB) + " MB)");
                System.out.println("⏱️  Transfer süresi: " + String.format("%.2f", transferTime) + " saniye");
                System.out.println("🚀 Transfer hızı: " + String.format("%.2f", throughputMBps) + " MB/s");
            } else {
                System.err.println("❌ Transfer başarısız: Dosya oluşturulamadı");
            }
            
        } catch (IOException e) {
            System.err.println("❌ IO Hatası: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("❌ Beklenmeyen hata: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cleanup
            if (receiverChannel != null && receiverChannel.isOpen()) {
                try {
                    receiverChannel.close();
                    System.out.println("🔵 Receiver kapatıldı");
                } catch (IOException e) {
                    System.err.println("⚠️  Channel kapatma hatası: " + e.getMessage());
                }
            }
            
            System.out.println("🔵 P2P Receiver sona erdi");
        }
    }
}