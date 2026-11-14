package vn.edu.ptit.exam.ui.view.student;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.client.ExamClient;
import vn.edu.ptit.exam.client.event.ExamClientAdapter;
import vn.edu.ptit.exam.common.dto.AnswerItemDto;
import vn.edu.ptit.exam.common.dto.OptionDto;
import vn.edu.ptit.exam.common.dto.QuestionDto;
import vn.edu.ptit.exam.common.dto.StartExamResponse;
import vn.edu.ptit.exam.common.dto.SubmitResultDto;
import vn.edu.ptit.exam.ui.util.UITheme;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Modern Swing in-exam interface for Students.
 * Features:
 * - Dynamic countdown timer synced with server clock
 * - Save status indicator (persisted locally and synced via TCP)
 * - Non-blocking offline reconnect banner
 * - Interactive Question Palette (1..N) with status colors
 * - Radio options selection with immediate dirty tracking
 * - Pre-submit validation and deterministic score result dialog
 */
public class ExamTakingFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(ExamTakingFrame.class);
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

    private final ExamClient client;
    private final StartExamResponse startExamData;
    private final StudentMainFrame studentMainFrame;
    private final List<QuestionDto> questions;
    private final Map<String, JButton> paletteButtons = new HashMap<>();

    private int currentIndex = 0;
    private ExamClientAdapter clientListener;

    // UI Components
    private JLabel timerLabel;
    private JLabel saveStatusLabel;
    private JPanel offlineBannerPanel;
    private JLabel offlineBannerLabel;
    private JButton submitButton;

    // Question display components
    private JLabel questionTitleLabel;
    private JTextArea questionContentArea;
    private JCheckBox markCheckBox;
    private JPanel optionsPanel;
    private ButtonGroup optionsGroup;
    private final List<JRadioButton> optionRadioButtons = new ArrayList<>();
    private final List<OptionDto> currentOptions = new ArrayList<>();

    private JButton prevButton;
    private JButton nextButton;

    public ExamTakingFrame(ExamClient client, StartExamResponse startExamData, StudentMainFrame studentMainFrame) {
        this.client = client;
        this.startExamData = startExamData;
        this.studentMainFrame = studentMainFrame;
        this.questions = startExamData.getQuestions() != null ? startExamData.getQuestions() : Collections.emptyList();

        initUI();
        setupClientListener();
        displayQuestion(0);
    }

    private void initUI() {
        setTitle(String.format("Bài thi: %s - %s | Thí sinh: %s",
                startExamData.getExamTitle(), startExamData.getSubject(), client.getCurrentFullName()));
        setSize(1150, 720);
        setMinimumSize(new Dimension(950, 600));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                confirmExitExam();
            }
        });

        JPanel rootPanel = new JPanel(new BorderLayout(0, 0));
        rootPanel.setBackground(UITheme.BG_COLOR);

        // 1. Top Header Bar (Timer, Save Status, Submit Button)
        JPanel topContainer = new JPanel(new BorderLayout());

        JPanel headerBar = new JPanel(new BorderLayout(16, 0));
        headerBar.setBackground(Color.WHITE);
        headerBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.BORDER_COLOR),
                new EmptyBorder(12, 20, 12, 20)
        ));

        // Top Left: Exam Info
        JPanel examInfoPanel = new JPanel(new GridLayout(2, 1, 0, 2));
        examInfoPanel.setBackground(Color.WHITE);
        JLabel titleLabel = new JLabel(startExamData.getExamTitle());
        titleLabel.setFont(UITheme.FONT_TITLE);
        titleLabel.setForeground(UITheme.TEXT_PRIMARY);

        JLabel subLabel = new JLabel(String.format("Môn: %s | Tổng số câu: %d | Thời gian: %d phút",
                startExamData.getSubject(), questions.size(), startExamData.getDurationMinutes()));
        subLabel.setFont(UITheme.FONT_BODY);
        subLabel.setForeground(UITheme.TEXT_SECONDARY);
        examInfoPanel.add(titleLabel);
        examInfoPanel.add(subLabel);

        // Top Center: Timer and Autosave Status
        JPanel centerHeaderPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 24, 0));
        centerHeaderPanel.setBackground(Color.WHITE);

        JPanel timerBox = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        timerBox.setBackground(Color.WHITE);
        JLabel timerIcon = new JLabel("⏱");
        timerIcon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
        timerLabel = new JLabel(formatTime(startExamData.getRemainingSeconds()));
        timerLabel.setFont(UITheme.FONT_TIMER);
        timerLabel.setForeground(UITheme.DANGER_COLOR);
        timerBox.add(timerIcon);
        timerBox.add(timerLabel);

        saveStatusLabel = new JLabel("Đã lưu");
        saveStatusLabel.setFont(UITheme.FONT_SMALL);
        saveStatusLabel.setForeground(UITheme.SUCCESS_COLOR);

        centerHeaderPanel.add(timerBox);
        centerHeaderPanel.add(saveStatusLabel);

        // Top Right: Submit Button
        JPanel actionHeaderPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actionHeaderPanel.setBackground(Color.WHITE);
        submitButton = UITheme.createSuccessButton("NỘP BÀI THI");
        submitButton.setPreferredSize(new Dimension(140, 38));
        submitButton.addActionListener(e -> confirmAndSubmitExam(false));
        actionHeaderPanel.add(submitButton);

        headerBar.add(examInfoPanel, BorderLayout.WEST);
        headerBar.add(centerHeaderPanel, BorderLayout.CENTER);
        headerBar.add(actionHeaderPanel, BorderLayout.EAST);
        topContainer.add(headerBar, BorderLayout.NORTH);

        // Reconnecting offline banner (initially hidden)
        offlineBannerPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        offlineBannerPanel.setBackground(new Color(255, 251, 230)); // Soft amber
        offlineBannerPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UITheme.WARNING_COLOR));
        offlineBannerLabel = new JLabel("⚠️ Mất kết nối tới máy chủ! Hệ thống đang tự động kết nối lại... Bạn vẫn tiếp tục làm bài bình thường.");
        offlineBannerLabel.setFont(UITheme.FONT_BOLD);
        offlineBannerLabel.setForeground(new Color(212, 107, 8));
        offlineBannerPanel.add(offlineBannerLabel);
        offlineBannerPanel.setVisible(false);
        topContainer.add(offlineBannerPanel, BorderLayout.SOUTH);

        rootPanel.add(topContainer, BorderLayout.NORTH);

        // 2. Main Body: Split into Question Display (Left/Center) and Navigation Palette (Right)
        JPanel bodyPanel = new JPanel(new BorderLayout(16, 0));
        bodyPanel.setBackground(UITheme.BG_COLOR);
        bodyPanel.setBorder(new EmptyBorder(16, 20, 16, 20));

        // Question Card
        JPanel questionCard = UITheme.createCardPanel();
        questionCard.setLayout(new BorderLayout(0, 12));

        // Question header: Title & Flag Checkbox
        JPanel qHeader = new JPanel(new BorderLayout());
        qHeader.setBackground(Color.WHITE);
        questionTitleLabel = new JLabel("Câu hỏi 1 / " + questions.size());
        questionTitleLabel.setFont(UITheme.FONT_HEADER);
        questionTitleLabel.setForeground(UITheme.PRIMARY_COLOR);

        markCheckBox = new JCheckBox("🚩 Đánh dấu xem lại sau");
        markCheckBox.setFont(UITheme.FONT_BOLD);
        markCheckBox.setBackground(Color.WHITE);
        markCheckBox.setForeground(new Color(212, 107, 8));
        markCheckBox.setFocusPainted(false);
        markCheckBox.addActionListener(e -> onMarkChanged());

        qHeader.add(questionTitleLabel, BorderLayout.WEST);
        qHeader.add(markCheckBox, BorderLayout.EAST);
        questionCard.add(qHeader, BorderLayout.NORTH);

        // Question Content & Options Panel
        JPanel centerQuestionPanel = new JPanel(new BorderLayout(0, 12));
        centerQuestionPanel.setBackground(Color.WHITE);

        questionContentArea = new JTextArea();
        questionContentArea.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        questionContentArea.setLineWrap(true);
        questionContentArea.setWrapStyleWord(true);
        questionContentArea.setEditable(false);
        questionContentArea.setBackground(new Color(250, 250, 250));
        questionContentArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER_COLOR, 1, true),
                new EmptyBorder(12, 14, 12, 14)
        ));

        optionsPanel = new JPanel();
        optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.Y_AXIS));
        optionsPanel.setBackground(Color.WHITE);
        optionsGroup = new ButtonGroup();

        centerQuestionPanel.add(questionContentArea, BorderLayout.NORTH);
        centerQuestionPanel.add(new JScrollPane(optionsPanel), BorderLayout.CENTER);
        questionCard.add(centerQuestionPanel, BorderLayout.CENTER);

        // Question Footer: Previous / Next buttons
        JPanel qFooter = new JPanel(new BorderLayout());
        qFooter.setBackground(Color.WHITE);
        qFooter.setBorder(new EmptyBorder(8, 0, 0, 0));

        prevButton = UITheme.createSecondaryButton("← Câu trước");
        prevButton.setPreferredSize(new Dimension(120, 36));
        prevButton.addActionListener(e -> navigateQuestion(currentIndex - 1));

        nextButton = UITheme.createPrimaryButton("Câu tiếp theo →");
        nextButton.setPreferredSize(new Dimension(140, 36));
        nextButton.addActionListener(e -> navigateQuestion(currentIndex + 1));

        qFooter.add(prevButton, BorderLayout.WEST);
        qFooter.add(nextButton, BorderLayout.EAST);
        questionCard.add(qFooter, BorderLayout.SOUTH);

        bodyPanel.add(questionCard, BorderLayout.CENTER);

        // Palette Card (Right Side)
        JPanel paletteCard = UITheme.createCardPanel();
        paletteCard.setLayout(new BorderLayout(0, 12));
        paletteCard.setPreferredSize(new Dimension(280, 0));

        JLabel paletteTitle = new JLabel("Danh sách câu hỏi");
        paletteTitle.setFont(UITheme.FONT_HEADER);
        paletteTitle.setForeground(UITheme.TEXT_PRIMARY);
        paletteCard.add(paletteTitle, BorderLayout.NORTH);

        // Palette Button Grid
        JPanel buttonsGrid = new JPanel(new GridLayout(0, 5, 8, 8));
        buttonsGrid.setBackground(Color.WHITE);

        for (int i = 0; i < questions.size(); i++) {
            final int idx = i;
            QuestionDto q = questions.get(i);
            JButton qBtn = new JButton(String.valueOf(i + 1));
            qBtn.setFont(UITheme.FONT_BOLD);
            qBtn.setFocusPainted(false);
            qBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
            qBtn.setBackground(Color.WHITE);
            qBtn.setForeground(UITheme.TEXT_PRIMARY);
            qBtn.setBorder(BorderFactory.createLineBorder(UITheme.BORDER_COLOR, 1, true));
            qBtn.addActionListener(e -> navigateQuestion(idx));
            paletteButtons.put(q.getId(), qBtn);
            buttonsGrid.add(qBtn);
        }

        JScrollPane paletteScroll = new JScrollPane(buttonsGrid);
        paletteScroll.setBorder(null);
        paletteScroll.setBackground(Color.WHITE);
        paletteCard.add(paletteScroll, BorderLayout.CENTER);

        // Palette Legend
        JPanel legendPanel = new JPanel(new GridLayout(3, 1, 0, 4));
        legendPanel.setBackground(Color.WHITE);
        legendPanel.setBorder(new EmptyBorder(8, 4, 0, 4));

        legendPanel.add(createLegendItem(Color.WHITE, UITheme.BORDER_COLOR, "Chưa trả lời"));
        legendPanel.add(createLegendItem(UITheme.PRIMARY_COLOR, UITheme.PRIMARY_COLOR, "Đã trả lời"));
        legendPanel.add(createLegendItem(new Color(250, 173, 20), new Color(212, 107, 8), "Đánh dấu xem lại"));
        paletteCard.add(legendPanel, BorderLayout.SOUTH);

        bodyPanel.add(paletteCard, BorderLayout.EAST);
        rootPanel.add(bodyPanel, BorderLayout.CENTER);

        setContentPane(rootPanel);
    }

    private JPanel createLegendItem(Color bg, Color border, String text) {
        JPanel item = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        item.setBackground(Color.WHITE);
        JPanel box = new JPanel();
        box.setPreferredSize(new Dimension(14, 14));
        box.setBackground(bg);
        box.setBorder(BorderFactory.createLineBorder(border, 1));
        JLabel lbl = new JLabel(text);
        lbl.setFont(UITheme.FONT_SMALL);
        lbl.setForeground(UITheme.TEXT_SECONDARY);
        item.add(box);
        item.add(lbl);
        return item;
    }

    private void setupClientListener() {
        clientListener = new ExamClientAdapter() {
            @Override
            public void onClockTick(long remainingSeconds) {
                SwingUtilities.invokeLater(() -> {
                    timerLabel.setText(formatTime(remainingSeconds));
                    if (remainingSeconds <= 300) {
                        timerLabel.setForeground(UITheme.DANGER_COLOR);
                    }
                });
            }

            @Override
            public void onTimeWarning(String message, long remainingSeconds) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(ExamTakingFrame.this,
                            message, "Cảnh báo thời gian", JOptionPane.WARNING_MESSAGE);
                });
            }

            @Override
            public void onTimeUp() {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(ExamTakingFrame.this,
                            "Đã hết thời gian làm bài!\nHệ thống đang tự động nộp bài thi của bạn...",
                            "Hết giờ làm bài", JOptionPane.INFORMATION_MESSAGE);
                    confirmAndSubmitExam(true);
                });
            }

            @Override
            public void onSaveStatusChanged(String statusText, boolean isError) {
                SwingUtilities.invokeLater(() -> {
                    saveStatusLabel.setText(statusText);
                    saveStatusLabel.setForeground(isError ? UITheme.DANGER_COLOR : UITheme.SUCCESS_COLOR);
                });
            }

            @Override
            public void onReconnecting(int attemptCount, int delaySeconds) {
                SwingUtilities.invokeLater(() -> {
                    offlineBannerPanel.setVisible(true);
                    offlineBannerLabel.setText(String.format(
                            "⚠️ Mất kết nối tới máy chủ! Đang tự động kết nối lại (lần %d, sau %ds)... Bạn vẫn tiếp tục làm bài bình thường.",
                            attemptCount, delaySeconds));
                });
            }

            @Override
            public void onConnected(String host, int port) {
                SwingUtilities.invokeLater(() -> {
                    offlineBannerPanel.setVisible(false);
                });
            }

            @Override
            public void onDisconnected(String reason) {
                SwingUtilities.invokeLater(() -> {
                    offlineBannerPanel.setVisible(true);
                    offlineBannerLabel.setText("⚠️ Mất kết nối mạng! Dữ liệu của bạn đang được lưu an toàn trên máy cục bộ.");
                });
            }
        };

        client.addListener(clientListener);
    }

    private void displayQuestion(int index) {
        if (index < 0 || index >= questions.size()) {
            return;
        }

        String prevQuestionId = questions.get(currentIndex).getId();
        this.currentIndex = index;
        QuestionDto currentQ = questions.get(currentIndex);

        // Notify client about question navigation to trigger auto-save if dirty
        client.onQuestionChanged(prevQuestionId, currentQ.getId());

        questionTitleLabel.setText(String.format("Câu hỏi %d / %d", currentIndex + 1, questions.size()));
        questionContentArea.setText(currentQ.getContent());

        // Check if previously marked or saved in client memory
        Map<String, AnswerItemDto> answers = client.getAnswersMap();
        AnswerItemDto existingAnswer = answers.get(currentQ.getId());

        boolean isMarked = existingAnswer != null ? existingAnswer.isMarked() : currentQ.isMarked();
        markCheckBox.setSelected(isMarked);

        String selectedOptionId = existingAnswer != null ? existingAnswer.getSelectedOptionId() : currentQ.getSelectedOptionId();

        // Render options
        optionsPanel.removeAll();
        optionsGroup.clearSelection();
        optionRadioButtons.clear();
        currentOptions.clear();

        List<OptionDto> options = currentQ.getOptions() != null ? currentQ.getOptions() : Collections.emptyList();
        currentOptions.addAll(options);

        for (OptionDto opt : options) {
            String optText = String.format(" %s.  %s", opt.getOptionKey(), opt.getContent());
            JRadioButton rb = new JRadioButton(optText);
            rb.setFont(UITheme.FONT_BODY);
            rb.setBackground(Color.WHITE);
            rb.setFocusPainted(false);
            rb.setBorder(new EmptyBorder(8, 8, 8, 8));

            if (selectedOptionId != null && selectedOptionId.equals(opt.getId())) {
                rb.setSelected(true);
            }

            rb.addActionListener(e -> onOptionSelected(opt.getId()));

            optionsGroup.add(rb);
            optionRadioButtons.add(rb);
            optionsPanel.add(rb);
            optionsPanel.add(Box.createVerticalStrut(4));
        }

        optionsPanel.revalidate();
        optionsPanel.repaint();

        // Update Prev / Next buttons
        prevButton.setEnabled(currentIndex > 0);
        nextButton.setEnabled(currentIndex < questions.size() - 1);

        updatePaletteStyles();
    }

    private void onOptionSelected(String optionId) {
        QuestionDto currentQ = questions.get(currentIndex);
        boolean isMarked = markCheckBox.isSelected();
        client.setAnswer(currentQ.getId(), optionId, isMarked);
        updatePaletteStyles();
    }

    private void onMarkChanged() {
        QuestionDto currentQ = questions.get(currentIndex);
        boolean isMarked = markCheckBox.isSelected();
        Map<String, AnswerItemDto> answers = client.getAnswersMap();
        AnswerItemDto existing = answers.get(currentQ.getId());
        String selectedOptId = existing != null ? existing.getSelectedOptionId() : null;

        client.setAnswer(currentQ.getId(), selectedOptId, isMarked);
        updatePaletteStyles();
    }

    private void navigateQuestion(int newIndex) {
        if (newIndex >= 0 && newIndex < questions.size()) {
            displayQuestion(newIndex);
        }
    }

    private void updatePaletteStyles() {
        Map<String, AnswerItemDto> answers = client.getAnswersMap();

        for (int i = 0; i < questions.size(); i++) {
            QuestionDto q = questions.get(i);
            JButton btn = paletteButtons.get(q.getId());
            if (btn == null) continue;

            AnswerItemDto ans = answers.get(q.getId());
            boolean answered = ans != null && ans.getSelectedOptionId() != null && !ans.getSelectedOptionId().isBlank();
            boolean marked = ans != null && ans.isMarked();

            boolean isCurrent = (i == currentIndex);

            if (marked) {
                btn.setBackground(new Color(250, 173, 20)); // Amber
                btn.setForeground(Color.WHITE);
            } else if (answered) {
                btn.setBackground(UITheme.PRIMARY_COLOR);
                btn.setForeground(Color.WHITE);
            } else {
                btn.setBackground(Color.WHITE);
                btn.setForeground(UITheme.TEXT_PRIMARY);
            }

            if (isCurrent) {
                btn.setBorder(BorderFactory.createLineBorder(new Color(9, 109, 217), 2, true));
            } else {
                btn.setBorder(BorderFactory.createLineBorder(UITheme.BORDER_COLOR, 1, true));
            }
        }
    }

    private void confirmAndSubmitExam(boolean isForceAutoSubmit) {
        if (!isForceAutoSubmit) {
            Map<String, AnswerItemDto> answers = client.getAnswersMap();
            int answeredCount = 0;
            for (AnswerItemDto a : answers.values()) {
                if (a.getSelectedOptionId() != null && !a.getSelectedOptionId().isBlank()) {
                    answeredCount++;
                }
            }
            int total = questions.size();
            int unanswered = total - answeredCount;

            String confirmMessage = String.format(
                    "BẠN CÓ CHẮC CHẮN MUỐN NỘP BÀI THI?\n\n" +
                    "• Đã trả lời: %d / %d câu hỏi\n" +
                    "• Chưa trả lời: %d câu\n\n" +
                    "Lưu ý: Sau khi nộp bài, bạn sẽ không thể chỉnh sửa câu trả lời nữa!",
                    answeredCount, total, unanswered);

            int choice = JOptionPane.showConfirmDialog(this,
                    confirmMessage, "Xác nhận nộp bài thi",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }

        submitButton.setEnabled(false);
        submitButton.setText("Đang nộp bài...");

        client.submit(startExamData.getAttemptId())
                .thenAccept(result -> SwingUtilities.invokeLater(() -> showResultDialog(result)))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        submitButton.setEnabled(true);
                        submitButton.setText("NỘP BÀI THI");
                        JOptionPane.showMessageDialog(this,
                                "Lỗi khi nộp bài: " + ex.getCause().getMessage() +
                                "\nHãy thử lại hoặc nhờ giám thị trợ giúp.",
                                "Lỗi nộp bài", JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });
    }

    private void showResultDialog(SubmitResultDto result) {
        // Clean up listeners
        if (clientListener != null) {
            client.removeListener(clientListener);
        }

        double score = result != null && result.getScore() != null ? result.getScore() : 0.0;
        int correct = result != null ? result.getCorrectCount() : 0;
        int total = result != null ? result.getTotalQuestions() : questions.size();

        String msg = String.format(
                "CHÚC MỪNG BẠN ĐÃ HOÀN THÀNH BÀI THI!\n\n" +
                "• Điểm số đạt được: %.2f / 10.0\n" +
                "• Số câu đúng: %d / %d câu\n" +
                "• Thời gian nộp bài: %s\n\n" +
                "Kết quả thi đã được lưu thành công trên hệ thống máy chủ.",
                score, correct, total, TIME_FORMAT.format(new Date()));

        JOptionPane.showMessageDialog(this, msg, "Kết quả thi trắc nghiệm", JOptionPane.INFORMATION_MESSAGE);

        this.dispose();
        studentMainFrame.loadExams();
        studentMainFrame.setVisible(true);
    }

    private void confirmExitExam() {
        int res = JOptionPane.showConfirmDialog(this,
                "Bài thi đang diễn ra! Nếu thoát màn hình, thời gian thi vẫn sẽ tiếp tục trôi.\n" +
                "Bạn có chắc chắn muốn quay lại danh sách ca thi không?",
                "Cảnh báo thoát bài thi", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (res == JOptionPane.YES_OPTION) {
            if (clientListener != null) {
                client.removeListener(clientListener);
            }
            this.dispose();
            studentMainFrame.loadExams();
            studentMainFrame.setVisible(true);
        }
    }

    private String formatTime(long totalSeconds) {
        if (totalSeconds < 0) totalSeconds = 0;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }
}
