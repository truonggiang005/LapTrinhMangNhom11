package vn.edu.ptit.exam.ui.view.student;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.client.ExamClient;
import vn.edu.ptit.exam.client.storage.LocalAttemptData;
import vn.edu.ptit.exam.common.dto.ExamItemDto;
import vn.edu.ptit.exam.common.dto.ExamListResponse;
import vn.edu.ptit.exam.common.dto.StartExamResponse;
import vn.edu.ptit.exam.ui.util.UITheme;
import vn.edu.ptit.exam.ui.view.LoginFrame;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Giao diện chính dành cho Thí sinh / Sinh viên.
 * Hiển thị danh sách ca thi được phân công, lọc trạng thái, tìm kiếm và tham gia thi.
 */
public class StudentMainFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(StudentMainFrame.class);
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm dd/MM/yyyy");

    private final ExamClient client;
    private final LoginFrame loginFrame;

    private JTable examsTable;
    private DefaultTableModel tableModel;
    private JComboBox<String> statusFilterCombo;
    private JTextField searchField;
    private JButton startExamButton;
    private JButton requestRetakeButton;
    private JButton refreshButton;
    private JLabel userInfoLabel;

    private List<ExamItemDto> currentExams = new ArrayList<>();

    public StudentMainFrame(ExamClient client, LoginFrame loginFrame) {
        this.client = client;
        this.loginFrame = loginFrame;
        initUI();
        loadExams();
        checkIncompleteAttempt();
    }

    private void initUI() {
        setTitle("Cổng Thi Trực Tuyến - Thí Sinh: " + client.getCurrentFullName());
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1040, 640);
        setLocationRelativeTo(null);
        getContentPane().setBackground(UITheme.BG_COLOR);

        JPanel mainPanel = new JPanel(new BorderLayout(0, 16));
        mainPanel.setBackground(UITheme.BG_COLOR);
        mainPanel.setBorder(new EmptyBorder(16, 20, 16, 20));

        // 1. Top Header Bar
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(Color.WHITE);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER_COLOR, 1, true),
                new EmptyBorder(12, 16, 12, 16)
        ));

        userInfoLabel = new JLabel(String.format("Sinh viên: %s (%s) - Vai trò: Sinh viên",
                client.getCurrentFullName(), client.getCurrentUsername()));
        userInfoLabel.setFont(UITheme.FONT_HEADER);
        userInfoLabel.setForeground(UITheme.TEXT_PRIMARY);

        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerRight.setBackground(Color.WHITE);

        JButton logoutButton = UITheme.createSecondaryButton("Đăng xuất");
        logoutButton.addActionListener(e -> logout());
        headerRight.add(logoutButton);

        headerPanel.add(userInfoLabel, BorderLayout.WEST);
        headerPanel.add(headerRight, BorderLayout.EAST);
        mainPanel.add(headerPanel, BorderLayout.NORTH);

        // 2. Center Content (Exams Table & Controls)
        JPanel contentCard = UITheme.createCardPanel();
        contentCard.setLayout(new BorderLayout(0, 12));

        // Filter / Search Bar
        JPanel toolBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        toolBar.setBackground(Color.WHITE);

        JLabel filterLabel = new JLabel("Trạng thái ca thi:");
        filterLabel.setFont(UITheme.FONT_BOLD);
        statusFilterCombo = new JComboBox<>(new String[]{
                "TẤT CẢ", "Đang diễn ra", "Đang làm bài dở", "Chưa mở", "Đã nộp bài", "Đã kết thúc"
        });
        statusFilterCombo.addActionListener(e -> loadExams());

        JLabel searchLabel = new JLabel("Tìm kiếm:");
        searchLabel.setFont(UITheme.FONT_BOLD);
        searchField = new JTextField(16);
        searchField.addActionListener(e -> loadExams());

        JButton searchBtn = UITheme.createSecondaryButton("Tìm kiếm");
        searchBtn.addActionListener(e -> loadExams());

        refreshButton = UITheme.createSecondaryButton("Làm mới");
        refreshButton.addActionListener(e -> loadExams());

        toolBar.add(filterLabel);
        toolBar.add(statusFilterCombo);
        toolBar.add(searchLabel);
        toolBar.add(searchField);
        toolBar.add(searchBtn);
        toolBar.add(refreshButton);

        contentCard.add(toolBar, BorderLayout.NORTH);

        // Exams JTable
        String[] columns = {"Mã ca thi", "Tên ca thi", "Môn thi", "Thời lượng", "Giờ mở", "Giờ đóng", "Số câu", "Trạng thái", "Điểm số"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        examsTable = new JTable(tableModel);
        examsTable.setFont(UITheme.FONT_BODY);
        examsTable.setRowHeight(32);
        examsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        examsTable.getSelectionModel().addListSelectionListener(e -> updateButtonState());

        // Center align table columns
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        for (int i = 0; i < examsTable.getColumnCount(); i++) {
            if (i != 1 && i != 2) { // Tên ca thi và Môn thi căn trái
                examsTable.getColumnModel().getColumn(i).setCellRenderer(centerRenderer);
            }
        }

        examsTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        examsTable.getColumnModel().getColumn(1).setPreferredWidth(220);
        examsTable.getColumnModel().getColumn(2).setPreferredWidth(160);
        examsTable.getColumnModel().getColumn(3).setPreferredWidth(85);
        examsTable.getColumnModel().getColumn(4).setPreferredWidth(125);
        examsTable.getColumnModel().getColumn(5).setPreferredWidth(125);
        examsTable.getColumnModel().getColumn(6).setPreferredWidth(65);
        examsTable.getColumnModel().getColumn(7).setPreferredWidth(110);
        examsTable.getColumnModel().getColumn(8).setPreferredWidth(75);

        JScrollPane scrollPane = new JScrollPane(examsTable);
        scrollPane.setBorder(BorderFactory.createLineBorder(UITheme.BORDER_COLOR));
        contentCard.add(scrollPane, BorderLayout.CENTER);

        // Bottom Action Bar
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        bottomBar.setBackground(Color.WHITE);

        startExamButton = UITheme.createPrimaryButton("VÀO THI NGAY");
        startExamButton.setPreferredSize(new Dimension(170, 38));
        startExamButton.setEnabled(false);
        startExamButton.addActionListener(e -> onStartExamClicked());

        requestRetakeButton = UITheme.createSecondaryButton("XIN THI LẠI");
        requestRetakeButton.setPreferredSize(new Dimension(140, 38));
        requestRetakeButton.setEnabled(false);
        requestRetakeButton.addActionListener(e -> onRequestRetakeClicked());

        bottomBar.add(requestRetakeButton);
        bottomBar.add(startExamButton);
        contentCard.add(bottomBar, BorderLayout.SOUTH);

        mainPanel.add(contentCard, BorderLayout.CENTER);

        setContentPane(mainPanel);
    }

    public void loadExams() {
        int selectedIndex = statusFilterCombo.getSelectedIndex();
        String filter = "ALL";
        switch (selectedIndex) {
            case 1: filter = "OPEN"; break;
            case 2: filter = "IN_PROGRESS"; break;
            case 3: filter = "NOT_OPEN"; break;
            case 4: filter = "SUBMITTED"; break;
            case 5: filter = "CLOSED"; break;
            default: filter = "ALL"; break;
        }

        String kw = searchField.getText().trim();

        refreshButton.setEnabled(false);

        client.listExams(kw.isEmpty() ? null : kw, filter, 1, 50, true)
                .thenAccept(response -> {
                    SwingUtilities.invokeLater(() -> {
                        refreshButton.setEnabled(true);
                        populateTable(response);
                    });
                })
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        refreshButton.setEnabled(true);
                        String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        JOptionPane.showMessageDialog(this, "Lỗi tải danh sách ca thi: " + errMsg,
                                "Thông báo lỗi", JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });
    }

    private void populateTable(ExamListResponse resp) {
        tableModel.setRowCount(0);
        currentExams = resp.getExams() != null ? resp.getExams() : new ArrayList<>();

        for (ExamItemDto item : currentExams) {
            String openStr = item.getStartTime() > 0 ? DATE_FORMAT.format(new Date(item.getStartTime())) : "-";
            String closeStr = item.getEndTime() > 0 ? DATE_FORMAT.format(new Date(item.getEndTime())) : "-";
            String scoreStr = item.getScore() != null ? String.format("%.2f", item.getScore()) : "-";

            String rawStatus = item.getStatus();
            String retakeSt = item.getRetakeStatus();
            String statusLabel = "Chưa mở";

            if ("PENDING".equalsIgnoreCase(retakeSt)) {
                statusLabel = "Chờ duyệt thi lại";
            } else if ("REJECTED".equalsIgnoreCase(retakeSt)) {
                statusLabel = "Từ chối thi lại";
            } else if ("OPEN".equalsIgnoreCase(rawStatus)) {
                statusLabel = "Đang diễn ra";
            } else if ("IN_PROGRESS".equalsIgnoreCase(rawStatus)) {
                statusLabel = "Đang làm dở";
            } else if ("SUBMITTED".equalsIgnoreCase(rawStatus)) {
                statusLabel = "Đã nộp bài";
            } else if ("NOT_OPEN".equalsIgnoreCase(rawStatus)) {
                statusLabel = "Chưa mở";
            } else if ("CLOSED".equalsIgnoreCase(rawStatus)) {
                statusLabel = "Đã kết thúc";
            } else if ("FINALIZED".equalsIgnoreCase(rawStatus)) {
                statusLabel = "Đã tổng kết";
            }

            tableModel.addRow(new Object[]{
                    item.getId(),
                    item.getTitle(),
                    item.getSubject(),
                    item.getDurationMinutes() + " phút",
                    openStr,
                    closeStr,
                    item.getTotalQuestions(),
                    statusLabel,
                    scoreStr
            });
        }
        updateButtonState();
    }

    private void updateButtonState() {
        int row = examsTable.getSelectedRow();
        if (row < 0 || row >= currentExams.size()) {
            startExamButton.setEnabled(false);
            requestRetakeButton.setEnabled(false);
            return;
        }

        ExamItemDto selected = currentExams.get(row);
        String st = selected.getStatus();
        boolean canStart = "OPEN".equalsIgnoreCase(st) || "IN_PROGRESS".equalsIgnoreCase(st);

        startExamButton.setEnabled(canStart);
        if ("IN_PROGRESS".equalsIgnoreCase(st)) {
            startExamButton.setText("TIẾP TỤC LÀM BÀI");
        } else {
            startExamButton.setText("VÀO THI NGAY");
        }

        boolean canRequestRetake = "SUBMITTED".equalsIgnoreCase(st) || "CLOSED".equalsIgnoreCase(st);
        if ("PENDING".equalsIgnoreCase(selected.getRetakeStatus())) {
            requestRetakeButton.setEnabled(false);
            requestRetakeButton.setText("CHỜ DUYỆT");
        } else if (canRequestRetake) {
            requestRetakeButton.setEnabled(true);
            requestRetakeButton.setText("XIN THI LẠI");
        } else {
            requestRetakeButton.setEnabled(false);
            requestRetakeButton.setText("XIN THI LẠI");
        }
    }

    private void onRequestRetakeClicked() {
        int row = examsTable.getSelectedRow();
        if (row < 0 || row >= currentExams.size()) return;

        ExamItemDto selected = currentExams.get(row);

        JTextArea reasonArea = new JTextArea(4, 25);
        reasonArea.setLineWrap(true);
        reasonArea.setWrapStyleWord(true);
        reasonArea.setFont(UITheme.FONT_BODY);
        reasonArea.setText("Em gặp sự cố thiết bị hoặc mất kết nối trong quá trình thi, kính mong Thầy/Cô cho phép em được thi lại.");

        JPanel p = new JPanel(new BorderLayout(5, 8));
        p.add(new JLabel("<html><b>Ca thi:</b> " + selected.getTitle() + "<br><b>Môn thi:</b> " + selected.getSubject() + "<br><br>Nhập lý do xin thi lại:</html>"), BorderLayout.NORTH);
        p.add(new JScrollPane(reasonArea), BorderLayout.CENTER);

        int opt = JOptionPane.showConfirmDialog(this, p, "Gửi Yêu Cầu Xin Thi Lại", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (opt != JOptionPane.OK_OPTION) return;

        String reason = reasonArea.getText().trim();
        if (reason.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập lý do xin thi lại!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        requestRetakeButton.setEnabled(false);
        requestRetakeButton.setText("Đang gửi...");

        client.requestRetake(selected.getId(), reason)
                .thenAccept(dto -> SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this,
                            "Đã gửi yêu cầu xin thi lại thành công!\nVui lòng chờ Giảng viên phê duyệt để bắt đầu làm bài lại.",
                            "Gửi yêu cầu thành công", JOptionPane.INFORMATION_MESSAGE);
                    loadExams();
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        updateButtonState();
                        String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        JOptionPane.showMessageDialog(this, "Lỗi gửi yêu cầu xin thi lại: " + errMsg,
                                "Lỗi gửi yêu cầu", JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });
    }

    private void onStartExamClicked() {
        int row = examsTable.getSelectedRow();
        if (row < 0 || row >= currentExams.size()) return;

        ExamItemDto selected = currentExams.get(row);

        int confirm = JOptionPane.showConfirmDialog(this,
                String.format("Bạn có chắc chắn muốn bắt đầu làm bài thi:\n'%s'\nMôn thi: %s\nThời lượng: %d phút\n\nThời gian sẽ bắt đầu tính ngay sau khi bấm Có!",
                        selected.getTitle(), selected.getSubject(), selected.getDurationMinutes()),
                "Xác nhận vào thi", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        startExamButton.setEnabled(false);
        startExamButton.setText("Đang khởi tạo...");

        client.startExam(selected.getId())
                .thenAccept(startResp -> {
                    SwingUtilities.invokeLater(() -> {
                        startExamButton.setEnabled(true);
                        updateButtonState();
                        openExamTakingWindow(startResp);
                    });
                })
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        startExamButton.setEnabled(true);
                        updateButtonState();
                        String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        JOptionPane.showMessageDialog(this,
                                "Không thể vào thi: " + errMsg,
                                "Lỗi vào thi", JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });
    }

    private void openExamTakingWindow(StartExamResponse startResp) {
        ExamTakingFrame takingFrame = new ExamTakingFrame(client, startResp, this);
        takingFrame.setVisible(true);
        this.setVisible(false);
    }

    private void checkIncompleteAttempt() {
        Optional<LocalAttemptData> incompleteOpt = client.checkIncompleteAttempt();
        if (incompleteOpt.isPresent()) {
            LocalAttemptData incomplete = incompleteOpt.get();
            long now = System.currentTimeMillis();
            if (incomplete.getDeadline() > now) {
                int res = JOptionPane.showConfirmDialog(this,
                        String.format("Hệ thống phát hiện bạn có bài thi '%s' đang làm dở!\nBạn có muốn tiếp tục làm bài thi này không?",
                                incomplete.getExamTitle()),
                        "Khôi phục bài làm dở", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

                if (res == JOptionPane.YES_OPTION) {
                    client.startExam(incomplete.getExamId())
                            .thenAccept(startResp -> SwingUtilities.invokeLater(() -> openExamTakingWindow(startResp)))
                            .exceptionally(ex -> {
                                SwingUtilities.invokeLater(() -> {
                                    String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                                    JOptionPane.showMessageDialog(this,
                                            "Lỗi khôi phục bài thi: " + errMsg, "Lỗi", JOptionPane.ERROR_MESSAGE);
                                });
                                return null;
                            });
                }
            }
        }
    }

    private void logout() {
        client.disconnect();
        this.dispose();
        loginFrame.setVisible(true);
    }
}
