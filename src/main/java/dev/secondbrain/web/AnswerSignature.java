package dev.secondbrain.web;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs each rendered answer so feedback can only be recorded for answers this server produced. The
 * key lives in memory and changes on restart; older pages then need a new question.
 */
@Component
final class AnswerSignature {
    private final byte[] key = new byte[32];

    AnswerSignature() {
        new SecureRandom().nextBytes(key);
    }

    String sign(String question, String answer, List<String> sources) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            // Lengths prefix every part, so different splits never sign the same bytes.
            for (var part : parts(question, answer, sources)) {
                var bytes = part.getBytes(StandardCharsets.UTF_8);
                mac.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
                mac.update(bytes);
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Answer signing unavailable", unavailable);
        }
    }

    boolean matches(String signature, String question, String answer, List<String> sources) {
        return MessageDigest.isEqual(
                sign(question, answer, sources).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    private static List<String> parts(String question, String answer, List<String> sources) {
        var all = new java.util.ArrayList<String>();
        all.add(question);
        all.add(answer);
        all.addAll(sources);
        return all;
    }
}
