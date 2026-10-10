-- 新建任务／物资的可选防重键；旧调用方仍可不传，正式页面始终传入。
ALTER TABLE task
    ADD COLUMN request_key VARCHAR(64) NULL AFTER created_by,
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER request_key,
    ADD UNIQUE KEY uk_task_org_request_key (org_id, request_key);

ALTER TABLE material
    ADD COLUMN request_key VARCHAR(64) NULL AFTER created_by,
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER request_key,
    ADD UNIQUE KEY uk_material_org_request_key (org_id, request_key);
