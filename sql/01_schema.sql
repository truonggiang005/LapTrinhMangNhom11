-- =====================================================================
-- HỆ THỐNG THI TRẮC NGHIỆM TRỰC TUYẾN ĐA SERVER (ĐỀ 13 - NHÓM 11)
-- SCRIPT TẠO LƯỢC ĐỒ CƠ SỞ DỮ LIỆU CHUNG (COMMON DATABASE SCHEMA)
-- Tương thích: MySQL 8.0+ / MariaDB 10.5+
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `exam_system_db` 
CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `exam_system_db`;

-- Tắt kiểm tra khóa ngoại tạm thời để xóa/tạo lại nếu cần
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

-- =====================================================================
-- 1. BẢNG QUẢN LÝ PHIÊN BẢN LƯỢC ĐỒ (schema_version)
-- Giúp 4 Server đối soát tính đồng nhất về lược đồ CSDL khi khởi động
-- =====================================================================
CREATE TABLE `schema_version` (
    `version` VARCHAR(50) NOT NULL COMMENT 'Phiên bản lược đồ, vd: 1.0.0',
    `description` VARCHAR(255) NOT NULL COMMENT 'Mô tả thay đổi',
    `applied_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Thời điểm áp dụng',
    `applied_by` VARCHAR(100) DEFAULT 'SYSTEM' COMMENT 'Người hoặc server áp dụng',
    PRIMARY KEY (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Quản lý phiên bản CSDL giữa 4 Server';

-- =====================================================================
-- 2. BẢNG TÀI KHOẢN NGƯỜI DÙNG (users)
-- Dùng cho xác thực (Sinh viên, Giảng viên, Quản trị viên)
-- Mật khẩu băm chuẩn BCrypt
-- =====================================================================
CREATE TABLE `users` (
    `id` VARCHAR(36) NOT NULL COMMENT 'UUID người dùng',
    `username` VARCHAR(50) NOT NULL COMMENT 'Mã đăng nhập / mã SV / mã GV',
    `password_hash` VARCHAR(255) NOT NULL COMMENT 'Mật khẩu băm BCrypt',
    `full_name` VARCHAR(100) NOT NULL COMMENT 'Họ và tên',
    `email` VARCHAR(100) DEFAULT NULL COMMENT 'Email liên hệ',
    `role` ENUM('STUDENT', 'LECTURER', 'ADMIN') NOT NULL DEFAULT 'STUDENT' COMMENT 'Vai trò người dùng',
    `class_name` VARCHAR(50) DEFAULT NULL COMMENT 'Lớp sinh hoạt (đối với sinh viên)',
    `status` ENUM('ACTIVE', 'LOCKED') NOT NULL DEFAULT 'ACTIVE' COMMENT 'Trạng thái tài khoản',
    `failed_login_count` INT NOT NULL DEFAULT 0 COMMENT 'Số lần đăng nhập sai liên tiếp',
    `locked_until` TIMESTAMP NULL DEFAULT NULL COMMENT 'Khóa tạm thời đến thời điểm này nếu sai quá 5 lần',
    `version` INT NOT NULL DEFAULT 1 COMMENT 'Phiên bản bản ghi phục vụ giải quyết xung đột đồng bộ',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_users_username` (`username`),
    KEY `idx_users_role` (`role`),
    KEY `idx_users_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Tài khoản người dùng trong hệ thống';

-- =====================================================================
-- 3. BẢNG NGÂN HÀNG CÂU HỎI (questions)
-- Giảng viên quản lý; hỗ trợ xóa mềm (is_deleted); kiểm soát phiên bản
-- =====================================================================
CREATE TABLE `questions` (
    `id` VARCHAR(36) NOT NULL COMMENT 'UUID câu hỏi',
    `subject` VARCHAR(100) NOT NULL COMMENT 'Môn học (vd: Lập trình mạng)',
    `chapter` INT NOT NULL DEFAULT 1 COMMENT 'Chương / bài học',
    `difficulty` ENUM('EASY', 'MEDIUM', 'HARD') NOT NULL DEFAULT 'MEDIUM' COMMENT 'Độ khó câu hỏi',
    `content` TEXT NOT NULL COMMENT 'Nội dung câu hỏi',
    `is_deleted` BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Xóa mềm để tránh hỏng đề thi / bài thi cũ',
    `version` INT NOT NULL DEFAULT 1 COMMENT 'Phiên bản (optimistic locking / đồng bộ)',
    `created_by` VARCHAR(36) DEFAULT NULL COMMENT 'ID Giảng viên tạo câu hỏi',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_questions_filter` (`subject`, `chapter`, `difficulty`, `is_deleted`),
    KEY `idx_questions_created_by` (`created_by`),
    CONSTRAINT `fk_questions_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Ngân hàng câu hỏi trắc nghiệm';

-- =====================================================================
-- 4. BẢNG CÁC PHƯƠNG ÁN LỰA CHỌN (question_options)
-- Mỗi câu hỏi có từ 2 đến 6 phương án, chỉ duy nhất 1 đáp án đúng
-- =====================================================================
CREATE TABLE `question_options` (
    `id` VARCHAR(36) NOT NULL COMMENT 'UUID phương án',
    `question_id` VARCHAR(36) NOT NULL COMMENT 'Mã câu hỏi cha',
    `option_key` VARCHAR(10) NOT NULL COMMENT 'Ký tự hiển thị: A, B, C, D, ...',
    `content` TEXT NOT NULL COMMENT 'Nội dung phương án',
    `is_correct` BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Phương án đúng (TRUE) hay sai (FALSE)',
    `order_num` INT NOT NULL DEFAULT 1 COMMENT 'Thứ tự hiển thị gốc',
    PRIMARY KEY (`id`),
    KEY `idx_options_question` (`question_id`),
    CONSTRAINT `fk_options_question` FOREIGN KEY (`question_id`) REFERENCES `questions` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Các phương án lựa chọn của câu hỏi';

-- =====================================================================
-- 5. BẢNG KỲ THI / CA THI (exams)
-- Quản lý ca thi, thời gian mở/đóng, thời lượng làm bài, chế độ xem điểm
-- =====================================================================
CREATE TABLE `exams` (
    `id` VARCHAR(36) NOT NULL COMMENT 'UUID ca thi',
    `title` VARCHAR(200) NOT NULL COMMENT 'Tên ca thi',
    `subject` VARCHAR(100) NOT NULL COMMENT 'Tên môn thi',
    `duration_minutes` INT NOT NULL COMMENT 'Thời lượng làm bài (phút)',
    `start_time` TIMESTAMP NOT NULL COMMENT 'Thời điểm mở ca thi',
    `end_time` TIMESTAMP NOT NULL COMMENT 'Thời điểm đóng ca thi',
    `exam_type` ENUM('FIXED', 'RANDOM_STRUCTURE') NOT NULL DEFAULT 'FIXED' COMMENT 'Loại đề: FIXED (cố định) hoặc RANDOM_STRUCTURE (theo ma trận)',
    `structure_config` JSON DEFAULT NULL COMMENT 'Cấu hình ma trận đề JSON nếu exam_type là RANDOM_STRUCTURE',
    `total_questions` INT NOT NULL DEFAULT 0 COMMENT 'Tổng số câu hỏi của đề',
    `show_score_type` ENUM('IMMEDIATE', 'AFTER_EXAM_CLOSED', 'NEVER') NOT NULL DEFAULT 'IMMEDIATE' COMMENT 'Chế độ công bố điểm cho sinh viên',
    `status` ENUM('NOT_OPEN', 'OPEN', 'CLOSED', 'FINALIZED') NOT NULL DEFAULT 'NOT_OPEN' COMMENT 'Trạng thái ca thi',
    `version` INT NOT NULL DEFAULT 1 COMMENT 'Phiên bản ca thi',
    `created_by` VARCHAR(36) DEFAULT NULL COMMENT 'ID Giảng viên tạo ca thi',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_exams_status_time` (`status`, `start_time`, `end_time`),
    KEY `idx_exams_created_by` (`created_by`),
    CONSTRAINT `fk_exams_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Thông tin ca thi trắc nghiệm';

-- =====================================================================
-- 6. BẢNG CÂU HỎI TRONG ĐỀ THI (exam_questions)
-- Danh sách câu hỏi được gán cho đề thi cố định (hoặc ngân hàng câu chọn lọc)
-- =====================================================================
CREATE TABLE `exam_questions` (
    `id` VARCHAR(36) NOT NULL COMMENT 'UUID bản ghi',
    `exam_id` VARCHAR(36) NOT NULL COMMENT 'UUID ca thi',
    `question_id` VARCHAR(36) NOT NULL COMMENT 'UUID câu hỏi',
    `question_order` INT NOT NULL DEFAULT 1 COMMENT 'Thứ tự câu hỏi trong đề gốc',
    `points` DECIMAL(5,2) NOT NULL DEFAULT 1.00 COMMENT 'Điểm số của câu hỏi',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_exam_question` (`exam_id`, `question_id`),
    KEY `idx_exam_questions_exam` (`exam_id`),
    KEY `idx_exam_questions_question` (`question_id`),
    CONSTRAINT `fk_exam_questions_exam` FOREIGN KEY (`exam_id`) REFERENCES `exams` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_exam_questions_question` FOREIGN KEY (`question_id`) REFERENCES `questions` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Liên kết câu hỏi với đề thi';

-- =====================================================================
-- 7. BẢNG PHÂN CÔNG SINH VIÊN VÀO CA THI (exam_assignments)
-- Xác định sinh viên nào được phép tham gia ca thi nào
-- =====================================================================
CREATE TABLE `exam_assignments` (
    `id` VARCHAR(36) NOT NULL COMMENT 'UUID bản ghi',
    `exam_id` VARCHAR(36) NOT NULL COMMENT 'UUID ca thi',
    `student_id` VARCHAR(36) NOT NULL COMMENT 'UUID sinh viên (users.id)',
    `assigned_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `version` INT NOT NULL DEFAULT 1,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_exam_assignment` (`student_id`, `exam_id`),
    KEY `idx_exam_assignments_student_exam` (`student_id`, `exam_id`),
    KEY `idx_exam_assignments_exam` (`exam_id`),
    CONSTRAINT `fk_assignments_exam` FOREIGN KEY (`exam_id`) REFERENCES `exams` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_assignments_student` FOREIGN KEY (`student_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Danh sách sinh viên được phân vào ca thi';

-- =====================================================================
-- 8. BẢNG BÀI LÀM / LƯỢT THI CỦA SINH VIÊN (attempts)
-- Mỗi sinh viên chỉ có 1 attempt duy nhất cho mỗi ca thi (UNIQUE student_id, exam_id)
-- Có seed để xáo trộn câu hỏi và đáp án độc lập nhưng tái lập được trên cả 4 Server
-- =====================================================================
CREATE TABLE `attempts` (
    `id` VARCHAR(36) NOT NULL COMMENT 'UUID bài làm (attemptId)',
    `exam_id` VARCHAR(36) NOT NULL COMMENT 'UUID ca thi',
    `student_id` VARCHAR(36) NOT NULL COMMENT 'UUID sinh viên làm bài',
    `seed` BIGINT NOT NULL COMMENT 'Seed ngẫu nhiên dùng để trộn câu hỏi & phương án',
    `start_time` TIMESTAMP NOT NULL COMMENT 'Thời điểm bắt đầu làm bài',
    `deadline` TIMESTAMP NOT NULL COMMENT 'Thời hạn nộp (min(start_time + duration, end_time))',
    `submit_time` TIMESTAMP NULL DEFAULT NULL COMMENT 'Thời điểm nộp bài thực tế',
    `server_id` VARCHAR(20) NOT NULL COMMENT 'Server đang quản trị bài thi (SERVER_1, SERVER_2, ...)',
    `status` ENUM('IN_PROGRESS', 'SUBMITTED', 'EXPIRED') NOT NULL DEFAULT 'IN_PROGRESS' COMMENT 'Trạng thái bài thi',
    `score` DECIMAL(5,2) DEFAULT NULL COMMENT 'Điểm thi đã chấm',
    `correct_count` INT NOT NULL DEFAULT 0 COMMENT 'Số câu trả lời đúng',
    `total_questions` INT NOT NULL DEFAULT 0 COMMENT 'Tổng số câu trong bài',
    `version` INT NOT NULL DEFAULT 1 COMMENT 'Phiên bản bản ghi phục vụ đồng bộ / chuyển Server',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_attempt_student_exam` (`student_id`, `exam_id`),
    KEY `idx_attempts_student_exam` (`student_id`, `exam_id`),
    KEY `idx_attempts_exam_status` (`exam_id`, `status`),
    KEY `idx_attempts_server` (`server_id`),
    CONSTRAINT `fk_attempts_exam` FOREIGN KEY (`exam_id`) REFERENCES `exams` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_attempts_student` FOREIGN KEY (`student_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bài làm của sinh viên trong ca thi';

-- =====================================================================
-- 9. BẢNG CHI TIẾT ĐÁP ÁN BÀI LÀM (attempt_answers)
-- Lưu câu trả lời của sinh viên trong quá trình làm (tự lưu / cập nhật liên tục)
-- =====================================================================
CREATE TABLE `attempt_answers` (
    `id` VARCHAR(36) NOT NULL COMMENT 'UUID bản ghi',
    `attempt_id` VARCHAR(36) NOT NULL COMMENT 'UUID bài làm (attempts.id)',
    `question_id` VARCHAR(36) NOT NULL COMMENT 'UUID câu hỏi',
    `selected_option_id` VARCHAR(36) DEFAULT NULL COMMENT 'UUID phương án được chọn (NULL nếu chưa trả lời)',
    `is_marked` BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Đánh dấu xem lại câu hỏi này (flagged)',
    `is_correct` BOOLEAN DEFAULT NULL COMMENT 'Kết quả chấm câu này: TRUE/FALSE (NULL khi đang thi)',
    `version` INT NOT NULL DEFAULT 1 COMMENT 'Version tăng dần phục vụ giải quyết xung đột',
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_attempt_question` (`attempt_id`, `question_id`),
    KEY `idx_attempt_answers_attempt` (`attempt_id`),
    KEY `idx_attempt_answers_question` (`question_id`),
    CONSTRAINT `fk_answers_attempt` FOREIGN KEY (`attempt_id`) REFERENCES `attempts` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_answers_question` FOREIGN KEY (`question_id`) REFERENCES `questions` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_answers_option` FOREIGN KEY (`selected_option_id`) REFERENCES `question_options` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Chi tiết đáp án từng câu hỏi của bài thi';

-- =====================================================================
-- 10. BẢNG NHẬT KÝ KIỂM TOÁN AN TOÀN (audit_log)
-- Ghi vết mọi hành vi bảo mật (LOGIN_OK, LOGIN_FAIL, SUBMIT, START_EXAM, ...)
-- Chỉ thêm mới (Append-Only), không sửa xóa
-- =====================================================================
CREATE TABLE `audit_log` (
    `id` VARCHAR(36) NOT NULL COMMENT 'UUID bản ghi',
    `user_id` VARCHAR(36) DEFAULT NULL COMMENT 'UUID người dùng thực hiện (nếu có)',
    `action` VARCHAR(50) NOT NULL COMMENT 'Loại hành động: LOGIN_OK, LOGIN_FAIL, SUBMIT, START_EXAM, EXAM_CREATE...',
    `entity_type` VARCHAR(50) DEFAULT NULL COMMENT 'Thực thể tác động: USER, EXAM, ATTEMPT, SYSTEM...',
    `entity_id` VARCHAR(50) DEFAULT NULL COMMENT 'Mã thực thể bị tác động',
    `server_id` VARCHAR(20) NOT NULL COMMENT 'Server ghi nhận: SERVER_1..4',
    `ip_address` VARCHAR(50) DEFAULT NULL COMMENT 'IP máy khách gửi yêu cầu',
    `details` TEXT DEFAULT NULL COMMENT 'Chi tiết bổ sung (dạng JSON hoặc text mô tả)',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_audit_user_time` (`user_id`, `created_at`),
    KEY `idx_audit_action` (`action`),
    KEY `idx_audit_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Nhật ký kiểm toán hệ thống';

-- =====================================================================
-- 11. BẢNG NHẬT KÝ ĐỒNG BỘ DỮ LIỆU ĐA SERVER (sync_log)
-- Dùng cho Module Đồng bộ dữ liệu (Data Sync của Huy)
-- Ghi kèm trong cùng transaction với thay đổi nghiệp vụ
-- =====================================================================
CREATE TABLE `sync_log` (
    `id` VARCHAR(36) NOT NULL COMMENT 'UUID bản ghi nhật ký đồng bộ',
    `origin` VARCHAR(20) NOT NULL COMMENT 'Server gốc phát sinh thao tác (SERVER_1, SERVER_2, ...)',
    `seq` BIGINT NOT NULL COMMENT 'Số thứ tự tăng đơn điệu theo Server gốc',
    `op_id` VARCHAR(50) NOT NULL COMMENT 'Mã duy nhất = origin-seq (vd: SERVER_1-1001)',
    `table_name` VARCHAR(50) NOT NULL COMMENT 'Tên bảng bị thay đổi',
    `record_id` VARCHAR(50) NOT NULL COMMENT 'Khóa chính của bản ghi bị thay đổi',
    `op_type` VARCHAR(30) NOT NULL COMMENT 'Loại thao tác: INSERT, UPDATE, DELETE, ATTEMPT_CREATE, ANSWER_UPSERT, ATTEMPT_SUBMIT, EXAM_PACKAGE, ATTEMPT_REASSIGN, EXAM_FINALIZE',
    `version` INT NOT NULL DEFAULT 1 COMMENT 'Phiên bản của bản ghi tại thời điểm ghi',
    `payload_json` LONGTEXT NOT NULL COMMENT 'Nội dung dữ liệu thay đổi đóng gói JSON',
    `status` ENUM('PENDING', 'DONE') NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING: đang chờ gửi; DONE: đã đủ 3 server xác nhận',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sync_log_op_id` (`op_id`),
    UNIQUE KEY `uk_sync_log_origin_seq` (`origin`, `seq`),
    KEY `idx_sync_log_status_time` (`status`, `created_at`),
    KEY `idx_sync_log_table_record` (`table_name`, `record_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Nhật ký thao tác đồng bộ giữa 4 Server';

-- =====================================================================
-- 12. BẢNG GHI NHẬN THAO TÁC ĐỒNG BỘ ĐÃ ÁP DỤNG (sync_applied)
-- Tránh việc áp dụng lặp thao tác nhận được từ Server khác (Idempotency)
-- =====================================================================
CREATE TABLE `sync_applied` (
    `id` VARCHAR(36) NOT NULL COMMENT 'UUID bản ghi',
    `origin` VARCHAR(20) NOT NULL COMMENT 'Server gốc phát sinh thao tác',
    `seq` BIGINT NOT NULL COMMENT 'Số thứ tự của thao tác tại Server gốc',
    `op_id` VARCHAR(50) NOT NULL COMMENT 'Mã thao tác (origin-seq)',
    `applied_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sync_applied_op_id` (`op_id`),
    UNIQUE KEY `uk_sync_applied_origin_seq` (`origin`, `seq`),
    KEY `idx_sync_applied_origin_seq` (`origin`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Ghi nhận các thao tác đồng bộ đã áp dụng thành công';

-- =====================================================================
-- 13. BẢNG TRẠNG THÁI TIẾN TRÌNH ĐỒNG BỘ VỚI CÁC PEER (sync_peer_state)
-- Lưu trữ con trỏ đồng bộ giữa Server hiện tại và 3 Server còn lại
-- =====================================================================
CREATE TABLE `sync_peer_state` (
    `peer_id` VARCHAR(20) NOT NULL COMMENT 'Mã Server đối tác (SERVER_1, SERVER_2, SERVER_3, SERVER_4)',
    `last_received_seq` BIGINT NOT NULL DEFAULT 0 COMMENT 'Seq lớn nhất đã nhận liên tục từ Server này',
    `last_acked_seq` BIGINT NOT NULL DEFAULT 0 COMMENT 'Seq lớn nhất của mình mà Server này đã ACK xác nhận',
    `last_sync_time` TIMESTAMP NULL DEFAULT NULL COMMENT 'Thời điểm trao đổi đồng bộ gần nhất',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'Trạng thái kết nối peer: ACTIVE, DOWN, SYNCING',
    PRIMARY KEY (`peer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Trạng thái con trỏ đồng bộ với các Server anh em';

