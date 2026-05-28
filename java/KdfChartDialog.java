import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import javax.swing.*;
import javax.swing.border.*;

/**
 * ===== KDF CRACK TIME CHART DIALOG =====
 *
 * Swing line chart comparing GPU brute-force crack times for:
 *   - PBKDF2-SHA256 at 600,000 iterations
 *   - Five Argon2id profiles used in Odin
 *
 * Across password lengths 10-20 characters, for four attacker tiers.
 *
 * Call from Odin toolbar:
 *   JButton kdfBtn = new JButton("KDF Chart");
 *   ThemeManager.styleSurfaceButton(kdfBtn);
 *   kdfBtn.addActionListener(e -> KdfChartDialog.show(mainFrame));
 *   panel.add(kdfBtn);
 *
 * ALL VALUES ARE ESTIMATES based on published Hashcat community benchmarks
 * and the Argon2 reference paper (RFC 9106). Nothing is uncrackable --
 * these are cost and resource estimates only.
 *
 * Benchmark sources:
 *   PBKDF2-SHA256 600K : Hashcat mode 10900, RTX 4090 ~450K h/s
 *   Argon2id 19MB/2i   : Hashcat mode 13932, ~600 h/s on RTX 4090
 *   Argon2id 32MB/6i   : ~350 h/s
 *   Argon2id 64MB/3i   : ~180 h/s
 *   Argon2id 1GB/4i    : ~10 h/s  (~24 parallel slots on 24GB VRAM)
 *   Argon2id 2GB/6i    : ~4 h/s   (~12 parallel slots on 24GB VRAM)
 */
public class KdfChartDialog {

    // =========================================================================
    // KDF PROFILE DEFINITIONS
    // =========================================================================

    private static final class Profile {
        final String  label;
        final boolean isPbkdf2;
        final int     memMb;
        final int     iterations;
        final double  singleGpuHps; // RTX 4090 h/s from Hashcat benchmarks
        final Color   lineColor;
        final float[] dash;         // stroke dash pattern (null = solid)

        Profile(String label, boolean isPbkdf2, int memMb, int iterations,
                double singleGpuHps, Color lineColor, float[] dash) {
            this.label        = label;
            this.isPbkdf2     = isPbkdf2;
            this.memMb        = memMb;
            this.iterations   = iterations;
            this.singleGpuHps = singleGpuHps;
            this.lineColor    = lineColor;
            this.dash         = dash;
        }
    }

    // --- PBKDF2-SHA256 600K: no memory constraint, linear GPU scaling ---
    // RTX 4090 Hashcat mode 10900: ~450,000 h/s
    private static final Profile PBKDF2 = new Profile(
        "PBKDF2-SHA256 (600K)",
        true, 0, 600_000,
        450_000,
        new Color(0xC94F3A),   // ThemeManager.DANGER equivalent
        new float[]{10f, 5f}
    );

    // --- OWASP 2023 minimum: 19MB/2i/1p, ~600 h/s ---
    private static final Profile OWASP_MIN = new Profile(
        "Argon2id OWASP min (19MB/2i)",
        false, 19, 2,
        600,
        new Color(0xC4882A),   // ThemeManager.WARNING
        new float[]{6f, 4f}
    );

    // --- Warden default: 32MB/6i/4p ---
    private static final Profile WARDEN = new Profile(
        "Argon2id Warden (32MB/6i)",
        false, 32, 6,
        120,
        new Color(0x378ADD),   // blue
        new float[]{8f, 4f}
    );

    // --- RFC 9106 balanced: 64MB/3i/4p, ~180 h/s ---
    private static final Profile RFC_BALANCED = new Profile(
        "Argon2id RFC balanced (64MB/3i)",
        false, 64, 3,
        180,
        new Color(0x5ECFB0),   // ThemeManager.ACCENT equivalent
        new float[]{4f, 3f}
    );

    // --- RFC 9106 high: 1GB/4i/4p, ~10 h/s ---
    // 1GB per hash = ~24 parallel slots on 24GB GPU
    private static final Profile RFC_HIGH = new Profile(
        "Argon2id RFC high (1GB/4i)",
        false, 1024, 4,
        10,
        new Color(0x3A9E6A),   // ThemeManager.SUCCESS
        new float[]{3f, 3f}
    );

    // --- Paranoid beyond RFC 9106: 2GB/6i/8p, ~4 h/s ---
    // 2GB per hash = ~12 parallel slots on 24GB GPU
    private static final Profile PARANOID = new Profile(
        "Argon2id Paranoid (2GB/6i)",
        false, 2048, 6,
        4,
        new Color(0xD4537E),   // pink
        new float[]{2f, 2f}
    );

    private static final Profile[] ALL_PROFILES = {
        PBKDF2, OWASP_MIN, WARDEN, RFC_BALANCED, RFC_HIGH, PARANOID
    };

    // =========================================================================
    // ATTACKER TIERS
    // =========================================================================

    private static final String[] TIER_NAMES = {
        "Single GPU (RTX 4090)",
        "GPU cluster (8x A100)",
        "Nation-state (~1,000 GPUs)",
        "Theoretical (~10,000 GPUs)"
    };

    // GPU-equivalent count per tier
    private static final int[] TIER_GPU_COUNT = { 1, 8, 1_000, 10_000 };

    // Total VRAM pool per tier in MB (for Argon2id slot cap)
    private static final long[] TIER_VRAM_MB = {
        24_576L,                       // single 24GB GPU
        8L   * 81_920,                 // 8x A100 80GB
        1_000L  * 24_576,              // 1,000 x 24GB
        10_000L * 24_576               // 10,000 x 24GB
    };

    // =========================================================================
    // CHARSET DEFINITIONS
    // =========================================================================

    private static final int[]    CHARSET_SIZES  = { 26, 52, 62, 72, 95 };
    private static final String[] CHARSET_LABELS = {
        "Lowercase (a-z)  [26]",
        "Upper+lower      [52]",
        "Alphanumeric     [62]",
        "+Symbols         [72]",
        "Full ASCII       [95]"
    };

    // =========================================================================
    // THROUGHPUT MODEL
    // =========================================================================

    /**
     * Effective hashes-per-second for a profile at a given tier.
     *
     * PBKDF2: no memory constraint, scales linearly with GPU count.
     *
     * Argon2id: VRAM-limited.
     *   maxSlots      = floor(totalVramMb / memMbPerHash)
     *   slotsPerGpu   = floor(24576 / memMbPerHash)  -- reference single GPU
     *   effectiveGpus = maxSlots / slotsPerGpu
     *   hps           = effectiveGpus * singleGpuHps
     *
     * This is the key: at 1-2GB per hash, even 10,000 GPUs are bounded
     * by total VRAM pool, not raw compute.
     */
    private static double effectiveHps(Profile p, int tierIdx) {
        if (p.isPbkdf2) {
            return p.singleGpuHps * TIER_GPU_COUNT[tierIdx];
        }
        long maxSlots       = TIER_VRAM_MB[tierIdx] / p.memMb;
        long singleGpuSlots = Math.max(1L, 24_576L / p.memMb);
        double effectiveGpus = (double) maxSlots / singleGpuSlots;
        return effectiveGpus * p.singleGpuHps;
    }

    /**
     * Log10 of average crack time in seconds.
     * Using logs avoids double overflow on very large keyspaces.
     *
     * log10(keyspace/2/hps) = len*log10(charset) - log10(2) - log10(hps)
     */
    private static double crackLog10(int len, int charset, double hps) {
        if (hps <= 0) return 42.0;
        double v = len * Math.log10(charset) - Math.log10(2) - Math.log10(hps);
        return Math.min(Math.max(v, -1.0), 42.0);
    }

    // Age of the universe in seconds (~13.8 billion years)
    private static final double AGE_UNIV_LOG10 =
        Math.log10(13.8e9 * 365.25 * 86400);

    /**
     * Formats a log10(seconds) value to a human-readable string.
     * No use of "uncrackable" -- everything is a cost/resource estimate.
     */
    private static String fmtLog(double logS) {
        if (logS >= 40) return ">10^30 yr";
        double s  = Math.pow(10, logS);
        double yr = s / (365.25 * 86400);
        if (yr > 1e9)  return String.format("%.1fB yr",  yr / 1e9);
        if (yr > 1e6)  return String.format("%.1fM yr",  yr / 1e6);
        if (yr > 1e3)  return String.format("%.0fK yr",  yr / 1e3);
        if (yr > 2)    return String.format("%.0f yr",   yr);
        double days = s / 86400;
        if (days > 30) return String.format("%.0f mo",   days / 30.0);
        if (days > 1)  return String.format("%.0f days", days);
        if (s > 3600)  return String.format("%.0f hr",   s / 3600);
        if (s > 60)    return String.format("%.0f min",  s / 60);
        return String.format("%.0f sec", s);
    }

    // =========================================================================
    // PUBLIC ENTRY POINT
    // =========================================================================

    /**
     * Opens the chart dialog as a modal popup.
     *
     * Two call sites supported:
     *
     *   From the main window toolbar (owner known):
     *     kdfBtn.addActionListener(e -> KdfChartDialog.show(mainFrame));
     *
     *   From login / vault-not-found dialog (no owner yet):
     *     kdfBtn.addActionListener(e -> KdfChartDialog.show());
     *
     * @param owner  parent JFrame for centering and modality, or null at login time
     */
    public static void show() {
        // ===== No-arg overload used at login time before mainFrame exists =====
        show(null);
    }

    public static void show(JFrame owner) {
        // ===== owner may be null at login time - JDialog accepts null fine =====
        JDialog dialog = new JDialog(owner, "KDF Crack Time Estimator", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setBackground(ThemeManager.BG);
        dialog.setContentPane(buildRoot(dialog));
        dialog.setSize(980, 660);
        dialog.setMinimumSize(new Dimension(860, 580));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    // =========================================================================
    // ROOT LAYOUT
    // =========================================================================

    private static JPanel buildRoot(JDialog dialog) {
        // ===== Shared state wired between controls and chart =====
        int[] state = { 0, 12, 2 }; // charsetIdx, pwLen, tierIdx -- must match DEF_* in buildControls

        // ===== Chart panel -- painted directly with Java2D =====
        ChartPanel chartPanel = new ChartPanel(state);

        // ===== Tooltip label shown on mouse hover =====
        JLabel tooltip = new JLabel(" ");
        tooltip.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        tooltip.setForeground(ThemeManager.TEXT);
        tooltip.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));

        // ===== Controls =====
        JPanel controls = buildControls(state, chartPanel, tooltip);

        // ===== Legend =====
        JPanel legend = buildLegend();

        // ===== Disclaimer =====
        JTextArea disclaimer = new JTextArea(
        "ESTIMATES ONLY - based on Hashcat community benchmarks. " +
        "Real throughput varies by GPU, driver, and implementation.\n" +
        "Crack time = average (keyspace / 2 / hashes-per-sec). Not guaranteed and nothing is uncrackable. \n" +
        "Results will vary, what you should really ask is, how guessable is your password?");
        disclaimer.setFont(new Font("Segoe UI", Font.ITALIC, 10));
        disclaimer.setForeground(ThemeManager.TEXT_MUTED);
        disclaimer.setBackground(ThemeManager.SURFACE);
        disclaimer.setEditable(false);
        disclaimer.setFocusable(false);
        disclaimer.setLineWrap(true);
        disclaimer.setWrapStyleWord(true);
        disclaimer.setOpaque(true);
        disclaimer.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

        // ===== Close button =====
        JButton closeBtn = new JButton("Close");
        ThemeManager.styleSurfaceButton(closeBtn);
        closeBtn.addActionListener(e -> dialog.dispose());

        // ===== South strip: tooltip + disclaimer + close =====
        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(ThemeManager.SURFACE);
        south.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ThemeManager.BORDER));

        JPanel southLeft = new JPanel(new BorderLayout());
        southLeft.setBackground(ThemeManager.SURFACE);
        southLeft.add(tooltip,    BorderLayout.NORTH);
        southLeft.add(disclaimer, BorderLayout.SOUTH);

        JPanel southRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        southRight.setBackground(ThemeManager.SURFACE);
        southRight.add(closeBtn);

        south.add(southLeft,  BorderLayout.CENTER);
        south.add(southRight, BorderLayout.EAST);

        // ===== Wire mouse hover on chart to update tooltip =====
        chartPanel.setTooltipLabel(tooltip);

        // ===== Root =====
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(ThemeManager.BG);
        root.add(controls,   BorderLayout.NORTH);
        root.add(chartPanel, BorderLayout.CENTER);
        root.add(legend,     BorderLayout.EAST);
        root.add(south,      BorderLayout.SOUTH);

        return root;
    }

    // =========================================================================
    // CONTROLS PANEL
    // =========================================================================

    private static JPanel buildControls(int[] state, ChartPanel chartPanel, JLabel tooltip) {

        // ===== Two-row layout: row1 = controls, row2 = current-value labels + reset =====
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ThemeManager.SURFACE);
        panel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ThemeManager.BORDER));

        Font lf = new Font("Segoe UI", Font.PLAIN, 12);
        Font vf = new Font("Segoe UI", Font.BOLD,  12);
        Font sf = new Font("Segoe UI", Font.PLAIN, 11);   // small muted sub-labels

        // ===== Default indices =====
        final int DEF_CHARSET = 0;   // Lowercase [26]
        final int DEF_LEN     = 12;
        final int DEF_TIER    = 2;   // Nation State

        // ===== Live value label refs (updated by listeners) =====
        JLabel csVal   = new JLabel(CHARSET_LABELS[state[0]]);
        JLabel pwVal   = new JLabel(state[1] + " chars");
        JLabel tierVal = new JLabel(TIER_NAMES[state[2]]);
        for (JLabel v : new JLabel[]{ csVal, pwVal, tierVal }) {
            v.setForeground(ThemeManager.ACCENT);
            v.setFont(vf);
        }

        // =========================================================
        // ROW 1: selector controls
        // =========================================================
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 6));
        row1.setBackground(ThemeManager.SURFACE);
        row1.setAlignmentX(Component.LEFT_ALIGNMENT);

        // --- Charset combo ---
        JLabel csLbl = new JLabel("Charset:");
        csLbl.setForeground(ThemeManager.TEXT_MUTED);
        csLbl.setFont(lf);

        JComboBox<String> csBox = new JComboBox<>(CHARSET_LABELS);
        csBox.setBackground(ThemeManager.SURFACE2);
        csBox.setForeground(ThemeManager.TEXT);
        csBox.setFont(lf);
        // ===== ROOT CAUSE FIX: ActionListener reads getSelectedIndex() which returns -1
        //       on some LAFs when a custom renderer is installed.
        //       ListDataListener on the model bypasses the renderer entirely and
        //       always returns the correct index. =====
        csBox.getModel().addListDataListener(new javax.swing.event.ListDataListener() {
            public void intervalAdded  (javax.swing.event.ListDataEvent e) {}
            public void intervalRemoved(javax.swing.event.ListDataEvent e) {}
            public void contentsChanged(javax.swing.event.ListDataEvent e) {
                int idx = csBox.getSelectedIndex();
                if (idx < 0) return;
                state[0] = idx;
                csVal.setText(CHARSET_LABELS[idx]);
                tooltip.setText(" ");
                chartPanel.repaint();
            }
        });
        csBox.setSelectedIndex(state[0]);   // set AFTER listener

        // --- Length slider ---
        JLabel pwLbl = new JLabel("Length:");
        pwLbl.setForeground(ThemeManager.TEXT_MUTED);
        pwLbl.setFont(lf);

        JSlider pwSlider = new JSlider(10, 16, state[1]);
        pwSlider.setBackground(ThemeManager.SURFACE);
        pwSlider.setForeground(ThemeManager.TEXT_MUTED);
        pwSlider.setSnapToTicks(true);
        pwSlider.setMajorTickSpacing(2);
        pwSlider.setMinorTickSpacing(1);
        pwSlider.setPaintTicks(true);
        pwSlider.setPreferredSize(new Dimension(170, 38));
        pwSlider.addChangeListener(e -> {
            state[1] = pwSlider.getValue();
            pwVal.setText(state[1] + " chars");
            tooltip.setText(" ");
            chartPanel.repaint();
        });

        // --- Attacker tier combo ---
        JLabel tierLbl = new JLabel("Attacker:");
        tierLbl.setForeground(ThemeManager.TEXT_MUTED);
        tierLbl.setFont(lf);

        JComboBox<String> tierBox = new JComboBox<>(TIER_NAMES);
        tierBox.setBackground(ThemeManager.SURFACE2);
        tierBox.setForeground(ThemeManager.TEXT);
        tierBox.setFont(lf);
        // ===== Same model-listener fix as charset =====
        tierBox.getModel().addListDataListener(new javax.swing.event.ListDataListener() {
            public void intervalAdded  (javax.swing.event.ListDataEvent e) {}
            public void intervalRemoved(javax.swing.event.ListDataEvent e) {}
            public void contentsChanged(javax.swing.event.ListDataEvent e) {
                int idx = tierBox.getSelectedIndex();
                if (idx < 0) return;
                state[2] = idx;
                tierVal.setText(TIER_NAMES[idx]);
                tooltip.setText(" ");
                chartPanel.repaint();
            }
        });
        tierBox.setSelectedIndex(state[2]);  // set AFTER listener

        // ===== Themed renderer: applied AFTER listener so it doesn't interfere =====
        DefaultListCellRenderer themedRenderer = new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setBackground(isSelected ? ThemeManager.SELECT : ThemeManager.SURFACE2);
                setForeground(ThemeManager.TEXT);
                setFont(lf);
                setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
                return this;
            }
        };
        csBox.setRenderer(themedRenderer);
        tierBox.setRenderer(themedRenderer);

        row1.add(csLbl);
        row1.add(csBox);
        row1.add(Box.createHorizontalStrut(8));
        row1.add(pwLbl);
        row1.add(pwSlider);
        row1.add(Box.createHorizontalStrut(8));
        row1.add(tierLbl);
        row1.add(tierBox);

        // =========================================================
        // ROW 2: live value labels + reset-to-default button
        // =========================================================
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        row2.setBackground(ThemeManager.SURFACE);
        row2.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Small muted "current:" prefix labels
        JLabel csCaption   = new JLabel("Charset:");
        JLabel pwCaption   = new JLabel("Length:");
        JLabel tierCaption = new JLabel("Attacker:");
        for (JLabel cap : new JLabel[]{ csCaption, pwCaption, tierCaption }) {
            cap.setForeground(ThemeManager.TEXT_MUTED);
            cap.setFont(sf);
        }

        // Reset button snaps everything back to defaults
        JButton resetBtn = new JButton("Reset to defaults");
        resetBtn.setFont(sf);
        ThemeManager.styleSurfaceButton(resetBtn);
        resetBtn.addActionListener(e -> {
            // ===== Updating the combo triggers the model listener which updates state[] =====
            csBox.setSelectedIndex(DEF_CHARSET);
            pwSlider.setValue(DEF_LEN);
            tierBox.setSelectedIndex(DEF_TIER);
        });

        row2.add(csCaption);
        row2.add(csVal);
        row2.add(Box.createHorizontalStrut(16));
        row2.add(pwCaption);
        row2.add(pwVal);
        row2.add(Box.createHorizontalStrut(16));
        row2.add(tierCaption);
        row2.add(tierVal);
        row2.add(Box.createHorizontalStrut(24));
        row2.add(resetBtn);

        panel.add(row1);
        panel.add(row2);
        return panel;
    }

    private static JPanel buildLegend() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ThemeManager.SURFACE);
        panel.setBorder(new CompoundBorder(
            BorderFactory.createMatteBorder(0, 1, 0, 0, ThemeManager.BORDER),
            BorderFactory.createEmptyBorder(16, 12, 12, 12)
        ));
        panel.setPreferredSize(new Dimension(240, 0));

        JLabel title = new JLabel("Profiles");
        title.setFont(new Font("Segoe UI", Font.BOLD, 12));
        title.setForeground(ThemeManager.TEXT_MUTED);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createVerticalStrut(8));

        // ===== One legend entry per profile =====
        // maxHeight raised to 30 so two-line HTML labels are never clipped =====
        for (Profile p : ALL_PROFILES) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            row.setBackground(ThemeManager.SURFACE);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

            // Swatch showing line color + dash pattern
            JComponent swatch = new JComponent() {
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                        RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(p.lineColor);
                    Stroke stroke = p.dash != null
                        ? new BasicStroke(2.5f, BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND, 1f, p.dash, 0f)
                        : new BasicStroke(2.5f, BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND);
                    g2.setStroke(stroke);
                    g2.drawLine(0, getHeight() / 2, getWidth(), getHeight() / 2);
                    g2.dispose();
                }
            };
            swatch.setPreferredSize(new Dimension(28, 14));

            // ===== Plain text label -- no <html> wrapping which silently clips
            //       at maxHeight. Truncate long names manually instead =====
            String name = p.label.length() > 28 ? p.label.substring(0, 26) + ".." : p.label;
            JLabel lbl = new JLabel(name);
            lbl.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            lbl.setForeground(ThemeManager.TEXT);
            lbl.setToolTipText(p.label); // full name on hover

            row.add(swatch);
            row.add(lbl);
            panel.add(row);
        }

        panel.add(Box.createVerticalStrut(16));

        // ===== Reference threshold lines legend =====
        JLabel refTitle = new JLabel("Reference lines");
        refTitle.setFont(new Font("Segoe UI", Font.BOLD, 12));
        refTitle.setForeground(ThemeManager.TEXT_MUTED);
        refTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(refTitle);
        panel.add(Box.createVerticalStrut(6));

        String[][] refs = {
            { "1 day",             "#E24B4A" },
            { "1 year",            "#C4882A" },
            { "100 years",         "#378ADD" },
            { "Age of universe",   "#7F77DD" },
        };

        for (String[] r : refs) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            row.setBackground(ThemeManager.SURFACE);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

            Color c = Color.decode(r[1]);
            JComponent swatch = new JComponent() {
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(c);
                    g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 3f}, 0f));
                    g2.drawLine(0, getHeight() / 2, getWidth(), getHeight() / 2);
                    g2.dispose();
                }
            };
            swatch.setPreferredSize(new Dimension(28, 14));

            JLabel lbl = new JLabel(r[0]);
            lbl.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            lbl.setForeground(ThemeManager.TEXT_MUTED);

            row.add(swatch);
            row.add(lbl);
            panel.add(row);
        }

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    // =========================================================================
    // CHART PANEL -- all rendering in paintComponent
    // =========================================================================

    /**
     * Custom JPanel that paints a log-scale line chart using Java2D.
     *
     * X axis: password length 10-20
     * Y axis: log10(avg crack seconds), 0-32
     *
     * Reference thresholds drawn as horizontal dashed lines.
     * Profile lines drawn with per-profile color and dash pattern.
     * Mouse hover shows crack time for the nearest x position.
     */
    static class ChartPanel extends JPanel {

        private final int[]   state;           // [charsetIdx, pwLen, tierIdx]
        private JLabel        tooltipLabel;    // updated on mouse move

        // ===== Chart geometry (computed in paintComponent) =====
        private int    chartX, chartY, chartW, chartH;

        // Password lengths plotted on x axis
        private static final int   LEN_MIN = 10;
        private static final int   LEN_MAX = 16;
        private static final int   LEN_COUNT = LEN_MAX - LEN_MIN + 1;

        // Y axis: log10(seconds), 0 (instant) to 32 (~10^22 yr)
        private static final double Y_MIN = 0.0;
        private static final double Y_MAX = 22.0;

        // Reference thresholds (log10 seconds)
        private static final double REF_DAY   = Math.log10(86_400);
        private static final double REF_YEAR  = Math.log10(365.25 * 86_400);
        private static final double REF_100YR = Math.log10(100 * 365.25 * 86_400);
        // Age of universe in log10 seconds
        private static final double REF_UNIV  = AGE_UNIV_LOG10;

        // Track last hovered x for tooltip
        private int hoverX = -1;

        ChartPanel(int[] state) {
            this.state = state;
            setBackground(ThemeManager.SURFACE2);
            setPreferredSize(new Dimension(720, 500));

            // ===== Mouse listener for hover tooltips =====
            addMouseMotionListener(new MouseMotionAdapter() {
                public void mouseMoved(MouseEvent e) {
                    hoverX = e.getX();
                    repaint();
                }
            });
            addMouseListener(new MouseAdapter() {
                public void mouseExited(MouseEvent e) {
                    hoverX = -1;
                    if (tooltipLabel != null) tooltipLabel.setText(" ");
                    repaint();
                }
            });
        }

        void setTooltipLabel(JLabel label) {
            this.tooltipLabel = label;
        }

        // ===== Coordinate helpers =====

        /** Maps password length to pixel x within chart area. */
        private float xForLen(int len) {
            float frac = (float)(len - LEN_MIN) / (LEN_COUNT - 1);
            return chartX + frac * chartW;
        }

        /** Maps log10(seconds) to pixel y within chart area. */
        private float yForLog(double logS) {
            float frac = (float)((logS - Y_MIN) / (Y_MAX - Y_MIN));
            // Invert: larger logS = higher up on screen
            return chartY + chartH - frac * chartH;
        }

        // ===== Main paint method =====
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int W = getWidth();
            int H = getHeight();

            // ===== Chart insets: space for axis labels =====
            int LEFT   = 72;
            int RIGHT  = 16;
            int TOP    = 16;
            int BOTTOM = 36;

            chartX = LEFT;
            chartY = TOP;
            chartW = W - LEFT - RIGHT;
            chartH = H - TOP - BOTTOM;

            // ===== Background =====
            g2.setColor(ThemeManager.SURFACE2);
            g2.fillRect(0, 0, W, H);

            // ===== Chart area background =====
            g2.setColor(ThemeManager.SURFACE);
            g2.fillRect(chartX, chartY, chartW, chartH);

            drawGrid(g2);
            drawRefLines(g2);
            drawLines(g2);
            drawAxes(g2, BOTTOM);
            drawHoverLine(g2);

            g2.dispose();
        }

        /** Draws horizontal grid lines at every 2 log10 steps. */
        private void drawGrid(Graphics2D g2) {
            g2.setColor(new Color(ThemeManager.BORDER.getRed(),
                                  ThemeManager.BORDER.getGreen(),
                                  ThemeManager.BORDER.getBlue(), 80));
            g2.setStroke(new BasicStroke(0.5f));

            for (int y = 0; y <= Y_MAX; y += 2) {
                float py = yForLog(y);
                g2.drawLine(chartX, (int) py, chartX + chartW, (int) py);
            }

            // Vertical grid lines at each length step
            for (int len = LEN_MIN; len <= LEN_MAX; len++) {
                float px = xForLen(len);
                g2.drawLine((int) px, chartY, (int) px, chartY + chartH);
            }
        }

        /**
         * Draws four horizontal reference threshold lines:
         *   1 day, 1 year, 100 years, age of universe.
         * Each gets a label on the right edge of the chart.
         */
        private void drawRefLines(Graphics2D g2) {
            double[][] refs = {
                { REF_DAY,   0xE24B4A },
                { REF_YEAR,  0xC4882A },
                { REF_100YR, 0x378ADD },
                { REF_UNIV,  0x7F77DD },
            };
            String[] refLabels = { "1 day", "1 year", "100 yr", "Univ. age" };

            g2.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_ROUND, 1f, new float[]{5f, 4f}, 0f));

            Font refFont = new Font("Segoe UI", Font.PLAIN, 10);
            g2.setFont(refFont);
            FontMetrics fm = g2.getFontMetrics();

            for (int i = 0; i < refs.length; i++) {
                double logVal = refs[i][0];
                // Clamp to visible Y range
                if (logVal < Y_MIN || logVal > Y_MAX) continue;

                Color c = new Color((int) refs[i][1]);
                g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 160));
                float py = yForLog(logVal);
                g2.drawLine(chartX, (int) py, chartX + chartW, (int) py);

                // Label at right edge
                g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 210));
                int sw = fm.stringWidth(refLabels[i]);
                g2.drawString(refLabels[i],
                    chartX + chartW - sw - 3,
                    (int) py - 3);
            }
        }

        /**
         * Draws one polyline per KDF profile.
         * state[1] = selected length from slider.
         * Lines are drawn at reduced alpha; the selected length column gets a
         * highlight band and every profile dot at that length is drawn filled
         * and larger so the slider has an obvious visible effect.
         */
        private void drawLines(Graphics2D g2) {
            int charset   = CHARSET_SIZES[state[0]];
            int tierIdx   = state[2];
            int selLen    = state[1]; // ===== selected length from slider =====

            // ===== Selected-length highlight band drawn BEFORE the lines =====
            float selX = xForLen(selLen);
            float bandW = (chartW / (float)(LEN_COUNT - 1)) * 0.6f;
            g2.setColor(new Color(
                ThemeManager.ACCENT.getRed(),
                ThemeManager.ACCENT.getGreen(),
                ThemeManager.ACCENT.getBlue(), 22));
            g2.fillRect((int)(selX - bandW / 2), chartY, (int)bandW, chartH);

            // ===== Vertical accent line at selected length =====
            g2.setColor(new Color(
                ThemeManager.ACCENT.getRed(),
                ThemeManager.ACCENT.getGreen(),
                ThemeManager.ACCENT.getBlue(), 90));
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_ROUND, 1f, new float[]{5f, 3f}, 0f));
            g2.drawLine((int)selX, chartY, (int)selX, chartY + chartH);

            for (Profile p : ALL_PROFILES) {
                double hps = effectiveHps(p, tierIdx);

                // Build full polyline path
                Path2D path = new Path2D.Float();
                boolean first = true;
                for (int len = LEN_MIN; len <= LEN_MAX; len++) {
                    double logS = crackLog10(len, charset, hps);
                    float  px   = xForLen(len);
                    float  py   = yForLog(logS);
                    py = Math.max(chartY, Math.min(chartY + chartH, py));
                    if (first) { path.moveTo(px, py); first = false; }
                    else        path.lineTo(px, py);
                }

                // ===== Line drawn at reduced opacity so selected column stands out =====
                Color lineCol = new Color(
                    p.lineColor.getRed(),
                    p.lineColor.getGreen(),
                    p.lineColor.getBlue(), 160);
                BasicStroke stroke = p.dash != null
                    ? new BasicStroke(2.0f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND, 1f, p.dash, 0f)
                    : new BasicStroke(2.0f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND);
                g2.setStroke(stroke);
                g2.setColor(lineCol);
                g2.draw(path);

                // ===== Small dim dot at every data point =====
                g2.setStroke(new BasicStroke(1.2f));
                for (int len = LEN_MIN; len <= LEN_MAX; len++) {
                    double logS = crackLog10(len, charset, hps);
                    float  px   = xForLen(len);
                    float  py   = yForLog(logS);
                    py = Math.max(chartY, Math.min(chartY + chartH, py));
                    g2.setColor(ThemeManager.SURFACE);
                    g2.fillOval((int) px - 3, (int) py - 3, 6, 6);
                    g2.setColor(lineCol);
                    g2.drawOval((int) px - 3, (int) py - 3, 6, 6);
                }

                // ===== SELECTED LENGTH: large filled dot + crack time label =====
                double selLogS = crackLog10(selLen, charset, hps);
                float  selPy   = yForLog(selLogS);
                selPy = Math.max(chartY + 6, Math.min(chartY + chartH - 6, selPy));

                // Filled dot, full opacity, larger
                g2.setColor(ThemeManager.SURFACE);
                g2.fillOval((int)selX - 6, (int)selPy - 6, 12, 12);
                g2.setColor(p.lineColor);
                g2.setStroke(new BasicStroke(2.0f));
                g2.fillOval((int)selX - 5, (int)selPy - 5, 10, 10);

                // ===== Crack time label to the right of the dot =====
                // Stagger labels vertically by profile index to avoid overlap
                int pidx = java.util.Arrays.asList(ALL_PROFILES).indexOf(p);
                Font labelFont = new Font("Segoe UI", Font.PLAIN, 10);
                g2.setFont(labelFont);
                FontMetrics fm = g2.getFontMetrics();
                String timeStr = fmtLog(selLogS);
                int labelX = (int)selX + 9;
                // ===== If near right edge, flip label to the left =====
                if (labelX + fm.stringWidth(timeStr) > chartX + chartW - 4)
                    labelX = (int)selX - fm.stringWidth(timeStr) - 9;
                // ===== Vertical spread: 14px apart, centered on dot =====
                int spread    = ALL_PROFILES.length * 14;
                int labelY    = (int)selPy - spread / 2 + pidx * 14 + fm.getAscent();
                labelY = Math.max(chartY + fm.getAscent(), Math.min(chartY + chartH, labelY));
                g2.setColor(p.lineColor);
                g2.drawString(timeStr, labelX, labelY);
            }
        }

        /** Draws x and y axis labels. */
        private void drawAxes(Graphics2D g2, int bottomInset) {
            Font axFont = new Font("Segoe UI", Font.PLAIN, 11);
            g2.setFont(axFont);
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(ThemeManager.TEXT_MUTED);

            // ===== X axis: password lengths -- selected length drawn in ACCENT bold =====
            int selLen = state[1];
            for (int len = LEN_MIN; len <= LEN_MAX; len++) {
                float px = xForLen(len);
                String s = String.valueOf(len);
                boolean isSel = (len == selLen);
                g2.setFont(isSel
                    ? new Font("Segoe UI", Font.BOLD,  12)
                    : axFont);
                g2.setColor(isSel ? ThemeManager.ACCENT : ThemeManager.TEXT_MUTED);
                int sw = g2.getFontMetrics().stringWidth(s);
                g2.drawString(s, (int) px - sw / 2, chartY + chartH + 18);
            }
            g2.setFont(axFont);
            g2.setColor(ThemeManager.TEXT_MUTED);

            // X axis label
            String xLabel = "Password length (chars)";
            int sw = fm.stringWidth(xLabel);
            g2.drawString(xLabel,
                chartX + (chartW - sw) / 2,
                chartY + chartH + bottomInset - 2);

            // ===== Y axis: log10 seconds with human labels =====
            // String[] yTicks = {
            //     "0",  "2",  "4",  "6",  "8",  "10",
            //     "12", "14", "16", "18", "20", "22",
            //     "24", "26", "28", "30", "32"
            // };
            // String[] yHuman = {
            //     "1 sec",  "~2 min",  "~3 hr",   "~12 days","~3 yr",   "~320 yr",
            //     "~32Kyr", "~3Myr",   "~320Myr", "~32Byr",  "~3Tyr",   "~300Tyr",
            //     "10^14yr","10^16yr", "10^18yr",  "10^20yr", "10^22yr"
            // };

            String[] yTicks = {
                "0",  "2",  "4",  "6",  "8",  "10",
                "12", "14", "16", "18", "20", "22"
            };
            String[] yHuman = {
                "1 sec",  "~2 min",  "~3 hr",   "~12 days","~3 yr",   "~320 yr",
                "~32Kyr", "~3Myr",   "~320Myr", "~32Byr",  "~3Tyr",   "~300Tyr"
            };

            for (int i = 0; i < yTicks.length; i++) {
                double logVal = Double.parseDouble(yTicks[i]);
                float  py     = yForLog(logVal);
                if (py < chartY || py > chartY + chartH) continue;

                // Short tick
                g2.setColor(ThemeManager.BORDER);
                g2.setStroke(new BasicStroke(0.8f));
                g2.drawLine(chartX - 4, (int) py, chartX, (int) py);

                // Label -- use human form
                g2.setColor(ThemeManager.TEXT_MUTED);
                String lbl = yHuman[i];
                int lw = fm.stringWidth(lbl);
                g2.drawString(lbl, chartX - lw - 6, (int) py + fm.getAscent() / 2 - 1);
            }

            // ===== Axis border lines =====
            g2.setColor(ThemeManager.BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.drawLine(chartX, chartY, chartX, chartY + chartH);
            g2.drawLine(chartX, chartY + chartH, chartX + chartW, chartY + chartH);
        }

        /**
         * Draws a vertical hover line at the nearest x tick and updates
         * the tooltip label with crack times for all profiles at that length.
         */
        private void drawHoverLine(Graphics2D g2) {
            if (hoverX < chartX || hoverX > chartX + chartW) return;

            // ===== Snap hoverX to nearest length tick =====
            float frac  = (float)(hoverX - chartX) / chartW;
            int   len   = Math.round(frac * (LEN_COUNT - 1)) + LEN_MIN;
            len = Math.max(LEN_MIN, Math.min(LEN_MAX, len));
            float snapX = xForLen(len);

            // ===== Vertical line =====
            g2.setColor(new Color(ThemeManager.ACCENT.getRed(),
                                  ThemeManager.ACCENT.getGreen(),
                                  ThemeManager.ACCENT.getBlue(), 100));
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 3f}, 0f));
            g2.drawLine((int) snapX, chartY, (int) snapX, chartY + chartH);

            // ===== Highlighted dot on each profile line at this length =====
            int charset = CHARSET_SIZES[state[0]];
            int tierIdx = state[2];

            StringBuilder sb = new StringBuilder();
            sb.append("Length ").append(len).append(" chars:  ");

            for (Profile p : ALL_PROFILES) {
                double hps  = effectiveHps(p, tierIdx);
                double logS = crackLog10(len, charset, hps);
                float  py   = yForLog(logS);
                py = Math.max(chartY, Math.min(chartY + chartH, py));

                // Filled dot on hover
                g2.setColor(ThemeManager.SURFACE);
                g2.fillOval((int) snapX - 5, (int) py - 5, 10, 10);
                g2.setColor(p.lineColor);
                g2.setStroke(new BasicStroke(2f));
                g2.drawOval((int) snapX - 5, (int) py - 5, 10, 10);

                sb.append(p.label).append(": ").append(fmtLog(logS)).append("   ");
            }

            // ===== Update tooltip label outside chart =====
            if (tooltipLabel != null) {
                tooltipLabel.setText(sb.toString());
            }
        }
    }
}
