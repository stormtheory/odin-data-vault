import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

public class Odin {

// ===== CONFIG =====
    
    public  static final String DATABASE_VER       = "0";
    private static int          PASSWORD_LENGTH      = 14;
    public  static long         IDLE_TIMEOUT_MINUTES = 10;
    private static final int    CLIPBOARD_CLEAR_MS = 30_000; // 30 seconds
    private static final int    BUFFER_SIZE = 8 * 1024;

// =========== FILTER =================
    private JTextField searchField;
    private String     currentSearchQuery = "";
    private boolean    suppressSearchListener = false;

// ===== FIELDS WRITTEN BY Login.finishStartup() - must be public =====
    private static String                binaryPath;
    public String                        DATABASE_TYPE;
    public String                        username     = "single-user";
    public boolean                       arg_VaultLevel = false;
    public String                        VaultLevel;
    public Connection                    conn;
    public ImageIcon                     dialogIcon   = null;
    public ImageIcon                     appIcon      = null;
    public List<Yggdrasil.Credential>    credentials  = new ArrayList<>();
    public Yggdrasil                     backend      = new Yggdrasil();
    public static boolean                DEBUG;

// import
    private static String shasumData;
    private static String shasumFile;
    private static char[] base64Encoded;
    private static Boolean binaryImportSuccess;

// ===== STATIC SHARED FIELDS =====
    protected static Mimir               databaseutilities = new Mimir();
    protected static int                 addupdate_id;

// ===== MASTER PASSWORD - written by createNewMasterPass(), read by Login =====
    public char[]  masterPassword = new char[0];

// ===== MAIN TABLE =====
    private JTable             table;
    private DefaultTableModel  model;

// ===== SIDEBAR FILTER =====
    private String currentTypeFilter = "account"; // ===== Default to accounts view =====

// ===== DETAIL PANEL =====
    private JPanel detailPanel;
    private JPanel detailContent;

// ===== MAIN FRAME - needed for toast anchoring =====
    private JFrame mainFrame;

// ======= MAIN ====================
    public static void main(String[] args) throws Exception {

        Mimir.configureSQLiteTmpDir();

        // ===== Re-launch with native access enabled if not already set =====
        if (System.getProperty("nativeAccessEnabled") == null) {
            String java      = ProcessHandle.current().info().command().orElse("java");
            String classpath = System.getProperty("java.class.path");
            List<String> cmd = new ArrayList<>(Arrays.asList(
                java,
                "--enable-native-access=ALL-UNNAMED",
                "-Dorg.sqlite.tmpdir=" + System.getProperty("org.sqlite.tmpdir"),
                "-DnativeAccessEnabled=true",
                "-cp", classpath,
                "Odin"
            ));
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            Process p = pb.start();
            System.exit(p.waitFor());
        }

        // ===== LAUNCH LOGIN on background thread =====
        // Login owns full startup - EDT must stay free for Swing rendering
        final String[] finalArgs = args;
        new Thread(() -> {
            try { new Login().start(finalArgs); }
            catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    // ===== BUILD MAIN UI - called by Login.finishStartup() on EDT =====
    // public so Login can call it after crypto init and data load complete
    public void buildMainUI(List<Image> icons) {
        mainFrame = new JFrame("Odin's Runa");
        mainFrame.setBackground(ThemeManager.BG);
        ThemeManager.applyOptionPaneTheme();
        if (DATABASE_TYPE.equals("m")) {
            mainFrame.setTitle("Odin's Runa  -  " + username);
        }
        mainFrame.setSize(1000, 650);
        mainFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // ===== Apply icons - must be done before setVisible =====
        if (!icons.isEmpty()) {
            mainFrame.setIconImages(icons);
        } else {
            System.err.println("[Odin] Warning: No icons loaded - using default Java icon");
        }

        // ===== IDLE TIMEOUT =====
        IdleTimeoutManager idleManager = new IdleTimeoutManager(mainFrame, IDLE_TIMEOUT_MINUTES);
        idleManager.start();

        // ===== TABLE MODEL =====
        // Columns set dynamically per type - see refreshTable()
        model = new DefaultTableModel(new Object[]{"ID", "Type", "Tag", "Username", "Password"}, 0) {
            public boolean isCellEditable(int row, int col) {
                return false; // ===== Nothing editable in the table =====
            }
        };

        table = new JTable(model);
        table.setBackground(ThemeManager.SURFACE);
        table.setForeground(ThemeManager.TEXT);
        table.setSelectionBackground(ThemeManager.SELECT);
        table.setSelectionForeground(ThemeManager.TEXT);
        table.setGridColor(ThemeManager.BORDER);
        table.setRowHeight(36);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.getTableHeader().setBackground(ThemeManager.SURFACE2);
        table.getTableHeader().setForeground(ThemeManager.TEXT_MUTED);
        table.getTableHeader().setReorderingAllowed(false);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 13));

        // ===== DOUBLE-CLICK TO COPY =====
        // Double-clicking any cell copies its decrypted value to clipboard
        table.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (DEBUG) System.out.println("ROW:" + row);
                if (row < 0) return;

                // ===== ROW SELECTION - show detail panel =====
                if (e.getClickCount() == 1) {
                    showDetailPanel(row);
                }

                // ===== DOUBLE CLICK - copy field to clipboard =====
                if (e.getClickCount() == 2) {
                    if (col == 0) return; // ===== Never copy hidden ID column =====
                    copyFieldToClipboard(row, col);
                }
            }
        });

        refreshTable();

        // ===== HIDE ID COLUMN =====
        table.getColumnModel().getColumn(0).setMinWidth(0);
        table.getColumnModel().getColumn(0).setMaxWidth(0);

        // ===== SCROLL PANE =====
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBackground(ThemeManager.BG);
        scroll.getViewport().setBackground(ThemeManager.SURFACE);
        scroll.setBorder(BorderFactory.createLineBorder(ThemeManager.BORDER));

        // ===== DETAIL PANEL =====
        detailPanel   = buildDetailPanel();
        detailContent = (JPanel) ((JScrollPane) detailPanel.getComponent(0)).getViewport().getView();

        // ===== SPLIT PANE - table on top, detail below =====
        JSplitPane verticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll, detailPanel);
        verticalSplit.setResizeWeight(0.65); // ===== 65% to table, 35% to detail =====
        verticalSplit.setDividerSize(4);
        verticalSplit.setBackground(ThemeManager.BG);
        verticalSplit.setBorder(null);

        // ===== SIDEBAR =====
        JPanel sidebar = buildSidebar();

        // ===== BOTTOM TOOLBAR =====
        JPanel toolbar = buildToolbar();

        // ===== SEARCH BAR - sits above the table, filters by tag =====
        JPanel searchPanel = buildSearchBar();

        // ===== MAIN CONTENT =====
        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(ThemeManager.BG);
        center.add(searchPanel,   BorderLayout.NORTH);
        center.add(verticalSplit, BorderLayout.CENTER);
        // JPanel center = new JPanel(new BorderLayout());
        // center.setBackground(ThemeManager.BG);
        // center.add(verticalSplit, BorderLayout.CENTER);



        // ===== ROOT LAYOUT =====
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(ThemeManager.BG);
        root.add(sidebar, BorderLayout.WEST);
        root.add(center,  BorderLayout.CENTER);
        root.add(toolbar, BorderLayout.SOUTH);

        mainFrame.setContentPane(root);
        mainFrame.setVisible(true);
    }

    // ===== SEARCH BAR =====
    /**
     * Builds a centered search field panel rendered above the main table.
     * Filters visible rows by tag column on each keystroke.
     * Placeholder is painted via paintComponent - never touches the document.
     */
    private JPanel buildSearchBar() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setBackground(ThemeManager.SURFACE2);
        panel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ThemeManager.BORDER));

        // ===== INNER WRAPPER - constrains search field to center column =====
        JPanel inner = new JPanel(new BorderLayout(6, 0));
        inner.setBackground(ThemeManager.SURFACE2);
        inner.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));

        // ===== MAGNIFIER ICON LABEL =====
        JLabel iconLabel = new JLabel("🔍");
        iconLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        iconLabel.setForeground(ThemeManager.TEXT_MUTED);

        // ===== PLACEHOLDER TEXTFIELD - paints hint without touching the document =====
        // paintComponent draws hint text when empty - no setText, no focus listener needed
        searchField = new JTextField() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                // ===== Only paint hint when field is empty =====
                if (getText().isEmpty()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(ThemeManager.TEXT_MUTED);
                    g2.setFont(getFont().deriveFont(Font.ITALIC));
                    Insets ins = getInsets();
                    // ===== Vertically center the hint text within the field =====
                    FontMetrics fm = g2.getFontMetrics();
                    int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                    g2.drawString("Search " + currentTypeFilter + " tags...", ins.left + 4, y);
                    g2.dispose();
                }
            }
        };
        searchField.getCaret().setBlinkRate(0);
        searchField.setBackground(ThemeManager.SURFACE2);
        searchField.setForeground(ThemeManager.TEXT);
        searchField.setCaretColor(ThemeManager.ACCENT);
        searchField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ThemeManager.BORDER),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)
        ));
        searchField.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        searchField.setToolTipText("Search by tag...");

        // ===== CLEAR BUTTON - visible only when field has text =====
        JButton clearBtn = new JButton("clear");
        clearBtn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        clearBtn.setForeground(ThemeManager.TEXT_MUTED);
        clearBtn.setBackground(ThemeManager.SURFACE2);
        clearBtn.setBorderPainted(false);
        clearBtn.setFocusPainted(false);
        clearBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clearBtn.setPreferredSize(new Dimension(28, 28));
        clearBtn.setVisible(false); // ===== Hidden until user types =====
        clearBtn.addActionListener(e -> {
            suppressSearchListener = true;
            searchField.setText("");
            suppressSearchListener = false;
            currentSearchQuery = "";
            clearBtn.setVisible(false);
            refreshTable(); // ===== Call through captured outer instance =====
        });

        // ===== LIVE FILTER - fires on every keystroke =====
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void update() {
                // ===== Skip programmatic setText calls =====
                if (suppressSearchListener) return;
                currentSearchQuery = searchField.getText().trim();
                clearBtn.setVisible(!currentSearchQuery.isEmpty());
                filterTable(); // ===== Call through captured outer instance =====
            }
            public void insertUpdate (javax.swing.event.DocumentEvent e) { update(); }
            public void removeUpdate (javax.swing.event.DocumentEvent e) { update(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }
        });

        inner.add(iconLabel,   BorderLayout.WEST);
        inner.add(searchField, BorderLayout.CENTER);
        inner.add(clearBtn,    BorderLayout.EAST);

        // ===== CENTER the inner panel so it doesn't stretch edge-to-edge =====
        panel.add(inner, BorderLayout.CENTER);
        return panel;
    }

    // ===== FILTER TABLE BY TAG =====
    /**
     * Rebuilds the table showing only credentials whose tag contains the search query.
     * Case-insensitive. Falls back to full refreshTable() when query is empty.
     * Does NOT mutate the credentials list - purely a display filter.
     */
    private void filterTable() {
        // ===== Empty query - show everything for the current type =====
        if (currentSearchQuery == null || currentSearchQuery.isEmpty()) {
            refreshTable();
            return;
        }

        String query = currentSearchQuery.toLowerCase();
        Futhark.EntryType type = Futhark.forKey(currentTypeFilter);

        // ===== REBUILD COLUMN HEADERS - same as refreshTable() =====
        String[] headers = Futhark.tableHeaders(type);
        model.setColumnCount(0);
        model.setRowCount(0);
        for (String h : headers) model.addColumn(h);

        // ===== HIDE ID COLUMN =====
        if (!DEBUG) {
            if (table.getColumnModel().getColumnCount() > 0) {
                table.getColumnModel().getColumn(0).setMinWidth(0);
                table.getColumnModel().getColumn(0).setMaxWidth(0);
            }
        }
        // ===== HIDE TYPE COLUMN =====
        if (table.getColumnModel().getColumnCount() > 1) {
            table.getColumnModel().getColumn(1).setMinWidth(0);
            table.getColumnModel().getColumn(1).setMaxWidth(0);
        }

        // ===== POPULATE ONLY MATCHING ROWS =====
        for (Yggdrasil.Credential c : credentials) {
            if (!currentTypeFilter.equals(c.type)) continue;

            // ===== TAG MATCH - case-insensitive substring search =====
            String tag = new String(c.tag).toLowerCase();
            if (!tag.contains(query)) continue; // ===== Skip non-matching rows =====

            Object[] rowData = new Object[headers.length];
            rowData[0] = c.id;
            rowData[1] = (type != null ? type.icon : "") + " " + currentTypeFilter;
            rowData[2] = new String(c.tag);

            if (type != null) {
                for (int i = 0; i < type.tableColumnIndices.length && (i + 3) < headers.length; i++) {
                    int           fieldIdx = type.tableColumnIndices[i];
                    Futhark.Field field    = type.fields.get(fieldIdx);
                    if (field.sensitive || field.password) {
                        rowData[i + 3] = "••••••••"; // ===== Never show sensitive data in table =====
                    } else {
                        byte[] enc = c.getDataField(fieldIdx);
                        if (enc != null) {
                            try {
                                if (DEBUG) System.out.println("Decrypt for Show - Wipe Memory Space after");
                                char[] val = backend.decryptData(enc, c.iv);
                                rowData[i + 3] = new String(val);
                                Yggdrasil.wipeCharArray(val);
                            } catch (Exception e) {
                                rowData[i + 3] = "[error]";
                            }
                        }
                    }
                }
            }
            model.addRow(rowData);
        }
    }

    // ===== SIDEBAR =====
    /**
     * Retractable sidebar.
     * Collapsed: icon-only strip (44px).
     * Expanded: full labels (164px) - animated slide on hover.
     */
    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel() {
            {
                setBackground(ThemeManager.SURFACE);
                setPreferredSize(new Dimension(44, 0)); // ===== Starts collapsed =====
                setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, ThemeManager.BORDER));
            }
        };
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));

        // ===== SIDEBAR ENTRIES - all 7 types =====
        String[][] entries = {
            {"Accounts",     "account"},
            {"Notes",        "note"},
            {"Addresses",    "address"},
            {"Cards",        "card"},
            {"Passkeys",     "passkey"},
            {"SSH Keys",     "ssh"},
            {"VPN Keys",     "vpn"},
            {"Binary Keys",  "binary"},
            {"Docs/Pictures",  "docs"},
        };

        // ===== ANIMATION STATE =====
        int[]     targetWidth  = {44};
        int[]     currentWidth = {44};
        Timer[]   animator     = {null};
        int COLLAPSED_W = 44;
        int EXPANDED_W  = 164;
        int ANIM_STEP   = 12; // ===== Pixels per frame =====

        List<JLabel> labelRefs = new ArrayList<>();

        sidebar.add(Box.createVerticalStrut(12));

        for (String[] entry : entries) {
            String label   = entry[0];
            String typeKey = entry[1];

            JPanel btn = new JPanel(new BorderLayout(6, 0));
            btn.setBackground(ThemeManager.SURFACE);
            btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            btn.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 8));
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            // ===== ICON - pure Java2D, no image files needed =====
            JLabel iconLabel = new JLabel(SidebarIcon.forType(typeKey, 20));
            iconLabel.setPreferredSize(new Dimension(24, 24));

            JLabel textLabel = new JLabel(label);
            textLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            textLabel.setForeground(ThemeManager.TEXT);
            textLabel.setVisible(false); // ===== Hidden when collapsed =====
            labelRefs.add(textLabel);

            btn.add(iconLabel, BorderLayout.WEST);
            btn.add(textLabel, BorderLayout.CENTER);

            btn.addMouseListener(new MouseAdapter() {
                // ===== CLICK - switch category =====
                public void mouseClicked(MouseEvent e) {
                    currentTypeFilter = typeKey;
                    for (Component c : sidebar.getComponents()) {
                        if (c instanceof JPanel p && p != sidebar)
                            p.setBackground(ThemeManager.SURFACE);
                    }
                    btn.setBackground(ThemeManager.SELECT);
                    refreshTable();
                }

                // ===== HOVER - expand sidebar =====
                public void mouseEntered(MouseEvent e) {
                    targetWidth[0] = EXPANDED_W;
                    startSidebarAnim(sidebar, animator, currentWidth, targetWidth,
                                     ANIM_STEP, labelRefs, COLLAPSED_W);
                }
            });

            sidebar.add(btn);
            sidebar.add(Box.createVerticalStrut(2));
        }

        sidebar.add(Box.createVerticalGlue());

        // ===== COLLAPSE ON MOUSE EXIT =====
        sidebar.addMouseListener(new MouseAdapter() {
            public void mouseExited(MouseEvent e) {
                if (!sidebar.getBounds().contains(
                        SwingUtilities.convertPoint(sidebar, e.getPoint(), sidebar.getParent()))) {
                    targetWidth[0] = COLLAPSED_W;
                    startSidebarAnim(sidebar, animator, currentWidth, targetWidth,
                                     ANIM_STEP, labelRefs, COLLAPSED_W);
                }
            }
        });

        return sidebar;
    }

    /**
     * Drives the sidebar slide animation.
     * Reuses the existing timer if already running - avoids stacking timers.
     */
    private void startSidebarAnim(JPanel sidebar, Timer[] animRef, int[] current,
                                   int[] target, int step, List<JLabel> labels, int collapsedW) {
        if (animRef[0] != null) animRef[0].stop();
        animRef[0] = new Timer(12, e -> {
            int diff = target[0] - current[0];
            if (Math.abs(diff) <= step) {
                current[0] = target[0];
                ((Timer) e.getSource()).stop();
            } else {
                current[0] += (diff > 0 ? step : -step);
            }
            sidebar.setPreferredSize(new Dimension(current[0], 0));
            boolean showLabels = current[0] > collapsedW + 30;
            for (JLabel lbl : labels) lbl.setVisible(showLabels);
            sidebar.revalidate();
            sidebar.repaint();
        });
        animRef[0].start();
    }

    // ===== BOTTOM TOOLBAR =====
    private JPanel buildToolbar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        panel.setBackground(ThemeManager.SURFACE);
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ThemeManager.BORDER));

        JButton addBtn              = new JButton("＋ Add Secret");
        JButton delBtn              = new JButton("🗑 Delete Selected");
        JButton updateBtn           = new JButton("Update");
        JButton changeMasterPassBtn = new JButton("🔐 Account: " + username);

        ThemeManager.styleAccentButton(addBtn);
        ThemeManager.styleAccentButton(updateBtn);
        ThemeManager.styleDangerButton(delBtn);
        ThemeManager.styleSurfaceButton(changeMasterPassBtn);

        addBtn.addActionListener(e              -> addUpdateEntry("add"));
        updateBtn.addActionListener(e           -> addUpdateEntry("update"));
        delBtn.addActionListener(e              -> deleteEntry());
        changeMasterPassBtn.addActionListener(e -> changeMasterPass(username));

        panel.add(addBtn);
        panel.add(updateBtn);
        panel.add(delBtn);
        panel.add(changeMasterPassBtn);

        // ===== IMPORT / EXPORT BUTTON =====
        JButton importExportBtn = new JButton("⇅ Import / Export(Backup)");
        ThemeManager.styleSurfaceButton(importExportBtn);
        importExportBtn.addActionListener(e -> {
            ImportExport ie = new ImportExport(backend, conn, DATABASE_TYPE, DEBUG);
            ie.showImportExportDialog(mainFrame, credentials, () -> {
                // ===== Called after successful import - reload and refresh =====
                try {
                    credentials.clear();
                    credentials.addAll(backend.loadAll(conn));
                    refreshTable();
                } catch (Exception ex) {
                    ex.printStackTrace();
                    ToastManager.error(mainFrame, "Failed to reload after import.");
                }
            });
        });
        panel.add(importExportBtn);

        // ===== MULTI-USER CONTROLS - only visible for multi-user vault =====
        if (DATABASE_TYPE.equals("m")) {
            JButton useraddBtn = new JButton("👤＋ Add User");
            JButton userdelBtn = new JButton("👤✕ Del User");
            ThemeManager.styleSurfaceButton(useraddBtn);
            ThemeManager.styleDangerButton(userdelBtn);
            useraddBtn.addActionListener(e -> useraddEntry(username));
            userdelBtn.addActionListener(e -> userdelEntry(username));
            panel.add(useraddBtn);
            panel.add(userdelBtn);
        }

        return panel;
    }

    // ===== DETAIL PANEL =====
    /**
     * Collapsible detail panel below the table.
     * Content rebuilt on each row selection.
     */
    private JPanel buildDetailPanel() {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(ThemeManager.SURFACE);
        wrapper.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ThemeManager.BORDER));

        detailContent = new JPanel();
        detailContent.setBackground(ThemeManager.SURFACE);
        detailContent.setLayout(new BoxLayout(detailContent, BoxLayout.Y_AXIS));

        // ===== Placeholder shown before any row is selected =====
        JLabel placeholder = new JLabel("Select a row to view details");
        placeholder.setForeground(ThemeManager.TEXT_MUTED);
        placeholder.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        placeholder.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        detailContent.add(placeholder);

        JScrollPane scroll = new JScrollPane(detailContent);
        scroll.setBorder(null);
        scroll.setBackground(ThemeManager.SURFACE);
        scroll.getViewport().setBackground(ThemeManager.SURFACE);

        wrapper.add(scroll, BorderLayout.CENTER);
        wrapper.setPreferredSize(new Dimension(0, 160));
        return wrapper;
    }

    /**
     * Populates the detail panel for the selected row.
     * Sensitive fields show with a Show/Hide button that decrypts on demand.
     */
    private void showDetailPanel(int row) {
        if (row < 0 || row >= credentials.size()) return;

        int credIndex = visibleRowToCredentialIndex(row);
        if (credIndex < 0) return;

        Yggdrasil.Credential c    = credentials.get(credIndex);
        Futhark.EntryType    type = Futhark.forKey(c.type);
        if (DEBUG) System.out.println("Detail type: " + type + "  ID: " + c.id + "  C: " + c.type);

        detailContent.removeAll();
        detailContent.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));

        // ===== HEADER - entry type + tag =====
        JLabel typeLabel = new JLabel(
            (type != null ? type.icon + " " + type.displayName : "Entry") + "  -  " + new String(c.tag));
        typeLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
        typeLabel.setForeground(ThemeManager.ACCENT);
        typeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        detailContent.add(typeLabel);
        detailContent.add(Box.createVerticalStrut(10));

        if (type == null) {
            addDetailField("Tag", new String(c.tag), false, row, -1);
        } else {
            for (int i = 0; i < type.fields.size(); i++) {
                Futhark.Field field    = type.fields.get(i);
                byte[]        encBytes = c.getDataField(i);
                if (encBytes == null) continue; // ===== Skip unpopulated fields =====

                if (field.multiline) {
                    detailContent.add(Box.createVerticalStrut(6));
                    JSeparator sep = new JSeparator();
                    sep.setForeground(ThemeManager.BORDER);
                    sep.setAlignmentX(Component.LEFT_ALIGNMENT);
                    detailContent.add(sep);
                    detailContent.add(Box.createVerticalStrut(6));
                    if (field.sensitive || field.password) {
                        if (type.typeKey.equals("docs")||type.typeKey.equals("binary")){
                            Yggdrasil.Credential fieldRefs = c;
                            byte[] data;
                            if (type.typeKey.equals("binary")) {data = fieldRefs.getDataField(3);} else {data = fieldRefs.getDataField(1);}
                            addDetailSensitiveField(field.label, encBytes, c.iv, row, i, type.typeKey, false, data);
                            } else {addDetailSensitiveField(field.label, encBytes, c.iv, row, i, type.typeKey, false, null);}
                    } else {
                        try {
                            char[] value = backend.decryptData(encBytes, c.iv);
                            addDetailField(field.label, new String(value), true, row, i);
                        } catch (Exception e) {
                            addDetailField(field.label, "[decrypt error]", false, row, i);
                        }
                    }
                } else if (field.sensitive || field.password) {
                    // ===== SENSITIVE FIELD - masked with Show/Hide button =====
                    if (type.typeKey.equals("docs")||type.typeKey.equals("binary")){
                        Yggdrasil.Credential fieldRefs = c;
                        byte[] data;
                        if (type.typeKey.equals("binary")) {data = fieldRefs.getDataField(3);} else {data = fieldRefs.getDataField(1);}
                        addDetailSensitiveField(field.label, encBytes, c.iv, row, i, type.typeKey, false, data);
                        } else {addDetailSensitiveField(field.label, encBytes, c.iv, row, i, type.typeKey, false, null);}
                } else {
                    // ===== PLAIN FIELD - decrypt and show directly =====
                    try {
                        if (DEBUG) System.out.println("Decrypt for Show - Wipe Memory Space after");
                        char[] value = backend.decryptData(encBytes, c.iv);
                        addDetailField(field.label, new String(value), field.multiline, row, i);
                        Yggdrasil.wipeCharArray(value);
                    } catch (Exception e) {
                        addDetailField(field.label, "[decrypt error]", false, row, i);
                    }
                }
            }
        }
        detailContent.revalidate();
        detailContent.repaint();
    }

    /** Adds a plain (non-sensitive) field row to the detail panel. */
    private void addDetailField(String label, String value, boolean multiline, int row, int fieldIdx) {
        JPanel row2 = new JPanel(new BorderLayout(8, 0));
        row2.setBackground(ThemeManager.SURFACE);
        row2.setAlignmentX(Component.LEFT_ALIGNMENT);
        row2.setMaximumSize(new Dimension(Integer.MAX_VALUE, multiline ? 100 : 26));

        JLabel lbl = new JLabel(label + ":");
        lbl.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lbl.setForeground(ThemeManager.TEXT_MUTED);
        lbl.setPreferredSize(new Dimension(110, 0));

        JButton copyClipBtn = new JButton("Copy");
        copyClipBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        ThemeManager.styleSurfaceButton(copyClipBtn);
        copyClipBtn.setPreferredSize(new Dimension(80, 24));

        if (multiline) {
            JTextArea ta = new JTextArea(value);
            ta.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            ta.setForeground(ThemeManager.TEXT);
            ta.setBackground(ThemeManager.SURFACE2);
            ta.setEditable(false);
            ta.setLineWrap(true);
            ta.setWrapStyleWord(true);
            ta.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            row2.add(lbl,         BorderLayout.WEST);
            row2.add(ta,          BorderLayout.CENTER);
            row2.add(copyClipBtn, BorderLayout.EAST);
        } else {
            JLabel val = new JLabel(value);
            val.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            val.setForeground(ThemeManager.TEXT);
            row2.add(lbl,         BorderLayout.WEST);
            row2.add(val,         BorderLayout.CENTER);
            row2.add(copyClipBtn, BorderLayout.EAST);
        }
        copyClipBtn.addActionListener(e -> {
            copySecretToClipBoard(value);
            ToastManager.show(mainFrame, " copied  -  clipboard clears in 60s",
                ToastManager.ToastType.SUCCESS, 10_000);
        });
        detailContent.add(row2);
        detailContent.add(Box.createVerticalStrut(4));
    }

    /** Adds a sensitive field row to the detail panel with a Show/Hide button. */
    private void addDetailSensitiveField(String label, byte[] encBytes, byte[] iv,int row, int fieldIdx, String keytype, boolean multiline, byte[] data) {
        JPanel fieldRow = new JPanel(new BorderLayout(8, 0));
        fieldRow.setBackground(ThemeManager.SURFACE);
        fieldRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        fieldRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, multiline ? 100 : 32));

        // ===== JTextArea handles both single and multiline without branching =====
        JTextArea masked = new JTextArea("••••••••••••");
        masked.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        masked.setForeground(ThemeManager.TEXT_MUTED);
        masked.setEditable(false);
        masked.setOpaque(false);
        masked.setBorder(null);
        masked.setFocusable(false); // ===== Prevent tab focus on read-only field =====
        masked.setLineWrap(multiline);
        masked.setWrapStyleWord(multiline);

        // ===== SCROLL WRAPPER - transparent, borderless =====
        JScrollPane scroll = new JScrollPane(masked);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setVerticalScrollBarPolicy(multiline
            ? JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            : JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        JLabel lbl = new JLabel(label + ":");
        lbl.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lbl.setForeground(ThemeManager.TEXT_MUTED);
        lbl.setPreferredSize(new Dimension(110, 0));

        JButton copyClipBtn = new JButton("Copy");
        copyClipBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        ThemeManager.styleSurfaceButton(copyClipBtn);
        copyClipBtn.setPreferredSize(new Dimension(80, 24));
        copyClipBtn.addActionListener(e -> {
            copySecretToClipBoard(encBytes, iv);
            ToastManager.show(mainFrame, " copied  -  clipboard clears in 60s",
                ToastManager.ToastType.SUCCESS, 10_000);
        });

        JButton exportBtn = new JButton("Export");
        exportBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        ThemeManager.styleSurfaceButton(exportBtn);
        exportBtn.setPreferredSize(new Dimension(80, 24));
        exportBtn.addActionListener(e -> {
            exportBinaryFunction(encBytes, iv, data);
        });

        JButton viewBtn = new JButton("👁 View");
        viewBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        ThemeManager.styleSurfaceButton(viewBtn);
        viewBtn.setPreferredSize(new Dimension(80, 24));

        viewBtn.addActionListener(e -> {
            try {
                // ===== Decrypt Base64 field =====
                char[] base64Chars = backend.decryptData(encBytes, iv);

                // ===== Decode Base64 back to raw bytes for detection and display =====
                byte[] decoded = Base64.getDecoder().decode(new String(base64Chars));
                Yggdrasil.wipeCharArray(base64Chars);

                // ===== Get filename from credential for magic byte detection =====
                // Find the credential that owns this field by scanning credentials
                String filename = "unknown";
                byte[] encFilename = data;
                    if (encFilename != null) {
                        try {
                            char[] fn = backend.decryptData(encFilename, iv);
                            filename = new String(fn);
                            Yggdrasil.wipeCharArray(fn);
                        } catch (Exception ignored) {}
                    }

                // ===== Detect file type from magic bytes + filename =====
                BinaryViewer.DetectedType fileType = BinaryViewer.detect(
                    Arrays.copyOf(decoded, Math.min(decoded.length, 16)),
                    filename
                );

                // ===== Open viewer dialog =====
                final byte[] decodedFinal = decoded;
                final String filenameFinal = filename;
                SwingUtilities.invokeLater(() ->
                    BinaryViewer.showViewerDialog(mainFrame, decodedFinal, fileType, filenameFinal));

            } catch (Exception ex) {
                ex.printStackTrace();
                ToastManager.error(mainFrame, "Failed to open viewer.");
            }
        });


        // ===== SHOW / HIDE TOGGLE =====
        // Auto-masks after 30s if user forgets to click Hide
        JButton  showHideBtn  = new JButton("👁 Show");
        showHideBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        ThemeManager.styleSurfaceButton(showHideBtn);
        showHideBtn.setPreferredSize(new Dimension(80, 24));

        Timer[]   autoMaskTimer = {null};
        boolean[] isVisible     = {false};

        Runnable doMask = () -> {
            try {
                masked.setText("••••••••••••");
                masked.setForeground(ThemeManager.TEXT_MUTED);
                showHideBtn.setText("👁 Show");
                isVisible[0] = false;
                if (autoMaskTimer[0] != null) { autoMaskTimer[0].stop(); autoMaskTimer[0] = null; }
            } catch (Exception e) { System.out.println("Mask Error: " + e); }
        };

        showHideBtn.addActionListener(e -> {
            if (isVisible[0]) {
                doMask.run();
            } else {
                try {
                    if (DEBUG) System.out.println("Decrypt for Show - Wipe Memory Space after");
                    char[] value = backend.decryptData(encBytes, iv);
                    masked.setText(new String(value));
                    masked.setForeground(ThemeManager.TEXT);
                    Yggdrasil.wipeCharArray(value); // ===== Wipe immediately after rendering =====
                    showHideBtn.setText("🙈 Hide");
                    isVisible[0] = true;
                    // ===== AUTO RE-MASK after 30 seconds =====
                    if (autoMaskTimer[0] != null) autoMaskTimer[0].stop();
                    autoMaskTimer[0] = new Timer(30_000, ev -> doMask.run());
                    autoMaskTimer[0].setRepeats(false);
                    autoMaskTimer[0].start();
                } catch (Exception ex) { ex.printStackTrace(); }
            }
        });

        JPanel actionBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        actionBtns.setOpaque(false);
        if (keytype.equals("docs") && label.equals("Base64"))actionBtns.add(viewBtn);
        if (label.equals("Base64"))actionBtns.add(exportBtn);
        if (!keytype.equals("docs"))actionBtns.add(showHideBtn);
        actionBtns.add(copyClipBtn);

        fieldRow.add(lbl,        BorderLayout.WEST);
        fieldRow.add(scroll,     BorderLayout.CENTER);
        fieldRow.add(actionBtns, BorderLayout.EAST);

        detailContent.add(fieldRow);
        detailContent.add(Box.createVerticalStrut(4));
    }

    protected void exportBinaryFunction(byte[] encBytes, byte[] iv, byte[] data) {
        try {
            base64Encoded = backend.decryptData(encBytes, iv);
        } catch (Exception e) { e.printStackTrace();}

        if (base64Encoded == null || base64Encoded.toString().isEmpty()) {
            JOptionPane.showMessageDialog(null,
                "No Base64 data to convert to binary to export.",
                "Export Error",
                JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Ask user where to save the exported file
        JFileChooser fc = new JFileChooser();

        for (Component comp : fc.getComponents()) {
                ThemeManager.themeFileChooserComponents(comp);
            }
            
        // ===== Get filename from credential for magic byte detection =====
                // Find the credential that owns this field by scanning credentials
                String filename = "unknown";
                byte[] encFilename = data;
                    if (encFilename != null) {
                        try {
                            char[] fn = backend.decryptData(encFilename, iv);
                            filename = new String(fn);
                            Yggdrasil.wipeCharArray(fn);
                        } catch (Exception ignored) {}
                    }
        if (filename != null && !filename.isEmpty()) {fc.setSelectedFile(new java.io.File(filename));}
        
        int result = fc.showSaveDialog(fc);
        
        // Bail out if user cancelled the dialog
        if (result != JFileChooser.APPROVE_OPTION) return;

        Path outputPath = fc.getSelectedFile().toPath();

        // Warn before overwriting an existing file
        if (Files.exists(outputPath)) {
            int confirm = JOptionPane.showConfirmDialog(null,
                "File already exists. Overwrite?",
                "Confirm Overwrite",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;
        }

        try {
            // Decode Base64 back to raw binary bytes
            byte[] fileBytes = Base64.getDecoder().decode(new String(base64Encoded));

            try {
                // Verify integrity before writing: re-hash and compare to stored hash
                String recomputedHash = SHA256Util.hashBytes(fileBytes);
                if (shasumData != null && !SHA256Util.verifyHash(shasumData, recomputedHash)) {
                    JOptionPane.showMessageDialog(null,
                        "Integrity check failed. Data may be corrupted.",
                        "Export Aborted",
                        JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // Write raw bytes to the chosen output path
                Files.write(outputPath, fileBytes);

                // Verify the written file matches the original file hash
                String writtenHash = SHA256Util.hashFile(outputPath);
                if (shasumFile != null && !SHA256Util.verifyHash(shasumFile, writtenHash)) {
                    JOptionPane.showMessageDialog(null,
                        "Written file hash mismatch. Export may be corrupt.",
                        "Export Warning",
                        JOptionPane.WARNING_MESSAGE);
                    return;
                }

                JOptionPane.showMessageDialog(null,
                    "File exported successfully.\n" + outputPath.toAbsolutePath(),
                    "Export Complete",
                    JOptionPane.INFORMATION_MESSAGE);

            } finally {
                // Wipe raw bytes from memory immediately after writing
                Arrays.fill(fileBytes, (byte) 0);
                ToastManager.show(mainFrame, filename + " exported", ToastManager.ToastType.SUCCESS, 10_000);
            }

        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to export file: " + outputPath, ex);
        }
    }

    // ===== COPY TO CLIPBOARD - plain string overload =====
    protected void copySecretToClipBoard(String plaintext) {
        try {
            java.awt.datatransfer.StringSelection sel =
                new java.awt.datatransfer.StringSelection(plaintext);
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
            // ===== AUTO-CLEAR clipboard after timeout =====
            javax.swing.Timer wipe = new javax.swing.Timer(CLIPBOARD_CLEAR_MS, ev ->
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(""), null));
            wipe.setRepeats(false);
            wipe.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ===== COPY TO CLIPBOARD - encrypted bytes overload =====
    protected void copySecretToClipBoard(byte[] encBytes, byte[] iv) {
        try {
            char[] plaintext = backend.decryptData(encBytes, iv);
            java.awt.datatransfer.StringSelection sel =
                new java.awt.datatransfer.StringSelection(new String(plaintext));
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
            Yggdrasil.wipeCharArray(plaintext); // ===== Wipe immediately after clipboard write =====
            // ===== AUTO-CLEAR clipboard after timeout =====
            javax.swing.Timer wipe = new javax.swing.Timer(CLIPBOARD_CLEAR_MS, ev ->
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(""), null));
            wipe.setRepeats(false);
            wipe.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void updateSearchPlaceholder() {
        if (searchField == null) return;
        // ===== No setText needed - hint is painted dynamically from currentTypeFilter =====
        if (currentSearchQuery.isEmpty()) {
            suppressSearchListener = true;
            searchField.setText(""); // ===== Ensure field is visually clear =====
            suppressSearchListener = false;
            searchField.repaint();   // ===== Repaint so hint text shows new category name =====
        }
    }

    // ===== REFRESH TABLE =====
    /**
     * Rebuilds the table model for the currently selected entry type.
     * Sensitive fields always show as bullets in the table.
     */
    private void refreshTable() {
        currentSearchQuery = ""; // Clear search when switching categories, one of my favorites
        updateSearchPlaceholder();

        Futhark.EntryType type = Futhark.forKey(currentTypeFilter);
        if (DEBUG) System.out.println("Filter: " + currentTypeFilter);

        // ===== REBUILD COLUMN HEADERS =====
        String[] headers = Futhark.tableHeaders(type);
        model.setColumnCount(0);
        model.setRowCount(0);
        for (String h : headers) model.addColumn(h);

        // ===== HIDE ID COLUMN =====
        if (!DEBUG) {
            if (table.getColumnModel().getColumnCount() > 0) {
                table.getColumnModel().getColumn(0).setMinWidth(0);
                table.getColumnModel().getColumn(0).setMaxWidth(0);
            }
        }
        // ===== HIDE TYPE COLUMN =====
        if (table.getColumnModel().getColumnCount() > 1) {
            table.getColumnModel().getColumn(1).setMinWidth(0);
            table.getColumnModel().getColumn(1).setMaxWidth(0);
        }

        // ===== POPULATE ROWS =====
        for (Yggdrasil.Credential c : credentials) {
            if (!currentTypeFilter.equals(c.type)) continue;

            Object[] rowData = new Object[headers.length];
            rowData[0] = c.id;
            rowData[1] = (type != null ? type.icon : "") + " " + currentTypeFilter;
            rowData[2] = new String(c.tag);

            if (type != null) {
                for (int i = 0; i < type.tableColumnIndices.length && (i + 3) < headers.length; i++) {
                    int           fieldIdx = type.tableColumnIndices[i];
                    Futhark.Field field    = type.fields.get(fieldIdx);
                    if (field.sensitive || field.password) {
                        rowData[i + 3] = "••••••••"; // ===== Never show sensitive data in table =====
                    } else {
                        byte[] enc = c.getDataField(fieldIdx);
                        if (enc != null) {
                            try {
                                if (DEBUG) System.out.println("Decrypt for Show - Wipe Memory Space after");
                                char[] val = backend.decryptData(enc, c.iv);
                                rowData[i + 3] = new String(val);
                                Yggdrasil.wipeCharArray(val);
                            } catch (Exception e) {
                                rowData[i + 3] = "[error]";
                            }
                        }
                    }
                }
            }
            model.addRow(rowData);
        }
    }

    // ===== COPY FIELD TO CLIPBOARD =====
    /**
     * Called on double-click. Decrypts the clicked field, copies to clipboard,
     * starts auto-wipe timer, shows toast.
     */
    private void copyFieldToClipboard(int row, int col) {
        try {
            int credIndex = visibleRowToCredentialIndex(row);
            if (credIndex < 0) return;

            Yggdrasil.Credential c    = credentials.get(credIndex);
            Futhark.EntryType    type = Futhark.forKey(c.type);
            if (DEBUG) System.out.println("Copy: " + c.id + "  " + c.type);

            char[] value = null;

            if (col == 2) {
                // ===== Tag column - already decrypted in credential =====
                value = Arrays.copyOf(c.tag, c.tag.length);
            } else if (type != null && col >= 3 && col < 3 + type.tableColumnIndices.length) {
                int    fieldIdx = type.tableColumnIndices[col - 3];
                byte[] enc      = c.getDataField(fieldIdx);
                if (enc != null) value = backend.decryptData(enc, c.iv);
            }

            if (value == null || value.length == 0) return;

            final char[] finalValue = value;
            java.awt.datatransfer.StringSelection sel =
                new java.awt.datatransfer.StringSelection(new String(finalValue));
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
            Yggdrasil.wipeCharArray(finalValue); // ===== Wipe local copy after write =====

            // ===== AUTO-CLEAR clipboard after timeout =====
            javax.swing.Timer wipe = new javax.swing.Timer(CLIPBOARD_CLEAR_MS, ev ->
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(""), null));
            wipe.setRepeats(false);
            wipe.start();

            String colName = model.getColumnName(col);
            ToastManager.show(mainFrame, colName + " copied  -  clipboard clears in 60s",
                ToastManager.ToastType.SUCCESS, 10_000);

        } catch (Exception e) {
            e.printStackTrace();
            ToastManager.error(mainFrame, "Failed to copy field.");
        }
    }

    /**
     * Maps a visible table row index to the full credentials list index.
     * Needed because the table is filtered by currentTypeFilter.
     */
    private int visibleRowToCredentialIndex(int visibleRow) {
        int count = 0;
        for (int i = 0; i < credentials.size(); i++) {
            if (credentials.get(i).type != null &&
                credentials.get(i).type.equals(currentTypeFilter)) {
                if (count == visibleRow) return i;
                count++;
            }
        }
        return -1;
    }

    // ===== ADD or UPDATE ENTRY =====
    private void addUpdateEntry(String mode) {
        System.out.println(mode);
        if (mode.equals("update")) {
            int row = table.getSelectedRow();
            if (row == -1) return;
            addupdate_id = (int) model.getValueAt(row, 0);
        }

        JComboBox<String> typeBox = new JComboBox<>();
        for (Futhark.EntryType t : Futhark.allTypes()) {
            typeBox.addItem(t.icon + "  " + t.displayName);
        }

        Futhark.EntryType preSelected = Futhark.forKey(currentTypeFilter);
        if (preSelected != null) {
            typeBox.setSelectedIndex(Futhark.allTypes().indexOf(preSelected));
        }

        if (mode.equals("update")) { typeBox.setVisible(false); 
            
        }

        JTextField tagField = new JTextField();
        tagField.setBackground(ThemeManager.SURFACE2);
        tagField.setForeground(ThemeManager.TEXT);
        tagField.setCaretColor(ThemeManager.ACCENT);

        CardLayout cards    = new CardLayout();
        JPanel     cardPanel = new JPanel(cards);
        cardPanel.setBackground(ThemeManager.BG);
        cardPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));

        java.util.Map<String, List<JComponent>> fieldMap = new java.util.LinkedHashMap<>();

        for (Futhark.EntryType t : Futhark.allTypes()) {
            JPanel         typePanel = new JPanel();
            typePanel.setBackground(ThemeManager.BG);
            typePanel.setLayout(new BoxLayout(typePanel, BoxLayout.Y_AXIS));
            List<JComponent> inputs = new ArrayList<>();

            for (Futhark.Field field : t.fields) {
                JLabel fLabel = new JLabel(field.label + ":");
                fLabel.setForeground(ThemeManager.TEXT_MUTED);
                fLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
                fLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
                if (field.sensitive && field.multiline) {
                    fLabel.setText("Hidden/Secure " + field.label + ":");
                }

                JComponent input;
                if (field.sensitive && field.multiline) {
                    // ===== Multline SENSITIVE FIELD + eye toggle =====
                    // for fields like Notes; SSHKeys; Passkeys; VPNKeys
                    JTextArea ta = new JTextArea(6, 20); // 6 visible rows for large data entry
                    ta.setBackground(ThemeManager.SURFACE2);
                    ta.setForeground(ThemeManager.TEXT);
                    ta.setCaretColor(ThemeManager.ACCENT);
                    ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); // monospace suits keys/tokens
                    ta.setLineWrap(true);
                    ta.setWrapStyleWord(false); // false = wrap mid-token (better for keys/hashes)
                    ta.setAlignmentX(Component.LEFT_ALIGNMENT);
                    ta.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

                    // ===== Wrap in scroll pane so long content stays contained =====
                    JScrollPane taScroll = new JScrollPane(ta);
                    taScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
                    taScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160)); // ~6 rows visible
                    taScroll.setPreferredSize(new Dimension(0, 160));
                    taScroll.setBorder(BorderFactory.createLineBorder(ThemeManager.BORDER));

                    // ===== Outer panel holds the scroll pane for layout consistency =====
                    JPanel taRow = new JPanel(new BorderLayout(0, 0));
                    taRow.setBackground(ThemeManager.SURFACE2);
                    taRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
                    taRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                    taRow.add(taScroll, BorderLayout.CENTER); // scroll pane goes in, not raw ta

                    // ===== Used for value extraction - read ta.getText() directly =====
                    taRow.putClientProperty("sensitiveField", ta);

                    input = taRow;
                    
                } else if (field.multiline) {
                    JTextArea ta = new JTextArea(3, 20);
                    ta.setBackground(ThemeManager.SURFACE2);
                    ta.setForeground(ThemeManager.TEXT);
                    ta.setLineWrap(true);
                    ta.setWrapStyleWord(true);
                    ta.setAlignmentX(Component.LEFT_ALIGNMENT);
                    if (!field.editable)ta.setEnabled(false);
                    input = new JScrollPane(ta);
                    ((JScrollPane) input).setAlignmentX(Component.LEFT_ALIGNMENT);
                    ((JScrollPane) input).setMaximumSize(new Dimension(Integer.MAX_VALUE, 72));
                
                } else if (field.sensitive || field.password) {
                        final JPasswordField pf = new JPasswordField();
                        pf.setBackground(ThemeManager.SURFACE2);
                        pf.setForeground(ThemeManager.TEXT);
                        pf.setCaretColor(ThemeManager.ACCENT);
                        pf.setAlignmentX(Component.LEFT_ALIGNMENT);                  

                    // ===== PASSWORD EYE TOGGLE BUTTON =====
                    JButton eyeBtn = new JButton("👁");
                    eyeBtn.setFont(new Font("Dialog", Font.PLAIN, 13));
                    eyeBtn.setToolTipText("Show / Hide");
                    eyeBtn.setPreferredSize(new Dimension(34, 26));
                    eyeBtn.setFocusPainted(false);
                    eyeBtn.setBorderPainted(false);
                    if (!field.editable)eyeBtn.setBackground(ThemeManager.BORDER);
                    eyeBtn.setForeground(ThemeManager.TEXT_MUTED);
                    eyeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    boolean[] pwVisible = {false};
                    eyeBtn.addActionListener(ev -> {
                        pwVisible[0] = !pwVisible[0];
                        pf.setEchoChar(pwVisible[0] ? (char) 0 : '•');
                        eyeBtn.setText(pwVisible[0] ? "🙈" : "👁");
                    });

                    JPanel pfRow = new JPanel(new BorderLayout(2, 0));
                    pfRow.setBackground(ThemeManager.SURFACE2);
                    pfRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
                    pfRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                    pfRow.add(pf,     BorderLayout.CENTER);
                    pfRow.add(eyeBtn, BorderLayout.EAST);
                    pfRow.putClientProperty("passwordField", pf); // ===== Used for value extraction =====
                    if (!field.password && !field.editable)pf.setEnabled(false);
                    input = pfRow;
                    

                        if (field.password) {
                            final JPasswordField pfRef = pf; 
                            final Thor.StrengthBarPanel fieldStrengthBar = field.password ? new Thor.StrengthBarPanel() : null;

                            pfRef.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                                void upd() {
                                    // ===== Guard: sensitive-only fields have no bar =====
                                    if (fieldStrengthBar != null) fieldStrengthBar.updateFromField(pfRef);
                                }
                                public void insertUpdate (javax.swing.event.DocumentEvent e) { upd(); }
                                public void removeUpdate (javax.swing.event.DocumentEvent e) { upd(); }
                                public void changedUpdate(javax.swing.event.DocumentEvent e) { upd(); }
                            });
                            pfRow.putClientProperty("strengthBar", fieldStrengthBar);
                        }
                } else {
                    JTextField tf = new JTextField();
                    tf.setBackground(ThemeManager.SURFACE2);
                    tf.setForeground(ThemeManager.TEXT);
                    tf.setCaretColor(ThemeManager.ACCENT);
                    tf.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
                    tf.setAlignmentX(Component.LEFT_ALIGNMENT);
                    if (!field.editable)tf.setEnabled(false);
                    if (!field.editable)tf.setBackground(ThemeManager.BORDER);
                    input = tf;
                }

            typePanel.add(fLabel);
            typePanel.add(Box.createVerticalStrut(2));
            typePanel.add(input);
            typePanel.add(Box.createVerticalStrut(6));
            inputs.add(input);

            if (field.label.equals("Base64")) {
                JPanel importRow = buildImportRow(inputs, t);
                JPanel importWrapper = new JPanel(new BorderLayout());
                    importWrapper.setBackground(ThemeManager.BG);
                    importWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
                    importWrapper.setPreferredSize(new Dimension(0, 30));
                    importWrapper.setMinimumSize(new Dimension(0, 30)); // ===== All three needed to lock height =====
                    importWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
                    importWrapper.add(importRow, BorderLayout.CENTER);

                    typePanel.add(importWrapper);
                    typePanel.add(Box.createVerticalStrut(3));
            }
            if (field.password) {
                Thor.StrengthBarPanel fieldStrengthBar = (Thor.StrengthBarPanel) ((JPanel) input).getClientProperty("strengthBar");
                        JPanel genRow = buildGeneratorRow(inputs, t);
                        JPanel genWrapper = new JPanel(new BorderLayout());
                        genWrapper.setBackground(ThemeManager.BG);
                        genWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
                        genWrapper.setPreferredSize(new Dimension(0, 30));
                        genWrapper.setMinimumSize(new Dimension(0, 30)); // ===== All three needed to lock height =====
                        genWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
                        genWrapper.add(genRow, BorderLayout.CENTER);

                        // ===== Wrap strength bar in fixed-height panel to prevent BoxLayout stretching =====
                        JPanel barWrapper = new JPanel(new BorderLayout());
                        barWrapper.setBackground(ThemeManager.BG);
                        barWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
                        barWrapper.setPreferredSize(new Dimension(0, 20));
                        barWrapper.setMinimumSize(new Dimension(0, 20)); // ===== All three needed to lock height =====
                        barWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
                        barWrapper.add(fieldStrengthBar, BorderLayout.CENTER);

                        typePanel.add(genWrapper);
                        typePanel.add(Box.createVerticalStrut(3));
                        typePanel.add(barWrapper);
                        typePanel.add(Box.createVerticalStrut(3));
            }
            
            }
            cardPanel.add(typePanel, t.typeKey);
            fieldMap.put(t.typeKey, inputs);

        if (mode.equals("update")) {
                typeBox.setVisible(false);
                // ===== PRE-POPULATE FIELDS from existing credential =====
                int credIndex = visibleRowToCredentialIndex(table.getSelectedRow());
                if (credIndex >= 0) {
                    Yggdrasil.Credential c        = credentials.get(credIndex);
                    Futhark.EntryType    type     = Futhark.forKey(c.type);

                    // ===== TAG - always present =====
                    tagField.setText(new String(c.tag));

                    if (type != null) {
                        // ===== Renamed to existingInputs to avoid conflict with card-builder loop var =====
                        List<JComponent> existingInputs = fieldMap.get(type.typeKey);
                        if (existingInputs != null) {
                            for (int i = 0; i < type.fields.size() && i < existingInputs.size(); i++) {
                                byte[] encBytes = c.getDataField(i);
                                if (encBytes == null) continue; // ===== Skip unpopulated fields =====
                                try {
                                    char[]     value = backend.decryptData(encBytes, c.iv);
                                    JComponent comp  = existingInputs.get(i);

                                    if (comp instanceof JPanel wrapper) {
                                        Object sfProp = wrapper.getClientProperty("sensitiveField");
                                        Object pfProp = wrapper.getClientProperty("passwordField");
                                        if (sfProp instanceof JTextArea ta) {
                                            ta.setText(new String(value));
                                        } else if (pfProp instanceof JPasswordField pf) {
                                            pf.setText(new String(value));
                                        }
                                    }
                                    Futhark.Field f = type.fields.get(i);
                                    if (comp instanceof JPasswordField pf) {
                                        pf.setEnabled(true);
                                        pf.setText(new String(value));
                                        if (!f.editable) pf.setEnabled(false);
                                    } else if (comp instanceof JTextField tf) {
                                        tf.setEnabled(true);
                                        tf.setText(new String(value));
                                        if (!f.editable) tf.setEnabled(false);
                                    } else if (comp instanceof JScrollPane sp &&
                                            sp.getViewport().getView() instanceof JTextArea ta) {
                                        ta.setEnabled(true);
                                        ta.setText(new String(value));
                                        if (!f.editable) ta.setEnabled(false);
                                    }

                                    Yggdrasil.wipeCharArray(value); // ===== Wipe after filling field =====

                                } catch (Exception e) {
                                    if (DEBUG) System.out.println("Pre-populate failed field " + i + ": " + e);
                                }
                            }
                        }
                    }
                }   
                }
            }

        typeBox.addActionListener(e -> {
            int idx = typeBox.getSelectedIndex();
            if (idx >= 0 && idx < Futhark.allTypes().size())
                cards.show(cardPanel, Futhark.allTypes().get(idx).typeKey);
        });

        if (preSelected != null) cards.show(cardPanel, preSelected.typeKey);

        Object[]  message   = { "Entry Type:", typeBox, "Tag / Label:", tagField, cardPanel };
        JButton   btnAdd    = new JButton(mode.equals("update") ? "Update" : "Add Entry");
        JButton   btnCancel = new JButton("Cancel");
        ThemeManager.styleAccentButton(btnAdd);
        ThemeManager.styleSurfaceButton(btnCancel);

        JOptionPane entryPane = new JOptionPane(message, JOptionPane.PLAIN_MESSAGE,
            JOptionPane.DEFAULT_OPTION, dialogIcon, new Object[]{ btnAdd, btnCancel }, null);

        JDialog entryDialog = entryPane.createDialog(mainFrame, "Add Entry");
        entryDialog.setModal(true);

        entryDialog.setMinimumSize(new Dimension(520, 400));
        entryDialog.setPreferredSize(new Dimension(560, entryDialog.getPreferredSize().height));
        entryDialog.pack();

        final boolean[] confirmed = { false };
        btnCancel.addActionListener(e -> entryDialog.dispose());
        btnAdd.addActionListener(e -> { confirmed[0] = true; entryDialog.dispose(); });
        entryDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        entryDialog.setVisible(true);

        if (confirmed[0]) {
            try {
                int               typeIdx      = typeBox.getSelectedIndex();
                Futhark.EntryType selectedType = Futhark.allTypes().get(typeIdx);
                List<JComponent>  inputs       = fieldMap.get(selectedType.typeKey);
                char[][]          dataFields   = new char[Futhark.DATA_COLUMNS.length][];

                for (int i = 0; i < inputs.size() && i < dataFields.length; i++) {
                    JComponent comp = inputs.get(i);
                    if (comp instanceof JPanel wrapper) {
                        Object sfProp = wrapper.getClientProperty("sensitiveField"); // ===== multiline sensitive =====
                        Object pfProp = wrapper.getClientProperty("passwordField");  // ===== single-line password =====
                        if (sfProp instanceof JTextArea ta) {
                            dataFields[i] = ta.getText().toCharArray();
                        } else if (pfProp instanceof JPasswordField pf) {
                            dataFields[i] = pf.getPassword();
                        }
                    }
                    if (comp instanceof JPasswordField pf) {
                        dataFields[i] = pf.getPassword();
                    } else if (comp instanceof JTextField tf) {
                        dataFields[i] = tf.getText().toCharArray();
                    } else if (comp instanceof JScrollPane sp &&
                               sp.getViewport().getView() instanceof JTextArea ta) {
                        dataFields[i] = ta.getText().toCharArray();
                    }
                }

                String creationDate=" "; // PlaceHolders
                String revisionDate=" "; // PlaceHolders
                String folderId=" "; // PlaceHolders
                if (mode.equals("update")) {
                    backend.updateEntry(conn, addupdate_id, tagField.getText().toCharArray(), dataFields, folderId);
                } else {
                    backend.addEntry(conn, tagField.getText().toCharArray(), selectedType.typeKey.toCharArray(), dataFields, DATABASE_TYPE, creationDate, revisionDate, folderId);
                }

                for (char[] d : dataFields) if (d != null) Yggdrasil.wipeCharArray(d);

                credentials = backend.loadAll(conn);
                refreshTable();
                ToastManager.success(mainFrame, "Entry saved.");

            } catch (Exception e) {
                e.printStackTrace();
                ToastManager.error(mainFrame, "Failed to save entry.");
            }
        }
    }

    private JPanel buildImportRow(List<JComponent> inputs, Futhark.EntryType type) {
        JPanel importRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        importRow.setBackground(ThemeManager.BG);
        importRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton importBtn = new JButton("Import");
        ThemeManager.styleSurfaceButton(importBtn);

        importBtn.addActionListener(e -> {
            binaryImportSuccess = false;

            // Ask for filename via native file chooser dialog
            JFileChooser fc = new JFileChooser();
            
            for (Component comp : fc.getComponents()) {
                ThemeManager.themeFileChooserComponents(comp);
            }

            int result = fc.showOpenDialog(importBtn);

            if (type.typeKey.toString().equals("docs")){
            // ===== Reject unsupported file types before reading any bytes =====
            String ext = fc.getSelectedFile().getName().toLowerCase();
            if (!ext.matches(".*\\.(pdf|png|jpg|jpeg|gif|bmp|webp|txt|md|csv|log|docx|doc|xlsx|xls)$")) {
                JOptionPane.showMessageDialog(null, "Unsupported file type: " + ext);
                return;
            }}

            // Bail out if user cancelled the dialog
            if (result != JFileChooser.APPROVE_OPTION) return;

            // Store the absolute path for later use
            binaryPath = fc.getSelectedFile().getAbsolutePath();

            // Extract just the filename portion for the Filename field
            String fileName = fc.getSelectedFile().getName();
            Path inputPath  = Path.of(binaryPath);

            try {
                long maxBytes;
                // Enforce 100MB size limit before reading anything
                long fileSizeBytes = Files.size(inputPath);
                if (type.typeKey.toString().equals("docs")){maxBytes = 200L * 1024 * 1024;} else {maxBytes = 100L * 1024 * 1024;}
                if (fileSizeBytes > maxBytes) {
                    JOptionPane.showMessageDialog(null, "File exceeds 200MB limit.");
                    return;
                }

                // SHA-256 the file via streaming before loading into memory
                shasumFile = SHA256Util.hashFile(inputPath);
                byte[] fileBytes = null;

                long max50MBytes      = 50L * 1024 * 1024;
                if (fileSizeBytes < max50MBytes) {try {
                    // Read file bytes once; safe under 100MB limit
                    fileBytes = Files.readAllBytes(inputPath);
                    // Encode binary to Base64 for storage in SQLite text field
                    base64Encoded = Base64.getEncoder().encodeToString(fileBytes).toCharArray();

                    // SHA-256 the raw data bytes (separate from the file hash above)
                    shasumData = SHA256Util.hashBytes(fileBytes);

                } finally {
                    // Wipe raw bytes from memory immediately after encoding
                    Arrays.fill(fileBytes, (byte) 0);
                }}

            } catch (IOException ex) {
                throw new UncheckedIOException("Failed to process file: " + binaryPath, ex);
            }

            // Walk inputs in parallel with type.fields to find fields by label
            // This lets us target Base64, Filename, SHA256 File, SHA256 Data precisely
            for (int i = 0; i < type.fields.size() && i < inputs.size(); i++) {
                String     label = type.fields.get(i).label;
                JComponent comp  = inputs.get(i);

                switch (label) {

                    case "Base64" -> {
                        // Base64 lives inside a JPanel wrapper with a passwordField property
                        if (comp instanceof JPanel wrapper) {
                            Object pfProp = wrapper.getClientProperty("passwordField");
                            if (pfProp instanceof JPasswordField pf) {
                                // Re-enable temporarily to allow programmatic setText()
                                pf.setEnabled(true);
                                pf.setText(new String(base64Encoded));
                                pf.setEnabled(false);
                            }
                        } else if (comp instanceof JPasswordField pf) {
                            pf.setEnabled(true);
                            pf.setText(new String(base64Encoded));
                            pf.setEnabled(false);
                        }
                    }

                    case "Filename" -> {
                        // Plain JTextField; re-enable to write, then lock again
                        if (comp instanceof JTextField tf) {
                            tf.setEnabled(true);
                            tf.setText(fileName);
                            tf.setEnabled(false);
                            tf.setBackground(ThemeManager.BORDER);
                        }
                    }

                    case "SHA256 File" -> {
                        // Hash of the raw file bytes before any encoding
                        if (comp instanceof JTextField tf) {
                            tf.setEnabled(true);
                            tf.setText(shasumFile);
                            tf.setEnabled(false);
                            tf.setBackground(ThemeManager.BORDER);
                        }
                    }

                    case "SHA256 Data" -> {
                        // Hash of the data after encoding; proves encoding round-trip integrity
                        if (comp instanceof JTextField tf) {
                            tf.setEnabled(true);
                            tf.setText(shasumData);
                            tf.setEnabled(false);
                            tf.setBackground(ThemeManager.BORDER);
                        }
                    }
                }
            }

            // Mark import as successful only after all fields are populated
            if (base64Encoded != null && !new String(base64Encoded).isEmpty()) {
                binaryImportSuccess = true;
                System.out.println("File hash : " + shasumFile);
                System.out.println("Data hash : " + shasumData);
            }
            backend.wipeCharArray(base64Encoded);
        });

        if (type.typeKey.toString().equals("docs")){importRow.add(new JLabel("Document or Picture File [< 200MB]: "));} else {importRow.add(new JLabel("Binary File [< 100MB]: "));}
        importRow.add(importBtn);
        return importRow;
    }

    /** Builds the password generator row for the add/update entry dialog. */
    private JPanel buildGeneratorRow(List<JComponent> inputs, Futhark.EntryType type) {
        JPanel genRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        genRow.setBackground(ThemeManager.BG);
        genRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JCheckBox useABC     = new JCheckBox("ABC", true);
        JCheckBox useNumbers = new JCheckBox("123", true);
        JCheckBox useSpecial = new JCheckBox("!@#", false);
        useABC.setBackground(ThemeManager.BG);     useABC.setForeground(ThemeManager.TEXT);
        useNumbers.setBackground(ThemeManager.BG); useNumbers.setForeground(ThemeManager.TEXT);
        useSpecial.setBackground(ThemeManager.BG); useSpecial.setForeground(ThemeManager.TEXT);

        SpinnerNumberModel lenModel   = new SpinnerNumberModel(18, 8, 128, 1);
        JSpinner           lenSpinner = new JSpinner(lenModel);
        lenSpinner.setPreferredSize(new Dimension(55, 24));

        JButton genBtn = new JButton("Generate");
        ThemeManager.styleSurfaceButton(genBtn);

        genBtn.addActionListener(e -> {
            int    length    = (int) lenSpinner.getValue();
            char[] generated = Mimir.generatePassword(length, useABC.isSelected(), useNumbers.isSelected(), useSpecial.isSelected());
            // ===== Find first password field and fill it =====
            for (JComponent comp : inputs) {
                if (comp instanceof JPanel wrapper) {
                    Object pfProp = wrapper.getClientProperty("passwordField");
                    if (pfProp instanceof JPasswordField pf) { pf.setText(new String(generated)); break; }
                } else if (comp instanceof JPasswordField pf) {
                    pf.setText(new String(generated)); break;
                }
            }
            Arrays.fill(generated, '\0'); // ===== Wipe immediately after use =====
        });

        genRow.add(new JLabel("Len:"));
        genRow.add(lenSpinner);
        genRow.add(useABC);
        genRow.add(useNumbers);
        genRow.add(useSpecial);
        genRow.add(genBtn);
        return genRow;
    }

    // ===== DELETE ENTRY =====
    private void deleteEntry() {
        // ===== GET ALL SELECTED ROW INDICES =====
        int[] rows = table.getSelectedRows();

        // No selection - nothing to do
        if (rows.length == 0) return;

        // ===== CONFIRM DELETION - show count so user knows scope =====
        int confirm = ThemeManager.showThemedConfirm(mainFrame,
    "Delete " + rows.length + " selected entr" + (rows.length == 1 ? "y" : "ies") + "?",
    "Confirm Delete");
    
        if (confirm == JOptionPane.YES_OPTION) {
            try {
                // ===== COLLECT IDs BEFORE DELETION =====
                // Rows must be collected upfront - indices shift as rows are removed
                // Sort descending so removal from bottom up doesn't affect upper indices
                int[] sortedRows = rows.clone();
                java.util.Arrays.sort(sortedRows);

                // ===== DELETE EACH SELECTED ENTRY BY ID =====
                for (int i = sortedRows.length - 1; i >= 0; i--) {
                    int id = (int) model.getValueAt(sortedRows[i], 0);
                    databaseutilities.deleteEntry(conn, id);
                }

                // ===== RELOAD STATE AFTER ALL DELETES =====
                credentials = backend.loadAll(conn);
                refreshTable();

                // ===== CLEAR DETAIL PANEL AFTER DELETE =====
                detailContent.removeAll();
                detailContent.revalidate();
                detailContent.repaint();

                ToastManager.info(mainFrame, rows.length + " entr" + (rows.length == 1 ? "y" : "ies") + " deleted.");
            } catch (Exception e) {
                e.printStackTrace();
                ToastManager.error(mainFrame, "Failed to delete entries.");
            }
        }
    }

    // ===== CHANGE MASTER PASSWORD =====
    private void changeMasterPass(String username) {
        char[][] creds = createNewMasterPass(conn, false,false,"From_SQL",PASSWORD_LENGTH);
            try {
                masterPassword = creds[1];
                username = new String(creds[2]);
                VaultLevel = new String(creds[3]);
                DATABASE_TYPE = new String(creds[4]);
                backend.changeMasterPass(conn, masterPassword, username);
                ToastManager.success(mainFrame, "Password changed for " + username);
            } catch (Exception e) {
                e.printStackTrace();
                ToastManager.error(mainFrame, "Failed to change account password.");
            }
            Yggdrasil.wipeCharArray(masterPassword);
        }

    // ===== PASSWORD STRENGTH CHECK =====
    public boolean testPasswordStrength(char[] password, char[] p2) {
        if (!java.util.Arrays.equals(password, p2)) {
            JOptionPane.showMessageDialog(null, "Passwords do not match!");
            Yggdrasil.wipeCharArray(password);
            Yggdrasil.wipeCharArray(p2);
            return false;
        } else if (password.length == 0) {
            JOptionPane.showMessageDialog(null, "Password cannot be empty!",
                "Error", JOptionPane.ERROR_MESSAGE, dialogIcon);
            Yggdrasil.wipeCharArray(password);
            Yggdrasil.wipeCharArray(p2);
            return false;
        } else if (password.length < PASSWORD_LENGTH) {
            JOptionPane.showMessageDialog(null,
                "Password must be at least " + PASSWORD_LENGTH + " characters!",
                "Error", JOptionPane.ERROR_MESSAGE, dialogIcon);
            Yggdrasil.wipeCharArray(password);
            Yggdrasil.wipeCharArray(p2);
            return false;
        } else {
            Yggdrasil.wipeCharArray(p2);
            return true;
        }
    }

    // ===== CREATE / UPDATE MASTER PASSWORD DIALOG =====
    // Called by Login for new vault, and by changeMasterPass() for password updates
    public char[][] createNewMasterPass(Connection conn, boolean createVault, boolean arg_vaultLevel, String vaultlevel ,int password_length) {
        arg_VaultLevel = arg_vaultLevel;
        VaultLevel = vaultlevel;
        PASSWORD_LENGTH = password_length;
        
        while (true) {
            JPasswordField pf1            = new JPasswordField(20);
            JPasswordField pf2            = new JPasswordField(20);
            JTextField     usernameField  = new JTextField(20);
            JLabel         usernameLabel  = new JLabel("Username:");
            JLabel         typeLabel      = new JLabel("Type of Vault:");
            JLabel         blank          = new JLabel(" ");
            JLabel         blank2         = new JLabel(" ");
            JLabel         blank3         = new JLabel(" ");
            JButton kdfBtn = new JButton("Compare profile options: Strength Chart");
            ThemeManager.styleSurfaceButton(kdfBtn);
            kdfBtn.addActionListener(e -> KdfChartDialog.show());

            Thor.StrengthBarPanel strengthBar = new Thor.StrengthBarPanel();
            pf1.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                void upd() { strengthBar.updateFromField(pf1); }
                public void insertUpdate (javax.swing.event.DocumentEvent e) { upd(); }
                public void removeUpdate (javax.swing.event.DocumentEvent e) { upd(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { upd(); }
            });

            usernameField.setVisible(false);
            usernameLabel.setVisible(false);

            JComboBox<String> dbSelector = new JComboBox<>(new String[]{
                "Single User - One password, one key",
                "Multi-User  - Many usernames/passwords, one key"
            });
            dbSelector.setSelectedIndex(0);

            JComboBox<String> profileSelector = new JComboBox<>(new String[]{
                "Minimum  - Low risk, high throughput (OWASP 2023)",
                "Warden   - Recommended for mobile and lower-end hardware",
                "Balanced - Most applications (RFC 9106)",
                "High     - Sensitive credentials (RFC 9106)",
                "Paranoid - Vault-grade master key derivation, exceeds all published standards"
            });
            profileSelector.setSelectedIndex(2); // ===== Default Balanced =====

            if (!createVault) {
                // ===== Pre-select current vault level when changing password =====
                try {
                    if (!arg_vaultLevel) VaultLevel  = Mimir.Pull_DB_Text_Meta_item(conn, "vault_level");
                    int    idx = switch (VaultLevel.trim()) {
                        case "MINIMUM"  -> 0;
                        case "WARDEN"   -> 1;
                        case "BALANCED" -> 2;
                        case "HIGH"     -> 3;
                        case "PARANOID" -> 4;
                        default -> { System.err.println("ERROR: Unknown vault_level " + VaultLevel); yield 2; }
                    };
                    profileSelector.setSelectedIndex(idx);
                } catch (Exception e) {
                    System.err.println("Failed to pull DB metadata: " + e.getMessage());
                }
            }

            typeLabel.setVisible(createVault);
            dbSelector.setVisible(createVault);
            dbSelector.addItemListener(e -> {
                if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                    boolean multi = dbSelector.getSelectedIndex() == 1;
                    usernameLabel.setVisible(multi);
                    usernameField.setVisible(multi);
                    blank.setVisible(!multi);
                    blank2.setVisible(!multi);
                    blank3.setVisible(!multi);
                }
            });

            JButton btnConfirm = new JButton(createVault ? "Create Vault" : "Update Password");
            JButton btnCancel  = new JButton("Cancel");
            ThemeManager.styleAccentButton(btnConfirm);
            ThemeManager.styleSurfaceButton(btnCancel);

            Object[] msg = {
                blank,blank,
                blank2,blank2,
                typeLabel, dbSelector, usernameLabel, usernameField,
                blank,blank,
                "Create Master Password:",  pf1,
                "Confirm Password:",        pf2,
                "Password Strength:",       strengthBar,
                "Security Profile:",        profileSelector,
                kdfBtn,
                blank,blank,
                
                blank3,blank3,
            };

            JOptionPane passPane = new JOptionPane(msg, JOptionPane.PLAIN_MESSAGE,
                JOptionPane.DEFAULT_OPTION, dialogIcon,
                new Object[]{ btnConfirm, btnCancel }, null);

            JDialog passDialog = passPane.createDialog(null,
                createVault ? "Create Vault" : "Update Account Password");
            passDialog.setModal(true);

            final boolean[] confirmed = { false };

            // ===== CANCEL - unrecoverable at this stage =====
            btnCancel.addActionListener(e -> { passDialog.dispose(); if (createVault) System.exit(0);});
            pf2.addActionListener(e -> { confirmed[0] = true; passDialog.dispose(); });
            btnConfirm.addActionListener(e -> { confirmed[0] = true; passDialog.dispose(); });

            passDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            passDialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent e) {
                    passDialog.dispose(); if (createVault) System.exit(0);
                }
            });

            blank.setVisible(true);
            passDialog.setVisible(true);
            if (!confirmed[0]) if (createVault)System.exit(0); else return null;

            masterPassword = pf1.getPassword();
            DATABASE_TYPE  = dbSelector.getSelectedIndex() == 1 ? "m" : "s";
            if (DATABASE_TYPE.equals("m")) username = usernameField.getText();

            char[] p2 = pf2.getPassword();
            VaultLevel = switch (profileSelector.getSelectedIndex()) {
                case 0  -> "MINIMUM";
                case 1  -> "WARDEN";
                case 2  -> "BALANCED";
                case 3  -> "HIGH";
                case 4  -> "PARANOID";
                default -> "BALANCED";
            };

            if (!createVault) {
                try {
                    Mimir.Update_DB_Text_Meta_item(conn, "vault_level", VaultLevel);
                } catch (Exception e) {
                    System.err.println("Failed to update vault_level: " + e.getMessage());
                }
            }
            char[][] creds = new char[5][];
            creds[0] = "true".toCharArray();
            creds[1] = masterPassword;
            creds[2] = (username != null && !username.isEmpty())
                    ? username.toCharArray() 
                    : "single-user".toCharArray();
            creds[3] = VaultLevel.toCharArray();
            creds[4] = DATABASE_TYPE.toCharArray();

            boolean good = testPasswordStrength(masterPassword, p2);
            Yggdrasil.wipeCharArray(p2);
            if (good) return creds;
        }
    }

    // ===== ADD USER =====
    private void useraddEntry(String current_username) {
        JTextField     userField  = new JTextField();
        JPasswordField passField1 = new JPasswordField();
        JPasswordField passField2 = new JPasswordField();

        int option = JOptionPane.showConfirmDialog(mainFrame,
            new Object[]{"New Username:", userField, "Create Password:", passField1,
                         "Confirm Password:", passField2},
            "Add User", JOptionPane.OK_CANCEL_OPTION);

        if (option == JOptionPane.OK_OPTION) {
            boolean good = testPasswordStrength(passField1.getPassword(), passField2.getPassword());
            if (good && !userField.getText().equals(current_username)) {
                try {
                    backend.useraddEntry(conn, userField.getText(), passField1.getPassword());
                    ToastManager.success(mainFrame, userField.getText() + " added.");
                } catch (Exception e) {
                    e.printStackTrace();
                    ToastManager.error(mainFrame, "Failed to add user: " + userField.getText());
                } 
            } else {ToastManager.error(mainFrame, "Failed to add user: " + userField.getText());}
        }
    }

    // ===== DELETE USER =====
    private void userdelEntry(String current_username) {
        JTextField userField = new JTextField();

        int option = JOptionPane.showConfirmDialog(mainFrame,
            new Object[]{"Username:", userField}, "Delete User", JOptionPane.OK_CANCEL_OPTION);

        if (option == JOptionPane.OK_OPTION && !userField.getText().equals(current_username)) {
            try {
                databaseutilities.userdelEntry(conn, userField.getText());
                ToastManager.success(mainFrame, userField.getText() + " deleted.");
            } catch (Exception e) {
                e.printStackTrace();
                ToastManager.error(mainFrame, "Failed to delete user: " + userField.getText());
            }
        } else {ToastManager.error(mainFrame, "Failed to add user: " + userField.getText());}
    }
}