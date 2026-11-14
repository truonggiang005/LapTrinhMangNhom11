package vn.edu.ptit.exam.ui.util;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Modern UI Theme constants and styling helpers using FlatLaf.
 */
public class UITheme {

    // Primary Colors
    public static final Color PRIMARY_COLOR = new Color(24, 144, 255);       // Modern Blue
    public static final Color PRIMARY_HOVER = new Color(64, 169, 255);
    public static final Color SUCCESS_COLOR = new Color(82, 196, 26);        // Green
    public static final Color WARNING_COLOR = new Color(250, 173, 20);       // Amber / Orange
    public static final Color DANGER_COLOR = new Color(245, 34, 45);         // Red
    public static final Color BG_COLOR = new Color(245, 247, 250);           // Soft Light Gray
    public static final Color CARD_BG = Color.WHITE;
    public static final Color TEXT_PRIMARY = new Color(38, 38, 38);
    public static final Color TEXT_SECONDARY = new Color(140, 140, 140);
    public static final Color BORDER_COLOR = new Color(220, 224, 230);

    // Fonts
    public static final Font FONT_TITLE = new Font("Segoe UI", Font.BOLD, 18);
    public static final Font FONT_HEADER = new Font("Segoe UI", Font.BOLD, 14);
    public static final Font FONT_BODY = new Font("Segoe UI", Font.PLAIN, 13);
    public static final Font FONT_BOLD = new Font("Segoe UI", Font.BOLD, 13);
    public static final Font FONT_SMALL = new Font("Segoe UI", Font.PLAIN, 11);
    public static final Font FONT_TIMER = new Font("Segoe UI", Font.BOLD, 22);

    public static void setupFlatLaf() {
        try {
            FlatLightLaf.setup();
            UIManager.put("Button.arc", 8);
            UIManager.put("Component.arc", 8);
            UIManager.put("ProgressBar.arc", 8);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("ScrollBar.thumbArc", 8);
            UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        } catch (Exception e) {
            System.err.println("Failed to initialize FlatLaf: " + e.getMessage());
        }
    }

    public static JPanel createCardPanel() {
        JPanel card = new JPanel();
        card.setBackground(CARD_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(16, 16, 16, 16)
        ));
        return card;
    }

    public static JButton createPrimaryButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_BOLD);
        btn.setBackground(PRIMARY_COLOR);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        return btn;
    }

    public static JButton createSuccessButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_BOLD);
        btn.setBackground(SUCCESS_COLOR);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        return btn;
    }

    public static JButton createDangerButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_BOLD);
        btn.setBackground(DANGER_COLOR);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        return btn;
    }

    public static JButton createSecondaryButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(FONT_BODY);
        btn.setBackground(Color.WHITE);
        btn.setForeground(TEXT_PRIMARY);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        return btn;
    }
}
