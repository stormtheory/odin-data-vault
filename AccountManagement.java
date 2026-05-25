import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.sql.Connection;
import java.util.*;
import javax.swing.*;
import org.json.*;
import java.awt.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ===== ODIN ACCOUNT MANAGEMENT =====
 
 */
public class AccountManagement {
    // ===== STATIC SHARED FIELDS =====
    protected static Mimir  databaseutilities = new Mimir();
    protected static Odin   odin = new Odin();

    public static boolean DEBUG = false;

    // ===== DEPENDENCIES =====
    private static int       PASSWORD_LENGTH      = 12;
    private final Yggdrasil  backend;
    private final Connection conn;
    private final String     dbType;
    private final String     username;
    private static String     VaultLevel;
    private static String     DATABASE_TYPE;
    private static JFrame     parent;
    private static char[]     masterPassword;

    public AccountManagement(Yggdrasil backend, Connection conn, String dbType, String username, String vaultlevel, String type, int pass_len, boolean debug) {
        DEBUG                = debug;
        this.backend         = backend;
        this.conn            = conn;
        this.dbType          = dbType;
        this.username        = username;
        VaultLevel           = vaultlevel;
        DATABASE_TYPE        = type;
        PASSWORD_LENGTH      = pass_len;
        Odin odin = new Odin();
    }

   
    // ===================================================================
    // ===== SWING UI DIALOG
    // ===================================================================

    /**
     * Shows the import/export dialog.
     * Called from the Odin toolbar - parent frame needed for modal anchoring.
     */
    public void showManagerPane(JFrame MainFrame, List<Yggdrasil.Credential> credentials, Runnable onImportComplete) {
        
        parent = MainFrame;

        // ===== OUTER DIALOG =====
        JDialog dialog = new JDialog(parent, "Account: " + username, true);
        dialog.setSize(520, 420);
        dialog.setLocationRelativeTo(parent);
        dialog.setResizable(false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel();
        root.setBackground(ThemeManager.BG);
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        // ===== TITLE =====
        JLabel title = new JLabel("User Management");
        title.setFont(new java.awt.Font("Segoe UI", java.awt.Font.BOLD, 15));
        title.setForeground(ThemeManager.ACCENT);
        title.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        root.add(title);
        root.add(Box.createVerticalStrut(16));
        
        JButton changeMasterPassBtn = new JButton("🔐 Change Vault Password");


        ThemeManager.styleSurfaceButton(changeMasterPassBtn);

        //addBtn.addActionListener(e              -> addUpdateEntry("add"));
        //updateBtn.addActionListener(e           -> addUpdateEntry("update"));
        //delBtn.addActionListener(e              -> deleteEntry());
        
        changeMasterPassBtn.addActionListener(e -> changeMasterPass(username));

        root.add(changeMasterPassBtn);

        // ===== MULTI-USER CONTROLS - only visible for multi-user vault =====
        if (DATABASE_TYPE.equals("m")) {
            JButton useraddBtn = new JButton("👤＋ Add User");
            JButton userdelBtn = new JButton("👤✕ Del User");
            ThemeManager.styleSurfaceButton(useraddBtn);
            ThemeManager.styleDangerButton(userdelBtn);
            useraddBtn.addActionListener(e -> useraddEntry(username));
            userdelBtn.addActionListener(e -> userdelEntry(username));
            root.add(useraddBtn);
            root.add(userdelBtn);
        }
        


        dialog.setContentPane(root);
        dialog.setVisible(true);
   }

   // ===== CHANGE MASTER PASSWORD =====
    protected void changeMasterPass(String username) {
        char[][] creds = odin.createNewMasterPass(conn, false,false,"From_SQL",PASSWORD_LENGTH);
        
        try {
                masterPassword = creds[1];
                username = new String(creds[2]);
                VaultLevel = new String(creds[3]);
                DATABASE_TYPE = new String(creds[4]);
                backend.changeMasterPass(conn, masterPassword, username);
                ToastManager.success(parent, "Password changed for " + username);
            } catch (Exception e) {
                e.printStackTrace();
                ToastManager.error(parent, "Failed to change account password.");
            }
            Yggdrasil.wipeCharArray(masterPassword);
        }

            // ===== ADD USER =====
    protected void useraddEntry(String current_username) {
        JTextField     userField  = new JTextField();
        JPasswordField passField1 = new JPasswordField();
        JPasswordField passField2 = new JPasswordField();

        int option = JOptionPane.showConfirmDialog(parent,
            new Object[]{"New Username:", userField, "Create Password:", passField1,
                         "Confirm Password:", passField2},
            "Add User", JOptionPane.OK_CANCEL_OPTION);

        if (option == JOptionPane.OK_OPTION) {
            boolean good = odin.testPasswordStrength(passField1.getPassword(), passField2.getPassword());
            if (good && !userField.getText().equals(current_username)) {
                try {
                    backend.useraddEntry(conn, userField.getText(), passField1.getPassword());
                    ToastManager.success(parent, userField.getText() + " added.");
                } catch (Exception e) {
                    e.printStackTrace();
                    ToastManager.error(parent, "Failed to add user: " + userField.getText());
                } 
            } else {ToastManager.error(parent, "Failed to add user: " + userField.getText());}
        }
    }

    // ===== DELETE USER =====
    protected void userdelEntry(String current_username) {
        JTextField userField = new JTextField();

        int option = JOptionPane.showConfirmDialog(parent,
            new Object[]{"Username:", userField}, "Delete User", JOptionPane.OK_CANCEL_OPTION);

        if (option == JOptionPane.OK_OPTION && !userField.getText().equals(current_username)) {
            try {
                databaseutilities.userdelEntry(conn, userField.getText());
                ToastManager.success(parent, userField.getText() + " deleted.");
            } catch (Exception e) {
                e.printStackTrace();
                ToastManager.error(parent, "Failed to delete user: " + userField.getText());
            }
        } else {ToastManager.error(parent, "Failed to add user: " + userField.getText());}
    }
}