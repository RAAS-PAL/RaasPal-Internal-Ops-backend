package com.raaspal.robotrecommendation.cvte.client;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;

/**
 * Implements the Kava Open Gateway "Signature &amp; Verification" rules:
 * sort the signing parameter set by name (ASCII order), concatenate name+value pairs,
 * then digest with MD5 (secret + string + secret) or HMAC_MD5 (keyed by secret).
 * The digest is rendered as 32 uppercase hex characters.
 *
 * Pure/stateless so it can be unit tested without an app secret in the environment.
 */
public final class KavaSignatureUtil {

    public static final String SIGN_TYPE_MD5 = "md5";
    public static final String SIGN_TYPE_HMAC = "hmac";

    private KavaSignatureUtil() {
    }

    /** byte2hex(MD5(bodyBuffer)) — used for the x-kv-content-md5 header. */
    public static String contentMd5(byte[] body) {
        return hex(md5(body));
    }

    /**
     * Signs the union of header + query signing parameters (params with empty
     * values are skipped, per the spec) and returns the 32-char uppercase hex signature.
     */
    public static String sign(Map<String, String> signingParams, String secret, String signType) {
        String[] keys = signingParams.keySet().toArray(new String[0]);
        Arrays.sort(keys);

        boolean isMd5 = SIGN_TYPE_MD5.equalsIgnoreCase(signType);
        StringBuilder stringToSign = new StringBuilder();
        if (isMd5) {
            stringToSign.append(secret);
        }
        for (String key : keys) {
            String value = signingParams.get(key);
            if (key != null && !key.isEmpty() && value != null && !value.isEmpty()) {
                stringToSign.append(key).append(value);
            }
        }

        byte[] digest;
        if (isMd5) {
            stringToSign.append(secret);
            digest = md5(stringToSign.toString().getBytes(StandardCharsets.UTF_8));
        } else {
            digest = hmacMd5(stringToSign.toString(), secret);
        }
        return hex(digest);
    }

    private static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }

    private static byte[] hmacMd5(String data, String secret) {
        try {
            SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacMD5");
            Mac mac = Mac.getInstance("HmacMD5");
            mac.init(key);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacMD5 algorithm not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String h = Integer.toHexString(b & 0xFF);
            if (h.length() == 1) {
                sb.append('0');
            }
            sb.append(h.toUpperCase());
        }
        return sb.toString();
    }
}
