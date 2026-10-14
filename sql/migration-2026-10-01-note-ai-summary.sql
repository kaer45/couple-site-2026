-- ============================================================
-- 增量迁移：note 表增加「AI 摘要」字段
--
-- 背景：sql/init.sql 只在 MySQL 数据卷为空时（首次启动）执行，
--       已有数据库需要手动跑本脚本。
--
-- 执行：
--   mysql -uroot -p couple < sql/migration-2026-10-01-note-ai-summary.sql
--
-- 说明：
--   - 重复执行会因「字段已存在」报错，属正常，说明已经迁移过了
--   - 不做任何数据删除，只加两列
-- ============================================================

USE `couple`;

ALTER TABLE `note`
  ADD COLUMN `summary_ai`    TEXT     DEFAULT NULL COMMENT 'AI生成的内容摘要(手动触发,优先于正文节选展示)' AFTER `parse_error`,
  ADD COLUMN `summary_ai_at` DATETIME DEFAULT NULL COMMENT 'AI摘要生成时间' AFTER `summary_ai`;
