package com.bdic.admin;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;

/**
 * Creates and styles common Swing components consistently to reduce repeated controller code.
 */
public final class UiComponentFactory {

    /** Utility class; instantiation is not needed. */
    private UiComponentFactory() {
    }

    /**
     * Creates a unified section panel with a title, description, and content area.
     */
    public static JPanel createSectionPanel(String title, String description, Component content) {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(createSectionBorder());

        // The title uses a bold font, while the description uses gray HTML text to reduce visual weight.
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 15f));
        JLabel descriptionLabel = new JLabel("<html><span style='color:#6b7280;'>" + description + "</span></html>");

        JPanel header = new JPanel(new BorderLayout(4, 4));
        header.add(titleLabel, BorderLayout.NORTH);
        header.add(descriptionLabel, BorderLayout.CENTER);

        panel.add(header, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    /** Applies primary action styling to a button. */
    public static void stylePrimaryButton(JButton button) {
        button.setBackground(new Color(37, 99, 235));
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
    }

    /** Applies secondary action styling to a button. */
    public static void styleSecondaryButton(JButton button) {
        button.setBackground(new Color(243, 244, 246));
        button.setForeground(new Color(31, 41, 55));
        button.setFocusPainted(false);
    }

    /** Applies danger action styling to a button, such as delete or exit. */
    public static void styleDangerButton(JButton button) {
        button.setBackground(new Color(220, 38, 38));
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
    }

    /**
     * Creates a unified section border with a light gray outer line and inner padding.
     */
    private static Border createSectionBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 224, 230)),
                BorderFactory.createEmptyBorder(12, 14, 12, 14)
        );
    }
}
