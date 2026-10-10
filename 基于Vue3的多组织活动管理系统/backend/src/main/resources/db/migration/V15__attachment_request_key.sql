-- F06：附件上传的创建防重（开发规范第三节：文件上传属可能重复产生记录的 POST）。
-- request_key 为客户端一次上传意图的 Idempotency-Key，按组织唯一；
-- request_fingerprint 保存「文件内容摘要 + 归属」的 SHA-256，重放只与摘要比较，不比较文件名。

ALTER TABLE attachment
    ADD COLUMN request_key VARCHAR(64) NULL AFTER uploaded_by,
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER request_key,
    ADD UNIQUE KEY uk_attachment_org_request_key (org_id, request_key);
