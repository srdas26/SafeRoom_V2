# 🔐 Secure Files - Complete Architecture Documentation

## 📋 Overview

**Secure Files** is a standalone file encryption/decryption module for SafeRoom. It operates **independently from DM** and provides:

- **AES-256-GCM encryption** (authenticated encryption)
- **Optional compression** (ZIP/GZIP) before encryption
- **Encrypted file vault** (SQLite database)
- **Key management** with QR code generation
- **Drag & drop** interface

---

## 🏗️ Architecture Layers

```
┌─────────────────────────────────────────────────────────────────┐
│                         UI LAYER                                 │
│  - SecureFilesController (drag & drop, vault management)        │
│  - KeyPopupController (key display, QR code)                    │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                      SERVICE LAYER                               │
│  - SecureFilesService (orchestrator)                            │
│  - Async operations (CompletableFuture)                         │
│  - Thread pool (4 threads)                                      │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                      CRYPTO LAYER                                │
│  - AES256GCMEncryptor (encryption)                              │
│  - AES256GCMDecryptor (decryption)                              │
│  - FileCompressor (ZIP/GZIP)                                    │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                      STORAGE LAYER                               │
│  - SecureFilesDatabase (SQLite)                                 │
│  - File system (vault directory)                                │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🔐 Encryption Specification

### AES-256-GCM Parameters

| Parameter | Value | Description |
|-----------|-------|-------------|
| **Algorithm** | AES/GCM/NoPadding | Galois/Counter Mode |
| **Key Size** | 256 bits (32 bytes) | Maximum security |
| **IV Size** | 96 bits (12 bytes) | Initialization Vector |
| **Tag Size** | 128 bits (16 bytes) | Authentication Tag |
| **Key Generation** | SecureRandom | CSPRNG |

### Encrypted File Format

```
[IV (12 bytes)][Encrypted Data + Auth Tag]
```

- **IV** is stored in plaintext (required for decryption)
- **Auth Tag** is appended by GCM mode (integrity check)
- **Encrypted Data** contains the actual file content

---

## 📊 Database Schema

### `secure_files` Table

```sql
CREATE TABLE secure_files (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_name TEXT NOT NULL,              -- Encrypted filename (.enc)
    original_name TEXT NOT NULL,          -- Original filename
    mime_type TEXT,
    original_size INTEGER NOT NULL,
    encrypted_size INTEGER NOT NULL,
    encrypted BOOLEAN NOT NULL DEFAULT 1,
    compressed BOOLEAN NOT NULL DEFAULT 0,
    compression_type TEXT,                -- 'ZIP', 'GZIP', or NULL
    local_path TEXT NOT NULL UNIQUE,      -- Absolute path to .enc file
    hash_sha256 TEXT NOT NULL,            -- SHA-256 of original file
    key_id TEXT,                          -- Optional key reference
    iv_base64 TEXT NOT NULL,              -- Base64-encoded IV
    created_at INTEGER NOT NULL,
    modified_at INTEGER NOT NULL,
    metadata_json TEXT                    -- Additional metadata
);
```

### `encryption_log` Table

```sql
CREATE TABLE encryption_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    operation_type TEXT NOT NULL,         -- 'ENCRYPT', 'DECRYPT', 'DELETE'
    file_id INTEGER,
    file_name TEXT,
    success BOOLEAN NOT NULL,
    error_message TEXT,
    timestamp INTEGER NOT NULL
);
```

---

## 🔄 Sequence Diagrams

### 1. Encrypt Flow

```
User                SecureFilesController    SecureFilesService    AES256GCMEncryptor    Database
 │                           │                       │                     │                │
 │  Drop File                │                       │                     │                │
 ├──────────────────────────>│                       │                     │                │
 │                           │  encryptFileAsync()   │                     │                │
 │                           ├──────────────────────>│                     │                │
 │                           │                       │  generateKey()      │                │
 │                           │                       ├────────────────────>│                │
 │                           │                       │<────────────────────┤                │
 │                           │                       │  (32-byte key)      │                │
 │                           │                       │                     │                │
 │                           │                       │  generateIV()       │                │
 │                           │                       ├────────────────────>│                │
 │                           │                       │<────────────────────┤                │
 │                           │                       │  (12-byte IV)       │                │
 │                           │                       │                     │                │
 │                           │                       │  encryptFile()      │                │
 │                           │                       ├────────────────────>│                │
 │                           │                       │<────────────────────┤                │
 │                           │                       │  (encrypted file)   │                │
 │                           │                       │                     │                │
 │                           │                       │  insertEncryptedFile()              │
 │                           │                       ├────────────────────────────────────>│
 │                           │                       │<────────────────────────────────────┤
 │                           │                       │  (file ID)          │                │
 │                           │<──────────────────────┤                     │                │
 │                           │  EncryptionResult     │                     │                │
 │                           │                       │                     │                │
 │  Show Key Popup           │                       │                     │                │
 │<──────────────────────────┤                       │                     │                │
 │                           │                       │                     │                │
```

### 2. Encrypt + Compress Flow

```
User                SecureFilesService    FileCompressor    AES256GCMEncryptor    Database
 │                           │                   │                   │                │
 │  Drop File (compress=true)│                   │                   │                │
 ├──────────────────────────>│                   │                   │                │
 │                           │  compressZip()    │                   │                │
 │                           ├──────────────────>│                   │                │
 │                           │<──────────────────┤                   │                │
 │                           │  (compressed.zip) │                   │                │
 │                           │                   │                   │                │
 │                           │  encryptFile(compressed.zip)          │                │
 │                           ├──────────────────────────────────────>│                │
 │                           │<──────────────────────────────────────┤                │
 │                           │  (encrypted file) │                   │                │
 │                           │                   │                   │                │
 │                           │  insertEncryptedFile(compressed=true) │                │
 │                           ├───────────────────────────────────────────────────────>│
 │                           │<───────────────────────────────────────────────────────┤
 │                           │                   │                   │                │
```

### 3. Decrypt Flow

```
User                SecureFilesController    SecureFilesService    AES256GCMDecryptor    Database
 │                           │                       │                     │                │
 │  Click Decrypt            │                       │                     │                │
 ├──────────────────────────>│                       │                     │                │
 │                           │  Show Key Dialog      │                     │                │
 │<──────────────────────────┤                       │                     │                │
 │  Enter Key                │                       │                     │                │
 ├──────────────────────────>│                       │                     │                │
 │                           │  decryptFileAsync()   │                     │                │
 │                           ├──────────────────────>│                     │                │
 │                           │                       │  getFileById()      │                │
 │                           │                       ├────────────────────────────────────>│
 │                           │                       │<────────────────────────────────────┤
 │                           │                       │  (file record)      │                │
 │                           │                       │                     │                │
 │                           │                       │  decryptFile()      │                │
 │                           │                       ├────────────────────>│                │
 │                           │                       │<────────────────────┤                │
 │                           │                       │  (decrypted file)   │                │
 │                           │<──────────────────────┤                     │                │
 │                           │  DecryptionResult     │                     │                │
 │                           │                       │                     │                │
 │  Show Success Message     │                       │                     │                │
 │<──────────────────────────┤                       │                     │                │
 │                           │                       │                     │                │
```

### 4. Decrypt + Extract Flow

```
User                SecureFilesService    AES256GCMDecryptor    FileCompressor    Database
 │                           │                     │                   │                │
 │  Decrypt compressed file  │                     │                   │                │
 ├──────────────────────────>│                     │                   │                │
 │                           │  getFileById()      │                   │                │
 │                           ├────────────────────────────────────────────────────────>│
 │                           │<────────────────────────────────────────────────────────┤
 │                           │  (record.compressed=true)               │                │
 │                           │                     │                   │                │
 │                           │  decryptFile()      │                   │                │
 │                           ├────────────────────>│                   │                │
 │                           │<────────────────────┤                   │                │
 │                           │  (decrypted.zip)    │                   │                │
 │                           │                     │                   │                │
 │                           │  decompressZip()    │                   │                │
 │                           ├────────────────────────────────────────>│                │
 │                           │<────────────────────────────────────────┤                │
 │                           │  (original file)    │                   │                │
 │<──────────────────────────┤                     │                   │                │
 │                           │                     │                   │                │
```

### 5. Vault Load Flow

```
User                SecureFilesController    SecureFilesService    Database
 │                           │                       │                │
 │  Open Secure Files        │                       │                │
 ├──────────────────────────>│                       │                │
 │                           │  loadVault()          │                │
 │                           ├──────────────────────>│                │
 │                           │                       │  getAllFiles() │
 │                           │                       ├───────────────>│
 │                           │                       │<───────────────┤
 │                           │                       │  (file list)   │
 │                           │<──────────────────────┤                │
 │                           │                       │                │
 │  Display Files in Grid    │                       │                │
 │<──────────────────────────┤                       │                │
 │                           │                       │                │
```

---

## 🎯 Key Features

### ✅ Security

- **AES-256-GCM**: Industry-standard authenticated encryption
- **SecureRandom**: Cryptographically secure key/IV generation
- **Authentication Tag**: Prevents tampering (integrity check)
- **SHA-256 Hash**: File integrity verification

### ✅ Performance

- **Streaming Encryption**: Handles large files (>10MB) without memory issues
- **Async Operations**: All crypto operations run in background threads
- **Thread Pool**: 4 worker threads for parallel operations
- **No UI Freezing**: JavaFX Application Thread never blocked

### ✅ User Experience

- **Drag & Drop**: Intuitive file selection
- **QR Code**: Easy key sharing via mobile
- **Progress Indicators**: Visual feedback during operations
- **Error Handling**: Clear error messages (wrong key, corrupted file, etc.)

### ✅ Storage

- **SQLite Database**: Metadata tracking
- **Vault Directory**: Organized file storage
- **Compression**: Optional ZIP/GZIP before encryption
- **Metadata JSON**: Extensible file information

---

## 📁 File Structure

```
SafeRoomV2/
├── src/main/java/com/saferoom/securefiles/
│   ├── crypto/
│   │   ├── AES256GCMEncryptor.java
│   │   └── AES256GCMDecryptor.java
│   ├── compression/
│   │   └── FileCompressor.java
│   ├── storage/
│   │   └── SecureFilesDatabase.java
│   ├── service/
│   │   └── SecureFilesService.java
│   └── controller/
│       ├── SecureFilesController.java
│       └── KeyPopupController.java
├── src/main/resources/
│   ├── view/
│   │   ├── SecureFilesView.fxml
│   │   └── KeyPopup.fxml
│   ├── css/
│   │   └── secure-files.css
│   └── sql/
│       └── secure_files_schema.sql
└── docs/
    └── SECURE_FILES_ARCHITECTURE.md
```

---

## 🚀 Usage Example

### Encrypt a File

```java
SecureFilesService service = SecureFilesService.getInstance();

service.encryptFileAsync(Paths.get("/path/to/document.pdf"), true)
    .thenAccept(result -> {
        if (result.success) {
            System.out.println("Key: " + result.keyBase64);
            System.out.println("File: " + result.encryptedFilePath);
        }
    });
```

### Decrypt a File

```java
service.decryptFileAsync(fileId, keyBase64, outputDir)
    .thenAccept(result -> {
        if (result.success) {
            System.out.println("Decrypted: " + result.decryptedFilePath);
        } else {
            System.err.println("Error: " + result.message);
        }
    });
```

---

## 🔒 Security Considerations

### ✅ What's Secure

- **Encryption**: AES-256-GCM is NIST-approved
- **Key Generation**: Uses `SecureRandom` (CSPRNG)
- **Authentication**: GCM mode provides integrity check
- **IV Uniqueness**: New IV generated for each file

### ⚠️ Important Notes

1. **Key Storage**: Keys are NOT stored in database (user responsibility)
2. **Key Loss**: If key is lost, file CANNOT be decrypted
3. **Tamper Detection**: Wrong key or corrupted file → decryption fails
4. **No Key Derivation**: Each file has unique key (no password-based KDF)

---

## 🎨 UI Components

### Secure Files View

- **Drop Zone**: Large drag & drop area
- **Compress Checkbox**: Optional compression toggle
- **Vault Grid**: FlowPane with file cards
- **Search Field**: Filter encrypted files
- **Empty State**: Shown when vault is empty

### Key Popup

- **Key Display**: TextArea with Base64 key
- **Copy Button**: Copy key to clipboard
- **QR Code**: Visual key representation
- **File Info**: Name, size, date
- **Share Button**: (Future: DM integration)

---

## 📈 Future Enhancements

- [ ] **Key Management**: Store keys in OS keychain (macOS Keychain, Windows DPAPI, Linux Secret Service)
- [ ] **DM Integration**: Share encrypted files via SafeRoom DM
- [ ] **Batch Operations**: Encrypt/decrypt multiple files
- [ ] **Password-Based Encryption**: Derive key from user password (PBKDF2)
- [ ] **File Versioning**: Track multiple versions of same file
- [ ] **Zstd Compression**: Better compression ratio than ZIP
- [ ] **Cloud Backup**: Sync vault to cloud storage

---

## 🐛 Error Handling

| Error | Cause | Solution |
|-------|-------|----------|
| **Wrong Key** | Decryption key doesn't match | Re-enter correct key |
| **Corrupted File** | File modified after encryption | Re-encrypt original file |
| **File Not Found** | Encrypted file deleted from disk | Restore from backup |
| **Database Error** | SQLite connection failed | Check file permissions |
| **Out of Memory** | File too large for in-memory encryption | Use streaming mode (automatic for >10MB) |

---

## 📞 Support

For issues or questions:
- Check logs in `~/.saferoom/secure_files/`
- Review database: `sqlite3 ~/.saferoom/secure_files/secure_files.db`
- Enable debug logging: `System.setProperty("saferoom.debug", "true")`

---

**Built with ❤️ for SafeRoom**

