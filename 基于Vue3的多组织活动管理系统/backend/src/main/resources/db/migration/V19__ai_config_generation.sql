-- P8：AI 文本辅助（M11 活动总结、M12 方案生成）。
-- 1) ai_config：每组织一套当前模型配置（协议、完整端点、模型 ID、加密密钥、调用参数、启用状态与测试版本）。
--    version 用于保存与启停的乐观锁；connection_revision 只在协议、端点、模型、认证、密钥与调用参数变化时递增，
--    测试成功绑定该版本，单纯启用不使刚通过的测试失效（后端架构 6.4）。
-- 2) ai_generation：生成记录。请求防重键与内容指纹、输入来源摘要、配置快照、模型输出、结果状态与人工采用状态；
--    与业务文稿分离，不保存密钥与完整提示词原文（source_scope 只记录输入范围说明）。

CREATE TABLE ai_config (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    org_id BIGINT UNSIGNED NOT NULL,
    protocol VARCHAR(32) NOT NULL,
    endpoint VARCHAR(512) NOT NULL,
    model VARCHAR(128) NOT NULL,
    auth_scheme VARCHAR(24) NOT NULL,
    api_key_cipher VARBINARY(1024) NULL,
    api_key_key_id VARCHAR(64) NULL,
    max_output_tokens INT NOT NULL DEFAULT 1024,
    output_limit_field VARCHAR(32) NOT NULL DEFAULT 'MAX_TOKENS',
    timeout_seconds INT NOT NULL DEFAULT 20,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    connection_revision INT NOT NULL DEFAULT 0,
    tested_revision INT NULL,
    tested_at TIMESTAMP(6) NULL,
    tested_by BIGINT UNSIGNED NULL,
    operator_account_id BIGINT UNSIGNED NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_ai_config_org FOREIGN KEY (org_id) REFERENCES organization (id),
    UNIQUE KEY uk_ai_config_org (org_id)
);

CREATE TABLE ai_generation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    org_id BIGINT UNSIGNED NOT NULL,
    activity_id BIGINT UNSIGNED NULL,
    type VARCHAR(24) NOT NULL,
    operator_account_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL,
    request_key VARCHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    source_hash CHAR(64) NULL,
    source_scope VARCHAR(1000) NULL,
    protocol VARCHAR(32) NULL,
    model VARCHAR(128) NULL,
    endpoint_host VARCHAR(255) NULL,
    config_version BIGINT NULL,
    connection_revision INT NULL,
    output_text MEDIUMTEXT NULL,
    error_code VARCHAR(48) NULL,
    request_id VARCHAR(64) NULL,
    adopted_at TIMESTAMP(6) NULL,
    adopted_by BIGINT UNSIGNED NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_ai_generation_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_ai_generation_activity FOREIGN KEY (activity_id) REFERENCES activity (id),
    UNIQUE KEY uk_ai_generation_request (org_id, operator_account_id, request_key),
    KEY idx_ai_generation_activity (activity_id, type, created_at),
    KEY idx_ai_generation_quota (org_id, created_at)
);
