-- ============================================
-- SECURE FILES DATABASE SCHEMA
-- Standalone encryption vault (NOT DM-related)
-- ============================================

CREATE TABLE IF NOT EXISTS secure_files (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    
    -- File Identity
    file_name TEXT NOT NULL,
    original_name TEXT NOT NULL,  -- Before encryption
    mime_type TEXT,
    
    -- Size Info
    original_size INTEGER NOT NULL,  -- Bytes before encryption
    encrypted_size INTEGER NOT NULL, -- Bytes after encryption
    
    -- Encryption Metadata
    encrypted BOOLEAN NOT NULL DEFAULT 1,
    compressed BOOLEAN NOT NULL DEFAULT 0,
    compression_type TEXT,  -- 'zstd', 'zip', or NULL
    
    -- Storage
    local_path TEXT NOT NULL UNIQUE,  -- Absolute path to .enc file
    
    -- Security
    hash_sha256 TEXT NOT NULL,  -- SHA-256 of original file
    key_id TEXT,  -- Reference to key in keychain (optional)
    iv_base64 TEXT NOT NULL,  -- Initialization Vector (base64)
    
    -- Timestamps
    created_at INTEGER NOT NULL,  -- Unix timestamp
    modified_at INTEGER NOT NULL,
    
    -- Metadata JSON
    metadata_json TEXT  -- Additional info (original extension, etc.)
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_secure_files_created 
    ON secure_files(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_secure_files_name 
    ON secure_files(file_name);

CREATE INDEX IF NOT EXISTS idx_secure_files_encrypted 
    ON secure_files(encrypted);

-- ============================================
-- ENCRYPTION KEYS TABLE (Optional - for key management)
-- ============================================
CREATE TABLE IF NOT EXISTS encryption_keys (
    key_id TEXT PRIMARY KEY,
    key_name TEXT,
    created_at INTEGER NOT NULL,
    last_used INTEGER,
    usage_count INTEGER DEFAULT 0
    -- Note: Actual key stored in OS keychain, not here
);

-- ============================================
-- ENCRYPTION OPERATIONS LOG
-- ============================================
CREATE TABLE IF NOT EXISTS encryption_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    operation_type TEXT NOT NULL,  -- 'ENCRYPT', 'DECRYPT', 'DELETE'
    file_id INTEGER,
    file_name TEXT,
    success BOOLEAN NOT NULL,
    error_message TEXT,
    timestamp INTEGER NOT NULL,
    
    FOREIGN KEY (file_id) REFERENCES secure_files(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_encryption_log_timestamp 
    ON encryption_log(timestamp DESC);

