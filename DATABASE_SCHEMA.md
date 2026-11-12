# TÀI LIỆU CƠ SỞ DỮ LIỆU CHUNG (COMMON DATABASE SCHEMA)
## Đề tài 13: Hệ thống thi trắc nghiệm trực tuyến theo mô hình đa Server

- **Tên CSDL**: `exam_system_db`
- **Hệ quản trị CSDL**: MySQL 8.0+ (hoặc MariaDB 10.5+)
- **Mã hóa ký tự**: `utf8mb4` / `utf8mb4_unicode_ci`
- **Cổng kết nối**: `3306` (mặc định)
- **Tài khoản dùng thử**: Mật khẩu mặc định là `123456` (đã băm bằng BCrypt).

---

## 1. Danh sách 13 bảng trong CSDL

| STT | Tên bảng | Chức năng chính |
| :---: | :--- | :--- |
| 1 | `schema_version` | Quản lý phiên bản lược đồ CSDL giữa 4 Server, bảo đảm tính nhất quán khi khởi động. |
| 2 | `users` | Lưu trữ tài khoản người dùng: Quản trị viên (`ADMIN`), Giảng viên (`LECTURER`), Sinh viên (`STUDENT`). Hỗ trợ băm mật khẩu BCrypt, khóa tài khoản khi nhập sai quá 5 lần. |
| 3 | `questions` | Ngân hàng câu hỏi trắc nghiệm theo môn học, chương, độ khó (`EASY`, `MEDIUM`, `HARD`). Hỗ trợ xóa mềm (`is_deleted`) và kiểm soát phiên bản (`version`). |
| 4 | `question_options` | Danh sách phương án trả lời cho mỗi câu hỏi (2 đến 6 phương án), đánh dấu phương án đúng duy nhất (`is_correct`). |
| 5 | `exams` | Thông tin ca thi: môn học, thời lượng, thời gian mở/đóng, trạng thái (`NOT_OPEN`, `OPEN`, `CLOSED`, `FINALIZED`), chế độ xem điểm. |
| 6 | `exam_questions` | Danh sách các câu hỏi thuộc đề thi và điểm số cho từng câu. |
| 7 | `exam_assignments` | Danh sách sinh viên được phân công vào ca thi. Đảm bảo mỗi sinh viên chỉ được phân 1 lần (`UNIQUE student_id, exam_id`). |
| 8 | `attempts` | Bài làm của sinh viên trong ca thi. Mỗi sinh viên chỉ có 1 lượt làm duy nhất (`UNIQUE student_id, exam_id`). Lưu `seed` để xáo trộn câu hỏi ngẫu nhiên nhưng đồng nhất giữa các server. |
| 9 | `attempt_answers` | Chi tiết câu trả lời của sinh viên cho từng câu hỏi, lưu cờ đánh dấu câu hỏi (`is_marked`), tự động lưu định kỳ và cập nhật điểm khi chấm. |
| 10 | `audit_log` | Nhật ký bảo mật và kiểm toán (Append-only): `LOGIN_OK`, `LOGIN_FAIL`, `START_EXAM`, `SAVE_ANSWERS`, `SUBMIT`,... |
| 11 | `sync_log` | Nhật ký đồng bộ dữ liệu đa Server: ghi kèm trong cùng transaction với dữ liệu nghiệp vụ, phục vụ gửi bản tin `SYNC_BATCH` qua cổng 910N. |
| 12 | `sync_applied` | Danh sách các thao tác đồng bộ đã áp dụng từ các Server khác, ngăn chặn việc áp dụng trùng lặp (Idempotency). |
| 13 | `sync_peer_state` | Lưu trạng thái con trỏ đồng bộ (`last_received_seq`, `last_acked_seq`) giữa server hiện tại với các server đối tác (`SERVER_1`, `SERVER_2`, `SERVER_3`, `SERVER_4`). |

---

## 2. Thông tin tài khoản mẫu sẵn sàng kiểm thử

Tất cả các tài khoản dưới đây đều có mật khẩu: **`123456`**

### Quản trị viên:
- `admin` - Quản trị viên Hệ thống

### Giảng viên:
- `gv_giang` - ThS. Nguyễn Trường Giang
- `gv_huy` - ThS. Trần Quang Huy
- `gv_ngoc` - ThS. Lê Bảo Ngọc
- `gv_thuan` - ThS. Phạm Đức Thuận

### Sinh viên:
- `sv_giang` - Nguyễn Trường Giang (Lớp: D21CQCN01-B)
- `sv_huy` - Trần Quang Huy (Lớp: D21CQCN01-B)
- `sv_ngoc` - Lê Bảo Ngọc (Lớp: D21CQCN02-B)
- `sv_thuan` - Phạm Đức Thuận (Lớp: D21CQCN02-B)
- `sv_nam` - Nguyễn Văn Nam (Lớp: D21CQCN01-B)
- `sv_hoa` - Trần Thị Hoa (Lớp: D21CQCN02-B)

---

## 3. Cách chạy lại hoặc khởi tạo trên máy các thành viên

Các file SQL được đặt tại thư mục `sql/`:
- [`sql/01_schema.sql`](file:///c:/Users/Admin/Downloads/LapTrinhMangNhom11/sql/01_schema.sql): Script tạo Database và 13 bảng với đầy đủ khóa chính, khóa ngoại, chỉ mục (Index).
- [`sql/02_sample_data.sql`](file:///c:/Users/Admin/Downloads/LapTrinhMangNhom11/sql/02_sample_data.sql): Script nạp dữ liệu mẫu (Tài khoản, Ngân hàng câu hỏi, Đề thi, Phân ca thi).
- [`sql/init_database.sql`](file:///c:/Users/Admin/Downloads/LapTrinhMangNhom11/sql/init_database.sql): Script thực thi toàn bộ 2 file trên trong một lệnh.

Lệnh chạy bằng MySQL CLI:
```bash
mysql -u root -p < sql/01_schema.sql
mysql -u root -p < sql/02_sample_data.sql
```
Hoặc mở file trong **MySQL Workbench** / **DBeaver** / **Navicat** và thực thi.

