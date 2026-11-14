package vn.edu.ptit.exam.ui.view;

import com.formdev.flatlaf.FlatClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.client.ExamClient;
import vn.edu.ptit.exam.common.dto.AuthToken;
import vn.edu.ptit.exam.ui.util.UITheme;
import vn.edu.ptit.exam.ui.view.admin.AdminMainFrame;
import vn.edu.ptit.exam.ui.view.lecturer.LecturerMainFrame;
import vn.edu.ptit.exam.ui.view.student.StudentMainFrame;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Giao diện Đăng nhập kết nối tới Bộ điều phối máy chủ (Cổng 9000).
 * Tự động chuyển đến màn hình Sinh viên, Giảng viên hoặc Quản trị viên sau khi xác thực thành công.
 */
public class LoginFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(LoginFrame.class);

    private final ExamClient client;

    private JTextField hostField;
    private JTextField portField;
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JLabel statusLabel;
    private JButton loginButton;
    private JProgressBar progressBar;

    public LoginFrame(ExamClient client) {
        this.client = client;
        initUI();
    }

    private void initUI() {
        setTitle("Hệ Thống Thi Trắc Nghiệm Trực Tuyến Đa Máy Chủ");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(480, 570);
        setLocationRelativeTo(null);
        setResizable(false);
        getContentPane().setBackground(UITheme.BG_COLOR);

        JPanel mainPanel = new JPanel(new BorderLayout(0, 16));
        mainPanel.setBackground(UITheme.BG_COLOR);
        mainPanel.setBorder(new EmptyBorder(28, 36, 28, 36));

        // Header Panel
        JPanel headerPanel = new JPanel(new GridLayout(2, 1, 0, 4));
        headerPanel.setBackground(UITheme.BG_COLOR);

        JLabel titleLabel = new JLabel("ĐĂNG NHẬP HỆ THỐNG", SwingConstants.CENTER);
        titleLabel.setFont(UITheme.FONT_TITLE);
        titleLabel.setForeground(UITheme.PRIMARY_COLOR);

        JLabel subTitleLabel = new JLabel("Hệ Thống Phân Tán Đa Máy Chủ - Nhóm 11", SwingConstants.CENTER);
        subTitleLabel.setFont(UITheme.FONT_BODY);
        subTitleLabel.setForeground(UITheme.TEXT_SECONDARY);

        headerPanel.add(titleLabel);
        headerPanel.add(subTitleLabel);
        mainPanel.add(headerPanel, BorderLayout.NORTH);

        // Center Form Card
        JPanel card = UITheme.createCardPanel();
        card.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 4, 6, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        int row = 0;

        // Server Host & Port
        gbc.gridx = 0; gbc.gridy = row++;
        JLabel serverLabel = new JLabel("Địa chỉ máy chủ & Cổng:");
        serverLabel.setFont(UITheme.FONT_BOLD);
        card.add(serverLabel, gbc);

        JPanel serverPanel = new JPanel(new BorderLayout(8, 0));
        serverPanel.setBackground(Color.WHITE);
        hostField = new JTextField("localhost");
        hostField.setFont(UITheme.FONT_BODY);
        hostField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Địa chỉ máy chủ");

        portField = new JTextField("9000", 5);
        portField.setFont(UITheme.FONT_BODY);
        portField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Cổng");

        serverPanel.add(hostField, BorderLayout.CENTER);
        serverPanel.add(portField, BorderLayout.EAST);

        gbc.gridy = row++;
        card.add(serverPanel, gbc);

        // Username
        gbc.gridy = row++;
        JLabel userLabel = new JLabel("Tên đăng nhập / Mã tài khoản:");
        userLabel.setFont(UITheme.FONT_BOLD);
        card.add(userLabel, gbc);

        gbc.gridy = row++;
        usernameField = new JTextField("sv_01");
        usernameField.setFont(UITheme.FONT_BODY);
        usernameField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Nhập tên đăng nhập...");
        card.add(usernameField, gbc);

        // Password
        gbc.gridy = row++;
        JLabel passLabel = new JLabel("Mật khẩu:");
        passLabel.setFont(UITheme.FONT_BOLD);
        card.add(passLabel, gbc);

        gbc.gridy = row++;
        passwordField = new JPasswordField("123456");
        passwordField.setFont(UITheme.FONT_BODY);
        passwordField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Nhập mật khẩu...");
        card.add(passwordField, gbc);

        // Quick Role Hints
        gbc.gridy = row++;
        JLabel hintLabel = new JLabel("Gợi ý: sv_01 / gv_01 / admin (mật khẩu: 123456)");
        hintLabel.setFont(UITheme.FONT_SMALL);
        hintLabel.setForeground(UITheme.TEXT_SECONDARY);
        card.add(hintLabel, gbc);

        // Status Label & Progress Bar
        gbc.gridy = row++;
        statusLabel = new JLabel("Sẵn sàng kết nối tới hệ thống máy chủ", SwingConstants.CENTER);
        statusLabel.setFont(UITheme.FONT_SMALL);
        statusLabel.setForeground(UITheme.TEXT_SECONDARY);
        card.add(statusLabel, gbc);

        gbc.gridy = row++;
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        card.add(progressBar, gbc);

        // Login Button
        gbc.gridy = row++;
        gbc.insets = new Insets(12, 4, 4, 4);
        loginButton = UITheme.createPrimaryButton("ĐĂNG NHẬP");
        loginButton.setPreferredSize(new Dimension(0, 40));
        loginButton.addActionListener(e -> performLogin());
        card.add(loginButton, gbc);

        // Enter key shortcut
        getRootPane().setDefaultButton(loginButton);

        mainPanel.add(card, BorderLayout.CENTER);

        // Footer note
        JLabel footerLabel = new JLabel("Mô hình phân tán 4 máy chủ - Cổng điều phối 9000 - Nhóm 11", SwingConstants.CENTER);
        footerLabel.setFont(UITheme.FONT_SMALL);
        footerLabel.setForeground(UITheme.TEXT_SECONDARY);
        mainPanel.add(footerLabel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    private void performLogin() {
        String host = hostField.getText().trim();
        String portStr = portField.getText().trim();
        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword()).trim();

        if (host.isEmpty() || portStr.isEmpty() || username.isEmpty() || password.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Vui lòng nhập đầy đủ địa chỉ máy chủ, cổng, tên đăng nhập và mật khẩu!",
                    "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Cổng máy chủ phải là số nguyên dương hợp lệ!", "Lỗi cổng", JOptionPane.ERROR_MESSAGE);
            return;
        }

        setLoading(true, "Đang kết nối tới máy chủ " + host + ":" + port + "...");

        client.connect(host, port)
                .thenCompose(v -> {
                    SwingUtilities.invokeLater(() -> statusLabel.setText("Đang xác thực thông tin đăng nhập..."));
                    return client.login(username, password);
                })
                .thenAccept(tokenStr -> SwingUtilities.invokeLater(() -> {
                    setLoading(false, "Đăng nhập thành công!");
                    dispatchUserView();
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        setLoading(false, "Đăng nhập thất bại");
                        String err = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        log.error("Lỗi đăng nhập: {}", err, ex);

                        if (err.contains("Connection refused") || err.contains("connect")) {
                            statusLabel.setText("Không thể kết nối tới máy chủ. Vui lòng kiểm tra lại địa chỉ và cổng!");
                            JOptionPane.showMessageDialog(this,
                                    "Không thể kết nối tới máy chủ tại " + host + ":" + port + "!\n" +
                                    "Vui lòng kiểm tra xem máy chủ hoặc bộ điều phối đã được khởi động chưa.",
                                    "Lỗi kết nối", JOptionPane.ERROR_MESSAGE);
                        } else {
                            statusLabel.setText("Đăng nhập thất bại: " + err);
                            JOptionPane.showMessageDialog(this,
                                    "Đăng nhập thất bại: " + err,
                                    "Lỗi đăng nhập", JOptionPane.ERROR_MESSAGE);
                        }
                    });
                    return null;
                });
    }

    private void dispatchUserView() {
        String role = client.getCurrentRole();
        if (role == null || role.isBlank()) {
            if (client.getCurrentToken() != null && client.getCurrentToken().getRole() != null) {
                role = client.getCurrentToken().getRole().name();
            }
        }

        if (role == null || role.isBlank()) {
            JOptionPane.showMessageDialog(this, "Không tìm thấy phiên làm việc sau đăng nhập!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            return;
        }

        this.setVisible(false);

        switch (role.toUpperCase()) {
            case "STUDENT":
                StudentMainFrame studentFrame = new StudentMainFrame(client, this);
                studentFrame.setVisible(true);
                break;
            case "LECTURER":
                LecturerMainFrame lecturerFrame = new LecturerMainFrame(client, this);
                lecturerFrame.setVisible(true);
                break;
            case "ADMIN":
                AdminMainFrame adminFrame = new AdminMainFrame(client, this);
                adminFrame.setVisible(true);
                break;
            default:
                JOptionPane.showMessageDialog(this, "Vai trò không xác định: " + role, "Lỗi", JOptionPane.ERROR_MESSAGE);
                this.setVisible(true);
                break;
        }
    }

    private void setLoading(boolean loading, String message) {
        progressBar.setVisible(loading);
        loginButton.setEnabled(!loading);
        hostField.setEnabled(!loading);
        portField.setEnabled(!loading);
        usernameField.setEnabled(!loading);
        passwordField.setEnabled(!loading);
        statusLabel.setText(message);
        statusLabel.setForeground(loading ? UITheme.PRIMARY_COLOR : UITheme.TEXT_SECONDARY);
    }

    public void resetLoginForm() {
        passwordField.setText("");
        statusLabel.setText("Sẵn sàng kết nối tới hệ thống máy chủ");
        statusLabel.setForeground(UITheme.TEXT_SECONDARY);
        progressBar.setVisible(false);
        loginButton.setEnabled(true);
        hostField.setEnabled(true);
        portField.setEnabled(true);
        usernameField.setEnabled(true);
        passwordField.setEnabled(true);
        setVisible(true);
    }
}
