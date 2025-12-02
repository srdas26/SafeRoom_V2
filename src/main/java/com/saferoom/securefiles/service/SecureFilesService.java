package com.saferoom.securefiles.service;

import com.saferoom.securefiles.crypto.AES256GCMEncryptor;
import com.saferoom.securefiles.crypto.AES256GCMDecryptor;
import com.saferoom.securefiles.compression.FileCompressor;
import com.saferoom.securefiles.storage.SecureFilesDatabase;
import com.saferoom.securefiles.storage.SecureFilesDatabase.SecureFileRecord;

import javax.crypto.SecretKey;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Secure Files Service - Main Orchestrator
 * 
 * Coordinates encryption, compression, and database operations
 * All operations are async to prevent UI freezing
 */
public class SecureFilesService {
    
    private static SecureFilesService instance;
    private final ExecutorService executorService;
    private final SecureFilesDatabase database;
    private final Path vaultDirectory;
    
    private SecureFilesService(String dataDir) throws Exception {
        this.executorService = Executors.newFixedThreadPool(4);
        this.database = SecureFilesDatabase.initialize(dataDir);
        
        // Create vault directory for encrypted files
        this.vaultDirectory = Paths.get(dataDir, "vault");
        Files.createDirectories(vaultDirectory);
        
        System.out.println("[SecureFilesService] ✅ Service initialized");
        System.out.println("[SecureFilesService] Vault directory: " + vaultDirectory);
    }
    
    public static synchronized SecureFilesService initialize(String dataDir) throws Exception {
        if (instance == null) {
            instance = new SecureFilesService(dataDir);
        }
        return instance;
    }
    
    public static SecureFilesService getInstance() {
        if (instance == null) {
            throw new IllegalStateException("SecureFilesService not initialized");
        }
        return instance;
    }
    
    /**
     * Encrypt a file (async)
     * 
     * @param sourceFile File to encrypt
     * @param compress Whether to compress before encryption
     * @return CompletableFuture with EncryptionResult
     */
    public CompletableFuture<EncryptionResult> encryptFileAsync(Path sourceFile, boolean compress) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("[SecureFiles] 🔒 Starting encryption: " + sourceFile.getFileName());
                
                // Generate encryption key
                SecretKey key = AES256GCMEncryptor.generateKey();
                
                Path fileToEncrypt = sourceFile;
                String compressionType = null;
                long compressedSize = 0;
                
                // Step 1: Compress if requested
                if (compress) {
                    System.out.println("[SecureFiles] 📦 Compressing file...");
                    Path compressedFile = Files.createTempFile("saferoom_compressed_", ".zip");
                    
                    FileCompressor.CompressionResult compResult = 
                        FileCompressor.compressZip(sourceFile, compressedFile);
                    
                    if (compResult.isSuccess()) {
                        fileToEncrypt = compressedFile;
                        compressionType = "ZIP";
                        compressedSize = compResult.getCompressedSize();
                    } else {
                        System.err.println("[SecureFiles] ⚠️ Compression failed, encrypting without compression");
                    }
                }
                
                // Step 2: Encrypt
                String encryptedFileName = sourceFile.getFileName().toString() + ".enc";
                Path encryptedFile = vaultDirectory.resolve(encryptedFileName);
                
                // Use streaming for large files (>10MB)
                long fileSize = Files.size(fileToEncrypt);
                AES256GCMEncryptor.EncryptionResult encResult;
                
                if (fileSize > 10 * 1024 * 1024) {
                    encResult = AES256GCMEncryptor.encryptFileStreaming(fileToEncrypt, encryptedFile, key);
                } else {
                    encResult = AES256GCMEncryptor.encryptFile(fileToEncrypt, encryptedFile, key);
                }
                
                // Step 3: Calculate SHA-256 hash of original file
                String hash = calculateSHA256(sourceFile);
                
                // Step 4: Save to database
                SecureFileRecord record = new SecureFileRecord();
                record.fileName = encryptedFileName;
                record.originalName = sourceFile.getFileName().toString();
                record.mimeType = Files.probeContentType(sourceFile);
                record.originalSize = Files.size(sourceFile);
                record.encryptedSize = encResult.getEncryptedSize();
                record.encrypted = true;
                record.compressed = compress;
                record.compressionType = compressionType;
                record.localPath = encryptedFile.toAbsolutePath().toString();
                record.hashSha256 = hash;
                record.ivBase64 = encResult.getIvBase64();
                record.createdAt = System.currentTimeMillis();
                record.modifiedAt = System.currentTimeMillis();
                
                // Store original extension in metadata
                record.metadata.put("original_extension", getFileExtension(sourceFile));
                if (compress) {
                    record.metadata.put("compressed_size", compressedSize);
                }
                
                long recordId = database.insertEncryptedFile(record);
                
                // Clean up temp compressed file
                if (compress && fileToEncrypt != sourceFile) {
                    Files.deleteIfExists(fileToEncrypt);
                }
                
                System.out.println("[SecureFiles] ✅ Encryption complete!");
                
                return new EncryptionResult(
                    true,
                    recordId,
                    encResult.getKeyBase64(),
                    encResult.getIvBase64(),
                    encryptedFile.toAbsolutePath().toString(),
                    "Encryption successful"
                );
                
            } catch (Exception e) {
                System.err.println("[SecureFiles] ❌ Encryption failed: " + e.getMessage());
                e.printStackTrace();
                return new EncryptionResult(
                    false,
                    -1,
                    null,
                    null,
                    null,
                    "Encryption failed: " + e.getMessage()
                );
            }
        }, executorService);
    }
    
    /**
     * Decrypt a file (async)
     * 
     * @param fileId Database ID of encrypted file
     * @param keyBase64 Base64-encoded decryption key
     * @param outputDir Directory to save decrypted file
     * @return CompletableFuture with DecryptionResult
     */
    public CompletableFuture<DecryptionResult> decryptFileAsync(long fileId, String keyBase64, Path outputDir) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("[SecureFiles] 🔓 Starting decryption for file ID: " + fileId);
                
                // Get file record from database
                SecureFileRecord record = database.getFileById(fileId);
                if (record == null) {
                    return new DecryptionResult(false, null, "File not found in database");
                }
                
                // Convert key
                SecretKey key = AES256GCMEncryptor.base64ToKey(keyBase64);
                
                // Decrypt
                Path encryptedFile = Paths.get(record.localPath);
                Path decryptedFile = Files.createTempFile("saferoom_decrypted_", "");
                
                AES256GCMDecryptor.DecryptionResult decResult;
                
                if (record.encryptedSize > 10 * 1024 * 1024) {
                    decResult = AES256GCMDecryptor.decryptFileStreaming(encryptedFile, decryptedFile, key);
                } else {
                    decResult = AES256GCMDecryptor.decryptFile(encryptedFile, decryptedFile, key);
                }
                
                if (!decResult.isSuccess()) {
                    Files.deleteIfExists(decryptedFile);
                    return new DecryptionResult(false, null, decResult.getMessage());
                }
                
                // Decompress if needed
                Path finalFile = decryptedFile;
                if (record.compressed) {
                    System.out.println("[SecureFiles] 📦 Decompressing file...");
                    
                    if ("ZIP".equals(record.compressionType)) {
                        FileCompressor.decompressZip(decryptedFile, outputDir);
                        finalFile = outputDir.resolve(record.originalName);
                    }
                    
                    Files.deleteIfExists(decryptedFile);
                } else {
                    // Move to output directory with original name
                    finalFile = outputDir.resolve(record.originalName);
                    Files.move(decryptedFile, finalFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                
                System.out.println("[SecureFiles] ✅ Decryption complete!");
                
                return new DecryptionResult(
                    true,
                    finalFile.toAbsolutePath().toString(),
                    "Decryption successful"
                );
                
            } catch (Exception e) {
                System.err.println("[SecureFiles] ❌ Decryption failed: " + e.getMessage());
                e.printStackTrace();
                return new DecryptionResult(
                    false,
                    null,
                    "Decryption failed: " + e.getMessage()
                );
            }
        }, executorService);
    }
    
    /**
     * Get all encrypted files from vault
     */
    public CompletableFuture<List<SecureFileRecord>> getAllFilesAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return database.getAllFiles();
            } catch (Exception e) {
                System.err.println("[SecureFiles] Failed to load files: " + e.getMessage());
                return List.of();
            }
        }, executorService);
    }
    
    /**
     * Delete encrypted file
     */
    public CompletableFuture<Boolean> deleteFileAsync(long fileId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                SecureFileRecord record = database.getFileById(fileId);
                if (record != null) {
                    // Delete physical file
                    Path filePath = Paths.get(record.localPath);
                    Files.deleteIfExists(filePath);
                    
                    // Delete database record
                    return database.deleteFile(fileId);
                }
                return false;
            } catch (Exception e) {
                System.err.println("[SecureFiles] Failed to delete file: " + e.getMessage());
                return false;
            }
        }, executorService);
    }
    
    /**
     * Calculate SHA-256 hash of file
     */
    private String calculateSHA256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] fileBytes = Files.readAllBytes(file);
        byte[] hash = digest.digest(fileBytes);
        return Base64.getEncoder().encodeToString(hash);
    }
    
    /**
     * Get file extension
     */
    private String getFileExtension(Path file) {
        String fileName = file.getFileName().toString();
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot) : "";
    }
    
    /**
     * Shutdown service
     */
    public void shutdown() {
        executorService.shutdown();
        database.close();
        System.out.println("[SecureFilesService] Service shutdown");
    }
    
    /**
     * Encryption result data class
     */
    public static class EncryptionResult {
        public final boolean success;
        public final long fileId;
        public final String keyBase64;
        public final String ivBase64;
        public final String encryptedFilePath;
        public final String message;
        
        public EncryptionResult(boolean success, long fileId, String keyBase64, 
                                String ivBase64, String encryptedFilePath, String message) {
            this.success = success;
            this.fileId = fileId;
            this.keyBase64 = keyBase64;
            this.ivBase64 = ivBase64;
            this.encryptedFilePath = encryptedFilePath;
            this.message = message;
        }
    }
    
    /**
     * Decryption result data class
     */
    public static class DecryptionResult {
        public final boolean success;
        public final String decryptedFilePath;
        public final String message;
        
        public DecryptionResult(boolean success, String decryptedFilePath, String message) {
            this.success = success;
            this.decryptedFilePath = decryptedFilePath;
            this.message = message;
        }
    }
}

