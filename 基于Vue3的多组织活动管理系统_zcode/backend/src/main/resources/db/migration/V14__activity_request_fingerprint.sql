-- F06：创建防重改用不可变的请求指纹。
-- 原实现把重放请求与「当前活动字段」比较，活动被编辑后，同一键＋同一请求会被误判为内容不同而返回 409。
-- 这里在创建时保存创建请求摘要（规范化业务字段的 SHA-256），重放只与摘要比较；
-- 该列仅创建时写入，后续编辑不改写，因此是稳定依据。

ALTER TABLE activity
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER request_key;
