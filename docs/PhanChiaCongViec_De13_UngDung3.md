BẢNG PHÂN CHIA CÔNG VIỆC VÀ KẾ HOẠCH TIẾN ĐỘ

Đề 13 – Ứng dụng 3: Hệ thống thi trắc nghiệm trực tuyến theo mô hình đa Server

cho phép truy xuất cơ sở dữ liệu lớn

I. THÔNG TIN ĐỀ TÀI

Mô tả: Xây dựng hệ thống thi trắc nghiệm trực tuyến cho phép nhiều sinh viên thi cùng lúc, nộp bài, chấm điểm tự động và lưu kết quả. Triển khai 4 Server và 4 cơ sở dữ liệu. Chương trình Client cho phép sinh viên làm bài thi; giảng viên quản lý ngân hàng câu hỏi, đề thi, ca thi và xem kết quả; quản trị viên giám sát hệ thống.

Công nghệ: Java (Socket TCP, đa luồng, JDBC, JavaFX). Đề xuất: CSDL quan hệ MySQL/PostgreSQL, JSON cho thông điệp Client–Server, JUnit để kiểm thử, Git để quản lý mã nguồn.

Mở rộng: Các chương trình Server phải xử lý cân bằng tải trọng giữa các Server (đặc biệt vào thời điểm bắt đầu ca thi) và xử lý đồng bộ dữ liệu giữa 4 cơ sở dữ liệu.

Thành viên: Giang, Huy, Ngọc, Thuận (4 người)

Mục tiêu nghiệm thu (đề xuất, có thể điều chỉnh khi họp nhóm): ≥ 200 Client thi đồng thời; nộp bài và chấm điểm ≤ 2 giây/bài ở tải bình thường; không mất và không trùng bài nộp khi 1 Server bị lỗi giữa ca thi; cơ sở dữ liệu mẫu > 10.000 bản ghi.

II. PHÂN TÍCH YÊU CẦU VÀ KIẾN TRÚC TỔNG QUAN

1. Chức năng theo vai trò người dùng



| Vai trò | Chức năng chính |
| --- | --- |
| Sinh viên | • Đăng nhập (mỗi tài khoản chỉ có 1 phiên thi tại một thời điểm) • Xem danh sách kỳ thi / ca thi mình được phép tham gia • Vào thi: nhận đề đã được xáo trộn thứ tự câu hỏi và đáp án; xem đồng hồ đếm ngược • Chọn / đổi đáp án, đánh dấu câu cần xem lại; hệ thống tự động lưu tạm đáp án định kỳ • Nộp bài (hoặc tự động nộp khi hết giờ); xem điểm và lịch sử các bài đã thi |
| Giảng viên | • Quản lý ngân hàng câu hỏi: thêm, sửa, xóa; tìm kiếm, lọc theo môn / chương / độ khó, phân trang • Tạo đề thi và ca thi: chọn câu thủ công hoặc ngẫu nhiên theo cấu trúc; đặt thời lượng, thời điểm mở / đóng; gán danh sách sinh viên • Theo dõi ca thi đang diễn ra (số sinh viên đã vào thi / đã nộp) • Xem bảng điểm, thống kê (điểm trung bình, phân bố điểm, câu hỏi sai nhiều); xuất CSV |
| Quản trị viên | • Quản lý tài khoản sinh viên / giảng viên • Giám sát trạng thái 4 Server (hoạt động / lỗi, số kết nối, tải), xem nhật ký phân phối tải và nhật ký đồng bộ • Cấu hình thuật toán cân bằng tải và các tham số hệ thống |


2. Luồng nghiệp vụ chính và thành phần chịu trách nhiệm



| Bước | Diễn giải | Thành phần liên quan |
| --- | --- | --- |
| 1. Đăng nhập | Client gửi LOGIN tới Load Balancer; Load Balancer chọn Server phù hợp; Server xác thực với CSDL của mình và tạo phiên đăng nhập. | Client (Ngọc), Load Balancer (Giang), Server 1–4 |
| 2. Vào thi | Server kiểm tra quyền và thời gian ca thi, tạo bài làm (attempt), xáo trộn câu hỏi / đáp án theo seed của bài làm, lưu và trả đề. Load Balancer gắn bài làm với Server đang xử lý (sticky session). | Server 1–4, Load Balancer (Giang) |
| 3. Làm bài | Client tự động lưu đáp án định kỳ (ví dụ 15–30 giây) và khi đổi câu; Server ghi đáp án tạm; module đồng bộ nhân bản sang các CSDL còn lại. | Client (Ngọc), Server 1–4, Đồng bộ (Huy) |
| 4. Nộp bài | Client gửi SUBMIT kèm mã bài làm (idempotent). Server kiểm tra hạn nộp theo giờ Server, chấm điểm tự động, lưu kết quả và trả điểm. Nộp trùng chỉ ghi nhận lần đầu. | Client (Ngọc), Server 1–4 |
| 5. Đồng bộ kết quả | Bài nộp và kết quả được nhân bản sang 3 CSDL còn lại; xung đột (nếu có) được giải quyết theo quy tắc đã thống nhất. | Đồng bộ (Huy) |
| 6. Xử lý sự cố | Server lỗi: Load Balancer phát hiện bằng health check → chuyển Client sang Server khác → khôi phục bài làm từ dữ liệu đã đồng bộ → sinh viên làm bài tiếp. Client mất mạng: lưu đệm đáp án, tự kết nối lại và tiếp tục; thời gian còn lại luôn tính theo giờ Server. | Load Balancer (Giang), Đồng bộ (Huy), Client (Ngọc) |


3. Các điểm kỹ thuật đặc thù của bài toán thi trực tuyến

Đồng hồ do Server quyết định: hạn nộp bài tính theo giờ Server; Client chỉ hiển thị đếm ngược theo độ lệch thời gian đã đo.

Nộp bài idempotent: mỗi bài làm có mã duy nhất; gửi lại do mất mạng không tạo bài nộp thứ hai.

Sticky session và khôi phục: xáo trộn câu hỏi theo seed lưu trong bài làm để Server khác dựng lại đúng đề khi failover.

Đột biến tải đầu ca thi: hàng trăm sinh viên vào thi cùng lúc, cần giới hạn kết nối / hàng đợi (admission control) và thread pool có giới hạn.

Toàn vẹn kết quả: dữ liệu bài nộp ghi theo kiểu chỉ thêm (append-only); không mất và không trùng kết quả khi Server lỗi.

4. Lược đồ CSDL chung (chốt ở Tuần 1, do Huy chủ trì; cả 4 CSDL dùng cùng lược đồ)



| Bảng | Nội dung chính | Kiểu dữ liệu đồng bộ / quy tắc xung đột |
| --- | --- | --- |
| users | Mã, họ tên, vai trò (SV / GV / Admin), mật khẩu băm | Danh mục – nhân bản đến mọi CSDL; Last-Write-Wins theo version |
| subjects, questions, options | Môn học, câu hỏi, các đáp án, đáp án đúng, chương, độ khó | Danh mục – nhân bản; Last-Write-Wins theo version |
| exams, exam_questions, exam_assignments | Đề / ca thi, thời lượng, thời điểm mở – đóng, câu hỏi của đề, sinh viên được thi | Danh mục – nhân bản; Last-Write-Wins theo version |
| attempts | Mã bài làm (UUID), sinh viên, ca thi, Server xử lý, seed xáo trộn, giờ bắt đầu, hạn nộp, trạng thái, giờ nộp, điểm | Dữ liệu bài thi – nhân bản; bài nộp bất biến, nộp trùng: nộp đầu tiên thắng |
| attempt_answers | Mã bài làm, câu hỏi, đáp án đã chọn, đánh dấu, thời điểm cập nhật | Nhân bản; Last-Write-Wins theo thời điểm cập nhật cho đến khi nộp bài |
| sync_log | Mã thao tác (op_id), bảng, khóa, loại thao tác, version, Server gốc, trạng thái | Cục bộ – phục vụ cơ chế replication và đồng bộ bù |
| audit_log | Sự kiện đăng nhập, vào / rời thi, nộp bài, địa chỉ IP | Append-only; đồng bộ để đối soát |


III. NGUYÊN TẮC PHÂN CHIA CÔNG BẰNG

Mỗi thành viên chịu trách nhiệm xây dựng 1 Server hoàn chỉnh với cùng một bộ chức năng thi (ngang nhau).

Mỗi thành viên thiết kế, tạo và quản lý 1 Cơ sở dữ liệu riêng theo lược đồ chung (ngang nhau).

Mỗi thành viên đảm nhận 1 module nâng cao có độ khó tương đương nhau (★★★).

Mỗi thành viên chủ trì 1 hạng mục chung của nhóm (kiến trúc và quy ước; lược đồ CSDL; đặc tả giao thức; kế hoạch kiểm thử).

Mỗi thành viên tự viết tài liệu cho phần mình phụ trách (ngang nhau).

Review chéo code theo vòng tròn: Giang → Server 2 (Huy); Huy → Server 3 (Ngọc); Ngọc → Server 4 (Thuận); Thuận → Server 1 (Giang).

Phối hợp: mỗi thành viên làm trên nhánh Git riêng, gộp qua pull request có review; họp nhóm 2 lần/tuần; các điểm phụ thuộc xử lý bằng interface đã chốt và mock / stub để không phải chờ nhau (xem mục VI).

IV. SO SÁNH ĐỘ KHÓ GIỮA CÁC THÀNH VIÊN



| Hạng mục | Giang | Huy | Ngọc | Thuận |
| --- | --- | --- | --- | --- |
| Xây dựng Server | Server 1 (Main Server + cổng Load Balancer) | Server 2 | Server 3 | Server 4 |
| Cơ sở dữ liệu | CSDL 1 | CSDL 2 | CSDL 3 | CSDL 4 |
| Module nâng cao (Độ khó ★★★) | Load Balancer (Cân bằng tải, sticky session, failover) | Đồng bộ dữ liệu (Replication, xung đột, khôi phục bài thi) | Chương trình Client + Đa luồng (Đồng hồ, autosave, reconnect) | Giao diện người dùng (JavaFX) + Kiểm thử tải |
| Hạng mục chung chủ trì | Kiến trúc tổng thể, quy ước code và Git | Lược đồ CSDL chung, script dữ liệu mẫu | Đặc tả giao thức Client–Server | Kế hoạch và bộ test case tích hợp |
| Review chéo | Server 2 (Huy) | Server 3 (Ngọc) | Server 4 (Thuận) | Server 1 (Giang) |
| Tài liệu | Tài liệu kiến trúc hệ thống và cân bằng tải | Tài liệu cơ chế đồng bộ | Tài liệu giao thức Client–Server | Báo cáo đồ án + Hướng dẫn sử dụng |
| Độ khó tổng thể | ★★★★☆ | ★★★★☆ | ★★★★☆ | ★★★★☆ |


V. BẢNG PHÂN CHIA CÔNG VIỆC CHI TIẾT



| STT | Thành viên | Vai trò | Phần chung(Mỗi người đều làm) | Module nâng cao riêng(Độ khó tương đương) |
| --- | --- | --- | --- | --- |
| 1 | Giang | Trưởng nhóm Server 1 & Load Balancer | • Xây dựng Server 1 hoàn chỉnh: TCP server, thread pool xử lý nhiều Client • Thiết kế & tạo CSDL 1 theo lược đồ chung (khóa, chỉ mục, dữ liệu mẫu) • Kết nối Server 1 với CSDL 1 (connection pool, giao dịch) • Module xác thực và quản lý phiên (mỗi tài khoản 1 phiên thi) • Module vào thi: kiểm tra quyền / ca thi, tạo bài làm, xáo trộn câu hỏi và đáp án theo seed, trả đề • Lưu đáp án tạm (autosave) và khôi phục bài làm đang dở (resume) • Nộp bài và chấm điểm tự động: kiểm tra hạn nộp theo giờ Server, xử lý nộp trùng (idempotent), lưu kết quả • Truy vấn kết quả, thống kê; thêm / sửa / xóa câu hỏi và đề thi (API cho giảng viên) • Xử lý đa luồng trên Server 1: đồng bộ truy cập dữ liệu dùng chung, tránh race condition • Cung cấp heartbeat / thông tin tải cho Load Balancer; ghi log; unit test phần mình • Viết tài liệu phần mình | • Chủ trì: thiết kế kiến trúc tổng quan hệ thống, quy ước code, cấu trúc project và quy trình Git • Xây dựng module Load Balancer trên Server 1 (proxy TCP, cổng duy nhất cho Client) • Triển khai thuật toán cân bằng tải: Round Robin, Weighted Least Connections (Server 1 vừa là Load Balancer vừa là Server xử lý nên được đặt trọng số thấp hơn) • Sticky session: gắn bài làm / sinh viên với Server đang xử lý • Giám sát trạng thái các Server (health check định kỳ, bảng trạng thái) • Xử lý failover khi Server bị lỗi: chuyển Client sang Server khác, phối hợp Huy khôi phục bài làm • Kiểm soát đột biến tải đầu ca thi: giới hạn kết nối, hàng đợi (admission control) • Cung cấp số liệu giám sát (kết nối, tải từng Server, nhật ký phân phối) cho màn hình Admin • Viết tài liệu kiến trúc hệ thống và cân bằng tải |
| 2 | Huy | Thành viên Server 2 & Đồng bộ dữ liệu | • Xây dựng Server 2 hoàn chỉnh: TCP server, thread pool xử lý nhiều Client • Thiết kế & tạo CSDL 2 theo lược đồ chung (khóa, chỉ mục, dữ liệu mẫu) • Kết nối Server 2 với CSDL 2 (connection pool, giao dịch) • Module xác thực và quản lý phiên (mỗi tài khoản 1 phiên thi) • Module vào thi: kiểm tra quyền / ca thi, tạo bài làm, xáo trộn câu hỏi và đáp án theo seed, trả đề • Lưu đáp án tạm (autosave) và khôi phục bài làm đang dở (resume) • Nộp bài và chấm điểm tự động: kiểm tra hạn nộp theo giờ Server, xử lý nộp trùng (idempotent), lưu kết quả • Truy vấn kết quả, thống kê; thêm / sửa / xóa câu hỏi và đề thi (API cho giảng viên) • Xử lý đa luồng trên Server 2: đồng bộ truy cập dữ liệu dùng chung, tránh race condition • Cung cấp heartbeat / thông tin tải cho Load Balancer; ghi log; unit test phần mình • Viết tài liệu phần mình | • Chủ trì: thiết kế lược đồ CSDL chung (ERD), script tạo bảng và dữ liệu mẫu • Thiết kế giao thức đồng bộ dữ liệu (sync log, mã thao tác op_id, version) • Triển khai replication giữa 4 CSDL: danh mục (người dùng, câu hỏi, đề thi, phân ca) và dữ liệu bài thi (đáp án tạm, bài nộp, kết quả) • Xử lý xung đột khi cập nhật đồng thời (Conflict Resolution): Last-Write-Wins theo version cho danh mục; nộp đầu tiên thắng cho bài nộp trùng • Đảm bảo tính nhất quán dữ liệu (Eventual Consistency); tùy chọn ghi bài nộp vào ≥ 2 CSDL trước khi báo thành công • Đồng bộ bù (catch-up) khi Server mất kết nối rồi kết nối lại; hàng đợi đồng bộ và retry • Cung cấp API khôi phục bài làm cho failover (phối hợp Giang) • Log, theo dõi trạng thái đồng bộ; kiểm tra nhất quán giữa 4 CSDL (đếm bản ghi / checksum) • Viết tài liệu cơ chế đồng bộ |
| 3 | Ngọc | Thành viên Server 3 & Client App | • Xây dựng Server 3 hoàn chỉnh: TCP server, thread pool xử lý nhiều Client • Thiết kế & tạo CSDL 3 theo lược đồ chung (khóa, chỉ mục, dữ liệu mẫu) • Kết nối Server 3 với CSDL 3 (connection pool, giao dịch) • Module xác thực và quản lý phiên (mỗi tài khoản 1 phiên thi) • Module vào thi: kiểm tra quyền / ca thi, tạo bài làm, xáo trộn câu hỏi và đáp án theo seed, trả đề • Lưu đáp án tạm (autosave) và khôi phục bài làm đang dở (resume) • Nộp bài và chấm điểm tự động: kiểm tra hạn nộp theo giờ Server, xử lý nộp trùng (idempotent), lưu kết quả • Truy vấn kết quả, thống kê; thêm / sửa / xóa câu hỏi và đề thi (API cho giảng viên) • Xử lý đa luồng trên Server 3: đồng bộ truy cập dữ liệu dùng chung, tránh race condition • Cung cấp heartbeat / thông tin tải cho Load Balancer; ghi log; unit test phần mình • Viết tài liệu phần mình | • Chủ trì: đặc tả giao thức Client–Server (JSON qua Socket TCP): LOGIN, LIST_EXAMS, START_EXAM, SAVE_ANSWER, SUBMIT, GET_RESULT, QUESTION_CRUD, PING… • Xây dựng thư viện Client (ExamClient API) kết nối đến Load Balancer (Server 1); cung cấp interface cho giao diện của Thuận • Xử lý đa luồng phía Client: luồng gửi / nhận không blocking, luồng đồng hồ đếm ngược (đồng bộ giờ Server), luồng autosave định kỳ, luồng nhận thông báo • Xây dựng chức năng truy xuất dữ liệu: tìm kiếm, lọc, phân trang (ngân hàng câu hỏi, ca thi, bảng điểm, lịch sử) • Xây dựng chức năng cập nhật dữ liệu: thêm, sửa, xóa câu hỏi, đề thi, ca thi • Xử lý mất kết nối: lưu đệm đáp án cục bộ, tự động reconnect, tiếp tục bài làm, gửi lại có kiểm soát • Tự động nộp bài khi hết giờ • Viết tài liệu giao thức Client–Server |
| 4 | Thuận | Thành viên Server 4 & Giao diện & Test | • Xây dựng Server 4 hoàn chỉnh: TCP server, thread pool xử lý nhiều Client • Thiết kế & tạo CSDL 4 theo lược đồ chung (khóa, chỉ mục, dữ liệu mẫu) • Kết nối Server 4 với CSDL 4 (connection pool, giao dịch) • Module xác thực và quản lý phiên (mỗi tài khoản 1 phiên thi) • Module vào thi: kiểm tra quyền / ca thi, tạo bài làm, xáo trộn câu hỏi và đáp án theo seed, trả đề • Lưu đáp án tạm (autosave) và khôi phục bài làm đang dở (resume) • Nộp bài và chấm điểm tự động: kiểm tra hạn nộp theo giờ Server, xử lý nộp trùng (idempotent), lưu kết quả • Truy vấn kết quả, thống kê; thêm / sửa / xóa câu hỏi và đề thi (API cho giảng viên) • Xử lý đa luồng trên Server 4: đồng bộ truy cập dữ liệu dùng chung, tránh race condition • Cung cấp heartbeat / thông tin tải cho Load Balancer; ghi log; unit test phần mình • Viết tài liệu phần mình | • Chủ trì: kế hoạch kiểm thử và bộ test case tích hợp toàn hệ thống • Thiết kế & xây dựng giao diện JavaFX: Đăng nhập; Danh sách ca thi; Làm bài (câu hỏi, bảng điều hướng câu, đánh dấu câu, đồng hồ, trạng thái kết nối / lưu); Kết quả và lịch sử • Giao diện giảng viên: quản lý câu hỏi, tạo đề / ca thi, bảng điểm, thống kê (biểu đồ), xuất CSV; giao diện Admin: giám sát Server, quản lý tài khoản • Tích hợp giao diện với module Client (Ngọc) qua interface đã chốt; dùng mock khi chưa có • Tạo dữ liệu mẫu lớn (> 10.000 bản ghi) bằng công cụ sinh dữ liệu • Xây dựng công cụ giả lập nhiều Client đồng thời và stress test (50 → 100 → 200+ Client) • Kịch bản test: tắt Server giữa ca thi, ngắt mạng Client, nộp trùng, hết giờ, đăng nhập 2 nơi • Đo hiệu suất (thời gian phản hồi, số bài nộp/giây, phân bố tải) và viết báo cáo kết quả • Viết báo cáo đồ án và hướng dẫn sử dụng |


VI. ĐIỂM PHỐI HỢP GIỮA CÁC THÀNH VIÊN

Các bàn giao dưới đây giúp mỗi người làm song song mà không phải chờ nhau; sau khi chốt, mọi thay đổi phải thông qua họp nhóm.



| STT | Sản phẩm bàn giao | Bên cung cấp | Bên sử dụng | Thời hạn |
| --- | --- | --- | --- | --- |
| 1 | Kiến trúc tổng quan, quy ước code, cấu trúc project, quy trình Git | Giang | Cả nhóm | Cuối Tuần 1 |
| 2 | Lược đồ CSDL chung (v1), sau đó script tạo bảng và dữ liệu mẫu nhỏ (v2) | Huy | Cả nhóm | v1: cuối Tuần 1; v2: giữa Tuần 2 |
| 3 | Đặc tả thông điệp Client–Server v1; đóng băng v1.0 sau khi 4 Server chạy được | Ngọc | Giang, Huy, Thuận, Server 1–4 | v1: cuối Tuần 1; v1.0: cuối Tuần 3 |
| 4 | Interface ExamClient API (kèm bản mock / stub) | Ngọc | Thuận | Giữa Tuần 2 |
| 5 | Endpoint heartbeat / thông tin tải trên từng Server | Mỗi người (Server của mình) | Giang | Cuối Tuần 3 |
| 6 | API đồng bộ nội bộ (push / pull thao tác, khôi phục bài làm) | Huy | Giang, Server 1–4 | Giữa Tuần 4 |
| 7 | Dữ liệu mẫu lớn và công cụ giả lập nhiều Client | Thuận | Cả nhóm | Giữa Tuần 5 |
| 8 | Số liệu giám sát Load Balancer và trạng thái đồng bộ cho màn hình Admin | Giang, Huy | Thuận | Giữa Tuần 5 |
| 9 | Bản build tích hợp lần 1 và lần 2 | Cả nhóm | Cả nhóm | Cuối Tuần 5; cuối Tuần 6 |


VII. BẢNG KẾ HOẠCH TIẾN ĐỘ

Thời gian dự kiến: 8 tuần  |  Mỗi thành viên thực hiện song song, cùng tiến độ



| Tuần | Giang (Server 1 + Load Balancer) | Huy (Server 2 + Đồng bộ) | Ngọc (Server 3 + Client) | Thuận (Server 4 + UI + Test) |
| --- | --- | --- | --- | --- |
| Tuần 1 Nghiên cứu & Thiết kế | • Nghiên cứu mô hình đa Server, thuật toán cân bằng tải (Round Robin, Least Connections), sticky session • Thiết kế kiến trúc tổng quan và luồng thi / failover • Chốt công nghệ, cấu trúc project, quy ước Git • Họp nhóm thống nhất thiết kế | • Nghiên cứu replication, Eventual Consistency, Conflict Resolution (LWW, version) • Thiết kế lược đồ CSDL chung (ERD) v1; chọn hệ quản trị CSDL cho cả nhóm • Thiết kế giao thức đồng bộ (sync log, op_id) | • Nghiên cứu Socket TCP, đa luồng và ExecutorService trong Java • Thiết kế đặc tả thông điệp Client–Server v1 (JSON) • Thiết kế cấu trúc Client và interface ExamClient | • Nghiên cứu JavaFX • Phân tích luồng màn hình, thiết kế mockup (Figma / draw) • Lên kế hoạch test và tiêu chí hiệu năng • Thiết kế cấu trúc dữ liệu mẫu |
| Tuần 2 Xây dựng nền tảng Server + DB | • Tạo project Server 1; thiết kế & tạo CSDL 1 • Lập trình TCP server, thread pool; xác thực và phiên • Thêm / sửa / xóa câu hỏi và đề thi cơ bản • Kết nối Server 1 với CSDL 1 • Dựng khung Load Balancer (nhận kết nối, chuyển tiếp tới 1 Server) | • Tạo project Server 2; thiết kế & tạo CSDL 2 • Lập trình TCP server, thread pool; xác thực và phiên • Thêm / sửa / xóa câu hỏi và đề thi cơ bản • Kết nối Server 2 với CSDL 2 • Hoàn thiện lược đồ chung v2, script tạo bảng và dữ liệu mẫu nhỏ | • Tạo project Server 3; thiết kế & tạo CSDL 3 • Lập trình TCP server, thread pool; xác thực và phiên • Thêm / sửa / xóa câu hỏi và đề thi cơ bản • Kết nối Server 3 với CSDL 3 • Dựng khung Client (kết nối, LOGIN); công bố stub ExamClient | • Tạo project Server 4; thiết kế & tạo CSDL 4 • Lập trình TCP server, thread pool; xác thực và phiên • Thêm / sửa / xóa câu hỏi và đề thi cơ bản • Kết nối Server 4 với CSDL 4 • Dựng khung ứng dụng JavaFX, màn hình đăng nhập (dùng mock) |
| Tuần 3 Hoàn thiện Server + Bắt đầu module riêng | • Server 1: vào thi (tạo bài làm, xáo trộn theo seed), autosave / resume, nộp & chấm tự động, truy vấn kết quả • Hoàn thiện đa luồng Server 1; heartbeat; unit test • BẮT ĐẦU: module Load Balancer – Round Robin, bảng routing và trạng thái Server | • Server 2: vào thi, autosave / resume, nộp & chấm tự động, truy vấn kết quả • Hoàn thiện đa luồng Server 2; heartbeat; unit test • BẮT ĐẦU: module đồng bộ – sync log, op_id, version; replicate danh mục | • Server 3: vào thi, autosave / resume, nộp & chấm tự động, truy vấn kết quả • Hoàn thiện đa luồng Server 3; heartbeat; unit test • BẮT ĐẦU: Client – gửi / nhận thông điệp, START_EXAM, SAVE_ANSWER; đóng băng giao thức v1.0 | • Server 4: vào thi, autosave / resume, nộp & chấm tự động, truy vấn kết quả • Hoàn thiện đa luồng Server 4; heartbeat; unit test • BẮT ĐẦU: giao diện JavaFX – danh sách ca thi, màn hình làm bài (dùng mock) |
| Tuần 4 Phát triển module nâng cao | • Weighted Least Connections • Sticky session theo bài làm • Health check định kỳ cho các Server • Test cân bằng tải cơ bản (giả lập ~50 kết nối) | • Replication dữ liệu bài thi (đáp án tạm, bài nộp, kết quả) • Xử lý xung đột cập nhật đồng thời • Xây dựng sync queue và retry • Test đồng bộ cơ bản từ 2 rồi 4 CSDL; công bố API đồng bộ nội bộ | • Đa luồng Client: đồng hồ, autosave, gửi / nhận không blocking • Chức năng tìm kiếm, lọc, phân trang • Chức năng thêm, sửa, xóa câu hỏi / đề / ca thi | • Hoàn thiện màn hình làm bài, kết quả, giảng viên (câu hỏi, tạo đề) • Tích hợp giao diện với thư viện Client, thay mock (phối hợp Ngọc) • Xây dựng công cụ sinh dữ liệu mẫu |
| Tuần 5 Tích hợp lần 1 | • Tích hợp Load Balancer với Server 1–4 • Failover: phát hiện Server lỗi, chuyển Client sang Server khác • Admission control đầu ca thi • Test với ~100 kết nối đồng thời | • Tích hợp đồng bộ với cả 4 CSDL • Đồng bộ bù khi mất kết nối rồi kết nối lại • API khôi phục bài làm cho failover (cùng Giang) • Test đồng bộ với nhiều thao tác cùng lúc | • Tích hợp Client với Load Balancer (Server 1) • Tự động reconnect, tiếp tục bài làm, lưu đệm cục bộ • Tự động nộp bài khi hết giờ • Test end-to-end Client → LB → Server → DB → Đồng bộ | • Tích hợp giao diện với toàn hệ thống; màn hình Admin giám sát • Tạo dữ liệu mẫu lớn (> 10.000 bản ghi); công cụ giả lập Client • Test giao diện trên dữ liệu thật • Mốc: demo trọn luồng thi của 1 ca thi nhỏ |
| Tuần 6 Tích hợp lần 2 + Tối ưu | • Tối ưu thuật toán cân bằng tải, điều chỉnh trọng số • Xử lý edge cases (Server quay lại, nhiều kết nối cùng lúc) • Test hiệu suất | • Tối ưu tốc độ đồng bộ (gộp lô, nén) • Xử lý edge cases (trùng thao tác, timeout, nộp trùng) • Kiểm tra nhất quán 4 CSDL (đếm bản ghi / checksum) • Log trạng thái đồng bộ | • Tối ưu hiệu suất Client; caching (ca thi, câu hỏi đã tải) • Xử lý edge cases (timeout, lỗi mạng) • Test với nhiều Client | • Tối ưu UX; chức năng thống kê và xuất CSV cho giảng viên • Stress test 100 → 200+ Client • Đo hiệu suất hệ thống, ghi nhận kết quả test |
| Tuần 7 Kiểm thử toàn diện | • Test Load Balancer với tải lớn; kịch bản tắt Server giữa ca thi • Sửa lỗi phát sinh • Viết tài liệu kiến trúc hệ thống và cân bằng tải • Review code Server 2 | • Test đồng bộ với tải lớn; test failover & recovery • Sửa lỗi đồng bộ • Viết tài liệu cơ chế đồng bộ • Review code Server 3 | • Test Client nhiều tình huống (mất mạng, hết giờ, nộp trùng) • Sửa lỗi giao thức • Viết tài liệu giao thức Client–Server • Review code Server 4 | • Chạy bộ test tổng thể; test toàn vẹn dữ liệu (không mất / trùng bài nộp) • Sửa lỗi giao diện • Viết báo cáo kết quả kiểm thử • Review code Server 1 |
| Tuần 8 Hoàn thiện & Demo | • Hoàn thiện code; review & clean code • Tổng hợp tài liệu kiến trúc • Chuẩn bị demo Load Balancer và failover | • Hoàn thiện code; review & clean code • Hoàn thiện tài liệu đồng bộ • Chuẩn bị demo đồng bộ dữ liệu (tắt rồi bật lại Server) | • Hoàn thiện code; review & clean code • Hoàn thiện tài liệu Client • Chuẩn bị demo Client (mất mạng, reconnect, tự nộp bài) | • Hoàn thiện giao diện; viết báo cáo đồ án và hướng dẫn sử dụng • Chuẩn bị slide thuyết trình, quay video demo • Điều phối kịch bản demo chung; cả nhóm tập demo |


VIII. MỐC KIỂM TRA CHUNG VÀ TIÊU CHÍ HOÀN THÀNH

1. Mốc kiểm tra chung theo tuần



| Mốc | Kết quả cần đạt |
| --- | --- |
| Cuối Tuần 1 | Kiến trúc, lược đồ CSDL v1 và đặc tả thông điệp v1 được cả nhóm thống nhất. |
| Cuối Tuần 2 | Mỗi Server chạy độc lập, đăng nhập được và kết nối được CSDL của mình. |
| Cuối Tuần 3 | Mỗi Server chạy trọn luồng thi độc lập (vào thi → autosave → nộp → chấm → xem kết quả); giao thức v1.0 đóng băng. |
| Cuối Tuần 5 | Tích hợp lần 1: 1 ca thi nhỏ chạy trọn luồng từ giao diện đến 4 CSDL qua Load Balancer và đồng bộ. |
| Cuối Tuần 6 | Tích hợp lần 2: ổn định với ~100 Client; có số liệu hiệu suất đầu tiên. |
| Cuối Tuần 7 | Đạt ≥ 200 Client đồng thời; vượt qua kịch bản tắt Server giữa ca thi mà không mất bài; hoàn tất tài liệu. |
| Cuối Tuần 8 | Demo hoàn chỉnh, báo cáo đồ án, hướng dẫn sử dụng, slide và video demo. |


2. Sản phẩm bàn giao và tiêu chí nghiệm thu của từng thành viên



| Thành viên | Sản phẩm bàn giao | Tiêu chí nghiệm thu (đề xuất) |
| --- | --- | --- |
| Giang | • Mã nguồn Server 1 + CSDL 1 • Module Load Balancer và failover • Tài liệu kiến trúc hệ thống và cân bằng tải | • Phân phối đúng theo thuật toán đã chọn; sticky session hoạt động • Phát hiện Server lỗi trong ≤ 5 giây và chuyển Client sang Server khác • Failover không làm mất bài đang làm • Chịu được ≥ 200 kết nối đồng thời |
| Huy | • Mã nguồn Server 2 + CSDL 2 • Module đồng bộ dữ liệu • ERD, script tạo bảng, dữ liệu mẫu • Tài liệu cơ chế đồng bộ | • Sau đồng bộ, 4 CSDL khớp số bản ghi / checksum • Không mất và không trùng bài nộp • Server mất kết nối rồi quay lại tự bắt kịp dữ liệu • Xung đột được giải quyết đúng quy tắc đã nêu |
| Ngọc | • Mã nguồn Server 3 + CSDL 3 • Thư viện Client (ExamClient) • Đặc tả và tài liệu giao thức Client–Server | • Đủ chức năng truy xuất và cập nhật cho sinh viên và giảng viên • Autosave, reconnect, tiếp tục bài làm, tự nộp khi hết giờ hoạt động đúng • Không treo giao diện khi mạng chậm hoặc mất kết nối |
| Thuận | • Mã nguồn Server 4 + CSDL 4 • Giao diện JavaFX (SV, GV, Admin) • Công cụ sinh dữ liệu, giả lập Client, bộ test • Báo cáo đồ án, hướng dẫn sử dụng, slide, video demo | • Đủ các màn hình theo mục II.1 • Chạy được toàn bộ kịch bản test; stress test ≥ 200 Client • Có báo cáo hiệu suất (thời gian phản hồi, số bài nộp/giây, phân bố tải) • Có hướng dẫn sử dụng đầy đủ |


IX. RỦI RO VÀ PHƯƠNG ÁN XỬ LÝ



| Rủi ro | Phương án xử lý |
| --- | --- |
| Giao diện / Client phải chờ Server hoặc Load Balancer hoàn thành | Chốt interface ExamClient ở Tuần 2; dùng mock / stub; mỗi Client chạy được trực tiếp với Server của Ngọc trước khi qua Load Balancer. |
| Giao thức Client–Server thay đổi muộn | Đóng băng v1.0 cuối Tuần 3; thông điệp có trường version; mọi thay đổi qua họp nhóm. |
| Đột biến tải đầu ca thi làm Server quá tải | Admission control và hàng đợi ở Load Balancer; thread pool và connection pool có giới hạn; kiểm tra bằng stress test từ Tuần 5. |
| Đồng bộ chậm hoặc xung đột dữ liệu | Dữ liệu bài thi dạng append-only; dùng version và op_id; gộp lô khi đồng bộ; có công cụ kiểm tra nhất quán. |
| Mất bài thi khi Server lỗi | Autosave định kỳ + replication + tùy chọn ghi vào ≥ 2 CSDL trước khi báo nộp thành công. |
| Lệch đồng hồ giữa các máy | Lấy giờ Server làm chuẩn; Client chỉ hiển thị theo độ lệch đo được; Server là nơi quyết định hết giờ. |
| Tải công việc lệch hoặc trễ tiến độ | Họp 2 lần/tuần, hỗ trợ chéo và review chéo; dành Tuần 7–8 làm đệm; ưu tiên chức năng cốt lõi trước (đăng nhập, thi, nộp, chấm, lưu kết quả). |


X. TÓM TẮT KHỐI LƯỢNG VÀ ĐỘ KHÓ



| Thành viên | Server + CSDL | Module nâng cao | Hạng mục chung chủ trì | Độ khó module | Tài liệu | Tỷ lệ đóng góp |
| --- | --- | --- | --- | --- | --- | --- |
| Giang | Server 1 + CSDL 1 | Load Balancer (Cân bằng tải, failover) | Kiến trúc tổng thể, quy ước code / Git | ★★★ | Tài liệu kiến trúc | 25% |
| Huy | Server 2 + CSDL 2 | Data Sync (Đồng bộ dữ liệu) | Lược đồ CSDL chung, dữ liệu mẫu | ★★★ | Tài liệu đồng bộ | 25% |
| Ngọc | Server 3 + CSDL 3 | Client App + Đa luồng | Đặc tả giao thức Client–Server | ★★★ | Tài liệu Client–Server | 25% |
| Thuận | Server 4 + CSDL 4 | JavaFX UI + Kiểm thử tải | Kế hoạch và bộ test case tích hợp | ★★★ | Báo cáo đồ án + Hướng dẫn sử dụng | 25% |

