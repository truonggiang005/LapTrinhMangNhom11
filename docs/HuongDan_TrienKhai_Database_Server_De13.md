HƯỚNG DẪN TRIỂN KHAI DATABASE SERVER RIÊNG

Đề tài 13: Xây dựng hệ thống theo mô hình đa Server cho phép truy xuất CSDL lớnNhóm 11 | Hệ thống thi trắc nghiệm trực tuyến

Cách 1: Chọn máy 1 thành viên làm DB Server chung (Qua Mạng LAN hoặc Radmin VPN) — Khuyên dùng

Một bạn trong nhóm (ví dụ máy bạn hoặc bạn Giang) sẽ chạy dịch vụ MySQL, còn 4 Server (chạy trên máy các bạn khác) sẽ kết nối tới địa chỉ IP của máy đó qua cổng 3306.

Bước 1: Mở quyền truy cập từ xa trên MySQL của máy chủ DB

Mở MySQL Command Line Client hoặc MySQL Workbench trên máy làm DB Server và chạy lệnh sau:



| -- Tạo tài khoản riêng cho các server kết nối từ xaCREATE USER 'exam_team'@'%' IDENTIFIED BY 'Team@123456';-- Cấp toàn quyền trên database exam_system_dbGRANT ALL PRIVILEGES ON exam_system_db.* TO 'exam_team'@'%';FLUSH PRIVILEGES; |
| --- |


(Ký tự '%' cho phép bất kỳ máy nào trong mạng đều có thể kết nối được).

Bước 2: Mở cổng 3306 trên Windows Firewall của máy chủ DB

Chạy lệnh PowerShell này bằng quyền Administrator trên máy chủ DB để cho phép các máy khác gửi dữ liệu vào cổng 3306:



| New-NetFirewallRule -DisplayName "MySQL Server Port 3306" -Direction Inbound -LocalPort 3306 -Protocol TCP -Action Allow |
| --- |


Bước 3: Xem địa chỉ IP của máy chủ DB

Mở CMD/PowerShell trên máy chủ DB và gõ:



| ipconfig |
| --- |


Tìm dòng IPv4 Address, ví dụ: 192.168.1.15.



| 💡 GỢI Ý (TIP): Kết nối khi làm việc Online tại nhàNếu các bạn không ngồi cùng mạng Wifi (làm việc online tại nhà):Cả nhóm hãy tải phần mềm miễn phí Radmin LAN (hoặc Hamachi), tạo một phòng chung (Network). Khi đó, Radmin LAN sẽ cấp cho máy chủ DB một IP ảo (ví dụ 26.x.x.x), các bạn ở nhà chỉ cần dùng IP ảo này là kết nối được như đang ngồi chung mạng LAN. |
| --- |


Bước 4: Cấu hình trên 4 Server của nhóm

Trong code kết nối CSDL của 4 Server, thay vì dùng localhost:3306, chỉ cần đổi thành:



| String url = "jdbc:mysql://192.168.1.15:3306/exam_system_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";String user = "exam_team";String pass = "Team@123456"; |
| --- |


Cách 2: Dùng Cloud Database miễn phí (Aiven / Clever Cloud / TiDB Cloud)

Nếu nhóm không muốn một bạn phải bật máy tính liên tục để làm server:

Đăng ký tài khoản miễn phí tại Aiven.io hoặc Clever-cloud.com (chọn MySQL).

Họ sẽ cấp sẵn cho bạn: Host, Port, Database, User, Password.

Chạy script sql/01_schema.sql và sql/02_sample_data.sql lên Cloud DB đó.

Cả 4 Server trên máy các bạn đều trỏ chung về URL đám mây này là có thể cùng làm việc bất kể ở trường hay ở nhà.

Cách 3: Viết một tiến trình Java "Custom DB Server" riêng bằng Socket TCP

Nếu thầy cô bộ môn yêu cầu không cho phép các Server kết nối trực tiếp JDBC mà bắt buộc phải tự viết một chương trình Java đóng vai trò Data Server (giao tiếp qua Socket TCP):

Bạn tạo thêm 1 project Java mang tên DatabaseService mở port (ví dụ 3307).

Các Server muốn truy vấn sẽ gửi gói tin TCP (ví dụ chuỗi JSON: {"action": "GET_USER", "username": "admin"}).

Data Server nhận socket, truy vấn MySQL local rồi gửi kết quả JSON trả lại cho các Server.

⚠️ Lưu ý rất quan trọng đối với Đề tài 13 của nhóm:



| Chiến lược tích hợp và phân chia khối lượng công việcTrong tài liệu Đề 13 và bảng phân chia công việc:• Huy phụ trách module nâng cao: "Đồng bộ dữ liệu giữa các Server (Data Sync / Replication)" (chiếm 25% điểm).Nếu nhóm chỉ dùng duy nhất 1 CSDL chung cố định:• Sẽ không còn dữ liệu để đồng bộ giữa các Server, khiến module nâng cao của Huy không có đất diễn khi báo cáo đồ án.Chiến lược khuyến nghị cho nhóm:1. Giai đoạn 1 (Hiện tại - Làm khung sườn): Cả nhóm cùng trỏ vào 1 DB chung như bạn vừa mong muốn để dễ dàng hoàn thiện chức năng đăng nhập, làm bài, chấm điểm, Load Balancer mà không bị vướng lỗi phân tán.2. Giai đoạn 2 (Tích hợp module đồng bộ của Huy): Tạo thành 4 database trên cùng máy chủ đó (exam_db_1, exam_db_2, exam_db_3, exam_db_4), mỗi Server dùng 1 database riêng, và cho module của Huy đồng bộ dữ liệu giữa 4 database này theo đúng bảng sync_log đã thiết kế sẵn. |
| --- |

