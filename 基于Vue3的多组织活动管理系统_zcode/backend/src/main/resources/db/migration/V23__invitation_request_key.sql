-- 邀请创建核对只恢复元数据，不保存或再次返回邀请码明文；旧无键调用兼容。
ALTER TABLE organization_invitation
    ADD COLUMN request_key VARCHAR(64) NULL AFTER created_by,
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER request_key,
    ADD UNIQUE KEY uk_invitation_org_request_key (org_id, request_key);
