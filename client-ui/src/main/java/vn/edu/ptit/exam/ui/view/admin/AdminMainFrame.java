package vn.edu.ptit.exam.ui.view.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.client.ExamClient;
import vn.edu.ptit.exam.common.dto.*;
import vn.edu.ptit.exam.common.enums.UserRole;
import vn.edu.ptit.exam.ui.util.UITheme;
import vn.edu.ptit.exam.ui.view.LoginFrame;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Giao diện Quản trị viên hệ thống.
 * Tab 1: Giám sát Cụm máy chủ & Thay đổi Thuật toán điều phối tải.
 * Tab 2: Quản lý Tài khoản người dùng (Thêm / Sửa / Đặt lại mật khẩu / Khóa / Nhập file CSV).
 * Tab 3: Nhật ký hoạt động & Kiểm toán hệ thống.
 */
public class AdminMainFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(AdminMainFrame.class);
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private final ExamClient client;
    private final LoginFrame loginFrame;

    // --- Tab 1: Cụm máy chủ & Điều phối tải ---
    private JLabel currentAlgoLabel;
    private JLabel activeSessionsLabel;
    private JLabel queuedRequestsLabel;
    private JComboBox<String> algoSelectorCombo;
    private JTable serverTable;
    private DefaultTableModel serverTableModel;

    // --- Tab 2: Quản lý người dùng ---
    private JTable userTable;
    private DefaultTableModel userTableModel;
    private JComboBox<String> roleFilterCombo;
    private JTextField userSearchField;
    private List<UserDto> currentUsers = new ArrayList<>();

    // --- Tab 3: Nhật ký hệ thống ---
    private JComboBox<String> logTypeCombo;
    private JTextField logKeywordField;
    private JTable logTable;
    private DefaultTableModel logTableModel;

    public AdminMainFrame(ExamClient client, LoginFrame loginFrame) {
        this.client = client;
        this.loginFrame = loginFrame;

        initUI();
        refreshClusterStatus();
        loadUsers();
        loadLogs();
    }

    private void initUI() {
        setTitle("Cổng Quản Trị Hệ Thống - Quản Trị Viên: " + client.getCurrentFullName());
        setSize(1220, 770);
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

        JLabel userLabel = new JLabel(String.format("Quản trị viên: %s (%s) | Hệ thống thi trực tuyến đa máy chủ",
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

        // 2. Các Tab chức năng
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(UITheme.FONT_BOLD);
        tabbedPane.addTab("  Giám Sát Cụm Máy Chủ & Điều Phối Tải  ", createClusterTab());
        tabbedPane.addTab("  Quản Lý Tài Khoản Người Dùng  ", createUserManagementTab());
        tabbedPane.addTab("  Nhật Ký & Kiểm Toán Hoạt Động  ", createLogsTab());

        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        setContentPane(mainPanel);
    }

    // =========================================================================
    // TAB 1: GIÁM SÁT CỤM MÁY CHỦ & ĐIỀU PHỐI TẢI
    // =========================================================================

    private JPanel createClusterTab() {
        JPanel panel = UITheme.createCardPanel();
        panel.setLayout(new BorderLayout(0, 16));

        // Thanh điều khiển trên: Thuật toán & Làm mới
        JPanel topBox = new JPanel(new BorderLayout(16, 0));
        topBox.setBackground(Color.WHITE);

        JPanel infoCards = new JPanel(new GridLayout(1, 3, 12, 0));
        infoCards.setBackground(Color.WHITE);

        currentAlgoLabel = new JLabel("Xoay vòng tuần tự", SwingConstants.CENTER);
        activeSessionsLabel = new JLabel("0", SwingConstants.CENTER);
        queuedRequestsLabel = new JLabel("0", SwingConstants.CENTER);

        infoCards.add(createMiniCard("Thuật Toán Điều Phối Hiện Tại", currentAlgoLabel, UITheme.PRIMARY_COLOR));
        infoCards.add(createMiniCard("Phiên Kết Nối Đang Hoạt Động", activeSessionsLabel, UITheme.SUCCESS_COLOR));
        infoCards.add(createMiniCard("Yêu Cầu Chờ Trong Hàng Đợi", queuedRequestsLabel, UITheme.WARNING_COLOR));
        topBox.add(infoCards, BorderLayout.CENTER);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        controlPanel.setBackground(Color.WHITE);
        controlPanel.add(new JLabel("Đổi thuật toán:"));

        algoSelectorCombo = new JComboBox<>(new String[]{"Xoay vòng tuần tự", "Ít kết nối nhất theo trọng số"});
        controlPanel.add(algoSelectorCombo);

        JButton applyAlgoBtn = UITheme.createPrimaryButton("Áp Dụng");
        applyAlgoBtn.addActionListener(e -> applyAlgorithm());
        controlPanel.add(applyAlgoBtn);

        JButton refreshBtn = UITheme.createSecondaryButton("Làm Mới Cụm");
        refreshBtn.addActionListener(e -> refreshClusterStatus());
        controlPanel.add(refreshBtn);

        topBox.add(controlPanel, BorderLayout.EAST);
        panel.add(topBox, BorderLayout.NORTH);

        // Bảng cụm máy chủ
        String[] columns = {"Mã máy chủ", "Địa chỉ mạng (Host:Port)", "Trạng thái", "Kết nối hiện thời", "Trọng số cơ sở", "Trọng số hiệu dụng", "Lệch giờ (ms)", "Độ trễ TB (ms)"};
        serverTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };

        serverTable = new JTable(serverTableModel);
        serverTable.setRowHeight(32);
        serverTable.setFont(UITheme.FONT_BODY);
        serverTable.getTableHeader().setFont(UITheme.FONT_BOLD);

        DefaultTableCellRenderer centerRender = new DefaultTableCellRenderer();
        centerRender.setHorizontalAlignment(JLabel.CENTER);
        for (int i = 0; i < columns.length; i++) {
            if (i != 1) serverTable.getColumnModel().getColumn(i).setCellRenderer(centerRender);
        }

        // Renderer màu sắc trạng thái
        serverTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object val, boolean sel, boolean focus, int row, int col) {
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(t, val, sel, focus, row, col);
                lbl.setHorizontalAlignment(SwingConstants.CENTER);
                lbl.setFont(UITheme.FONT_BOLD);
                String st = val != null ? val.toString() : "";
                if (st.contains("hoạt động") || "UP".equalsIgnoreCase(st)) {
                    lbl.setForeground(UITheme.SUCCESS_COLOR);
                    lbl.setText("● Đang hoạt động");
                } else {
                    lbl.setForeground(UITheme.DANGER_COLOR);
                    lbl.setText("● Ngừng hoạt động");
                }
                return lbl;
            }
        });

        panel.add(new JScrollPane(serverTable), BorderLayout.CENTER);
        return panel;
    }

    private void refreshClusterStatus() {
        client.getAdminStatus()
                .thenAccept(data -> SwingUtilities.invokeLater(() -> {
                    if (data == null) return;

                    if (data.containsKey("algorithm")) {
                        String algo = String.valueOf(data.get("algorithm"));
                        if ("ROUND_ROBIN".equalsIgnoreCase(algo)) {
                            currentAlgoLabel.setText("Xoay vòng tuần tự");
                            algoSelectorCombo.setSelectedIndex(0);
                        } else {
                            currentAlgoLabel.setText("Ít kết nối nhất");
                            algoSelectorCombo.setSelectedIndex(1);
                        }
                    }
                    if (data.containsKey("activeSessions")) {
                        activeSessionsLabel.setText(String.valueOf(data.get("activeSessions")));
                    }
                    if (data.containsKey("queuedRequests")) {
                        queuedRequestsLabel.setText(String.valueOf(data.get("queuedRequests")));
                    }

                    serverTableModel.setRowCount(0);
                    Object serversObj = data.get("servers");
                    if (serversObj instanceof List<?> list) {
                        for (Object item : list) {
                            if (item instanceof Map<?, ?> m) {
                                String rawSt = String.valueOf(m.get("status"));
                                String stVN = "UP".equalsIgnoreCase(rawSt) ? "Đang hoạt động" : "Ngừng hoạt động";

                                serverTableModel.addRow(new Object[]{
                                        m.get("serverId"),
                                        m.get("host") + ":" + m.get("port"),
                                        stVN,
                                        m.get("activeConnections"),
                                        m.get("baseWeight"),
                                        m.get("effectiveWeight"),
                                        m.get("clockSkewMs"),
                                        m.get("avgResponseTimeMs")
                                });
                            }
                        }
                    }
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> log.warn("Lỗi làm mới cụm máy chủ: {}", ex.getMessage()));
                    return null;
                });
    }

    private void applyAlgorithm() {
        int idx = algoSelectorCombo.getSelectedIndex();
        String selectedAlgo = (idx == 0) ? "ROUND_ROBIN" : "WEIGHTED_LEAST_CONNECTIONS";
        String algoVN = (idx == 0) ? "Xoay vòng tuần tự" : "Ít kết nối nhất theo trọng số";

        client.setAdminAlgorithm(selectedAlgo, null)
                .thenAccept(res -> SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this,
                            "Chuyển đổi thuật toán điều phối thành công: " + algoVN,
                            "Thành công", JOptionPane.INFORMATION_MESSAGE);
                    refreshClusterStatus();
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        JOptionPane.showMessageDialog(this, "Lỗi cập nhật thuật toán: " + errMsg, "Lỗi", JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });
    }

    // =========================================================================
    // TAB 2: QUẢN LÝ TÀI KHOẢN NGƯỜI DÙNG
    // =========================================================================

    private JPanel createUserManagementTab() {
        JPanel panel = UITheme.createCardPanel();
        panel.setLayout(new BorderLayout(0, 12));

        // Thanh công cụ trên
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(Color.WHITE);

        JPanel leftFilters = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        leftFilters.setBackground(Color.WHITE);
        leftFilters.add(new JLabel("Vai trò:"));
        roleFilterCombo = new JComboBox<>(new String[]{"TẤT CẢ", "Sinh viên", "Giảng viên", "Quản trị viên"});
        roleFilterCombo.addActionListener(e -> loadUsers());
        leftFilters.add(roleFilterCombo);

        leftFilters.add(new JLabel("Tìm kiếm:"));
        userSearchField = new JTextField(15);
        userSearchField.addActionListener(e -> loadUsers());
        leftFilters.add(userSearchField);

        JButton searchBtn = UITheme.createPrimaryButton("Lọc");
        searchBtn.addActionListener(e -> loadUsers());
        leftFilters.add(searchBtn);

        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightActions.setBackground(Color.WHITE);

        JButton addBtn = UITheme.createSuccessButton("+ Thêm Người Dùng");
        addBtn.addActionListener(e -> showAddUserDialog());
        rightActions.add(addBtn);

        JButton editBtn = UITheme.createPrimaryButton("Sửa");
        editBtn.addActionListener(e -> showEditUserDialog());
        rightActions.add(editBtn);

        JButton resetPwdBtn = UITheme.createSecondaryButton("Đặt Lại MK");
        resetPwdBtn.addActionListener(e -> showResetPasswordDialog());
        rightActions.add(resetPwdBtn);

        JButton toggleLockBtn = UITheme.createDangerButton("Khóa / Mở Khóa");
        toggleLockBtn.addActionListener(e -> toggleLockUser());
        rightActions.add(toggleLockBtn);

        JButton importCsvBtn = UITheme.createSecondaryButton("Nhập CSV");
        importCsvBtn.addActionListener(e -> importUsersFromCsv());
        rightActions.add(importCsvBtn);

        topBar.add(leftFilters, BorderLayout.WEST);
        topBar.add(rightActions, BorderLayout.EAST);
        panel.add(topBar, BorderLayout.NORTH);

        // Bảng tài khoản
        String[] cols = {"Mã ID", "Tên đăng nhập", "Họ và tên", "Email", "Vai trò", "Lớp", "Trạng thái", "Phiên bản"};
        userTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };

        userTable = new JTable(userTableModel);
        userTable.setRowHeight(30);
        userTable.setFont(UITheme.FONT_BODY);
        userTable.getTableHeader().setFont(UITheme.FONT_BOLD);
        userTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        DefaultTableCellRenderer centerRender = new DefaultTableCellRenderer();
        centerRender.setHorizontalAlignment(JLabel.CENTER);
        userTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        userTable.getColumnModel().getColumn(0).setCellRenderer(centerRender);
        userTable.getColumnModel().getColumn(1).setPreferredWidth(110);
        userTable.getColumnModel().getColumn(2).setPreferredWidth(180);
        userTable.getColumnModel().getColumn(3).setPreferredWidth(180);
        userTable.getColumnModel().getColumn(4).setPreferredWidth(110);
        userTable.getColumnModel().getColumn(4).setCellRenderer(centerRender);
        userTable.getColumnModel().getColumn(5).setPreferredWidth(90);
        userTable.getColumnModel().getColumn(5).setCellRenderer(centerRender);
        userTable.getColumnModel().getColumn(6).setPreferredWidth(110);
        userTable.getColumnModel().getColumn(6).setCellRenderer(centerRender);
        userTable.getColumnModel().getColumn(7).setPreferredWidth(70);
        userTable.getColumnModel().getColumn(7).setCellRenderer(centerRender);

        panel.add(new JScrollPane(userTable), BorderLayout.CENTER);
        return panel;
    }

    private void loadUsers() {
        int roleIdx = roleFilterCombo.getSelectedIndex();
        String roleParam = null;
        if (roleIdx == 1) roleParam = "STUDENT";
        else if (roleIdx == 2) roleParam = "LECTURER";
        else if (roleIdx == 3) roleParam = "ADMIN";

        String kw = userSearchField.getText().trim();
        if (kw.isEmpty()) kw = null;

        client.listUsers(kw, roleParam, 1, 100)
                .thenAccept(resp -> SwingUtilities.invokeLater(() -> {
                    userTableModel.setRowCount(0);
                    currentUsers = resp.getUsers() != null ? resp.getUsers() : Collections.emptyList();
                    for (UserDto u : currentUsers) {
                        String roleVN = "Sinh viên";
                        if (u.getRole() == UserRole.LECTURER) roleVN = "Giảng viên";
                        else if (u.getRole() == UserRole.ADMIN) roleVN = "Quản trị viên";

                        String stVN = "LOCKED".equalsIgnoreCase(u.getStatus()) ? "Bị khóa" : "Đang hoạt động";

                        userTableModel.addRow(new Object[]{
                                u.getId(),
                                u.getUsername(),
                                u.getFullName(),
                                u.getEmail() != null ? u.getEmail() : "-",
                                roleVN,
                                u.getClassName() != null ? u.getClassName() : "-",
                                stVN,
                                u.getVersion() != null ? u.getVersion() : 1
                        });
                    }
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        JOptionPane.showMessageDialog(this, "Lỗi tải người dùng: " + errMsg, "Lỗi", JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });
    }

    private void showAddUserDialog() {
        JDialog dialog = new JDialog(this, "Thêm Người Dùng Mới", true);
        dialog.setSize(440, 360);
        dialog.setLocationRelativeTo(this);

        JPanel form = new JPanel(new GridLayout(6, 2, 8, 10));
        form.setBorder(new EmptyBorder(16, 20, 16, 20));
        form.setBackground(Color.WHITE);

        JTextField uField = new JTextField();
        JPasswordField pField = new JPasswordField("123456");
        JTextField fField = new JTextField();
        JTextField eField = new JTextField();
        JComboBox<String> rCombo = new JComboBox<>(new String[]{"Sinh viên", "Giảng viên", "Quản trị viên"});
        JTextField cField = new JTextField("CNTT-01");

        form.add(new JLabel("Tên đăng nhập:")); form.add(uField);
        form.add(new JLabel("Mật khẩu ban đầu:")); form.add(pField);
        form.add(new JLabel("Họ và tên:")); form.add(fField);
        form.add(new JLabel("Email:")); form.add(eField);
        form.add(new JLabel("Vai trò:")); form.add(rCombo);
        form.add(new JLabel("Lớp (nếu là SV):")); form.add(cField);

        JButton saveBtn = UITheme.createPrimaryButton("Lưu Người Dùng");
        saveBtn.addActionListener(e -> {
            String u = uField.getText().trim();
            String p = new String(pField.getPassword()).trim();
            String f = fField.getText().trim();
            if (u.isEmpty() || p.isEmpty() || f.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Vui lòng nhập tên đăng nhập, mật khẩu và họ tên!", "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
                return;
            }

            UserDto newUser = new UserDto();
            newUser.setUsername(u);
            newUser.setPassword(p);
            newUser.setFullName(f);
            newUser.setEmail(eField.getText().trim());

            int rIdx = rCombo.getSelectedIndex();
            UserRole roleCode = UserRole.STUDENT;
            if (rIdx == 1) roleCode = UserRole.LECTURER;
            else if (rIdx == 2) roleCode = UserRole.ADMIN;
            newUser.setRole(roleCode);

            newUser.setClassName(cField.getText().trim());
            newUser.setStatus("ACTIVE");

            client.createUser(newUser)
                    .thenAccept(created -> SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(dialog, "Tạo người dùng thành công: " + created.getUsername(), "Thành công", JOptionPane.INFORMATION_MESSAGE);
                        dialog.dispose();
                        loadUsers();
                    }))
                    .exceptionally(ex -> {
                        SwingUtilities.invokeLater(() -> {
                            String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                            JOptionPane.showMessageDialog(dialog, "Lỗi tạo người dùng: " + errMsg, "Lỗi", JOptionPane.ERROR_MESSAGE);
                        });
                        return null;
                    });
        });

        JPanel wrap = new JPanel(new BorderLayout(0, 10));
        wrap.setBackground(Color.WHITE);
        wrap.add(form, BorderLayout.CENTER);
        JPanel btm = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btm.setBackground(Color.WHITE);
        btm.add(saveBtn);
        wrap.add(btm, BorderLayout.SOUTH);

        dialog.setContentPane(wrap);
        dialog.setVisible(true);
    }

    private void showEditUserDialog() {
        int row = userTable.getSelectedRow();
        if (row < 0 || row >= currentUsers.size()) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một người dùng để sửa!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        UserDto targetUser = currentUsers.get(row);

        JDialog dialog = new JDialog(this, "Sửa Người Dùng - " + targetUser.getUsername(), true);
        dialog.setSize(440, 320);
        dialog.setLocationRelativeTo(this);

        JPanel form = new JPanel(new GridLayout(4, 2, 8, 10));
        form.setBorder(new EmptyBorder(16, 20, 16, 20));
        form.setBackground(Color.WHITE);

        JTextField fField = new JTextField(targetUser.getFullName());
        JTextField eField = new JTextField(targetUser.getEmail() != null ? targetUser.getEmail() : "");
        JComboBox<String> rCombo = new JComboBox<>(new String[]{"Sinh viên", "Giảng viên", "Quản trị viên"});
        if (targetUser.getRole() == UserRole.LECTURER) rCombo.setSelectedIndex(1);
        else if (targetUser.getRole() == UserRole.ADMIN) rCombo.setSelectedIndex(2);
        else rCombo.setSelectedIndex(0);

        JTextField cField = new JTextField(targetUser.getClassName() != null ? targetUser.getClassName() : "");

        form.add(new JLabel("Họ và tên:")); form.add(fField);
        form.add(new JLabel("Email:")); form.add(eField);
        form.add(new JLabel("Vai trò:")); form.add(rCombo);
        form.add(new JLabel("Lớp:")); form.add(cField);

        JButton saveBtn = UITheme.createPrimaryButton("Cập Nhật");
        saveBtn.addActionListener(e -> {
            targetUser.setFullName(fField.getText().trim());
            targetUser.setEmail(eField.getText().trim());

            int rIdx = rCombo.getSelectedIndex();
            UserRole roleCode = UserRole.STUDENT;
            if (rIdx == 1) roleCode = UserRole.LECTURER;
            else if (rIdx == 2) roleCode = UserRole.ADMIN;
            targetUser.setRole(roleCode);

            targetUser.setClassName(cField.getText().trim());

            client.updateUser(targetUser)
                    .thenAccept(updated -> SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(dialog, "Cập nhật người dùng thành công!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
                        dialog.dispose();
                        loadUsers();
                    }))
                    .exceptionally(ex -> {
                        SwingUtilities.invokeLater(() -> {
                            String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                            JOptionPane.showMessageDialog(dialog, "Lỗi cập nhật: " + errMsg, "Lỗi", JOptionPane.ERROR_MESSAGE);
                        });
                        return null;
                    });
        });

        JPanel wrap = new JPanel(new BorderLayout(0, 10));
        wrap.setBackground(Color.WHITE);
        wrap.add(form, BorderLayout.CENTER);
        JPanel btm = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btm.setBackground(Color.WHITE);
        btm.add(saveBtn);
        wrap.add(btm, BorderLayout.SOUTH);

        dialog.setContentPane(wrap);
        dialog.setVisible(true);
    }

    private void showResetPasswordDialog() {
        int row = userTable.getSelectedRow();
        if (row < 0 || row >= currentUsers.size()) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn người dùng cần đặt lại mật khẩu!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        UserDto targetUser = currentUsers.get(row);
        String newPass = JOptionPane.showInputDialog(this,
                "Nhập mật khẩu mới cho tài khoản '" + targetUser.getUsername() + "':",
                "123456");

        if (newPass != null && !newPass.trim().isEmpty()) {
            client.resetUserPassword(targetUser.getId(), newPass.trim())
                    .thenAccept(v -> SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                "Đặt lại mật khẩu thành công cho '" + targetUser.getUsername() + "'!",
                                "Thành công", JOptionPane.INFORMATION_MESSAGE);
                        loadUsers();
                    }))
                    .exceptionally(ex -> {
                        SwingUtilities.invokeLater(() -> {
                            String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                            JOptionPane.showMessageDialog(this, "Lỗi đặt lại mật khẩu: " + errMsg, "Lỗi", JOptionPane.ERROR_MESSAGE);
                        });
                        return null;
                    });
        }
    }

    private void toggleLockUser() {
        int row = userTable.getSelectedRow();
        if (row < 0 || row >= currentUsers.size()) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn người dùng cần khóa/mở khóa!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        UserDto targetUser = currentUsers.get(row);
        boolean isLocked = "LOCKED".equalsIgnoreCase(targetUser.getStatus());
        boolean targetLockState = !isLocked;

        String actionText = targetLockState ? "KHÓA" : "MỞ KHÓA";
        int confirm = JOptionPane.showConfirmDialog(this,
                "Bạn có chắc chắn muốn " + actionText + " tài khoản: " + targetUser.getUsername() + "?",
                "Xác nhận thay đổi trạng thái", JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            client.setUserLocked(targetUser.getId(), targetLockState)
                    .thenAccept(v -> SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this, "Thao tác thành công!", "Thành công", JOptionPane.INFORMATION_MESSAGE);
                        loadUsers();
                    }))
                    .exceptionally(ex -> {
                        SwingUtilities.invokeLater(() -> {
                            String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                            JOptionPane.showMessageDialog(this, "Lỗi thực hiện: " + errMsg, "Lỗi", JOptionPane.ERROR_MESSAGE);
                        });
                        return null;
                    });
        }
    }

    private void importUsersFromCsv() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Chọn file danh sách tài khoản CSV");
        int res = fileChooser.showOpenDialog(this);
        if (res != JFileChooser.APPROVE_OPTION) return;

        File csvFile = fileChooser.getSelectedFile();
        List<UserDto> usersToImport = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(csvFile), StandardCharsets.UTF_8))) {
            String line;
            boolean firstLine = true;
            while ((line = br.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    continue; // Skip header
                }
                String[] parts = line.split(",");
                if (parts.length >= 3) {
                    UserDto u = new UserDto();
                    u.setUsername(parts[0].trim());
                    u.setFullName(parts[1].trim());
                    u.setPassword(parts[2].trim());
                    if (parts.length >= 4) u.setEmail(parts[3].trim());
                    if (parts.length >= 5) {
                        try {
                            u.setRole(UserRole.valueOf(parts[4].trim().toUpperCase()));
                        } catch (Exception ex) {
                            u.setRole(UserRole.STUDENT);
                        }
                    } else {
                        u.setRole(UserRole.STUDENT);
                    }
                    if (parts.length >= 6) u.setClassName(parts[5].trim());
                    usersToImport.add(u);
                }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Lỗi đọc file CSV: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (usersToImport.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Không tìm thấy dữ liệu người dùng nào trong file!", "Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }

        client.importUsersBatch(usersToImport)
                .thenAccept(result -> SwingUtilities.invokeLater(() -> {
                    loadUsers();
                    JOptionPane.showMessageDialog(this,
                            String.format("Nhập thành công %d / %d tài khoản!", result.getImportedCount(), result.getTotalRows()),
                            "Nhập CSV hoàn tất", JOptionPane.INFORMATION_MESSAGE);
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        String errMsg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        JOptionPane.showMessageDialog(this, "Lỗi nhập file CSV: " + errMsg, "Lỗi", JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });
    }

    // =========================================================================
    // TAB 3: NHẬT KÝ & KIỂM TOÁN HOẠT ĐỘNG
    // =========================================================================

    private JPanel createLogsTab() {
        JPanel panel = UITheme.createCardPanel();
        panel.setLayout(new BorderLayout(0, 12));

        // Filter Bar
        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        filterBar.setBackground(Color.WHITE);

        filterBar.add(new JLabel("Loại nhật ký:"));
        logTypeCombo = new JComboBox<>(new String[]{"Tất cả", "Điều phối tải", "Đồng bộ dữ liệu", "Kiểm toán hệ thống"});
        logTypeCombo.addActionListener(e -> loadLogs());
        filterBar.add(logTypeCombo);

        filterBar.add(new JLabel("Từ khóa:"));
        logKeywordField = new JTextField(16);
        logKeywordField.addActionListener(e -> loadLogs());
        filterBar.add(logKeywordField);

        JButton queryBtn = UITheme.createPrimaryButton("Truy Vấn");
        queryBtn.addActionListener(e -> loadLogs());
        filterBar.add(queryBtn);

        panel.add(filterBar, BorderLayout.NORTH);

        // Bảng logs
        String[] cols = {"Thời gian", "Loại", "Mã yêu cầu", "Tài khoản", "Hành động / Sự kiện", "Máy chủ đích", "Trạng thái", "Chi tiết"};
        logTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };

        logTable = new JTable(logTableModel);
        logTable.setRowHeight(28);
        logTable.setFont(UITheme.FONT_BODY);
        logTable.getTableHeader().setFont(UITheme.FONT_BOLD);

        panel.add(new JScrollPane(logTable), BorderLayout.CENTER);
        return panel;
    }

    private void loadLogs() {
        int logIdx = logTypeCombo.getSelectedIndex();
        String logType = null;
        if (logIdx == 1) logType = "DISTRIBUTION";
        else if (logIdx == 2) logType = "SYNC";
        else if (logIdx == 3) logType = "AUDIT";

        String kw = logKeywordField.getText().trim();
        if (kw.isEmpty()) kw = null;

        client.queryLogs(logType, kw, null, null, 1, 100)
                .thenAccept(resp -> SwingUtilities.invokeLater(() -> {
                    logTableModel.setRowCount(0);
                    if (resp.getEntries() != null) {
                        for (LogEntryDto l : resp.getEntries()) {
                            String timeStr = l.getTimestamp() > 0 ? TIME_FORMAT.format(new Date(l.getTimestamp())) : "-";
                            logTableModel.addRow(new Object[]{
                                    timeStr,
                                    l.getLogType(),
                                    l.getId(),
                                    l.getUserId() != null ? l.getUserId() : "-",
                                    l.getAction() != null ? l.getAction() : "-",
                                    l.getTargetServer() != null ? l.getTargetServer() : "-",
                                    l.getStatus() != null ? l.getStatus() : "-",
                                    l.getDetails() != null ? l.getDetails() : "-"
                            });
                        }
                    }
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> log.warn("Lỗi truy vấn nhật ký: {}", ex.getMessage()));
                    return null;
                });
    }

    private JPanel createMiniCard(String title, JLabel valueLabel, Color accentColor) {
        JPanel card = new JPanel(new BorderLayout(0, 4));
        card.setBackground(new Color(250, 250, 250));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(2, 0, 0, 0, accentColor),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(UITheme.BORDER_COLOR, 1),
                        new EmptyBorder(10, 12, 10, 12)
                )
        ));

        JLabel tLbl = new JLabel(title, SwingConstants.CENTER);
        tLbl.setFont(UITheme.FONT_SMALL);
        tLbl.setForeground(UITheme.TEXT_SECONDARY);

        valueLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
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
