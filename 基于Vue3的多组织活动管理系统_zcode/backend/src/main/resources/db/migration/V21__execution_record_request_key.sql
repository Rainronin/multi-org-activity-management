-- 执行记录追加的可选防重键；旧无键调用保持兼容。
ALTER TABLE execution_record
    ADD COLUMN request_key VARCHAR(64) NULL AFTER recorded_by,
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER request_key,
    ADD UNIQUE KEY uk_execution_record_org_request_key (org_id, request_key);
