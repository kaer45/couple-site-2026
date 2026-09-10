USE couple;

-- ============================================================
-- 修复所有表的注释
-- ============================================================
ALTER TABLE `user` COMMENT = '用户表';
ALTER TABLE `couple` COMMENT = '情侣关系表';
ALTER TABLE `moment` COMMENT = '动态表';
ALTER TABLE `album` COMMENT = '相册表';
ALTER TABLE `photo` COMMENT = '照片表';
ALTER TABLE `anniversary` COMMENT = '纪念日表';

-- ============================================================
-- 修复 user 表字段注释
-- ============================================================
ALTER TABLE `user` MODIFY COLUMN `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID';
ALTER TABLE `user` MODIFY COLUMN `username` VARCHAR(50) NOT NULL COMMENT '登录名(唯一)';
ALTER TABLE `user` MODIFY COLUMN `password` VARCHAR(100) NOT NULL COMMENT '密码(BCrypt加密)';
ALTER TABLE `user` MODIFY COLUMN `nickname` VARCHAR(50) NOT NULL COMMENT '昵称';
ALTER TABLE `user` MODIFY COLUMN `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像URL';
ALTER TABLE `user` MODIFY COLUMN `couple_code` VARCHAR(10) DEFAULT NULL COMMENT '情侣码(注册时生成,绑定成功后作废)';
ALTER TABLE `user` MODIFY COLUMN `couple_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '绑定关系ID(关联 couple.id)';
ALTER TABLE `user` MODIFY COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `user` MODIFY COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- ============================================================
-- 修复 couple 表字段注释
-- ============================================================
ALTER TABLE `couple` MODIFY COLUMN `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '关系ID';
ALTER TABLE `couple` MODIFY COLUMN `user_a_id` BIGINT UNSIGNED NOT NULL COMMENT '发起绑定方用户ID';
ALTER TABLE `couple` MODIFY COLUMN `user_b_id` BIGINT UNSIGNED NOT NULL COMMENT '被绑定方用户ID';
ALTER TABLE `couple` MODIFY COLUMN `start_date` DATE NOT NULL COMMENT '在一起纪念日';
ALTER TABLE `couple` MODIFY COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

-- ============================================================
-- 修复 moment 表字段注释
-- ============================================================
ALTER TABLE `moment` MODIFY COLUMN `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '动态ID';
ALTER TABLE `moment` MODIFY COLUMN `couple_id` BIGINT UNSIGNED NOT NULL COMMENT '所属情侣关系ID';
ALTER TABLE `moment` MODIFY COLUMN `user_id` BIGINT UNSIGNED NOT NULL COMMENT '发布者用户ID';
ALTER TABLE `moment` MODIFY COLUMN `content` TEXT NOT NULL COMMENT '文字内容';
ALTER TABLE `moment` MODIFY COLUMN `images` JSON DEFAULT NULL COMMENT '图片URL数组(JSON)';
ALTER TABLE `moment` MODIFY COLUMN `location` VARCHAR(100) DEFAULT NULL COMMENT '定位/地点';
ALTER TABLE `moment` MODIFY COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间';

-- ============================================================
-- 修复 album 表字段注释
-- ============================================================
ALTER TABLE `album` MODIFY COLUMN `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '相册ID';
ALTER TABLE `album` MODIFY COLUMN `couple_id` BIGINT UNSIGNED NOT NULL COMMENT '所属情侣关系ID';
ALTER TABLE `album` MODIFY COLUMN `name` VARCHAR(100) NOT NULL COMMENT '相册名称';
ALTER TABLE `album` MODIFY COLUMN `description` VARCHAR(255) DEFAULT NULL COMMENT '相册描述';
ALTER TABLE `album` MODIFY COLUMN `cover_url` VARCHAR(255) DEFAULT NULL COMMENT '封面图URL(取最新照片)';
ALTER TABLE `album` MODIFY COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `album` MODIFY COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- ============================================================
-- 修复 photo 表字段注释
-- ============================================================
ALTER TABLE `photo` MODIFY COLUMN `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '照片ID';
ALTER TABLE `photo` MODIFY COLUMN `album_id` BIGINT UNSIGNED NOT NULL COMMENT '所属相册ID';
ALTER TABLE `photo` MODIFY COLUMN `user_id` BIGINT UNSIGNED NOT NULL COMMENT '上传者用户ID';
ALTER TABLE `photo` MODIFY COLUMN `url` VARCHAR(255) NOT NULL COMMENT '原图URL';
ALTER TABLE `photo` MODIFY COLUMN `thumbnail_url` VARCHAR(255) DEFAULT NULL COMMENT '缩略图URL(可选)';
ALTER TABLE `photo` MODIFY COLUMN `description` VARCHAR(255) DEFAULT NULL COMMENT '照片描述';
ALTER TABLE `photo` MODIFY COLUMN `taken_at` DATETIME DEFAULT NULL COMMENT '拍摄时间';
ALTER TABLE `photo` MODIFY COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间';

-- ============================================================
-- 修复 anniversary 表字段注释
-- ============================================================
ALTER TABLE `anniversary` MODIFY COLUMN `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '纪念日ID';
ALTER TABLE `anniversary` MODIFY COLUMN `couple_id` BIGINT UNSIGNED NOT NULL COMMENT '所属情侣关系ID';
ALTER TABLE `anniversary` MODIFY COLUMN `name` VARCHAR(100) NOT NULL COMMENT '纪念日名称,如"第一次旅行"';
ALTER TABLE `anniversary` MODIFY COLUMN `anniversary_date` DATE NOT NULL COMMENT '纪念日日期(月-日)';
ALTER TABLE `anniversary` MODIFY COLUMN `is_start` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否"在一起"纪念日(1=是,自动创建不可删)';
ALTER TABLE `anniversary` MODIFY COLUMN `remind_days` INT NOT NULL DEFAULT 0 COMMENT '提前提醒天数(预留)';
ALTER TABLE `anniversary` MODIFY COLUMN `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

-- ============================================================
-- 验证修复结果
-- ============================================================
SHOW FULL COLUMNS FROM `user`;
SHOW TABLE STATUS WHERE Name = 'user';