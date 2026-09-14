-- ============================================================
-- 双人情侣互动网站 - 数据库初始化脚本
-- 数据库: couple  (utf8mb4 / utf8mb4_unicode_ci)
-- 适用: MySQL 8.0+
-- Docker 部署时挂载到 /docker-entrypoint-initdb.d/init.sql 自动执行
-- ============================================================

CREATE DATABASE IF NOT EXISTS `couple`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE `couple`;

-- ------------------------------------------------------------
-- 1. 用户表 user
--    注册即生成情侣码 couple_code，伴侣凭码绑定；
--    绑定成功后 couple_id 指向 couple 表，couple_code 作废
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username`    VARCHAR(50)  NOT NULL COMMENT '登录名(唯一)',
  `password`    VARCHAR(100) NOT NULL COMMENT '密码(BCrypt加密)',
  `nickname`    VARCHAR(50)  NOT NULL COMMENT '昵称',
  `avatar`      VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
  `couple_code` VARCHAR(10)  DEFAULT NULL COMMENT '情侣码(注册时生成,绑定成功后作废)',
  `couple_id`   BIGINT UNSIGNED DEFAULT NULL COMMENT '绑定关系ID(关联 couple.id)',
  `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_couple_code` (`couple_code`),
  KEY `idx_couple_id` (`couple_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ------------------------------------------------------------
-- 2. 情侣关系表 couple
--    start_date = 在一起的那天，首页"在一起 X 天"据此计算
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `couple`;
CREATE TABLE `couple` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '关系ID',
  `user_a_id`  BIGINT UNSIGNED NOT NULL COMMENT '发起绑定方用户ID',
  `user_b_id`  BIGINT UNSIGNED NOT NULL COMMENT '被绑定方用户ID',
  `start_date` DATE NOT NULL COMMENT '在一起纪念日',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_a` (`user_a_id`),
  UNIQUE KEY `uk_user_b` (`user_b_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='情侣关系表';

-- ------------------------------------------------------------
-- 3. 动态表 moment（情侣朋友圈时间轴）
--    images 为 JSON 数组，例如 ["/uploads/a.jpg","/uploads/b.jpg"]
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `moment`;
CREATE TABLE `moment` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '动态ID',
  `couple_id`  BIGINT UNSIGNED NOT NULL COMMENT '所属情侣关系ID',
  `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '发布者用户ID',
  `content`    TEXT NOT NULL COMMENT '文字内容',
  `images`     JSON DEFAULT NULL COMMENT '图片URL数组(JSON)',
  `location`   VARCHAR(100) DEFAULT NULL COMMENT '定位/地点',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
  PRIMARY KEY (`id`),
  KEY `idx_couple_created` (`couple_id`, `created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='动态表';

-- ------------------------------------------------------------
-- 4. 相册表 album（共享云相册，按主题分组，如"2024旅行"）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `album`;
CREATE TABLE `album` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '相册ID',
  `couple_id`   BIGINT UNSIGNED NOT NULL COMMENT '所属情侣关系ID',
  `name`        VARCHAR(100) NOT NULL COMMENT '相册名称',
  `description` VARCHAR(255) DEFAULT NULL COMMENT '相册描述',
  `cover_url`   VARCHAR(255) DEFAULT NULL COMMENT '封面图URL(取最新照片)',
  `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_couple_id` (`couple_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='相册表';

-- ------------------------------------------------------------
-- 5. 照片表 photo
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `photo`;
CREATE TABLE `photo` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '照片ID',
  `album_id`      BIGINT UNSIGNED NOT NULL COMMENT '所属相册ID',
  `user_id`       BIGINT UNSIGNED NOT NULL COMMENT '上传者用户ID',
  `url`           VARCHAR(255) NOT NULL COMMENT '原图URL',
  `thumbnail_url` VARCHAR(255) DEFAULT NULL COMMENT '缩略图URL(可选)',
  `description`   VARCHAR(255) DEFAULT NULL COMMENT '照片描述',
  `taken_at`      DATETIME DEFAULT NULL COMMENT '拍摄时间',
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  PRIMARY KEY (`id`),
  KEY `idx_album_id` (`album_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='照片表';

-- ------------------------------------------------------------
-- 6. 纪念日表 anniversary
--    is_start=1 表示"在一起的那天"(绑定关系时自动创建, 不可删除)
--    remind_days: 提前提醒天数(预留)
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `anniversary`;
CREATE TABLE `anniversary` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '纪念日ID',
  `couple_id`        BIGINT UNSIGNED NOT NULL COMMENT '所属情侣关系ID',
  `name`             VARCHAR(100) NOT NULL COMMENT '纪念日名称,如"第一次旅行"',
  `anniversary_date` DATE NOT NULL COMMENT '纪念日日期(月-日)',
  `is_start`         TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否"在一起"纪念日(1=是,自动创建不可删)',
  `remind_days`      INT NOT NULL DEFAULT 0 COMMENT '提前提醒天数(预留)',
  `created_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_couple_id` (`couple_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='纪念日表';

-- ------------------------------------------------------------
-- 7. 提醒记录表 reminder_log
--    防止同一天对同一个纪念日重复提醒
--    UNIQUE(anniversary_id, remind_date) 是防重的最后一道防线
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `reminder_log`;
CREATE TABLE `reminder_log` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `anniversary_id` BIGINT UNSIGNED NOT NULL COMMENT '纪念日ID(关联 anniversary.id)',
  `remind_date`    DATE NOT NULL COMMENT '提醒发生的日期(不是纪念日日期)',
  `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_anniversary_date` (`anniversary_id`, `remind_date`),
  KEY `idx_remind_date` (`remind_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='纪念日提醒记录表(防重)';

-- ------------------------------------------------------------
-- 8. 通知表 notification（站内信）
--    定时任务发现需要提醒时插入记录，用户登录后查看
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `notification`;
CREATE TABLE `notification` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '通知ID',
  `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '接收人用户ID(关联 user.id)',
  `couple_id`  BIGINT UNSIGNED DEFAULT NULL COMMENT '所属情侣关系ID(关联 couple.id)',
  `type`       VARCHAR(32) NOT NULL COMMENT '通知类型,如 ANNIVERSARY_REMIND',
  `title`      VARCHAR(64) DEFAULT NULL COMMENT '标题',
  `content`    VARCHAR(255) DEFAULT NULL COMMENT '内容',
  `related_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '关联业务ID(如纪念日ID)',
  `is_read`    TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已读(0=未读,1=已读)',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_read_created` (`user_id`, `is_read`, `created_at` DESC),
  KEY `idx_couple_id` (`couple_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='站内通知表';

-- ============================================================
-- 以下为扩展功能预留表（后续迭代再启用，可先不建）
-- ============================================================
-- -- 9. 情侣任务打卡 task / task_record
-- CREATE TABLE `task` (
--   `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
--   `couple_id`   BIGINT UNSIGNED NOT NULL,
--   `title`       VARCHAR(100) NOT NULL,
--   `description` VARCHAR(255) DEFAULT NULL,
--   `points`      INT NOT NULL DEFAULT 10 COMMENT '完成奖励积分',
--   `status`      TINYINT NOT NULL DEFAULT 1 COMMENT '1=进行中 0=已下线',
--   `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
--   PRIMARY KEY (`id`)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='情侣任务表';
--
-- CREATE TABLE `task_record` (
--   `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
--   `task_id`    BIGINT UNSIGNED NOT NULL,
--   `user_id`    BIGINT UNSIGNED NOT NULL,
--   `completed_date` DATE NOT NULL COMMENT '打卡日期',
--   `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
--   PRIMARY KEY (`id`),
--   UNIQUE KEY `uk_task_user_date` (`task_id`, `user_id`, `completed_date`)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务打卡记录表';
--
-- -- 10. 积分记录 point_record（积分商城预留）
-- CREATE TABLE `point_record` (
--   `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
--   `user_id`    BIGINT UNSIGNED NOT NULL,
--   `change`     INT NOT NULL COMMENT '变动积分(正负)',
--   `reason`     VARCHAR(100) NOT NULL,
--   `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
--   PRIMARY KEY (`id`)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='积分变动记录表';
--
-- -- 11. 实时聊天 chat_message（WebSocket 预留）
-- CREATE TABLE `chat_message` (
--   `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
--   `couple_id`  BIGINT UNSIGNED NOT NULL,
--   `from_user_id` BIGINT UNSIGNED NOT NULL,
--   `content`    VARCHAR(2000) NOT NULL,
--   `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
--   PRIMARY KEY (`id`),
--   KEY `idx_couple_created` (`couple_id`, `created_at`)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天消息表';


-- ------------------------------------------------------------
-- 12. 个人待办表 user_todo（每日待办）
--    纯个人数据，按 user_id 硬隔离，对方不可见
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `user_todo`;
CREATE TABLE `user_todo` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '待办ID',
  `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '所属用户ID(关联 user.id，仅从 JWT 取，前端禁止传)',
  `title`        VARCHAR(200) NOT NULL COMMENT '待办内容',
  `done`         TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否完成(0=未完成,1=已完成)',
  `priority`     TINYINT(1) NOT NULL DEFAULT 0 COMMENT '优先级(0=普通,1=重要)',
  `due_date`     DATE DEFAULT NULL COMMENT '归属日期(空=未排期)',
  `completed_at` DATETIME DEFAULT NULL COMMENT '完成时间',
  `created_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_date` (`user_id`, `due_date`),
  KEY `idx_user_done` (`user_id`, `done`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='个人待办表';
