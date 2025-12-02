package com.saferoom.securefiles.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.AEADBadTagException;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * AES-256-GCM File Decryptor
 * 
 * Decrypts files encrypted by AES256GCMEncryptor
 * Validates authentication tag to ensure integrity
 */
public class AES256GCMDecryptor {
    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_SIZE = 12;
    private static final int TAG_SIZE = 128;
    
    /**
     * Decrypt a file using AES-256-GCM
     * 
     * @param encryptedFile Encrypted file (.enc)
     * @param outputFile Destination decrypted file
     * @param key AES-256 key (must match encryption key)
     * @return DecryptionResult with status and metadata
     */
    public static DecryptionResult decryptFile(Path encryptedFile, Path outputFile, SecretKey key) 
            throws Exception {
        
        long startTime = System.currentTimeMillis();
        
        // Read encrypted file
        byte[] encryptedData = Files.readAllBytes(encryptedFile);
        
        if (encryptedData.length < IV_SIZE) {
            return new DecryptionResult(false, "File too small to be valid encrypted file", 0, 0);
        }
        
        // Extract IV (first 12 bytes)
        byte[] iv = new byte[IV_SIZE];
        System.arraycopy(encryptedData, 0, iv, 0, IV_SIZE);
        
        // Extract ciphertext + tag
        byte[] ciphertext = new byte[encryptedData.length - IV_SIZE];
        System.arraycopy(encryptedData, IV_SIZE, ciphertext, 0, ciphertext.length);
        
        try {
            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_SIZE, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
            
            // Decrypt (this also validates the authentication tag)
            byte[] decryptedBytes = cipher.doFinal(ciphertext);
            
            // Write decrypted file
            Files.write(outputFile, decryptedBytes);
            
            long duration = System.currentTimeMillis() - startTime;
            
            System.out.printf("[Decryptor] ✅ Decrypted: %s → %s (%.2f KB) in %d ms%n",
                encryptedFile.getFileName(), outputFile.getFileName(),
                decryptedBytes.length / 1024.0, duration);
            
            return new DecryptionResult(
                true,
                "Decryption successful",
                encryptedData.length,
                decryptedBytes.length,
                duration
            );
            
        } catch (AEADBadTagException e) {
            // Authentication tag validation failed
            System.err.println("[Decryptor] ❌ Authentication failed: Wrong key or tampered file");
            return new DecryptionResult(
                false,
                "Wrong decryption key or file has been tampered with",
                encryptedData.length,
                0
            );
            
        } catch (Exception e) {
            System.err.println("[Decryptor] ❌ Decryption error: " + e.getMessage());
            return new DecryptionResult(
                false,
                "Decryption failed: " + e.getMessage(),
                encryptedData.length,
                0
            );
        }
    }
    
    /**
     * Decrypt file with streaming (for large files)
     */
    public static DecryptionResult decryptFileStreaming(Path encryptedFile, Path outputFile, SecretKey key)
            throws Exception {
        
        long startTime = System.currentTimeMillis();
        long encryptedSize = 0;
        long decryptedSize = 0;
        
        try (FileInputStream fis = new FileInputStream(encryptedFile.toFile());
             FileOutputStream fos = new FileOutputStream(outputFile.toFile())) {
            
            // Read IV
            byte[] iv = new byte[IV_SIZE];
            int ivRead = fis.read(iv);
            if (ivRead != IV_SIZE) {
                return new DecryptionResult(false, "Invalid encrypted file format", 0, 0);
            }
            encryptedSize += ivRead;
            
            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_SIZE, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
            
            // Process in chunks
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = fis.read(buffer)) != -1) {
                encryptedSize += bytesRead;
                byte[] output = cipher.update(buffer, 0, bytesRead);
                if (output != null) {
                    fos.write(output);
                    decryptedSize += output.length;
                }
            }
            
            // Finalize (validates auth tag)
            byte[] finalOutput = cipher.doFinal();
            fos.write(finalOutput);
            decryptedSize += finalOutput.length;
            
            long duration = System.currentTimeMillis() - startTime;
            
            System.out.printf("[Decryptor] ✅ Decrypted (streaming): %s (%.2f MB) in %d ms%n",
                encryptedFile.getFileName(), decryptedSize / (1024.0 * 1024.0), duration);
            
            return new DecryptionResult(
                true,
                "Decryption successful",
                encryptedSize,
                decryptedSize,
                duration
            );
            
        } catch (AEADBadTagException e) {
            System.err.println("[Decryptor] ❌ Authentication failed");
            return new DecryptionResult(
                false,
                "Wrong decryption key or file has been tampered with",
                encryptedSize,
                0
            );
        } catch (Exception e) {
            System.err.println("[Decryptor] ❌ Decryption error: " + e.getMessage());
            e.printStackTrace();
            return new DecryptionResult(
                false,
                "Decryption failed: " + e.getMessage(),
                encryptedSize,
                0
            );
        }
    }
    
    /**
     * Validate if a file is properly encrypted (has valid IV header)
     */
    public static boolean isValidEncryptedFile(Path file) {
        try {
            long fileSize = Files.size(file);
            if (fileSize < IV_SIZE + 16) { // IV + minimum tag size
                return false;
            }
            
            // Check if file has .enc extension (optional)
            String fileName = file.getFileName().toString();
            return fileName.endsWith(".enc");
            
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Decryption result data class
     */
    public static class DecryptionResult {
        private final boolean success;
        private final String message;
        private final long encryptedSize;
        private final long decryptedSize;
        private final long durationMs;
        
        public DecryptionResult(boolean success, String message, 
                                long encryptedSize, long decryptedSize) {
            this(success, message, encryptedSize, decryptedSize, 0);
        }
        
        public DecryptionResult(boolean success, String message,
                                long encryptedSize, long decryptedSize, long durationMs) {
            this.success = success;
            this.message = message;
            this.encryptedSize = encryptedSize;
            this.decryptedSize = decryptedSize;
            this.durationMs = durationMs;
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public long getEncryptedSize() { return encryptedSize; }
        public long getDecryptedSize() { return decryptedSize; }
        public long getDurationMs() { return durationMs; }
    }
}

