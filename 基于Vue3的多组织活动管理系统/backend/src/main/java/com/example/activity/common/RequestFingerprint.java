package com.example.activity.common;

/** 按字段长度分隔创建请求，避免输入中含分隔符时产生同一摘要。 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(Object... fields) {
        StringBuilder canonical = new StringBuilder();
        for (Object field : fields) {
            String value = field == null ? "" : field.toString();
            canonical.append(field == null ? -1 : value.length()).append(':').append(value);
        }
        return SecureCodes.hash(canonical.toString());
    }

    /** 旧摘要无法区分空值、文字 null 或字段分隔符，不把这些请求认定为原意图。 */
    public static boolean legacyTextUnambiguous(String... fields) {
        for (String field : fields) {
            if (field == null || "null".equals(field) || field.indexOf('\u001f') >= 0) {
                return false;
            }
        }
        return true;
    }
}
