-- Online heartbeat sistemi için gerekli tablolar
USE saferoom;

-- Kullanıcı oturumları tablosu
CREATE TABLE IF NOT EXISTS user_sessions (
    id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL,
    session_id VARCHAR(100) NOT NULL UNIQUE,
    last_heartbeat TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    login_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE,
    client_info VARCHAR(200),
    INDEX idx_username (username),
    INDEX idx_last_heartbeat (last_heartbeat),
    INDEX idx_is_active (is_active),
    FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE
);

-- Aktif olmayan oturumları temizleme (5 dakikadan eski)
CREATE EVENT IF NOT EXISTS cleanup_inactive_sessions
ON SCHEDULE EVERY 1 MINUTE
DO
  DELETE FROM user_sessions 
  WHERE last_heartbeat < DATE_SUB(NOW(), INTERVAL 5 MINUTE);

-- Test verileri - şu anda online olarak göstermek için
INSERT INTO user_sessions (username, session_id, last_heartbeat, client_info) 
VALUES 
('abkarada', CONCAT('session_', UNIX_TIMESTAMP(), '_1'), NOW(), 'SafeRoom Desktop Client'),
('saferoom', CONCAT('session_', UNIX_TIMESTAMP(), '_2'), NOW(), 'SafeRoom Desktop Client')
ON DUPLICATE KEY UPDATE last_heartbeat = NOW();
