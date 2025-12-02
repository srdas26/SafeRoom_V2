package com.saferoom.securefiles.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM File Encryptor
 * 
 * Specification:
 * - Algorithm: AES/GCM/NoPadding
 * - Key Size: 256 bits (32 bytes)
 * - IV Size: 96 bits (12 bytes)
 * - Tag Size: 128 bits (16 bytes)
 * - Provides authenticated encryption (confidentiality + integrity)
 */
public class AES256GCMEncryptor {
    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_SIZE = 256;
    private static final int IV_SIZE = 12;  // 96 bits
    private static final int TAG_SIZE = 128; // 128 bits
    
    private static final SecureRandom secureRandom = new SecureRandom();
    
    /**
     * Generate a new 256-bit AES key
     */
    public static SecretKey generateKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(KEY_SIZE, secureRandom);
        return keyGen.generateKey();
    }
    
    /**
     * Generate a random 12-byte IV
     */
    public static byte[] generateIV() {
        byte[] iv = new byte[IV_SIZE];
        secureRandom.nextBytes(iv);
        return iv;
    }
    
    /**
     * Encrypt a file using AES-256-GCM
     * 
     * @param inputFile Source file to encrypt
     * @param outputFile Destination encrypted file
     * @param key AES-256 key
     * @return EncryptionResult containing IV and metadata
     */
    public static EncryptionResult encryptFile(Path inputFile, Path outputFile, SecretKey key) 
            throws Exception {
        
        long startTime = System.currentTimeMillis();
        
        // Generate IV
        byte[] iv = generateIV();
        
        // Initialize cipher
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_SIZE, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
        
        // Read input file
        byte[] inputBytes = Files.readAllBytes(inputFile);
        long originalSize = inputBytes.length;
        
        // Encrypt
        byte[] encryptedBytes = cipher.doFinal(inputBytes);
        
        // Write encrypted file format:
        // [IV (12 bytes)][Encrypted Data + Auth Tag]
        try (FileOutputStream fos = new FileOutputStream(outputFile.toFile())) {
            fos.write(iv);
            fos.write(encryptedBytes);
        }
        
        long encryptedSize = iv.length + encryptedBytes.length;
        long duration = System.currentTimeMillis() - startTime;
        
        System.out.printf("[Encryptor] ✅ Encrypted: %s → %s (%.2f KB → %.2f KB) in %d ms%n",
            inputFile.getFileName(), outputFile.getFileName(),
            originalSize / 1024.0, encryptedSize / 1024.0, duration);
        
        return new EncryptionResult(
            true,
            Base64.getEncoder().encodeToString(iv),
            Base64.getEncoder().encodeToString(key.getEncoded()),
            originalSize,
            encryptedSize,
            duration
        );
    }
    
    /**
     * Encrypt file with in-memory processing (for large files, use streaming)
     */
    public static EncryptionResult encryptFileStreaming(Path inputFile, Path outputFile, SecretKey key)
            throws Exception {
        
        long startTime = System.currentTimeMillis();
        byte[] iv = generateIV();
        
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_SIZE, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
        
        long originalSize = 0;
        long encryptedSize = 0;
        
        try (FileInputStream fis = new FileInputStream(inputFile.toFile());
             FileOutputStream fos = new FileOutputStream(outputFile.toFile())) {
            
            // Write IV first
            fos.write(iv);
            encryptedSize += iv.length;
            
            // Process in chunks
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = fis.read(buffer)) != -1) {
                originalSize += bytesRead;
                byte[] output = cipher.update(buffer, 0, bytesRead);
                if (output != null) {
                    fos.write(output);
                    encryptedSize += output.length;
                }
            }
            
            // Finalize (includes auth tag)
            byte[] finalOutput = cipher.doFinal();
            fos.write(finalOutput);
            encryptedSize += finalOutput.length;
        }
        
        long duration = System.currentTimeMillis() - startTime;
        
        System.out.printf("[Encryptor] ✅ Encrypted (streaming): %s (%.2f MB) in %d ms%n",
            inputFile.getFileName(), originalSize / (1024.0 * 1024.0), duration);
        
        return new EncryptionResult(
            true,
            Base64.getEncoder().encodeToString(iv),
            Base64.getEncoder().encodeToString(key.getEncoded()),
            originalSize,
            encryptedSize,
            duration
        );
    }
    
    /**
     * Convert key bytes to SecretKey
     */
    public static SecretKey bytesToKey(byte[] keyBytes) {
        return new SecretKeySpec(keyBytes, "AES");
    }
    
    /**
     * Convert base64 key string to SecretKey
     */
    public static SecretKey base64ToKey(String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        return bytesToKey(keyBytes);
    }
    
    /**
     * Encryption result data class
     */
    public static class EncryptionResult {
        private final boolean success;
        private final String ivBase64;
        private final String keyBase64;
        private final long originalSize;
        private final long encryptedSize;
        private final long durationMs;
        
        public EncryptionResult(boolean success, String ivBase64, String keyBase64,
                                long originalSize, long encryptedSize, long durationMs) {
            this.success = success;
            this.ivBase64 = ivBase64;
            this.keyBase64 = keyBase64;
            this.originalSize = originalSize;
            this.encryptedSize = encryptedSize;
            this.durationMs = durationMs;
        }
        
        public boolean isSuccess() { return success; }
        public String getIvBase64() { return ivBase64; }
        public String getKeyBase64() { return keyBase64; }
        public long getOriginalSize() { return originalSize; }
        public long getEncryptedSize() { return encryptedSize; }
        public long getDurationMs() { return durationMs; }
        
        public byte[] getKeyBytes() {
            return Base64.getDecoder().decode(keyBase64);
        }
        
        public byte[] getIvBytes() {
            return Base64.getDecoder().decode(ivBase64);
        }
    }
}

