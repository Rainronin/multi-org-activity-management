-- 活动与费用新指纹增加v2:前缀，复用字段长度分隔，区分NULL、字面量null及分隔符。
-- 仅扩容，不回写旧64位摘要；类型变更显式COPY，部署应留迁移维护窗口。
ALTER TABLE activity
    MODIFY COLUMN request_fingerprint VARCHAR(67) NULL, ALGORITHM=COPY, LOCK=SHARED;

ALTER TABLE expense
    MODIFY COLUMN request_fingerprint VARCHAR(67) NULL, ALGORITHM=COPY, LOCK=SHARED;
