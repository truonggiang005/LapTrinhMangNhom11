-- =====================================================================
-- HỆ THỐNG THI TRẮC NGHIỆM TRỰC TUYẾN ĐA SERVER (ĐỀ 13 - NHÓM 11)
-- DỮ LIỆU KHỞI TẠO MẪU (SAMPLE DATA)
-- Mật khẩu mặc định cho tất cả tài khoản: 123456
-- BCrypt Hash: $2a$10$eACCYoNOHEqgkVE8aBG7NuS9V8KjJ3p2Cq9eQ3gT1vD8g7y4z0YTu
-- =====================================================================

USE `exam_system_db`;

-- ---------------------------------------------------------------------
-- 1. KHỞI TẠO PHIÊN BẢN LƯỢC ĐỒ
-- ---------------------------------------------------------------------
INSERT INTO `schema_version` (`version`, `description`, `applied_at`, `applied_by`)
VALUES ('1.0.0', 'Khoi tao luoc do CSDL chung cho 4 Server (De 13)', NOW(), 'NHOM_11')
ON DUPLICATE KEY UPDATE `description` = VALUES(`description`);

-- ---------------------------------------------------------------------
-- 2. TÀI KHOẢN NGƯỜI DÙNG (Admin, Giảng viên, Sinh viên)
-- ---------------------------------------------------------------------
INSERT INTO `users` (`id`, `username`, `password_hash`, `full_name`, `email`, `role`, `class_name`, `status`, `version`) VALUES
-- Quản trị viên
('u-admin-001', 'admin', '$2a$10$eACCYoNOHEqgkVE8aBG7NuS9V8KjJ3p2Cq9eQ3gT1vD8g7y4z0YTu', 'Quản trị viên Hệ thống', 'admin@ptit.edu.vn', 'ADMIN', NULL, 'ACTIVE', 1),

-- 4 Giảng viên (Đại diện 4 thành viên nhóm)
('u-gv-001', 'gv_giang', '$2a$10$eACCYoNOHEqgkVE8aBG7NuS9V8KjJ3p2Cq9eQ3gT1vD8g7y4z0YTu', 'ThS. Nguyễn Trường Giang', 'giang@ptit.edu.vn', 'LECTURER', NULL, 'ACTIVE', 1),
('u-gv-002', 'gv_huy', '$2a$10$eACCYoNOHEqgkVE8aBG7NuS9V8KjJ3p2Cq9eQ3gT1vD8g7y4z0YTu', 'ThS. Trần Quang Huy', 'huy@ptit.edu.vn', 'LECTURER', NULL, 'ACTIVE', 1),
('u-gv-003', 'gv_ngoc', '$2a$10$eACCYoNOHEqgkVE8aBG7NuS9V8KjJ3p2Cq9eQ3gT1vD8g7y4z0YTu', 'ThS. Lê Bảo Ngọc', 'ngoc@ptit.edu.vn', 'LECTURER', NULL, 'ACTIVE', 1),
('u-gv-004', 'gv_thuan', '$2a$10$eACCYoNOHEqgkVE8aBG7NuS9V8KjJ3p2Cq9eQ3gT1vD8g7y4z0YTu', 'ThS. Phạm Đức Thuận', 'thuan@ptit.edu.vn', 'LECTURER', NULL, 'ACTIVE', 1),

-- Sinh viên kiểm thử
('u-sv-001', 'sv_giang', '$2a$10$eACCYoNOHEqgkVE8aBG7NuS9V8KjJ3p2Cq9eQ3gT1vD8g7y4z0YTu', 'Nguyễn Trường Giang (SV)', 'sv_giang@student.ptit.edu.vn', 'STUDENT', 'D21CQCN01-B', 'ACTIVE', 1),
('u-sv-002', 'sv_huy', '$2a$10$eACCYoNOHEqgkVE8aBG7NuS9V8KjJ3p2Cq9eQ3gT1vD8g7y4z0YTu', 'Trần Quang Huy (SV)', 'sv_huy@student.ptit.edu.vn', 'STUDENT', 'D21CQCN01-B', 'ACTIVE', 1),
('u-sv-003', 'sv_ngoc', '$2a$10$eACCYoNOHEqgkVE8aBG7NuS9V8KjJ3p2Cq9eQ3gT1vD8g7y4z0YTu', 'Lê Bảo Ngọc (SV)', 'sv_ngoc@student.ptit.edu.vn', 'STUDENT', 'D21CQCN02-B', 'ACTIVE', 1),
('u-sv-004', 'sv_thuan', '$2a$10$eACCYoNOHEqgkVE8aBG7NuS9V8KjJ3p2Cq9eQ3gT1vD8g7y4z0YTu', 'Phạm Đức Thuận (SV)', 'sv_thuan@student.ptit.edu.vn', 'STUDENT', 'D21CQCN02-B', 'ACTIVE', 1),
('u-sv-005', 'sv_nam', '$2a$10$eACCYoNOHEqgkVE8aBG7NuS9V8KjJ3p2Cq9eQ3gT1vD8g7y4z0YTu', 'Nguyễn Văn Nam', 'nam@student.ptit.edu.vn', 'STUDENT', 'D21CQCN01-B', 'ACTIVE', 1),
('u-sv-006', 'sv_hoa', '$2a$10$eACCYoNOHEqgkVE8aBG7NuS9V8KjJ3p2Cq9eQ3gT1vD8g7y4z0YTu', 'Trần Thị Hoa', 'hoa@student.ptit.edu.vn', 'STUDENT', 'D21CQCN02-B', 'ACTIVE', 1)
ON DUPLICATE KEY UPDATE `full_name` = VALUES(`full_name`);

-- ---------------------------------------------------------------------
-- 3. NGÂN HÀNG CÂU HỎI (Môn: Lập trình mạng & Hệ điều hành)
-- ---------------------------------------------------------------------
INSERT INTO `questions` (`id`, `subject`, `chapter`, `difficulty`, `content`, `is_deleted`, `version`, `created_by`) VALUES
-- Lập trình mạng
('q-ltm-001', 'Lập trình mạng', 1, 'EASY', 'Giao thức nào hoạt động ở tầng Giao vận (Transport Layer) cung cấp kết nối tin cậy và có kiểm soát luồng?', FALSE, 1, 'u-gv-001'),
('q-ltm-002', 'Lập trình mạng', 1, 'EASY', 'Cổng (Port) mặc định của giao thức HTTP là bao nhiêu?', FALSE, 1, 'u-gv-001'),
('q-ltm-003', 'Lập trình mạng', 2, 'MEDIUM', 'Lớp nào trong gói java.net được dùng để tạo một Server Socket TCP phía máy chủ?', FALSE, 1, 'u-gv-002'),
('q-ltm-004', 'Lập trình mạng', 2, 'MEDIUM', 'Phương thức nào của ServerSocket được gọi để chờ và chấp nhận kết nối từ Client?', FALSE, 1, 'u-gv-002'),
('q-ltm-005', 'Lập trình mạng', 3, 'MEDIUM', 'Mô hình I/O nào trong Java cho phép một luồng xử lý đồng thời nhiều kênh kết nối mạng (multiplexing)?', FALSE, 1, 'u-gv-003'),
('q-ltm-006', 'Lập trình mạng', 3, 'HARD', 'Thuật toán Load Balancing nào phân phối tuần tự các yêu cầu tới danh sách server lần lượt theo vòng lặp?', FALSE, 1, 'u-gv-001'),
('q-ltm-007', 'Lập trình mạng', 4, 'HARD', 'Trong hệ thống phân tán, nguyên lý nào khẳng định không thể đồng thời đạt được cả 3 yếu tố: Tính nhất quán, Tính sẵn sàng và Khả năng chịu lỗi phân vùng mạng?', FALSE, 1, 'u-gv-002'),
('q-ltm-008', 'Lập trình mạng', 4, 'HARD', 'Kỹ thuật Heartbeat (PING-PONG) giữa Load Balancer và các Server có mục đích chính là gì?', FALSE, 1, 'u-gv-001'),
('q-ltm-009', 'Lập trình mạng', 2, 'EASY', 'Giao thức UDP khác TCP ở điểm mấu chốt nào?', FALSE, 1, 'u-gv-003'),
('q-ltm-010', 'Lập trình mạng', 3, 'MEDIUM', 'Cơ chế giải quyết xung đột kiểu "Last-Write-Wins" dựa vào thuộc tính nào của bản ghi dữ liệu?', FALSE, 1, 'u-gv-002'),

-- Hệ điều hành
('q-hdh-001', 'Hệ điều hành', 1, 'EASY', 'Thành phần nào của hệ điều hành chịu trách nhiệm phân chia thời gian CPU cho các tiến trình?', FALSE, 1, 'u-gv-004'),
('q-hdh-002', 'Hệ điều hành', 2, 'MEDIUM', 'Điều kiện nào sau đây KHÔNG PHẢI là 1 trong 4 điều kiện cần gây ra Bế tắc (Deadlock)?', FALSE, 1, 'u-gv-004'),
('q-hdh-003', 'Hệ điều hành', 2, 'MEDIUM', 'Mutex và Semaphore khác nhau cơ bản ở điểm nào?', FALSE, 1, 'u-gv-004'),
('q-hdh-004', 'Hệ điều hành', 3, 'EASY', 'Bộ nhớ ảo (Virtual Memory) được hiện thực chủ yếu thông qua kỹ thuật nào?', FALSE, 1, 'u-gv-004'),
('q-hdh-005', 'Hệ điều hành', 3, 'HARD', 'Thuật toán thay thế trang nào đạt tỷ lệ lỗi trang thấp nhất trên lý thuyết nhưng không khả thi trong thực tế?', FALSE, 1, 'u-gv-004')
ON DUPLICATE KEY UPDATE `content` = VALUES(`content`);

-- ---------------------------------------------------------------------
-- 4. PHƯƠNG ÁN LỰA CHỌN CỦA CÂU HỎI
-- ---------------------------------------------------------------------
INSERT INTO `question_options` (`id`, `question_id`, `option_key`, `content`, `is_correct`, `order_num`) VALUES
-- q-ltm-001
('opt-001-A', 'q-ltm-001', 'A', 'UDP (User Datagram Protocol)', FALSE, 1),
('opt-001-B', 'q-ltm-001', 'B', 'TCP (Transmission Control Protocol)', TRUE, 2),
('opt-001-C', 'q-ltm-001', 'C', 'IP (Internet Protocol)', FALSE, 3),
('opt-001-D', 'q-ltm-001', 'D', 'ICMP (Internet Control Message Protocol)', FALSE, 4),

-- q-ltm-002
('opt-002-A', 'q-ltm-002', 'A', '80', TRUE, 1),
('opt-002-B', 'q-ltm-002', 'B', '443', FALSE, 2),
('opt-002-C', 'q-ltm-002', 'C', '8080', FALSE, 3),
('opt-002-D', 'q-ltm-002', 'D', '21', FALSE, 4),

-- q-ltm-003
('opt-003-A', 'q-ltm-003', 'A', 'java.net.Socket', FALSE, 1),
('opt-003-B', 'q-ltm-003', 'B', 'java.net.ServerSocket', TRUE, 2),
('opt-003-C', 'q-ltm-003', 'C', 'java.net.DatagramSocket', FALSE, 3),
('opt-003-D', 'q-ltm-003', 'D', 'java.net.HttpURLConnection', FALSE, 4),

-- q-ltm-004
('opt-004-A', 'q-ltm-004', 'A', 'connect()', FALSE, 1),
('opt-004-B', 'q-ltm-004', 'B', 'bind()', FALSE, 2),
('opt-004-C', 'q-ltm-004', 'C', 'accept()', TRUE, 3),
('opt-004-D', 'q-ltm-004', 'D', 'listen()', FALSE, 4),

-- q-ltm-005
('opt-005-A', 'q-ltm-005', 'A', 'Java IO cổ điển (Blocking IO)', FALSE, 1),
('opt-005-B', 'q-ltm-005', 'B', 'Java NIO với Selector (Non-blocking IO)', TRUE, 2),
('opt-005-C', 'q-ltm-005', 'C', 'Java RMI', FALSE, 3),
('opt-005-D', 'q-ltm-005', 'D', 'Java Serialization', FALSE, 4),

-- q-ltm-006
('opt-006-A', 'q-ltm-006', 'A', 'Least Connections', FALSE, 1),
('opt-006-B', 'q-ltm-006', 'B', 'Round Robin', TRUE, 2),
('opt-006-C', 'q-ltm-006', 'C', 'IP Hash', FALSE, 3),
('opt-006-D', 'q-ltm-006', 'D', 'Randomized Weighted', FALSE, 4),

-- q-ltm-007
('opt-007-A', 'q-ltm-007', 'A', 'Định lý Bayes', FALSE, 1),
('opt-007-B', 'q-ltm-007', 'B', 'Định lý CAP (Brewer Theorem)', TRUE, 2),
('opt-007-C', 'q-ltm-007', 'C', 'Định lý Shannon', FALSE, 3),
('opt-007-D', 'q-ltm-007', 'D', 'Luật Moore', FALSE, 4),

-- q-ltm-008
('opt-008-A', 'q-ltm-008', 'A', 'Tăng tốc độ truyền dữ liệu của mạng', FALSE, 1),
('opt-008-B', 'q-ltm-008', 'B', 'Kiểm tra trạng thái sống còn (health check) và độ trễ của các Server', TRUE, 2),
('opt-008-C', 'q-ltm-008', 'C', 'Mã hóa gói tin bảo mật', FALSE, 3),
('opt-008-D', 'q-ltm-008', 'D', 'Lưu trữ đệm cơ sở dữ liệu', FALSE, 4),

-- q-ltm-009
('opt-009-A', 'q-ltm-009', 'A', 'UDP không thiết lập bắt tay 3 bước và không đảm bảo thứ tự gói tin', TRUE, 1),
('opt-009-B', 'q-ltm-009', 'B', 'UDP có tốc độ truyền chậm hơn TCP', FALSE, 2),
('opt-009-C', 'q-ltm-009', 'C', 'UDP chỉ truyền được dữ liệu dạng text', FALSE, 3),
('opt-009-D', 'q-ltm-009', 'D', 'UDP hoạt động ở tầng Ứng dụng', FALSE, 4),

-- q-ltm-010
('opt-010-A', 'q-ltm-010', 'A', 'Kích thước gói tin lớn hơn', FALSE, 1),
('opt-010-B', 'q-ltm-010', 'B', 'Thời điểm cập nhật (updated_at / timestamp) muộn hơn', TRUE, 2),
('opt-010-C', 'q-ltm-010', 'C', 'Độ ưu tiên của user ID', FALSE, 3),
('opt-010-D', 'q-ltm-010', 'D', 'Ngẫu nhiên', FALSE, 4),

-- q-hdh-001
('opt-h01-A', 'q-hdh-001', 'A', 'Bộ điều độ CPU (CPU Scheduler)', TRUE, 1),
('opt-h01-B', 'q-hdh-001', 'B', 'Bộ quản lý bộ nhớ (Memory Manager)', FALSE, 2),
('opt-h01-C', 'q-hdh-001', 'C', 'Trình điều khiển thiết bị (Device Driver)', FALSE, 3),
('opt-h01-D', 'q-hdh-001', 'D', 'Hệ thống tệp (File System)', FALSE, 4),

-- q-hdh-002
('opt-h02-A', 'q-hdh-002', 'A', 'Loại trừ tương hỗ (Mutual Exclusion)', FALSE, 1),
('opt-h02-B', 'q-hdh-002', 'B', 'Giữ và chờ (Hold and Wait)', FALSE, 2),
('opt-h02-C', 'q-hdh-002', 'C', 'Có quyền ưu tiên thu hồi tài nguyên (Preemption)', TRUE, 3),
('opt-h02-D', 'q-hdh-002', 'D', 'Chờ đợi vòng tròn (Circular Wait)', FALSE, 4),

-- q-hdh-003
('opt-h03-A', 'q-hdh-003', 'A', 'Mutex chỉ có trạng thái 0 hoặc 1, Semaphore có thể có giá trị đếm lớn hơn 1', TRUE, 1),
('opt-h03-B', 'q-hdh-003', 'B', 'Mutex chỉ dùng cho mạng, Semaphore dùng cho ổ cứng', FALSE, 2),
('opt-h03-C', 'q-hdh-003', 'C', 'Semaphore không thể tránh xung đột tài nguyên', FALSE, 3),
('opt-h03-D', 'q-hdh-003', 'D', 'Cả hai hoàn toàn giống hệt nhau', FALSE, 4),

-- q-hdh-004
('opt-h04-A', 'q-hdh-004', 'A', 'Phân trang theo yêu cầu (Demand Paging)', TRUE, 1),
('opt-h04-B', 'q-hdh-004', 'B', 'Định thời Round Robin', FALSE, 2),
('opt-h04-C', 'q-hdh-004', 'C', 'Nén dữ liệu ổ cứng', FALSE, 3),
('opt-h04-D', 'q-hdh-004', 'D', 'Đồng bộ hóa luồng', FALSE, 4),

-- q-hdh-005
('opt-h05-A', 'q-hdh-005', 'A', 'FIFO (First-In, First-Out)', FALSE, 1),
('opt-h05-B', 'q-hdh-005', 'B', 'LRU (Least Recently Used)', FALSE, 2),
('opt-h05-C', 'q-hdh-005', 'C', 'OPT (Optimal Page Replacement)', TRUE, 3),
('opt-h05-D', 'q-hdh-005', 'D', 'Clock Algorithm', FALSE, 4)
ON DUPLICATE KEY UPDATE `content` = VALUES(`content`);

-- ---------------------------------------------------------------------
-- 5. KỲ THI / CA THI MẪU (exams)
-- ---------------------------------------------------------------------
INSERT INTO `exams` (`id`, `title`, `subject`, `duration_minutes`, `start_time`, `end_time`, `exam_type`, `total_questions`, `show_score_type`, `status`, `version`, `created_by`) VALUES
('exam-ltm-01', 'Thi trắc nghiệm Lập trình mạng - Học kỳ 1', 'Lập trình mạng', 45, 
 DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 5 DAY), 'FIXED', 10, 'IMMEDIATE', 'OPEN', 1, 'u-gv-001'),

('exam-hdh-01', 'Kiểm tra giữa kỳ Hệ điều hành', 'Hệ điều hành', 30, 
 DATE_ADD(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 3 DAY), 'FIXED', 5, 'AFTER_EXAM_CLOSED', 'NOT_OPEN', 1, 'u-gv-004')
ON DUPLICATE KEY UPDATE `title` = VALUES(`title`);

-- ---------------------------------------------------------------------
-- 6. GÁN CÂU HỎI VÀO ĐỀ THI (exam_questions)
-- ---------------------------------------------------------------------
INSERT INTO `exam_questions` (`id`, `exam_id`, `question_id`, `question_order`, `points`) VALUES
('eq-ltm-01', 'exam-ltm-01', 'q-ltm-001', 1, 1.00),
('eq-ltm-02', 'exam-ltm-01', 'q-ltm-002', 2, 1.00),
('eq-ltm-03', 'exam-ltm-01', 'q-ltm-003', 3, 1.00),
('eq-ltm-04', 'exam-ltm-01', 'q-ltm-004', 4, 1.00),
('eq-ltm-05', 'exam-ltm-01', 'q-ltm-005', 5, 1.00),
('eq-ltm-06', 'exam-ltm-01', 'q-ltm-006', 6, 1.00),
('eq-ltm-07', 'exam-ltm-01', 'q-ltm-007', 7, 1.00),
('eq-ltm-08', 'exam-ltm-01', 'q-ltm-008', 8, 1.00),
('eq-ltm-09', 'exam-ltm-01', 'q-ltm-009', 9, 1.00),
('eq-ltm-10', 'exam-ltm-01', 'q-ltm-010', 10, 1.00),

-- Đề Hệ điều hành
('eq-hdh-01', 'exam-hdh-01', 'q-hdh-001', 1, 2.00),
('eq-hdh-02', 'exam-hdh-01', 'q-hdh-002', 2, 2.00),
('eq-hdh-03', 'exam-hdh-01', 'q-hdh-003', 3, 2.00),
('eq-hdh-04', 'exam-hdh-01', 'q-hdh-004', 4, 2.00),
('eq-hdh-05', 'exam-hdh-01', 'q-hdh-005', 5, 2.00)
ON DUPLICATE KEY UPDATE `points` = VALUES(`points`);

-- ---------------------------------------------------------------------
-- 7. PHÂN CÔNG SINH VIÊN VÀO CA THI (exam_assignments)
-- ---------------------------------------------------------------------
INSERT INTO `exam_assignments` (`id`, `exam_id`, `student_id`, `assigned_at`, `version`) VALUES
('asg-ltm-01', 'exam-ltm-01', 'u-sv-001', NOW(), 1),
('asg-ltm-02', 'exam-ltm-01', 'u-sv-002', NOW(), 1),
('asg-ltm-03', 'exam-ltm-01', 'u-sv-003', NOW(), 1),
('asg-ltm-04', 'exam-ltm-01', 'u-sv-004', NOW(), 1),
('asg-ltm-05', 'exam-ltm-01', 'u-sv-005', NOW(), 1),
('asg-ltm-06', 'exam-ltm-01', 'u-sv-006', NOW(), 1),

('asg-hdh-01', 'exam-hdh-01', 'u-sv-001', NOW(), 1),
('asg-hdh-02', 'exam-hdh-01', 'u-sv-002', NOW(), 1),
('asg-hdh-03', 'exam-hdh-01', 'u-sv-003', NOW(), 1),
('asg-hdh-04', 'exam-hdh-01', 'u-sv-004', NOW(), 1)
ON DUPLICATE KEY UPDATE `assigned_at` = VALUES(`assigned_at`);

-- ---------------------------------------------------------------------
-- 8. TRẠNG THÁI KHỞI ĐẦU ĐỒNG BỘ 4 SERVER (sync_peer_state)
-- ---------------------------------------------------------------------
INSERT INTO `sync_peer_state` (`peer_id`, `last_received_seq`, `last_acked_seq`, `last_sync_time`, `status`) VALUES
('SERVER_1', 0, 0, NOW(), 'ACTIVE'),
('SERVER_2', 0, 0, NOW(), 'ACTIVE'),
('SERVER_3', 0, 0, NOW(), 'ACTIVE'),
('SERVER_4', 0, 0, NOW(), 'ACTIVE')
ON DUPLICATE KEY UPDATE `status` = VALUES(`status`);

