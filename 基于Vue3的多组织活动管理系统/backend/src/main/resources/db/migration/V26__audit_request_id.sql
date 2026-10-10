-- 新审计关联本次服务端请求；历史及非HTTP写入保留NULL，不回填或改写旧记录。
ALTER TABLE audit_log
    ADD COLUMN request_id VARCHAR(64) NULL,
    ALGORITHM=INSTANT;
