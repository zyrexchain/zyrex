package org.zyrexchain.desktop;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.border.Border;

/** Shared, dependency-free desktop styling. */
final class Ui {
    static final Color BACKGROUND = new Color(0xf2f5f7);
    static final Color SURFACE = Color.WHITE;
    static final Color TEXT = new Color(0x182936);
    static final Color MUTED = new Color(0x667887);
    static final Color ACCENT = new Color(0x157e58);
    static final Color MINT = new Color(0x71f5be);
    static final Color DARK = new Color(0x111923);
    static final Color LINE = new Color(0xdde5e9);
    static final Color WARNING = new Color(0x996519);
    static final Font BODY = new Font(Font.SANS_SERIF, Font.PLAIN, 14);

    private Ui() { }

    static void install() {
        UIManager.put("defaultFont", BODY);
        for (String type : new String[] {"Label", "Button", "TextField", "PasswordField", "TextArea", "ComboBox",
                "Table", "TableHeader", "TabbedPane", "CheckBox", "OptionPane", "ProgressBar"}) {
            UIManager.put(type + ".font", BODY);
        }
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("TextField.background", SURFACE);
        UIManager.put("TextArea.background", SURFACE);
        UIManager.put("PasswordField.background", SURFACE);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("PasswordField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", TEXT);
        UIManager.put("TextField.selectionBackground", new Color(0xc7efdf));
        UIManager.put("PasswordField.selectionBackground", new Color(0xc7efdf));
        UIManager.put("TabbedPane.selected", SURFACE);
        UIManager.put("TabbedPane.contentAreaColor", SURFACE);
        UIManager.put("TabbedPane.tabInsets", insets(10, 18, 10, 18));
        UIManager.put("TabbedPane.focus", ACCENT);
        UIManager.put("TabbedPane.darkShadow", LINE);
        UIManager.put("TabbedPane.shadow", LINE);
        UIManager.put("TabbedPane.highlight", SURFACE);
        UIManager.put("TabbedPane.borderHightlightColor", LINE);
        UIManager.put("Table.gridColor", LINE);
        UIManager.put("Table.selectionBackground", new Color(0xe4f5ee));
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("ProgressBar.foreground", ACCENT);
    }

    static JLabel title(String text) {
        JLabel label = new JLabel(text);
        label.setFont(BODY.deriveFont(Font.BOLD, 26));
        label.setForeground(TEXT);
        return label;
    }

    static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        return label;
    }

    static JTextArea paragraph(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setOpaque(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(BODY);
        area.setForeground(MUTED);
        area.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        return area;
    }

    static JPanel card() {
        JPanel panel = new JPanel();
        panel.setBackground(SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),
            BorderFactory.createEmptyBorder(24, 26, 24, 26)));
        return panel;
    }

    static JButton button(String text, String name, boolean primary) {
        JButton button = new JButton(text);
        button.setName(name);
        button.getAccessibleContext().setAccessibleName(text);
        button.setFont(BODY.deriveFont(Font.BOLD));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBackground(primary ? ACCENT : SURFACE);
        button.setForeground(primary ? Color.WHITE : TEXT);
        button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(primary ? ACCENT : LINE),
            BorderFactory.createEmptyBorder(11, 18, 11, 18)));
        return button;
    }

    static Border inputBorder() {
        return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE),
            BorderFactory.createEmptyBorder(10, 12, 10, 12));
    }

    static JPasswordField passwordField(String name, int columns) {
        JPasswordField field = new JPasswordField(columns);
        field.setName(name);
        field.setFont(BODY);
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
        field.setBackground(SURFACE);
        field.setEchoChar(BODY.canDisplay('\u2022') ? '\u2022' : '*');
        field.setBorder(inputBorder());
        Insets padding = field.getInsets();
        Dimension preferred = field.getPreferredSize();
        int height = field.getFontMetrics(field.getFont()).getHeight() + padding.top + padding.bottom + 4;
        // Keep the echo characters and caret visible inside our padded border.
        preferred.height = Math.max(preferred.height, height);
        field.setPreferredSize(preferred);
        field.setMinimumSize(new Dimension(64, preferred.height));
        return field;
    }

    static void size(Component component, int width, int height) {
        component.setPreferredSize(new Dimension(width, height));
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    }

    static Insets insets(int top, int left, int bottom, int right) {
        return new Insets(top, left, bottom, right);
    }
}
