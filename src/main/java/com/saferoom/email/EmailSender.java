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
                                        <div style='text-align:center; margin-bottom: 32px;'>
                                            <img src='cid:verificateimg' alt='SafeRoom' style='width:128px; height:128px; border-radius:16px; box-shadow:0 0 24px #00f2ff99, 0 0 48px #00f2ff44; background:#0a0a14; padding:8px;'/>
                                        </div>
                                        <h2 style='color:#00f2ff; margin-bottom:18px; font-family: "Orbitron", Arial, sans-serif; letter-spacing:1px; text-shadow:0 2px 8px #00f2ff44;'>" + subject + "</h2>
                                        <div style='color:#e0e6f7; font-size:17px; line-height:1.7; text-shadow:0 1px 4px #0006;'>" + body.replace("\n", "<br>") + "</div>
                                        <div style='margin-top:36px; text-align:center; color:#00f2ffcc; font-size:14px; letter-spacing:1px; text-shadow:0 1px 8px #00f2ff44;'>SafeRoom Security Team<br><span style='font-size:11px; color:#fff8; text-shadow:none;'>🔐 Exploring Security in the Universe 🌌</span></div>
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

    public static boolean sendVerificationEmail(String toEmail, String username, String verificationCode) throws Exception {
        String subject = "🔐 Verify Your SafeRoom Account";
        
        String htmlBody = """
                <div style='min-height:100vh; background: radial-gradient(ellipse at 70% 30%, #2b2d42 0%, #1a1a2e 100%), url(https://www.transparenttextures.com/patterns/stardust.png); padding: 0; margin: 0;'>
                    <div style='max-width: 500px; margin: 48px auto; background: rgba(30,34,60,0.85); border-radius: 18px; box-shadow: 0 8px 32px #0008, 0 1.5px 8px #00f2ff44; padding: 40px 28px; border: 1.5px solid #00f2ff33; backdrop-filter: blur(4px);'>
                        <div style='text-align:center; margin-bottom: 32px;'>
                            <img src='cid:verificateimg' alt='SafeRoom' style='width:128px; height:128px; border-radius:16px; box-shadow:0 0 24px #00f2ff99, 0 0 48px #00f2ff44; background:#0a0a14; padding:8px;'/>
                        </div>
                        <h2 style='color:#00f2ff; margin-bottom:18px; font-family: "Orbitron", Arial, sans-serif; letter-spacing:1px; text-shadow:0 2px 8px #00f2ff44; text-align:center;'>🔐 Account Verification</h2>
                        
                        <div style='color:#e0e6f7; font-size:17px; line-height:1.7; text-shadow:0 1px 4px #0006;'>
                            <p>Hello <strong style='color:#00f2ff;'>""" + username + """
                            </strong>,</p>
                            
                            <p>Welcome to <strong style='color:#00f2ff;'>SafeRoom</strong>! 🚀</p>
                            
                            <p>To complete your account registration, please verify your email address using the verification code below:</p>
                            
                            <div style='background: rgba(0,242,255,0.1); border: 2px solid #00f2ff; border-radius: 12px; padding: 20px; margin: 24px 0; text-align: center;'>
                                <p style='margin: 0; color: #fff; font-size: 14px; margin-bottom: 8px;'>Your Verification Code:</p>
                                <p style='margin: 0; color: #00f2ff; font-size: 32px; font-weight: bold; letter-spacing: 4px; font-family: "Courier New", monospace; text-shadow: 0 0 12px #00f2ff44;'>""" + verificationCode + """
                                </p>
                            </div>
                            
                            <p style='color: #ffcc00;'>⚠️ <strong>Important:</strong></p>
                            <ul style='color: #e0e6f7; padding-left: 20px;'>
                                <li>This code will expire in <strong>1 minutes</strong></li>
                                <li>Do not share this code with anyone</li>
                                <li>If you didn't create this account, please ignore this email</li>
                            </ul>
                            
                            <p>Thank you for choosing SafeRoom for your secure communication needs! 💪</p>
                        </div>
                        
                        <div style='margin-top:36px; text-align:center; color:#00f2ffcc; font-size:14px; letter-spacing:1px; text-shadow:0 1px 8px #00f2ff44;'>
                            SafeRoom Security Team<br>
                            <span style='font-size:11px; color:#fff8; text-shadow:none;'>🌟 Exploring Security in the Universe 🌟</span>
                        </div>
                    </div>
                </div>
        """;

        return sendEmailWithHtml(toEmail, subject, htmlBody);
    }

    public static boolean sendPasswordResetEmail(String toEmail, String username, String resetCode) throws Exception {
        String subject = "🔒 Reset Your SafeRoom Password";
        
        String htmlBody = """
                <div style='min-height:100vh; background: radial-gradient(ellipse at 70% 30%, #2b2d42 0%, #1a1a2e 100%), url(https://www.transparenttextures.com/patterns/stardust.png); padding: 0; margin: 0;'>
                    <div style='max-width: 500px; margin: 48px auto; background: rgba(30,34,60,0.85); border-radius: 18px; box-shadow: 0 8px 32px #0008, 0 1.5px 8px #ff6b6b44; padding: 40px 28px; border: 1.5px solid #ff6b6b33; backdrop-filter: blur(4px);'>
                        <div style='text-align:center; margin-bottom: 32px;'>
                            <img src='cid:verificateimg' alt='SafeRoom' style='width:128px; height:128px; border-radius:16px; box-shadow:0 0 24px #ff6b6b99, 0 0 48px #ff6b6b44; background:#0a0a14; padding:8px;'/>
                        </div>
                        <h2 style='color:#ff6b6b; margin-bottom:18px; font-family: "Orbitron", Arial, sans-serif; letter-spacing:1px; text-shadow:0 2px 8px #ff6b6b44; text-align:center;'>🔒 Password Reset Request</h2>
                        
                        <div style='color:#e0e6f7; font-size:17px; line-height:1.7; text-shadow:0 1px 4px #0006;'>
                            <p>Hello <strong style='color:#ff6b6b;'>""" + username + """
                            </strong>,</p>
                            
                            <p>We received a request to reset your <strong style='color:#ff6b6b;'>SafeRoom</strong> account password. 🔐</p>
                            
                            <p>If you requested this password reset, please use the verification code below to proceed:</p>
                            
                            <div style='background: rgba(255,107,107,0.1); border: 2px solid #ff6b6b; border-radius: 12px; padding: 20px; margin: 24px 0; text-align: center;'>
                                <p style='margin: 0; color: #fff; font-size: 14px; margin-bottom: 8px;'>Your Password Reset Code:</p>
                                <p style='margin: 0; color: #ff6b6b; font-size: 32px; font-weight: bold; letter-spacing: 4px; font-family: "Courier New", monospace; text-shadow: 0 0 12px #ff6b6b44;'>""" + resetCode + """
                                </p>
                            </div>
                            
                            <p style='color: #ffcc00;'>⚠️ <strong>Security Notice:</strong></p>
                            <ul style='color: #e0e6f7; padding-left: 20px;'>
                                <li>This reset code will expire in <strong>15 minutes</strong></li>
                                <li><strong>Never share this code</strong> with anyone</li>
                                <li>If you didn't request this reset, please <strong>ignore this email</strong></li>
                                <li>Your account remains secure until you complete the reset process</li>
                            </ul>
                            
                            <div style='background: rgba(255,193,7,0.1); border-left: 4px solid #ffc107; padding: 16px; margin: 20px 0; border-radius: 4px;'>
                                <p style='margin: 0; color: #ffc107; font-weight: bold;'>🛡️ Didn't request this?</p>
                                <p style='margin: 8px 0 0 0; color: #e0e6f7; font-size: 15px;'>If you didn't request a password reset, someone may be trying to access your account. Please contact our security team immediately.</p>
                            </div>
                            
                            <p>Stay secure with SafeRoom! 💪</p>
                        </div>
                        
                        <div style='margin-top:36px; text-align:center; color:#ff6b6bcc; font-size:14px; letter-spacing:1px; text-shadow:0 1px 8px #ff6b6b44;'>
                            SafeRoom Security Team<br>
                            <span style='font-size:11px; color:#fff8; text-shadow:none;'>🔐 Your Security is Our Priority 🔐</span>
                        </div>
                    </div>
                </div>
        """;

        return sendEmailWithHtml(toEmail, subject, htmlBody);
    }

    private static boolean sendEmailWithHtml(String toEmail, String subject, String htmlBody) throws Exception {
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

            // Text version (fallback)
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText("Please verify your SafeRoom account. If you cannot see the HTML version, please contact support.");
            multipart.addBodyPart(textPart);

            // HTML version
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(htmlBody, "text/html; charset=utf-8");

            // Add image
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

                MimeBodyPart relatedBodyPart = new MimeBodyPart();
                relatedBodyPart.setContent(relatedMultipart);
                multipart.addBodyPart(relatedBodyPart);
            } else {
                multipart.addBodyPart(htmlPart);
                LOGGER.warn("Verificate.png bulunamadı, ikon eklenmedi.");
            }

            message.setContent(multipart);
            Transport.send(message);
            LOGGER.info("Verification email sent successfully to: " + toEmail);
            return true;

        } catch (MessagingException | IOException e) {
            LOGGER.error("Verification email sending failed: " + e.getMessage());
            throw new RuntimeException("Verification email sending failed: " + e.getMessage(), e);
        }
    }
}
