-- R05：收敛成员主关系模型——同一 (org_id, account_id) 仅保留最新一行作为主关系，
-- 历史以 audit_log 为准；重新加入复用主关系行（memberId 稳定），不再新增并列行。
DELETE m1
FROM organization_member m1
JOIN organization_member m2
  ON m1.org_id = m2.org_id
 AND m1.account_id = m2.account_id
 AND m1.id < m2.id;
