import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;

/**
 * ===== THEME MANAGER =====
 * Detects system dark/light mode preference and applies a consistent color
 * palette across all Swing components.
 *
 * Detection order:
 *   1. macOS  - NSRequiresAquaSystemAppearance / apple.awt.application.appearance
 *   2. Windows - registry query HKCU\...\AppsUseLightTheme (0 = dark, 1 = light)
 *   3. Linux/other - GTK theme name heuristic
 *   4. Fallback - DEFAULT DARK (fail secure / fail stylish)
 *
 * If any detection step throws or returns ambiguous results, debug output is
 * sent to stderr and we fall through to the next method.  Final fallback is
 * always dark so the vault never opens in a blinding white UI unexpectedly.
 */
public class ThemeManager {

    // ===== PALETTE - DARK =====
    // Cold void, iron stone, northern lights - Yggdrasil at midnight
    public static final Color DARK_BG          = new Color(0x0A0E17); // void before creation - main window bg
    public static final Color DARK_SURFACE     = new Color(0x111827); // cold stone hall - sidebar, panels
    public static final Color DARK_SURFACE2    = new Color(0x1A2235); // aged timber - inputs, table rows
    public static final Color DARK_BORDER      = new Color(0x2A3347); // iron veins in stone - dividers
    public static final Color DARK_TEXT        = new Color(0xE8EEF4); // frost breath - primary text
    public static final Color DARK_TEXT_MUTED  = new Color(0x7A8899); // fog over the fjord - secondary text
    public static final Color DARK_ACCENT      = new Color(0x5ECFB0); // aurora teal - the light Odin follows
    public static final Color DARK_ACCENT_HOV  = new Color(0x3DB896); // deeper aurora on hover
    public static final Color DARK_ACCENT_BTN     = new Color(0x2E9478); // aurora teal - muted, seen from distance
    public static final Color DARK_ACCENT_BTN_HOV = new Color(0x1F7D63); // deeper aurora on hover
    public static final Color DARK_DANGER      = new Color(0xC94F3A); // forge fire - Ragnarok red
    public static final Color DARK_SUCCESS     = new Color(0x3A9E6A); // Yggdrasil leaf - living green
    public static final Color DARK_WARNING     = new Color(0xC4882A); // torchlight amber - mead hall glow
    public static final Color DARK_SELECT      = new Color(0x1F3A5F); // deep fjord water - row selection
    public static final Color DARK_LEFT_LOGIN  = new Color(0x1E5F8E); // deep cold water

    // ===== PALETTE - LIGHT =====
    // Aged stone, weathered birch, overcast Nordic sky - Asgard at dawn
    public static final Color LIGHT_BG         = new Color(0xDDE4EC); // slate grey-blue - overcast Nordic sky
    public static final Color LIGHT_SURFACE    = new Color(0xE8EEF4); // birch bark - panels, sidebar
    public static final Color LIGHT_SURFACE2   = new Color(0xD4DCE6); // wet stone - inputs, table rows
    public static final Color LIGHT_BORDER     = new Color(0xB0BDCC); // iron slate - dividers
    public static final Color LIGHT_TEXT       = new Color(0x1A2130); // raven black - deep night ink
    public static final Color LIGHT_TEXT_MUTED = new Color(0x5A6878); // storm grey - secondary text
    public static final Color LIGHT_ACCENT     = new Color(0x1B6CA8); // fjord deep blue - Odin's cloak
    public static final Color LIGHT_ACCENT_HOV = new Color(0x14538A); // midnight water - hover state
    public static final Color LIGHT_ACCENT_BTN     = new Color(0x1B6CA8); // fjord deep blue - Odin's cloak
    public static final Color LIGHT_ACCENT_BTN_HOV = new Color(0x14538A); // midnight water - hover state
    public static final Color LIGHT_DANGER     = new Color(0xB33A2A); // blood on the battlefield
    public static final Color LIGHT_SUCCESS    = new Color(0x246E45); // forest of Yggdrasil
    public static final Color LIGHT_WARNING    = new Color(0x9A6200); // burnished gold - Odin's ravens
    public static final Color LIGHT_SELECT     = new Color(0xD6E8F5); // glacial melt - row selection
    public static final Color LIGHT_LEFT_LOGIN = new Color(0xE8EEF4); // glacial blue - iceberg cold
//0x2A6B9C
    // ===== ACTIVE PALETTE (set after detection) =====
    public static Color BG, SURFACE, SURFACE2, BORDER, TEXT, TEXT_MUTED,
                        ACCENT, ACCENT_HOV, ACCENT_BTN, ACCENT_BTN_HOV, DANGER, SUCCESS, WARNING, SELECT, LEFT_LOGIN;

    // Whether the active theme is dark
    public static boolean isDark = true;

    // ===== DETECT AND APPLY =====
    /**
     * Call once at startup before any UI is built.
     * Populates all static color fields and applies UIManager defaults.
     *
     * @param debug  if true, extra detection trace is printed to stderr
     */
    public static void detect(boolean debug, String theme_override) {
        boolean detected = false;
        String os = System.getProperty("os.name", "").toLowerCase();

        if (theme_override.equals("dark")){
            detected = true;
            isDark = true;
            System.out.println("[ThemeManager] Theme override called for Dark");
        } else if (theme_override.equals("light")){
            detected = true;
            isDark = false;
            System.out.println("[ThemeManager] Theme override called for Light");
        }

        // --- 1. macOS detection ---
        if (os.contains("mac")) {
            try {
                // Apple sets this property when dark aqua is active
                String appearance = System.getProperty("apple.awt.application.appearance", "");
                if (debug) System.err.println("[ThemeManager] macOS appearance prop: '" + appearance + "'");

                if (appearance.equalsIgnoreCase("NSAppearanceNameDarkAqua") ||
                    appearance.toLowerCase().contains("dark")) {
                    isDark = true;
                    detected = true;
                    if (debug) System.err.println("[ThemeManager] macOS → DARK detected");
                } else if (!appearance.isEmpty()) {
                    isDark = false;
                    detected = true;
                    if (debug) System.err.println("[ThemeManager] macOS → LIGHT detected");
                } else {
                    // Property absent - try UIManager hint that Java sets after LAF init
                    Object uiHint = UIManager.get("control");
                    if (uiHint instanceof Color c) {
                        // Luminance heuristic: dark control color → dark theme
                        double lum = luminance(c);
                        if (debug) System.err.println("[ThemeManager] macOS UIManager control luminance: " + lum);
                        isDark = lum < 0.4;
                        detected = true;
                        if (debug) System.err.println("[ThemeManager] macOS luminance → " + (isDark ? "DARK" : "LIGHT"));
                    }
                }
            } catch (Exception e) {
                // Detection failed - log and fall through
                if (debug) System.err.println("[ThemeManager] macOS detection error: " + e.getMessage());
            }
        }

        // --- 2. Windows registry detection ---
        if (!detected && os.contains("win")) {
            try {
                // AppsUseLightTheme: 0 = dark mode, 1 = light mode
                String regKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
                Process proc = Runtime.getRuntime().exec(
                    new String[]{"reg", "query", regKey, "/v", "AppsUseLightTheme"});
                String output = new String(proc.getInputStream().readAllBytes()).trim();
                if (debug) System.err.println("[ThemeManager] Windows reg output: " + output);

                if (output.contains("0x0")) {
                    isDark = true;
                    detected = true;
                    if (debug) System.err.println("[ThemeManager] Windows registry → DARK");
                } else if (output.contains("0x1")) {
                    isDark = false;
                    detected = true;
                    if (debug) System.err.println("[ThemeManager] Windows registry → LIGHT");
                } else {
                    // Unexpected registry output
                    System.err.println("[ThemeManager] WARN: Windows registry value unexpected: " + output);
                }
            } catch (Exception e) {
                System.err.println("[ThemeManager] WARN: Windows registry detection failed: " + e.getMessage());
            }
        }

        // --- 3. Linux - multi-method detection, each logged individually ---
        // GTK_THEME env alone is unreliable - most DEs don't set it.
        // Try in order: gsettings color-scheme → dconf → GTK_THEME env → UIManager luminance
        if (!detected && (os.contains("nix") || os.contains("nux") || os.contains("bsd"))) {

            // --- 3a. gsettings (GNOME / KDE with GNOME compat / most modern DEs) ---
            // color-scheme returns "prefer-dark", "prefer-light", or "default"
            try {
                Process proc = Runtime.getRuntime().exec(
                    new String[]{"gsettings", "get", "org.gnome.desktop.interface", "color-scheme"});
                String out = new String(proc.getInputStream().readAllBytes()).trim().toLowerCase();
                System.err.println("[ThemeManager] gsettings color-scheme: '" + out + "'");

                if (out.contains("dark")) {
                    isDark = true; detected = true;
                    System.err.println("[ThemeManager] Linux gsettings → DARK");
                } else if (out.contains("light")) {
                    isDark = false; detected = true;
                    System.err.println("[ThemeManager] Linux gsettings → LIGHT");
                } else {
                    System.err.println("[ThemeManager] Linux gsettings → ambiguous ('" + out + "') - trying next method");
                }
            } catch (Exception e) {
                System.err.println("[ThemeManager] Linux gsettings unavailable: " + e.getMessage());
            }

            // --- 3b. dconf fallback (covers older GNOME / Cinnamon / MATE) ---
            if (!detected) {
                try {
                    Process proc = Runtime.getRuntime().exec(
                        new String[]{"dconf", "read", "/org/gnome/desktop/interface/color-scheme"});
                    String out = new String(proc.getInputStream().readAllBytes()).trim().toLowerCase();
                    System.err.println("[ThemeManager] dconf color-scheme: '" + out + "'");

                    if (out.contains("dark")) {
                        isDark = true; detected = true;
                        System.err.println("[ThemeManager] Linux dconf → DARK");
                    } else if (out.contains("light")) {
                        isDark = false; detected = true;
                        System.err.println("[ThemeManager] Linux dconf → LIGHT");
                    } else {
                        System.err.println("[ThemeManager] Linux dconf → ambiguous - trying next method");
                    }
                } catch (Exception e) {
                    System.err.println("[ThemeManager] Linux dconf unavailable: " + e.getMessage());
                }
            }

            // --- 3c. GTK_THEME environment variable ---
            // Set by some DEs / compositors - contains theme name which may include "dark"
            if (!detected) {
                try {
                    String gtkTheme = System.getenv("GTK_THEME");
                    System.err.println("[ThemeManager] GTK_THEME env: '" + gtkTheme + "'");

                    if (gtkTheme != null && gtkTheme.toLowerCase().contains("dark")) {
                        isDark = true; detected = true;
                        System.err.println("[ThemeManager] Linux GTK_THEME → DARK");
                    } else if (gtkTheme != null && !gtkTheme.isEmpty()) {
                        isDark = false; detected = true;
                        System.err.println("[ThemeManager] Linux GTK_THEME → LIGHT (no 'dark' in name: '" + gtkTheme + "')");
                    } else {
                        System.err.println("[ThemeManager] Linux GTK_THEME → not set, trying next method");
                    }
                } catch (Exception e) {
                    System.err.println("[ThemeManager] Linux GTK_THEME check failed: " + e.getMessage());
                }
            }

            // --- 3d. UIManager control color luminance --- last resort ---
            // Swing's default Metal LAF uses a light grey control color - not reliable
            // but better than nothing if all other methods fail
            if (!detected) {
                try {
                    Object uiHint = UIManager.get("control");
                    if (uiHint instanceof Color c) {
                        double lum = luminance(c);
                        // Always log this - it's the most likely failure point
                        System.err.println("[ThemeManager] Linux UIManager control color: " + c +
                                           " luminance=" + String.format("%.3f", lum) +
                                           " (threshold 0.4 - below=DARK above=LIGHT)");
                        System.err.println("[ThemeManager] NOTE: UIManager luminance uses Swing default LAF color,");
                        System.err.println("[ThemeManager]       NOT your GTK theme - result may be wrong.");
                        isDark = lum < 0.4;
                        detected = true;
                        System.err.println("[ThemeManager] Linux UIManager luminance → " + (isDark ? "DARK" : "LIGHT"));
                    } else {
                        System.err.println("[ThemeManager] Linux UIManager 'control' key returned null or non-Color");
                    }
                } catch (Exception e) {
                    System.err.println("[ThemeManager] Linux UIManager check failed: " + e.getMessage());
                }
            }
        }

        // --- 4. Fallback - default DARK, always log this so it is visible ---
        if (!detected) {
            isDark = true;
            System.err.println("[ThemeManager] WARN: All detection methods exhausted for OS=" +
                System.getProperty("os.name") + " defaulting to DARK");
        }

        // ===== APPLY ACTIVE PALETTE =====
        applyPalette();
        applyUIManagerDefaults();

        if (debug) {
        System.err.println("[ThemeManager] =====================================");
        System.err.println("[ThemeManager] THEME: " + (isDark ? "DARK" : "LIGHT") +
                           "  |  detected=" + detected +
                           "  |  OS=" + System.getProperty("os.name"));
        System.err.println("[ThemeManager] =====================================");
        }
    }

    // ===== APPLY PALETTE =====
    // Copies either DARK_ or LIGHT_ constants into the active BG/TEXT/etc fields
    private static void applyPalette() {
        if (isDark) {
            BG         = DARK_BG;
            SURFACE    = DARK_SURFACE;
            LEFT_LOGIN = DARK_LEFT_LOGIN;
            SURFACE2   = DARK_SURFACE2;
            BORDER     = DARK_BORDER;
            TEXT       = DARK_TEXT;
            TEXT_MUTED = DARK_TEXT_MUTED;
            ACCENT     = DARK_ACCENT;
            ACCENT_HOV = DARK_ACCENT_HOV;
            ACCENT_BTN     = DARK_ACCENT_BTN;
            ACCENT_BTN_HOV = DARK_ACCENT_BTN_HOV;
            DANGER     = DARK_DANGER;
            SUCCESS    = DARK_SUCCESS;
            WARNING    = DARK_WARNING;
            SELECT     = DARK_SELECT;
        } else {
            BG         = LIGHT_BG;
            SURFACE    = LIGHT_SURFACE;
            LEFT_LOGIN = LIGHT_LEFT_LOGIN;
            SURFACE2   = LIGHT_SURFACE2;
            BORDER     = LIGHT_BORDER;
            TEXT       = LIGHT_TEXT;
            TEXT_MUTED = LIGHT_TEXT_MUTED;
            ACCENT     = LIGHT_ACCENT;
            ACCENT_HOV = LIGHT_ACCENT_HOV;
            ACCENT_BTN     = LIGHT_ACCENT_BTN;
            ACCENT_BTN_HOV = LIGHT_ACCENT_BTN_HOV;
            DANGER     = LIGHT_DANGER;
            SUCCESS    = LIGHT_SUCCESS;
            WARNING    = LIGHT_WARNING;
            SELECT     = LIGHT_SELECT;
        }
    }

    // ===== UIMANAGER DEFAULTS =====
    // Pushes theme colors into Swing's global defaults so basic components
    // (JOptionPane, JScrollBar, etc.) inherit the palette without manual styling
    private static void applyUIManagerDefaults() {
        // Panel / window backgrounds
        UIManager.put("Panel.background",            new ColorUIResource(BG));
        UIManager.put("OptionPane.background",       new ColorUIResource(SURFACE));
        UIManager.put("OptionPane.messageForeground",new ColorUIResource(TEXT));
        UIManager.put("Dialog.background",           new ColorUIResource(SURFACE));

        // Text fields
        UIManager.put("TextField.background",        new ColorUIResource(SURFACE2));
        UIManager.put("TextField.foreground",        new ColorUIResource(TEXT));
        UIManager.put("TextField.caretForeground",   new ColorUIResource(ACCENT));
        UIManager.put("TextField.border",            javax.swing.BorderFactory.createLineBorder(BORDER));
        UIManager.put("PasswordField.background",    new ColorUIResource(SURFACE2));
        UIManager.put("PasswordField.foreground",    new ColorUIResource(TEXT));
        UIManager.put("PasswordField.caretForeground", new ColorUIResource(ACCENT));
        UIManager.put("TextArea.background",         new ColorUIResource(SURFACE2));
        UIManager.put("TextArea.foreground",         new ColorUIResource(TEXT));

        // Buttons
        UIManager.put("Button.background",           new ColorUIResource(SURFACE2));
        UIManager.put("Button.foreground",           new ColorUIResource(TEXT));

        // Labels
        UIManager.put("Label.foreground",            new ColorUIResource(TEXT));

        // Table
        UIManager.put("Table.background",            new ColorUIResource(SURFACE));
        UIManager.put("Table.foreground",            new ColorUIResource(TEXT));
        UIManager.put("Table.selectionBackground",   new ColorUIResource(SELECT));
        UIManager.put("Table.selectionForeground",   new ColorUIResource(TEXT));
        UIManager.put("Table.gridColor",             new ColorUIResource(BORDER));
        UIManager.put("TableHeader.background",      new ColorUIResource(SURFACE2));
        UIManager.put("TableHeader.foreground",      new ColorUIResource(TEXT_MUTED));

        // ScrollPane
        UIManager.put("ScrollPane.background",       new ColorUIResource(BG));
        UIManager.put("Viewport.background",         new ColorUIResource(SURFACE));

        // ComboBox
        UIManager.put("ComboBox.background",         new ColorUIResource(SURFACE2));
        UIManager.put("ComboBox.foreground",         new ColorUIResource(TEXT));
        UIManager.put("ComboBox.selectionBackground",new ColorUIResource(SELECT));
        UIManager.put("ComboBox.selectionForeground",new ColorUIResource(TEXT));

        // CheckBox / RadioButton
        UIManager.put("CheckBox.background",         new ColorUIResource(SURFACE));
        UIManager.put("CheckBox.foreground",         new ColorUIResource(TEXT));
        UIManager.put("RadioButton.background",      new ColorUIResource(SURFACE));
        UIManager.put("RadioButton.foreground",      new ColorUIResource(TEXT));

        // Spinner
        UIManager.put("Spinner.background",          new ColorUIResource(SURFACE2));
        UIManager.put("Spinner.foreground",          new ColorUIResource(TEXT));

        // Tooltip
        UIManager.put("ToolTip.background",          new ColorUIResource(SURFACE2));
        UIManager.put("ToolTip.foreground",          new ColorUIResource(TEXT));
        UIManager.put("ToolTip.border",              javax.swing.BorderFactory.createLineBorder(BORDER));

        // SplitPane / ScrollBar
        UIManager.put("SplitPane.background",        new ColorUIResource(BG));
        UIManager.put("ScrollBar.background",        new ColorUIResource(SURFACE));
        UIManager.put("ScrollBar.thumb",             new ColorUIResource(BORDER));
    }

    // ===== LUMINANCE HELPER =====
    // sRGB relative luminance per WCAG 2.1 - used for theme heuristic detection
    // Returns 0.0 (black) to 1.0 (white)
    private static double luminance(Color c) {
        double r = c.getRed()   / 255.0;
        double g = c.getGreen() / 255.0;
        double b = c.getBlue()  / 255.0;
        // Linearise sRGB channels
        r = r <= 0.04045 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.04045 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        b = b <= 0.04045 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    // ===== CONVENIENCE HELPERS =====
    // Styled accent button - call after construction to apply theme colors
    public static void styleAccentButton(javax.swing.JButton btn) {
        btn.setBackground(ACCENT);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        btn.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        btn.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent e) { btn.setBackground(ACCENT_BTN_HOV); }
            public void mouseExited (java.awt.event.MouseEvent e) { btn.setBackground(ACCENT_BTN);     }
        });
    }

    // Styled danger button (delete, remove)
    public static void styleDangerButton(javax.swing.JButton btn) {
        btn.setBackground(DANGER);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        btn.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
    }

    // Styled neutral/surface button
    public static void styleSurfaceButton(javax.swing.JButton btn) {
        btn.setBackground(SURFACE2);
        btn.setForeground(TEXT);
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        btn.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent e) { btn.setBackground(BORDER);    }
            public void mouseExited (java.awt.event.MouseEvent e) { btn.setBackground(SURFACE2);  }
        });
    }

    protected static void themeFileChooserComponents(Component comp) {
        // Theme panels/containers
        if (comp instanceof JPanel panel) {
            panel.setBackground(ThemeManager.SURFACE);
            panel.setForeground(ThemeManager.TEXT);
        }
        // Theme buttons specifically
        if (comp instanceof JButton btn) {
            btn.setBackground(ThemeManager.SURFACE2);       // Your accent/button color
            btn.setForeground(ThemeManager.TEXT);
            btn.setBorderPainted(false);
            btn.setFocusPainted(false);
            btn.setOpaque(true);
        }
        // Theme text fields (path bar etc.)
        if (comp instanceof JTextField field) {
            field.setBackground(ThemeManager.SURFACE);
            field.setForeground(ThemeManager.TEXT);
            field.setCaretColor(ThemeManager.TEXT);
        }
        // Recurse into nested containers
        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) {
                themeFileChooserComponents(child);
            }
        }
    }

    // ===== STYLE OPTION PANE =====
    // Apply before any showConfirmDialog / showMessageDialog call
    // JOptionPane creates a new dialog each time so UIManager defaults handle it
    public static void applyOptionPaneTheme() {
        // Background of the dialog panel itself
        UIManager.put("OptionPane.background",        new ColorUIResource(SURFACE));
        UIManager.put("Panel.background",             new ColorUIResource(SURFACE));

        // Message text color
        UIManager.put("OptionPane.messageForeground", new ColorUIResource(TEXT));

        // Yes / No / OK / Cancel button colors
        UIManager.put("Button.background",            new ColorUIResource(SURFACE2));
        UIManager.put("Button.foreground",            new ColorUIResource(TEXT));
        UIManager.put("Button.focus",                 new ColorUIResource(BORDER));

        // Border around buttons area
        UIManager.put("OptionPane.border",
            javax.swing.BorderFactory.createLineBorder(BORDER, 1));
    }
    // ===== THEMED CONFIRM DIALOG =====
    // JOptionPane buttons ignore UIManager after LAF render - must theme post-creation
    // Returns JOptionPane.YES_OPTION or JOptionPane.NO_OPTION
    public static int showThemedConfirm(java.awt.Component parent, String message, String title) {
        // ===== BUILD PANE MANUALLY SO WE CAN THEME IT BEFORE DISPLAY =====
        JOptionPane pane = new JOptionPane(
            message,
            JOptionPane.QUESTION_MESSAGE,
            JOptionPane.YES_NO_OPTION
        );

        // ===== APPLY PALETTE TO PANE ITSELF =====
        pane.setBackground(ThemeManager.SURFACE);
        pane.setForeground(ThemeManager.TEXT);

        // ===== CREATE DIALOG FROM PANE =====
        javax.swing.JDialog dialog = pane.createDialog(parent, title);
        dialog.setBackground(ThemeManager.SURFACE);

        // ===== RECURSE INTO ALL CHILD COMPONENTS AND THEME THEM =====
        themeFileChooserComponents(dialog);

        // ===== STYLE BUTTONS SPECIFICALLY (Yes / No) =====
        themeOptionPaneButtons(dialog);

        dialog.setVisible(true);

        // ===== EXTRACT RESULT =====
        Object result = pane.getValue();
        if (result instanceof Integer val) return val;

        // User closed dialog without choosing - treat as NO
        return JOptionPane.NO_OPTION;
    }

    // ===== THEME OPTION PANE BUTTONS =====
    // Recurses into dialog to find and style JButtons (Yes/No/OK/Cancel)
    private static void themeOptionPaneButtons(java.awt.Container container) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JButton btn) {
                // ===== APPLY ACCENT BUTTON STYLE TO DIALOG BUTTONS =====
                btn.setBackground(ThemeManager.SURFACE2);
                btn.setForeground(ThemeManager.TEXT);
                btn.setFocusPainted(false);
                btn.setBorderPainted(false);
                btn.setOpaque(true);
                btn.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

                // ===== HOVER EFFECT =====
                btn.addMouseListener(new java.awt.event.MouseAdapter() {
                    public void mouseEntered(java.awt.event.MouseEvent e) { btn.setBackground(ThemeManager.BORDER); }
                    public void mouseExited (java.awt.event.MouseEvent e) { btn.setBackground(ThemeManager.SURFACE2); }
                });
            }
            // ===== RECURSE INTO NESTED CONTAINERS =====
            if (comp instanceof java.awt.Container nested) {
                themeOptionPaneButtons(nested);
            }
        }
    }
}