import java.awt.*;
import javax.swing.*;

/**
 * ===== PASSWORD STRENGTH UTILITY =====
 * Calculates Shannon entropy-based bit strength for a generated or typed password.
 *
 * Entropy formula:
 *   bits = length × log₂(pool_size)
 *
 * Pool size is determined by which character classes are present in the password:
 *   lowercase a-z   →  26 chars
 *   uppercase A-Z   →  26 chars
 *   digits 0-9      →  10 chars
 *   special !@#...  →  32 chars  (standard printable ASCII minus alphanumeric)
 *
 * Strength buckets (NIST / academic consensus):
 *   < 28 bits  → Very Weak  (cracks in seconds on modern hardware)
 *   28-35      → Weak
 *   36-59      → Fair
 *   60-79      → Good
 *   80-99      → Strong
 *   100+       → Very Strong
 *
 * The strength bar widget updates live as the user types or clicks Generate.
 */
public class Thor {

    // ===== STRENGTH LEVELS =====
    public enum Strength {
        VERY_WEAK  ("Very Weak",   new Color(0xEF4444)),  // red
        WEAK       ("Weak",        new Color(0xF97316)),  // orange
        FAIR       ("Fair",        new Color(0xEAB308)),  // yellow
        GOOD       ("Good",        new Color(0x84CC16)),  // lime
        STRONG     ("Strong",      new Color(0x22C55E)),  // green
        VERY_STRONG("Very Strong", new Color(0x00B4D8)),  // cyan (matches accent)
        SUPER_STRONG("Super Strong", new Color(0x1E5F8E));  // cyan (matches accent)

        public final String label;
        public final Color  color;

        Strength(String label, Color color) {
            this.label = label;
            this.color = color;
        }
    }

    // ===== ENTROPY CALCULATION =====
    /**
     * Calculates entropy in bits for the given password char array.
     * Does NOT store or log the password - operates on the array directly.
     *
     * @param password  char[] password to evaluate
     * @return entropy bits as a double
     */
    public static double entropyBits(char[] password) {
        if (password == null || password.length == 0) return 0.0;

        // ===== DETECT WHICH CHARACTER CLASSES ARE PRESENT =====
        boolean hasLower   = false;
        boolean hasUpper   = false;
        boolean hasDigit   = false;
        boolean hasSpecial = false;

        for (char c : password) {
            if      (c >= 'a' && c <= 'z') hasLower   = true;
            else if (c >= 'A' && c <= 'Z') hasUpper   = true;
            else if (c >= '0' && c <= '9') hasDigit   = true;
            else                            hasSpecial = true; // anything else = special
        }

        // ===== POOL SIZE = SUM OF ACTIVE CHARACTER CLASS SIZES =====
        int pool = 0;
        if (hasLower)   pool += 26;
        if (hasUpper)   pool += 26;
        if (hasDigit)   pool += 10;
        if (hasSpecial) pool += 32; // printable ASCII special chars

        if (pool == 0) return 0.0;

        // bits = length × log₂(pool)
        return password.length * (Math.log(pool) / Math.log(2));
    }

    // ===== STRENGTH CLASSIFICATION =====
    /**
     * Maps entropy bits to a Strength enum value.
     */
    public static Strength classify(double bits) {
        if (bits < 28) return Strength.VERY_WEAK;
        if (bits < 36) return Strength.WEAK;
        if (bits < 60) return Strength.FAIR;
        if (bits < 80) return Strength.GOOD;
        if (bits < 100) return Strength.STRONG;
        if (bits < 130) return Strength.VERY_STRONG;
        return Strength.SUPER_STRONG;
    }

    // ===== UI: STRENGTH BAR PANEL =====
    /**
     * Creates a self-contained panel showing:
     *   [████████░░░░]  Strong  (87.3 bits)
     *
     * Call {@link StrengthBarPanel#update(char[])} whenever the password changes.
     * The panel handles its own repainting - no external timer needed.
     */
    public static class StrengthBarPanel extends JPanel {

        private final JProgressBar bar;
        private final JLabel       label;

        public StrengthBarPanel() {
            setLayout(new BorderLayout(6, 0));
            setOpaque(false);

            // ===== PROGRESS BAR =====
            bar = new JProgressBar(0, 130); // 130 bits = upper display limit
            bar.setValue(0);
            bar.setStringPainted(false);    // we draw our own label
            bar.setPreferredSize(new Dimension(0, 8)); // thin bar
            bar.setBorderPainted(false);
            bar.setBackground(ThemeManager.BORDER);
            bar.setForeground(Strength.VERY_WEAK.color);

            // ===== LABEL =====
            label = new JLabel("-");
            label.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            label.setForeground(ThemeManager.TEXT_MUTED);
            label.setPreferredSize(new Dimension(170, 16)); // fixed width stops layout jump

            add(bar,   BorderLayout.CENTER);
            add(label, BorderLayout.EAST);
        }

        /**
         * Recalculates entropy and updates the bar + label.
         * Safe to call from any thread - marshals to EDT internally.
         *
         * @param password  Current password char array.  NOT stored or wiped here -
         *                  caller is responsible for lifecycle.
         */
        public void update(char[] password) {
            double bits     = entropyBits(password);
            Strength rating = classify(bits);

            SwingUtilities.invokeLater(() -> {
                // Clamp bar value to max
                bar.setValue((int) Math.min(bits, 130));
                bar.setForeground(rating.color);

                // Label: "Strong  (87.3 bits)"
                label.setText(String.format("%-12s %.0f bits", rating.label, bits));
                label.setForeground(rating.color);
            });
        }

        /**
         * Convenience - extracts char[] from a JPasswordField, calls update(),
         * then immediately wipes the extracted array.
         * The JPasswordField's own internal buffer is NOT wiped here - caller
         * should call getPassword() → wipe only at final submission.
         */
        public void updateFromField(JPasswordField field) {
            char[] pw = field.getPassword();
            update(pw);
            // Wipe our local copy - the field's internal buffer is handled separately
            java.util.Arrays.fill(pw, '\0');
        }
    }
}
