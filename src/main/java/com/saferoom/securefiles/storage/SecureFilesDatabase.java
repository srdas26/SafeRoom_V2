package com.saferoom.securefiles.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Secure Files Database Manager
 * 
 * Manages encrypted files vault (NOT DM-related)
 * SQLite database for tracking encrypted files
 */
public class SecureFilesDatabase {
    
    private static SecureFilesDatabase instance;
    private Connection connection;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    private static final String DB_NAME = "secure_files.db";
    
    private SecureFilesDatabase(String dataDir) throws SQLException {
        try {
            // Create data directory if not exists
            Path dirPath = Paths.get(dataDir);
            Files.createDirectories(dirPath);
            
            // Connect to database
            String dbPath = Paths.get(dataDir, DB_NAME).toString();
            String url = "jdbc:sqlite:" + dbPath;
            connection = DriverManager.getConnection(url);
            
            // Enable foreign keys
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
            }
            
            initializeSchema();
            
            System.out.println("[SecureFilesDB] ✅ Database initialized at: " + dbPath);
            
        } catch (Exception e) {
            System.err.println("[SecureFilesDB] ❌ Failed to initialize database: " + e.getMessage());
            throw new SQLException("Database initialization failed", e);
        }
    }
    
    public static synchronized SecureFilesDatabase initialize(String dataDir) throws SQLException {
        if (instance == null) {
            instance = new SecureFilesDatabase(dataDir);
        }
        return instance;
    }
    
    public static SecureFilesDatabase getInstance() {
        if (instance == null) {
            throw new IllegalStateException("SecureFilesDatabase not initialized");
        }
        return instance;
    }
    
    private void initializeSchema() throws SQLException {
        String createTableSQL = """
            CREATE TABLE IF NOT EXISTS secure_files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_name TEXT NOT NULL,
                original_name TEXT NOT NULL,
                mime_type TEXT,
                original_size INTEGER NOT NULL,
                encrypted_size INTEGER NOT NULL,
                encrypted BOOLEAN NOT NULL DEFAULT 1,
                compressed BOOLEAN NOT NULL DEFAULT 0,
                compression_type TEXT,
                local_path TEXT NOT NULL UNIQUE,
                hash_sha256 TEXT NOT NULL,
                key_id TEXT,
                iv_base64 TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                modified_at INTEGER NOT NULL,
                metadata_json TEXT
            )
            """;
        
        String createIndexes = """
            CREATE INDEX IF NOT EXISTS idx_secure_files_created 
                ON secure_files(created_at DESC);
            CREATE INDEX IF NOT EXISTS idx_secure_files_name 
                ON secure_files(file_name);
            """;
        
        String createLogTable = """
            CREATE TABLE IF NOT EXISTS encryption_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                operation_type TEXT NOT NULL,
                file_id INTEGER,
                file_name TEXT,
                success BOOLEAN NOT NULL,
                error_message TEXT,
                timestamp INTEGER NOT NULL,
                FOREIGN KEY (file_id) REFERENCES secure_files(id) ON DELETE SET NULL
            )
            """;
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTableSQL);
            stmt.execute(createIndexes);
            stmt.execute(createLogTable);
        }
    }
    
    /**
     * Insert a new encrypted file record
     */
    public long insertEncryptedFile(SecureFileRecord record) throws SQLException {
        String sql = """
            INSERT INTO secure_files (
                file_name, original_name, mime_type, original_size, encrypted_size,
                encrypted, compressed, compression_type, local_path, hash_sha256,
                key_id, iv_base64, created_at, modified_at, metadata_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, record.fileName);
            pstmt.setString(2, record.originalName);
            pstmt.setString(3, record.mimeType);
            pstmt.setLong(4, record.originalSize);
            pstmt.setLong(5, record.encryptedSize);
            pstmt.setBoolean(6, record.encrypted);
            pstmt.setBoolean(7, record.compressed);
            pstmt.setString(8, record.compressionType);
            pstmt.setString(9, record.localPath);
            pstmt.setString(10, record.hashSha256);
            pstmt.setString(11, record.keyId);
            pstmt.setString(12, record.ivBase64);
            pstmt.setLong(13, record.createdAt);
            pstmt.setLong(14, record.modifiedAt);
            pstmt.setString(15, gson.toJson(record.metadata));
            
            pstmt.executeUpdate();
            
            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    logOperation("ENCRYPT", id, record.fileName, true, null);
                    return id;
                }
            }
        }
        
        return -1;
    }
    
    /**
     * Get all encrypted files
     */
    public List<SecureFileRecord> getAllFiles() throws SQLException {
        String sql = "SELECT * FROM secure_files ORDER BY created_at DESC";
        List<SecureFileRecord> files = new ArrayList<>();
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                files.add(mapResultSetToRecord(rs));
            }
        }
        
        return files;
    }
    
    /**
     * Get file by ID
     */
    public SecureFileRecord getFileById(long id) throws SQLException {
        String sql = "SELECT * FROM secure_files WHERE id = ?";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return mapResultSetToRecord(rs);
                }
            }
        }
        
        return null;
    }
    
    /**
     * Delete file record
     */
    public boolean deleteFile(long id) throws SQLException {
        SecureFileRecord record = getFileById(id);
        if (record == null) {
            return false;
        }
        
        String sql = "DELETE FROM secure_files WHERE id = ?";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            int affected = pstmt.executeUpdate();
            
            if (affected > 0) {
                logOperation("DELETE", id, record.fileName, true, null);
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Log encryption operation
     */
    private void logOperation(String operationType, long fileId, String fileName, 
                              boolean success, String errorMessage) {
        String sql = """
            INSERT INTO encryption_log (operation_type, file_id, file_name, success, error_message, timestamp)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, operationType);
            pstmt.setLong(2, fileId);
            pstmt.setString(3, fileName);
            pstmt.setBoolean(4, success);
            pstmt.setString(5, errorMessage);
            pstmt.setLong(6, System.currentTimeMillis());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[SecureFilesDB] Failed to log operation: " + e.getMessage());
        }
    }
    
    /**
     * Map ResultSet to SecureFileRecord
     */
    private SecureFileRecord mapResultSetToRecord(ResultSet rs) throws SQLException {
        SecureFileRecord record = new SecureFileRecord();
        record.id = rs.getLong("id");
        record.fileName = rs.getString("file_name");
        record.originalName = rs.getString("original_name");
        record.mimeType = rs.getString("mime_type");
        record.originalSize = rs.getLong("original_size");
        record.encryptedSize = rs.getLong("encrypted_size");
        record.encrypted = rs.getBoolean("encrypted");
        record.compressed = rs.getBoolean("compressed");
        record.compressionType = rs.getString("compression_type");
        record.localPath = rs.getString("local_path");
        record.hashSha256 = rs.getString("hash_sha256");
        record.keyId = rs.getString("key_id");
        record.ivBase64 = rs.getString("iv_base64");
        record.createdAt = rs.getLong("created_at");
        record.modifiedAt = rs.getLong("modified_at");
        
        String metadataJson = rs.getString("metadata_json");
        if (metadataJson != null) {
            record.metadata = gson.fromJson(metadataJson, Map.class);
        }
        
        return record;
    }
    
    /**
     * Close database connection
     */
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                System.out.println("[SecureFilesDB] Database connection closed");
            }
        } catch (SQLException e) {
            System.err.println("[SecureFilesDB] Error closing database: " + e.getMessage());
        }
    }
    
    /**
     * Secure File Record data class
     */
    public static class SecureFileRecord {
        public long id;
        public String fileName;
        public String originalName;
        public String mimeType;
        public long originalSize;
        public long encryptedSize;
        public boolean encrypted;
        public boolean compressed;
        public String compressionType;
        public String localPath;
        public String hashSha256;
        public String keyId;
        public String ivBase64;
        public long createdAt;
        public long modifiedAt;
        public Map<String, Object> metadata = new HashMap<>();
    }
}

