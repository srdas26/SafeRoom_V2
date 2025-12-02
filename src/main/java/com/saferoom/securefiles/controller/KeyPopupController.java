package com.saferoom.securefiles.controller;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;
import com.saferoom.securefiles.service.SecureFilesService;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Key Popup Controller
 * 
 * Displays encryption key after successful file encryption
 * Shows QR code for easy key sharing
 */
public class KeyPopupController {
    
    @FXML private TextArea keyTextArea;
    @FXML private Button copyKeyButton;
    @FXML private StackPane qrCodeContainer;
    @FXML private ImageView qrCodeImage;
    @FXML private Label fileNameLabel;
    @FXML private Label fileSizeLabel;
    @FXML private Label dateLabel;
    @FXML private Button shareButton;
    @FXML private Button closeButton;
    
    private Stage stage;
    private SecureFilesService.EncryptionResult encryptionResult;
    
    @FXML
    public void initialize() {
        System.out.println("[KeyPopup] Initializing...");
    }
    
    /**
     * Set encryption result and populate UI
     */
    public void setEncryptionResult(SecureFilesService.EncryptionResult result) {
        this.encryptionResult = result;
        
        // Display key
        keyTextArea.setText(result.keyBase64);
        
        // Generate QR code
        generateQRCode(result.keyBase64);
        
        // Display file info
        fileNameLabel.setText(extractFileName(result.encryptedFilePath));
        fileSizeLabel.setText(formatFileSize(result.encryptedFilePath));
        dateLabel.setText("Just now");
    }
    
    /**
     * Set stage reference
     */
    public void setStage(Stage stage) {
        this.stage = stage;
    }
    
    /**
     * Handle copy key to clipboard
     */
    @FXML
    private void handleCopyKey() {
        String key = keyTextArea.getText();
        
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(key);
        clipboard.setContent(content);
        
        // Visual feedback
        copyKeyButton.setText("✓ Copied");
        copyKeyButton.setDisable(true);
        
        // Reset after 2 seconds
        new Thread(() -> {
            try {
                Thread.sleep(2000);
                javafx.application.Platform.runLater(() -> {
                    copyKeyButton.setText("");
                    copyKeyButton.setDisable(false);
                });
            } catch (InterruptedException e) {
                // Ignore
            }
        }).start();
        
        System.out.println("[KeyPopup] Key copied to clipboard");
    }
    
    /**
     * Handle share encrypted file
     */
    @FXML
    private void handleShareFile() {
        // TODO: Implement share via DM
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Share File");
        alert.setHeaderText("Share via SafeRoom DM");
        alert.setContentText("Share functionality coming soon!\n\nFor now, you can manually send:\n" +
            "1. The encrypted file (.enc)\n" +
            "2. This encryption key");
        alert.showAndWait();
    }
    
    /**
     * Handle close popup
     */
    @FXML
    private void handleClose() {
        if (stage != null) {
            stage.close();
        }
    }
    
    /**
     * Generate QR code for key
     */
    private void generateQRCode(String key) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            
            BitMatrix bitMatrix = qrCodeWriter.encode(key, BarcodeFormat.QR_CODE, 180, 180, hints);
            
            BufferedImage qrImage = new BufferedImage(180, 180, BufferedImage.TYPE_INT_RGB);
            
            for (int x = 0; x < 180; x++) {
                for (int y = 0; y < 180; y++) {
                    qrImage.setRGB(x, y, bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            
            qrCodeImage.setImage(SwingFXUtils.toFXImage(qrImage, null));
            
            System.out.println("[KeyPopup] QR code generated");
            
        } catch (Exception e) {
            System.err.println("[KeyPopup] Failed to generate QR code: " + e.getMessage());
            e.printStackTrace();
            
            // Show error in QR container
            Label errorLabel = new Label("QR generation failed");
            errorLabel.setStyle("-fx-text-fill: #ef4444;");
            qrCodeContainer.getChildren().clear();
            qrCodeContainer.getChildren().add(errorLabel);
        }
    }
    
    /**
     * Extract file name from path
     */
    private String extractFileName(String path) {
        if (path == null) return "unknown";
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) lastSlash = path.lastIndexOf('\\');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
    
    /**
     * Format file size
     */
    private String formatFileSize(String path) {
        try {
            java.nio.file.Path filePath = java.nio.file.Paths.get(path);
            long bytes = java.nio.file.Files.size(filePath);
            
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
            return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        } catch (Exception e) {
            return "Unknown";
        }
    }
}

