package com.saferoom.securefiles.compression;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.*;

/**
 * File Compression Service
 * 
 * Supports:
 * - ZIP compression (Java built-in)
 * - GZIP compression (Java built-in)
 * 
 * Note: Zstd would require external library (com.github.luben:zstd-jni)
 * For now, using ZIP which is universally supported
 */
public class FileCompressor {
    
    public enum CompressionType {
        ZIP,
        GZIP,
        NONE
    }
    
    /**
     * Compress a file using ZIP
     */
    public static CompressionResult compressZip(Path inputFile, Path outputFile) throws Exception {
        long startTime = System.currentTimeMillis();
        long originalSize = Files.size(inputFile);
        
        try (FileInputStream fis = new FileInputStream(inputFile.toFile());
             FileOutputStream fos = new FileOutputStream(outputFile.toFile());
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            
            // Set compression level (0-9, 9 = best compression)
            zos.setLevel(Deflater.BEST_COMPRESSION);
            
            // Create zip entry
            ZipEntry zipEntry = new ZipEntry(inputFile.getFileName().toString());
            zos.putNextEntry(zipEntry);
            
            // Write file data
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                zos.write(buffer, 0, bytesRead);
            }
            
            zos.closeEntry();
        }
        
        long compressedSize = Files.size(outputFile);
        long duration = System.currentTimeMillis() - startTime;
        double ratio = (1.0 - (double) compressedSize / originalSize) * 100;
        
        System.out.printf("[Compressor] ✅ ZIP: %s (%.2f KB → %.2f KB, %.1f%% reduction) in %d ms%n",
            inputFile.getFileName(),
            originalSize / 1024.0,
            compressedSize / 1024.0,
            ratio,
            duration);
        
        return new CompressionResult(
            true,
            CompressionType.ZIP,
            originalSize,
            compressedSize,
            ratio,
            duration
        );
    }
    
    /**
     * Compress a file using GZIP
     */
    public static CompressionResult compressGzip(Path inputFile, Path outputFile) throws Exception {
        long startTime = System.currentTimeMillis();
        long originalSize = Files.size(inputFile);
        
        try (FileInputStream fis = new FileInputStream(inputFile.toFile());
             FileOutputStream fos = new FileOutputStream(outputFile.toFile());
             GZIPOutputStream gzos = new GZIPOutputStream(fos)) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                gzos.write(buffer, 0, bytesRead);
            }
        }
        
        long compressedSize = Files.size(outputFile);
        long duration = System.currentTimeMillis() - startTime;
        double ratio = (1.0 - (double) compressedSize / originalSize) * 100;
        
        System.out.printf("[Compressor] ✅ GZIP: %s (%.2f KB → %.2f KB, %.1f%% reduction) in %d ms%n",
            inputFile.getFileName(),
            originalSize / 1024.0,
            compressedSize / 1024.0,
            ratio,
            duration);
        
        return new CompressionResult(
            true,
            CompressionType.GZIP,
            originalSize,
            compressedSize,
            ratio,
            duration
        );
    }
    
    /**
     * Decompress a ZIP file
     */
    public static DecompressionResult decompressZip(Path zipFile, Path outputDir) throws Exception {
        long startTime = System.currentTimeMillis();
        long totalDecompressed = 0;
        
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile.toFile()))) {
            ZipEntry entry;
            
            while ((entry = zis.getNextEntry()) != null) {
                Path outputFile = outputDir.resolve(entry.getName());
                
                // Create parent directories if needed
                Files.createDirectories(outputFile.getParent());
                
                try (FileOutputStream fos = new FileOutputStream(outputFile.toFile())) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = zis.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        totalDecompressed += bytesRead;
                    }
                }
                
                zis.closeEntry();
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        
        System.out.printf("[Compressor] ✅ Decompressed ZIP: %.2f KB in %d ms%n",
            totalDecompressed / 1024.0, duration);
        
        return new DecompressionResult(
            true,
            CompressionType.ZIP,
            Files.size(zipFile),
            totalDecompressed,
            duration
        );
    }
    
    /**
     * Decompress a GZIP file
     */
    public static DecompressionResult decompressGzip(Path gzipFile, Path outputFile) throws Exception {
        long startTime = System.currentTimeMillis();
        long totalDecompressed = 0;
        
        try (FileInputStream fis = new FileInputStream(gzipFile.toFile());
             GZIPInputStream gzis = new GZIPInputStream(fis);
             FileOutputStream fos = new FileOutputStream(outputFile.toFile())) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = gzis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalDecompressed += bytesRead;
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        
        System.out.printf("[Compressor] ✅ Decompressed GZIP: %.2f KB in %d ms%n",
            totalDecompressed / 1024.0, duration);
        
        return new DecompressionResult(
            true,
            CompressionType.GZIP,
            Files.size(gzipFile),
            totalDecompressed,
            duration
        );
    }
    
    /**
     * Compression result data class
     */
    public static class CompressionResult {
        private final boolean success;
        private final CompressionType type;
        private final long originalSize;
        private final long compressedSize;
        private final double compressionRatio;
        private final long durationMs;
        
        public CompressionResult(boolean success, CompressionType type,
                                 long originalSize, long compressedSize,
                                 double compressionRatio, long durationMs) {
            this.success = success;
            this.type = type;
            this.originalSize = originalSize;
            this.compressedSize = compressedSize;
            this.compressionRatio = compressionRatio;
            this.durationMs = durationMs;
        }
        
        public boolean isSuccess() { return success; }
        public CompressionType getType() { return type; }
        public long getOriginalSize() { return originalSize; }
        public long getCompressedSize() { return compressedSize; }
        public double getCompressionRatio() { return compressionRatio; }
        public long getDurationMs() { return durationMs; }
    }
    
    /**
     * Decompression result data class
     */
    public static class DecompressionResult {
        private final boolean success;
        private final CompressionType type;
        private final long compressedSize;
        private final long decompressedSize;
        private final long durationMs;
        
        public DecompressionResult(boolean success, CompressionType type,
                                   long compressedSize, long decompressedSize,
                                   long durationMs) {
            this.success = success;
            this.type = type;
            this.compressedSize = compressedSize;
            this.decompressedSize = decompressedSize;
            this.durationMs = durationMs;
        }
        
        public boolean isSuccess() { return success; }
        public CompressionType getType() { return type; }
        public long getCompressedSize() { return compressedSize; }
        public long getDecompressedSize() { return decompressedSize; }
        public long getDurationMs() { return durationMs; }
    }
}

