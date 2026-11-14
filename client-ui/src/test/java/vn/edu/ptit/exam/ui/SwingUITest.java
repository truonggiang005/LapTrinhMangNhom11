package vn.edu.ptit.exam.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vn.edu.ptit.exam.client.ExamClient;
import vn.edu.ptit.exam.common.dto.*;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.ui.util.UITheme;
import vn.edu.ptit.exam.ui.view.LoginFrame;
import vn.edu.ptit.exam.ui.view.admin.AdminMainFrame;
import vn.edu.ptit.exam.ui.view.lecturer.LecturerMainFrame;
import vn.edu.ptit.exam.ui.view.student.ExamTakingFrame;
import vn.edu.ptit.exam.ui.view.student.StudentMainFrame;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SwingUITest {

    @BeforeAll
    public static void setup() {
        // Run safely in headless or windowed test environment
        System.setProperty("java.awt.headless", "true");
        UITheme.setupFlatLaf();
    }

    @Test
    public void testThemeConstantsAndButtons() {
        assertNotNull(UITheme.PRIMARY_COLOR);
        assertNotNull(UITheme.SUCCESS_COLOR);
        assertNotNull(UITheme.WARNING_COLOR);
        assertNotNull(UITheme.DANGER_COLOR);

        if (!GraphicsEnvironment.isHeadless()) {
            JButton pBtn = UITheme.createPrimaryButton("Test Primary");
            assertNotNull(pBtn);
            assertEquals("Test Primary", pBtn.getText());

            JButton sBtn = UITheme.createSuccessButton("Test Success");
            assertNotNull(sBtn);

            JButton dBtn = UITheme.createDangerButton("Test Danger");
            assertNotNull(dBtn);

            JPanel card = UITheme.createCardPanel();
            assertNotNull(card);
        }
    }

    @Test
    public void testUserListResponseDto() {
        List<UserDto> users = new ArrayList<>();
        users.add(new UserDto("u1", "sv01", "Nguyen Van A", "a@ptit.edu.vn", UserRole.STUDENT, "D21CQCN01-N", "ACTIVE", 1));
        users.add(new UserDto("u2", "gv01", "Tran Thi B", "b@ptit.edu.vn", UserRole.LECTURER, null, "ACTIVE", 1));

        UserListResponse resp = new UserListResponse(users, 2, 1, 20);
        assertEquals(2, resp.getTotalRows());
        assertEquals(2, resp.getUsers().size());
        assertEquals("sv01", resp.getUsers().get(0).getUsername());
        assertEquals(UserRole.STUDENT, resp.getUsers().get(0).getRole());
    }

    @Test
    public void testExamTakingFrameInitializationInSwing() {
        if (!GraphicsEnvironment.isHeadless()) {
            ExamClient client = new ExamClient();
            LoginFrame loginFrame = new LoginFrame(client);

            StartExamResponse startData = new StartExamResponse();
            startData.setAttemptId("att-test-1");
            startData.setExamId("exam-1");
            startData.setExamTitle("Lập Trình Mạng 2026");
            startData.setSubject("Lập Trình Mạng");
            startData.setDurationMinutes(45);
            startData.setRemainingSeconds(2700);

            List<QuestionDto> questions = new ArrayList<>();
            QuestionDto q1 = new QuestionDto();
            q1.setId("q-1");
            q1.setContent("Giao thức nào cung cấp kết nối tin cậy theo Section 14?");
            List<OptionDto> opts = new ArrayList<>();
            opts.add(new OptionDto("opt-1", "A", "TCP"));
            opts.add(new OptionDto("opt-2", "B", "UDP"));
            opts.add(new OptionDto("opt-3", "C", "ICMP"));
            opts.add(new OptionDto("opt-4", "D", "IGMP"));
            q1.setOptions(opts);
            questions.add(q1);
            startData.setQuestions(questions);

            StudentMainFrame studentFrame = new StudentMainFrame(client, loginFrame);
            ExamTakingFrame takingFrame = new ExamTakingFrame(client, startData, studentFrame);

            assertNotNull(takingFrame);
            assertEquals("att-test-1", startData.getAttemptId());
            assertEquals(1, startData.getQuestions().size());

            takingFrame.dispose();
            studentFrame.dispose();
            loginFrame.dispose();
        }
    }
}
