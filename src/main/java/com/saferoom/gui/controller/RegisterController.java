package com.saferoom.gui.controller;

import com.jfoenix.controls.JFXButton;
import com.saferoom.client.ClientMenu;
import com.saferoom.gui.utils.AlertUtils;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

public class RegisterController {

    @FXML private VBox rootPane;
    @FXML private TextField usernameField;
    @FXML private TextField emailField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private Button createAccountButton;
    @FXML private JFXButton googleSignUpButton;
    @FXML private JFXButton githubSignUpButton;
    @FXML private Hyperlink signInLink;
    @FXML private Button closeButton;

    private double xOffset = 0;
    private double yOffset = 0;

    private boolean containsSqlInjection(String input) {
        // SQL injection pattern - daha hızlı regex kullanımı
        String sqlInjectionPattern = "(?i).*(SELECT|INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|EXEC|EXECUTE|UNION|OR\\s+\\d|AND\\s+\\d|--|/\\*|\\*/|xp_|sp_|'|\"|;|<|>|SCRIPT|IFRAME|ONLOAD).*";
        return input.matches(sqlInjectionPattern);
    }

    private boolean isValidInput(String input) {
        return input.matches("^[a-zA-Z0-9._@-]+$");
    }

    private boolean isValidEmail(String email) {
        String emailPattern = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
        return email.matches(emailPattern);
    }

    private void logSecurityIncident(String attemptedInput, String fieldName) {
        System.err.println("SECURITY ALERT: SQL Injection attempt detected in " + fieldName + "!");
        System.err.println("Input attempt: " + attemptedInput);
        System.err.println("Timestamp: " + java.time.LocalDateTime.now());
        System.err.println("This incident has been logged and will be reported.");
    }

    @FXML
    public void initialize() {
        createAccountButton.setOnAction(event -> handleCreateAccount());
        googleSignUpButton.setOnAction(event -> handleGoogleSignUp());
        githubSignUpButton.setOnAction(event -> handleGitHubSignUp());
        signInLink.setOnAction(event -> handleSignInLink());
        closeButton.setOnAction(event -> handleClose());
        
        // Real-time password validation
        confirmPasswordField.textProperty().addListener((observable, oldValue, newValue) -> {
            validatePasswordMatch();
        });
        
        passwordField.textProperty().addListener((observable, oldValue, newValue) -> {
            validatePasswordMatch();
        });
    }
    
    private void validatePasswordMatch() {
        String password = passwordField.getText();
        String confirmPassword = confirmPasswordField.getText();
        
        // Sadece her iki alan da dolu ise kontrol et
        if (!password.isEmpty() && !confirmPassword.isEmpty()) {
            if (!password.equals(confirmPassword)) {
                // Şifreler uyuşmuyor - kırmızı border
                confirmPasswordField.setStyle("-fx-border-color: red; -fx-border-width: 2px;");
            } else {
                // Şifreler uyuşuyor - yeşil border
                confirmPasswordField.setStyle("-fx-border-color: green; -fx-border-width: 2px;");
            }
        } else {
            // Alanlardan biri boş - normal border
            confirmPasswordField.setStyle("");
        }
    }

    /**
     * "Create Account" butonuna tıklandığında, kayıt penceresini kapatıp
     * mail doğrulama penceresini açar.
     */
    private void handleCreateAccount() {
        String candicate_username = usernameField.getText().trim();
        String candicate_email = emailField.getText().trim();
        String password = passwordField.getText().trim();
        String confirm_password = confirmPasswordField.getText().trim();

        java.util.List<String> emptyFields = new java.util.ArrayList<>();
        
        if(candicate_username.isEmpty()) {
            emptyFields.add("Username");
        }
        if(candicate_email.isEmpty()) {
            emptyFields.add("Email");
        }
        if(password.isEmpty()) {
            emptyFields.add("Password");
        }
        if(confirm_password.isEmpty()) {
            emptyFields.add("Confirm Password");
        }

        if (!emptyFields.isEmpty()) {
            if (emptyFields.size() == 1) {
                String field = emptyFields.get(0);
                switch (field) {
                    case "Username":
                        showAlert("Username is Empty", "Username Cannot be Empty");
                        break;
                    case "Email":
                        showAlert("Email is Empty", "Email Cannot be Empty");
                        break;
                    case "Password":
                        showAlert("Choose a Password", "I Guess you'll need a Password");
                        break;
                    case "Confirm Password":
                        showAlert("Confirm your Password", "Enter your Password again just to be sure!");
                        break;
                }
            } else if (emptyFields.size() == 2) {
                showAlert("Multiple Fields Empty", 
                    "Please fill in these fields: " + String.join(" and ", emptyFields));
            } else if (emptyFields.size() == 3) {
                showAlert("Almost Everything is Empty", 
                    "You need to fill in: " + String.join(", ", emptyFields));
            } else {
                showAlert("All Fields Empty", "Please fill in all the required information to create your account!");
            }
            return;
        }

        if (containsSqlInjection(candicate_username)) {
            showAlert("Security Alert", "Suspicious input detected in username. This will be reported!");
            logSecurityIncident(candicate_username, "username");
            return;
        }

        if (containsSqlInjection(candicate_email)) {
            showAlert("Security Alert", "Suspicious input detected in email. This will be reported!");
            logSecurityIncident(candicate_email, "email");
            return;
        }

        if (containsSqlInjection(password)) {
            showAlert("Security Alert", "Suspicious input detected in password. This will be reported!");
            logSecurityIncident("***HIDDEN***", "password");
            return;
        }

        if (!isValidInput(candicate_username)) {
            showAlert("Invalid Username", "Username can only contain letters, numbers, dots, underscores and hyphens!");
            return;
        }

        if (!isValidEmail(candicate_email)) {
            showAlert("Invalid Email", "Please enter a valid email address!");
            return;
        }

        if (candicate_username.length() < 3 || candicate_username.length() > 20) {
            showAlert("Invalid Username Length", "Username must be between 3-20 characters!");
            return;
        }

        if (password.length() < 6 || password.length() > 50) {
            showAlert("Invalid Password Length", "Password must be between 6-50 characters!");
            return;
        }

        if (!password.equals(confirm_password)) {
            showAlert("Password Mismatch", "The passwords you entered do not match!\nPlease make sure both password fields contain the same password.");
            // Password alanlarını temizle ve focus yap
            passwordField.clear();
            confirmPasswordField.clear();
            passwordField.requestFocus();
            return;
        }

        // Sunucuya kayıt isteği gönder
        try {
            System.out.println("Attempting to register user: " + candicate_username);
            int registrationResult = ClientMenu.register_client(candicate_username, password, candicate_email);
            
            switch (registrationResult) {
                case 0:
                    // Başarılı kayıt - email doğrulama ekranına geç
                    System.out.println("Registration successful - proceeding to verification...");
                    openVerificationScreen();
                    break;
                case 1:
                    showAlert("Username Taken", "This username is already taken. Please choose another one.");
                    break;
                case 2:
                    showAlert("Email Already Used", "This email address is already registered. Please use a different email.");
                    break;
                case 3:
                    showAlert("Registration Failed", "An error occurred during registration. Please try again.");
                    break;
                default:
                    showAlert("Unknown Error", "An unexpected error occurred. Please try again later.");
                    break;
            }
        } catch (io.grpc.StatusRuntimeException e) {
            System.err.println("gRPC Connection Error during registration: " + e.getMessage());
            if (e.getStatus().getCode() == io.grpc.Status.Code.CANCELLED) {
                showAlert("Server Connection Failed", "Cannot connect to server. Please check if the server is running.");
            } else if (e.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE) {
                showAlert("Server Unavailable", "Server is currently unavailable. Please try again later.");
            } else {
                showAlert("Connection Error", "Connection error: " + e.getStatus().getDescription());
            }
        } catch (Exception e) {
            System.err.println("Unexpected error during registration: " + e.getMessage());
            e.printStackTrace();
            showAlert("Registration Error", "An unexpected error occurred. Please try again.");
        }
    }

    private void openVerificationScreen() {
        try {
            // Mevcut kayıt penceresini kapat
            Stage currentStage = (Stage) rootPane.getScene().getWindow();
            currentStage.close();

            // Yeni bir doğrulama penceresi aç
            Stage verifyStage = new Stage();
            verifyStage.initStyle(StageStyle.TRANSPARENT);
            Parent root = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("/view/VerifyEmailView.fxml")));

            // Sürükleme özelliği
            root.setOnMousePressed(event -> {
                xOffset = event.getSceneX();
                yOffset = event.getSceneY();
            });
            root.setOnMouseDragged(event -> {
                verifyStage.setX(event.getScreenX() - xOffset);
                verifyStage.setY(event.getScreenY() - yOffset);
            });

            Scene scene = new Scene(root);
            String cssPath = "/styles/styles.css";
            URL cssUrl = getClass().getResource(cssPath);
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            }

            verifyStage.setScene(scene);
            verifyStage.setResizable(false);
            verifyStage.show();

        } catch (IOException e) {
            e.printStackTrace();
            showAlert("Error", "Failed to load verification screen.");
        }
    }


    private void handleGoogleSignUp() {
        showAlert("Google ile Kayıt", "Bu özellik yakında eklenecektir.");
    }

    private void handleGitHubSignUp() {
        showAlert("GitHub ile Kayıt", "Bu özellik yakında eklenecektir.");
    }

    private void handleSignInLink() {
        System.out.println("Giriş ekranına dönülüyor...");
        try {
            Stage currentStage = (Stage) rootPane.getScene().getWindow();
            currentStage.close();
            Stage loginStage = new Stage();
            loginStage.initStyle(StageStyle.TRANSPARENT);
            Parent root = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("/view/LoginView.fxml")));
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
        }
    }

    private void handleClose() {
        Platform.exit();
    }

    private void showAlert(String title, String content) {
        AlertUtils.showInfo(title, content);
    }
}
