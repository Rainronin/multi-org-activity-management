-- P4.2：独立报名开关（S02）。报名申请要求开关开启且处于申请时间窗；
-- 审核既有申请不看开关。新建/重新确认的活动默认关闭，由负责人明确开启（计划书 6.1／6.2）。
ALTER TABLE activity
    ADD COLUMN registration_open TINYINT(1) NOT NULL DEFAULT 0 AFTER registration_end;
