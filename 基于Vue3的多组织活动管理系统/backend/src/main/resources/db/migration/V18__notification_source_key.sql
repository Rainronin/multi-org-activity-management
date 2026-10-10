-- P7：站内提示来源与防重。
-- 关键业务事务写最小通知意图（来源操作、接收账号、类型、资源引用），以（接收账号, 类型, 来源操作）唯一防重，
-- 保证重放与并发不产生重复的有效提示；read_at 记录已读。
-- 说明：首期只有站内提示，"投递"就是意图行本身（生成即可读），不另建投递队列；
-- 外部渠道投递失败重试不在本期范围，接口说明与数据设计已记录该最小例外。

ALTER TABLE notification
    ADD COLUMN source_key VARCHAR(64) NULL AFTER activity_id,
    ADD UNIQUE KEY uk_notification_dedupe (recipient_account_id, type, source_key);
