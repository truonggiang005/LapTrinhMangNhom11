import os

bcrypt_hash = '$2a$10$eACCYoNOHEqgkVE8aBG7NuS9V8KjJ3p2Cq9eQ3gT1vD8g7y4z0YTu'

lines = []
lines.append('-- =====================================================================')
lines.append('-- DỮ LIỆU MẪU KHỞI TẠO (db/sample_data.sql)')
lines.append('-- 1 Admin, 2 Giảng viên, 20 Sinh viên, 50 Câu hỏi, 1 Ca thi (20 câu)')
lines.append('-- Mật khẩu mặc định tất cả tài khoản: 123456')
lines.append('-- =====================================================================\n')

# 1. Schema version
lines.append("""INSERT INTO `schema_version` (`version`, `description`, `applied_at`, `applied_by`)
VALUES ('1.0.0', 'Khoi tao luoc do CSDL chung cho 4 Server', NOW(), 'SYSTEM')
ON DUPLICATE KEY UPDATE `description` = VALUES(`description`);
""")

# 2. Users
lines.append('-- 2. TÀI KHOẢN NGƯỜI DÙNG')
lines.append('INSERT INTO `users` (`id`, `username`, `password_hash`, `full_name`, `email`, `role`, `class_name`, `status`, `version`) VALUES')
lines.append(f"('u-admin-001', 'admin', '{bcrypt_hash}', 'Quản trị viên Hệ thống', 'admin@ptit.edu.vn', 'ADMIN', NULL, 'ACTIVE', 1),")
lines.append(f"('u-gv-001', 'gv_giang', '{bcrypt_hash}', 'ThS. Nguyễn Trường Giang', 'giang@ptit.edu.vn', 'LECTURER', NULL, 'ACTIVE', 1),")
lines.append(f"('u-gv-002', 'gv_huy', '{bcrypt_hash}', 'ThS. Trần Quang Huy', 'huy@ptit.edu.vn', 'LECTURER', NULL, 'ACTIVE', 1),")

for i in range(1, 21):
    sid = f"u-sv-{i:03d}"
    uname = f"sv_{i:02d}"
    cname = 'D21CQCN01-B' if i <= 10 else 'D21CQCN02-B'
    comma = ',' if i < 20 else ''
    lines.append(f"('{sid}', '{uname}', '{bcrypt_hash}', 'Sinh viên {i:02d}', '{uname}@student.ptit.edu.vn', 'STUDENT', '{cname}', 'ACTIVE', 1){comma}")
lines.append('ON DUPLICATE KEY UPDATE `full_name` = VALUES(`full_name`);\n')

# 3. 50 Questions & Options
lines.append('-- 3. NGÂN HÀNG 50 CÂU HỎI & PHƯƠNG ÁN')
questions_data = [
    ("Giao thức nào cung cấp dịch vụ truyền dữ liệu tin cậy hướng kết nối?", "TCP", "UDP", "IP", "ICMP", "A"),
    ("Cổng (Port) mặc định cho dịch vụ truyền tải web bảo mật HTTPS là?", "80", "443", "8080", "22", "B"),
    ("Lớp nào trong java.net dùng để lắng nghe kết nối từ Client phía Server TCP?", "Socket", "ServerSocket", "DatagramSocket", "HttpURLConnection", "B"),
    ("Phương thức nào của ServerSocket trả về một Socket kết nối với Client?", "connect()", "listen()", "accept()", "bind()", "C"),
    ("Mô hình kiến trúc nào cho phép gửi thông điệp không cần duy trì kết nối liên tục?", "TCP Socket", "UDP Datagram", "HTTP 1.0", "FTP", "B"),
    ("Trong kiến trúc đa luồng xử lý socket, giải pháp nào kiểm soát số lượng luồng tối đa?", "Thread.sleep()", "ThreadPoolExecutor", "Object.wait()", "ForkJoinPool", "B"),
    ("Thuật toán cân bằng tải nào phân phối yêu cầu tới server có ít kết nối nhất?", "Round Robin", "Least Connections", "IP Hash", "Random", "B"),
    ("Mục đích của việc gửi bản tin PING/PONG (Heartbeat) định kỳ giữa LB và Server là gì?", "Đo nhiệt độ CPU", "Phát hiện sớm Server gặp sự cố (Down)", "Nén dữ liệu", "Đồng bộ đồng hồ", "B"),
    ("Thuật toán HMAC-SHA256 sử dụng yếu tố nào để đảm bảo tính toàn vẹn và xác thực?", "Khóa bí mật dùng chung", "Khóa công khai", "Chứng chỉ SSL", "Địa chỉ IP", "A"),
    ("Tại sao cần xáo trộn câu hỏi và đáp án bằng Random(seed) thay vì Random()?", "Để đề thi dễ hơn", "Để tái lập cùng một thứ tự đề khi khôi phục bài", "Để mã hóa dữ liệu", "Để tiết kiệm bộ nhớ", "B"),
    ("Kỹ thuật Idempotent trong xử lý mạng có ý nghĩa gì?", "Gửi nhiều lần cùng yêu cầu không đổi kết quả", "Gửi yêu cầu nhanh gấp đôi", "Mã hóa hai lần", "Tự động nén dữ liệu", "A"),
    ("Trong mô hình ServerSocket, gọi accept() trong luồng chính khi chưa tạo luồng con sẽ dẫn đến?", "Tắc nghẽn kết nối của client tiếp theo", "Sập JVM lập tức", "Client bị từ chối kết nối", "Rò rỉ bộ nhớ RAM", "A"),
    ("Để đọc dữ liệu văn bản theo từng dòng từ Socket InputStream, ta nên bọc qua lớp nào?", "BufferedReader & InputStreamReader", "DataInputStream", "ObjectInputStream", "ByteArrayInputStream", "A"),
    ("Ký tự kết thúc nào thường được dùng để phân định các dòng thông điệp JSON thuần?", "\\0 (NULL)", "\\n (Newline)", "\\t (Tab)", "EOF", "B"),
    ("Bất biến attempts có UNIQUE(student_id, exam_id) nhằm ngăn chặn điều gì?", "Sinh viên thi lại nhiều lần trong cùng ca thi", "Giảng viên xem điểm", "Lỗi tràn bộ nhớ", "Xung đột mạng", "A"),
    ("Độ trễ mạng RTT (Round Trip Time) được đo bằng công thức nào khi gửi PING/PONG?", "t_nhan + t_gui", "t_nhan - t_gui", "(t_nhan - t_gui) / 2", "t_gui * 2", "B"),
    ("Độ lệch đồng hồ offset giữa Client và Server được ước lượng theo công thức nào?", "serverTime - (t_gui + t_nhan)/2", "serverTime + t_nhan", "t_nhan - serverTime", "(serverTime + t_gui)/2", "A"),
    ("Chuỗi băm BCrypt bao gồm những thành phần nào?", "Version, Cost, Salt, Hash", "Chỉ mã Hash", "Public key và Private key", "Salt và MD5", "A"),
    ("Cơ chế nào giúp hệ thống đa Server đồng bộ các thay đổi CSDL theo thứ tự tăng dần?", "Sequence Number đơn điệu (Seq)", "Random UUID", "Địa chỉ MAC", "Số cổng socket", "A"),
    ("Bảng sync_applied lưu thông tin gì để đảm bảo tính Idempotent của đồng bộ?", "origin và seq đã áp dụng", "Mật khẩu server", "Nội dung câu hỏi", "Địa chỉ IP client", "A"),
    ("Khi hai Server cùng cập nhật câu hỏi, quy tắc nào thường giải quyết xung đột?", "Version cao hơn thắng (Optimistic Locking)", "Xóa cả hai", "Báo lỗi sập hệ thống", "Server nào tạo sau thắng", "A"),
    ("Trong giao thức TCP, cờ FIN và ACK trong quá trình đóng kết nối gọi là?", "Bắt tay 3 bước", "Bắt tay 4 bước (Four-way Handshake)", "Keep-Alive", "RST flag", "B"),
    ("Kích thước tối đa của một UDP Datagram là bao nhiêu byte?", "1500 bytes", "65535 bytes", "4096 bytes", "Không giới hạn", "B"),
    ("Giao thức nào cho phép phân giải tên miền thành địa chỉ IP?", "DHCP", "DNS", "ARP", "NAT", "B"),
    ("Địa chỉ loopback mặc định của máy cục bộ IPv4 là gì?", "192.168.1.1", "10.0.0.1", "127.0.0.1", "255.255.255.255", "C"),
    ("Cổng dịch vụ MySQL mặc định là bao nhiêu?", "1433", "3306", "5432", "1521", "B"),
    ("Trong lập trình Socket Java, phương thức setSoTimeout() dùng để làm gì?", "Giới hạn thời gian kết nối của server", "Đặt thời gian timeout tối đa khi đọc từ socket", "Giới hạn tốc độ mạng", "Tự động đóng socket", "B"),
    ("Khi tải START_EXAM vượt quá 50, Load Balancer xử lý như thế nào?", "Từ chối ngay lập tức", "Xếp hàng FIFO và trả phản hồi QUEUED", "Tạo thêm 50 luồng mới", "Chuyển sang UDP", "B"),
    ("Trong giao thức HTTP/1.1, cờ Keep-Alive có tác dụng gì?", "Tái sử dụng kết nối TCP cho nhiều yêu cầu", "Nén ảnh tự động", "Tăng bảo mật dữ liệu", "Chuyển đổi giao thức sang WebSocket", "A"),
    ("Cơ chế kết nối lại với thời gian chờ tăng theo số mũ (Exponential Backoff) nhằm mục đích?", "Tránh làm tê liệt server sau sự cố", "Tăng tốc độ kết nối", "Tiết kiệm băng thông", "Đảm bảo bảo mật", "A"),
    ("Mã trạng thái HTTP nào biểu thị tài nguyên không tìm thấy?", "400", "403", "404", "500", "C"),
    ("Mã lỗi 429 biểu thị trạng thái nào của hệ thống?", "Hết hạn token", "Hệ thống đang bận (BUSY / Too Many Requests)", "Không có quyền truy cập", "Dữ liệu xung đột", "B"),
    ("Trọng số của Server 1 trong Load Balancer được thiết lập là 1 (so với 2 của các server khác) vì lý do gì?", "Server 1 cấu hình yếu hơn", "Server 1 kiêm nhiệm tiến trình Load Balancer", "Server 1 chỉ lưu CSDL", "Server 1 chỉ nhận sinh viên nữ", "B"),
    ("Để đảm bảo an toàn luồng trong biến đếm số kết nối, lớp Java nào phù hợp nhất?", "Integer", "AtomicInteger", "Long", "volatile int", "B"),
    ("Bộ đệm HikariCP trong ứng dụng có vai trò gì?", "Quản lý bộ nhớ RAM", "Quản lý hồ bơi kết nối CSDL (Connection Pool)", "Quản lý session người dùng", "Mã hóa dữ liệu CSDL", "B"),
    ("Mô hình nào giúp tách biệt giữa giao diện đồ họa và xử lý mạng không gây đơ (freeze)?", "Gọi socket trực tiếp trên luồng JavaFX Application", "Chạy tác vụ mạng trên luồng nền (Worker/Task)", "Dùng vòng lặp while(true)", "Dùng Thread.sleep() trên UI", "B"),
    ("Phương thức nào đưa công việc cập nhật UI trở lại luồng giao diện JavaFX an toàn?", "Platform.runLater()", "Thread.yield()", "SwingUtilities.invokeAndWait()", "Task.cancel()", "A"),
    ("Trong bảng attempts, trường status có thể nhận các giá trị nào?", "ACTIVE, INACTIVE", "IN_PROGRESS, SUBMITTED, EXPIRED", "PENDING, SUCCESS, FAILED", "NEW, OPEN, CLOSED", "B"),
    ("Phương án xóa mềm (Soft Delete) trong bảng questions sử dụng cột nào?", "status", "is_deleted", "deleted_at", "active", "B"),
    ("Để chấm điểm bài thi trắc nghiệm có tính xác định, công thức chuẩn là?", "(số câu đúng / tổng số câu) * thang điểm", "số câu đúng * 1.5", "tổng số câu - số câu sai", "ngẫu nhiên trong khoảng 5-10", "A"),
    ("Dung sai thời gian nộp bài muộn sau deadline trong đặc tả hệ thống là bao nhiêu giây?", "1 giây", "5 giây", "15 giây", "30 giây", "B"),
    ("Chu kỳ tự động quét các bài thi quá hạn để tự động nộp và chấm điểm trên Server là?", "1 giây", "5 giây", "10 giây", "60 giây", "C"),
    ("Thời hạn hiệu lực của Token đăng nhập HMAC là bao nhiêu giờ?", "1 giờ", "2 giờ", "4 giờ", "24 giờ", "C"),
    ("Khi sinh viên mất mạng lúc làm bài, Client sẽ ứng xử như thế nào?", "Đóng ứng dụng ngay", "Cho làm bài tiếp và lưu cục bộ, tự động kết nối lại", "Hủy toàn bộ kết quả bài thi", "Chấm điểm 0 ngay", "B"),
    ("Khi Server quản lý bài thi bị DOWN, Load Balancer thực hiện cơ chế gì?", "Failover chuyển Client sang Server UP khác", "Hủy bỏ ca thi", "Khởi động lại toàn bộ mạng", "Chờ người dùng tự đổi cổng", "A"),
    ("Thông điệp nào được gửi từ LB tới Server mới để khôi phục bài làm của sinh viên?", "START_EXAM", "RESUME_REQUEST", "LOGIN_RETRY", "SYNC_RECOVER", "B"),
    ("Trong trường hợp Server mới chưa có dữ liệu bài làm, thông tin được tái lập từ đâu?", "Vé bài làm (ExamTicket) có chữ ký HMAC do Client gửi", "Hỏi toàn bộ sinh viên trong phòng", "Dữ liệu mặc định rỗng", "Không khôi phục được", "A"),
    ("Gói tin đồng bộ giữa các Server được gửi theo lô (Batch) với giới hạn nào?", "Tối đa 10 thao tác hoặc 1 giây", "Tối đa 100 thao tác hoặc 200 mili giây", "Tối đa 500 thao tác", "Gửi từng thao tác rời", "B"),
    ("Cổng TCP dùng cho Load Balancer đón Client trong hệ thống là cổng nào?", "8080", "9000", "9001", "3306", "B"),
    ("Các cổng nghiệp vụ của 4 Server lần lượt là các cổng nào?", "8001 - 8004", "9001 - 9004", "9101 - 9104", "7001 - 7004", "B")
]

lines.append('INSERT INTO `questions` (`id`, `subject`, `chapter`, `difficulty`, `content`, `is_deleted`, `version`, `created_by`) VALUES')
for idx, (content, opt_a, opt_b, opt_c, opt_d, correct_opt) in enumerate(questions_data, 1):
    qid = f"q-{idx:03d}"
    chap = (idx % 5) + 1
    diff = 'EASY' if idx % 3 == 1 else ('MEDIUM' if idx % 3 == 2 else 'HARD')
    creator = 'u-gv-001' if idx % 2 == 1 else 'u-gv-002'
    comma = ',' if idx < len(questions_data) else ''
    escaped_content = content.replace("'", "\\'")
    lines.append(f"('{qid}', 'Lập trình mạng', {chap}, '{diff}', '{escaped_content}', FALSE, 1, '{creator}'){comma}")
lines.append('ON DUPLICATE KEY UPDATE `content` = VALUES(`content`);\n')

lines.append('-- 4. CÁC PHƯƠNG ÁN LỰA CHỌN CHO 50 CÂU HỎI')
lines.append('INSERT INTO `question_options` (`id`, `question_id`, `option_key`, `content`, `is_correct`, `order_num`) VALUES')
opt_rows = []
for idx, (content, opt_a, opt_b, opt_c, opt_d, correct_opt) in enumerate(questions_data, 1):
    qid = f"q-{idx:03d}"
    opts = [('A', opt_a), ('B', opt_b), ('C', opt_c), ('D', opt_d)]
    for order, (key, opt_text) in enumerate(opts, 1):
        opt_id = f"opt-{idx:03d}-{key}"
        is_correct = 'TRUE' if key == correct_opt else 'FALSE'
        escaped_opt = opt_text.replace("'", "\\'")
        opt_rows.append(f"('{opt_id}', '{qid}', '{key}', '{escaped_opt}', {is_correct}, {order})")

lines.append(',\n'.join(opt_rows) + '\nON DUPLICATE KEY UPDATE `content` = VALUES(`content`);\n')

# 5. 1 Exam
lines.append('-- 5. CA THI MẪU (OPEN - SẴN SÀNG THI)')
lines.append("""INSERT INTO `exams` (`id`, `title`, `subject`, `duration_minutes`, `start_time`, `end_time`, `exam_type`, `total_questions`, `max_score`, `show_score_type`, `status`, `version`, `created_by`)
VALUES ('exam-001', 'Thi kết thúc học phần Lập trình mạng', 'Lập trình mạng', 45, 
        DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 3 HOUR), 
        'FIXED', 20, 10.00, 'IMMEDIATE', 'OPEN', 1, 'u-gv-001')
ON DUPLICATE KEY UPDATE `title` = VALUES(`title`);
""")

# 6. Exam questions (20 questions for exam-001)
lines.append('-- 6. GÁN 20 CÂU HỎI VÀO ĐỀ THI')
lines.append('INSERT INTO `exam_questions` (`id`, `exam_id`, `question_id`, `question_order`, `points`) VALUES')
eq_rows = []
for order in range(1, 21):
    eq_id = f"eq-001-{order:02d}"
    qid = f"q-{order:03d}"
    eq_rows.append(f"('{eq_id}', 'exam-001', '{qid}', {order}, 0.50)")
lines.append(',\n'.join(eq_rows) + '\nON DUPLICATE KEY UPDATE `points` = VALUES(`points`);\n')

# 7. Exam assignments (assign 20 students to exam-001)
lines.append('-- 7. PHÂN CÔNG 20 SINH VIÊN VÀO CA THI')
lines.append('INSERT INTO `exam_assignments` (`id`, `exam_id`, `student_id`, `version`) VALUES')
ea_rows = []
for i in range(1, 21):
    ea_id = f"ea-001-{i:03d}"
    sid = f"u-sv-{i:03d}"
    ea_rows.append(f"('{ea_id}', 'exam-001', '{sid}', 1)")
lines.append(',\n'.join(ea_rows) + '\nON DUPLICATE KEY UPDATE `version` = VALUES(`version`);\n')

os.makedirs('db', exist_ok=True)
with open('db/sample_data.sql', 'w', encoding='utf-8') as f:
    f.write('\n'.join(lines))

print(f"Successfully generated db/sample_data.sql with {len(questions_data)} questions, {len(opt_rows)} options, 1 exam, 20 assignments.")
