package com.saferoom.securefiles.controller;

import com.saferoom.securefiles.service.SecureFilesService;
import com.saferoom.securefiles.storage.SecureFilesDatabase.SecureFileRecord;
import com.saferoom.storage.LocalDatabase;
import com.saferoom.storage.MessageDao;
import com.saferoom.storage.MessageDao.DmFileRecord;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Secure Files Controller
 * 
 * Shows ALL files in SafeRoom:
 * - DM shared files
 * - Meeting shared files
 * - Manually encrypted local files
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
    
    // Category filters
    @FXML private HBox categoryFilterBar;
    @FXML private Button filterAll;
    @FXML private Button filterImages;
    @FXML private Button filterDocuments;
    @FXML private Button filterArchives;
    @FXML private Button filterEncrypted;
    
    private SecureFilesService secureFilesService;
    private MessageDao messageDao;
    
    private List<SecureFileRecord> encryptedFiles = new ArrayList<>();
    private List<DmFileRecord> dmFiles = new ArrayList<>();
    private String currentFilter = "All";
    
    @FXML
    public void initialize() {
        System.out.println("[SecureFiles] Initializing...");
        
        try {
            // Initialize SecureFiles service
            String userHome = System.getProperty("user.home");
            String dataDir = userHome + "/.saferoom/secure_files";
            secureFilesService = SecureFilesService.initialize(dataDir);
            
            // Initialize MessageDao for DM files
            if (LocalDatabase.isInitialized()) {
                messageDao = new MessageDao(LocalDatabase.getInstance());
                System.out.println("[SecureFiles] ✅ MessageDao connected");
            } else {
                System.out.println("[SecureFiles] ⚠️ LocalDatabase not initialized - DM files won't show");
            }
            
            setupDropZone();
            setupFilterButtons();
            setupSearchFilter();
            
            loadAllFiles();
            
            System.out.println("[SecureFiles] ✅ Initialized successfully");
            
        } catch (Exception e) {
            System.err.println("[SecureFiles] ❌ Initialization failed: " + e.getMessage());
            e.printStackTrace();
            showError("Failed to initialize Secure Files", e.getMessage());
        }
    }
    
    /**
     * Setup category filter buttons
     */
    private void setupFilterButtons() {
        if (filterAll != null) {
            filterAll.setOnAction(e -> setFilter("All", filterAll));
            filterAll.getStyleClass().add("active");
        }
        if (filterImages != null) {
            filterImages.setOnAction(e -> setFilter("Images", filterImages));
        }
        if (filterDocuments != null) {
            filterDocuments.setOnAction(e -> setFilter("Documents", filterDocuments));
        }
        if (filterArchives != null) {
            filterArchives.setOnAction(e -> setFilter("Archives", filterArchives));
        }
        if (filterEncrypted != null) {
            filterEncrypted.setOnAction(e -> setFilter("Encrypted", filterEncrypted));
        }
    }
    
    private void setFilter(String filter, Button activeButton) {
        currentFilter = filter;
        
        // Update button styles
        if (filterAll != null) filterAll.getStyleClass().remove("active");
        if (filterImages != null) filterImages.getStyleClass().remove("active");
        if (filterDocuments != null) filterDocuments.getStyleClass().remove("active");
        if (filterArchives != null) filterArchives.getStyleClass().remove("active");
        if (filterEncrypted != null) filterEncrypted.getStyleClass().remove("active");
        
        if (activeButton != null) activeButton.getStyleClass().add("active");
        
        displayFilteredFiles();
    }
    
    /**
     * Setup drop zone
     */
    private void setupDropZone() {
        dropZone.setOnMouseClicked(e -> handleBrowseFiles());
        
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
        if (searchField != null) {
            searchField.textProperty().addListener((obs, oldVal, newVal) -> {
                filterBySearch(newVal);
            });
        }
    }
    
    @FXML
    private void handleDragOver(DragEvent event) {
        if (event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
            dropZone.setStyle("-fx-border-color: #22d3ee; -fx-border-width: 3; -fx-background-color: rgba(34, 211, 238, 0.1);");
            if (dropZoneIcon != null) dropZoneIcon.setIconColor(Color.web("#22d3ee"));
            if (dropZoneLabel != null) dropZoneLabel.setText("Drop to encrypt");
        }
        event.consume();
    }
    
    @FXML
    private void handleDragExited(DragEvent event) {
        dropZone.setStyle("");
        if (dropZoneIcon != null) dropZoneIcon.setIconColor(Color.web("#94a3b8"));
        if (dropZoneLabel != null) dropZoneLabel.setText("Drag & drop files to encrypt");
        event.consume();
    }
    
    @FXML
    private void handleDragDropped(DragEvent event) {
        Dragboard db = event.getDragboard();
        boolean success = false;
        
        if (db.hasFiles()) {
            success = true;
            for (File file : db.getFiles()) {
                if (file.isFile()) {
                    encryptFile(file.toPath());
                }
            }
        }
        
        dropZone.setStyle("");
        if (dropZoneIcon != null) dropZoneIcon.setIconColor(Color.web("#94a3b8"));
        if (dropZoneLabel != null) dropZoneLabel.setText("Drag & drop files to encrypt");
        
        event.setDropCompleted(success);
        event.consume();
    }
    
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
        
        boolean compress = compressCheckbox != null && compressCheckbox.isSelected();
        
        secureFilesService.encryptFileAsync(filePath, compress)
            .thenAccept(result -> {
                Platform.runLater(() -> {
                    if (result.success) {
                        System.out.println("[SecureFiles] ✅ Encryption successful");
                        showKeyPopup(result);
                        loadAllFiles();
                    } else {
                        System.err.println("[SecureFiles] ❌ Encryption failed: " + result.message);
                        showError("Encryption Failed", result.message);
                    }
                });
            })
            .exceptionally(error -> {
                Platform.runLater(() -> {
                    System.err.println("[SecureFiles] ❌ Encryption error: " + error.getMessage());
                    showError("Encryption Error", error.getMessage());
                });
                return null;
            });
    }
    
    /**
     * Show key popup
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
        }
    }
    
    @FXML
    private void handleRefreshVault() {
        loadAllFiles();
    }
    
    /**
     * Load all files from both sources
     */
    private void loadAllFiles() {
        System.out.println("[SecureFiles] Loading all files...");
        
        // Load encrypted files
        secureFilesService.getAllFilesAsync()
            .thenAccept(files -> {
                encryptedFiles = files;
                
                // Also load DM files
                loadDmFiles();
            })
            .exceptionally(error -> {
                Platform.runLater(() -> {
                    System.err.println("[SecureFiles] Failed to load encrypted files: " + error.getMessage());
                });
                return null;
            });
    }
    
    /**
     * Load DM files from MessageDao
     */
    private void loadDmFiles() {
        if (messageDao == null) {
            dmFiles = new ArrayList<>();
            Platform.runLater(this::displayFilteredFiles);
            return;
        }
        
        try {
            dmFiles = messageDao.getAllFileMessages();
            System.out.printf("[SecureFiles] Loaded %d DM files%n", dmFiles.size());
        } catch (Exception e) {
            System.err.println("[SecureFiles] Failed to load DM files: " + e.getMessage());
            dmFiles = new ArrayList<>();
        }
        
        Platform.runLater(this::displayFilteredFiles);
    }
    
    /**
     * Display files based on current filter
     */
    private void displayFilteredFiles() {
        filesGrid.getChildren().clear();
        
        // Get list of already encrypted file names to filter out from DM list
        java.util.Set<String> encryptedOriginalNames = encryptedFiles.stream()
            .map(sf -> sf.originalName)
            .collect(java.util.stream.Collectors.toSet());
        
        // Filter DM files - exclude ones that are already encrypted
        List<DmFileRecord> unencryptedDmFiles = dmFiles.stream()
            .filter(dm -> !encryptedOriginalNames.contains(dm.fileName))
            .toList();
        
        List<Object> filesToShow = new ArrayList<>();
        
        switch (currentFilter) {
            case "Images":
                // Filter DM files by category (excluding encrypted ones)
                unencryptedDmFiles.stream()
                    .filter(dm -> "Images".equals(dm.getCategory()))
                    .forEach(filesToShow::add);
                break;
            case "Documents":
                unencryptedDmFiles.stream()
                    .filter(dm -> "Documents".equals(dm.getCategory()))
                    .forEach(filesToShow::add);
                break;
            case "Archives":
                unencryptedDmFiles.stream()
                    .filter(dm -> "Archives".equals(dm.getCategory()))
                    .forEach(filesToShow::add);
                break;
            case "Encrypted":
                filesToShow.addAll(encryptedFiles);
                break;
            default: // All
                filesToShow.addAll(unencryptedDmFiles);
                filesToShow.addAll(encryptedFiles);
                break;
        }
        
        // Apply search filter
        String searchTerm = searchField != null ? searchField.getText() : "";
        if (searchTerm != null && !searchTerm.isBlank()) {
            String term = searchTerm.toLowerCase();
            filesToShow = filesToShow.stream()
                .filter(f -> {
                    if (f instanceof DmFileRecord dm) {
                        return dm.fileName.toLowerCase().contains(term);
                    } else if (f instanceof SecureFileRecord sf) {
                        return sf.originalName.toLowerCase().contains(term);
                    }
                    return false;
                })
                .toList();
        }
        
        // Update stats with category counts (using unencrypted DM files)
        int totalFiles = unencryptedDmFiles.size() + encryptedFiles.size();
        long imageCount = unencryptedDmFiles.stream().filter(dm -> "Images".equals(dm.getCategory())).count();
        long docCount = unencryptedDmFiles.stream().filter(dm -> "Documents".equals(dm.getCategory())).count();
        long archiveCount = unencryptedDmFiles.stream().filter(dm -> "Archives".equals(dm.getCategory())).count();
        
        updateVaultStats(totalFiles, encryptedFiles.size(), (int) imageCount, (int) docCount, (int) archiveCount);
        
        if (filesToShow.isEmpty()) {
            if (emptyState != null) {
                emptyState.setVisible(true);
                emptyState.setManaged(true);
            }
            if (vaultScrollPane != null) {
                vaultScrollPane.setVisible(false);
                vaultScrollPane.setManaged(false);
            }
        } else {
            if (emptyState != null) {
                emptyState.setVisible(false);
                emptyState.setManaged(false);
            }
            if (vaultScrollPane != null) {
                vaultScrollPane.setVisible(true);
                vaultScrollPane.setManaged(true);
            }
            
            for (Object file : filesToShow) {
                if (file instanceof DmFileRecord dm) {
                    filesGrid.getChildren().add(createDmFileCard(dm));
                } else if (file instanceof SecureFileRecord sf) {
                    filesGrid.getChildren().add(createEncryptedFileCard(sf));
                }
            }
        }
    }
    
    /**
     * Filter by search term
     */
    private void filterBySearch(String searchTerm) {
        displayFilteredFiles();
    }
    
    /**
     * Create card for DM file
     */
    private VBox createDmFileCard(DmFileRecord file) {
        VBox card = new VBox(10);
        card.getStyleClass().add("file-card");
        card.setPrefSize(200, 220);
        card.setAlignment(Pos.CENTER);
        
        // Thumbnail or icon
        if (file.thumbnail != null) {
            ImageView thumbView = new ImageView(file.thumbnail);
            thumbView.setFitWidth(60);
            thumbView.setFitHeight(60);
            thumbView.setPreserveRatio(true);
            card.getChildren().add(thumbView);
        } else {
            FontIcon fileIcon = new FontIcon(getIconForFile(file.fileName, file.messageType));
            fileIcon.setIconSize(48);
            fileIcon.setIconColor(Color.web("#60a5fa"));
            card.getChildren().add(fileIcon);
        }
        
        // Source badge (DM)
        Label sourceBadge = new Label("DM • " + file.senderId);
        sourceBadge.getStyleClass().add("source-badge-dm");
        sourceBadge.setStyle("-fx-font-size: 10px; -fx-text-fill: #94a3b8;");
        card.getChildren().add(sourceBadge);
        
        // File name
        Label nameLabel = new Label(file.fileName);
        nameLabel.getStyleClass().add("file-card-name");
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(180);
        card.getChildren().add(nameLabel);
        
        // Size and date
        Label sizeLabel = new Label(file.getFormattedSize());
        sizeLabel.getStyleClass().add("file-card-size");
        card.getChildren().add(sizeLabel);
        
        Label dateLabel = new Label(file.getFormattedDate());
        dateLabel.getStyleClass().add("file-card-date");
        card.getChildren().add(dateLabel);
        
        // Actions
        HBox actions = new HBox(5);
        actions.setAlignment(Pos.CENTER);
        
        Button openBtn = new Button();
        openBtn.setGraphic(new FontIcon("fas-external-link-alt"));
        openBtn.getStyleClass().add("file-card-action");
        openBtn.setTooltip(new Tooltip("Open"));
        openBtn.setOnAction(e -> openDmFile(file));
        
        Button encryptBtn = new Button();
        encryptBtn.setGraphic(new FontIcon("fas-lock"));
        encryptBtn.getStyleClass().add("file-card-action");
        encryptBtn.setTooltip(new Tooltip("Encrypt"));
        encryptBtn.setOnAction(e -> {
            if (file.filePath != null) {
                encryptFile(Path.of(file.filePath));
            }
        });
        
        actions.getChildren().addAll(openBtn, encryptBtn);
        card.getChildren().add(actions);
        
        return card;
    }
    
    /**
     * Create card for encrypted file
     */
    private VBox createEncryptedFileCard(SecureFileRecord file) {
        VBox card = new VBox(10);
        card.getStyleClass().add("file-card");
        card.setPrefSize(200, 220);
        card.setAlignment(Pos.CENTER);
        
        // Lock icon (encrypted)
        FontIcon fileIcon = new FontIcon("fas-file-archive");
        fileIcon.setIconSize(48);
        fileIcon.setIconColor(Color.web("#22d3ee"));
        card.getChildren().add(fileIcon);
        
        // Source badge (Encrypted)
        Label sourceBadge = new Label("🔒 Encrypted");
        sourceBadge.setStyle("-fx-font-size: 10px; -fx-text-fill: #22d3ee;");
        card.getChildren().add(sourceBadge);
        
        // File name
        Label nameLabel = new Label(file.originalName);
        nameLabel.getStyleClass().add("file-card-name");
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(180);
        card.getChildren().add(nameLabel);
        
        // Size
        Label sizeLabel = new Label(formatFileSize(file.encryptedSize));
        sizeLabel.getStyleClass().add("file-card-size");
        card.getChildren().add(sizeLabel);
        
        // Date
        Label dateLabel = new Label(formatDate(file.createdAt));
        dateLabel.getStyleClass().add("file-card-date");
        card.getChildren().add(dateLabel);
        
        // Actions
        HBox actions = new HBox(5);
        actions.setAlignment(Pos.CENTER);
        
        Button decryptBtn = new Button();
        decryptBtn.setGraphic(new FontIcon("fas-unlock"));
        decryptBtn.getStyleClass().add("file-card-action");
        decryptBtn.setTooltip(new Tooltip("Decrypt"));
        decryptBtn.setOnAction(e -> handleDecryptFile(file));
        
        Button shareBtn = new Button();
        shareBtn.setGraphic(new FontIcon("fas-share-alt"));
        shareBtn.getStyleClass().add("file-card-action");
        shareBtn.setTooltip(new Tooltip("Share"));
        
        Button deleteBtn = new Button();
        deleteBtn.setGraphic(new FontIcon("fas-trash"));
        deleteBtn.getStyleClass().add("file-card-action-danger");
        deleteBtn.setTooltip(new Tooltip("Delete"));
        deleteBtn.setOnAction(e -> handleDeleteFile(file));
        
        actions.getChildren().addAll(decryptBtn, shareBtn, deleteBtn);
        card.getChildren().add(actions);
        
        return card;
    }
    
    /**
     * Open DM file
     */
    private void openDmFile(DmFileRecord file) {
        if (file.filePath == null) {
            showError("File Not Found", "File path is not available");
            return;
        }
        
        try {
            Path path = Path.of(file.filePath);
            if (Files.exists(path)) {
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("linux")) {
                    pb = new ProcessBuilder("xdg-open", path.toString());
                } else if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", path.toString());
                } else {
                    pb = new ProcessBuilder("cmd", "/c", "start", "", path.toString());
                }
                pb.start();
            } else {
                showError("File Not Found", "File no longer exists at: " + path);
            }
        } catch (Exception e) {
            showError("Error", "Could not open file: " + e.getMessage());
        }
    }
    
    /**
     * Get icon for file type
     */
    private String getIconForFile(String fileName, String messageType) {
        if (fileName == null) return "fas-file";
        
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")) {
            return "fas-file-image";
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv")) {
            return "fas-file-video";
        }
        if (lower.endsWith(".pdf")) {
            return "fas-file-pdf";
        }
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) {
            return "fas-file-word";
        }
        if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")) {
            return "fas-file-archive";
        }
        
        return "fas-file-alt";
    }
    
    private void handleDecryptFile(SecureFileRecord file) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Decrypt File");
        dialog.setHeaderText("Enter Decryption Key");
        dialog.setContentText("Key:");
        
        dialog.showAndWait().ifPresent(key -> {
            if (key != null && !key.trim().isEmpty()) {
                String userHome = System.getProperty("user.home");
                Path outputDir = Path.of(userHome, "Downloads");
                
                secureFilesService.decryptFileAsync(file.id, key.trim(), outputDir)
                    .thenAccept(result -> {
                        Platform.runLater(() -> {
                            if (result.success) {
                                // 1. Delete encrypted file from vault (DB + disk)
                                secureFilesService.deleteFileAsync(file.id)
                                    .thenAccept(deleted -> {
                                        Platform.runLater(() -> {
                                            if (deleted) {
                                                System.out.println("[SecureFiles] ✅ Encrypted file removed from vault after decrypt");
                                            }
                                            // 2. Refresh the file list
                                            loadAllFiles();
                                        });
                                    });
                                
                                // 3. Open the decrypted file automatically
                                openDecryptedFile(result.decryptedFilePath);
                                
                                showInfo("Decryption Successful", "File decrypted and opened:\n" + result.decryptedFilePath);
                            } else {
                                showError("Decryption Failed", result.message);
                            }
                        });
                    });
            }
        });
    }
    
    /**
     * Open a decrypted file with system default application
     */
    private void openDecryptedFile(String filePath) {
        if (filePath == null) return;
        
        try {
            Path path = Path.of(filePath);
            if (Files.exists(path)) {
                String os = System.getProperty("os.name").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("linux")) {
                    pb = new ProcessBuilder("xdg-open", path.toString());
                } else if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", path.toString());
                } else {
                    pb = new ProcessBuilder("cmd", "/c", "start", "", path.toString());
                }
                pb.start();
                System.out.println("[SecureFiles] 📂 Opened decrypted file: " + path.getFileName());
            }
        } catch (Exception e) {
            System.err.println("[SecureFiles] Failed to open decrypted file: " + e.getMessage());
        }
    }
    
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
                                loadAllFiles();
                            } else {
                                showError("Delete Failed", "Could not delete file");
                            }
                        });
                    });
            }
        });
    }
    
    private void updateVaultStats(int totalFiles, int encryptedCount, int imageCount, int docCount, int archiveCount) {
        if (vaultStatsLabel != null) {
            String text = String.format("%d files • %d 🖼 • %d 📄 • %d 📦 • %d 🔒", 
                totalFiles, imageCount, docCount, archiveCount, encryptedCount);
            vaultStatsLabel.setText(text);
        }
    }
    
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
    
    private String formatDate(long timestamp) {
        java.time.Instant instant = java.time.Instant.ofEpochMilli(timestamp);
        return instant.atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy"));
    }
    
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
