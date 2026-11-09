package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.entity.User;
import com.ieltsai.ieltsaibackend.mapper.UserMapper;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final String CAPTCHA_SESSION_KEY = "AUTH_CAPTCHA";
    private static final String USER_ID_SESSION_KEY = "AUTH_USER_ID";
    private static final String CAPTCHA_CHARACTERS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int CAPTCHA_LENGTH = 4;
    private static final int CAPTCHA_WIDTH = 140;
    private static final int CAPTCHA_HEIGHT = 48;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserMapper userMapper;

    public AuthService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public byte[] createCaptcha(HttpSession session) {
        String captcha = generateCaptchaCode();
        session.setAttribute(CAPTCHA_SESSION_KEY, captcha);
        return renderCaptchaImage(captcha);
    }

    public LoginResult login(
            String username,
            String password,
            String captcha,
            HttpSession session
    ) {
        Object storedCaptcha = session.getAttribute(CAPTCHA_SESSION_KEY);
        session.removeAttribute(CAPTCHA_SESSION_KEY);

        if (!(storedCaptcha instanceof String expectedCaptcha)) {
            log.warn("event=auth_login_failed reason=CAPTCHA_EXPIRED");
            return new LoginResult(LoginStatus.CAPTCHA_EXPIRED);
        }
        if (captcha == null || !expectedCaptcha.equalsIgnoreCase(captcha.trim())) {
            log.warn("event=auth_login_failed reason=INVALID_CAPTCHA");
            return new LoginResult(LoginStatus.INVALID_CAPTCHA);
        }

        User user = userMapper.findByUsername(username);
        if (user == null || !verifyPassword(password, user.getPassword())) {
            log.warn("event=auth_login_failed reason=INVALID_CREDENTIALS");
            return new LoginResult(LoginStatus.INVALID_CREDENTIALS);
        }

        session.setAttribute(USER_ID_SESSION_KEY, user.getId());
        log.info("event=auth_login_success userId={}", user.getId());
        return new LoginResult(LoginStatus.SUCCESS);
    }

    public User getCurrentUser(HttpSession session) {
        Object userId = session.getAttribute(USER_ID_SESSION_KEY);
        if (!(userId instanceof Long id)) {
            return null;
        }

        User user = userMapper.findById(id);
        if (user == null) {
            session.removeAttribute(USER_ID_SESSION_KEY);
        }
        return user;
    }

    private String generateCaptchaCode() {
        StringBuilder captcha = new StringBuilder(CAPTCHA_LENGTH);
        for (int index = 0; index < CAPTCHA_LENGTH; index++) {
            int characterIndex = SECURE_RANDOM.nextInt(CAPTCHA_CHARACTERS.length());
            captcha.append(CAPTCHA_CHARACTERS.charAt(characterIndex));
        }
        return captcha.toString();
    }

    private byte[] renderCaptchaImage(String captcha) {
        BufferedImage image = new BufferedImage(
                CAPTCHA_WIDTH,
                CAPTCHA_HEIGHT,
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D graphics = image.createGraphics();

        try {
            graphics.setColor(new Color(245, 247, 250));
            graphics.fillRect(0, 0, CAPTCHA_WIDTH, CAPTCHA_HEIGHT);
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            for (int index = 0; index < 6; index++) {
                graphics.setColor(randomLightColor());
                graphics.drawLine(
                        SECURE_RANDOM.nextInt(CAPTCHA_WIDTH),
                        SECURE_RANDOM.nextInt(CAPTCHA_HEIGHT),
                        SECURE_RANDOM.nextInt(CAPTCHA_WIDTH),
                        SECURE_RANDOM.nextInt(CAPTCHA_HEIGHT)
                );
            }

            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
            for (int index = 0; index < captcha.length(); index++) {
                graphics.setColor(randomDarkColor());
                graphics.drawString(
                        String.valueOf(captcha.charAt(index)),
                        18 + index * 29,
                        34 + SECURE_RANDOM.nextInt(5)
                );
            }
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create captcha image", exception);
        }
    }

    private Color randomLightColor() {
        return new Color(
                150 + SECURE_RANDOM.nextInt(80),
                150 + SECURE_RANDOM.nextInt(80),
                150 + SECURE_RANDOM.nextInt(80)
        );
    }

    private Color randomDarkColor() {
        return new Color(
                SECURE_RANDOM.nextInt(100),
                SECURE_RANDOM.nextInt(100),
                SECURE_RANDOM.nextInt(100)
        );
    }

    private boolean verifyPassword(String password, String storedPassword) {
        if (password == null || storedPassword == null) {
            return false;
        }

        PBEKeySpec keySpec = null;
        try {
            String[] parts = storedPassword.split("\\$", 4);
            if (parts.length != 4 || !"pbkdf2_sha256".equals(parts[0])) {
                return false;
            }

            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expectedHash = Base64.getDecoder().decode(parts[3]);

            keySpec = new PBEKeySpec(
                    password.toCharArray(),
                    salt,
                    iterations,
                    expectedHash.length * 8
            );
            byte[] actualHash = SecretKeyFactory
                    .getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(keySpec)
                    .getEncoded();

            return MessageDigest.isEqual(expectedHash, actualHash);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        } finally {
            if (keySpec != null) {
                keySpec.clearPassword();
            }
        }
    }

    public enum LoginStatus {
        SUCCESS,
        CAPTCHA_EXPIRED,
        INVALID_CAPTCHA,
        INVALID_CREDENTIALS
    }

    public record LoginResult(LoginStatus status) {
    }
}
