import java.awt.*;
import javax.swing.*;

/**
 * ===== TOAST MANAGER =====
 * Displays a brief themed notification overlay anchored to the bottom-right
 * corner of a parent JFrame.  Auto-dismisses after a configurable duration.
 *
 * Features:
 *   - Borderless JWindow - no taskbar entry, no title bar
 *   - Slide-up animation on show, fade on dismiss
 *   - Colour-coded: SUCCESS (green), INFO (accent), WARNING (yellow), ERROR (red)
 *   - Click to dismiss early
 *   - Thread-safe - always dispatched on the EDT
 *   - Previous toast cancelled automatically if a new one arrives before dismiss
 */
public class ToastManager {

    // ===== TOAST TYPE =====
    public enum ToastType { SUCCESS, INFO, WARNING, ERROR }

    // ===== DISPLAY DURATION =====
    private static final int DEFAULT_DURATION_MS = 10_000; // 10 seconds as spec'd
    private static final int SLIDE_DURATION_MS   = 220;    // slide-up animation duration
    private static final int SLIDE_STEPS         = 12;     // animation frame count
    private static final int TOAST_WIDTH         = 320;
    private static final int TOAST_HEIGHT        = 68;
    private static final int MARGIN              = 16;     // gap from window edge

    // ===== ACTIVE TOAST TRACKING =====
    // Only one toast visible at a time - previous is dismissed before new one shows
    private static JWindow  activeToast  = null;
    private static Timer    dismissTimer = null;
    private static Timer    slideTimer   = null;

    // ===== SHOW =====
    /**
     * Show a toast anchored to the bottom-right of {@code parent}.
     *
     * @param parent   The JFrame to anchor to (required for positioning)
     * @param message  Text to display
     * @param type     Colour coding - SUCCESS / INFO / WARNING / ERROR
     * @param durationMs Auto-dismiss after this many milliseconds
     */
    public static void show(JFrame parent, String message, ToastType type, int durationMs) {
        // Always run UI work on the EDT
        SwingUtilities.invokeLater(() -> {
            dismissActive(); // cancel any previous toast immediately

            // ===== BUILD TOAST WINDOW =====
            JWindow toast = new JWindow(parent);
            toast.setSize(TOAST_WIDTH, TOAST_HEIGHT);
            // Make window background transparent so rounded corners work
            toast.setBackground(new Color(0, 0, 0, 0));

            // ===== TOAST PANEL =====
            JPanel panel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    // Custom paint - rounded rectangle with theme colour
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                        RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(toastBackground(type));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                    // Subtle border
                    g2.setColor(toastBorder(type));
                    g2.setStroke(new BasicStroke(1.2f));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            panel.setOpaque(false);
            panel.setLayout(new BorderLayout(10, 0));
            panel.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

            // ===== ICON LABEL =====
            JLabel icon = new JLabel(toastIcon(type));
            icon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 20));
            icon.setForeground(toastTextColor(type));

            // ===== MESSAGE LABEL =====
            JLabel label = new JLabel("<html><body style='width:220px'>" + message + "</body></html>");
            label.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            label.setForeground(toastTextColor(type));

            // ===== CLOSE BUTTON =====
            JLabel closeBtn = new JLabel("✕");
            closeBtn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            closeBtn.setForeground(toastTextColor(type));
            closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            // Click to dismiss early
            closeBtn.addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseClicked(java.awt.event.MouseEvent e) { dismissActive(); }
            });

            panel.add(icon,     BorderLayout.WEST);
            panel.add(label,    BorderLayout.CENTER);
            panel.add(closeBtn, BorderLayout.EAST);
            toast.add(panel);

            // ===== POSITION - bottom-right of parent frame =====
            updatePosition(toast, parent);

            // Track parent resize/move so toast stays anchored
            parent.addComponentListener(new java.awt.event.ComponentAdapter() {
                public void componentMoved  (java.awt.event.ComponentEvent e) { updatePosition(toast, parent); }
                public void componentResized(java.awt.event.ComponentEvent e) { updatePosition(toast, parent); }
            });

            // ===== SLIDE-UP ANIMATION =====
            // Toast starts TOAST_HEIGHT px below its final Y position and slides up
            Point finalPos  = toastPosition(parent);
            int   startY    = finalPos.y + TOAST_HEIGHT;
            int   targetY   = finalPos.y;
            int[] currentY  = { startY }; // array wrapper so lambda can mutate

            toast.setLocation(finalPos.x, startY);
            toast.setVisible(true);
            activeToast = toast;

            int stepSize = (startY - targetY) / SLIDE_STEPS;
            slideTimer = new Timer(SLIDE_DURATION_MS / SLIDE_STEPS, null);
            slideTimer.addActionListener(e -> {
                currentY[0] -= stepSize;
                if (currentY[0] <= targetY) {
                    currentY[0] = targetY;
                    ((Timer) e.getSource()).stop();
                }
                toast.setLocation(finalPos.x, currentY[0]);
            });
            slideTimer.start();

            // ===== AUTO-DISMISS TIMER =====
            dismissTimer = new Timer(durationMs, e -> dismissActive());
            dismissTimer.setRepeats(false);
            dismissTimer.start();
        });
    }

    /** Show with default 10-second duration. */
    public static void show(JFrame parent, String message, ToastType type) {
        show(parent, message, type, DEFAULT_DURATION_MS);
    }

    /** Convenience - SUCCESS toast. */
    public static void success(JFrame parent, String message) {
        show(parent, message, ToastType.SUCCESS);
    }

    /** Convenience - INFO toast. */
    public static void info(JFrame parent, String message) {
        show(parent, message, ToastType.INFO);
    }

    /** Convenience - WARNING toast. */
    public static void warning(JFrame parent, String message) {
        show(parent, message, ToastType.WARNING);
    }

    /** Convenience - ERROR toast. */
    public static void error(JFrame parent, String message) {
        show(parent, message, ToastType.ERROR);
    }

    // ===== DISMISS ACTIVE =====
    // Stops timers and disposes the current toast window
    private static void dismissActive() {
        if (dismissTimer != null) { dismissTimer.stop(); dismissTimer = null; }
        if (slideTimer   != null) { slideTimer.stop();   slideTimer   = null; }
        if (activeToast  != null) { activeToast.dispose(); activeToast = null; }
    }

    // ===== POSITION HELPERS =====
    // Calculates bottom-right corner position relative to parent frame
    private static Point toastPosition(JFrame parent) {
        Rectangle bounds = parent.getBounds();
        int x = bounds.x + bounds.width  - TOAST_WIDTH  - MARGIN;
        int y = bounds.y + bounds.height - TOAST_HEIGHT - MARGIN - 40; // 40 = approx taskbar clearance
        return new Point(x, y);
    }

    private static void updatePosition(JWindow toast, JFrame parent) {
        if (toast.isVisible()) {
            Point p = toastPosition(parent);
            toast.setLocation(p.x, p.y);
        }
    }

    // ===== COLOUR HELPERS =====
    // Maps toast type to theme colours - respects ThemeManager.isDark
    private static Color toastBackground(ToastType type) {
        return switch (type) {
            case SUCCESS -> ThemeManager.isDark ? new Color(0x1A3A2A) : new Color(0xDCFCE7);
            case WARNING -> ThemeManager.isDark ? new Color(0x3A2E0A) : new Color(0xFEF9C3);
            case ERROR   -> ThemeManager.isDark ? new Color(0x3A1A1A) : new Color(0xFEE2E2);
            case INFO    -> ThemeManager.isDark ? new Color(0x0D2137) : new Color(0xDCF0FF);
        };
    }

    private static Color toastBorder(ToastType type) {
        return switch (type) {
            case SUCCESS -> ThemeManager.SUCCESS;
            case WARNING -> ThemeManager.WARNING;
            case ERROR   -> ThemeManager.DANGER;
            case INFO    -> ThemeManager.ACCENT;
        };
    }

    private static Color toastTextColor(ToastType type) {
        // In light mode use darker shades for readability
        if (!ThemeManager.isDark) {
            return switch (type) {
                case SUCCESS -> new Color(0x14532D);
                case WARNING -> new Color(0x713F12);
                case ERROR   -> new Color(0x7F1D1D);
                case INFO    -> new Color(0x1E3A5F);
            };
        }
        // Dark mode - just use bright theme text
        return ThemeManager.TEXT;
    }

    private static String toastIcon(ToastType type) {
        return switch (type) {
            case SUCCESS -> "✓";
            case WARNING -> "⚠";
            case ERROR   -> "✕";
            case INFO    -> "ℹ";
        };
    }
}
