package com.evops.papercomp.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 卷面 / 素材池指纹工具（确定性、无随机、无时钟）。
 * 指纹把"算法口径 + 素材池快照 + 种子 + 入选题目"绑死，事后同样输入必须算出同一指纹。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /** SHA-256 十六进制（小写，64 位）。 */
    public static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用：" + e.getMessage(), e);
        }
    }
}
