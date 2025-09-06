package com.saferoom.crypto;

import java.util.Random;

public class VerificationCodeGenerator {

    public static String generateVerificationCode() {
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        int length = 6; 

        while (sb.length() < length) {
            if (random.nextBoolean()) {
                sb.append(random.nextInt(10));
            } else {
                char letter = (char) (random.nextBoolean() ? 'a' + random.nextInt(26) : 'A' + random.nextInt(26));
                sb.append(letter);
            }
        }

        return sb.toString();
    }
}
