package com.example.activity.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 一次性分享码（恢复码、邀请码共用）：安全随机源 128 位，展示为分段十六进制；
 * 持久层只保存规范化后的 SHA-256 摘要，明文仅在发放响应中出现一次。
 */
public final class SecureCodes {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private SecureCodes() {
    }

    public static String generate() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
        StringBuilder display = new StringBuilder(hex.length() + hex.length() / 4 - 1);
        for (int i = 0; i < hex.length(); i += 4) {
            if (i > 0) {
                display.append('-');
            }
            display.append(hex, i, i + 4);
        }
        return display.toString();
    }

    /** 比较前忽略大小写、连字符与空格，容忍手工抄录差异。 */
    public static String normalize(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    public static String hash(String normalizedCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalizedCode.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
