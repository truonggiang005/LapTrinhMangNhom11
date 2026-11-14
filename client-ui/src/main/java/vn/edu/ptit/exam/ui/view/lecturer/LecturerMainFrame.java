package vn.edu.ptit.exam.ui.view.lecturer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.client.ExamClient;
import vn.edu.ptit.exam.common.dto.*;
import vn.edu.ptit.exam.ui.util.SubjectManager;
import vn.edu.ptit.exam.ui.util.SubjectManager.SubjectItem;
import vn.edu.ptit.exam.ui.util.UITheme;
import vn.edu.ptit.exam.ui.view.LoginFrame;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Giao diện Quản trị dành cho Giảng viên.
 * Tab 1: Quản lý Môn thi (Tạo môn thi trước khi tạo câu hỏi).
 * Tab 2: Ngân hàng Câu hỏi (Gắn với môn thi cụ thể, Thêm / Sửa / Xóa câu hỏi).
 * Tab 3: Tạo Ca thi & Giám sát trực tiếp (Chọn môn -> Chọn câu hỏi trực quan, phân công sinh viên, giám sát thời gian thực).
 * Tab 4: Bảng điểm & Thống kê kết quả thi (Xuất file Excel / CSV).
 */
public class LecturerMainFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(LecturerMainFrame.class);
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm dd/MM/yyyy");

    private final ExamClient client;
    private final LoginFrame loginFrame;
    private final SubjectManager subjectManager = SubjectManager.getInstance();

    // --- Tab 1: Quản lý môn thi ---
    private JTable subjectTable;
    private DefaultTableModel subjectTableModel;

    // --- Tab 2: Ngân hàng câu hỏi ---
    private JTable questionTable;
    private DefaultTableModel questionTableModel;
    private JTextField qSearchField;
    private JComboBox<String> qSubjectCombo;
    private JComboBox<String> qDifficultyCombo;
    private List<QuestionDto> currentQuestions = new ArrayList<>();

    // --- Tab 3: Tạo ca thi & Giám sát ---
    private JTextField examTitleField;
    private JComboBox<String> examSubjectCombo;
    private JSpinner examDurationSpinner;
    private JComboBox<String> examTypeCombo;
    private JTable examQuestionPickTable;
    private DefaultTableModel examQuestionPickTableModel;
    private JLabel selectedQuestionCountLabel;
    private List<QuestionDto> subjectQuestionsForPick = new ArrayList<>();

    private JTextField monitorExamIdField;
    private JLabel totalAssignedLabel;
    private JLabel notStartedLabel;
    private JLabel inProgressLabel;
    private JLabel submittedLabel;
    private JLabel readinessStatusLabel;

    // --- Tab 4: Bảng điểm & Thống kê ---
    private JTextField scoreExamIdField;
    private JTable scoreTable;
    private DefaultTableModel scoreTableModel;
    private JLabel avgScoreLabel;
    private JLabel highestScoreLabel;
    private JLabel lowestScoreLabel;
    private JLabel distributionLabel;
    private JProgressBar exportProgressBar;
    private JButton exportCsvButton;

    // --- Tab 5: Phê duyệt thi lại ---
    private JTable retakeTable;
    private DefaultTableModel retakeTableModel;
    private JComboBox<String> retakeStatusFilterCombo;
    private JButton approveRetakeBtn;
    private JButton rejectRetakeBtn;
    private JButton refreshRetakeBtn;
    private List<RetakeRequestDto> currentRetakeRequests = new ArrayList<>();

    public LecturerMainFrame(ExamClient client, LoginFrame loginFrame) {
        this.client = client;
        this.loginFrame = loginFrame;

        initUI();
        loadSubjects();
        loadQuestions();
        loadSubjectQuestionsForExamPick();
        loadRetakeRequests();
    }

    private void initUI() {
        setTitle("Cổng Giảng Viên - Quản Lý Đào Tạo & Thi Trực Tuyến: " + client.getCurrentFullName());
        setSize(1220, 780);
        setMinimumSize(new Dimension(1050, 680));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel mainPanel = new JPanel(new BorderLayout(0, 16));
        mainPanel.setBackground(UITheme.BG_COLOR);
        mainPanel.setBorder(new EmptyBorder(16, 20, 16, 20));

        // 1. Header trên cùng
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(Color.WHITE);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UITheme.BORDER_COLOR, 1, true),
                new EmptyBorder(12, 16, 12, 16)
        ));

        JLabel userLabel = new JLabel(String.format("Giảng viên: %s (%s) | Vai trò: Giảng viên",
                client.getCurrentFullName(), client.getCurrentUsername()));
        userLabel.setFont(UITheme.FONT_HEADER);
        userLabel.setForeground(UITheme.TEXT_PRIMARY);

        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerRight.setBackground(Color.WHITE);
        JButton logoutBtn = UITheme.createSecondaryButton("Đăng xuất");
        logoutBtn.addActionListener(e -> logout());
        headerRight.add(logoutBtn);

        headerPanel.add(userLabel, BorderLayout.WEST);
        headerPanel.add(headerRight, BorderLayout.EAST);
        mainPanel.add(headerPanel, BorderLayout.NORTH);

        // 2. Các Tab nghiệp vụ
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(UITheme.FONT_BOLD);
        tabbedPane.addTab("  1. Quản Lý Môn Thi  ", createSubjectManagementTab());
        tabbedPane.addTab("  2. Ngân Hàng Câu Hỏi  ", createQuestionBankTab());
        tabbedPane.addTab("  3. Tạo Ca Thi & Giám Sát  ", createExamMonitorTab());
        tabbedPane.addTab("  4. Bảng Điểm & Thống Kê  ", createScoresStatsTab());
        tabbedPane.addTab("  5. Phê Duyệt Thi Lại  ", createRetakeApprovalTab());

        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        setContentPane(mainPanel);
    }

    // =========================================================================
    // TAB 1: QUẢN LÝ MÔN THI
    // =========================================================================

    private JPanel createSubjectManagementTab() {
        JPanel panel = UITheme.createCardPanel();
        panel.setLayout(new BorderLayout(0, 14));

        // Thanh công cụ trên
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(Color.WHITE);

        JLabel tabTitle = new JLabel("Danh Mục Các Môn Thi Trong Hệ Thống");
        tabTitle.setFont(UITheme.FONT_HEADER);
        tabTitle.setForeground(UITheme.PRIMARY_COLOR);
        topBar.add(tabTitle, BorderLayout.WEST);

        JPanel actionGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actionGroup.setBackground(Color.WHITE);

        JButton addSubjectBtn = UITheme.createSuccessButton("+ Thêm Môn Thi Mới");
        addSubjectBtn.addActionListener(e -> showAddSubjectDialog());
        actionGroup.add(addSubjectBtn);

        JButton deleteSubjectBtn = UITheme.createDangerButton("Xóa Môn Thi");
        deleteSubjectBtn.addActionListener(e -> deleteSelectedSubject());
        actionGroup.add(deleteSubjectBtn);

        JButton refreshBtn = UITheme.createSecondaryButton("Làm Mới");
        refreshBtn.addActionListener(e -> loadSubjects());
        actionGroup.add(refreshBtn);

        topBar.add(actionGroup, BorderLayout.EAST);
        panel.add(topBar, BorderLayout.NORTH);

        // Bảng môn thi
        String[] columns = {"Mã môn", "Tên môn thi", "Mô tả môn thi"};
        subjectTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };

        subjectTable = new JTable(subjectTableModel);
        subjectTable.setRowHeight(32);
        subjectTable.setFont(UITheme.FONT_BODY);
        subjectTable.getTableHeader().setFont(UITheme.FONT_BOLD);
        subjectTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        subjectTable.getColumnModel().getColumn(0).setPreferredWidth(120);
        subjectTable.getColumnModel().getColumn(0).setCellRenderer(centerRenderer);
        subjectTable.getColumnModel().getColumn(1).setPreferredWidth(280);
        subjectTable.getColumnModel().getColumn(2).setPreferredWidth(550);

        panel.add(new JScrollPane(subjectTable), BorderLayout.CENTER);
        return panel;
    }

    private void loadSubjects() {
        subjectTableModel.setRowCount(0);
        List<SubjectItem> list = subjectManager.getSubjects();
        for (SubjectItem s : list) {
            subjectTableModel.addRow(new Object[]{
                    s.getCode(),
                    s.getName(),
                    s.getDescription()
            });
        }
        updateSubjectCombos();
    }

    private void updateSubjectCombos() {
        List<String> names = subjectManager.getSubjectNames();

        // Cập nhật combo ở Tab Ngân hàng câu hỏi
        if (qSubjectCombo != null) {
            String selected = (String) qSubjectCombo.getSelectedItem();
            qSubjectCombo.removeAllItems();
            qSubjectCombo.addItem("TẤT CẢ");
            for (String n : names) {
                qSubjectCombo.addItem(n);
            }
            if (selected != null) {
                qSubjectCombo.setSelectedItem(selected);
            }
        }

        // Cập nhật combo ở Tab Tạo ca thi
        if (examSubjectCombo != null) {
            String selected = (String) examSubjectCombo.getSelectedItem();
            examSubjectCombo.removeAllItems();
            for (String n : names) {
                examSubjectCombo.addItem(n);
            }
            if (selected != null) {
                examSubjectCombo.setSelectedItem(selected);
            }
        }
    }

    private void showAddSubjectDialog() {
        JDialog dialog = new JDialog(this, "Thêm Môn Thi Mới", true);
        dialog.setSize(480, 280);
        dialog.setLocationRelativeTo(this);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(16, 20, 16, 20));
        form.setBackground(Color.WHITE);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0;
        form.add(new JLabel("Mã môn thi (viết tắt):"), gbc);
        gbc.gridx = 1;
        JTextField codeField = new JTextField(15);
        form.add(codeField, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        form.add(new JLabel("Tên môn thi đầy đủ:"), gbc);
        gbc.gridx = 1;
        JTextField nameField = new JTextField(20);
        form.add(nameField, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        form.add(new JLabel("Mô tả môn học:"), gbc);
        gbc.gridx = 1;
        JTextField descField = new JTextField(20);
        form.add(descField, gbc);

        gbc.gridx = 1; gbc.gridy = 3;
        JButton saveBtn = UITheme.createPrimaryButton("Lưu Môn Thi");
        saveBtn.addActionListener(e -> {
            String code = codeField.getText().trim();
            String name = nameField.getText().trim();
            String desc = descField.getText().trim();

            if (code.isEmpty() || name.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Vui lòng nhập đầy đủ mã môn và tên môn!",
                        "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
                return;
            }

            boolean ok = subjectManager.addSubject(code, name, desc);
            if (ok) {
                JOptionPane.showMessageDialog(dialog, "Thêm môn thi thành công: " + name,
                        "Thành công", JOptionPane.INFORMATION_MESSAGE);
                dialog.dispose();
                loadSubjects();
            } else {
                JOptionPane.showMessageDialog(dialog, "Mã môn hoặc tên môn thi này đã tồn tại!",
                        "Trùng dữ liệu", JOptionPane.ERROR_MESSAGE);
            }
        });
        form.add(saveBtn, gbc);

        dialog.setContentPane(form);
        dialog.setVisible(true);
    }

    private void deleteSelectedSubject() {
        int row = subjectTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn môn thi cần xóa!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String code = (String) subjectTableModel.getValueAt(row, 0);
        String name = (String) subjectTableModel.getValueAt(row, 1);

        int confirm = JOptionPane.showConfirmDialog(this,
                "Bạn có chắc chắn muốn xóa môn thi: " + name + " (" + code + ")?",
                "Xác nhận xóa", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            subjectManager.deleteSubject(code);
            loadSubjects();
        }
    }

    // =========================================================================
    // TAB 2: NGÂN HÀNG CÂU HỎI
    // =========================================================================

    private JPanel createQuestionBankTab() {
        JPanel panel = UITheme.createCardPanel();
        panel.setLayout(new BorderLayout(0, 12));

        // Thanh công cụ lọc
        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        filterBar.setBackground(Color.WHITE);

        filterBar.add(new JLabel("Môn thi:"));
        qSubjectCombo = new JComboBox<>();
        qSubjectCombo.addActionListener(e -> loadQuestions());
        filterBar.add(qSubjectCombo);

        filterBar.add(new JLabel("Độ khó:"));
        qDifficultyCombo = new JComboBox<>(new String[]{"TẤT CẢ", "Dễ", "Trung bình", "Khó"});
        qDifficultyCombo.addActionListener(e -> loadQuestions());
        filterBar.add(qDifficultyCombo);

        filterBar.add(new JLabel("Tìm kiếm:"));
        qSearchField = new JTextField(15);
        qSearchField.addActionListener(e -> loadQuestions());
        filterBar.add(qSearchField);

        JButton filterBtn = UITheme.createPrimaryButton("Lọc");
        filterBtn.addActionListener(e -> loadQuestions());
        filterBar.add(filterBtn);

        JPanel actionGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actionGroup.setBackground(Color.WHITE);

        JButton addBtn = UITheme.createSuccessButton("+ Thêm Câu Hỏi Mới");
        addBtn.addActionListener(e -> showAddQuestionDialog());
        actionGroup.add(addBtn);

        JButton editBtn = UITheme.createPrimaryButton("Sửa Câu Hỏi");
        editBtn.addActionListener(e -> showEditQuestionDialog());
        actionGroup.add(editBtn);

        JButton deleteBtn = UITheme.createDangerButton("Xóa");
        deleteBtn.addActionListener(e -> deleteSelectedQuestion());
        actionGroup.add(deleteBtn);

        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(Color.WHITE);
        topBar.add(filterBar, BorderLayout.WEST);
        topBar.add(actionGroup, BorderLayout.EAST);
        panel.add(topBar, BorderLayout.NORTH);

        // Bảng câu hỏi
        String[] columns = {"Mã câu", "Môn thi", "Chương", "Độ khó", "Nội dung câu hỏi", "Phiên bản"};
        questionTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };

        questionTable = new JTable(questionTableModel);
        questionTable.setRowHeight(32);
        questionTable.setFont(UITheme.FONT_BODY);
        questionTable.getTableHeader().setFont(UITheme.FONT_BOLD);
        questionTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        questionTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        questionTable.getColumnModel().getColumn(0).setCellRenderer(centerRenderer);
        questionTable.getColumnModel().getColumn(1).setPreferredWidth(160);
        questionTable.getColumnModel().getColumn(2).setPreferredWidth(70);
        questionTable.getColumnModel().getColumn(2).setCellRenderer(centerRenderer);
        questionTable.getColumnModel().getColumn(3).setPreferredWidth(95);
        questionTable.getColumnModel().getColumn(3).setCellRenderer(centerRenderer);
        questionTable.getColumnModel().getColumn(4).setPreferredWidth(450);
        questionTable.getColumnModel().getColumn(5).setPreferredWidth(80);
        questionTable.getColumnModel().getColumn(5).setCellRenderer(centerRenderer);

        panel.add(new JScrollPane(questionTable), BorderLayout.CENTER);
        return panel;
    }

    private void loadQuestions() {
        String subj = (String) qSubjectCombo.getSelectedItem();
        if ("TẤT CẢ".equalsIgnoreCase(subj)) subj = null;

        String diff = (String) qDifficultyCombo.getSelectedItem();
        String diffParam = null;
        if ("Dễ".equalsIgnoreCase(diff)) diffParam = "EASY";
        else if ("Trung bình".equalsIgnoreCase(diff)) diffParam = "MEDIUM";
        else if ("Khó".equalsIgnoreCase(diff)) diffParam = "HARD";

        String kw = qSearchField.getText().trim();
        if (kw.isEmpty()) kw = null;

        client.listQuestions(kw, subj, null, diffParam, 1, 200)
                .thenAccept(resp -> SwingUtilities.invokeLater(() -> {
                    questionTableModel.setRowCount(0);
                    currentQuestions = resp.getQuestions() != null ? resp.getQuestions() : Collections.emptyList();

                    Set<String> foundSubjects = new HashSet<>();
                    for (QuestionDto q : currentQuestions) {
                        if (q.getSubject() != null && !q.getSubject().isBlank()) {
                            foundSubjects.add(q.getSubject());
                        }

                        String diffVN = "Trung bình";
                        if ("EASY".equalsIgnoreCase(q.getDifficulty())) diffVN = "Dễ";
                        else if ("HARD".equalsIgnoreCase(q.getDifficulty())) diffVN = "Khó";

                        questionTableModel.addRow(new Object[]{
                                q.getId(),
                                q.getSubject() != null ? q.getSubject() : "-",
                                q.getChapter() != null ? q.getChapter() : "-",
                                diffVN,
                                q.getContent(),
                                q.getVersion() != null ? q.getVersion() : 1
                        });
                    }

                    // Đồng bộ nếu có môn học mới trong CSDL
                    subjectManager.mergeFromQuestionSubjects(foundSubjects);
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        JOptionPane.showMessageDialog(this,
                                "Lỗi tải ngân hàng câu hỏi: " + errMsg, "Lỗi", JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });
    }

    private void showAddQuestionDialog() {
        JDialog dialog = new JDialog(this, "Thêm Câu Hỏi Mới", true);
        dialog.setSize(640, 580);
        dialog.setLocationRelativeTo(this);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(16, 20, 16, 20));
        form.setBackground(Color.WHITE);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Chọn môn học từ danh sách môn đã tạo
        gbc.gridx = 0; gbc.gridy = 0;
        form.add(new JLabel("Môn thi:"), gbc);
        gbc.gridx = 1;
        JComboBox<String> subjCombo = new JComboBox<>();
        for (String sName : subjectManager.getSubjectNames()) {
            subjCombo.addItem(sName);
        }
        // Chọn môn đang xem nếu có
        String currentSubj = (String) qSubjectCombo.getSelectedItem();
        if (currentSubj != null && !"TẤT CẢ".equalsIgnoreCase(currentSubj)) {
            subjCombo.setSelectedItem(currentSubj);
        }
        form.add(subjCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        form.add(new JLabel("Chương:"), gbc);
        gbc.gridx = 1;
        JSpinner chapterSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 20, 1));
        form.add(chapterSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        form.add(new JLabel("Độ khó:"), gbc);
        gbc.gridx = 1;
        JComboBox<String> diffCombo = new JComboBox<>(new String[]{"Dễ", "Trung bình", "Khó"});
        diffCombo.setSelectedIndex(1);
        form.add(diffCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 3;
        form.add(new JLabel("Nội dung câu hỏi:"), gbc);
        gbc.gridx = 1;
        JTextArea contentArea = new JTextArea(4, 25);
        contentArea.setLineWrap(true);
        form.add(new JScrollPane(contentArea), gbc);

        // Đáp án A, B, C, D
        JTextField optA = new JTextField();
        JTextField optB = new JTextField();
        JTextField optC = new JTextField();
        JTextField optD = new JTextField();

        ButtonGroup bg = new ButtonGroup();
        JRadioButton rA = new JRadioButton("Đáp án A (Đúng)"); rA.setSelected(true); bg.add(rA);
        JRadioButton rB = new JRadioButton("Đáp án B (Đúng)"); bg.add(rB);
        JRadioButton rC = new JRadioButton("Đáp án C (Đúng)"); bg.add(rC);
        JRadioButton rD = new JRadioButton("Đáp án D (Đúng)"); bg.add(rD);

        gbc.gridx = 0; gbc.gridy = 4; form.add(rA, gbc);
        gbc.gridx = 1; form.add(optA, gbc);
        gbc.gridx = 0; gbc.gridy = 5; form.add(rB, gbc);
        gbc.gridx = 1; form.add(optB, gbc);
        gbc.gridx = 0; gbc.gridy = 6; form.add(rC, gbc);
        gbc.gridx = 1; form.add(optC, gbc);
        gbc.gridx = 0; gbc.gridy = 7; form.add(rD, gbc);
        gbc.gridx = 1; form.add(optD, gbc);

        // Nút Lưu
        gbc.gridx = 1; gbc.gridy = 8;
        JButton saveBtn = UITheme.createPrimaryButton("Lưu Câu Hỏi");
        saveBtn.addActionListener(e -> {
            String content = contentArea.getText().trim();
            if (content.isEmpty() || optA.getText().isBlank() || optB.getText().isBlank()) {
                JOptionPane.showMessageDialog(dialog, "Vui lòng nhập nội dung câu hỏi và các phương án trả lời!",
                        "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
                return;
            }

            QuestionDto newQ = new QuestionDto();
            newQ.setSubject((String) subjCombo.getSelectedItem());
            newQ.setChapter((Integer) chapterSpinner.getValue());

            String diffSelected = (String) diffCombo.getSelectedItem();
            String diffCode = "MEDIUM";
            if ("Dễ".equalsIgnoreCase(diffSelected)) diffCode = "EASY";
            else if ("Khó".equalsIgnoreCase(diffSelected)) diffCode = "HARD";
            newQ.setDifficulty(diffCode);

            newQ.setContent(content);

            List<OptionDto> options = new ArrayList<>();
            options.add(new OptionDto("opt-1", "A", optA.getText().trim(), rA.isSelected()));
            options.add(new OptionDto("opt-2", "B", optB.getText().trim(), rB.isSelected()));
            options.add(new OptionDto("opt-3", "C", optC.getText().trim(), rC.isSelected()));
            options.add(new OptionDto("opt-4", "D", optD.getText().trim(), rD.isSelected()));
            newQ.setOptions(options);

            client.createQuestion(newQ)
                    .thenAccept(created -> SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(dialog, "Tạo câu hỏi thành công!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
                        dialog.dispose();
                        loadQuestions();
                        loadSubjectQuestionsForExamPick();
                    }))
                    .exceptionally(ex -> {
                        SwingUtilities.invokeLater(() -> {
                            String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                            JOptionPane.showMessageDialog(dialog, "Lỗi tạo câu hỏi: " + errMsg, "Lỗi", JOptionPane.ERROR_MESSAGE);
                        });
                        return null;
                    });
        });
        form.add(saveBtn, gbc);

        dialog.setContentPane(form);
        dialog.setVisible(true);
    }

    private void showEditQuestionDialog() {
        int row = questionTable.getSelectedRow();
        if (row < 0 || row >= currentQuestions.size()) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một câu hỏi để chỉnh sửa!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        QuestionDto targetQ = currentQuestions.get(row);
        int baseVersion = targetQ.getVersion() != null ? targetQ.getVersion() : 1;

        JDialog dialog = new JDialog(this, "Sửa Câu Hỏi - Mã: " + targetQ.getId(), true);
        dialog.setSize(620, 480);
        dialog.setLocationRelativeTo(this);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(16, 20, 16, 20));
        form.setBackground(Color.WHITE);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0;
        form.add(new JLabel("Môn thi:"), gbc);
        gbc.gridx = 1;
        JComboBox<String> subjCombo = new JComboBox<>();
        for (String sName : subjectManager.getSubjectNames()) {
            subjCombo.addItem(sName);
        }
        subjCombo.setSelectedItem(targetQ.getSubject());
        form.add(subjCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        form.add(new JLabel("Chương:"), gbc);
        gbc.gridx = 1;
        JSpinner chapterSpinner = new JSpinner(new SpinnerNumberModel(
                targetQ.getChapter() != null ? targetQ.getChapter() : 1, 1, 20, 1));
        form.add(chapterSpinner, gbc);

        gbc.gridx = 0; gbc.gridy = 2;
        form.add(new JLabel("Độ khó:"), gbc);
        gbc.gridx = 1;
        JComboBox<String> diffCombo = new JComboBox<>(new String[]{"Dễ", "Trung bình", "Khó"});
        if ("EASY".equalsIgnoreCase(targetQ.getDifficulty())) diffCombo.setSelectedIndex(0);
        else if ("HARD".equalsIgnoreCase(targetQ.getDifficulty())) diffCombo.setSelectedIndex(2);
        else diffCombo.setSelectedIndex(1);
        form.add(diffCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 3;
        form.add(new JLabel("Nội dung:"), gbc);
        gbc.gridx = 1;
        JTextArea contentArea = new JTextArea(targetQ.getContent(), 4, 25);
        contentArea.setLineWrap(true);
        form.add(new JScrollPane(contentArea), gbc);

        gbc.gridx = 1; gbc.gridy = 4;
        JButton saveBtn = UITheme.createPrimaryButton("Cập Nhật Câu Hỏi");
        saveBtn.addActionListener(e -> {
            targetQ.setSubject((String) subjCombo.getSelectedItem());
            targetQ.setChapter((Integer) chapterSpinner.getValue());

            String diffSelected = (String) diffCombo.getSelectedItem();
            String diffCode = "MEDIUM";
            if ("Dễ".equalsIgnoreCase(diffSelected)) diffCode = "EASY";
            else if ("Khó".equalsIgnoreCase(diffSelected)) diffCode = "HARD";
            targetQ.setDifficulty(diffCode);

            targetQ.setContent(contentArea.getText().trim());

            client.updateQuestion(targetQ, baseVersion)
                    .thenAccept(updated -> SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(dialog, "Cập nhật câu hỏi thành công!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
                        dialog.dispose();
                        loadQuestions();
                        loadSubjectQuestionsForExamPick();
                    }))
                    .exceptionally(ex -> {
                        SwingUtilities.invokeLater(() -> {
                            String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                            if (errMsg.contains("409")) {
                                JOptionPane.showMessageDialog(dialog,
                                        "Xung đột dữ liệu!\nMột giảng viên khác vừa chỉnh sửa câu hỏi này.\n" +
                                        "Hệ thống đã tự động làm mới lại dữ liệu.",
                                        "Xung đột chỉnh sửa", JOptionPane.ERROR_MESSAGE);
                            } else {
                                JOptionPane.showMessageDialog(dialog, "Lỗi cập nhật: " + errMsg, "Lỗi", JOptionPane.ERROR_MESSAGE);
                            }
                            dialog.dispose();
                            loadQuestions();
                        });
                        return null;
                    });
        });
        form.add(saveBtn, gbc);

        dialog.setContentPane(form);
        dialog.setVisible(true);
    }

    private void deleteSelectedQuestion() {
        int row = questionTable.getSelectedRow();
        if (row < 0 || row >= currentQuestions.size()) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn câu hỏi cần xóa!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        QuestionDto targetQ = currentQuestions.get(row);
        int confirm = JOptionPane.showConfirmDialog(this,
                "Bạn có chắc chắn muốn xóa câu hỏi mã: " + targetQ.getId() + "?",
                "Xác nhận xóa", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            int baseVersion = targetQ.getVersion() != null ? targetQ.getVersion() : 1;
            client.deleteQuestion(targetQ.getId(), baseVersion)
                    .thenAccept(v -> SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this, "Xóa câu hỏi thành công!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
                        loadQuestions();
                        loadSubjectQuestionsForExamPick();
                    }))
                    .exceptionally(ex -> {
                        SwingUtilities.invokeLater(() -> {
                            String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                            JOptionPane.showMessageDialog(this, "Lỗi xóa câu hỏi: " + errMsg, "Lỗi", JOptionPane.ERROR_MESSAGE);
                        });
                        return null;
                    });
        }
    }

    // =========================================================================
    // TAB 3: TẠO CA THI & GIÁM SÁT TRỰC TIẾP
    // =========================================================================

    private JPanel createExamMonitorTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 16));
        panel.setBackground(UITheme.BG_COLOR);

        // Khung 1: Tạo ca thi mới
        JPanel createCard = UITheme.createCardPanel();
        createCard.setLayout(new BorderLayout(0, 10));

        JLabel createTitle = new JLabel("Tạo Ca Thi Mới - Chọn Môn Thi & Danh Sách Câu Hỏi Trực Quan");
        createTitle.setFont(UITheme.FONT_HEADER);
        createTitle.setForeground(UITheme.PRIMARY_COLOR);
        createCard.add(createTitle, BorderLayout.NORTH);

        JPanel centerCreatePanel = new JPanel(new BorderLayout(0, 8));
        centerCreatePanel.setBackground(Color.WHITE);

        // Thông tin chung của ca thi
        JPanel formGrid = new JPanel(new GridLayout(2, 4, 12, 8));
        formGrid.setBackground(Color.WHITE);

        examTitleField = new JTextField("Thi kết thúc học phần");
        examSubjectCombo = new JComboBox<>();
        examSubjectCombo.addActionListener(e -> loadSubjectQuestionsForExamPick());

        examDurationSpinner = new JSpinner(new SpinnerNumberModel(45, 5, 180, 5));
        examTypeCombo = new JComboBox<>(new String[]{"Đề thi cố định", "Đề thi ngẫu nhiên"});

        formGrid.add(createFieldBox("Tên ca thi:", examTitleField));
        formGrid.add(createFieldBox("Môn thi:", examSubjectCombo));
        formGrid.add(createFieldBox("Thời lượng (phút):", examDurationSpinner));
        formGrid.add(createFieldBox("Hình thức ra đề:", examTypeCombo));
        centerCreatePanel.add(formGrid, BorderLayout.NORTH);

        // Bảng chọn câu hỏi trực quan cho ca thi
        JPanel pickPanel = new JPanel(new BorderLayout(0, 6));
        pickPanel.setBackground(Color.WHITE);

        JPanel pickHeader = new JPanel(new BorderLayout());
        pickHeader.setBackground(Color.WHITE);

        selectedQuestionCountLabel = new JLabel("Danh sách câu hỏi của môn đã chọn (Đã chọn: 0 câu hỏi):");
        selectedQuestionCountLabel.setFont(UITheme.FONT_BOLD);
        selectedQuestionCountLabel.setForeground(UITheme.TEXT_PRIMARY);
        pickHeader.add(selectedQuestionCountLabel, BorderLayout.WEST);

        JPanel pickTools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        pickTools.setBackground(Color.WHITE);

        JButton selectAllBtn = UITheme.createSecondaryButton("Chọn tất cả");
        selectAllBtn.addActionListener(e -> setAllQuestionsSelected(true));
        pickTools.add(selectAllBtn);

        JButton deselectAllBtn = UITheme.createSecondaryButton("Bỏ chọn tất cả");
        deselectAllBtn.addActionListener(e -> setAllQuestionsSelected(false));
        pickTools.add(deselectAllBtn);

        JSpinner randomCountSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 100, 1));
        pickTools.add(new JLabel("Số câu:"));
        pickTools.add(randomCountSpinner);

        JButton randomSelectBtn = UITheme.createSecondaryButton("Chọn ngẫu nhiên N câu");
        randomSelectBtn.addActionListener(e -> selectRandomQuestions((Integer) randomCountSpinner.getValue()));
        pickTools.add(randomSelectBtn);

        pickHeader.add(pickTools, BorderLayout.EAST);
        pickPanel.add(pickHeader, BorderLayout.NORTH);

        String[] pickCols = {"Chọn", "Mã câu", "Chương", "Độ khó", "Nội dung câu hỏi"};
        examQuestionPickTableModel = new DefaultTableModel(pickCols, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0; // Chỉ cho phép tích vào cột Chọn
            }
        };

        examQuestionPickTableModel.addTableModelListener(e -> updateSelectedQuestionCount());

        examQuestionPickTable = new JTable(examQuestionPickTableModel);
        examQuestionPickTable.setRowHeight(26);
        examQuestionPickTable.setFont(UITheme.FONT_BODY);
        examQuestionPickTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        examQuestionPickTable.getColumnModel().getColumn(1).setPreferredWidth(90);
        examQuestionPickTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        examQuestionPickTable.getColumnModel().getColumn(3).setPreferredWidth(90);
        examQuestionPickTable.getColumnModel().getColumn(4).setPreferredWidth(500);

        JScrollPane pickScroll = new JScrollPane(examQuestionPickTable);
        pickScroll.setPreferredSize(new Dimension(0, 150));
        pickPanel.add(pickScroll, BorderLayout.CENTER);

        centerCreatePanel.add(pickPanel, BorderLayout.CENTER);
        createCard.add(centerCreatePanel, BorderLayout.CENTER);

        // Nút tạo ca thi
        JButton createExamBtn = UITheme.createPrimaryButton("+ Tạo & Phát Hành Ca Thi");
        createExamBtn.setPreferredSize(new Dimension(200, 38));
        createExamBtn.addActionListener(e -> performCreateExam());

        JPanel createBottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        createBottom.setBackground(Color.WHITE);
        createBottom.add(createExamBtn);
        createCard.add(createBottom, BorderLayout.SOUTH);

        panel.add(createCard, BorderLayout.NORTH);

        // Khung 2: Giám sát ca thi trực tiếp
        JPanel monitorCard = UITheme.createCardPanel();
        monitorCard.setLayout(new BorderLayout(0, 16));

        JPanel monitorTopBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        monitorTopBar.setBackground(Color.WHITE);

        monitorTopBar.add(new JLabel("Mã ca thi cần giám sát:"));
        monitorExamIdField = new JTextField("exam-001", 12);
        monitorTopBar.add(monitorExamIdField);

        JButton checkReadinessBtn = UITheme.createSecondaryButton("Kiểm tra đồng bộ 4 máy chủ");
        checkReadinessBtn.addActionListener(e -> checkReadiness());
        monitorTopBar.add(checkReadinessBtn);

        readinessStatusLabel = new JLabel("Chưa kiểm tra đồng bộ");
        readinessStatusLabel.setFont(UITheme.FONT_BOLD);
        readinessStatusLabel.setForeground(UITheme.TEXT_SECONDARY);
        monitorTopBar.add(readinessStatusLabel);

        JButton refreshMonBtn = UITheme.createPrimaryButton("Làm Mới Giám Sát");
        refreshMonBtn.addActionListener(e -> refreshMonitor());
        monitorTopBar.add(refreshMonBtn);

        monitorCard.add(monitorTopBar, BorderLayout.NORTH);

        // 4 Thẻ trạng thái thí sinh
        JPanel statsCards = new JPanel(new GridLayout(1, 4, 16, 0));
        statsCards.setBackground(Color.WHITE);

        totalAssignedLabel = new JLabel("0", SwingConstants.CENTER);
        notStartedLabel = new JLabel("0", SwingConstants.CENTER);
        inProgressLabel = new JLabel("0", SwingConstants.CENTER);
        submittedLabel = new JLabel("0", SwingConstants.CENTER);

        statsCards.add(createStatCard("Tổng Số Thí Sinh", totalAssignedLabel, UITheme.PRIMARY_COLOR));
        statsCards.add(createStatCard("Chưa Vào Thi", notStartedLabel, UITheme.TEXT_SECONDARY));
        statsCards.add(createStatCard("Đang Làm Bài", inProgressLabel, UITheme.WARNING_COLOR));
        statsCards.add(createStatCard("Đã Nộp Bài", submittedLabel, UITheme.SUCCESS_COLOR));

        monitorCard.add(statsCards, BorderLayout.CENTER);
        panel.add(monitorCard, BorderLayout.CENTER);

        return panel;
    }

    private void loadSubjectQuestionsForExamPick() {
        if (examSubjectCombo == null || examSubjectCombo.getSelectedItem() == null) return;
        String selectedSubj = (String) examSubjectCombo.getSelectedItem();

        client.listQuestions(null, selectedSubj, null, null, 1, 200)
                .thenAccept(resp -> SwingUtilities.invokeLater(() -> {
                    examQuestionPickTableModel.setRowCount(0);
                    subjectQuestionsForPick = resp.getQuestions() != null ? resp.getQuestions() : Collections.emptyList();

                    for (QuestionDto q : subjectQuestionsForPick) {
                        String diffVN = "Trung bình";
                        if ("EASY".equalsIgnoreCase(q.getDifficulty())) diffVN = "Dễ";
                        else if ("HARD".equalsIgnoreCase(q.getDifficulty())) diffVN = "Khó";

                        examQuestionPickTableModel.addRow(new Object[]{
                                Boolean.TRUE, // Mặc định chọn tất cả câu hỏi của môn này
                                q.getId(),
                                q.getChapter() != null ? q.getChapter() : "-",
                                diffVN,
                                q.getContent()
                        });
                    }
                    updateSelectedQuestionCount();
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        examQuestionPickTableModel.setRowCount(0);
                        subjectQuestionsForPick.clear();
                        updateSelectedQuestionCount();
                    });
                    return null;
                });
    }

    private void setAllQuestionsSelected(boolean selected) {
        for (int i = 0; i < examQuestionPickTableModel.getRowCount(); i++) {
            examQuestionPickTableModel.setValueAt(selected, i, 0);
        }
        updateSelectedQuestionCount();
    }

    private void selectRandomQuestions(int count) {
        int totalRows = examQuestionPickTableModel.getRowCount();
        if (totalRows == 0) return;

        setAllQuestionsSelected(false);
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < totalRows; i++) indices.add(i);
        Collections.shuffle(indices);

        int pick = Math.min(count, totalRows);
        for (int i = 0; i < pick; i++) {
            examQuestionPickTableModel.setValueAt(true, indices.get(i), 0);
        }
        updateSelectedQuestionCount();
    }

    private void updateSelectedQuestionCount() {
        int count = 0;
        for (int i = 0; i < examQuestionPickTableModel.getRowCount(); i++) {
            Boolean val = (Boolean) examQuestionPickTableModel.getValueAt(i, 0);
            if (Boolean.TRUE.equals(val)) {
                count++;
            }
        }
        if (selectedQuestionCountLabel != null) {
            selectedQuestionCountLabel.setText(String.format("Danh sách câu hỏi của môn đã chọn (Đã chọn: %d câu hỏi):", count));
        }
    }

    private void performCreateExam() {
        String title = examTitleField.getText().trim();
        String subject = (String) examSubjectCombo.getSelectedItem();
        int duration = (Integer) examDurationSpinner.getValue();

        if (title.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập tên ca thi!", "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Lấy danh sách ID câu hỏi đã chọn
        List<String> chosenQuestionIds = new ArrayList<>();
        for (int i = 0; i < examQuestionPickTableModel.getRowCount(); i++) {
            Boolean chosen = (Boolean) examQuestionPickTableModel.getValueAt(i, 0);
            if (Boolean.TRUE.equals(chosen)) {
                String qId = (String) examQuestionPickTableModel.getValueAt(i, 1);
                chosenQuestionIds.add(qId);
            }
        }

        if (chosenQuestionIds.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Vui lòng chọn ít nhất 1 câu hỏi cho ca thi này!\n(Nếu chưa có câu hỏi, hãy sang Tab Ngân Hàng Câu Hỏi để tạo)",
                    "Chưa chọn câu hỏi", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Lấy danh sách sinh viên để phân công
        client.listUsers(null, "STUDENT", 1, 100)
                .thenAccept(userResp -> {
                    List<String> studentIds = new ArrayList<>();
                    if (userResp.getUsers() != null) {
                        for (UserDto u : userResp.getUsers()) {
                            studentIds.add(u.getId());
                        }
                    }

                    if (studentIds.isEmpty()) {
                        // Dự phòng nếu API không trả về
                        studentIds.addAll(Arrays.asList("usr-sv-01", "usr-sv-02", "usr-sv-03", "usr-sv-04", "usr-sv-05"));
                    }

                    CreateExamRequest req = new CreateExamRequest();
                    req.setTitle(title);
                    req.setSubject(subject);
                    req.setDurationMinutes(duration);
                    req.setStartTime(System.currentTimeMillis());
                    req.setEndTime(System.currentTimeMillis() + (long) duration * 60 * 1000L * 4); // Mở 4 lần thời lượng
                    req.setExamType("FIXED");
                    req.setQuestionIds(chosenQuestionIds);
                    req.setAssignedStudentIds(studentIds);

                    client.createExam(req)
                            .thenAccept(exam -> SwingUtilities.invokeLater(() -> {
                                JOptionPane.showMessageDialog(this,
                                        "Tạo ca thi thành công!\n" +
                                        "• Mã ca thi: " + exam.getId() + "\n" +
                                        "• Tên ca thi: " + exam.getTitle() + "\n" +
                                        "• Môn thi: " + exam.getSubject() + "\n" +
                                        "• Số lượng câu hỏi: " + chosenQuestionIds.size() + "\n" +
                                        "• Số sinh viên dự thi: " + studentIds.size(),
                                        "Tạo ca thi thành công", JOptionPane.INFORMATION_MESSAGE);

                                monitorExamIdField.setText(exam.getId());
                                scoreExamIdField.setText(exam.getId());
                                checkReadiness();
                                refreshMonitor();
                            }))
                            .exceptionally(ex -> {
                                SwingUtilities.invokeLater(() -> {
                                    String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                                    JOptionPane.showMessageDialog(this, "Lỗi tạo ca thi: " + errMsg, "Lỗi", JOptionPane.ERROR_MESSAGE);
                                });
                                return null;
                            });
                })
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        JOptionPane.showMessageDialog(this, "Lỗi lấy danh sách sinh viên: " + errMsg, "Lỗi", JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });
    }

    private void checkReadiness() {
        String examId = monitorExamIdField.getText().trim();
        if (examId.isEmpty()) return;

        readinessStatusLabel.setText("Đang kiểm tra 4 máy chủ...");
        readinessStatusLabel.setForeground(UITheme.TEXT_SECONDARY);

        client.getExamReadiness(examId)
                .thenAccept(dto -> SwingUtilities.invokeLater(() -> {
                    if (dto != null && dto.isReady()) {
                        readinessStatusLabel.setText("● Đã đồng bộ sẵn sàng trên cả 4 máy chủ");
                        readinessStatusLabel.setForeground(UITheme.SUCCESS_COLOR);
                    } else {
                        readinessStatusLabel.setText("● Chưa đồng bộ đủ 4 máy chủ");
                        readinessStatusLabel.setForeground(UITheme.DANGER_COLOR);
                    }
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        readinessStatusLabel.setText("Lỗi kiểm tra đồng bộ");
                        readinessStatusLabel.setForeground(UITheme.DANGER_COLOR);
                    });
                    return null;
                });
    }

    private void refreshMonitor() {
        String examId = monitorExamIdField.getText().trim();
        if (examId.isEmpty()) return;

        client.getExamMonitor(examId)
                .thenAccept(dto -> SwingUtilities.invokeLater(() -> {
                    if (dto == null) return;
                    totalAssignedLabel.setText(String.valueOf(dto.getTotalAssigned()));
                    notStartedLabel.setText(String.valueOf(dto.getNotStartedCount()));
                    inProgressLabel.setText(String.valueOf(dto.getInProgressCount()));
                    submittedLabel.setText(String.valueOf(dto.getSubmittedCount()));
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        log.warn("Lỗi giám sát ca thi: {}", errMsg);
                    });
                    return null;
                });
    }

    // =========================================================================
    // TAB 4: BẢNG ĐIỂM & THỐNG KÊ
    // =========================================================================

    private JPanel createScoresStatsTab() {
        JPanel panel = UITheme.createCardPanel();
        panel.setLayout(new BorderLayout(0, 16));

        // Top Filter Bar
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        topBar.setBackground(Color.WHITE);

        topBar.add(new JLabel("Mã ca thi:"));
        scoreExamIdField = new JTextField("exam-001", 12);
        topBar.add(scoreExamIdField);

        JButton loadScoreBtn = UITheme.createPrimaryButton("Xem Bảng Điểm");
        loadScoreBtn.addActionListener(e -> loadScoresAndStats());
        topBar.add(loadScoreBtn);

        exportCsvButton = UITheme.createSuccessButton("Xuất Excel (CSV)");
        exportCsvButton.addActionListener(e -> exportScoresCsv());
        topBar.add(exportCsvButton);

        exportProgressBar = new JProgressBar(0, 100);
        exportProgressBar.setPreferredSize(new Dimension(160, 24));
        exportProgressBar.setVisible(false);
        topBar.add(exportProgressBar);

        panel.add(topBar, BorderLayout.NORTH);

        // Center: Summary Stats Cards & Table
        JPanel centerPanel = new JPanel(new BorderLayout(0, 12));
        centerPanel.setBackground(Color.WHITE);

        JPanel statsRow = new JPanel(new GridLayout(1, 4, 12, 0));
        statsRow.setBackground(Color.WHITE);

        avgScoreLabel = new JLabel("0.00", SwingConstants.CENTER);
        highestScoreLabel = new JLabel("0.00", SwingConstants.CENTER);
        lowestScoreLabel = new JLabel("0.00", SwingConstants.CENTER);
        distributionLabel = new JLabel("Chưa có dữ liệu", SwingConstants.CENTER);

        statsRow.add(createStatCard("Điểm Trung Bình", avgScoreLabel, UITheme.PRIMARY_COLOR));
        statsRow.add(createStatCard("Điểm Cao Nhất", highestScoreLabel, UITheme.SUCCESS_COLOR));
        statsRow.add(createStatCard("Điểm Thấp Nhất", lowestScoreLabel, UITheme.DANGER_COLOR));
        statsRow.add(createStatCard("Phổ Điểm (Giỏi/Khá/TB)", distributionLabel, UITheme.WARNING_COLOR));

        centerPanel.add(statsRow, BorderLayout.NORTH);

        // Bảng điểm chi tiết
        String[] cols = {"Mã thí sinh", "Họ và tên", "Lớp", "Điểm số", "Số câu đúng", "Thời gian nộp", "Trạng thái"};
        scoreTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };

        scoreTable = new JTable(scoreTableModel);
        scoreTable.setRowHeight(30);
        scoreTable.setFont(UITheme.FONT_BODY);
        scoreTable.getTableHeader().setFont(UITheme.FONT_BOLD);

        DefaultTableCellRenderer centerRender = new DefaultTableCellRenderer();
        centerRender.setHorizontalAlignment(JLabel.CENTER);
        scoreTable.getColumnModel().getColumn(0).setPreferredWidth(100);
        scoreTable.getColumnModel().getColumn(0).setCellRenderer(centerRender);
        scoreTable.getColumnModel().getColumn(1).setPreferredWidth(200);
        scoreTable.getColumnModel().getColumn(2).setPreferredWidth(90);
        scoreTable.getColumnModel().getColumn(2).setCellRenderer(centerRender);
        scoreTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        scoreTable.getColumnModel().getColumn(3).setCellRenderer(centerRender);
        scoreTable.getColumnModel().getColumn(4).setPreferredWidth(90);
        scoreTable.getColumnModel().getColumn(4).setCellRenderer(centerRender);
        scoreTable.getColumnModel().getColumn(5).setPreferredWidth(140);
        scoreTable.getColumnModel().getColumn(5).setCellRenderer(centerRender);
        scoreTable.getColumnModel().getColumn(6).setPreferredWidth(110);
        scoreTable.getColumnModel().getColumn(6).setCellRenderer(centerRender);

        centerPanel.add(new JScrollPane(scoreTable), BorderLayout.CENTER);
        panel.add(centerPanel, BorderLayout.CENTER);

        return panel;
    }

    private void loadScoresAndStats() {
        String examId = scoreExamIdField.getText().trim();
        if (examId.isEmpty()) return;

        client.getScores(examId, null, 1, 100, "score_desc")
                .thenAccept(resp -> SwingUtilities.invokeLater(() -> {
                    scoreTableModel.setRowCount(0);
                    if (resp.getScores() != null) {
                        for (ScoreItemDto s : resp.getScores()) {
                            String submitStr = s.getSubmittedAt() != null && s.getSubmittedAt() > 0 ? DATE_FORMAT.format(new Date(s.getSubmittedAt())) : "-";
                            String scoreStr = s.getScore() != null ? String.format("%.2f", s.getScore()) : "-";
                            String stVN = "Đã nộp bài";
                            if ("IN_PROGRESS".equalsIgnoreCase(s.getStatus())) stVN = "Đang làm bài";
                            else if ("NOT_STARTED".equalsIgnoreCase(s.getStatus())) stVN = "Chưa thi";

                            scoreTableModel.addRow(new Object[]{
                                    s.getStudentCode(),
                                    s.getFullName(),
                                    "-",
                                    scoreStr,
                                    s.getCorrectCount() != null ? s.getCorrectCount() + " câu" : "-",
                                    submitStr,
                                    stVN
                            });
                        }
                    }
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        JOptionPane.showMessageDialog(this, "Lỗi tải bảng điểm: " + errMsg, "Lỗi", JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });

        client.getStats(examId)
                .thenAccept(stats -> SwingUtilities.invokeLater(() -> {
                    if (stats == null) return;
                    avgScoreLabel.setText(stats.getAverageScore() != null ? String.format("%.2f", stats.getAverageScore()) : "0.00");
                    highestScoreLabel.setText(stats.getHighestScore() != null ? String.format("%.2f", stats.getHighestScore()) : "0.00");
                    lowestScoreLabel.setText(stats.getLowestScore() != null ? String.format("%.2f", stats.getLowestScore()) : "0.00");

                    if (stats.getScoreDistribution() != null) {
                        Map<String, Integer> dist = stats.getScoreDistribution();
                        distributionLabel.setText(String.format("0-5: %d | 5-7: %d | 7-9: %d | 9-10: %d",
                                dist.getOrDefault("0-5", 0),
                                dist.getOrDefault("5-7", 0),
                                dist.getOrDefault("7-9", 0),
                                dist.getOrDefault("9-10", 0)));
                    }
                }))
                .exceptionally(ex -> null);
    }

    private void exportScoresCsv() {
        String examId = scoreExamIdField.getText().trim();
        if (examId.isEmpty()) return;

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Lưu Bảng Điểm Thi (Excel CSV)");
        fileChooser.setSelectedFile(new File("BangDiem_" + examId + ".csv"));

        int userSelection = fileChooser.showSaveDialog(this);
        if (userSelection != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File targetFile = fileChooser.getSelectedFile();
        exportProgressBar.setValue(0);
        exportProgressBar.setVisible(true);
        exportCsvButton.setEnabled(false);

        client.fetchAllScores(examId, progress -> SwingUtilities.invokeLater(() -> {
            int pct = (int) (progress * 100);
            exportProgressBar.setValue(pct);
        })).thenAccept(scores -> {
            try (FileOutputStream fos = new FileOutputStream(targetFile);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                 PrintWriter pw = new PrintWriter(osw)) {

                // Ghi UTF-8 BOM để Excel hiển thị đúng dấu tiếng Việt
                fos.write(0xEF);
                fos.write(0xBB);
                fos.write(0xBF);

                // Dòng tiêu đề CSV
                pw.println("STT,Mã sinh viên,Họ và tên,Điểm số,Số câu đúng,Thời gian nộp bài,Trạng thái");

                int idx = 1;
                for (ScoreItemDto s : scores) {
                    String subStr = s.getSubmittedAt() != null && s.getSubmittedAt() > 0 ? DATE_FORMAT.format(new Date(s.getSubmittedAt())) : "-";
                    String scoreVal = s.getScore() != null ? String.format(Locale.US, "%.2f", s.getScore()) : "-";
                    String stVN = "Đã nộp bài";
                    if ("IN_PROGRESS".equalsIgnoreCase(s.getStatus())) stVN = "Đang làm bài";
                    else if ("NOT_STARTED".equalsIgnoreCase(s.getStatus())) stVN = "Chưa thi";

                    pw.printf("%d,\"%s\",\"%s\",%s,%s,\"%s\",\"%s\"%n",
                            idx++,
                            s.getStudentCode() != null ? s.getStudentCode() : "",
                            s.getFullName() != null ? s.getFullName() : "",
                            scoreVal,
                            s.getCorrectCount() != null ? s.getCorrectCount() : 0,
                            subStr,
                            stVN
                    );
                }
                pw.flush();

                SwingUtilities.invokeLater(() -> {
                    exportProgressBar.setVisible(false);
                    exportCsvButton.setEnabled(true);
                    JOptionPane.showMessageDialog(this,
                            String.format("Xuất thành công %d bản ghi bảng điểm ra file:\n%s", scores.size(), targetFile.getAbsolutePath()),
                            "Xuất Excel thành công", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    exportProgressBar.setVisible(false);
                    exportCsvButton.setEnabled(true);
                    JOptionPane.showMessageDialog(this, "Lỗi ghi file CSV: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).exceptionally(ex -> {
            SwingUtilities.invokeLater(() -> {
                exportProgressBar.setVisible(false);
                exportCsvButton.setEnabled(true);
                String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                JOptionPane.showMessageDialog(this, "Lỗi xuất bảng điểm: " + errMsg, "Lỗi", JOptionPane.ERROR_MESSAGE);
            });
            return null;
        });
    }

    // =========================================================================
    // TAB 5: PHÊ DUYỆT THI LẠI
    // =========================================================================

    private JPanel createRetakeApprovalTab() {
        JPanel panel = UITheme.createCardPanel();
        panel.setLayout(new BorderLayout(0, 12));

        // Thanh công cụ trên
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(Color.WHITE);

        JLabel tabTitle = new JLabel("Danh Sách Yêu Cầu Xin Thi Lại Từ Thí Sinh");
        tabTitle.setFont(UITheme.FONT_HEADER);
        tabTitle.setForeground(UITheme.PRIMARY_COLOR);
        topBar.add(tabTitle, BorderLayout.WEST);

        JPanel filterGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        filterGroup.setBackground(Color.WHITE);

        JLabel filterLbl = new JLabel("Trạng thái:");
        filterLbl.setFont(UITheme.FONT_BOLD);
        retakeStatusFilterCombo = new JComboBox<>(new String[]{"TẤT CẢ", "Chờ duyệt", "Đã duyệt", "Từ chối"});
        retakeStatusFilterCombo.addActionListener(e -> loadRetakeRequests());

        refreshRetakeBtn = UITheme.createSecondaryButton("Làm Mới Danh Sách");
        refreshRetakeBtn.addActionListener(e -> loadRetakeRequests());

        filterGroup.add(filterLbl);
        filterGroup.add(retakeStatusFilterCombo);
        filterGroup.add(refreshRetakeBtn);
        topBar.add(filterGroup, BorderLayout.EAST);
        panel.add(topBar, BorderLayout.NORTH);

        // Bảng danh sách yêu cầu
        String[] columns = {"Mã Yêu Cầu", "Mã SV", "Họ Và Tên", "Mã Ca Thi", "Tên Ca Thi", "Môn Thi", "Lý Do Xin Thi Lại", "Thời Gian Gửi", "Trạng Thái"};
        retakeTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        retakeTable = new JTable(retakeTableModel);
        retakeTable.setFont(UITheme.FONT_BODY);
        retakeTable.setRowHeight(32);
        retakeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        retakeTable.getSelectionModel().addListSelectionListener(e -> updateRetakeButtonState());

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        retakeTable.getColumnModel().getColumn(0).setPreferredWidth(80);
        retakeTable.getColumnModel().getColumn(1).setPreferredWidth(90);
        retakeTable.getColumnModel().getColumn(2).setPreferredWidth(160);
        retakeTable.getColumnModel().getColumn(3).setPreferredWidth(90);
        retakeTable.getColumnModel().getColumn(4).setPreferredWidth(180);
        retakeTable.getColumnModel().getColumn(5).setPreferredWidth(130);
        retakeTable.getColumnModel().getColumn(6).setPreferredWidth(230);
        retakeTable.getColumnModel().getColumn(7).setPreferredWidth(130);
        retakeTable.getColumnModel().getColumn(8).setPreferredWidth(110);

        for (int i : new int[]{0, 1, 3, 7, 8}) {
            retakeTable.getColumnModel().getColumn(i).setCellRenderer(centerRenderer);
        }

        JScrollPane scrollPane = new JScrollPane(retakeTable);
        scrollPane.setBorder(BorderFactory.createLineBorder(UITheme.BORDER_COLOR));
        panel.add(scrollPane, BorderLayout.CENTER);

        // Thanh điều khiển dưới
        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBackground(Color.WHITE);

        JLabel hintLbl = new JLabel("<html><i>* Lưu ý: Khi Giảng viên phê duyệt, bài làm cũ của thí sinh sẽ được xóa hoàn toàn và ca thi sẽ mở lại để thí sinh vào thi lại ngay.</i></html>");
        hintLbl.setFont(UITheme.FONT_SMALL);
        hintLbl.setForeground(UITheme.TEXT_SECONDARY);
        bottomBar.add(hintLbl, BorderLayout.WEST);

        JPanel btnGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnGroup.setBackground(Color.WHITE);

        rejectRetakeBtn = UITheme.createSecondaryButton("✗ Từ Chối Yêu Cầu");
        rejectRetakeBtn.setEnabled(false);
        rejectRetakeBtn.addActionListener(e -> onRejectRetakeClicked());

        approveRetakeBtn = UITheme.createSuccessButton("✓ Duyệt Cho Thi Lại");
        approveRetakeBtn.setEnabled(false);
        approveRetakeBtn.addActionListener(e -> onApproveRetakeClicked());

        btnGroup.add(rejectRetakeBtn);
        btnGroup.add(approveRetakeBtn);
        bottomBar.add(btnGroup, BorderLayout.EAST);

        panel.add(bottomBar, BorderLayout.SOUTH);
        return panel;
    }

    private void loadRetakeRequests() {
        if (refreshRetakeBtn != null) refreshRetakeBtn.setEnabled(false);

        String statusFilter = "ALL";
        if (retakeStatusFilterCombo != null) {
            int idx = retakeStatusFilterCombo.getSelectedIndex();
            if (idx == 1) statusFilter = "PENDING";
            else if (idx == 2) statusFilter = "APPROVED";
            else if (idx == 3) statusFilter = "REJECTED";
        }

        client.listRetakeRequests(null, statusFilter)
                .thenAccept(list -> SwingUtilities.invokeLater(() -> {
                    if (refreshRetakeBtn != null) refreshRetakeBtn.setEnabled(true);
                    currentRetakeRequests = list != null ? list : new ArrayList<>();
                    retakeTableModel.setRowCount(0);

                    for (RetakeRequestDto req : currentRetakeRequests) {
                        String st = req.getStatus();
                        String stDisplay = "Chờ duyệt";
                        if ("APPROVED".equalsIgnoreCase(st)) stDisplay = "Đã duyệt";
                        else if ("REJECTED".equalsIgnoreCase(st)) stDisplay = "Từ chối";

                        String timeStr = req.getCreatedAt() != null && req.getCreatedAt() > 0 ?
                                DATE_FORMAT.format(new Date(req.getCreatedAt())) : "-";

                        retakeTableModel.addRow(new Object[]{
                                req.getId().length() > 8 ? req.getId().substring(0, 8) + "..." : req.getId(),
                                req.getStudentCode(),
                                req.getStudentName(),
                                req.getExamId(),
                                req.getExamTitle() != null ? req.getExamTitle() : req.getExamId(),
                                req.getSubject() != null ? req.getSubject() : "-",
                                req.getReason(),
                                timeStr,
                                stDisplay
                        });
                    }
                    updateRetakeButtonState();
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        if (refreshRetakeBtn != null) refreshRetakeBtn.setEnabled(true);
                        String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        JOptionPane.showMessageDialog(this, "Lỗi tải danh sách xin thi lại: " + errMsg,
                                "Thông báo", JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });
    }

    private void updateRetakeButtonState() {
        int row = retakeTable != null ? retakeTable.getSelectedRow() : -1;
        if (row < 0 || row >= currentRetakeRequests.size()) {
            if (approveRetakeBtn != null) approveRetakeBtn.setEnabled(false);
            if (rejectRetakeBtn != null) rejectRetakeBtn.setEnabled(false);
            return;
        }

        RetakeRequestDto req = currentRetakeRequests.get(row);
        boolean isPending = "PENDING".equalsIgnoreCase(req.getStatus());

        if (approveRetakeBtn != null) approveRetakeBtn.setEnabled(isPending);
        if (rejectRetakeBtn != null) rejectRetakeBtn.setEnabled(isPending);
    }

    private void onApproveRetakeClicked() {
        int row = retakeTable.getSelectedRow();
        if (row < 0 || row >= currentRetakeRequests.size()) return;

        RetakeRequestDto req = currentRetakeRequests.get(row);

        int confirm = JOptionPane.showConfirmDialog(this,
                String.format("Bạn có chắc chắn muốn PHÊ DUYỆT cho thí sinh được thi lại?\n\n" +
                                "• Thí sinh: %s (%s)\n" +
                                "• Ca thi: %s\n" +
                                "• Môn thi: %s\n" +
                                "• Lý do: %s\n\n" +
                                "Hệ thống sẽ xóa bài làm cũ và mở lại ca thi để thí sinh có thể vào thi lại ngay!",
                        req.getStudentName(), req.getStudentCode(), req.getExamTitle(), req.getSubject(), req.getReason()),
                "Xác nhận phê duyệt thi lại", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) return;

        approveRetakeBtn.setEnabled(false);
        rejectRetakeBtn.setEnabled(false);

        client.decideRetakeRequest(req.getId(), "APPROVE")
                .thenAccept(v -> SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this,
                            String.format("Đã phê duyệt cho sinh viên %s (%s) thi lại ca thi thành công!\nBài làm cũ đã được xóa và ca thi đã sẵn sàng.",
                                    req.getStudentName(), req.getStudentCode()),
                            "Phê duyệt thành công", JOptionPane.INFORMATION_MESSAGE);
                    loadRetakeRequests();
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        updateRetakeButtonState();
                        String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        JOptionPane.showMessageDialog(this, "Lỗi phê duyệt thi lại: " + errMsg,
                                "Lỗi phê duyệt", JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });
    }

    private void onRejectRetakeClicked() {
        int row = retakeTable.getSelectedRow();
        if (row < 0 || row >= currentRetakeRequests.size()) return;

        RetakeRequestDto req = currentRetakeRequests.get(row);

        int confirm = JOptionPane.showConfirmDialog(this,
                String.format("Bạn có chắc chắn muốn TỪ CHỐI yêu cầu xin thi lại của thí sinh:\n" +
                                "• Thí sinh: %s (%s)\n" +
                                "• Ca thi: %s\n" +
                                "• Lý do xin thi lại: %s",
                        req.getStudentName(), req.getStudentCode(), req.getExamTitle(), req.getReason()),
                "Xác nhận từ chối", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) return;

        approveRetakeBtn.setEnabled(false);
        rejectRetakeBtn.setEnabled(false);

        client.decideRetakeRequest(req.getId(), "REJECT")
                .thenAccept(v -> SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Đã từ chối yêu cầu xin thi lại thành công.",
                            "Từ chối thành công", JOptionPane.INFORMATION_MESSAGE);
                    loadRetakeRequests();
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        updateRetakeButtonState();
                        String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        JOptionPane.showMessageDialog(this, "Lỗi từ chối yêu cầu: " + errMsg,
                                "Lỗi", JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });
    }

    // =========================================================================
    // TIỆN ÍCH GIAO DIỆN
    // =========================================================================

    private JPanel createFieldBox(String labelText, JComponent comp) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBackground(Color.WHITE);
        JLabel lbl = new JLabel(labelText);
        lbl.setFont(UITheme.FONT_BOLD);
        p.add(lbl, BorderLayout.NORTH);
        p.add(comp, BorderLayout.CENTER);
        return p;
    }

    private JPanel createStatCard(String title, JLabel valueLabel, Color accentColor) {
        JPanel card = new JPanel(new BorderLayout(0, 4));
        card.setBackground(new Color(250, 250, 250));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(3, 0, 0, 0, accentColor),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(UITheme.BORDER_COLOR, 1),
                        new EmptyBorder(12, 16, 12, 16)
                )
        ));

        JLabel tLbl = new JLabel(title, SwingConstants.CENTER);
        tLbl.setFont(UITheme.FONT_SMALL);
        tLbl.setForeground(UITheme.TEXT_SECONDARY);

        valueLabel.setFont(new Font("Segoe UI", Font.BOLD, 22));
        valueLabel.setForeground(accentColor);

        card.add(tLbl, BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    private void logout() {
        client.disconnect();
        this.dispose();
        loginFrame.setVisible(true);
    }
}
