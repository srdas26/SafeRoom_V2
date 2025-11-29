package com.saferoom.gui.view.cell;

import com.saferoom.gui.model.FileAttachment;
import com.saferoom.gui.model.Message;
import com.saferoom.gui.model.MessageType;
import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.value.ChangeListener;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

public class MessageCell extends ListCell<Message> {
    private static final double THUMB_SIZE = 140;
    private final HBox hbox = new HBox(10);
    private final Label avatar = new Label();
    private final Pane spacer = new Pane();
    private final String currentUserId;
    private ProgressBar boundProgressBar;
    private Label boundStatusLabel;
    private Message boundMessage;
    private ChangeListener<MessageType> typeListener;
    
    // Highlight support for search
    private boolean isHighlighted = false;

    public MessageCell(String currentUserId) {
        super();
        this.currentUserId = currentUserId;

        avatar.getStyleClass().add("message-avatar");
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        // Add base style class
        getStyleClass().add("message-cell");
    }
    
    /**
     * Set highlight state for search results
     */
    public void setHighlighted(boolean highlighted) {
        this.isHighlighted = highlighted;
        if (highlighted) {
            if (!getStyleClass().contains("message-highlight")) {
                getStyleClass().add("message-highlight");
            }
        } else {
            getStyleClass().remove("message-highlight");
        }
    }
    
    /**
     * Check if cell is highlighted
     */
    public boolean isHighlighted() {
        return isHighlighted;
    }

    @Override
    protected void updateItem(Message message, boolean empty) {
        super.updateItem(message, empty);
        unbindFileBindings();
        detachTypeListener();
        
        // Clear highlight when cell is reused
        getStyleClass().remove("message-highlight");
        isHighlighted = false;
        
        if (empty || message == null || currentUserId == null) {
            boundMessage = null;
            setGraphic(null);
        } else {
            Node bubble = createBubble(message);
            avatar.setText(message.getSenderAvatarChar());

            final boolean isSentByMe = message.getSenderId().equals(currentUserId);

            if (isSentByMe) {
                hbox.getChildren().setAll(spacer, bubble, avatar);
                bubble.getStyleClass().add("message-bubble-sent");
            } else {
                hbox.getChildren().setAll(avatar, bubble, spacer);
                bubble.getStyleClass().add("message-bubble-received");
            }
            if (bubble.getStyleClass().contains("file-message-bubble")) {
                bubble.getStyleClass().add(isSentByMe ? "file-bubble-sent" : "file-bubble-received");
            }
            boundMessage = message;
            attachTypeListener(bubble, message);
            setGraphic(hbox);
            
            // Check if this message should be highlighted (for search results)
            if (isSelected()) {
                // Apply highlight animation for selected items
                applyHighlightAnimation(hbox);
            }
        }
    }
    
    /**
     * Apply yellow flash highlight animation for search results
     */
    private void applyHighlightAnimation(Node node) {
        // Add highlight class
        if (!getStyleClass().contains("message-highlight")) {
            getStyleClass().add("message-highlight");
            isHighlighted = true;
            
            // Create fade animation
            FadeTransition fadeIn = new FadeTransition(Duration.millis(200), node);
            fadeIn.setFromValue(0.7);
            fadeIn.setToValue(1.0);
            fadeIn.play();
        }
    }

    private Node createBubble(Message message) {
        if (message.getType() == MessageType.TEXT) {
            Label textLabel = new Label(message.getText());
            textLabel.setWrapText(true);
            textLabel.getStyleClass().add("message-bubble");
            textLabel.maxWidthProperty().bind(widthProperty().subtract(120));
            return textLabel;
        }
        return createFileBubble(message);
    }

    private Node createFileBubble(Message message) {
        FileAttachment attachment = message.getAttachment();
        VBox container = new VBox(6);
        container.getStyleClass().add("file-message-bubble");

        Node previewNode = buildPreviewNode(message, attachment);
        if (previewNode != null) {
            container.getChildren().add(previewNode);
        }

        HBox metaRow = new HBox(8);
        Label icon = new Label(iconForType(attachment != null ? attachment.getTargetType() : MessageType.FILE));
        icon.getStyleClass().add("file-card-icon");

        Label name = new Label(attachment != null ? attachment.getFileName() : "File");
        name.getStyleClass().add("file-name");
        Label size = new Label(attachment != null ? attachment.getFormattedSize() : "");
        size.getStyleClass().add("file-size");
        VBox metaText = new VBox(2, name, size);
        metaRow.getChildren().addAll(icon, metaText);
        container.getChildren().add(metaRow);

        if (message.getType() == MessageType.FILE_PLACEHOLDER) {
            ProgressBar bar = new ProgressBar();
            bar.setPrefWidth(200);
            bar.progressProperty().bind(message.progressProperty());
            boundProgressBar = bar;
            Label status = new Label();
            status.textProperty().bind(message.statusTextProperty());
            boundStatusLabel = status;
            status.getStyleClass().add("file-status");
            container.getChildren().addAll(bar, status);
        } else {
            Label status = new Label(message.getStatusText().isEmpty() ? "Sent" : message.getStatusText());
            status.getStyleClass().add("file-status");
            container.getChildren().add(status);
            animateFadeIn(container);
        }

        return container;
    }

    private Node buildPreviewNode(Message message, FileAttachment attachment) {
        if (attachment == null) {
            return null;
        }
        if (attachment.getTargetType() == MessageType.IMAGE && attachment.getThumbnail() != null) {
            return buildImagePreview(message, attachment);
        }
        if (attachment.getTargetType() == MessageType.DOCUMENT
            && attachment.getThumbnail() != null
            && isPdfAttachment(attachment)) {
            return buildPdfPreview(message, attachment);
        }

        StackPane placeholder = new StackPane();
        placeholder.getStyleClass().add("file-generic-thumb");
        Label symbol = new Label(iconForType(attachment.getTargetType()));
        symbol.getStyleClass().add("file-card-icon");
        placeholder.getChildren().add(symbol);
        if (message.getType() == MessageType.FILE_PLACEHOLDER) {
            placeholder.getChildren().add(createProgressOverlay(message));
        } else {
            placeholder.getStyleClass().add("interactive-thumb");
            if (attachment.getTargetType() == MessageType.DOCUMENT && isPdfAttachment(attachment)) {
                placeholder.setOnMouseClicked(e -> openPdfModal(attachment));
            } else {
                placeholder.setOnMouseClicked(e -> openGenericFile(attachment));
            }
        }
        return placeholder;
    }

    private StackPane createProgressOverlay(Message message) {
        Rectangle dim = new Rectangle(THUMB_SIZE, THUMB_SIZE, Color.color(0, 0, 0, 0.45));
        Label percent = new Label();
        StringBinding percentText = Bindings.createStringBinding(
            () -> String.format("%.0f%%", message.getProgress() * 100),
            message.progressProperty()
        );
        percent.textProperty().bind(percentText);
        percent.getStyleClass().add("image-overlay-text");
        StackPane overlay = new StackPane(dim, percent);
        overlay.getStyleClass().add("image-overlay");
        return overlay;
    }

    private Node buildImagePreview(Message message, FileAttachment attachment) {
        Image image = attachment.getThumbnail();
        ImageView preview = new ImageView(image);
        preview.setPreserveRatio(true);
        preview.setFitWidth(THUMB_SIZE);
        preview.setFitHeight(THUMB_SIZE);
        preview.setEffect(message.getType() == MessageType.FILE_PLACEHOLDER ? new GaussianBlur(8) : null);
        StackPane thumbWrapper = new StackPane(preview);
        thumbWrapper.getStyleClass().add("file-thumbnail");
        if (message.getType() == MessageType.FILE_PLACEHOLDER) {
            thumbWrapper.getChildren().add(createProgressOverlay(message));
        } else {
            thumbWrapper.getStyleClass().add("interactive-thumb");
            thumbWrapper.setOnMouseClicked(e -> openPreviewModal(attachment));
        }
        return thumbWrapper;
    }

    private Node buildPdfPreview(Message message, FileAttachment attachment) {
        ImageView preview = new ImageView(attachment.getThumbnail());
        preview.setPreserveRatio(true);
        preview.setFitWidth(THUMB_SIZE);
        preview.setFitHeight(THUMB_SIZE);
        StackPane thumbWrapper = new StackPane(preview);
        thumbWrapper.getStyleClass().add("file-thumbnail");
        if (message.getType() == MessageType.FILE_PLACEHOLDER) {
            thumbWrapper.getChildren().add(createProgressOverlay(message));
        } else {
            thumbWrapper.getStyleClass().add("interactive-thumb");
            thumbWrapper.setOnMouseClicked(e -> openPdfModal(attachment));
        }
        return thumbWrapper;
    }

    private boolean isPdfAttachment(FileAttachment attachment) {
        return attachment != null
            && attachment.getFileName() != null
            && attachment.getFileName().toLowerCase().endsWith(".pdf");
    }

    private void animateFadeIn(Node node) {
        node.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(150), node);
        ft.setToValue(1);
        ft.play();
    }

    private void attachTypeListener(Node bubble, Message message) {
        typeListener = (obs, oldType, newType) -> {
            if (newType != oldType && getListView() != null) {
                Platform.runLater(() -> updateItem(message, false));
            }
        };
        message.typeProperty().addListener(typeListener);
    }

    private void detachTypeListener() {
        if (boundMessage != null && typeListener != null) {
            boundMessage.typeProperty().removeListener(typeListener);
            typeListener = null;
        }
    }

    private void unbindFileBindings() {
        if (boundProgressBar != null) {
            boundProgressBar.progressProperty().unbind();
            boundProgressBar = null;
        }
        if (boundStatusLabel != null) {
            boundStatusLabel.textProperty().unbind();
            boundStatusLabel = null;
        }
    }

    private String iconForType(MessageType type) {
        if (type == null) return "\uD83D\uDCCE";
        return switch (type) {
            case IMAGE -> "\uD83D\uDDBC";
            case VIDEO -> "\u25B6";
            case DOCUMENT -> "\uD83D\uDCC4";
            case FILE, FILE_PLACEHOLDER, TEXT -> "\uD83D\uDCCE";
        };
    }

    private void openPreviewModal(FileAttachment attachment) {
        if (attachment == null || attachment.getLocalPath() == null) {
            return;
        }
        Image image = new Image(attachment.getLocalPath().toUri().toString(), 0, 0, true, true, true);
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("Preview");

        ImageView full = new ImageView(image);
        full.setPreserveRatio(true);
        full.setFitWidth(720);
        full.setFitHeight(720);

        BorderPane root = new BorderPane(full);
        root.setStyle("-fx-background-color: rgba(0,0,0,0.85);");

        Scene scene = new Scene(root, 740, 740, Color.TRANSPARENT);
        stage.setScene(scene);

        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                stage.close();
            }
        });
        root.setOnMouseClicked(e -> stage.close());
        stage.show();
    }

    private void openPdfModal(FileAttachment attachment) {
        if (attachment == null || attachment.getLocalPath() == null) {
            return;
        }
        Path path = attachment.getLocalPath();
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(attachment.getFileName());

        VBox pages = new VBox(12);
        pages.setStyle("-fx-padding: 16; -fx-background-color: #0f111a;");

        ScrollPane scrollPane = new ScrollPane(pages);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-border-color: transparent;");

        BorderPane root = new BorderPane(scrollPane);
        Scene scene = new Scene(root, 900, 960);
        stage.setScene(scene);

        Label loading = new Label("Loading PDF…");
        loading.getStyleClass().add("file-status");
        pages.getChildren().add(loading);

        Thread loader = new Thread(() -> {
            try (PDDocument doc = PDDocument.load(path.toFile())) {
                PDFRenderer renderer = new PDFRenderer(doc);
                int pageCount = doc.getNumberOfPages();
                for (int i = 0; i < pageCount; i++) {
                    BufferedImage page = renderer.renderImageWithDPI(i, 150);
                    Image fxImage = SwingFXUtils.toFXImage(page, null);
                    final int idx = i;
                    Platform.runLater(() -> {
                        pages.getChildren().remove(loading);
                        ImageView view = new ImageView(fxImage);
                        view.setPreserveRatio(true);
                        view.setFitWidth(860);
                        view.setSmooth(true);
                        Label pageLabel = new Label("Page " + (idx + 1));
                        pageLabel.getStyleClass().add("file-status");
                        pages.getChildren().add(pageLabel);
                        pages.getChildren().add(view);
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    pages.getChildren().clear();
                    Label error = new Label("Failed to load PDF: " + e.getMessage());
                    error.getStyleClass().add("file-status");
                    pages.getChildren().add(error);
                });
            }
        }, "pdf-render-" + System.nanoTime());
        loader.setDaemon(true);
        loader.start();

        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                stage.close();
            }
        });
        stage.show();
    }

    private void openGenericFile(FileAttachment attachment) {
        if (attachment == null || attachment.getLocalPath() == null) {
            return;
        }
        Path path = attachment.getLocalPath();
        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception e) {
            System.err.println("[MessageCell] Failed to open file: " + e.getMessage());
        }
    }

}