package com.saferoom.gui.dialog;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Dialog for accepting/rejecting incoming file transfers
 */
public class FileTransferDialog {
    
    private final String senderUsername;
    private final String fileName;
    private final long fileSize;
    private final long fileId;
    
    private Path selectedSavePath;
    
    public FileTransferDialog(String senderUsername, long fileId, String fileName, long fileSize) {
        this.senderUsername = senderUsername;
        this.fileId = fileId;
        this.fileName = fileName;
        this.fileSize = fileSize;
    }
    
    /**
     * Show dialog and return save path if accepted, null if declined
     */
    public Optional<Path> showAndWait() {
        
        // Create custom dialog
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Incoming File Transfer");
        dialog.setHeaderText(null);
        
        // Create dialog content
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setAlignment(Pos.CENTER_LEFT);
        content.setStyle("-fx-min-width: 400px;");
        
        // Sender info
        HBox senderBox = new HBox(10);
        senderBox.setAlignment(Pos.CENTER_LEFT);
        
        FontIcon userIcon = new FontIcon("fas-user");
        userIcon.setIconSize(20);
        userIcon.setStyle("-fx-icon-color: #4a90e2;");
        
        Label senderLabel = new Label(senderUsername + " wants to send you a file:");
        senderLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        
        senderBox.getChildren().addAll(userIcon, senderLabel);
        
        // File info section
        VBox fileInfoBox = new VBox(8);
        fileInfoBox.setPadding(new Insets(10));
        fileInfoBox.setStyle("-fx-background-color: #f5f5f5; -fx-background-radius: 8px;");
        
        // File name with icon
        HBox fileNameBox = new HBox(10);
        fileNameBox.setAlignment(Pos.CENTER_LEFT);
        
        FontIcon fileIcon = getFileIcon(fileName);
        fileIcon.setIconSize(24);
        
        Label fileNameLabel = new Label(fileName);
        fileNameLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        
        fileNameBox.getChildren().addAll(fileIcon, fileNameLabel);
        
        // File size
        HBox fileSizeBox = new HBox(10);
        fileSizeBox.setAlignment(Pos.CENTER_LEFT);
        
        FontIcon sizeIcon = new FontIcon("fas-hdd");
        sizeIcon.setIconSize(16);
        sizeIcon.setStyle("-fx-icon-color: #666;");
        
        Label fileSizeLabel = new Label(formatFileSize(fileSize));
        fileSizeLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        
        fileSizeBox.getChildren().addAll(sizeIcon, fileSizeLabel);
        
        fileInfoBox.getChildren().addAll(fileNameBox, fileSizeBox);
        
        // Warning message
        HBox warningBox = new HBox(10);
        warningBox.setAlignment(Pos.CENTER_LEFT);
        warningBox.setPadding(new Insets(10, 0, 0, 0));
        
        FontIcon warningIcon = new FontIcon("fas-exclamation-triangle");
        warningIcon.setIconSize(16);
        warningIcon.setStyle("-fx-icon-color: #ff9800;");
        
        Label warningLabel = new Label("Accept this file transfer?");
        warningLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        
        warningBox.getChildren().addAll(warningIcon, warningLabel);
        
        // Save location section
        VBox saveLocationBox = new VBox(8);
        saveLocationBox.setPadding(new Insets(10, 0, 0, 0));
        
        Label saveLocationLabel = new Label("Save to:");
        saveLocationLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");
        
        HBox pathSelectionBox = new HBox(10);
        pathSelectionBox.setAlignment(Pos.CENTER_LEFT);
        
        // Default save path
        String defaultPath = System.getProperty("user.home") + "/Downloads";
        TextField savePathField = new TextField(defaultPath);
        savePathField.setEditable(false);
        savePathField.setPrefWidth(300);
        
        Button browseButton = new Button("Browse...");
        browseButton.setOnAction(e -> {
            DirectoryChooser dirChooser = new DirectoryChooser();
            dirChooser.setTitle("Select Save Location");
            dirChooser.setInitialDirectory(new File(savePathField.getText()));
            
            Stage stage = (Stage) dialog.getDialogPane().getScene().getWindow();
            File selectedDir = dirChooser.showDialog(stage);
            
            if (selectedDir != null) {
                savePathField.setText(selectedDir.getAbsolutePath());
            }
        });
        
        pathSelectionBox.getChildren().addAll(savePathField, browseButton);
        saveLocationBox.getChildren().addAll(saveLocationLabel, pathSelectionBox);
        
        // Add all sections to content
        content.getChildren().addAll(senderBox, fileInfoBox, warningBox, saveLocationBox);
        
        dialog.getDialogPane().setContent(content);
        
        // Add buttons
        ButtonType declineButton = new ButtonType("Decline", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType acceptButton = new ButtonType("Accept & Save", ButtonBar.ButtonData.OK_DONE);
        
        dialog.getDialogPane().getButtonTypes().addAll(declineButton, acceptButton);
        
        // Style the dialog
        dialog.getDialogPane().getStylesheets().add(
            getClass().getResource("/styles/styles.css").toExternalForm()
        );
        
        // Style accept button
        Button acceptBtn = (Button) dialog.getDialogPane().lookupButton(acceptButton);
        acceptBtn.setStyle("-fx-background-color: #4a90e2; -fx-text-fill: white; -fx-font-weight: bold;");
        
        // Show dialog and process result
        Optional<ButtonType> result = dialog.showAndWait();
        
        if (result.isPresent() && result.get() == acceptButton) {
            // User accepted - return full save path (directory + filename)
            String saveDir = savePathField.getText();
            selectedSavePath = Paths.get(saveDir, fileName);
            return Optional.of(selectedSavePath);
        }
        
        // User declined or closed dialog
        return Optional.empty();
    }
    
    /**
     * Get appropriate icon based on file extension
     */
    private FontIcon getFileIcon(String fileName) {
        String extension = getFileExtension(fileName).toLowerCase();
        FontIcon icon;
        
        switch (extension) {
            case "pdf":
                icon = new FontIcon("fas-file-pdf");
                icon.setStyle("-fx-icon-color: #e74c3c;");
                break;
            case "doc":
            case "docx":
                icon = new FontIcon("fas-file-word");
                icon.setStyle("-fx-icon-color: #2980b9;");
                break;
            case "xls":
            case "xlsx":
                icon = new FontIcon("fas-file-excel");
                icon.setStyle("-fx-icon-color: #27ae60;");
                break;
            case "ppt":
            case "pptx":
                icon = new FontIcon("fas-file-powerpoint");
                icon.setStyle("-fx-icon-color: #e67e22;");
                break;
            case "zip":
            case "rar":
            case "7z":
            case "tar":
            case "gz":
                icon = new FontIcon("fas-file-archive");
                icon.setStyle("-fx-icon-color: #9b59b6;");
                break;
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "bmp":
                icon = new FontIcon("fas-file-image");
                icon.setStyle("-fx-icon-color: #3498db;");
                break;
            case "mp4":
            case "avi":
            case "mkv":
            case "mov":
                icon = new FontIcon("fas-file-video");
                icon.setStyle("-fx-icon-color: #e74c3c;");
                break;
            case "mp3":
            case "wav":
            case "flac":
                icon = new FontIcon("fas-file-audio");
                icon.setStyle("-fx-icon-color: #1abc9c;");
                break;
            case "txt":
            case "md":
            case "log":
                icon = new FontIcon("fas-file-alt");
                icon.setStyle("-fx-icon-color: #95a5a6;");
                break;
            case "java":
            case "py":
            case "js":
            case "cpp":
            case "c":
                icon = new FontIcon("fas-file-code");
                icon.setStyle("-fx-icon-color: #f39c12;");
                break;
            default:
                icon = new FontIcon("fas-file");
                icon.setStyle("-fx-icon-color: #7f8c8d;");
        }
        
        return icon;
    }
    
    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < fileName.length() - 1) {
            return fileName.substring(dotIndex + 1);
        }
        return "";
    }
    
    /**
     * Format file size in human-readable format
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
}
