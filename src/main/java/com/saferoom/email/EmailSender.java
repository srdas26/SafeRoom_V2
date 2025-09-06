package com.saferoom.email;

import javax.mail.*;
import javax.mail.internet.*;
import java.io.*;
import java.util.Properties;

import com.saferoom.db.DBManager;
import com.saferoom.log.Logger;

public class EmailSender {

    public static final String CONFIG_FILE = "src/main/resources/emailconfig.properties";
    public static final String ICON_RESOURCE_NAME = "Verificate.png";
    public static String HOST;
    public static String PORT;
    public static String USERNAME;
    public static String PASSWORD;

    

    public static Logger LOGGER = Logger.getLogger(EmailSender.class);

    static {
        try {
            loadEmailConfig();
        } catch (Exception e) {
            try {
                LOGGER.error("Email Config loading failed: " + e.getMessage());
                throw new RuntimeException("Email Config File Unreadable", e);
            } catch (Exception e1) {
                e1.printStackTrace();
            }
            e.printStackTrace();
        }
    }

    private static void loadEmailConfig() throws Exception {
        try (FileInputStream fis = new FileInputStream(CONFIG_FILE)) {
            Properties props = new Properties();
            props.load(fis);
            HOST = props.getProperty("smtp.host");
            PORT = props.getProperty("smtp.port");
            USERNAME = props.getProperty("smtp.user");
            PASSWORD = props.getProperty("smtp.password");
            LOGGER.info("Email config loaded successfully.");
        } catch (FileNotFoundException e) {
            LOGGER.error("Config file not found: " + e.getMessage());
            throw new IOException("Email Config file not found.", e);
        } catch (IOException e) {
            LOGGER.error("Failed to load email config: " + e.getMessage());
            throw new IOException("Failed to load email config.", e);
        }
    }

    public static boolean sendEmail(String toEmail, String subject, String body, String attachmentPath) throws Exception {
    return sendEmail(toEmail, subject, body); 
    }

       public static boolean sendEmail(String toEmail, String subject, String body) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", HOST);
        props.put("mail.smtp.port", PORT);

        Session session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(USERNAME, PASSWORD);
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(USERNAME));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
            message.setSubject(subject);

            Multipart multipart = new MimeMultipart("alternative");

            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(body);
            multipart.addBodyPart(textPart);

                        MimeBodyPart htmlPart = new MimeBodyPart();
                        String htmlBody = """
                                <div style='min-height:100vh; background: radial-gradient(ellipse at 70% 30%, #2b2d42 0%, #1a1a2e 100%), url(https://www.transparenttextures.com/patterns/stardust.png); padding: 0; margin: 0;'>
                                    <div style='max-width: 500px; margin: 48px auto; background: rgba(30,34,60,0.85); border-radius: 18px; box-shadow: 0 8px 32px #0008, 0 1.5px 8px #00f2ff44; padding: 40px 28px; border: 1.5px solid #00f2ff33; backdrop-filter: blur(4px);'>
                                        <div style='text-align:center; margin-bottom: 28px;'>
                                            <img src='cid:verificateimg' alt='SafeRoom' style='width:72px; height:72px; border-radius:12px; box-shadow:0 0 16px #00f2ff88; background:#111;'/>
                                        </div>
                                        <h2 style='color:#00f2ff; margin-bottom:18px; font-family: "Orbitron", Arial, sans-serif; letter-spacing:1px; text-shadow:0 2px 8px #00f2ff44;'>" + subject + "</h2>
                                        <div style='color:#e0e6f7; font-size:17px; line-height:1.7; text-shadow:0 1px 4px #0006;'>" + body.replace("\n", "<br>") + "</div>
                                        <div style='margin-top:36px; text-align:center; color:#00f2ffcc; font-size:14px; letter-spacing:1px; text-shadow:0 1px 8px #00f2ff44;'>SafeRoom Security Team<br><span style='font-size:11px; color:#fff8; text-shadow:none;'>Exploring Security in the Universe</span></div>
                                    </div>
                                </div>
                        """;
                        htmlPart.setContent(htmlBody, "text/html; charset=utf-8");
                        multipart.addBodyPart(htmlPart);

            InputStream imageStream = EmailSender.class.getClassLoader().getResourceAsStream(ICON_RESOURCE_NAME);
            if (imageStream != null) {
                File tempFile = File.createTempFile("verificate", ".png");
                try (OutputStream os = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = imageStream.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }

                MimeBodyPart imagePart = new MimeBodyPart();
                imagePart.attachFile(tempFile);
                imagePart.setContentID("<verificateimg>");
                imagePart.setDisposition(MimeBodyPart.INLINE);

                MimeMultipart relatedMultipart = new MimeMultipart("related");
                relatedMultipart.addBodyPart(htmlPart);
                relatedMultipart.addBodyPart(imagePart);

                multipart.removeBodyPart(htmlPart);
                MimeBodyPart relatedBodyPart = new MimeBodyPart();
                relatedBodyPart.setContent(relatedMultipart);
                multipart.addBodyPart(relatedBodyPart);
            } else {
                LOGGER.warn("Verificate.png bulunamadı, ikon eklentisi yapılmadı.");
            }

            message.setContent(multipart);
            Transport.send(message);
            System.out.println("Email başarıyla gönderildi.");
            return true;

        } catch (MessagingException | IOException e) {
            LOGGER.error("Email gönderme hatası: " + e.getMessage());
            throw new RuntimeException("Email gönderme hatası: " + e.getMessage(), e);
        }
    }

    public static void notifyAccountLock(String username) throws Exception {
        String userEmail = DBManager.getEmailByUsername(username);
        String subject = "Urgent: Your SafeRoom Account has been Locked";
        String message = "Dear " + username + ",\n\n"
                + "Due to multiple incorrect verification attempts, your account has been temporarily locked.\n"
                + "If this was not you, please contact SafeRoom Security Team immediately.\n\n"
                + "Regards,\nSafeRoom Security Team";
        sendEmail(userEmail, subject, message);
    }
}
