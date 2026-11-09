package com.ieltsai.ieltsaibackend.service;

import com.ieltsai.ieltsaibackend.entity.User;
import com.ieltsai.ieltsaibackend.mapper.UserMapper;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class UserService {

    private static final int ITERATION_COUNT = 600_000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public void registerUser(User user) {
        user.setPassword(hashPassword(user.getPassword()));
        userMapper.insert(user);
    }

    private String hashPassword(String password) {
        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);

        PBEKeySpec keySpec = new PBEKeySpec(
                password.toCharArray(),
                salt,
                ITERATION_COUNT,
                KEY_LENGTH
        );

        try {
            byte[] passwordHash = SecretKeyFactory
                    .getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(keySpec)
                    .getEncoded();

            return "pbkdf2_sha256$"
                    + ITERATION_COUNT + "$"
                    + Base64.getEncoder().encodeToString(salt) + "$"
                    + Base64.getEncoder().encodeToString(passwordHash);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to hash password", exception);
        } finally {
            keySpec.clearPassword();
        }
    }
}
