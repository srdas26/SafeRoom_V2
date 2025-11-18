package com.saferoom.gui.utils;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window; // <-- YENİ EKLENEN IMPORT
import org.kordamp.ikonli.javafx.FontIcon;

import java.net.URL;

/**
 * Utility class for creating custom styled alert dialogs that match the
 * project's design theme. Uses custom dialog implementation instead of standard
 * JavaFX Alert for better styling control.
 */
public class AlertUtils {

    /**
     * Shows a styled information alert dialog.
     *
     * * @param title The title of the alert
     * @param message The message to display
     */
    public static void showInfo(String title, String message) {
        showCustomAlert(title, message, AlertType.INFO);
    }

    /**
     * Shows a styled error alert dialog.
     *
     * * @param title The title of the alert
     * @param message The error message to display
     */
    public static void showError(String title, String message) {
        showCustomAlert(title, message, AlertType.ERROR);
    }

    /**
     * Shows a styled warning alert dialog.
     *
     * * @param title The title of the alert
     * @param message The warning message to display
     */
    public static void showWarning(String title, String message) {
        showCustomAlert(title, message, AlertType.WARNING);
    }

    /**
     * Shows a styled success alert dialog.
     *
     * * @param title The title of the alert
     * @param message The success message to display
     */
    public static void showSuccess(String title, String message) {
        showCustomAlert(title, message, AlertType.SUCCESS);
    }

    /**
     * Alert types for different styled dialogs
     */
    public enum AlertType {
        INFO("fas-info-circle", "Info"),
        ERROR("fas-exclamation-triangle", "Error"),
        WARNING("fas-exclamation-circle", "Warning"),
        SUCCESS("fas-check-circle", "Success");

        public String iconLiteral;
        public String displayName;

        AlertType(String iconLiteral, String displayName) {
            this.iconLiteral = iconLiteral;
            this.displayName = displayName;
        }
    }

    /**
     * Creates and shows a custom styled alert dialog
     */
    private static void showCustomAlert(String title, String message, AlertType alertType) {
        Stage alertStage = new Stage();

        // --- ÇÖZÜM BAŞLANGICI: SAHİPLİK ATAMA ---
        // Açık olan pencerelerden ana pencereyi (sahibi) bul
        // Bu, tam ekran modunda alert'in kaybolmasını önler.
        Window owner = Window.getWindows().stream()
                .filter(Window::isShowing) // Şu an görünen
                .filter(w -> w instanceof Stage) // Bir Stage olan
                .findFirst() // İlkini al
                .orElse(null);

        if (owner != null) {
            // Alert'i ana pencerenin "çocuğu" yap.
            alertStage.initOwner(owner);
        }
        // --- ÇÖZÜM BİTİŞİ ---

        // WINDOW_MODAL: Sadece sahibini kilitler, tam ekran için daha uygundur.
        alertStage.initModality(Modality.WINDOW_MODAL);
        alertStage.initStyle(StageStyle.TRANSPARENT);
        alertStage.setResizable(false);

        // Main container
        VBox mainContainer = new VBox();
        mainContainer.getStyleClass().add("custom-alert-container");
        mainContainer.setMinWidth(380);
        mainContainer.setMinHeight(180);

        // Header section
        HBox headerSection = new HBox(15);
        headerSection.getStyleClass().addAll("custom-alert-header", "custom-alert-header-" + alertType.name().toLowerCase());
        headerSection.setAlignment(Pos.CENTER_LEFT);
        headerSection.setPadding(new Insets(20, 28, 20, 28));

        // Icon
        FontIcon icon = new FontIcon(alertType.iconLiteral);
        icon.getStyleClass().add("custom-alert-icon");
        icon.setIconSize(24);

        // Title
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("custom-alert-title");

        headerSection.getChildren().addAll(icon, titleLabel);

        // Content section
        VBox contentSection = new VBox();
        contentSection.setPadding(new Insets(0, 28, 24, 28));

        // Message
        Label messageLabel = new Label(message);
        messageLabel.getStyleClass().add("custom-alert-message");
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(Double.MAX_VALUE);

        contentSection.getChildren().add(messageLabel);

        // Button section
        HBox buttonSection = new HBox();
        buttonSection.getStyleClass().add("custom-alert-buttons");
        buttonSection.setAlignment(Pos.CENTER_RIGHT);
        buttonSection.setPadding(new Insets(0, 28, 24, 28));

        Button okButton = new Button("OK");
        okButton.getStyleClass().add("custom-alert-button");
        okButton.setOnAction(e -> alertStage.close());
        okButton.setPrefWidth(80);

        buttonSection.getChildren().add(okButton);

        // Assemble dialog
        mainContainer.getChildren().addAll(headerSection, contentSection, buttonSection);

        // Create scene and apply styling
        Scene scene = new Scene(mainContainer);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);

        // Apply CSS
        String cssPath = "/styles/styles.css";
        URL cssUrl = AlertUtils.class.getResource(cssPath);
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        alertStage.setScene(scene);

        // Center on screen (Owner varsa onun ortasına, yoksa ekran ortasına)
        alertStage.centerOnScreen();

        // Show and wait
        alertStage.showAndWait();
    }
}
