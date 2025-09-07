package com.saferoom.gui.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import com.saferoom.gui.utils.AlertUtils;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;
import java.util.regex.Pattern;

public class NewPasswordController {

    @FXML private VBox rootPane;
    @FXML private PasswordField newPasswordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private Label passwordStrengthLabel;
    @FXML private Label passwordMatchLabel;
    @FXML private Button resetPasswordButton;
    @FXML private Hyperlink backToLoginLink;
    
    // Password requirement indicators
    @FXML private FontIcon lengthIcon;
    @FXML private Label lengthLabel;
    @FXML private FontIcon upperIcon;
    @FXML private Label upperLabel;
    @FXML private FontIcon numberIcon;
    @FXML private Label numberLabel;
    @FXML private FontIcon specialIcon;
    @FXML private Label specialLabel;

    // Password validation patterns
    private static final Pattern UPPERCASE_PATTERN = Pattern.compile(".*[A-Z].*");
    private static final Pattern LOWERCASE_PATTERN = Pattern.compile(".*[a-z].*");
    private static final Pattern NUMBER_PATTERN = Pattern.compile(".*[0-9].*");
    private static final Pattern SPECIAL_CHAR_PATTERN = Pattern.compile(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*");
    private static final int MIN_PASSWORD_LENGTH = 8;
    
    private String userEmail;
    
    // Window drag variables
    private double xOffset = 0;
    private double yOffset = 0;

    @FXML
    public void initialize() {
        resetPasswordButton.setOnAction(event -> handleResetPassword());
        backToLoginLink.setOnAction(event -> handleBackToLogin());
        
        // Setup real-time password validation
        newPasswordField.textProperty().addListener((observable, oldValue, newValue) -> {
            validatePassword(newValue);
            checkPasswordMatch();
        });
        
        confirmPasswordField.textProperty().addListener((observable, oldValue, newValue) -> {
            checkPasswordMatch();
        });
        
        // Enter key support
        confirmPasswordField.setOnAction(event -> {
            if (!resetPasswordButton.isDisabled()) {
                handleResetPassword();
            }
        });
    }

    public void setEmail(String email) {
        this.userEmail = email;
    }

    private void validatePassword(String password) {
        boolean hasMinLength = password.length() >= MIN_PASSWORD_LENGTH;
        boolean hasUppercase = UPPERCASE_PATTERN.matcher(password).matches();
        boolean hasNumber = NUMBER_PATTERN.matcher(password).matches();
        boolean hasSpecial = SPECIAL_CHAR_PATTERN.matcher(password).matches();
        
        updateRequirementIndicator(lengthIcon, lengthLabel, hasMinLength);
        updateRequirementIndicator(upperIcon, upperLabel, hasUppercase);
        updateRequirementIndicator(numberIcon, numberLabel, hasNumber);
        updateRequirementIndicator(specialIcon, specialLabel, hasSpecial);
        
        // Calculate password strength
        int strengthScore = 0;
        if (hasMinLength) strengthScore++;
        if (hasUppercase) strengthScore++;
        if (hasNumber) strengthScore++;
        if (hasSpecial) strengthScore++;
        
        updatePasswordStrength(strengthScore, password.length());
        
        // Enable/disable reset button based on all validations
        boolean isPasswordValid = hasMinLength && hasUppercase && hasNumber && hasSpecial;
        boolean isMatchValid = !confirmPasswordField.getText().isEmpty() && 
                              password.equals(confirmPasswordField.getText());
        
        resetPasswordButton.setDisable(!(isPasswordValid && isMatchValid));
    }

    private void updateRequirementIndicator(FontIcon icon, Label label, boolean isMet) {
        if (isMet) {
            icon.setIconLiteral("fas-check-circle");
            icon.setStyle("-fx-icon-color: #22c55e;");
            label.setStyle("-fx-text-fill: #22c55e;");
        } else {
            icon.setIconLiteral("fas-times-circle");
            icon.setStyle("-fx-icon-color: #ef4444;");
            label.setStyle("-fx-text-fill: #94a3b8;");
        }
    }

    private void updatePasswordStrength(int score, int length) {
        passwordStrengthLabel.setVisible(length > 0);
        
        switch (score) {
            case 0:
            case 1:
                passwordStrengthLabel.setText("Password strength: Very Weak");
                passwordStrengthLabel.setStyle("-fx-text-fill: #ef4444;");
                break;
            case 2:
                passwordStrengthLabel.setText("Password strength: Weak");
                passwordStrengthLabel.setStyle("-fx-text-fill: #f59e0b;");
                break;
            case 3:
                passwordStrengthLabel.setText("Password strength: Good");
                passwordStrengthLabel.setStyle("-fx-text-fill: #eab308;");
                break;
            case 4:
                passwordStrengthLabel.setText("Password strength: Strong");
                passwordStrengthLabel.setStyle("-fx-text-fill: #22c55e;");
                break;
        }
    }

    private void checkPasswordMatch() {
        String newPassword = newPasswordField.getText();
        String confirmPassword = confirmPasswordField.getText();
        
        if (confirmPassword.isEmpty()) {
            passwordMatchLabel.setVisible(false);
            return;
        }
        
        boolean isMatch = newPassword.equals(confirmPassword);
        passwordMatchLabel.setVisible(!isMatch);
        
        if (!isMatch) {
            passwordMatchLabel.setText("Passwords do not match");
            passwordMatchLabel.setStyle("-fx-text-fill: #ef4444;");
        }
        
        // Update button state
        boolean isPasswordValid = isPasswordStrong(newPassword);
        resetPasswordButton.setDisable(!(isPasswordValid && isMatch));
    }

    private boolean isPasswordStrong(String password) {
        return password.length() >= MIN_PASSWORD_LENGTH &&
               UPPERCASE_PATTERN.matcher(password).matches() &&
               NUMBER_PATTERN.matcher(password).matches() &&
               SPECIAL_CHAR_PATTERN.matcher(password).matches();
    }

    private void handleResetPassword() {
        String newPassword = newPasswordField.getText();
        String confirmPassword = confirmPasswordField.getText();
        
        // Final validation
        if (!isPasswordStrong(newPassword)) {
            showError("Password does not meet the security requirements.");
            return;
        }
        
        if (!newPassword.equals(confirmPassword)) {
            showError("Passwords do not match.");
            return;
        }
        
        if (userEmail == null || userEmail.isEmpty()) {
            showError("Email information is missing. Please start the process again.");
            return;
        }
        
        // Server integration - update password in database
        try {
            int result = com.saferoom.client.ClientMenu.changePassword(userEmail, newPassword);
            
            switch (result) {
                case 0: // PASSWORD_CHANGED
                    System.out.println("Password reset successfully for: " + userEmail);
                    AlertUtils.showSuccess("Success", 
                        "Your password has been reset successfully! You can now login with your new password.");
                    handleBackToLogin();
                    break;
                    
                case 1: // EMAIL_NOT_FOUND
                    showError("Email address not found. Please contact support.");
                    break;
                    
                case 2: // INVALID_FORMAT, PASSWORD_CHANGE_FAILED, DATABASE_ERROR
                default:
                    showError("Failed to reset password. Please try again or contact support.");
                    break;
            }
            
        } catch (Exception e) {
            System.err.println("Password reset error: " + e.getMessage());
            showError("Connection error occurred. Please check your internet connection and try again.");
        }
    }

    private void handleBackToLogin() {
        System.out.println("Returning to login screen...");
        try {
            Stage currentStage = (Stage) rootPane.getScene().getWindow();
            currentStage.close();

            Stage loginStage = new Stage();
            loginStage.initStyle(StageStyle.TRANSPARENT);
            Parent root = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("/view/LoginView.fxml")));

            // Setup window dragging
            root.setOnMousePressed(event -> {
                xOffset = event.getSceneX();
                yOffset = event.getSceneY();
            });
            root.setOnMouseDragged(event -> {
                loginStage.setX(event.getScreenX() - xOffset);
                loginStage.setY(event.getScreenY() - yOffset);
            });

            Scene scene = new Scene(root);
            scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            String cssPath = "/styles/styles.css";
            URL cssUrl = getClass().getResource(cssPath);
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            }

            loginStage.setScene(scene);
            loginStage.setResizable(false);
            loginStage.show();

        } catch (IOException e) {
            e.printStackTrace();
            showError("Failed to return to login screen.");
        }
    }

    private void showError(String message) {
        AlertUtils.showError("Error", message);
    }
}