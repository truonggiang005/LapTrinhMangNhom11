LUỒNG CHẠY CHƯƠNG TRÌNH VÀ NHIỆM VỤ CỦA TỪNG THÀNH VIÊN

Đề 13 – Ứng dụng 3: Hệ thống thi trắc nghiệm trực tuyến theo mô hình đa Server

1. PHẠM VI VÀ CÁCH ĐỌC TÀI LIỆU

Tài liệu này mô tả từng bước chương trình chạy khi hệ thống hoạt động, từ lúc khởi động đến lúc chốt kết quả ca thi, và chỉ rõ trong mỗi bước thành viên nào làm việc gì. Tài liệu đi kèm bản phân chia công việc và kế hoạch tiến độ, dùng cùng tên Server, tên CSDL và vai trò: Giang phụ trách Server 1, CSDL 1 và Load Balancer; Huy phụ trách Server 2, CSDL 2 và module đồng bộ; Ngọc phụ trách Server 3, CSDL 3 và Client; Thuận phụ trách Server 4, CSDL 4, giao diện JavaFX và kiểm thử.

Mỗi luồng gồm một đoạn tóm tắt (mục đích, điều kiện bắt đầu, kết quả) và một bảng theo từng bước. Cột Diễn biến mô tả điều chương trình làm. Bốn cột còn lại nêu việc cụ thể của từng thành viên ở bước đó. Dấu gạch ngang nghĩa là thành viên đó không có việc trực tiếp ở bước này. Với các bước do Server xử lý, cả 4 thành viên cùng cài đặt phần chung trên Server của mình; cột của mỗi người ghi câu “Cài đặt phần xử lý này trên Server N của mình (phần chung)” và nêu thêm phần riêng nếu có.

Các con số, số cổng và tên thông điệp trong tài liệu là đề xuất để cả nhóm thống nhất ở Tuần 1; khi nhóm chốt giá trị khác thì sửa lại cho khớp.

1.1. Các thành phần của hệ thống



| Thành phần | Vai trò | Địa chỉ / cổng (đề xuất) | Người phụ trách chính |
| --- | --- | --- | --- |
| Giao diện JavaFX | Màn hình đăng nhập, làm bài, kết quả, giảng viên, quản trị. Chỉ gọi các hàm của thư viện Client, không tự mở socket. | Chạy trên máy người dùng | Thuận |
| Thư viện Client (ExamClient) | Mở kết nối TCP, đóng gói và đọc thông điệp JSON, đa luồng (gửi/nhận, đồng hồ, tự lưu, nhận thông báo), tự kết nối lại, lưu đệm cục bộ. | Kết nối tới Load Balancer, cổng 9000 | Ngọc |
| Load Balancer | Cổng duy nhất cho Client; chọn Server; giữ liên kết bài làm với Server (sticky); kiểm tra sức khỏe Server; chuyển Client sang Server khác khi lỗi; giữ sổ đăng ký phiên; giới hạn kết nối và hàng đợi đầu ca thi. | Chạy trong tiến trình Server 1, cổng 9000 | Giang |
| Server 1, 2, 3, 4 | Xử lý nghiệp vụ thi: xác thực, vào thi, lưu đáp án, nộp và chấm, truy vấn, quản lý câu hỏi và đề. Mỗi Server có đủ chức năng. | Cổng nghiệp vụ 9001, 9002, 9003, 9004 (chỉ Load Balancer gọi tới) | Giang, Huy, Ngọc, Thuận (mỗi người một Server) |
| CSDL 1, 2, 3, 4 | Mỗi Server dùng một CSDL riêng, cùng lược đồ chung. | Cổng mặc định của hệ quản trị CSDL | Giang, Huy, Ngọc, Thuận |
| Module đồng bộ | Nhúng trong mỗi Server; ghi nhật ký thao tác, gửi tới 3 Server còn lại, áp dụng thao tác nhận được, xử lý xung đột, đồng bộ bù, kiểm tra nhất quán. | Cổng đồng bộ 9101, 9102, 9103, 9104 | Huy (cài đặt); cả 4 người tích hợp vào Server của mình |
| Công cụ kiểm thử | Sinh dữ liệu mẫu, giả lập nhiều Client (bot thi), kịch bản gây lỗi, đo hiệu suất. | Chạy trên máy kiểm thử | Thuận |


1.2. Quy ước thông điệp Client – Server (Ngọc chủ trì đặc tả)

Mỗi thông điệp là một dòng JSON mã hóa UTF-8, kết thúc bằng ký tự xuống dòng. Thông điệp yêu cầu và thông điệp phản hồi có các trường sau.



| Trường | Có trong | Ý nghĩa |
| --- | --- | --- |
| type | Yêu cầu, phản hồi | Tên thông điệp, ví dụ LOGIN, START_EXAM, SAVE_ANSWERS, SUBMIT. |
| requestId | Yêu cầu, phản hồi | Mã duy nhất do Client sinh ra; phản hồi mang lại đúng mã này để ghép cặp. Với SUBMIT, mã được đặt cố định theo bài làm để gửi lại không tạo bài nộp thứ hai. |
| token | Yêu cầu | Mã phiên do Server cấp khi đăng nhập, gồm mã người dùng, vai trò, thời hạn và chữ ký HMAC bằng khóa chung đặt trong tệp cấu hình; Server nào cũng kiểm tra được mà không cần hỏi Server đã cấp. |
| attemptId | Yêu cầu liên quan bài làm | Mã bài làm (UUID). |
| seq | SAVE_ANSWERS | Số thứ tự tăng dần của gói lưu đáp án, để Server bỏ gói cũ đến muộn. |
| payload | Yêu cầu, phản hồi | Dữ liệu riêng của từng thông điệp. |
| status, code, message | Phản hồi | status là OK hoặc ERROR; code là mã lỗi (xem mục 14); message là câu mô tả. |
| serverTime | Phản hồi | Giờ của Server tại lúc trả lời (mili giây), để Client hiệu chỉnh đồng hồ. |
| retryAfter | Phản hồi BUSY | Số giây Client nên chờ trước khi thử lại. |


1.3. Tham số vận hành (đề xuất)



| Tham số | Giá trị | Ý nghĩa | Người sở hữu |
| --- | --- | --- | --- |
| Chu kỳ heartbeat (PING từ Load Balancer tới Server) | 1 giây | Kiểm tra Server còn sống và lấy tải, giờ Server. | Giang |
| Số lần mất heartbeat liên tiếp để coi Server lỗi | 3 lần | Phát hiện lỗi trong khoảng 3 đến 4 giây. | Giang |
| Số lần PONG liên tiếp để coi Server hoạt động lại | 3 lần | Tránh Server chập chờn bị bật tắt liên tục. Sau khi hoạt động lại, trọng số tăng dần trong 30 giây. | Giang |
| Trọng số cân bằng tải | Server 1: 1; Server 2, 3, 4: 2 | Server 1 vừa là Load Balancer vừa xử lý nghiệp vụ nên nhận ít hơn. | Giang |
| Giới hạn kết nối đồng thời trên Load Balancer | 500 | Vượt ngưỡng thì trả BUSY. | Giang |
| Số yêu cầu START_EXAM xử lý song song tối đa | 50 | Vượt ngưỡng thì xếp hàng FIFO, trả QUEUED kèm vị trí. | Giang |
| Chu kỳ tự lưu đáp án của Client | 20 giây, hoặc khi chuyển câu nếu có thay đổi | Giảm mất dữ liệu khi sự cố. | Ngọc |
| Thời gian chờ phản hồi của Client | 10 giây | Quá hạn thì thử lại hoặc coi là mất kết nối. | Ngọc |
| Chu kỳ thử kết nối lại của Client | 1, 2, 4, 8, 16, rồi 30 giây | Tăng dần để không làm quá tải Load Balancer. | Ngọc |
| Thời gian dung sai nộp muộn | 5 giây sau hạn nộp | Bù trễ mạng khi Client nộp sát giờ. | Cả nhóm (Server thực thi) |
| Chu kỳ quét bài hết hạn trên mỗi Server | 10 giây | Tự nộp và chấm bài mà Client không còn nộp được. | Cả nhóm (Server thực thi) |
| Chu kỳ và kích thước lô gửi đồng bộ | 200 mili giây, tối đa 100 thao tác | Cân bằng độ trễ đồng bộ và số lần gửi. | Huy |
| Chu kỳ kiểm tra nhất quán giữa 4 CSDL | 5 phút | So số bản ghi và mã băm từng bảng. | Huy |
| Thời hạn token đăng nhập | 4 giờ | Đủ dài hơn một ca thi. | Cả nhóm (Server thực thi) |


2. LUỒNG 0 – KHỞI ĐỘNG HỆ THỐNG

Mục đích: Đưa 4 Server, 4 CSDL, Load Balancer, module đồng bộ và Client vào trạng thái sẵn sàng thi.

Điều kiện bắt đầu: Các máy đã đồng bộ giờ (NTP) và có tệp cấu hình chứa địa chỉ, cổng, khóa HMAC.

Kết quả: Load Balancer thấy ít nhất 3 Server ở trạng thái hoạt động, 4 CSDL cùng phiên bản lược đồ và đã bắt kịp nhau, Client hiển thị màn hình đăng nhập.



| Bước | Diễn biến của chương trình | Giang (Server 1, Load Balancer) | Huy (Server 2, Đồng bộ) | Ngọc (Server 3, Client) | Thuận (Server 4, Giao diện, Test) |
| --- | --- | --- | --- | --- | --- |
| Bước 1 | Mỗi người bật hệ quản trị CSDL của mình, chạy script tạo bảng nếu chưa có, kiểm tra bảng schema_version khớp với phiên bản lược đồ chung. | Khởi động CSDL 1 và chạy script lược đồ chung. | Cung cấp script tạo bảng, chỉ mục, schema_version và script dữ liệu mẫu nhỏ; thông báo cho nhóm khi đổi phiên bản lược đồ. | Khởi động CSDL 3 và chạy script lược đồ chung. | Khởi động CSDL 4 và chạy script lược đồ chung; nạp dữ liệu mẫu lớn bằng công cụ sinh dữ liệu khi cần thử tải. |
| Bước 2 | Server N đọc tệp cấu hình, tạo connection pool, thử kết nối CSDL N, tạo thread pool nghiệp vụ, mở cổng nghiệp vụ 900N, tạo một luồng riêng xử lý PING (để vẫn trả lời khi thread pool nghiệp vụ đầy), khởi động lịch quét bài hết hạn mỗi 10 giây. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). |
| Bước 3 | Module đồng bộ khởi động trong từng Server: mở cổng 910N, đọc từ bảng sync_peer_state số thứ tự thao tác cuối cùng đã nhận từ mỗi Server khác, kết nối tới 3 Server kia và trao đổi SYNC_HELLO. Bên nào thiếu thao tác thì được gửi bù (xem Luồng 6, bước 6). | Gọi hàm khởi động module đồng bộ trong hàm main của Server 1. | Cài đặt module đồng bộ, bảng sync_log, sync_applied, sync_peer_state và quy trình SYNC_HELLO. | Gọi hàm khởi động module đồng bộ trong hàm main của Server 3. | Gọi hàm khởi động module đồng bộ trong hàm main của Server 4; kiểm tra sau khởi động 4 CSDL cùng số bản ghi. |
| Bước 4 | Server 1 khởi động Load Balancer: mở cổng 9000, tạo bảng trạng thái 4 Server (ban đầu là UNKNOWN), tạo sổ đăng ký phiên và sổ đăng ký bài làm (rỗng), khởi động luồng kiểm tra sức khỏe gửi PING mỗi giây. Server nào trả PONG đủ 3 lần liên tiếp thì chuyển sang UP. Load Balancer bắt đầu nhận Client khi có từ 3 Server UP trở lên (có thể cấu hình hạ ngưỡng khi thử nghiệm). | Cài đặt toàn bộ bước này: bộ lắng nghe cổng 9000, bảng trạng thái, luồng kiểm tra sức khỏe, nhật ký phân phối. | Đảm bảo Server 2 trả PONG kèm thông tin tải và giờ Server. | Đảm bảo Server 3 trả PONG kèm thông tin tải và giờ Server. | Đảm bảo Server 4 trả PONG kèm thông tin tải và giờ Server. |
| Bước 5 | Mỗi lần nhận PONG, Load Balancer so giờ Server với giờ của Server 1 để tính độ lệch đồng hồ; độ lệch vượt 1 giây thì ghi cảnh báo cho quản trị viên. | Tính và ghi độ lệch đồng hồ từng Server, hiển thị trong dữ liệu giám sát. | — | — | Nhắc nhóm bật NTP trên các máy trước buổi kiểm thử tải. |
| Bước 6 | Client khởi động: đọc địa chỉ Load Balancer trong cấu hình, mở kết nối tới cổng 9000, gửi PING 3 lần. Load Balancer chuyển PING tới một Server để lấy serverTime. Client lấy lần có thời gian khứ hồi (RTT) nhỏ nhất và tính độ lệch: offset = serverTime − (t gửi + t nhận) / 2. Mọi phép tính thời gian sau này dùng offset này. | Chuyển tiếp PING của Client tới một Server UP và trả PONG nhanh. | — | Cài đặt hàm connect() và phép tính offset; bắt đầu gửi PING định kỳ mỗi 5 giây để giữ kết nối. | Hiển thị màn hình đăng nhập và biểu tượng trạng thái kết nối (đang kết nối, đã kết nối, mất kết nối). |


3. LUỒNG 1 – ĐĂNG NHẬP VÀ QUẢN LÝ PHIÊN

Mục đích: Xác thực người dùng, cấp token và đảm bảo mỗi tài khoản chỉ có một phiên hoạt động.

Điều kiện bắt đầu: Client đã kết nối tới Load Balancer (Luồng 0).

Kết quả: Client có token, vai trò và offset; Load Balancer có bản ghi phiên của người dùng; giao diện chuyển đúng màn hình theo vai trò.



| Bước | Diễn biến của chương trình | Giang (Server 1, Load Balancer) | Huy (Server 2, Đồng bộ) | Ngọc (Server 3, Client) | Thuận (Server 4, Giao diện, Test) |
| --- | --- | --- | --- | --- | --- |
| Bước 1 | Người dùng nhập mã và mật khẩu rồi bấm Đăng nhập. Giao diện kiểm tra không để trống, khóa nút, hiện trạng thái chờ và gọi ExamClient.login() trên luồng nền, không gọi trên luồng giao diện JavaFX. | — | — | Cài đặt login(): tạo thông điệp LOGIN, gửi và trả về CompletableFuture; hết 10 giây không có phản hồi thì báo lỗi hết thời gian. | Cài đặt màn hình đăng nhập, kiểm tra dữ liệu nhập, hiển thị chờ và thông báo lỗi theo mã lỗi. |
| Bước 2 | Load Balancer nhận LOGIN. Nếu số kết nối vượt giới hạn thì trả BUSY kèm retryAfter. Nếu không có Server UP thì trả lỗi 503 NO_SERVER_AVAILABLE. Ngược lại chọn Server theo thuật toán (Round Robin hoặc Weighted Least Connections), mở hoặc dùng lại kết nối nội bộ tới Server đó và chuyển tiếp thông điệp. | Cài đặt toàn bộ bước này, ghi nhật ký phân phối (thời điểm, người dùng, Server được chọn). | — | Xử lý phản hồi BUSY: chờ retryAfter rồi thử lại tối đa 3 lần. | Hiển thị thông báo “Hệ thống đang bận, thử lại sau n giây” và đếm ngược. |
| Bước 3 | Server xác thực: tìm người dùng theo mã, so mật khẩu với giá trị băm có muối (BCrypt) trong bảng users. Sai mật khẩu: ghi audit_log LOGIN_FAIL, tăng bộ đếm sai trong bộ nhớ, sai 5 lần thì khóa đăng nhập tài khoản đó 5 phút. Đúng: tạo token ký HMAC (mã người dùng, vai trò, thời hạn 4 giờ), ghi audit_log LOGIN_OK và trả về token, họ tên, vai trò, serverTime. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). Dùng hàm ghi sync_log của mình để audit_log được nhân bản (bản ghi chỉ thêm). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). |
| Bước 4 | Load Balancer đăng ký phiên vào sổ: người dùng → (kết nối Client, Server). Nếu người dùng đã có phiên cũ thì kiểm tra phiên cũ còn sống không. Phiên cũ đã đứt: cho phép phiên mới thay thế. Phiên cũ còn sống và đang có bài làm đang làm dở: từ chối phiên mới bằng lỗi 409 SESSION_IN_EXAM. Phiên cũ còn sống nhưng không thi: gửi SESSION_REPLACED cho phiên cũ rồi đóng nó. | Cài đặt sổ đăng ký phiên và các quy tắc thay thế phiên; thử với các tình huống đăng nhập hai nơi. | — | Xử lý thông điệp SESSION_REPLACED: đóng kết nối, xóa token, về màn hình đăng nhập. | Hiển thị hộp thoại “Tài khoản đã đăng nhập ở nơi khác” và lỗi 409 khi đang thi; viết kịch bản thử đăng nhập hai nơi. |
| Bước 5 | Client nhận phản hồi: lưu token, vai trò, họ tên, cập nhật offset bằng serverTime; giao diện chuyển sang màn hình theo vai trò (sinh viên, giảng viên, quản trị). Nếu có tệp bài làm dở trên máy (xem Luồng 9, bước 5) thì hỏi người dùng có muốn tiếp tục không. | — | — | Lưu thông tin phiên; phát sự kiện đăng nhập thành công cho giao diện; kiểm tra tệp bài làm dở. | Chuyển màn hình theo vai trò; hiển thị hộp thoại tiếp tục bài làm dở. |


4. LUỒNG 2 – XEM DANH SÁCH CA THI CỦA SINH VIÊN

Mục đích: Sinh viên xem các ca thi được gán, tìm kiếm, lọc theo trạng thái và phân trang.

Điều kiện bắt đầu: Sinh viên đã đăng nhập.

Kết quả: Giao diện hiển thị đúng danh sách, nút Vào thi chỉ bật khi ca thi đang mở.



| Bước | Diễn biến của chương trình | Giang (Server 1, Load Balancer) | Huy (Server 2, Đồng bộ) | Ngọc (Server 3, Client) | Thuận (Server 4, Giao diện, Test) |
| --- | --- | --- | --- | --- | --- |
| Bước 1 | Giao diện gọi ExamClient.listExams(từ khóa, trạng thái, trang, cỡ trang). Client gửi LIST_EXAMS. Client giữ bộ nhớ đệm từng trang trong 30 giây để không gửi lại khi người dùng chuyển qua lại giữa các trang. | — | — | Cài đặt listExams() có tìm kiếm, lọc, phân trang và bộ nhớ đệm 30 giây. | Cài đặt bảng danh sách có ô tìm kiếm (chờ 300 mili giây sau lần gõ cuối mới gửi), bộ lọc trạng thái, nút phân trang. |
| Bước 2 | Load Balancer chuyển tiếp tới đúng Server mà phiên của người dùng đang gắn (theo sổ đăng ký phiên). Yêu cầu không cần chọn Server mới. | Chuyển tiếp theo sổ đăng ký phiên; cập nhật thời điểm hoạt động cuối của kết nối. | — | — | — |
| Bước 3 | Server kiểm tra token (chữ ký, thời hạn), vai trò sinh viên. Truy vấn exam_assignments nối exams và attempts của sinh viên, lọc theo từ khóa và trạng thái, dùng LIMIT/OFFSET, đếm tổng số dòng. Mỗi ca thi có trạng thái: NOT_OPEN (chưa mở), OPEN (đang mở, chưa vào thi), IN_PROGRESS (đang làm dở), SUBMITTED (đã nộp), CLOSED (đã đóng, chưa thi). Trạng thái tính theo giờ Server. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). Bảo đảm chỉ mục cho exam_assignments(student_id, exam_id) và attempts(student_id, exam_id) có trong script lược đồ. | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). |
| Bước 4 | Phản hồi gồm danh sách ca thi (mã, tên, môn, giờ mở, giờ đóng, thời lượng, trạng thái, thời gian còn lại nếu đang làm) và tổng số dòng. Client hiệu chỉnh offset bằng serverTime và vẽ lại bảng; nút Vào thi chỉ bật với trạng thái OPEN hoặc IN_PROGRESS. | — | Trước giờ mở 5 phút, cung cấp hàm cho biết đề của ca thi đã nhân bản đủ tới 4 CSDL chưa (dùng ở Luồng 7, bước 7). | Cập nhật offset, trả dữ liệu cho giao diện, xóa bộ nhớ đệm khi có SUBMITTED. | Hiển thị bảng, nút Vào thi, nhãn trạng thái và đếm ngược tới giờ mở. |


5. LUỒNG 3 – VÀO THI

Mục đích: Tạo bài làm cho sinh viên (hoặc khôi phục bài đang làm dở) và gửi đề đã xáo trộn xuống Client.

Điều kiện bắt đầu: Sinh viên đã đăng nhập, ca thi ở trạng thái OPEN hoặc IN_PROGRESS.

Kết quả: Server có bản ghi attempt; Client có đề, hạn nộp, vé bài làm; ba luồng nền của Client đang chạy; Load Balancer đã gắn bài làm với Server.



| Bước | Diễn biến của chương trình | Giang (Server 1, Load Balancer) | Huy (Server 2, Đồng bộ) | Ngọc (Server 3, Client) | Thuận (Server 4, Giao diện, Test) |
| --- | --- | --- | --- | --- | --- |
| Bước 1 | Sinh viên chọn ca thi và bấm Vào thi. Giao diện hiện quy chế thi và xác nhận. Sau khi đồng ý, giao diện gọi ExamClient.startExam(examId). | — | — | Cài đặt startExam(): gửi START_EXAM; xử lý ba loại phản hồi: đề thi, QUEUED (xếp hàng), lỗi. | Cài đặt hộp thoại quy chế và xác nhận vào thi; khóa nút để không bấm hai lần. |
| Bước 2 | Load Balancer tra sổ đăng ký bài làm theo (người dùng, ca thi). Nếu đã có thì chuyển tới đúng Server cũ (sticky). Nếu chưa có thì kiểm soát đầu ca thi: số START_EXAM đang xử lý vượt 50 thì đưa yêu cầu vào hàng đợi FIFO và gửi QUEUED kèm vị trí; khi tới lượt, chọn Server theo Weighted Least Connections trong số Server UP rồi chuyển tiếp. | Cài đặt sổ đăng ký bài làm, hàng đợi FIFO, gửi QUEUED mỗi 3 giây cập nhật vị trí, chọn Server theo trọng số. | — | Xử lý QUEUED: giữ yêu cầu chờ, không báo lỗi, báo vị trí cho giao diện. | Hiển thị “Đang xếp hàng, vị trí n”; dùng bot giả lập 200 sinh viên cùng bấm Vào thi để kiểm tra hàng đợi. |
| Bước 3 | Server kiểm tra điều kiện: token hợp lệ và vai trò sinh viên; sinh viên được gán ca thi (nếu không: 403 NOT_ASSIGNED); giờ Server nằm trong khoảng giờ mở – giờ đóng (chưa tới: 425 EXAM_NOT_OPEN; quá giờ đóng: 410 EXAM_CLOSED); chưa có bài đã nộp (nếu có: 409 ALREADY_SUBMITTED). Nếu đã có bài ở trạng thái IN_PROGRESS thì chuyển sang bước 5 (khôi phục). | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). |
| Bước 4 | Tạo bài làm trong một giao dịch: attemptId là UUID, seed là số ngẫu nhiên, start_time là giờ Server, deadline = nhỏ nhất của (start_time + thời lượng, giờ đóng ca thi), server_id là Server này, status là IN_PROGRESS. Bảng attempts có ràng buộc UNIQUE (student_id, exam_id) để không tạo hai bài cho cùng một sinh viên. Cùng giao dịch ghi một dòng sync_log loại ATTEMPT_CREATE. Server ký HMAC “vé bài làm” gồm attemptId, userId, examId, seed, start_time, deadline. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). Cung cấp hàm ghi sync_log trong cùng giao dịch; gán độ ưu tiên cao cho ATTEMPT_CREATE để bài làm sớm có mặt ở Server khác. | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). |
| Bước 5 | Dựng đề: đọc exam_questions (nếu đề ngẫu nhiên theo cấu trúc thì chọn câu theo seed). Xáo trộn thứ tự câu hỏi và thứ tự phương án bằng java.util.Random(seed) nên Server nào dựng lại cũng ra đúng cùng một đề. Phản hồi gồm attemptId, vé bài làm, deadline, serverTime, thời gian còn lại, danh sách câu hỏi (mã câu, nội dung, các phương án), tuyệt đối không có đáp án đúng. Trường hợp khôi phục: dựng lại bằng seed đã lưu, kèm các đáp án đã lưu trong attempt_answers. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). |
| Bước 6 | Load Balancer thấy phản hồi START_EXAM thành công thì ghi (người dùng, ca thi) → Server vào sổ đăng ký bài làm, và đánh dấu kết nối là “đang thi” để Luồng 1 bước 4 áp dụng quy tắc 409. | Cập nhật sổ đăng ký bài làm và cờ đang thi; ghi nhật ký. | — | — | — |
| Bước 7 | Client nhận đề: lưu vào bộ nhớ và ghi tệp cục bộ attempt_<attemptId>.json (token, vé bài làm, đề, đáp án hiện có; không chứa đáp án đúng). Tính deadline cục bộ = deadline − offset. Khởi động ba luồng nền: luồng đồng hồ, luồng tự lưu, luồng nhận thông báo. Giao diện dựng màn hình làm bài. | — | — | Cài đặt lưu tệp cục bộ, tính deadline cục bộ, khởi động ba luồng nền. | Dựng màn hình làm bài: vùng câu hỏi, bảng điều hướng số câu (chưa làm, đã làm, đã đánh dấu), nút Câu trước, Câu sau, Nộp bài, đồng hồ đếm ngược, dòng trạng thái lưu và kết nối. |


6. LUỒNG 4 – LÀM BÀI VÀ TỰ ĐỘNG LƯU ĐÁP ÁN

Mục đích: Ghi nhận đáp án sinh viên chọn, giữ đồng hồ chạy và lưu đáp án định kỳ lên Server.

Điều kiện bắt đầu: Sinh viên đã vào thi (Luồng 3).

Kết quả: Đáp án mới nhất có ở Client, Server và các CSDL khác; sinh viên thấy trạng thái “Đã lưu”.



| Bước | Diễn biến của chương trình | Giang (Server 1, Load Balancer) | Huy (Server 2, Đồng bộ) | Ngọc (Server 3, Client) | Thuận (Server 4, Giao diện, Test) |
| --- | --- | --- | --- | --- | --- |
| Bước 1 | Sinh viên chọn hoặc đổi đáp án, hoặc đánh dấu câu. Giao diện cập nhật mô hình hiển thị rồi gọi ExamClient.setAnswer(maCau, maPhuongAn, daDanhDau). Client cập nhật bản đồ đáp án trong bộ nhớ, đặt cờ dirty, ghi nhanh vào tệp cục bộ bằng luồng nền. | — | — | Cài đặt setAnswer(), cờ dirty cho từng câu, ghi tệp cục bộ bất đồng bộ. | Cài đặt sự kiện chọn đáp án, đổi màu ô số câu, nút đánh dấu. |
| Bước 2 | Luồng đồng hồ của Client chạy mỗi giây: còn lại = deadline cục bộ − (giờ máy + offset). Cập nhật nhãn thời gian; báo nhắc khi còn 5 phút và 1 phút; khi còn lại bằng 0 thì chuyển sang Luồng 5 (tự động nộp). | — | — | Cài đặt luồng đồng hồ, phát sự kiện nhắc giờ và hết giờ. | Hiển thị đồng hồ, hiệu ứng nhắc 5 phút và 1 phút; khóa thao tác chọn đáp án khi hết giờ. |
| Bước 3 | Luồng tự lưu: cứ 20 giây, hoặc khi chuyển câu, nếu có câu dirty thì gửi SAVE_ANSWERS gồm attemptId, seq tăng dần và danh sách đáp án thay đổi. Việc gửi nằm ở luồng ghi socket, nhận ở luồng đọc socket, ghép cặp bằng requestId, không chặn giao diện. Quá 10 giây không có phản hồi thì đánh dấu “chưa lưu” và gửi lại; socket báo lỗi thì chuyển sang Luồng 9. | — | — | Cài đặt luồng tự lưu, hàng đợi gửi, ghép cặp requestId, thử lại và chuyển sang Luồng 9 khi socket lỗi. | Hiển thị dòng “Đang lưu…”, “Đã lưu lúc hh:mm:ss”, “Chưa lưu – đang thử lại”. |
| Bước 4 | Load Balancer chuyển tiếp SAVE_ANSWERS tới Server của bài làm theo sổ đăng ký bài làm, không chọn lại. | Chuyển tiếp theo sticky; cập nhật bộ đếm tải của Server. | — | — | — |
| Bước 5 | Server xử lý SAVE_ANSWERS: kiểm tra token, bài làm thuộc về người dùng, trạng thái IN_PROGRESS, giờ Server không quá deadline + 5 giây (nếu quá: 410 ATTEMPT_EXPIRED); kiểm tra seq lớn hơn seq đã nhận (nhỏ hơn thì bỏ qua, vẫn trả OK). Ghi từng đáp án vào attempt_answers theo kiểu cập nhật hoặc thêm với updated_at là giờ Server và version tăng, trong một giao dịch, cùng các dòng sync_log loại ANSWER_UPSERT. Trả về số câu đã lưu, serverTime, số giây còn lại. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). |
| Bước 6 | Client nhận xác nhận: xóa cờ dirty của các câu đã lưu, cập nhật offset dần bằng serverTime (tránh nhảy đồng hồ đột ngột), cập nhật dòng trạng thái “Đã lưu”. Nếu nhận 410 ATTEMPT_EXPIRED thì chuyển ngay sang Luồng 5. | — | — | Cài đặt xử lý xác nhận, hiệu chỉnh offset dần, chuyển sang Luồng 5 khi gặp 410. | Cập nhật dòng trạng thái lưu. |
| Bước 7 | Luồng nhận thông báo của Client lắng nghe các thông điệp do Server hoặc Load Balancer chủ động gửi: SERVER_SWITCHING, SESSION_REPLACED, thông báo của giảng viên (nếu có). Mỗi loại được chuyển cho giao diện hoặc cho Luồng 8, 9 xử lý. | Gửi SERVER_SWITCHING khi chuyển Server (Luồng 8). | — | Cài đặt luồng nhận thông báo và bộ chuyển sự kiện. | Hiển thị thông báo trên màn hình làm bài. |
| Bước 8 | Module đồng bộ gom ANSWER_UPSERT vào lô 200 mili giây rồi gửi tới 3 Server còn lại (Luồng 6). Mục tiêu: đáp án đã lưu có mặt ở ít nhất một Server khác trong vòng vài trăm mili giây trong điều kiện mạng bình thường. | — | Cài đặt việc gom lô và gửi; đo độ trễ đồng bộ ANSWER_UPSERT và báo cho nhóm. | — | Đo độ trễ đồng bộ từ lúc lưu tới lúc có ở CSDL khác khi chạy stress test. |


7. LUỒNG 5 – NỘP BÀI VÀ CHẤM ĐIỂM

Mục đích: Chốt bài làm, chấm điểm tự động và lưu kết quả; nộp lặp lại hoặc hết giờ đều cho cùng một kết quả.

Điều kiện bắt đầu: Sinh viên bấm Nộp bài, hoặc đồng hồ về 0, hoặc Server phát hiện bài đã quá hạn.

Kết quả: Bài làm ở trạng thái SUBMITTED, có điểm, đã nhân bản; Client hiển thị kết quả hoặc thông báo đã nộp.



| Bước | Diễn biến của chương trình | Giang (Server 1, Load Balancer) | Huy (Server 2, Đồng bộ) | Ngọc (Server 3, Client) | Thuận (Server 4, Giao diện, Test) |
| --- | --- | --- | --- | --- | --- |
| Bước 1 | Sinh viên bấm Nộp bài: giao diện hiện hộp thoại xác nhận kèm số câu chưa làm. Nếu hết giờ thì bỏ qua xác nhận. Giao diện khóa màn hình làm bài và gọi ExamClient.submit(). | — | — | Cài đặt submit(): gửi một SAVE_ANSWERS cuối chứa toàn bộ đáp án hiện có (ảnh chụp đầy đủ, seq mới), sau đó gửi SUBMIT với requestId cố định “SUBMIT-<attemptId>”. Gửi lại khi mất kết nối vẫn dùng cùng requestId. | Cài đặt hộp thoại xác nhận, khóa giao diện, màn hình chờ “Đang nộp bài”. |
| Bước 2 | Load Balancer chuyển tiếp SAVE_ANSWERS và SUBMIT theo sticky. | Chuyển tiếp theo sticky. | — | — | — |
| Bước 3 | Server nhận SUBMIT: kiểm tra token và bài làm thuộc về người dùng. Nếu bài đã SUBMITTED thì trả lại ngay kết quả đã có (không chấm lại). Nếu giờ Server quá deadline + 5 giây thì vẫn nhận nhưng chỉ tính các đáp án có updated_at không muộn hơn deadline + 5 giây và đánh dấu bài nộp muộn. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). |
| Bước 4 | Chấm điểm: nạp đáp án đúng của ca thi từ bộ nhớ đệm của Server (đề không đổi trong lúc thi; nạp một lần từ CSDL rồi giữ lại). Với mỗi câu so phương án đã chọn với phương án đúng; câu bỏ trống tính 0, không trừ điểm. Điểm = số câu đúng ÷ tổng số câu × thang điểm (mặc định 10), làm tròn 2 chữ số. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). |
| Bước 5 | Lưu kết quả bằng một giao dịch: UPDATE attempts SET status = SUBMITTED, submit_time, score, correct_count WHERE id = ? AND status = IN_PROGRESS. Nếu không dòng nào bị cập nhật (có tiến trình khác vừa nộp) thì đọc lại bài và trả kết quả đã có. Cùng giao dịch ghi sync_log loại ATTEMPT_SUBMIT; ghi audit_log SUBMIT. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). Cung cấp ghi sync_log trong giao dịch. | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). |
| Bước 6 | Bảo đảm bền vững (tùy chọn cấu hình, mặc định bật): trước khi trả OK, Server gọi waitForReplication(opId, tốiThiểu = 1 Server khác, hạnChờ = 2 giây). Hết 2 giây chưa có xác nhận thì vẫn trả OK nhưng ghi nhận bài nộp đang chờ nhân bản và ưu tiên gửi lại. | Gọi waitForReplication ở Server 1. | Cài đặt waitForReplication, hàng ưu tiên ATTEMPT_SUBMIT và nhật ký các bài nộp chờ nhân bản. | Gọi waitForReplication ở Server 3. | Gọi waitForReplication ở Server 4; kiểm thử ngắt mạng đúng lúc nộp bài. |
| Bước 7 | Phản hồi SUBMIT gồm điểm, số câu đúng, tổng số câu, cờ nộp muộn. Nếu ca thi cấu hình ẩn điểm thì chỉ trả trạng thái SUBMITTED. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). |
| Bước 8 | Client nhận kết quả: dừng ba luồng nền, xóa tệp cục bộ của bài làm, chuyển sự kiện cho giao diện. Giao diện hiển thị màn hình kết quả hoặc thông báo “Đã nộp bài”. | — | — | Dừng luồng đồng hồ và luồng tự lưu; xóa tệp cục bộ; xóa bộ nhớ đệm danh sách ca thi. | Hiển thị màn hình kết quả, quay về danh sách ca thi. |
| Bước 9 | Load Balancer gỡ bản ghi bài làm khỏi sổ đăng ký bài làm, bỏ cờ “đang thi” của kết nối. | Cập nhật sổ đăng ký và cờ đang thi. | — | — | — |
| Bước 10 | Trường hợp Client không nộp được (mất mạng, tắt máy, hết giờ): lịch quét 10 giây trên mỗi Server tìm bài IN_PROGRESS thuộc Server mình có deadline + 5 giây đã qua, rồi chạy lại bước 3 đến 5 với người nộp là SYSTEM. Mỗi Server cũng quét bài của Server đang DOWN theo danh sách Load Balancer gửi kèm PING (xem Luồng 8, bước 9). | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). Tích hợp danh sách Server DOWN vào PING. | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). Cung cấp thao tác nhận quản lý bài (ATTEMPT_REASSIGN) để Server khác chấm bài của Server DOWN. | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). Viết kịch bản kiểm thử: tắt Client giữa chừng rồi kiểm tra bài được tự chấm. |


8. LUỒNG 6 – ĐỒNG BỘ DỮ LIỆU GIỮA 4 CSDL

Mục đích: Nhân bản mọi thay đổi dữ liệu giữa 4 CSDL, giải quyết xung đột, bắt kịp sau khi mất kết nối và kiểm tra nhất quán.

Điều kiện bắt đầu: Một Server vừa ghi thay đổi dữ liệu nghiệp vụ và sync_log trong cùng giao dịch.

Kết quả: Sau một khoảng ngắn, 4 CSDL có cùng dữ liệu; mọi sai lệch được phát hiện và sửa.



| Bước | Diễn biến của chương trình | Giang (Server 1, Load Balancer) | Huy (Server 2, Đồng bộ) | Ngọc (Server 3, Client) | Thuận (Server 4, Giao diện, Test) |
| --- | --- | --- | --- | --- | --- |
| Bước 1 | Ghi nhật ký: mỗi thay đổi nghiệp vụ ghi kèm một dòng sync_log trong cùng giao dịch với dữ liệu thật. Dòng gồm origin (Server gốc), seq (tăng đơn điệu theo từng Server gốc), opId = origin-seq, bảng, khóa, loại thao tác, version, nội dung JSON, thời điểm, trạng thái PENDING. Thao tác nhiều bảng (như tạo đề thi) gói thành một thao tác duy nhất EXAM_PACKAGE để Server nhận thấy đề trọn vẹn hoặc không thấy gì. | Gọi hàm ghi sync_log từ mọi nơi Server 1 ghi dữ liệu. | Cài đặt SyncLogWriter (ghi trong cùng giao dịch, cấp seq), các loại thao tác và định dạng nội dung. | Gọi hàm ghi sync_log từ mọi nơi Server 3 ghi dữ liệu. | Gọi hàm ghi sync_log từ mọi nơi Server 4 ghi dữ liệu; viết kiểm thử kiểm tra không có thay đổi nào thiếu dòng sync_log. |
| Bước 2 | Luồng gửi: lấy các dòng PENDING theo thứ tự seq, gom lô tối đa 100 dòng hoặc 200 mili giây, gửi SYNC_BATCH tới từng Server khác qua cổng đồng bộ. Mỗi Server khác có con trỏ riêng trong sync_peer_state ghi seq cuối đã được xác nhận. | — | Cài đặt luồng gửi, gom lô, con trỏ theo từng Server đích. | — | — |
| Bước 3 | Server nhận áp dụng từng thao tác trong một giao dịch: bỏ qua nếu (origin, seq) đã có trong sync_applied. Quy tắc xung đột: danh mục (users, câu hỏi, đề, phân ca) thì bản có version cao hơn thắng, bằng nhau thì Server gốc có mã nhỏ hơn thắng. attempts: trạng thái SUBMITTED không bị ghi đè bởi IN_PROGRESS; hai bài SUBMITTED khác nhau thì bài có submit_time sớm hơn thắng. attempt_answers: bản có updated_at muộn hơn thắng, nhưng bỏ qua đáp án có updated_at sau thời điểm nộp của bài đã SUBMITTED. audit_log: chỉ thêm, loại trùng theo mã. Thao tác nhận từ Server khác không sinh sync_log mới (mạng 4 Server nối đầy đủ nên không cần chuyển tiếp). | — | Cài đặt bộ áp dụng thao tác và toàn bộ quy tắc xung đột; viết kiểm thử từng quy tắc (hai Server cùng sửa một câu hỏi; hai Server cùng nộp một bài). | — | Viết kịch bản thử xung đột và kiểm tra kết quả khớp quy tắc. |
| Bước 4 | Xác nhận: Server nhận gửi SYNC_ACK với seq cao nhất đã áp dụng liên tục. Server gốc cập nhật con trỏ; dòng sync_log chuyển sang DONE khi đủ 3 Server xác nhận; dọn dòng DONE cũ hơn 24 giờ. | — | Cài đặt SYNC_ACK, con trỏ, đánh dấu DONE và dọn dẹp. | — | — |
| Bước 5 | Mất kết nối giữa hai Server: luồng gửi không nhận được xác nhận trong 3 giây hoặc socket lỗi thì đánh dấu Server đích là DOWN trong module đồng bộ; các thao tác mới vẫn chất vào sync_log ở trạng thái PENDING; luồng gửi thử kết nối lại theo nhịp 1, 2, 4, 8, 16, 30 giây. | — | Cài đặt phát hiện mất kết nối và thử lại. | — | Dùng công cụ gây lỗi ngắt kết nối giữa hai Server và đo thời gian phát hiện. |
| Bước 6 | Bắt kịp sau khi nối lại: hai bên trao đổi SYNC_HELLO kèm seq cuối đã thấy của từng Server gốc; mỗi bên gửi các thao tác lớn hơn seq của bên kia. Nếu chênh lệch quá 50.000 thao tác hoặc dòng cũ đã bị dọn thì chuyển sang đồng bộ nguyên bảng: so mã băm theo khối, chỉ chuyển các dòng khác nhau. | — | Cài đặt bắt kịp theo seq và đồng bộ nguyên bảng theo khối. | — | Kiểm thử: tắt một Server 5 phút khi đang có tải, bật lại và so số bản ghi, mã băm. |
| Bước 7 | Kiểm tra nhất quán mỗi 5 phút: Server 2 (điều phối viên) yêu cầu mỗi Server trả số bản ghi và mã băm của từng bảng (mã băm của danh sách (id, version) đã sắp xếp). Có sai lệch thì ghi cảnh báo, kích hoạt đồng bộ nguyên bảng cho bảng đó và gửi kết quả cho quản trị viên. | — | Cài đặt điều phối viên kiểm tra nhất quán chạy trên Server 2 và phần trả lời trên cả 4 Server. | — | Đưa kết quả kiểm tra nhất quán vào báo cáo kiểm thử. |
| Bước 8 | Giám sát: module đồng bộ cung cấp SyncStatus gồm số thao tác chờ gửi theo từng Server đích, độ trễ, trạng thái từng Server đích, kết quả kiểm tra nhất quán lần gần nhất. Load Balancer gom số liệu này khi trả ADMIN_STATUS. | Gom SyncStatus của 4 Server vào ADMIN_STATUS. | Cài đặt SyncStatus. | Bổ sung thông điệp và hàm trong ExamClient để lấy ADMIN_STATUS. | Hiển thị bảng trạng thái đồng bộ trên màn hình quản trị. |


9. LUỒNG 7 – GIẢNG VIÊN QUẢN LÝ CÂU HỎI, ĐỀ THI VÀ XEM KẾT QUẢ

Mục đích: Giảng viên quản lý ngân hàng câu hỏi, tạo đề và ca thi, theo dõi ca thi, xem bảng điểm và thống kê, xuất CSV.

Điều kiện bắt đầu: Giảng viên đã đăng nhập.

Kết quả: Dữ liệu đề thi có ở cả 4 CSDL trước giờ mở; giảng viên xem được điểm và xuất được tệp CSV.



| Bước | Diễn biến của chương trình | Giang (Server 1, Load Balancer) | Huy (Server 2, Đồng bộ) | Ngọc (Server 3, Client) | Thuận (Server 4, Giao diện, Test) |
| --- | --- | --- | --- | --- | --- |
| Bước 1 | Tìm kiếm, lọc, phân trang câu hỏi: giao diện gửi QUESTION_LIST gồm từ khóa, môn, chương, độ khó, trang, cỡ trang, cột sắp xếp. Load Balancer chọn Server theo thuật toán (yêu cầu của giảng viên không cần sticky). Server truy vấn bằng điều kiện LIKE và LIMIT/OFFSET, bỏ qua câu đã xóa mềm, trả danh sách và tổng số dòng. | Chọn Server theo thuật toán; ghi nhật ký phân phối. | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). Bảo đảm chỉ mục theo môn, chương, độ khó và bộ lọc xóa mềm. | Cài đặt searchQuestions() có lọc, phân trang, bộ nhớ đệm ngắn hạn. | Cài đặt bảng câu hỏi, ô tìm kiếm, bộ lọc môn, chương, độ khó và phân trang. |
| Bước 2 | Thêm câu hỏi: giao diện hiển thị biểu mẫu (nội dung, 2 đến 6 phương án, đúng một phương án đúng, môn, chương, độ khó) và kiểm tra ở phía Client. Client gửi QUESTION_CREATE. Server kiểm tra lại, tạo mã UUID, version = 1, ghi trong một giao dịch cùng sync_log. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). Cài đặt createQuestion() và ánh xạ lỗi kiểm tra dữ liệu. | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). Cài đặt biểu mẫu thêm câu hỏi và hiển thị lỗi theo từng ô. |
| Bước 3 | Sửa câu hỏi: Client gửi QUESTION_UPDATE kèm baseVersion (version lúc mở biểu mẫu). Server so baseVersion với version trong CSDL của mình: khác nhau thì trả 409 VERSION_CONFLICT kèm bản hiện tại. Câu hỏi thuộc ca thi đang diễn ra thì trả 423 IN_USE_BY_ACTIVE_EXAM. Hợp lệ thì cập nhật, version tăng 1, ghi sync_log. Hai giảng viên sửa cùng lúc trên hai Server khác nhau được xử lý bằng quy tắc xung đột ở Luồng 6. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). Hiển thị hộp thoại so sánh hai bản khi gặp 409. |
| Bước 4 | Xóa câu hỏi: xóa mềm (đặt deleted = true, version tăng, ghi sync_log), không xóa vật lý vì đề và bài thi cũ vẫn tham chiếu. Câu hỏi đang thuộc ca thi đang diễn ra thì trả 423. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). Cài đặt deleteQuestion(). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). Cài đặt nút xóa và hộp thoại xác nhận. |
| Bước 5 | Tạo đề và ca thi: giao diện thu thập tên, môn, thời lượng, giờ mở, giờ đóng, kiểu đề (cố định hoặc ngẫu nhiên theo cấu trúc: chương, độ khó, số câu), chế độ hiển thị điểm, danh sách sinh viên hoặc lớp được gán. Client gửi EXAM_CREATE. Server kiểm tra: giờ đóng sau giờ mở, thời lượng không vượt khoảng giờ mở – đóng, ngân hàng đủ câu cho cấu trúc, các sinh viên có tồn tại. Ghi exams, exam_questions, exam_assignments trong một giao dịch và một thao tác sync_log duy nhất EXAM_PACKAGE. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). Cài đặt áp dụng nguyên tử EXAM_PACKAGE ở Server nhận. | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). Cài đặt createExam(). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). Cài đặt các bước nhập của biểu mẫu tạo đề, kiểm tra ở phía Client và màn hình xem trước đề. |
| Bước 6 | Kiểm tra sẵn sàng trước giờ mở: Server trả thêm trạng thái “đã nhân bản tới n trên 4 Server” của ca thi bằng cách hỏi module đồng bộ xem thao tác EXAM_PACKAGE đã được đủ 3 Server khác xác nhận chưa. | — | Cung cấp hàm isReplicated(opId) trả số Server đã xác nhận. | Cài đặt getExamReadiness(). | Hiển thị trạng thái “Sẵn sàng 4/4 Server” hoặc cảnh báo khi chưa đủ. |
| Bước 7 | Theo dõi ca thi: giao diện gọi EXAM_MONITOR mỗi 5 giây. Server đếm theo trạng thái: số sinh viên được gán, chưa vào thi, đang làm, đã nộp. Số liệu có thể chậm vài giây so với thực tế do độ trễ đồng bộ. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). Cài đặt hàm làm mới định kỳ mỗi 5 giây và tự dừng khi đóng màn hình. | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). Hiển thị bảng và biểu đồ số lượng theo trạng thái. |
| Bước 8 | Xem bảng điểm và thống kê: GET_SCORES (từ khóa, trang, cỡ trang, sắp xếp) trả danh sách điểm kèm giờ nộp; GET_STATS trả điểm trung bình, cao nhất, thấp nhất, phân bố điểm theo khoảng, tỷ lệ trả lời đúng của từng câu để chỉ ra câu sai nhiều. Bảng điểm ghi rõ trạng thái “tạm thời” cho đến khi ca thi được chốt (Luồng 10). | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). Cài đặt getScores() và getStats(). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). Hiển thị bảng điểm, biểu đồ phân bố, danh sách câu sai nhiều. |
| Bước 9 | Xuất CSV: giao diện yêu cầu toàn bộ bảng điểm. Client lấy lần lượt từng trang (cỡ 200 dòng) cho đến hết, giao diện ghi tệp CSV mã hóa UTF-8 có BOM để mở được trong Excel. | — | — | Cài đặt hàm fetchAll() lấy hết các trang và báo tiến độ. | Cài đặt nút Xuất CSV, hộp thoại chọn nơi lưu và thanh tiến độ. |


10. LUỒNG 8 – SERVER LỖI VÀ CHUYỂN SANG SERVER KHÁC (FAILOVER)

Mục đích: Khi một Server dừng giữa ca thi, các sinh viên đang làm bài trên Server đó được chuyển sang Server khác, không mất bài và không được thêm thời gian.

Điều kiện bắt đầu: Một Server (ví dụ Server 3) dừng đột ngột khi đang có sinh viên làm bài.

Kết quả: Mọi bài làm của Server lỗi được tiếp tục trên Server khác trong vài giây; khi Server lỗi chạy lại, nó bắt kịp dữ liệu.



| Bước | Diễn biến của chương trình | Giang (Server 1, Load Balancer) | Huy (Server 2, Đồng bộ) | Ngọc (Server 3, Client) | Thuận (Server 4, Giao diện, Test) |
| --- | --- | --- | --- | --- | --- |
| Bước 1 | Phát hiện: kết nối nội bộ từ Load Balancer tới Server 3 báo lỗi vào/ra (nhanh nhất), hoặc Server 3 không trả PONG 3 lần liên tiếp (khoảng 3 đến 4 giây). Load Balancer đánh dấu Server 3 là DOWN, ghi nhật ký, loại khỏi danh sách chọn Server và báo cho màn hình quản trị. | Cài đặt phát hiện, trạng thái DOWN và nhật ký. | Đảm bảo luồng PING của Server 2 luôn trả lời kể cả khi thread pool nghiệp vụ đầy. | Đảm bảo luồng PING của Server 3 luôn trả lời kể cả khi thread pool nghiệp vụ đầy. | Viết kịch bản gây lỗi: tắt tiến trình Server 3 bằng lệnh kill khi 100 bot đang làm bài. |
| Bước 2 | Chọn Server thay thế cho từng kết nối đang gắn Server 3: lấy lần lượt Server UP có ít kết nối nhất (cập nhật đếm sau mỗi lần chọn để chia đều, không dồn hết vào một Server). Load Balancer gửi SERVER_SWITCHING cho từng Client qua kết nối Client – Load Balancer (kết nối này vẫn còn sống). | Cài đặt chọn Server thay thế có cập nhật bộ đếm, gửi SERVER_SWITCHING. | — | Xử lý SERVER_SWITCHING: tạm dừng gửi, giữ nguyên đáp án trong bộ nhớ, đồng hồ vẫn chạy. | Hiển thị “Đang chuyển Server, xin chờ…” và vẫn cho phép chọn đáp án. |
| Bước 3 | Load Balancer mở kết nối tới Server mới và gửi RESUME_REQUEST nội bộ gồm attemptId, người dùng, vé bài làm. Token và vé ký HMAC nên Server mới kiểm tra được mà không cần hỏi Server cũ. | Cài đặt gửi RESUME_REQUEST cho từng bài của Server lỗi. | — | — | — |
| Bước 4 | Server mới khôi phục bài: tìm attempt trong CSDL của mình. Nếu có thì dùng. Nếu chưa có (chưa kịp đồng bộ) thì gọi hàm khôi phục của module đồng bộ để hỏi 3 Server còn sống, lấy bản mới nhất của bài làm và đáp án. Vẫn không có thì dựng lại bài làm từ vé bài làm (có attemptId, seed, start_time, deadline) nên hạn nộp không bị kéo dài. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). Cài đặt hàm khôi phục bài làm (hỏi các Server còn sống, hợp nhất bản mới nhất) và dựng lại từ vé. | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). |
| Bước 5 | Server mới nhận quản lý bài: ghi ATTEMPT_REASSIGN (đổi server_id thành chính mình, version tăng, ghi sync_log) rồi trả RESUME_OK gồm trạng thái bài, thời gian còn lại, các đáp án đã có. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). Cập nhật sổ đăng ký bài làm sang Server mới. | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). |
| Bước 6 | Client nhận RESUME_OK: gửi lại toàn bộ đáp án hiện có trong bộ nhớ (ảnh chụp đầy đủ với seq mới) để ghi đè phần chưa kịp đồng bộ; hạ cờ “đang chuyển”; chạy lại tự lưu. Với đáp án Client gửi sau khi nối lại, Server dùng giờ nhận làm updated_at nên các thay đổi cục bộ chưa gửi luôn thắng bản cũ. | — | — | Cài đặt gửi lại ảnh chụp đầy đủ sau RESUME_OK và đưa các luồng nền về trạng thái bình thường. | Hiển thị “Đã khôi phục”; thêm vào báo cáo kiểm thử: số bài khôi phục, số đáp án mất (kỳ vọng 0). |
| Bước 7 | Trường hợp Server 3 đã chấm xong và trả điểm nhưng thao tác chưa kịp tới Server khác: nếu bật xác nhận nhân bản (Luồng 5, bước 6) thì bài nộp đã có ở ít nhất một Server khác. Nếu không, Client thấy bài ở trạng thái IN_PROGRESS trong khi mình đã nộp thì gửi lại SUBMIT với cùng requestId; việc chấm có tính xác định nên cho ra đúng điểm cũ. | — | Bảo đảm bài nộp được ưu tiên gửi ngay khi Server hoạt động lại để khỏi phải chấm lại. | Cài đặt đánh dấu “đã nhận kết quả” trong tệp cục bộ và quy tắc gửi lại SUBMIT. | Kiểm thử: tắt Server ngay sau khi chấm, kiểm tra không mất và không trùng bài nộp. |
| Bước 8 | Server 3 chạy lại: khởi động như Luồng 0, bắt kịp dữ liệu như Luồng 6 bước 6, trả PONG đủ 3 lần liên tiếp thì Load Balancer chuyển sang UP và tăng dần trọng số trong 30 giây. Các Client đã chuyển Server không bị chuyển ngược lại. | Cài đặt UP có làm nóng trọng số 30 giây. | Bảo đảm bắt kịp hoàn tất trước khi báo sẵn sàng. | Khởi động lại Server 3 theo Luồng 0. | Kiểm thử Server chập chờn (tắt bật liên tục) để kiểm tra không bị bật tắt liên tục. |
| Bước 9 | Bài nào Client không quay lại (sinh viên tắt máy): Load Balancer gửi danh sách Server DOWN trong mỗi PING; mỗi Server quét thêm các bài có server_id thuộc Server DOWN và đã quá deadline + 5 giây, nhận quản lý bằng ATTEMPT_REASSIGN rồi chấm như Luồng 5 bước 10. | Đưa danh sách Server DOWN vào PING. | Cài đặt ATTEMPT_REASSIGN khi nhận bài của Server DOWN. | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). |


Giới hạn đã biết: Server 1 vừa chạy Load Balancer vừa là Server nghiệp vụ nên Load Balancer là điểm lỗi duy nhất. Phương án mở rộng nếu còn thời gian: Server 2 chạy thêm Load Balancer ở chế độ dự phòng; Client có danh sách địa chỉ dự phòng và sau 3 lần thử kết nối thất bại trong 10 giây thì chuyển sang địa chỉ dự phòng (Giang cài đặt chế độ dự phòng, Ngọc cài đặt danh sách địa chỉ, Thuận kiểm thử).

11. LUỒNG 9 – CLIENT MẤT MẠNG VÀ KẾT NỐI LẠI

Mục đích: Sinh viên mất mạng, tắt ứng dụng hoặc tắt máy giữa lúc thi vẫn tiếp tục được bài làm dở với thời gian còn lại chính xác.

Điều kiện bắt đầu: Đang trong bài làm (Luồng 4).

Kết quả: Bài làm được tiếp tục trên đúng Server cũ (hoặc Server thay thế); đáp án làm khi mất mạng được gửi lên.



| Bước | Diễn biến của chương trình | Giang (Server 1, Load Balancer) | Huy (Server 2, Đồng bộ) | Ngọc (Server 3, Client) | Thuận (Server 4, Giao diện, Test) |
| --- | --- | --- | --- | --- | --- |
| Bước 1 | Client phát hiện mất kết nối: luồng đọc socket gặp lỗi hoặc hết dữ liệu, hoặc 10 giây không nhận PONG cho PING gửi mỗi 5 giây. Giao diện hiển thị biểu ngữ “Mất kết nối, đang thử lại” nhưng vẫn cho làm bài. | — | — | Cài đặt phát hiện mất kết nối và sự kiện cho giao diện. | Hiển thị biểu ngữ trạng thái và không khóa giao diện. |
| Bước 2 | Trong lúc mất kết nối: đồng hồ vẫn chạy theo giờ máy cộng offset; mọi thay đổi đáp án vẫn ghi vào bộ nhớ và tệp cục bộ; luồng tự lưu tạm dừng. Hết giờ khi vẫn chưa có mạng thì khóa giao diện, đánh dấu “chờ nộp”; Server sẽ tự chấm theo các đáp án đã nhận trước đó (đáp án chưa tới Server trước deadline + 5 giây không được tính). | — | — | Cài đặt chế độ ngoại tuyến và đánh dấu chờ nộp. | Hiển thị thông báo khi hết giờ lúc chưa có mạng. |
| Bước 3 | Kết nối lại: Client thử mở socket tới Load Balancer theo nhịp 1, 2, 4, 8, 16 rồi 30 giây. Khi nối được, gửi RESUME_SESSION kèm token. Load Balancer tra sổ đăng ký: nếu còn kết nối cũ của người dùng (nửa đứt) thì đóng nó, gán kết nối mới vào phiên, và nhờ sổ đăng ký bài làm chuyển tiếp tới đúng Server cũ (nếu Server đó đã DOWN thì chạy Luồng 8). | Cài đặt RESUME_SESSION, đóng kết nối cũ, gán kết nối mới. | — | Cài đặt vòng thử kết nối lại và gửi RESUME_SESSION. | Hiển thị số lần thử lại và nút Thử ngay. |
| Bước 4 | Server kiểm tra token còn hạn rồi trả RESUME_OK gồm trạng thái bài, thời gian còn lại, đáp án đã lưu. Client gửi lại ảnh chụp đầy đủ các đáp án hiện có; Server dùng giờ nhận làm updated_at nên thay đổi cục bộ chưa gửi luôn thắng. Sau đó tự lưu chạy lại như Luồng 4. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). Cài đặt hợp nhất đáp án sau RESUME_OK. | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). |
| Bước 5 | Sinh viên tắt ứng dụng hoặc máy tắt: khi mở lại, Client đọc tệp cục bộ attempt_<attemptId>.json; có bài đang làm dở thì giao diện hỏi “Bạn có bài thi đang làm dở, tiếp tục không?”. Chọn tiếp tục thì Client dùng token cũ nếu còn hạn để RESUME_SESSION; hết hạn thì đăng nhập lại (Luồng 1), sau đó bấm Vào thi, Server nhận ra bài IN_PROGRESS và trả bài khôi phục (Luồng 3, bước 5). | Cho phép đăng nhập lại khi kết nối cũ đã đứt (Luồng 1, bước 4). | — | Cài đặt đọc tệp cục bộ và quyết định dùng RESUME_SESSION hay đăng nhập lại. | Cài đặt hộp thoại tiếp tục bài làm dở; thử tắt đột ngột ứng dụng và máy. |


12. LUỒNG 10 – QUẢN TRỊ VIÊN GIÁM SÁT VÀ QUẢN LÝ

Mục đích: Quản trị viên theo dõi tải và trạng thái 4 Server, đồng bộ; đổi thuật toán cân bằng tải; quản lý tài khoản; xem nhật ký.

Điều kiện bắt đầu: Quản trị viên đã đăng nhập.

Kết quả: Quản trị viên nắm được tình trạng hệ thống theo thời gian thực và điều chỉnh được thuật toán, tài khoản.



| Bước | Diễn biến của chương trình | Giang (Server 1, Load Balancer) | Huy (Server 2, Đồng bộ) | Ngọc (Server 3, Client) | Thuận (Server 4, Giao diện, Test) |
| --- | --- | --- | --- | --- | --- |
| Bước 1 | Màn hình giám sát gửi ADMIN_STATUS mỗi 2 giây. Load Balancer tổng hợp: bảng Server (trạng thái UP hoặc DOWN, số kết nối, trọng số, thời gian phản hồi trung bình, thời điểm PONG gần nhất, độ lệch đồng hồ), tổng số kết nối, thuật toán hiện tại, độ dài hàng đợi START_EXAM; đồng thời hỏi từng Server bằng STATUS_REQUEST để lấy số bài IN_PROGRESS, mức dùng thread pool và SyncStatus. | Cài đặt tổng hợp ADMIN_STATUS và STATUS_REQUEST tới 4 Server. | Cung cấp SyncStatus. | Cài đặt getAdminStatus() và làm mới mỗi 2 giây. | Hiển thị bảng Server, biểu đồ số kết nối theo thời gian, bảng đồng bộ. |
| Bước 2 | Đổi thuật toán: quản trị viên chọn Round Robin hoặc Weighted Least Connections và chỉnh trọng số, gửi ADMIN_SET_ALGO. Load Balancer áp dụng ngay cho kết nối mới, không ngắt kết nối đang có, ghi nhật ký người đổi và giá trị cũ, mới. | Cài đặt đổi thuật toán và trọng số khi đang chạy. | — | Cài đặt setAlgorithm(). | Hiển thị lựa chọn thuật toán, ô trọng số, nút Áp dụng; dùng chức năng này để so sánh hiệu suất hai thuật toán trong báo cáo. |
| Bước 3 | Quản lý tài khoản: USER_CREATE, USER_UPDATE, RESET_PASSWORD, USER_LOCK; nhập hàng loạt từ CSV gửi USER_BATCH. Server kiểm tra, băm mật khẩu BCrypt, ghi bảng users và sync_log. | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). Cài đặt các hàm gọi và importUsersBatch(). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). Cài đặt màn hình quản lý tài khoản, đọc tệp CSV, báo cáo dòng lỗi. |
| Bước 4 | Xem nhật ký: LOG_QUERY (loại nhật ký là phân phối, đồng bộ hoặc audit; khoảng thời gian; từ khóa; trang) trả các dòng nhật ký tương ứng. | Cung cấp nhật ký phân phối. | Cung cấp nhật ký đồng bộ. | Cài đặt queryLogs(). | Hiển thị bảng nhật ký có lọc và phân trang. |


13. LUỒNG 11 – KẾT THÚC CA THI VÀ CHỐT KẾT QUẢ

Mục đích: Đóng ca thi, bảo đảm mọi bài đều được nộp và chấm, kiểm tra 4 CSDL khớp nhau rồi chốt bảng điểm chính thức.

Điều kiện bắt đầu: Đến giờ đóng ca thi.

Kết quả: Ca thi ở trạng thái FINALIZED; bảng điểm chính thức giống nhau ở cả 4 CSDL.



| Bước | Diễn biến của chương trình | Giang (Server 1, Load Balancer) | Huy (Server 2, Đồng bộ) | Ngọc (Server 3, Client) | Thuận (Server 4, Giao diện, Test) |
| --- | --- | --- | --- | --- | --- |
| Bước 1 | Server từ chối START_EXAM mới của ca thi với lỗi 410 EXAM_CLOSED. Lịch quét 10 giây của từng Server tự nộp và chấm mọi bài IN_PROGRESS đã quá hạn (Luồng 5, bước 10). | Cài đặt phần xử lý này trên Server 1 của mình (phần chung). | Cài đặt phần xử lý này trên Server 2 của mình (phần chung). | Cài đặt phần xử lý này trên Server 3 của mình (phần chung). | Cài đặt phần xử lý này trên Server 4 của mình (phần chung). |
| Bước 2 | Client còn kết nối và đang thi tự động nộp khi đồng hồ về 0 (Luồng 5). Sau deadline + 5 giây, mọi SAVE_ANSWERS bị từ chối bằng 410. | — | — | Bảo đảm tự động nộp hoạt động khi đồng hồ về 0. | Kiểm thử bằng bot: 200 bot cùng hết giờ một lúc, kiểm tra tất cả bài được nộp đúng một lần. |
| Bước 3 | Chốt: sau giờ đóng cộng 1 phút, điều phối viên trên Server 2 kiểm tra đủ 3 điều kiện: không còn bài IN_PROGRESS ở cả 4 Server; số thao tác chờ gửi bằng 0 ở cả 4 Server; số bản ghi và mã băm của attempts, attempt_answers thuộc ca thi giống nhau ở cả 4 CSDL. Đủ điều kiện thì ghi thao tác EXAM_FINALIZED. Chưa đủ thì kiểm tra lại mỗi 30 giây; quá 10 phút vẫn chưa đủ thì cảnh báo quản trị viên. | — | Cài đặt điều phối viên chốt ca thi, kiểm tra ba điều kiện, EXAM_FINALIZED và cảnh báo quá 10 phút. | — | Viết kiểm thử toàn vẹn: số bài nộp bằng số sinh viên đã vào thi, không có bài trùng, không có bài mất. |
| Bước 4 | Giảng viên mở bảng điểm: trạng thái chuyển từ “tạm thời” sang “đã chốt”, có thể xuất CSV (Luồng 7, bước 8 và 9). | — | — | Cập nhật trạng thái bảng điểm khi nhận EXAM_FINALIZED. | Hiển thị nhãn “Đã chốt” và bật nút xuất CSV chính thức. |
| Bước 5 | Sau ca thi, nhóm kiểm thử thu thập số liệu: thời gian phản hồi trung bình, số bài nộp mỗi giây cao nhất, phân bố tải giữa 4 Server, số lần failover, số bài khôi phục, số lần kiểm tra nhất quán thất bại. Số liệu đưa vào báo cáo. | Cung cấp nhật ký phân phối và nhật ký failover. | Cung cấp nhật ký đồng bộ và kết quả kiểm tra nhất quán. | Cung cấp nhật ký Client (số lần mất kết nối, thời gian kết nối lại). | Tổng hợp số liệu, vẽ biểu đồ và viết báo cáo hiệu suất. |


14. DANH MỤC THÔNG ĐIỆP VÀ MÃ LỖI

14.1. Danh mục thông điệp



| Thông điệp | Hướng | Nội dung chính (payload) | Phản hồi | Người xử lý |
| --- | --- | --- | --- | --- |
| PING / PONG | Client hoặc Load Balancer → Server | Không có | PONG: serverTime, tải, danh sách Server DOWN (khi từ Load Balancer) | Mỗi Server (phần chung); Load Balancer (Giang) |
| LOGIN | Client → Server | Mã, mật khẩu | token, họ tên, vai trò, serverTime | Mỗi Server; Load Balancer đăng ký phiên |
| RESUME_SESSION | Client → Load Balancer | token | RESUME_OK hoặc lỗi 401 | Load Balancer (Giang); Server |
| SESSION_REPLACED | Load Balancer → Client | Lý do | Không có | Client (Ngọc) |
| LIST_EXAMS | Client → Server | Từ khóa, trạng thái, trang, cỡ trang | Danh sách ca thi, tổng số dòng | Mỗi Server |
| START_EXAM | Client → Server | examId | attemptId, vé bài làm, deadline, đề đã xáo trộn; hoặc QUEUED | Mỗi Server; hàng đợi ở Load Balancer |
| QUEUED | Load Balancer → Client | Vị trí trong hàng đợi | Không có | Client (Ngọc) |
| SAVE_ANSWERS | Client → Server | attemptId, seq, danh sách (maCau, maPhuongAn, daDanhDau) | Số câu đã lưu, serverTime, số giây còn lại | Mỗi Server |
| SUBMIT | Client → Server | attemptId | Điểm, số câu đúng, tổng số câu, cờ nộp muộn | Mỗi Server |
| GET_RESULT / LIST_HISTORY | Client → Server | attemptId hoặc trang | Kết quả hoặc lịch sử các bài đã thi | Mỗi Server |
| QUESTION_LIST / CREATE / UPDATE / DELETE | Client → Server | Điều kiện lọc hoặc dữ liệu câu hỏi, baseVersion | Danh sách hoặc mã và version mới | Mỗi Server |
| EXAM_CREATE / EXAM_MONITOR | Client → Server | Dữ liệu ca thi hoặc examId | Mã ca thi, trạng thái sẵn sàng; hoặc số lượng theo trạng thái | Mỗi Server |
| GET_SCORES / GET_STATS | Client → Server | examId, từ khóa, trang, cỡ trang | Bảng điểm hoặc thống kê, nhãn tạm thời hoặc đã chốt | Mỗi Server |
| USER_CREATE / UPDATE / RESET_PASSWORD / LOCK / BATCH | Client → Server | Dữ liệu tài khoản | Kết quả từng tài khoản | Mỗi Server |
| ADMIN_STATUS / ADMIN_SET_ALGO / LOG_QUERY | Client → Load Balancer | Thuật toán, trọng số, điều kiện lọc nhật ký | Trạng thái hệ thống hoặc kết quả đổi cấu hình hoặc dòng nhật ký | Load Balancer (Giang), Huy cung cấp SyncStatus |
| SERVER_SWITCHING | Load Balancer → Client | Lý do | Không có | Client (Ngọc) |
| RESUME_REQUEST / RESUME_OK | Load Balancer → Server; Server → Load Balancer | attemptId, người dùng, vé bài làm; trạng thái bài và đáp án | RESUME_OK hoặc lỗi 404 | Mỗi Server; khôi phục: module đồng bộ (Huy) |
| STATUS_REQUEST | Load Balancer → Server | Không có | Số bài đang làm, mức dùng thread pool, SyncStatus | Mỗi Server |
| SYNC_HELLO / SYNC_BATCH / SYNC_ACK | Server ↔ Server (cổng đồng bộ) | seq đã thấy theo từng Server gốc; danh sách thao tác; seq đã áp dụng | SYNC_ACK | Module đồng bộ (Huy) |


14.2. Mã lỗi



| Mã | Tên | Ý nghĩa | Bên tạo lỗi | Cách Client và giao diện xử lý |
| --- | --- | --- | --- | --- |
| 400 | BAD_REQUEST | Thông điệp sai khuôn dạng hoặc dữ liệu không hợp lệ | Server | Hiển thị lỗi, không thử lại tự động |
| 401 | UNAUTHENTICATED | Token sai, hết hạn hoặc chưa đăng nhập | Server | Thử RESUME_SESSION; không được thì về màn hình đăng nhập |
| 403 | FORBIDDEN / NOT_ASSIGNED | Không đủ quyền, hoặc chưa được gán ca thi | Server | Hiển thị thông báo |
| 404 | NOT_FOUND / ATTEMPT_NOT_FOUND | Không có dữ liệu yêu cầu | Server | Với bài làm: gửi lại RESUME kèm vé bài làm |
| 409 | ALREADY_SUBMITTED / VERSION_CONFLICT / SESSION_IN_EXAM | Bài đã nộp; phiên bản dữ liệu đã đổi; tài khoản đang thi ở nơi khác | Server hoặc Load Balancer | Hiển thị thông báo; với VERSION_CONFLICT mở hộp thoại so sánh |
| 410 | EXAM_CLOSED / ATTEMPT_EXPIRED | Ca thi đã đóng; bài đã quá hạn | Server | Chuyển sang nộp bài hoặc về danh sách ca thi |
| 423 | IN_USE_BY_ACTIVE_EXAM | Dữ liệu đang được ca thi diễn ra sử dụng, không sửa hoặc xóa được | Server | Hiển thị thông báo |
| 425 | EXAM_NOT_OPEN | Chưa tới giờ mở ca thi | Server | Hiển thị đếm ngược |
| 429 | BUSY | Quá tải; kèm retryAfter | Load Balancer | Chờ retryAfter rồi thử lại tối đa 3 lần |
| 500 | INTERNAL | Lỗi không mong đợi của Server | Server | Thử lại một lần; vẫn lỗi thì báo người dùng |
| 503 | NO_SERVER_AVAILABLE | Không có Server nào hoạt động | Load Balancer | Thử lại theo chu kỳ kết nối lại |


15. TỔNG HỢP NHIỆM VỤ CỦA TỪNG THÀNH VIÊN THEO LUỒNG

Bảng dưới cho biết mức tham gia: Chính là người cài đặt phần cốt lõi của luồng; Phối hợp là người cung cấp hoặc dùng một phần; Phần chung là người chỉ cài đặt phần xử lý chung trên Server của mình.



| Luồng | Giang | Huy | Ngọc | Thuận |
| --- | --- | --- | --- | --- |
| Luồng 0 – Khởi động | Chính: Load Balancer, kiểm tra sức khỏe | Chính: khởi động module đồng bộ, script lược đồ | Chính: kết nối Client, đo offset | Phối hợp: màn hình đăng nhập, dữ liệu mẫu |
| Luồng 1 – Đăng nhập | Chính: chọn Server, sổ đăng ký phiên | Phần chung; nhân bản audit_log | Chính: login() và xử lý lỗi | Chính: màn hình đăng nhập |
| Luồng 2 – Danh sách ca thi | Phối hợp: chuyển tiếp theo phiên | Phối hợp: chỉ mục, nhân bản đề | Chính: listExams(), bộ nhớ đệm | Chính: bảng danh sách, tìm kiếm, phân trang |
| Luồng 3 – Vào thi | Chính: sticky, hàng đợi đầu ca thi | Phối hợp: ATTEMPT_CREATE ưu tiên | Chính: startExam(), ba luồng nền | Chính: màn hình làm bài, kiểm thử 200 bot |
| Luồng 4 – Làm bài | Phối hợp: chuyển tiếp sticky | Phối hợp: gom lô ANSWER_UPSERT | Chính: đồng hồ, tự lưu, không chặn | Chính: giao diện chọn đáp án, trạng thái lưu |
| Luồng 5 – Nộp và chấm | Phối hợp: sticky, cập nhật sổ | Chính: waitForReplication, ATTEMPT_REASSIGN | Chính: submit() idempotent | Chính: hộp thoại nộp, màn hình kết quả |
| Luồng 6 – Đồng bộ | Phối hợp: gom trạng thái | Chính: toàn bộ module đồng bộ | Phối hợp: lấy trạng thái cho quản trị | Phối hợp: kiểm thử ngắt kết nối, nhất quán |
| Luồng 7 – Giảng viên | Phối hợp: chọn Server | Chính: EXAM_PACKAGE, isReplicated | Chính: API câu hỏi, đề, điểm, fetchAll | Chính: màn hình giảng viên, xuất CSV |
| Luồng 8 – Failover | Chính: phát hiện, chọn Server thay thế | Chính: khôi phục bài làm, ATTEMPT_REASSIGN | Chính: SERVER_SWITCHING, gửi lại đáp án | Chính: kịch bản gây lỗi, đo số bài mất |
| Luồng 9 – Mất mạng | Phối hợp: RESUME_SESSION | Phần chung | Chính: ngoại tuyến, kết nối lại, tệp cục bộ | Chính: giao diện trạng thái, tiếp tục bài dở |
| Luồng 10 – Quản trị | Chính: ADMIN_STATUS, đổi thuật toán | Phối hợp: SyncStatus, nhật ký đồng bộ | Chính: API quản trị, nhập hàng loạt | Chính: màn hình giám sát và tài khoản |
| Luồng 11 – Chốt ca thi | Phối hợp: nhật ký phân phối | Chính: điều phối viên chốt ca thi | Phối hợp: tự nộp khi hết giờ | Chính: kiểm tra toàn vẹn, báo cáo hiệu suất |


Tóm tắt nhiệm vụ từng người

Giang chịu trách nhiệm cổng vào của hệ thống. Giang cài đặt Load Balancer trên Server 1: nhận kết nối, chọn Server theo Round Robin và Weighted Least Connections, giữ sổ đăng ký phiên và sổ đăng ký bài làm, hàng đợi đầu ca thi, kiểm tra sức khỏe, đổi Server khi có lỗi, tổng hợp số liệu giám sát. Giang cũng cài đặt phần chung trên Server 1 và viết tài liệu kiến trúc hệ thống.

Huy chịu trách nhiệm dữ liệu nhất quán. Huy cài đặt module đồng bộ cho cả 4 Server: ghi sync_log, gửi và áp dụng thao tác, quy tắc xung đột, bắt kịp sau mất kết nối, kiểm tra nhất quán, khôi phục bài làm, chốt ca thi; thiết kế lược đồ CSDL chung và script dữ liệu mẫu. Huy cũng cài đặt phần chung trên Server 2 và viết tài liệu cơ chế đồng bộ.

Ngọc chịu trách nhiệm phía Client. Ngọc đặc tả giao thức thông điệp và cài đặt thư viện ExamClient: kết nối, đo độ lệch giờ, ba luồng nền, tự lưu, nộp bài không trùng, ngoại tuyến, kết nối lại, khôi phục bài, các hàm tìm kiếm, lọc, phân trang, thêm, sửa, xóa và lấy toàn bộ trang. Ngọc cũng cài đặt phần chung trên Server 3 và viết tài liệu giao thức Client – Server.

Thuận chịu trách nhiệm giao diện và chất lượng. Thuận xây dựng giao diện JavaFX cho sinh viên, giảng viên và quản trị viên; công cụ sinh dữ liệu lớn, bot giả lập nhiều Client và các kịch bản gây lỗi (tắt Server, ngắt mạng, nộp trùng, hết giờ, đăng nhập hai nơi); đo hiệu suất và kiểm tra toàn vẹn kết quả. Thuận cũng cài đặt phần chung trên Server 4 và viết báo cáo đồ án, hướng dẫn sử dụng.
