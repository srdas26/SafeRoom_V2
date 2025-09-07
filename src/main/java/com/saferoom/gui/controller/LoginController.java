package com.saferoom.gui.controller;

import com.jfoenix.controls.JFXButton;
import com.saferoom.client.ClientMenu;
import com.saferoom.gui.utils.AlertUtils;
import javafx.application.Platform; // Kapatma işlevi için import edildi
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

public class LoginController {

    // --- FXML Değişkenleri ---
    @FXML private VBox rootPane;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private CheckBox rememberMe;
    @FXML private Hyperlink forgotPasswordLink;
    @FXML private Button signInButton;
    @FXML private JFXButton googleLoginButton;
    @FXML private JFXButton githubLoginButton;
    @FXML private Hyperlink signUpLink;
    @FXML private Button closeButton; // YENİ: Kapatma butonu eklendi

    private double xOffset = 0;
    private double yOffset = 0;

    private boolean containsSqlInjection(String input) {
    // SQL injection pattern - daha hızlı regex kullanımı
    String sqlInjectionPattern = "(?i).*(SELECT|INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|EXEC|EXECUTE|UNION|OR\\s+\\d|AND\\s+\\d|--|/\\*|\\*/|xp_|sp_|'|\"|;|<|>|SCRIPT|IFRAME|ONLOAD).*";
    return input.matches(sqlInjectionPattern);
}

private boolean isValidInput(String input) {
    // Username için kısıtlayıcı kontrol
    return input.matches("^[a-zA-Z0-9._-]+$");
}

private boolean isValidPassword(String password) {
    // Password için daha geniş karakter seti (özel karakterler dahil)
    return password.matches("^[a-zA-Z0-9._@#$%^&*()+=\\[\\]{}|\\\\:;\"'<>,.?/~`!-]+$");
}

private void logSecurityIncident(String attemptedUsername) {
    System.err.println("SECURITY ALERT: SQL Injection attempt detected!");
    System.err.println("Username attempt: " + attemptedUsername);
    System.err.println("Timestamp: " + java.time.LocalDateTime.now());
    System.err.println("This incident has been logged and will be reported.");
    
    // Buraya IP loglama, email gönderme vs ekleyebilirsin
}

    @FXML
    public void initialize() {
        signInButton.setOnAction(event -> handleSignIn());
        signUpLink.setOnAction(event -> handleSignUp());
        forgotPasswordLink.setOnAction(event -> handleForgotPassword());
        googleLoginButton.setOnAction(event -> handleGoogleLogin());
        githubLoginButton.setOnAction(event -> handleGitHubLogin());
        passwordField.setOnAction(event -> handleSignIn());
        closeButton.setOnAction(event -> handleClose());
    }

    private void handleSignIn() {
       String username = usernameField.getText().trim();
       String password = passwordField.getText().trim();

         if (username.isEmpty() || password.isEmpty()) {
              showError("Kullanıcı adı ve şifre alanlarını doldurun.");
              return;
         }
         if (containsSqlInjection(username) || containsSqlInjection(password)) {
            showError("Security Alert: Suspicious input detected. This will report on your IP!");
            logSecurityIncident(username); 
        return;
    }
    
        if (username.length() > 50 || password.length() > 100) {
            showError("Username or password too long!");
        return;
        }
    
         if (!isValidInput(username) || !isValidPassword(password)) {
            showError("Invalid characters detected. This will report on your IP!");
        return;
    }

         
       try {
           int loginResult = ClientMenu.Login(username, password);
           switch (loginResult) {
            case 0:
                 try {
                 Stage loginStage = (Stage) rootPane.getScene().getWindow();
                 loginStage.close();
                 Stage mainStage = new Stage();
                  mainStage.initStyle(StageStyle.TRANSPARENT);
                  mainStage.setTitle("SafeRoom");
                 Parent mainRoot = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("/view/MainView.fxml")));
                 Scene mainScene = new Scene(mainRoot, 1280, 800);
                 mainScene.setFill(javafx.scene.paint.Color.TRANSPARENT);
                 String cssPath = "/styles/styles.css";
                 URL cssUrl = getClass().getResource(cssPath);
                    if (cssUrl != null) {
                     mainScene.getStylesheets().add(cssUrl.toExternalForm());
                    }
                mainStage.setScene(mainScene);
                mainStage.setResizable(true);
                mainStage.setMinWidth(1024);
                mainStage.setMinHeight(768);
                mainStage.show();
                } catch (IOException e) {
                    e.printStackTrace();
                    showError("Ana sayfa yüklenemedi.");
                }
                break;
            case 1:
                showError("You are not Registered Yet!Let's make you a member!.");
            break;

            case 2:
                showError("Blocked User, this incident will report.");
            break;

            case 3:
                showError("Wrong Password, Please try again.");
            break;

            default:
                showError("An unknown error occurred. Please try again.");
            break;
       }
       } catch (io.grpc.StatusRuntimeException e) {
           System.err.println("gRPC Connection Error: " + e.getMessage());
           if (e.getStatus().getCode() == io.grpc.Status.Code.CANCELLED) {
               showError("Server connection failed. Please check if the server is running.");
           } else if (e.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE) {
               showError("Server is unavailable. Please try again later.");
           } else {
               showError("Connection error: " + e.getStatus().getDescription());
           }
       } catch (Exception e) {
           System.err.println("Unexpected error during login: " + e.getMessage());
           e.printStackTrace();
           showError("An unexpected error occurred. Please try again.");
       }
   
    }

    private void handleSignUp() {
        System.out.println("Kayıt ekranına geçiş yapılıyor...");
        try {
            Stage currentStage = (Stage) rootPane.getScene().getWindow();
            currentStage.close();

            Stage registerStage = new Stage();
            registerStage.initStyle(StageStyle.TRANSPARENT);
            Parent root = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("/view/RegisterView.fxml")));

            root.setOnMousePressed(event -> {
                xOffset = event.getSceneX();
                yOffset = event.getSceneY();
            });
            root.setOnMouseDragged(event -> {
                registerStage.setX(event.getScreenX() - xOffset);
                registerStage.setY(event.getScreenY() - yOffset);
            });

            Scene scene = new Scene(root);
            scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            String cssPath = "/styles/styles.css";
            URL cssUrl = getClass().getResource(cssPath);
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            }
            registerStage.setScene(scene);
            registerStage.setResizable(false);
            registerStage.show();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * YENİ: Uygulamayı tamamen kapatır.
     */
    private void handleClose() {
        System.out.println("Uygulama kapatılıyor...");
        Platform.exit();
    }

    // ... (Diğer metodlar değişmedi) ...
    private void handleForgotPassword() {
        System.out.println("Navigating to forgot password screen...");
        try {
            Stage currentStage = (Stage) rootPane.getScene().getWindow();
            currentStage.close();

            Stage forgotPasswordStage = new Stage();
            forgotPasswordStage.initStyle(StageStyle.TRANSPARENT);
            Parent root = FXMLLoader.load(Objects.requireNonNull(getClass().getResource("/view/ForgotPasswordView.fxml")));

            root.setOnMousePressed(event -> {
                xOffset = event.getSceneX();
                yOffset = event.getSceneY();
            });
            root.setOnMouseDragged(event -> {
                forgotPasswordStage.setX(event.getScreenX() - xOffset);
                forgotPasswordStage.setY(event.getScreenY() - yOffset);
            });

            Scene scene = new Scene(root);
            scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
            String cssPath = "/styles/styles.css";
            URL cssUrl = getClass().getResource(cssPath);
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            }
            forgotPasswordStage.setScene(scene);
            forgotPasswordStage.setResizable(false);
            forgotPasswordStage.show();
        } catch (IOException e) {
            e.printStackTrace();
            showError("Failed to load forgot password screen.");
        }
    }
    private void handleGoogleLogin() { showAlert("Google ile Giriş", "Bu özellik yakında eklenecektir."); }
    private void handleGitHubLogin() { showAlert("GitHub ile Giriş", "Bu özellik yakında eklenecektir."); }
    private void showAlert(String title, String content) { AlertUtils.showInfo(title, content); }
    private void showError(String message) { AlertUtils.showError("Hata", message); }
}
