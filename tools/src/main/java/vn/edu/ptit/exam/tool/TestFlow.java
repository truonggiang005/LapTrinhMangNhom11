package vn.edu.ptit.exam.tool;

import vn.edu.ptit.exam.client.ExamClient;
import vn.edu.ptit.exam.common.dto.*;

import java.util.Arrays;
import java.util.List;

public class TestFlow {
    public static void main(String[] args) throws Exception {
        System.out.println("=== BẮT ĐẦU KIỂM THỬ HỆ THỐNG ===");

        // 1. Kiểm thử Giảng viên gv_01
        ExamClient gvClient = new ExamClient();
        gvClient.connect("localhost", 9000).get();
        System.out.println("[GV] Đã kết nối tới cổng 9000!");

        String gvToken = gvClient.login("gv_01", "123456").get();
        System.out.println("[GV] Đăng nhập thành công với họ tên: " + gvClient.getCurrentFullName() + " | Vai trò: " + gvClient.getCurrentRole());

        // Lấy danh sách câu hỏi của môn Lập trình mạng
        QuestionListResponse qList = gvClient.listQuestions(null, "Lập trình mạng", null, null, 1, 20).get();
        System.out.println("[GV] Số câu hỏi môn Lập trình mạng tìm thấy: " + (qList.getQuestions() != null ? qList.getQuestions().size() : 0));

        if (qList.getQuestions() == null || qList.getQuestions().isEmpty()) {
            throw new RuntimeException("Không tìm thấy câu hỏi nào!");
        }

        List<String> questionIds = Arrays.asList(qList.getQuestions().get(0).getId());
        List<String> studentIds = Arrays.asList("usr-sv-01", "usr-sv-02", "usr-sv-03", "usr-sv-04");

        // Tạo ca thi với danh sách câu hỏi cụ thể và sinh viên phân công
        CreateExamRequest examReq = new CreateExamRequest();
        examReq.setTitle("Thi Giữa Kỳ Lập Trình Mạng");
        examReq.setSubject("Lập trình mạng");
        examReq.setDurationMinutes(45);
        examReq.setStartTime(System.currentTimeMillis());
        examReq.setEndTime(System.currentTimeMillis() + 3600000L);
        examReq.setExamType("FIXED");
        examReq.setQuestionIds(questionIds);
        examReq.setAssignedStudentIds(studentIds);

        ExamDto createdExam = gvClient.createExam(examReq).get();
        System.out.println("[GV] Tạo ca thi thành công! Mã ca thi: " + createdExam.getId() + " - " + createdExam.getTitle());
        gvClient.disconnect();

        // 2. Kiểm thử Sinh viên sv_01 (Lưu Trường Giang)
        ExamClient svClient = new ExamClient();
        svClient.connect("localhost", 9000).get();
        System.out.println("[SV] Đã kết nối tới cổng 9000!");

        String svToken = svClient.login("sv_01", "123456").get();
        System.out.println("[SV] Đăng nhập thành công với họ tên: " + svClient.getCurrentFullName() + " | Tài khoản: " + svClient.getCurrentUsername());

        // Lấy danh sách ca thi của sinh viên
        ExamListResponse examList = svClient.listExams(null, "ALL", 1, 10, true).get();
        System.out.println("[SV] Số ca thi tìm thấy: " + (examList.getExams() != null ? examList.getExams().size() : 0));

        for (ExamItemDto ex : examList.getExams()) {
            System.out.println("  -> Ca thi: " + ex.getId() + " | Tên: " + ex.getTitle() + " | Môn: " + ex.getSubject() + " | Trạng thái: " + ex.getStatus() + " | Điểm: " + ex.getScore());
        }

        // Bắt đầu vào thi ca thi vừa tạo
        StartExamResponse startResp = svClient.startExam(createdExam.getId()).get();
        System.out.println("[SV] Bắt đầu làm bài thi thành công! Số câu hỏi trong đề: " + (startResp.getQuestions() != null ? startResp.getQuestions().size() : 0));

        // Nộp bài thi
        SubmitResultDto submitResult = svClient.submit(startResp.getAttemptId()).get();
        System.out.println("[SV] Nộp bài thi thành công! Điểm số: " + submitResult.getScore() + " | Số câu đúng: " + submitResult.getCorrectCount());

        svClient.disconnect();
        System.out.println("=== TẤT CẢ KIỂM THỬ THÀNH CÔNG RỰC RỠ! ===");
    }
}
