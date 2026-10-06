-- 确认文稿独立于当前草稿，只在业务确认事务中追加；不从现正文伪造历史快照。
CREATE TABLE activity_confirmation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_id BIGINT UNSIGNED NOT NULL,
    kind VARCHAR(16) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    activity_version BIGINT NOT NULL,
    source_hash CHAR(64) NOT NULL,
    confirmed_by BIGINT UNSIGNED NOT NULL,
    confirmed_at TIMESTAMP(6) NOT NULL,
    reopen_reason VARCHAR(500) NULL,
    reopen_count INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_confirmation_activity FOREIGN KEY (activity_id) REFERENCES activity (id),
    CONSTRAINT fk_confirmation_actor FOREIGN KEY (confirmed_by) REFERENCES account (id),
    CONSTRAINT ck_confirmation_kind CHECK (kind IN ('PROPOSAL', 'SUMMARY', 'CANCEL_NOTE')),
    UNIQUE KEY uk_confirmation_activity_version (activity_id, activity_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
