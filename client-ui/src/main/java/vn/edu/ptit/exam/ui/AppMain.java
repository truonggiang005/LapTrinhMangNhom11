package vn.edu.ptit.exam.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.edu.ptit.exam.client.ExamClient;
import vn.edu.ptit.exam.ui.util.UITheme;
import vn.edu.ptit.exam.ui.view.LoginFrame;

import javax.swing.*;

/**
 * Main desktop application entry point for the Multi-Server Online Exam System.
 * Initializes FlatLaf Look and Feel and launches LoginFrame on Swing Event Dispatch Thread.
 */
public class AppMain {

    private static final Logger log = LoggerFactory.getLogger(AppMain.class);

    public static void main(String[] args) {
        log.info("Starting Multi-Server Online Exam Desktop Client...");

        // Setup Modern FlatLaf Look and Feel
        UITheme.setupFlatLaf();

        // Launch UI on Event Dispatch Thread (EDT)
        SwingUtilities.invokeLater(() -> {
            try {
                ExamClient client = new ExamClient();
                LoginFrame loginFrame = new LoginFrame(client);
                loginFrame.setVisible(true);
                log.info("LoginFrame opened successfully.");
            } catch (Exception e) {
                log.error("Fatal error starting client UI: {}", e.getMessage(), e);
                JOptionPane.showMessageDialog(null,
                        "Không thể khởi động ứng dụng: " + e.getMessage(),
                        "Lỗi Khởi Động", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
