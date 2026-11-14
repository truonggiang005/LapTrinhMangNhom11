-- =====================================================================
-- HỆ THỐNG THI TRẮC NGHIỆM TRỰC TUYẾN ĐA SERVER (ĐỀ 13 - NHÓM 11)
-- SCRIPT KHỞI TẠO 4 CƠ SỞ DỮ LIỆU RIÊNG BIỆT CHO 4 SERVER
-- exam_db_1 (Server 1), exam_db_2 (Server 2), exam_db_3 (Server 3), exam_db_4 (Server 4)
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `exam_db_1` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `exam_db_2` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `exam_db_3` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `exam_db_4` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Cấp quyền người dùng nếu cần
-- CREATE USER IF NOT EXISTS 'exam_team'@'%' IDENTIFIED BY 'Team@123456';
-- GRANT ALL PRIVILEGES ON exam_db_1.* TO 'exam_team'@'%';
-- GRANT ALL PRIVILEGES ON exam_db_2.* TO 'exam_team'@'%';
-- GRANT ALL PRIVILEGES ON exam_db_3.* TO 'exam_team'@'%';
-- GRANT ALL PRIVILEGES ON exam_db_4.* TO 'exam_team'@'%';
-- FLUSH PRIVILEGES;

-- LƯU Ý: Để nạp lược đồ và dữ liệu mẫu vào cả 4 database, bạn có thể chạy:
-- mysql -u root -p exam_db_1 < db/schema.sql
-- mysql -u root -p exam_db_1 < db/sample_data.sql
-- (Tương tự cho exam_db_2, exam_db_3, exam_db_4)
