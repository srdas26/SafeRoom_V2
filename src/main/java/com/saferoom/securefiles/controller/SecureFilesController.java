package com.saferoom.securefiles.controller;

import com.saferoom.securefiles.service.SecureFilesService;
import com.saferoom.securefiles.storage.SecureFilesDatabase.SecureFileRecord;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Secure Files Controller
 * 
 * Manages the Secure Files UI:
 * - Drag & drop encryption
 * - Encrypted files vault
 * - File operations (decrypt, delete, share)
 */
public class SecureFilesController {
    
    @FXML private VBox dropZone;
    @FXML private FontIcon dropZoneIcon;
    @FXML private Label dropZoneLabel;
    @FXML private Button browseButton;
    @FXML private CheckBox compressCheckbox;
    
    @FXML private Label vaultStatsLabel;
    @FXML private Button refreshButton;
    @FXML private TextField searchField;
    @FXML private ScrollPane vaultScrollPane;
    @FXML private FlowPane filesGrid;
    @FXML private VBox emptyState;
    
    private SecureFilesService secureFilesService;
    private List<SecureFileRecord> currentFiles;
    
    @FXML
    public void initialize() {
        System.out.println("[SecureFilesController] Initializing...");
        
        try {
            // Initialize service
            String userHome = System.getProperty("user.home");
            String dataDir = userHome + "/.saferoom/secure_files";
            secureFilesService = SecureFilesService.initialize(dataDir);
            
            // Setup UI
            setupDropZone();
            setupSearchFilter();
            
            // Load vault
            loadVault();
            
            System.out.println("[SecureFilesController] ✅ Initialized successfully");
            
        } catch (Exception e) {
            System.err.println("[SecureFilesController] ❌ Initialization failed: " + e.getMessage());
            e.printStackTrace();
            showError("Failed to initialize Secure Files", e.getMessage());
        }
    }
    
    /**
     * Setup drop zone for drag & drop
     */
    private void setupDropZone() {
        // Make drop zone clickable
        dropZone.setOnMouseClicked(e -> handleBrowseFiles());
        
        // Add hover effect
        dropZone.setOnMouseEntered(e -> {
            dropZone.setStyle("-fx-border-color: #22d3ee; -fx-border-width: 2;");
        });
        
        dropZone.setOnMouseExited(e -> {
            dropZone.setStyle("");
        });
    }
    
    /**
     * Setup search filter
     */
    private void setupSearchFilter() {
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            filterFiles(newVal);
        });
    }
    
    /**
     * Handle drag over event
     */
    @FXML
    private void handleDragOver(DragEvent event) {
        if (event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
            
            // Visual feedback
            dropZone.setStyle("-fx-border-color: #22d3ee; -fx-border-width: 3; -fx-background-color: rgba(34, 211, 238, 0.1);");
            dropZoneIcon.setIconColor(Color.web("#22d3ee"));
            dropZoneLabel.setText("Drop to encrypt");
        }
        event.consume();
    }
    
    /**
     * Handle drag exited event
     */
    @FXML
    private void handleDragExited(DragEvent event) {
        dropZone.setStyle("");
        dropZoneIcon.setIconColor(Color.web("#94a3b8"));
        dropZoneLabel.setText("Drag & drop files to encrypt");
        event.consume();
    }
    
    /**
     * Handle drag dropped event
     */
    @FXML
    private void handleDragDropped(DragEvent event) {
        Dragboard db = event.getDragboard();
        boolean success = false;
        
        if (db.hasFiles()) {
            success = true;
            List<File> files = db.getFiles();
            
            for (File file : files) {
                if (file.isFile()) {
                    encryptFile(file.toPath());
                }
            }
        }
        
        // Reset UI
        dropZone.setStyle("");
        dropZoneIcon.setIconColor(Color.web("#94a3b8"));
        dropZoneLabel.setText("Drag & drop files to encrypt");
        
        event.setDropCompleted(success);
        event.consume();
    }
    
    /**
     * Handle browse files button
     */
    @FXML
    private void handleBrowseFiles() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File to Encrypt");
        
        File file = fileChooser.showOpenDialog(browseButton.getScene().getWindow());
        if (file != null) {
            encryptFile(file.toPath());
        }
    }
    
    /**
     * Encrypt a file
     */
    private void encryptFile(Path filePath) {
        System.out.println("[SecureFiles] Encrypting: " + filePath.getFileName());
        
        // Show progress
        showProgress("Encrypting " + filePath.getFileName() + "...");
        
        boolean compress = compressCheckbox.isSelected();
        
        secureFilesService.encryptFileAsync(filePath, compress)
            .thenAccept(result -> {
                Platform.runLater(() -> {
                    hideProgress();
                    
                    if (result.success) {
                        System.out.println("[SecureFiles] ✅ Encryption successful");
                        
                        // Show key popup
                        showKeyPopup(result);
                        
                        // Reload vault
                        loadVault();
                        
                    } else {
                        System.err.println("[SecureFiles] ❌ Encryption failed: " + result.message);
                        showError("Encryption Failed", result.message);
                    }
                });
            })
            .exceptionally(error -> {
                Platform.runLater(() -> {
                    hideProgress();
                    System.err.println("[SecureFiles] ❌ Encryption error: " + error.getMessage());
                    showError("Encryption Error", error.getMessage());
                });
                return null;
            });
    }
    
    /**
     * Show key popup after successful encryption
     */
    private void showKeyPopup(SecureFilesService.EncryptionResult result) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/KeyPopup.fxml"));
            VBox root = loader.load();
            
            KeyPopupController controller = loader.getController();
            controller.setEncryptionResult(result);
            
            Stage stage = new Stage();
            stage.initStyle(StageStyle.UTILITY);
            stage.initModality(Modality.APPLICATION_MODAL);
            stage.setTitle("Encryption Key");
            stage.setScene(new Scene(root));
            stage.setResizable(false);
            
            controller.setStage(stage);
            
            stage.show();
            
        } catch (Exception e) {
            System.err.println("[SecureFiles] Failed to show key popup: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Load vault (all encrypted files)
     */
    @FXML
    private void handleRefreshVault() {
        loadVault();
    }
    
    private void loadVault() {
        System.out.println("[SecureFiles] Loading vault...");
        
        secureFilesService.getAllFilesAsync()
            .thenAccept(files -> {
                Platform.runLater(() -> {
                    currentFiles = files;
                    displayFiles(files);
                    updateVaultStats(files.size());
                });
            })
            .exceptionally(error -> {
                Platform.runLater(() -> {
                    System.err.println("[SecureFiles] Failed to load vault: " + error.getMessage());
                    showError("Failed to Load Vault", error.getMessage());
                });
                return null;
            });
    }
    
    /**
     * Display files in grid
     */
    private void displayFiles(List<SecureFileRecord> files) {
        filesGrid.getChildren().clear();
        
        if (files.isEmpty()) {
            emptyState.setVisible(true);
            emptyState.setManaged(true);
            filesGrid.setVisible(false);
        } else {
            emptyState.setVisible(false);
            emptyState.setManaged(false);
            filesGrid.setVisible(true);
            
            for (SecureFileRecord file : files) {
                VBox fileCard = createFileCard(file);
                filesGrid.getChildren().add(fileCard);
            }
        }
    }
    
    /**
     * Create file card for grid
     */
    private VBox createFileCard(SecureFileRecord file) {
        VBox card = new VBox(10);
        card.getStyleClass().add("file-card");
        card.setPrefSize(200, 220);
        
        // File icon
        FontIcon fileIcon = new FontIcon("fas-file-archive");
        fileIcon.setIconSize(48);
        fileIcon.setIconColor(Color.web("#22d3ee"));
        
        // File name
        Label nameLabel = new Label(file.originalName);
        nameLabel.getStyleClass().add("file-card-name");
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(180);
        
        // File size
        String sizeStr = formatFileSize(file.encryptedSize);
        Label sizeLabel = new Label(sizeStr);
        sizeLabel.getStyleClass().add("file-card-size");
        
        // Date
        String dateStr = formatDate(file.createdAt);
        Label dateLabel = new Label(dateStr);
        dateLabel.getStyleClass().add("file-card-date");
        
        // Actions
        HBox actions = new HBox(5);
        actions.setAlignment(javafx.geometry.Pos.CENTER);
        
        Button decryptBtn = new Button();
        decryptBtn.setGraphic(new FontIcon("fas-unlock"));
        decryptBtn.getStyleClass().add("file-card-action");
        decryptBtn.setTooltip(new Tooltip("Decrypt"));
        decryptBtn.setOnAction(e -> handleDecryptFile(file));
        
        Button shareBtn = new Button();
        shareBtn.setGraphic(new FontIcon("fas-share-alt"));
        shareBtn.getStyleClass().add("file-card-action");
        shareBtn.setTooltip(new Tooltip("Share"));
        shareBtn.setOnAction(e -> handleShareFile(file));
        
        Button deleteBtn = new Button();
        deleteBtn.setGraphic(new FontIcon("fas-trash"));
        deleteBtn.getStyleClass().add("file-card-action-danger");
        deleteBtn.setTooltip(new Tooltip("Delete"));
        deleteBtn.setOnAction(e -> handleDeleteFile(file));
        
        actions.getChildren().addAll(decryptBtn, shareBtn, deleteBtn);
        
        card.getChildren().addAll(fileIcon, nameLabel, sizeLabel, dateLabel, actions);
        card.setAlignment(javafx.geometry.Pos.CENTER);
        
        return card;
    }
    
    /**
     * Handle decrypt file
     */
    private void handleDecryptFile(SecureFileRecord file) {
        // Show key input dialog
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Decrypt File");
        dialog.setHeaderText("Enter Decryption Key");
        dialog.setContentText("Key:");
        
        dialog.showAndWait().ifPresent(key -> {
            if (key != null && !key.trim().isEmpty()) {
                decryptFile(file, key.trim());
            }
        });
    }
    
    /**
     * Decrypt file
     */
    private void decryptFile(SecureFileRecord file, String keyBase64) {
        showProgress("Decrypting " + file.originalName + "...");
        
        // Output to Downloads folder
        String userHome = System.getProperty("user.home");
        Path outputDir = Path.of(userHome, "Downloads");
        
        secureFilesService.decryptFileAsync(file.id, keyBase64, outputDir)
            .thenAccept(result -> {
                Platform.runLater(() -> {
                    hideProgress();
                    
                    if (result.success) {
                        showInfo("Decryption Successful", 
                            "File decrypted to: " + result.decryptedFilePath);
                    } else {
                        showError("Decryption Failed", result.message);
                    }
                });
            })
            .exceptionally(error -> {
                Platform.runLater(() -> {
                    hideProgress();
                    showError("Decryption Error", error.getMessage());
                });
                return null;
            });
    }
    
    /**
     * Handle share file
     */
    private void handleShareFile(SecureFileRecord file) {
        // TODO: Implement share via DM
        showInfo("Share File", "Share functionality coming soon!");
    }
    
    /**
     * Handle delete file
     */
    private void handleDeleteFile(SecureFileRecord file) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete File");
        confirm.setHeaderText("Delete encrypted file?");
        confirm.setContentText("This will permanently delete: " + file.originalName);
        
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                secureFilesService.deleteFileAsync(file.id)
                    .thenAccept(success -> {
                        Platform.runLater(() -> {
                            if (success) {
                                loadVault();
                            } else {
                                showError("Delete Failed", "Could not delete file");
                            }
                        });
                    });
            }
        });
    }
    
    /**
     * Filter files by search term
     */
    private void filterFiles(String searchTerm) {
        if (currentFiles == null) return;
        
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            displayFiles(currentFiles);
        } else {
            String term = searchTerm.toLowerCase();
            List<SecureFileRecord> filtered = currentFiles.stream()
                .filter(f -> f.originalName.toLowerCase().contains(term))
                .toList();
            displayFiles(filtered);
        }
    }
    
    /**
     * Update vault statistics
     */
    private void updateVaultStats(int fileCount) {
        String text = fileCount == 1 ? "1 file encrypted" : fileCount + " files encrypted";
        vaultStatsLabel.setText(text);
    }
    
    /**
     * Format file size
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
    
    /**
     * Format date
     */
    private String formatDate(long timestamp) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(timestamp);
        java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy");
        return dateTime.format(formatter);
    }
    
    /**
     * Show progress indicator
     */
    private void showProgress(String message) {
        // TODO: Implement progress indicator
        System.out.println("[Progress] " + message);
    }
    
    /**
     * Hide progress indicator
     */
    private void hideProgress() {
        // TODO: Hide progress indicator
    }
    
    /**
     * Show error alert
     */
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    /**
     * Show info alert
     */
    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}

