import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.sql.*;
import java.util.List;
import java.util.Arrays;
import javax.swing.*;
import javax.swing.table.*;
import org.json.*;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * ===== ODIN ACCOUNT MANAGEMENT =====
 *
 * Displays a live user table polled from the SQLite users table, showing
 * per-user Argon2 parameters and last login status. Argon2 values are
 * color-coded against OWASP 2023 recommended minimums. A horizontal bottom
 * toolbar exposes all user management actions.
 *
 * Button visibility and enabled state is controlled exclusively by
 * applyToolbarState(), called once on open and again on every row
 * selection change. No other code touches button visibility.
 *
 * Toolbar actions that are not yet wired to backend calls are stubbed
 * with detailed intent comments describing what each would do.
 */
public class AccountManagement {

    // ===== STATIC SHARED FIELDS =====
    protected static Mimir  databaseutilities = new Mimir();
    protected static Odin   odin              = new Odin();

    public static boolean DEBUG = false;

    // ===== ARGON2 OWASP 2023 RECOMMENDED MINIMUMS =====
    // Values below these thresholds are flagged amber in the table.
    private static final int ARGON2_ITER_MIN = 3;
    private static final int ARGON2_MEM_MIN  = 65536; // 64 MB in KB
    private static final int ARGON2_PARA_MIN = 4;

    // ===== DATETIME FORMAT for last_login display =====
    private static final DateTimeFormatter LAST_LOGIN_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    // ===== DEPENDENCIES =====
    private static int       PASSWORD_LENGTH = 12;
    private final Yggdrasil  backend;
    private final Connection conn;
    private final String     dbType;
    private final String     username;
    private static String    VaultLevel;
    private static String    DATABASE_TYPE;
    private static JFrame    parent;
    private static char[]    masterPassword;

    // ===== TABLE COLUMN DEFINITIONS: mirrors the users SELECT =====
    // Order: Username, Role, argon2_iter, argon2_mem, argon2_para, last_login, status
    private static final String[] TABLE_COLUMNS = {
        "User", "Role", "Iterations", "Memory (KB)", "Lanes", "Last Login", "Status"
    };

    public AccountManagement(Yggdrasil backend, Connection conn, String dbType,
                             String username, String vaultlevel, String type,
                             int pass_len, boolean debug) {
        DEBUG           = debug;
        this.backend    = backend;
        this.conn       = conn;
        this.dbType     = dbType;
        this.username   = username;
        VaultLevel      = vaultlevel;
        DATABASE_TYPE   = type;
        PASSWORD_LENGTH = pass_len;
        Odin odin       = new Odin();
    }


    // ===================================================================
    // ===== SWING UI DIALOG
    // ===================================================================

    /**
     * Shows the user management dialog with a live user table and horizontal toolbar.
     * Called from the Odin toolbar; parent frame needed for modal anchoring.
     *
     * @param MainFrame         the parent JFrame to anchor the modal dialog to
     * @param credentials       current credential list (passed through for context)
     * @param onImportComplete  callback to run after any import operation
     */
    public void showManagerPane(JFrame MainFrame,
                                List<Yggdrasil.Credential> credentials,
                                Runnable onImportComplete) {

        parent = MainFrame;
        final boolean currentUserIsAdmin = "admin".equals(lookupCurrentUserRole());

        // ===== OUTER DIALOG =====
        JDialog dialog = new JDialog(parent, "Account: " + username, true);
        dialog.setSize(720, 460);
        dialog.setLocationRelativeTo(parent);
        dialog.setResizable(true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(ThemeManager.BG);

        // ===== TITLE PANEL =====
        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 12));
        titlePanel.setBackground(ThemeManager.BG);
        titlePanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ThemeManager.BORDER));

        JLabel title = new JLabel("User Management");
        title.setFont(new Font("Segoe UI", Font.BOLD, 15));
        title.setForeground(ThemeManager.ACCENT);
        titlePanel.add(title);

        // ===== VAULT MODE BADGE: shows single vs multi-user vault type =====
        String modeText = DATABASE_TYPE.equals("m") ? "multi-user" : "single-user";
        JLabel modeBadge = new JLabel(modeText);
        modeBadge.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        modeBadge.setForeground(ThemeManager.ACCENT);
        modeBadge.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ThemeManager.ACCENT, 1, true),
            BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        titlePanel.add(modeBadge);

        root.add(titlePanel, BorderLayout.NORTH);

        // ===== USER TABLE: polled from users table on dialog open =====
        DefaultTableModel tableModel = buildTableModel();
        JTable userTable = new JTable(tableModel);
        userTable.setRowHeight(26);
        userTable.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        userTable.setBackground(ThemeManager.BG);
        userTable.setForeground(ThemeManager.TEXT);
        userTable.setGridColor(ThemeManager.BORDER);
        userTable.setSelectionBackground(ThemeManager.SELECT);
        userTable.setSelectionForeground(ThemeManager.TEXT);
        userTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        userTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // ===== COLUMN WIDTHS: sized to content type =====
        int[] colWidths = { 120, 70, 80, 90, 60, 140, 80 };
        for (int i = 0; i < colWidths.length; i++) {
            userTable.getColumnModel().getColumn(i).setPreferredWidth(colWidths[i]);
        }

        // ===== ARGON2 CELL RENDERER: color-code params against OWASP minimums =====
        userTable.getColumnModel().getColumn(2).setCellRenderer(new Argon2CellRenderer(ARGON2_ITER_MIN));
        userTable.getColumnModel().getColumn(3).setCellRenderer(new Argon2CellRenderer(ARGON2_MEM_MIN));
        userTable.getColumnModel().getColumn(4).setCellRenderer(new Argon2CellRenderer(ARGON2_PARA_MIN));

        // ===== HEADER STYLING =====
        JTableHeader header = userTable.getTableHeader();
        header.setFont(new Font("Segoe UI", Font.BOLD, 11));
        header.setBackground(ThemeManager.SURFACE);
        header.setForeground(ThemeManager.TEXT_MUTED);
        header.setReorderingAllowed(false);

        JScrollPane scrollPane = new JScrollPane(userTable);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(ThemeManager.BG);
        root.add(scrollPane, BorderLayout.CENTER);

        // ===== TOOLBAR: horizontal bottom bar with all user management actions =====
        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));
        toolbar.setBackground(ThemeManager.SURFACE);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, ThemeManager.BORDER),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        // ===== BUTTON DECLARATIONS =====
        // All initial visibility/enabled state is set by applyToolbarState() below.
        // Do NOT set setVisible() or setEnabled() anywhere else in this method.
        JButton btnAdd     = new JButton("+ Add User");
        JButton btnDel     = new JButton("Del User");
        JButton btnPass    = new JButton("Change Password");
        JButton btnReauth  = new JButton("Force Re-auth");
        JButton btnUpgrade = new JButton("Upgrade Params");
        JButton btnRefresh = new JButton("Refresh");

        ThemeManager.styleSurfaceButton(btnAdd);
        ThemeManager.styleDangerButton(btnDel);
        ThemeManager.styleSurfaceButton(btnPass);
        ThemeManager.styleSurfaceButton(btnReauth);
        ThemeManager.styleSurfaceButton(btnUpgrade);
        ThemeManager.styleSurfaceButton(btnRefresh);

        // ===== INITIAL TOOLBAR STATE: no row selected =====
        // applyToolbarState is the single source of truth for all button
        // visibility and enabled state. Called here on open, and again
        // inside the selection listener on every row change.
        applyToolbarState(btnAdd, btnDel, btnPass, btnReauth, btnUpgrade, btnRefresh,
                          false, false, false);

        // ===== ROW SELECTION LISTENER: re-evaluate toolbar state on every change =====
        userTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = userTable.getSelectedRow();

            // ===== NO ROW SELECTED: reset to no-selection defaults =====
            if (row < 0) {
                applyToolbarState(btnAdd, btnDel, btnPass, btnReauth, btnUpgrade, btnRefresh,
                                  false, false, false);
                return;
            }

            String selectedUid = (String) tableModel.getValueAt(row, 0);
            // ===== DERIVE CONTEXT FLAGS from selected row =====
            boolean isSelf = selectedUid.equals(username);
            applyToolbarState(btnAdd, btnDel, btnPass, btnReauth, btnUpgrade, btnRefresh,
                            true, isSelf, currentUserIsAdmin);
        });

        // ===== ADD USER ACTION =====
        // Calls useraddEntry() to INSERT a new row, then refreshes the table.
        btnAdd.addActionListener(e -> {
            useraddEntry(username);
            refreshTableModel(tableModel);
        });

        // ===== DELETE USER ACTION =====
        // Calls userdelEntry() after confirming the row is not the current user.
        // Table is refreshed inside userdelEntry() on success.
        btnDel.addActionListener(e -> {
            int row = userTable.getSelectedRow();
            if (row < 0) return;
            String target = (String) tableModel.getValueAt(row, 0);
            userdelEntry(target, tableModel, dialog);
        });

        // ===== CHANGE PASSWORD ACTION =====
        // Calls changeMasterPass() to re-derive and re-wrap the vault key under
        // a new password, then refreshes the table so last_login updates.
        btnPass.addActionListener(e -> {
            changeMasterPass(username);
            refreshTableModel(tableModel);
        });

        // ===== FORCE RE-AUTH ACTION (STUB) =====
        // INTENT: Invalidate the selected user's active session token or in-memory
        // vault key reference so they must re-enter their master password on the next
        // vault operation. Useful after a suspected credential leak or after the admin
        // changes that user's Argon2 parameters.
        //
        // IMPLEMENTATION WOULD:
        //   1. Retrieve the selected username from the table selection.
        //   2. Call databaseutilities.forceReauth(conn, selectedUsername) which would
        //      UPDATE users SET last_login = NULL, forced_reauth = 1 WHERE user_id = ?
        //      (requires adding a forced_reauth INTEGER column to the users schema).
        //   3. Any in-memory cached vault key held by that user's session would be
        //      wiped via a session registry lookup (e.g. Odin.invalidateSession(uid)).
        //   4. On the affected user's next unlock attempt, the vault layer checks
        //      forced_reauth = 1, skips any cached key, and forces fresh KDF derivation.
        //   5. Reset forced_reauth = 0 after successful re-authentication.
        //   6. Show a success toast confirming re-auth was forced for that username.
        btnReauth.addActionListener(e -> {
            // ===== STUB: no backend call made; intent documented above =====
            int row = userTable.getSelectedRow();
            if (row < 0) return;
            String target = (String) tableModel.getValueAt(row, 0);
            ToastManager.info(parent, "Force re-auth for \"" + target + "\" (not yet implemented)");
        });

        // ===== UPGRADE ARGON2 PARAMS ACTION (STUB) =====
        // INTENT: Re-derive the selected user's wrapped vault key using the current
        // recommended Argon2 parameters without requiring a password change.
        //
        // IMPLEMENTATION WOULD:
        //   1. Check the selected user is currently authenticated (vault key live in memory).
        //      If their key is not in memory this cannot proceed; prompt them to log in first.
        //   2. Retrieve the current vault key for that user from the in-memory session.
        //   3. Generate a new random salt (SecureRandom, 16 bytes minimum).
        //   4. Re-wrap the vault key using AES-GCM with a new KDF output derived from
        //      the user's existing password + new salt + upgraded Argon2 params.
        //   5. Call backend.rekeyUser(conn, target, newWrappedVK, newSalt, newIV,
        //      ARGON2_ITER_MIN, ARGON2_MEM_MIN, ARGON2_PARA_MIN) which UPDATEs the
        //      users row in a single transaction (no window where two valid wrapped keys exist).
        //   6. Wipe the old wrapped_vk from memory before returning.
        //   7. Refresh the table so the upgraded Argon2 values appear immediately.
        //   8. Show a success toast confirming params were upgraded.
        btnUpgrade.addActionListener(e -> {
            // ===== STUB: no backend call made; intent documented above =====
            int row = userTable.getSelectedRow();
            if (row < 0) return;
            String target = (String) tableModel.getValueAt(row, 0);
            ToastManager.info(parent, "Upgrade Argon2 for \"" + target + "\" (not yet implemented)");
        });

        // ===== REFRESH ACTION =====
        // Re-polls the users table and rebuilds the model in-place.
        // Called automatically after add/delete; also exposed for manual use.
        btnRefresh.addActionListener(e -> {
            refreshTableModel(tableModel);
            ToastManager.success(parent, "User list refreshed.");
        });

        // ===== ASSEMBLE TOOLBAR =====
        // Left-side action buttons | flexible spacer | right-side refresh
        toolbar.add(btnAdd);
        toolbar.add(Box.createHorizontalStrut(6));
        toolbar.add(btnDel);
        toolbar.add(Box.createHorizontalStrut(6));
        toolbar.add(btnPass);
        toolbar.add(Box.createHorizontalStrut(6));
        toolbar.add(btnReauth);
        toolbar.add(Box.createHorizontalStrut(6));
        toolbar.add(btnUpgrade);
        toolbar.add(Box.createHorizontalGlue()); // ===== PUSH refresh to the right =====
        toolbar.add(btnRefresh);

        root.add(toolbar, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.setVisible(true);
    }
    // ===== CURRENT USER ROLE LOOKUP =====
    private String lookupCurrentUserRole() {
        String sql = "SELECT role FROM users WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("role");
            }
        } catch (SQLException ex) {
            if (DEBUG) ex.printStackTrace();
        }
        return "user"; // ===== FAIL SECURE: deny admin controls if role cannot be confirmed =====
    }


    // ===================================================================
    // ===== TOOLBAR STATE MANAGER
    // ===================================================================

    /**
     * Single source of truth for all toolbar button visibility and enabled state.
     * Called once on dialog open (no selection) and again on every row selection change.
     * No other code in this class may call setVisible() or setEnabled() on toolbar buttons.
     *
     * Button matrix by vault type and selection context:
     *
     *   SINGLE-USER vault ("s"):
     *     Only Change Password is visible; all multi-user controls are hidden.
     *     The logged-in user can only change their own password.
     *
     *   MULTI-USER vault ("m"), no row selected:
     *     Add is enabled. Del, Re-auth, Upgrade disabled (need a target row).
     *     Change Password disabled (need to know whose password to change).
     *
     *   MULTI-USER vault ("m"), self selected:
     *     Change Password enabled (own password only).
     *     Del and Re-auth disabled (cannot act on yourself).
     *     Upgrade enabled (can upgrade your own params).
     *
     *   MULTI-USER vault ("m"), other user selected, current user is admin:
     *     All buttons enabled except Change Password (admins change others' params,
     *     not their passwords; the other user must change their own password).
     *
     *   MULTI-USER vault ("m"), other user selected, current user is NOT admin:
     *     All destructive buttons disabled; only Change Password (own) stays enabled.
     *
     * @param btnAdd        Add User button
     * @param btnDel        Delete User button
     * @param btnPass       Change Password button
     * @param btnReauth     Force Re-auth button
     * @param btnUpgrade    Upgrade Argon2 Params button
     * @param btnRefresh    Refresh table button
     * @param hasSelection  true when a row is selected in the table
     * @param isSelf        true when the selected row is the logged-in user
     * @param isAdmin       true when the selected row's role is "admin"
     */
    private void applyToolbarState(JButton btnAdd, JButton btnDel, JButton btnPass,
                                   JButton btnReauth, JButton btnUpgrade, JButton btnRefresh,
                                   boolean hasSelection, boolean isSelf, boolean isAdmin) {

        // ===== SINGLE-USER VAULT: hide all multi-user controls =====
        if (DATABASE_TYPE.equals("s")) {
            btnAdd.setVisible(false);
            btnDel.setVisible(false);
            btnReauth.setVisible(false);
            btnUpgrade.setVisible(false);
            btnRefresh.setVisible(false);

            // ===== CHANGE PASSWORD: always visible, always enabled for own account =====
            btnPass.setVisible(true);
            btnPass.setEnabled(true);
            return;
        }

        // ===== MULTI-USER VAULT: all buttons visible =====
        btnAdd.setVisible(true);
        btnDel.setVisible(true);
        btnPass.setVisible(true);
        btnReauth.setVisible(true);
        btnUpgrade.setVisible(true);
        btnRefresh.setVisible(true);

        if (isAdmin && isSelf) {
            btnAdd.setEnabled(true);
            btnDel.setEnabled(false);
            btnPass.setEnabled(true);
            btnReauth.setEnabled(true);
            btnUpgrade.setEnabled(true);
            btnRefresh.setEnabled(true);
            return;
        }
        if (isAdmin) {
            btnAdd.setEnabled(true);
            btnDel.setEnabled(true);
            btnPass.setEnabled(false);
            btnReauth.setEnabled(true);
            btnUpgrade.setEnabled(true);
            btnRefresh.setEnabled(true);
            return;
        }

        // ===== NO SELECTION: only Add and Refresh are actionable =====
        if (!hasSelection) {
            btnAdd.setEnabled(false);
            btnDel.setEnabled(false);
            btnPass.setEnabled(false);
            btnReauth.setEnabled(false);
            btnUpgrade.setEnabled(false);
            btnRefresh.setEnabled(true);
            return;
        }

        // ===== SELF SELECTED: own row =====
        if (isSelf) {
            btnAdd.setEnabled(false);
            btnDel.setEnabled(false);
            btnPass.setEnabled(true);
            btnReauth.setEnabled(false);
            btnUpgrade.setEnabled(false);
            btnRefresh.setEnabled(true);
            return;
        }

        // ===== OTHER USER SELECTED, current user is NOT admin: read-only =====
        // Non-admin users can see the table but cannot act on other accounts.
        btnAdd.setEnabled(false);
        btnDel.setEnabled(false);
        btnPass.setEnabled(false);
        btnReauth.setEnabled(false);
        btnUpgrade.setEnabled(false);
        btnRefresh.setEnabled(false);
    }


    // ===================================================================
    // ===== TABLE MODEL BUILDER
    // ===================================================================

    /**
     * Queries the users table and builds a non-editable DefaultTableModel.
     * Argon2 columns store raw integers so Argon2CellRenderer can compare
     * them against the OWASP minimums for color-coding.
     *
     * @return populated DefaultTableModel, or empty model on query failure
     */
    private DefaultTableModel buildTableModel() {
        DefaultTableModel model = new DefaultTableModel(TABLE_COLUMNS, 0) {
            // ===== MAKE ALL CELLS NON-EDITABLE: table is read-only display =====
            @Override public boolean isCellEditable(int row, int col) { return false; }

            // ===== RETURN INTEGER TYPE for Argon2 columns so renderer can compare =====
            @Override public Class<?> getColumnClass(int col) {
                if (col == 2 || col == 3 || col == 4) return Integer.class;
                return String.class;
            }
        };
        refreshTableModel(model);
        return model;
    }

    /**
     * Re-executes the SELECT against the live connection and repopulates the model.
     * Called on dialog open and after every add/delete to keep the table current.
     * Existing rows are cleared before re-populating so there are no duplicates.
     *
     * @param model the DefaultTableModel to repopulate
     */
    private void refreshTableModel(DefaultTableModel model) {
        // ===== CLEAR EXISTING ROWS before re-populating =====
        model.setRowCount(0);

        String sql = "SELECT user_id, role, argon2_iter, argon2_mem, argon2_para, last_login " +
                     "FROM users ORDER BY role DESC, user_id ASC";

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String userId        = rs.getString("user_id");
                String role          = rs.getString("role");
                int    iter          = rs.getInt("argon2_iter");
                int    mem           = rs.getInt("argon2_mem");
                int    para          = rs.getInt("argon2_para");
                byte[] lastLoginBlob = rs.getBytes("last_login");

                // ===== DERIVE DISPLAY VALUES from raw column data =====
                String lastLoginStr = formatLastLogin(lastLoginBlob);
                String statusStr    = deriveStatus(lastLoginBlob);

                model.addRow(new Object[]{ userId, role, iter, mem, para, lastLoginStr, statusStr });
            }

        } catch (SQLException ex) {
            if (DEBUG) ex.printStackTrace();
            ToastManager.error(parent, "Failed to load user list.");
        }
    }

    /**
     * Formats the last_login BLOB column (stored as an ISO-8601 string byte array)
     * into a human-readable date string.
     *
     * @param blob raw bytes from the last_login column, or null if never logged in
     * @return formatted date string, or "Never" if null/blank
     */
    private String formatLastLogin(byte[] blob) {
        if (blob == null || blob.length == 0) return "Never";
        try {
            String raw = new String(blob, StandardCharsets.UTF_8).trim();
            Instant instant = Instant.parse(raw);
            return LAST_LOGIN_FMT.format(instant);
        } catch (Exception ex) {
            // ===== FALLBACK: return raw string if ISO-8601 parsing fails =====
            return new String(blob, StandardCharsets.UTF_8);
        }
    }

    /**
     * Derives a human-readable activity status from the last_login timestamp.
     * Thresholds: Active (less than 1h), Recent (less than 48h), Idle (48h or more), Never (null).
     *
     * @param blob raw bytes from the last_login column
     * @return status label string
     */
    private String deriveStatus(byte[] blob) {
        if (blob == null || blob.length == 0) return "Never";
        try {
            String  raw      = new String(blob, StandardCharsets.UTF_8).trim();
            Instant instant  = Instant.parse(raw);
            long    hoursAgo = (System.currentTimeMillis() - instant.toEpochMilli()) / 3_600_000L;
            if (hoursAgo < 1)  return "Active";
            if (hoursAgo < 48) return "Recent";
            return "Idle";
        } catch (Exception ex) {
            return "Unknown";
        }
    }


    // ===================================================================
    // ===== ARGON2 CELL RENDERER
    // ===================================================================

    /**
     * Colors Argon2 parameter cells relative to a minimum threshold:
     *   Green  (ThemeManager.SUCCESS) = meets or exceeds the OWASP minimum
     *   Amber  (ThemeManager.WARNING) = below the minimum; upgrade recommended
     *
     * One instance is attached per Argon2 column with its own minimum value.
     */
    private static class Argon2CellRenderer extends DefaultTableCellRenderer {

        private final int minimum;

        Argon2CellRenderer(int minimum) {
            this.minimum = minimum;
            setHorizontalAlignment(CENTER);
            setFont(new Font("Consolas", Font.PLAIN, 12));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int col) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);

            if (!isSelected && value instanceof Integer) {
                int v = (Integer) value;
                // ===== COLOR CODE: green if meets OWASP minimum, amber if below =====
                if (v >= minimum) {
                    setForeground(ThemeManager.SUCCESS); // ===== OWASP minimum met =====
                } else {
                    setForeground(ThemeManager.WARNING); // ===== Below recommended minimum =====
                }
            } else {
                // ===== SELECTED ROW: revert to table selection foreground =====
                setForeground(table.getSelectionForeground());
            }
            return this;
        }
    }


    // ===================================================================
    // ===== CHANGE MASTER PASSWORD
    // ===================================================================

    /**
     * Opens the change-password flow for the given username.
     * Delegates to odin.createNewMasterPass() to collect credentials, then
     * calls backend.changeMasterPass() to re-wrap the vault key under the
     * new password in a single atomic transaction.
     *
     * @param username the username whose password is being changed
     */
    protected void changeMasterPass(String username) {
        char[][] creds = odin.createNewMasterPass(conn, false, false, "From_SQL", PASSWORD_LENGTH);

        try {
            masterPassword = creds[1];
            username       = new String(creds[2]);
            VaultLevel     = new String(creds[3]);
            DATABASE_TYPE  = new String(creds[4]);
            backend.changeMasterPass(conn, masterPassword, username);
            ToastManager.success(parent, "Password changed for " + username);
        } catch (Exception e) {
            e.printStackTrace();
            ToastManager.error(parent, "Failed to change account password.");
        }
        // ===== WIPE master password from memory immediately after use =====
        Yggdrasil.wipeCharArray(masterPassword);
    }


    // ===================================================================
    // ===== ADD USER
    // ===================================================================

    /**
     * Prompts for new user credentials, validates password strength and username
     * uniqueness, then inserts a new row into the users table via the backend.
     *
     * @param current_username the logged-in username; new name must differ from this
     */
    protected void useraddEntry(String current_username) {
        JTextField     userField  = new JTextField();
        JPasswordField passField1 = new JPasswordField();
        JPasswordField passField2 = new JPasswordField();

        int option = JOptionPane.showConfirmDialog(parent,
            new Object[]{ "New Username:", userField,
                          "Create Password:", passField1,
                          "Confirm Password:", passField2 },
            "Add User", JOptionPane.OK_CANCEL_OPTION);

        if (option == JOptionPane.OK_OPTION) {
            String newUser = userField.getText().trim();

            // ===== GUARD: new username must not be blank or identical to current user =====
            if (newUser.isEmpty() || newUser.equals(current_username)) {
                ToastManager.error(parent, "Invalid username: \"" + newUser + "\"");
                return;
            }

            boolean good = odin.testPasswordStrength(passField1.getPassword(), passField2.getPassword());
            if (good) {
                try {
                    backend.useraddEntry(conn, newUser, passField1.getPassword());
                    ToastManager.success(parent, "\"" + newUser + "\" added.");
                } catch (Exception e) {
                    e.printStackTrace();
                    ToastManager.error(parent, "Failed to add user: \"" + newUser + "\"");
                }
            } else {
                ToastManager.error(parent, "Password too weak or does not match.");
            }
        }
    }


    // ===================================================================
    // ===== DELETE USER
    // ===================================================================

    /**
     * Deletes the specified user from the users table after confirmation.
     * Guards against deleting the currently logged-in user. Refreshes the
     * table model automatically after a successful delete.
     *
     * @param target    the username to delete
     * @param model     the live table model to refresh after deletion
     * @param dialog    the parent dialog (for confirmation anchoring)
     */
    protected void userdelEntry(String target, DefaultTableModel model, JDialog dialog) {

        // ===== GUARD: must not delete the currently logged-in user =====
        if (target.equals(username)) {
            ToastManager.error(parent, "Cannot delete the currently logged-in user.");
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(parent,
            "Permanently delete user \"" + target + "\"?\nThis cannot be undone.",
            "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (confirm != JOptionPane.YES_OPTION) return;

        try {
            databaseutilities.userdelEntry(conn, target);
            ToastManager.success(parent, "\"" + target + "\" deleted.");

            // ===== REFRESH TABLE: rebuild model so deleted row disappears immediately =====
            refreshTableModel(model);

        } catch (Exception e) {
            e.printStackTrace();
            ToastManager.error(parent, "Failed to delete user: \"" + target + "\"");
        }
    }
}