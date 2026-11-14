package vn.edu.ptit.exam.tool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

public class UpdateDatabase {
    private static final String BCRYPT_123456 = "$2a$10$PL/adEDae618XcmOXSFZwOW7rT2G4/zi8xd4eaE6/6uxbUfJdZ/Li";

    public static void main(String[] args) {
        String[] dbNames = {"exam_db_1", "exam_db_2", "exam_db_3", "exam_db_4"};

        for (String db : dbNames) {
            String url = "jdbc:mysql://127.0.0.1:3306/" + db + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Ho_Chi_Minh";
            try (Connection conn = DriverManager.getConnection(url, "root", "")) {
                System.out.println("Updating " + db + "...");

                try (Statement stmt = conn.createStatement()) {
                    // 1. Tạo bảng subjects nếu chưa có
                    stmt.execute("CREATE TABLE IF NOT EXISTS `subjects` (" +
                            "`id` VARCHAR(36) NOT NULL," +
                            "`code` VARCHAR(50) NOT NULL," +
                            "`name` VARCHAR(150) NOT NULL," +
                            "`description` TEXT DEFAULT NULL," +
                            "`created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                            "`updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                            "PRIMARY KEY (`id`)," +
                            "UNIQUE KEY `uk_subject_code` (`code`)" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;");

                    // Xóa và chèn môn học chuẩn
                    stmt.execute("DELETE FROM `subjects`");
                    stmt.execute("INSERT INTO `subjects` (id, code, name, description) VALUES " +
                            "('subj-001', 'LTM', 'Lập trình mạng', 'Kiến trúc máy chủ/máy trạm, giao thức mạng TCP/IP, lập trình đa luồng')," +
                            "('subj-002', 'CSDL', 'Cơ sở dữ liệu', 'Hệ quản trị CSDL quan hệ, ngôn ngữ SQL, thiết kế lược đồ và giao dịch')," +
                            "('subj-003', 'CTDL_GT', 'Cấu trúc dữ liệu và giải thuật', 'Danh sách liên kết, cây, đồ thị, sắp xếp và tối ưu hoá giải thuật')," +
                            "('subj-004', 'HDH', 'Hệ điều hành', 'Quản lý tiến trình, lập lịch CPU, bộ nhớ ảo, đồng bộ hoá và vào ra')," +
                            "('subj-005', 'KTMT', 'Kiến trúc máy tính', 'Kiến trúc vi xử lý, tập lệnh assembly, phân cấp bộ nhớ đệm và bus')");

                    // 2. Tắt kiểm tra khóa ngoại để dọn dẹp và cập nhật users
                    stmt.execute("SET FOREIGN_KEY_CHECKS = 0");
                    stmt.execute("DELETE FROM `users`");

                    // Chèn danh sách người dùng chuẩn theo yêu cầu:
                    // Admin: admin
                    // GV: gv_01, gv_02, gv_03
                    // SV: Lưu Trường Giang, Hoàng Mạnh Huy, Nguyễn Đức Thuận, Tạ Minh Ngọc, Trần Văn An
                    String insertUserSql = "INSERT INTO `users` (id, username, password_hash, full_name, email, role, class_name, status, version) VALUES " +
                            "('usr-admin', 'admin', ?, 'Quản trị viên', 'admin@hethongthi.vn', 'ADMIN', NULL, 'ACTIVE', 1)," +
                            "('usr-gv-01', 'gv_01', ?, 'Giảng viên 01', 'gv01@hethongthi.vn', 'LECTURER', NULL, 'ACTIVE', 1)," +
                            "('usr-gv-02', 'gv_02', ?, 'Giảng viên 02', 'gv02@hethongthi.vn', 'LECTURER', NULL, 'ACTIVE', 1)," +
                            "('usr-gv-03', 'gv_03', ?, 'Giảng viên 03', 'gv03@hethongthi.vn', 'LECTURER', NULL, 'ACTIVE', 1)," +
                            "('usr-sv-01', 'sv_01', ?, 'Lưu Trường Giang', 'giang.lt@hethongthi.vn', 'STUDENT', 'CNTT-01', 'ACTIVE', 1)," +
                            "('usr-sv-02', 'sv_02', ?, 'Hoàng Mạnh Huy', 'huy.hm@hethongthi.vn', 'STUDENT', 'CNTT-01', 'ACTIVE', 1)," +
                            "('usr-sv-03', 'sv_03', ?, 'Nguyễn Đức Thuận', 'thuan.nd@hethongthi.vn', 'STUDENT', 'CNTT-01', 'ACTIVE', 1)," +
                            "('usr-sv-04', 'sv_04', ?, 'Tạ Minh Ngọc', 'ngoc.tm@hethongthi.vn', 'STUDENT', 'CNTT-01', 'ACTIVE', 1)," +
                            "('usr-sv-05', 'sv_05', ?, 'Trần Văn An', 'an.tv@hethongthi.vn', 'STUDENT', 'CNTT-01', 'ACTIVE', 1)";

                    try (PreparedStatement ps = conn.prepareStatement(insertUserSql)) {
                        for (int i = 1; i <= 9; i++) {
                            ps.setString(i, BCRYPT_123456);
                        }
                        ps.executeUpdate();
                    }

                    // 3. Cập nhật created_by trong questions
                    stmt.execute("UPDATE `questions` SET created_by = 'usr-gv-01' WHERE created_by IS NOT NULL");

                    // 4. Cập nhật created_by trong exams
                    stmt.execute("UPDATE `exams` SET created_by = 'usr-gv-01' WHERE created_by IS NOT NULL");

                    // 5. Cập nhật phân công sinh viên trong exam_assignments
                    stmt.execute("DELETE FROM `exam_assignments` WHERE exam_id = 'exam-001'");
                    stmt.execute("INSERT INTO `exam_assignments` (id, exam_id, student_id) VALUES " +
                            "('asg-001', 'exam-001', 'usr-sv-01')," +
                            "('asg-002', 'exam-001', 'usr-sv-02')," +
                            "('asg-003', 'exam-001', 'usr-sv-03')," +
                            "('asg-004', 'exam-001', 'usr-sv-04')," +
                            "('asg-005', 'exam-001', 'usr-sv-05')");

                    // 6. Cập nhật tên đề thi mẫu bằng tiếng Việt chuẩn
                    stmt.execute("UPDATE `exams` SET title = 'Thi cuối kỳ Lập trình mạng', subject = 'Lập trình mạng' WHERE id = 'exam-001'");

                    stmt.execute("SET FOREIGN_KEY_CHECKS = 1");
                }
                System.out.println("Updated " + db + " successfully!");
            } catch (Exception ex) {
                System.err.println("Error updating " + db + ": " + ex.getMessage());
                ex.printStackTrace();
            }
        }
    }
}
