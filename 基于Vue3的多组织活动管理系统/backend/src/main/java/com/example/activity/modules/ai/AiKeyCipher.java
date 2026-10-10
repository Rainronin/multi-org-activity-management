package com.example.activity.modules.ai;

import com.example.activity.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 密钥保护（后端架构 6.4）：组织 API Key 用标准 AES-GCM 加密保存，
 * 随机 nonce、认证标签与 keyId 一并管理，组织标识作为关联数据（AAD）。
 *
 * <p>密钥不能只做散列，因为调用时需要解密。主密钥由部署环境提供，不进数据库与仓库；
 * 主密钥缺失或密文无法解密时只让 AI 配置报错，手工业务不受影响。
 */
@Component
public class AiKeyCipher {

    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final AiProperties properties;
    private final SecureRandom random = new SecureRandom();

    public AiKeyCipher(AiProperties properties) {
        this.properties = properties;
    }

    /** 加密：返回 nonce ‖ 密文与认证标签。主密钥不可用时拒绝，不静默使用占位密钥。 */
    public byte[] encrypt(long orgId, String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            throw new IllegalArgumentException("明文密钥不能为空");
        }
        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(orgId, properties.getMasterKeyId()));
            byte[] sealed = cipher.doFinal(plainKey.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length);
            System.arraycopy(sealed, 0, result, nonce.length, sealed.length);
            return result;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable("密钥加密失败，请联系管理员检查部署环境", ex);
        }
    }

    /** 解密：keyId 与密文不一致（主密钥轮换或换库）时报错并允许重新配置。 */
    public String decrypt(long orgId, byte[] sealed, String keyId) {
        if (sealed == null || sealed.length <= NONCE_LENGTH) {
            throw unavailable("密钥密文不可用，请重新配置组织密钥", null);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce = java.util.Arrays.copyOfRange(sealed, 0, NONCE_LENGTH);
            byte[] body = java.util.Arrays.copyOfRange(sealed, NONCE_LENGTH, sealed.length);
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(orgId, keyId == null ? properties.getMasterKeyId() : keyId));
            return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable("密钥无法解密，请由管理员核对主密钥与数据库是否匹配，恢复正确主密钥或重新配置组织密钥", ex);
        }
    }

    /** 当前主密钥标识：随密文一起保存，便于轮换。 */
    public String currentKeyId() {
        return properties.getMasterKeyId();
    }

    private byte[] associatedData(long orgId, String keyId) {
        return ("org:" + orgId + "|key:" + keyId).getBytes(StandardCharsets.UTF_8);
    }

    private SecretKey masterKey() {
        String configured = properties.getMasterKey();
        if (configured == null || configured.isBlank()) {
            throw unavailable("部署环境未提供 AI 主密钥；请由管理员配置 ACTIVITY_AI_MASTER_KEY（或 app.ai.master-key）并重启后端", null);
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException ex) {
            throw unavailable("AI 主密钥不是合法的 Base64，请由管理员修正部署配置并重启后端", ex);
        }
        if (decoded.length != 32) {
            throw unavailable("AI 主密钥长度必须为 32 字节，请由管理员修正部署配置并重启后端", null);
        }
        return new SecretKeySpec(decoded, "AES");
    }

    private ApiException unavailable(String detail, Throwable cause) {
        ApiException exception = new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_KEY_UNAVAILABLE",
            "AI 密钥不可用", detail + "；手工编写方案与总结不受影响。");
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }
}
