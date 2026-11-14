-- =====================================================================
-- HỆ THỐNG THI TRẮC NGHIỆM TRỰC TUYẾN ĐA SERVER (ĐỀ 13 - NHÓM 11)
-- LƯỢC ĐỒ CƠ SỞ DỮ LIỆU CHUNG (13 BẢNG)
-- Tương thích: MySQL 8.0+ / MariaDB 10.5+
-- =====================================================================

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `sync_peer_state`;
DROP TABLE IF EXISTS `sync_applied`;
DROP TABLE IF EXISTS `sync_log`;
DROP TABLE IF EXISTS `audit_log`;
DROP TABLE IF EXISTS `attempt_answers`;
DROP TABLE IF EXISTS `attempts`;
DROP TABLE IF EXISTS `exam_assignments`;
DROP TABLE IF EXISTS `exam_questions`;
DROP TABLE IF EXISTS `question_options`;
DROP TABLE IF EXISTS `questions`;
DROP TABLE IF EXISTS `exams`;
DROP TABLE IF EXISTS `users`;
DROP TABLE IF EXISTS `schema_version`;

SET FOREIGN_KEY_CHECKS = 1;

-- 1. BẢNG PHIÊN BẢN LƯỢC ĐỒ
CREATE TABLE `schema_version` (
    `version` VARCHAR(50) NOT NULL,
    `description` VARCHAR(255) NOT NULL,
    `applied_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `applied_by` VARCHAR(100) DEFAULT 'SYSTEM',
    PRIMARY KEY (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. BẢNG TÀI KHOẢN NGƯỜI DÙNG
CREATE TABLE `users` (
    `id` VARCHAR(36) NOT NULL,
    `username` VARCHAR(50) NOT NULL,
    `password_hash` VARCHAR(255) NOT NULL,
    `full_name` VARCHAR(100) NOT NULL,
    `email` VARCHAR(100) DEFAULT NULL,
    `role` ENUM('STUDENT', 'LECTURER', 'ADMIN') NOT NULL DEFAULT 'STUDENT',
    `class_name` VARCHAR(50) DEFAULT NULL,
    `status` ENUM('ACTIVE', 'LOCKED') NOT NULL DEFAULT 'ACTIVE',
    `failed_login_count` INT NOT NULL DEFAULT 0,
    `locked_until` TIMESTAMP NULL DEFAULT NULL,
    `version` INT NOT NULL DEFAULT 1,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_users_username` (`username`),
    KEY `idx_users_role` (`role`),
    KEY `idx_users_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. BẢNG NGÂN HÀNG CÂU HỎI (Hỗ trợ xóa mềm: is_deleted, phiên bản: version)
CREATE TABLE `questions` (
    `id` VARCHAR(36) NOT NULL,
    `subject` VARCHAR(100) NOT NULL,
    `chapter` INT NOT NULL DEFAULT 1,
    `difficulty` ENUM('EASY', 'MEDIUM', 'HARD') NOT NULL DEFAULT 'MEDIUM',
    `content` TEXT NOT NULL,
    `is_deleted` BOOLEAN NOT NULL DEFAULT FALSE,
    `version` INT NOT NULL DEFAULT 1,
    `created_by` VARCHAR(36) DEFAULT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_questions_filter` (`subject`, `chapter`, `difficulty`, `is_deleted`),
    KEY `idx_questions_created_by` (`created_by`),
    CONSTRAINT `fk_questions_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. BẢNG CÁC PHƯƠNG ÁN LỰA CHỌN CÂU HỎI
CREATE TABLE `question_options` (
    `id` VARCHAR(36) NOT NULL,
    `question_id` VARCHAR(36) NOT NULL,
    `option_key` VARCHAR(10) NOT NULL,
    `content` TEXT NOT NULL,
    `is_correct` BOOLEAN NOT NULL DEFAULT FALSE,
    `order_num` INT NOT NULL DEFAULT 1,
    PRIMARY KEY (`id`),
    KEY `idx_options_question` (`question_id`),
    CONSTRAINT `fk_options_question` FOREIGN KEY (`question_id`) REFERENCES `questions` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. BẢNG CA THI / ĐỀ THI
CREATE TABLE `exams` (
    `id` VARCHAR(36) NOT NULL,
    `title` VARCHAR(200) NOT NULL,
    `subject` VARCHAR(100) NOT NULL,
    `duration_minutes` INT NOT NULL,
    `start_time` TIMESTAMP NOT NULL,
    `end_time` TIMESTAMP NOT NULL,
    `exam_type` ENUM('FIXED', 'RANDOM_STRUCTURE') NOT NULL DEFAULT 'FIXED',
    `structure_config` JSON DEFAULT NULL,
    `total_questions` INT NOT NULL DEFAULT 0,
    `max_score` DECIMAL(5,2) NOT NULL DEFAULT 10.00,
    `show_score_type` ENUM('IMMEDIATE', 'AFTER_EXAM_CLOSED', 'NEVER') NOT NULL DEFAULT 'IMMEDIATE',
    `status` ENUM('NOT_OPEN', 'OPEN', 'CLOSED', 'FINALIZED') NOT NULL DEFAULT 'NOT_OPEN',
    `version` INT NOT NULL DEFAULT 1,
    `created_by` VARCHAR(36) DEFAULT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_exams_status_time` (`status`, `start_time`, `end_time`),
    KEY `idx_exams_created_by` (`created_by`),
    CONSTRAINT `fk_exams_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. BẢNG CÂU HỎI TRONG ĐỀ THI
CREATE TABLE `exam_questions` (
    `id` VARCHAR(36) NOT NULL,
    `exam_id` VARCHAR(36) NOT NULL,
    `question_id` VARCHAR(36) NOT NULL,
    `question_order` INT NOT NULL DEFAULT 1,
    `points` DECIMAL(5,2) NOT NULL DEFAULT 1.00,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_exam_question` (`exam_id`, `question_id`),
    KEY `idx_exam_questions_exam` (`exam_id`),
    KEY `idx_exam_questions_question` (`question_id`),
    CONSTRAINT `fk_exam_questions_exam` FOREIGN KEY (`exam_id`) REFERENCES `exams` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_exam_questions_question` FOREIGN KEY (`question_id`) REFERENCES `questions` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. BẢNG PHÂN CÔNG SINH VIÊN VÀO CA THI
CREATE TABLE `exam_assignments` (
    `id` VARCHAR(36) NOT NULL,
    `exam_id` VARCHAR(36) NOT NULL,
    `student_id` VARCHAR(36) NOT NULL,
    `assigned_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `version` INT NOT NULL DEFAULT 1,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_exam_assignment` (`student_id`, `exam_id`),
    KEY `idx_exam_assignments_student_exam` (`student_id`, `exam_id`),
    KEY `idx_exam_assignments_exam` (`exam_id`),
    CONSTRAINT `fk_assignments_exam` FOREIGN KEY (`exam_id`) REFERENCES `exams` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_assignments_student` FOREIGN KEY (`student_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. BẢNG BÀI LÀM (attempts) - BẤT BIẾN UNIQUE (student_id, exam_id)
CREATE TABLE `attempts` (
    `id` VARCHAR(36) NOT NULL,
    `exam_id` VARCHAR(36) NOT NULL,
    `student_id` VARCHAR(36) NOT NULL,
    `seed` BIGINT NOT NULL,
    `start_time` TIMESTAMP NOT NULL,
    `deadline` TIMESTAMP NOT NULL,
    `submit_time` TIMESTAMP NULL DEFAULT NULL,
    `is_late` BOOLEAN NOT NULL DEFAULT FALSE,
    `server_id` VARCHAR(20) NOT NULL,
    `status` ENUM('IN_PROGRESS', 'SUBMITTED', 'EXPIRED') NOT NULL DEFAULT 'IN_PROGRESS',
    `score` DECIMAL(5,2) DEFAULT NULL,
    `correct_count` INT NOT NULL DEFAULT 0,
    `total_questions` INT NOT NULL DEFAULT 0,
    `version` INT NOT NULL DEFAULT 1,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_attempt_student_exam` (`student_id`, `exam_id`),
    KEY `idx_attempts_student_exam` (`student_id`, `exam_id`),
    KEY `idx_attempts_exam_status` (`exam_id`, `status`),
    KEY `idx_attempts_server` (`server_id`),
    CONSTRAINT `fk_attempts_exam` FOREIGN KEY (`exam_id`) REFERENCES `exams` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_attempts_student` FOREIGN KEY (`student_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. BẢNG CHI TIẾT ĐÁP ÁN BÀI LÀM
CREATE TABLE `attempt_answers` (
    `id` VARCHAR(36) NOT NULL,
    `attempt_id` VARCHAR(36) NOT NULL,
    `question_id` VARCHAR(36) NOT NULL,
    `selected_option_id` VARCHAR(36) DEFAULT NULL,
    `is_marked` BOOLEAN NOT NULL DEFAULT FALSE,
    `is_correct` BOOLEAN DEFAULT NULL,
    `seq` BIGINT NOT NULL DEFAULT 0,
    `version` INT NOT NULL DEFAULT 1,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_attempt_question` (`attempt_id`, `question_id`),
    KEY `idx_attempt_answers_attempt` (`attempt_id`),
    KEY `idx_attempt_answers_question` (`question_id`),
    CONSTRAINT `fk_answers_attempt` FOREIGN KEY (`attempt_id`) REFERENCES `attempts` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_answers_question` FOREIGN KEY (`question_id`) REFERENCES `questions` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_answers_option` FOREIGN KEY (`selected_option_id`) REFERENCES `question_options` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. BẢNG NHẬT KÝ KIỂM TOÁN (Append-only)
CREATE TABLE `audit_log` (
    `id` VARCHAR(36) NOT NULL,
    `user_id` VARCHAR(36) DEFAULT NULL,
    `action` VARCHAR(50) NOT NULL,
    `entity_type` VARCHAR(50) DEFAULT NULL,
    `entity_id` VARCHAR(50) DEFAULT NULL,
    `server_id` VARCHAR(20) NOT NULL,
    `ip_address` VARCHAR(50) DEFAULT NULL,
    `details` TEXT DEFAULT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_audit_user_time` (`user_id`, `created_at`),
    KEY `idx_audit_action` (`action`),
    KEY `idx_audit_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 11. BẢNG NHẬT KÝ ĐỒNG BỘ ĐA SERVER (sync_log)
CREATE TABLE `sync_log` (
    `id` VARCHAR(36) NOT NULL,
    `origin` VARCHAR(20) NOT NULL,
    `seq` BIGINT NOT NULL,
    `op_id` VARCHAR(50) NOT NULL,
    `table_name` VARCHAR(50) NOT NULL,
    `record_id` VARCHAR(50) NOT NULL,
    `op_type` VARCHAR(30) NOT NULL,
    `version` INT NOT NULL DEFAULT 1,
    `payload_json` LONGTEXT NOT NULL,
    `status` ENUM('PENDING', 'DONE') NOT NULL DEFAULT 'PENDING',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sync_log_op_id` (`op_id`),
    UNIQUE KEY `uk_sync_log_origin_seq` (`origin`, `seq`),
    KEY `idx_sync_log_status_time` (`status`, `created_at`),
    KEY `idx_sync_log_table_record` (`table_name`, `record_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 12. BẢNG GHI NHẬN THAO TÁC ĐỒNG BỘ ĐÃ ÁP DỤNG (sync_applied)
CREATE TABLE `sync_applied` (
    `id` VARCHAR(36) NOT NULL,
    `origin` VARCHAR(20) NOT NULL,
    `seq` BIGINT NOT NULL,
    `op_id` VARCHAR(50) NOT NULL,
    `applied_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sync_applied_op_id` (`op_id`),
    UNIQUE KEY `uk_sync_applied_origin_seq` (`origin`, `seq`),
    KEY `idx_sync_applied_origin_seq` (`origin`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 13. BẢNG TRẠNG THÁI TIẾN TRÌNH ĐỒNG BỘ (sync_peer_state)
CREATE TABLE `sync_peer_state` (
    `peer_id` VARCHAR(20) NOT NULL,
    `last_received_seq` BIGINT NOT NULL DEFAULT 0,
    `last_acked_seq` BIGINT NOT NULL DEFAULT 0,
    `last_sync_time` TIMESTAMP NULL DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (`peer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 14. BẢNG YÊU CẦU THI LẠI (retake_requests)
CREATE TABLE IF NOT EXISTS `retake_requests` (
    `id` VARCHAR(36) NOT NULL,
    `exam_id` VARCHAR(36) NOT NULL,
    `student_id` VARCHAR(36) NOT NULL,
    `student_code` VARCHAR(50) NOT NULL,
    `student_name` VARCHAR(100) DEFAULT NULL,
    `reason` TEXT DEFAULT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_retake_exam` (`exam_id`),
    KEY `idx_retake_student` (`student_id`),
    KEY `idx_retake_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
