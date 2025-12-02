package com.saferoom.gui.controller;

import com.saferoom.gui.utils.UserSession;
import javafx.animation.*;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Dashboard Controller - Modern ve Sade Tasarım
 * Kullanıcının ana sayfasını ve yaklaşan etkinliklerini yönetir
 */
public class DashboardController {

    // ============================================
    // FXML Components
    // ============================================

    // Action Cards
    @FXML private VBox newMeetingCard;
    @FXML private VBox joinRoomCard;
    @FXML private VBox scheduleRoomCard;
    @FXML private VBox encryptedFilesCard;

    // Top Bar Labels
    @FXML private Label welcomeLabel;
    @FXML private Label profileNameLabel;
    @FXML private Label profileInitials;

    // Events Section
    @FXML private Label currentDateLabel;
    @FXML private ScrollPane eventsScrollPane;
    @FXML private VBox eventsContainer;

    // ============================================
    // Data Models
    // ============================================

    /**
     * Event sınıfı - Yaklaşan etkinlikleri temsil eder
     */
    public static class Event {
        private String title;
        private LocalDateTime dateTime;
        private String type; // "meeting", "scheduled", "file"
        private String description;

        public Event(String title, LocalDateTime dateTime, String type, String description) {
            this.title = title;
            this.dateTime = dateTime;
            this.type = type;
            this.description = description;
        }

        // Getters
        public String getTitle() { return title; }
        public LocalDateTime getDateTime() { return dateTime; }
        public String getType() { return type; }
        public String getDescription() { return description; }
    }

    // ============================================
    // Initialization
    // ============================================

    @FXML
    public void initialize() {
        // Kullanıcı bilgilerini güncelle
        updateWelcomeMessage();
        updateProfileInfo();

        // Mevcut tarihi güncelle
        updateCurrentDate();

        // Action kartlarına click event'leri ekle ve animasyonları ayarla
        setupActionCards();

        // Yaklaşan etkinlikleri yükle
        loadUpcomingEvents();

        // Kartlara giriş animasyonu ekle
        playEntranceAnimations();
    }

    // ============================================
    // UI Update Methods
    // ============================================

    /**
     * Hoşgeldin mesajını kullanıcı adıyla günceller
     */
    private void updateWelcomeMessage() {
        if (welcomeLabel != null) {
            String userName = UserSession.getInstance().getDisplayName();
            if (userName == null || userName.isEmpty()) {
                userName = "User";
            }
            welcomeLabel.setText("Welcome back, " + userName + "!");
        }
    }

    /**
     * Profil bilgilerini günceller (isim ve başharfler)
     */
    private void updateProfileInfo() {
        String userName = UserSession.getInstance().getDisplayName();
        if (userName == null || userName.isEmpty()) {
            userName = "User";
        }

        // Profil adını güncelle
        if (profileNameLabel != null) {
            profileNameLabel.setText(userName);
        }

        // Profil başharflerini güncelle
        if (profileInitials != null) {
            String initials = getInitials(userName);
            profileInitials.setText(initials);
        }
    }

    /**
     * Mevcut tarihi günceller
     */
    private void updateCurrentDate() {
        if (currentDateLabel != null) {
            LocalDate today = LocalDate.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy");
            currentDateLabel.setText(today.format(formatter));
        }
    }

    /**
     * İsimden baş harfleri alır
     * @param name Kullanıcı adı
     * @return Baş harfler (örn: "John Doe" -> "JD")
     */
    private String getInitials(String name) {
        if (name == null || name.isEmpty()) {
            return "U";
        }

        name = name.trim();
        String[] parts = name.split("\\s+");

        if (parts.length >= 2) {
            return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
        } else if (parts.length == 1 && parts[0].length() > 0) {
            return parts[0].substring(0, 1).toUpperCase();
        }

        return "U";
    }

    // ============================================
    // Events Management
    // ============================================

    /**
     * Yaklaşan etkinlikleri yükler
     */
    private void loadUpcomingEvents() {
        // TODO: Backend'den gerçek etkinlikleri çek
        List<Event> events = getUpcomingEventsFromBackend();

        if (eventsContainer != null) {
            eventsContainer.getChildren().clear();

            // StyleClass'ı güncelle
            eventsContainer.getStyleClass().clear();

            if (events.isEmpty()) {
                // Etkinlik yoksa "No Events" kartını göster
                eventsContainer.getStyleClass().add("no-events-card");
                eventsContainer.setAlignment(Pos.CENTER);
                eventsContainer.getChildren().add(createNoEventsContent());
            } else {
                // Etkinlikler varsa normal container olarak ayarla
                eventsContainer.getStyleClass().add("events-container-main");
                eventsContainer.setAlignment(Pos.TOP_LEFT);

                // Etkinlikleri sırala ve kart olarak ekle
                events.sort((e1, e2) -> e1.getDateTime().compareTo(e2.getDateTime()));
                for (Event event : events) {
                    eventsContainer.getChildren().add(createEventCard(event));
                }
            }
        }
    }

    /**
     * Backend'den etkinlikleri çeker (şimdilik örnek veri)
     */
    private List<Event> getUpcomingEventsFromBackend() {
        // TODO: Gerçek backend entegrasyonu
        List<Event> events = new ArrayList<>();

        return events;
    }

    /**
     * Event kartı oluşturur
     */
    private VBox createEventCard(Event event) {
        VBox card = new VBox(8);
        card.getStyleClass().add("event-card");

        // Üst kısım: Icon + Title
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        // Event icon
        FontIcon icon = new FontIcon(getIconForEventType(event.getType()));
        icon.getStyleClass().add("event-icon");
        icon.getStyleClass().add("event-icon-" + event.getType());

        // Title
        Label titleLabel = new Label(event.getTitle());
        titleLabel.getStyleClass().add("event-title");
        titleLabel.setWrapText(true);

        header.getChildren().addAll(icon, titleLabel);

        // Tarih ve saat
        HBox timeBox = new HBox(8);
        timeBox.setAlignment(Pos.CENTER_LEFT);

        FontIcon clockIcon = new FontIcon("fas-clock");
        clockIcon.getStyleClass().add("event-time-icon");

        Label timeLabel = new Label(formatEventTime(event.getDateTime()));
        timeLabel.getStyleClass().add("event-time");

        timeBox.getChildren().addAll(clockIcon, timeLabel);

        // Açıklama
        Label descLabel = new Label(event.getDescription());
        descLabel.getStyleClass().add("event-description");
        descLabel.setWrapText(true);

        card.getChildren().addAll(header, timeBox, descLabel);

        // Hover animasyonu ekle
        setupEventCardAnimation(card);

        return card;
    }

    /**
     * "No Events" kartının içeriğini oluşturur (sadece içerik, container değil)
     */
    private VBox createNoEventsContent() {
        VBox content = new VBox(12);
        content.setAlignment(Pos.CENTER);

        // Icon
        FontIcon icon = new FontIcon("fas-calendar-times");
        icon.getStyleClass().add("no-events-icon");

        // Mesaj
        Label messageLabel = new Label("No Upcoming Events");
        messageLabel.getStyleClass().add("no-events-title");

        Label subLabel = new Label("You're all caught up!");
        subLabel.getStyleClass().add("no-events-subtitle");

        content.getChildren().addAll(icon, messageLabel, subLabel);

        return content;
    }

    /**
     * Event tipi için icon döndürür
     */
    private String getIconForEventType(String type) {
        return switch (type) {
            case "meeting" -> "fas-video";
            case "scheduled" -> "far-clock";
            case "file" -> "fas-file-alt";
            default -> "fas-calendar-check";
        };
    }

    /**
     * Event zamanını formatlar
     */
    private String formatEventTime(LocalDateTime dateTime) {
        LocalDateTime now = LocalDateTime.now();

        // Bugünse
        if (dateTime.toLocalDate().equals(now.toLocalDate())) {
            long hoursUntil = java.time.Duration.between(now, dateTime).toHours();
            if (hoursUntil < 1) {
                long minutesUntil = java.time.Duration.between(now, dateTime).toMinutes();
                return "In " + minutesUntil + " minutes";
            } else if (hoursUntil < 24) {
                return "Today at " + dateTime.format(DateTimeFormatter.ofPattern("HH:mm"));
            }
        }

        // Yarınsa
        if (dateTime.toLocalDate().equals(now.toLocalDate().plusDays(1))) {
            return "Tomorrow at " + dateTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        }

        // Diğer günler
        return dateTime.format(DateTimeFormatter.ofPattern("MMM dd, HH:mm"));
    }

    /**
     * Event kartına hover animasyonu ekler
     */
    private void setupEventCardAnimation(VBox card) {
        ScaleTransition scaleUp = new ScaleTransition(Duration.millis(150), card);
        scaleUp.setToX(1.02);
        scaleUp.setToY(1.02);

        ScaleTransition scaleDown = new ScaleTransition(Duration.millis(150), card);
        scaleDown.setToX(1.0);
        scaleDown.setToY(1.0);

        card.setOnMouseEntered(e -> scaleUp.playFromStart());
        card.setOnMouseExited(e -> scaleDown.playFromStart());
    }

    // ============================================
    // Action Cards Setup
    // ============================================

    /**
     * Action kartlarına tıklama ve hover animasyonları ekler
     */
    private void setupActionCards() {
        if (newMeetingCard != null) {
            setupCardHoverAnimation(newMeetingCard);
            newMeetingCard.setOnMouseClicked(event -> {
                playClickAnimation(newMeetingCard);
                handleNewMeeting();
            });
        }

        if (joinRoomCard != null) {
            setupCardHoverAnimation(joinRoomCard);
            joinRoomCard.setOnMouseClicked(event -> {
                playClickAnimation(joinRoomCard);
                handleJoinMeet();
            });
        }

        if (scheduleRoomCard != null) {
            setupCardHoverAnimation(scheduleRoomCard);
            scheduleRoomCard.setOnMouseClicked(event -> {
                playClickAnimation(scheduleRoomCard);
                handleScheduleMeeting();
            });
        }

        if (encryptedFilesCard != null) {
            setupCardHoverAnimation(encryptedFilesCard);
            encryptedFilesCard.setOnMouseClicked(event -> {
                playClickAnimation(encryptedFilesCard);
                handleEncryptedFiles();
            });
        }
    }

    // ============================================
    // Animation Methods
    // ============================================

    private void setupCardHoverAnimation(VBox card) {
        // PERFORMANCE FIX: Removed DropShadow (GPU expensive)
        // Using CSS :hover pseudo-class instead for border glow effect
        // ScaleTransition is kept but simplified
        
        ScaleTransition scaleUp = new ScaleTransition(Duration.millis(150), card);
        scaleUp.setToX(1.02);
        scaleUp.setToY(1.02);
        scaleUp.setInterpolator(javafx.animation.Interpolator.EASE_OUT);

        ScaleTransition scaleDown = new ScaleTransition(Duration.millis(150), card);
        scaleDown.setToX(1.0);
        scaleDown.setToY(1.0);
        scaleDown.setInterpolator(javafx.animation.Interpolator.EASE_IN);

        card.setOnMouseEntered(event -> {
            // No setEffect() - let CSS handle visual changes
            scaleUp.playFromStart();
        });

        card.setOnMouseExited(event -> {
            scaleDown.playFromStart();
        });
    }

    private void playClickAnimation(VBox card) {
        ScaleTransition shrink = new ScaleTransition(Duration.millis(100), card);
        shrink.setToX(0.95);
        shrink.setToY(0.95);

        ScaleTransition grow = new ScaleTransition(Duration.millis(100), card);
        grow.setToX(1.03);
        grow.setToY(1.03);

        SequentialTransition sequence = new SequentialTransition(shrink, grow);
        sequence.play();
    }

    private void playEntranceAnimations() {
        VBox[] cards = {newMeetingCard, joinRoomCard, scheduleRoomCard, encryptedFilesCard};

        for (int i = 0; i < cards.length; i++) {
            if (cards[i] == null) continue;

            VBox card = cards[i];
            card.setOpacity(0);
            card.setTranslateY(20);

            FadeTransition fadeIn = new FadeTransition(Duration.millis(400), card);
            fadeIn.setToValue(1);
            fadeIn.setDelay(Duration.millis(i * 100));

            TranslateTransition slideUp = new TranslateTransition(Duration.millis(400), card);
            slideUp.setToY(0);
            slideUp.setDelay(Duration.millis(i * 100));

            ParallelTransition parallel = new ParallelTransition(fadeIn, slideUp);
            parallel.play();
        }
    }

    // ============================================
    // Navigation Methods
    // ============================================

    private void handleNewMeeting() {
        System.out.println("New Meeting clicked");
        if (MainController.getInstance() != null) {
            MainController.getInstance().loadSecureRoomView();
        } else {
            System.err.println("MainController instance is null.");
        }
    }

    private void handleJoinMeet() {
        System.out.println("Join Meeting clicked");
        if (MainController.getInstance() != null) {
            MainController.getInstance().loadJoinMeetView();
        } else {
            System.err.println("MainController instance is null.");
        }
    }

    private void handleScheduleMeeting() {
        System.out.println("Schedule Meeting clicked");
    }

    private void handleEncryptedFiles() {
        System.out.println("Encrypted Files clicked");
        if (MainController.getInstance() != null) {
            MainController.getInstance().handleFileVault();
        } else {
            System.err.println("MainController instance is null.");
        }
    }

    // ============================================
    // Public Methods
    // ============================================

    public void refresh() {
        updateWelcomeMessage();
        updateProfileInfo();
        updateCurrentDate();
        loadUpcomingEvents();
    }
}