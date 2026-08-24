package io.github.fyfybai.bilirecord;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

final class UiTheme {
    private static final String UI_FONT_FAMILY = findUiFont();
    static final Color BACKGROUND = new Color(0xF6F7F8);
    static final Color SURFACE = Color.WHITE;
    static final Color TEXT = new Color(0x18191C);
    static final Color MUTED = new Color(0x61666D);
    static final Color BORDER = new Color(0xE3E5E7);
    static final Color PINK = new Color(0xFB7299);
    static final Color PINK_HOVER = new Color(0xFC8BAB);
    static final Color BLUE = new Color(0x00AEEC);
    static final Color GREEN = new Color(0x2AC864);
    static final Color WARNING = new Color(0xF69C0B);
    static final Color DANGER = new Color(0xF85A54);

    private UiTheme() {
    }

    static void install() {
        FlatLightLaf.setup();
        Font font = new Font(UI_FONT_FAMILY, Font.PLAIN, 14);
        UIManager.put("defaultFont", font);
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("Component.focusColor", new Color(0x66FB7299, true));
        UIManager.put("Component.borderColor", BORDER);
        UIManager.put("ScrollBar.thumbArc", 8);
        UIManager.put("ScrollBar.width", 10);
    }

    static void accent(JButton button) {
        button.setBackground(PINK);
        button.setForeground(Color.WHITE);
        button.putClientProperty("FlatLaf.style",
                "background: #FB7299; hoverBackground: #FC8BAB; pressedBackground: #E85C86;"
                        + "focusedBackground: #FB7299; borderWidth: 0; focusWidth: 0; arc: 8");
    }

    static void outline(JButton button) {
        button.setBackground(SURFACE);
        button.setForeground(TEXT);
        button.putClientProperty("FlatLaf.style",
                "background: #FFFFFF; hoverBackground: #F1F2F3; borderColor: #E3E5E7;"
                        + "focusWidth: 0; arc: 8");
    }

    static void destructive(JButton button) {
        button.setBackground(SURFACE);
        button.setForeground(DANGER);
        button.putClientProperty("FlatLaf.style",
                "background: #FFFFFF; hoverBackground: #FFF1F0; borderColor: #F85A54;"
                        + "foreground: #F85A54; focusWidth: 0; arc: 8");
    }

    static void placeholder(JComponent component, String text) {
        component.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, text);
    }

    static Font uiFont(int style, float size) {
        return new Font(UI_FONT_FAMILY, style, Math.round(size)).deriveFont(size);
    }

    static ImageIcon brandIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(PINK);
        graphics.fillRoundRect(0, 0, size, size, size / 4, size / 4);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(size * 0.55f)));
        String text = "B";
        var metrics = graphics.getFontMetrics();
        graphics.drawString(text,
                (size - metrics.stringWidth(text)) / 2,
                (size - metrics.getHeight()) / 2 + metrics.getAscent());
        graphics.dispose();
        return new ImageIcon(image);
    }

    private static String findUiFont() {
        var installed = java.util.Set.of(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String candidate : new String[]{
                "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC", "SimSun"}) {
            if (installed.contains(candidate)) {
                return candidate;
            }
        }
        return Font.DIALOG;
    }
}
