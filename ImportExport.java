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

import de.mkammerer.argon2.Argon2Advanced;
import de.mkammerer.argon2.Argon2Factory;
import de.mkammerer.argon2.Argon2Factory.Argon2Types;

/**
 * ===== ODIN VAULT IMPORT / EXPORT =====
 *
 * Five operations selectable via dropdown:
 *   1. Export  : Odin encrypted backup (Argon2id + AES-256-GCM)
 *   2. Export  : Bitwarden encrypted JSON (PBKDF2-SHA256 + AES-256-CBC)
 *   3. Import  : Odin encrypted backup
 *   4. Import  : Bitwarden encrypted JSON (PBKDF2-SHA256 + AES-256-CBC)
 *   5. Import  : Bitwarden unencrypted JSON (via RAM disk)
 *
 * =========================================================================
 * ===== BITWARDEN ENCRYPTED FORMAT - CRITICAL IMPLEMENTATION NOTES ========
 * =========================================================================
 *
 * After extensive debugging, the following was confirmed by reading the
 * compiled Bitwarden CLI source (bw.js) directly. These facts are NOT
 * documented anywhere publicly and contradict every crypto tutorial.
 *
 * ----- SALT ENCODING (the bug that took weeks to find) -----
 *
 *   In Bitwarden's deriveKeyFromPassword (key-generation.service.ts):
 *
 *     if (typeof salt === "string") {
 *         salt = new TextEncoder().encode(salt);  // UTF-8 encode the STRING
 *     }
 *
 *   The salt stored in the JSON file is a base64 string, e.g.:
 *     "salt": "GySYSCvSdeP2WDWOFAVjvw=="
 *
 *   Bitwarden passes that base64 STRING through TextEncoder, producing
 *   the UTF-8 bytes of the characters "GySYSCvSdeP2WDWOFAVjvw==" (24 bytes).
 *
 *   It does NOT base64-decode the salt first. Every previous attempt
 *   decoded the salt to 16 raw bytes before PBKDF2, which produced a
 *   completely different master key and the "invalid file password" error.
 *
 *   Java equivalent:
 *     CORRECT:   saltB64String.getBytes(StandardCharsets.UTF_8)
 *     WRONG:     Base64.getDecoder().decode(saltB64String)
 *
 * ----- KEY DERIVATION PIPELINE -----
 *
 *   1. password  -> UTF-8 bytes (TextEncoder, matches Java UTF-8)
 *   2. salt      -> UTF-8 bytes of the base64 STRING (see above)
 *   3. PBKDF2-SHA256(passwordBytes, saltBytes, kdfIterations, 256-bit) -> masterKey
 *   4. HKDF-Expand(masterKey, info="enc", 32 bytes) -> encKey
 *   5. HKDF-Expand(masterKey, info="mac", 32 bytes) -> macKey
 *
 *   Note: HKDF input is the raw 32-byte masterKey, not a SymmetricCryptoKey object.
 *   Bitwarden's stretchKey uses key.inner().encryptionKey which is exactly the
 *   raw 32 bytes from the KDF output.
 *
 * ----- encKeyValidation_DO_NOT_EDIT -----
 *
 *   Bitwarden source: encryptString(Utils.newGuid(), key)
 *   This encrypts a random UUID string (e.g. "a7d30425-5694-4961-b8d4-d887d904e037").
 *   It does NOT encrypt empty bytes. The importer decrypts this field and
 *   checks the result is a valid non-empty string to verify the key before
 *   attempting to decrypt the full data blob.
 *   Encrypting empty bytes causes "invalid file password" on import.
 *
 * ----- CIPHER FORMAT -----
 *
 *   CipherString type "2" = AES-256-CBC + HMAC-SHA256 (Bitwarden native)
 *   Format: "2.{iv_b64}|{ciphertext_b64}|{mac_b64}"
 *   MAC covers: IV || ciphertext (Encrypt-then-MAC)
 *
 * ----- PBKDF2 IMPLEMENTATION -----
 *
 *   Java's JCE PBEKeySpec(char[]) encodes the password as UTF-16BE internally.
 *   Bitwarden uses UTF-8 (TextEncoder). For any non-ASCII password these
 *   diverge. Use BouncyCastle PKCS5S2ParametersGenerator with explicit
 *   UTF-8 byte[] to match Bitwarden exactly.
 *
 * ----- WHAT BITWARDEN CHECKS ON IMPORT -----
 *
 *   1. Reads kdfType, kdfIterations from outer JSON
 *   2. Derives key using: deriveVaultExportKey(password, salt, kdfConfig)
 *      which calls: stretchKey(deriveKeyFromPassword(password, salt, kdfConfig))
 *   3. Decrypts encKeyValidation_DO_NOT_EDIT with that key
 *   4. If decryption throws -> "invalid file password"
 *   5. If decryption succeeds -> decrypts data field -> imports items
 *
 * =========================================================================
 *
 * Odin backup crypto stack:
 *   KDF         : Argon2id - parameters from vault security profile
 *   Key stretch : HKDF-SHA256 expand only, "enc" + "mac" labels
 *   Cipher      : AES-256-GCM (authenticated encryption, no separate HMAC)
 *   IV/Nonce    : 12 bytes (96-bit, NIST SP 800-38D recommended)
 *   Auth tag    : 128-bit (maximum GCM tag size)
 *   CipherString: "3.{iv_b64}|{ct_with_tag_b64}"
 */
public class ImportExport {

    public static boolean DEBUG = false;
    private boolean isRamImport;
    private boolean isExport;
    private static String filename = null;

    // ===== CIPHER / CRYPTO CONSTANTS =====
    private static final int    KDF_TYPE_ARGON2    = 1;       // Odin native KDF
    private static final int    KDF_TYPE_PBKDF2    = 0;       // Bitwarden KDF
    private static final int    PBKDF2_ITERATIONS  = 600_000; // Bitwarden current minimum recommendation
    private static final int    SALT_BYTES         = 32;      // Argon2 salt size - 16 minimum, 32 recommended
    private static final int    IV_BYTES_GCM       = 12;      // NIST SP 800-38D: 12x8=96bit - do not change
    private static final int    IV_BYTES_CBC       = 16;      // AES block size - required for CBC mode
    private static final int    GCM_TAG_BITS       = 128;     // 128-bit auth tag - max size
    private static final int    KEY_BYTES          = 32;      // AES-256 = 32 bytes
    private static final String CIPHER_TYPE_GCM    = "3";     // "3" = AES-256-GCM (Odin)
    private static final String CIPHER_TYPE_CBC    = "2";     // "2" = AES-256-CBC+HMAC (Bitwarden native + Odin legacy)
    private static final int    CIPHER_VERSION_GCM = 3;

    // ===== BITWARDEN ITEM TYPE INTEGERS =====
    private static final int BW_LOGIN    = 1;
    private static final int BW_NOTE     = 2;
    private static final int BW_CARD     = 3;
    private static final int BW_IDENTITY = 4;

    private static final int ODIN_NOTES    = 10;
    private static final int ODIN_DOCS     = 11;
    private static final int ODIN_BINARY   = 12;
    private static final int ODIN_SSH      = 13;
    private static final int ODIN_PASSKEY  = 14;
    private static final int ODIN_VPN      = 15;

    // ===== DEPENDENCIES =====
    private final Yggdrasil  backend;
    private final Connection conn;
    private final String     dbType;

    public ImportExport(Yggdrasil backend, Connection conn, String dbType, boolean debug) {
        DEBUG        = debug;
        this.backend = backend;
        this.conn    = conn;
        this.dbType  = dbType;
    }

    // ===================================================================
    // ===== DROPDOWN OPERATION DESCRIPTOR
    // ===================================================================

    /** The five operations exposed in the dropdown. */
    private enum Operation {
        EXPORT_ODIN           ("⬆  Export  - Encrypted Odin Backup"),
        EXPORT_BITWARDEN      ("⬆  Export  - Encrypted Bitwarden JSON"),
        IMPORT_ODIN           ("⬇  Import  - Encrypted Odin Backup"),
        IMPORT_BITWARDEN_ENC  ("⬇  Import  - Encrypted Bitwarden JSON"),
        IMPORT_BITWARDEN_RAM  ("⬇  Import  - Bitwarden Unencrypted JSON (secure RAM disk)");

        final String label;
        Operation(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    // ===================================================================
    // ===== SWING UI DIALOG
    // ===================================================================

    /**
     * Shows the import/export dialog.
     * Called from the Odin toolbar - parent frame needed for modal anchoring.
     */
    public void showImportExportDialog(JFrame parent, List<Yggdrasil.Credential> credentials, Runnable onImportComplete) {

        // ===== LOAD VAULT SECURITY PROFILE =====
        String vaultLevel;
        Argon2Profile.Profile profile;
        try {
            vaultLevel = Mimir.Pull_DB_Text_Meta_item(conn, "vault_level");
            profile = switch (vaultLevel) {
                case "MINIMUM"  -> Argon2Profile.MINIMUM;
                case "BALANCED" -> Argon2Profile.BALANCED;
                case "HIGH"     -> Argon2Profile.HIGH;
                case "PARANOID" -> Argon2Profile.PARANOID;
                default -> throw new IllegalArgumentException("Unknown security profile: " + vaultLevel);
            };
        } catch (Exception e) {
            JOptionPane.showMessageDialog(parent,
                "Could not load vault security profile:\n" + e.getMessage(),
                "Import / Export Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // ===== OUTER DIALOG =====
        JDialog dialog = new JDialog(parent, "Import / Export", true);
        dialog.setSize(520, 420);
        dialog.setLocationRelativeTo(parent);
        dialog.setResizable(false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel();
        root.setBackground(ThemeManager.BG);
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        // ===== TITLE =====
        JLabel title = new JLabel("Import / Export Vault");
        title.setFont(new java.awt.Font("Segoe UI", java.awt.Font.BOLD, 15));
        title.setForeground(ThemeManager.ACCENT);
        title.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        root.add(title);
        root.add(Box.createVerticalStrut(16));

        // ===== OPERATION DROPDOWN =====
        JLabel opLabel = new JLabel("Operation:");
        opLabel.setFont(new java.awt.Font("Segoe UI", java.awt.Font.PLAIN, 11));
        opLabel.setForeground(ThemeManager.TEXT_MUTED);
        opLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        JComboBox<Operation> opCombo = new JComboBox<>(Operation.values());
        opCombo.setBackground(ThemeManager.SURFACE2);
        opCombo.setForeground(ThemeManager.TEXT);
        opCombo.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 32));
        opCombo.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        root.add(opLabel);
        root.add(Box.createVerticalStrut(4));
        root.add(opCombo);
        root.add(Box.createVerticalStrut(14));

        // ===== FILE PATH ROW =====
        JLabel fileLabel = new JLabel("File:");
        fileLabel.setFont(new java.awt.Font("Segoe UI", java.awt.Font.PLAIN, 11));
        fileLabel.setForeground(ThemeManager.TEXT_MUTED);

        JTextField filePath = new JTextField("No file selected");
        filePath.setEditable(false);
        filePath.setBackground(ThemeManager.SURFACE2);
        filePath.setForeground(ThemeManager.TEXT_MUTED);
        filePath.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ThemeManager.BORDER),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        filePath.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 30));
        filePath.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        JButton browseBtn = new JButton("Browse");
        ThemeManager.styleSurfaceButton(browseBtn);

        JPanel fileRow = new JPanel(new java.awt.BorderLayout(6, 0));
        fileRow.setBackground(ThemeManager.BG);
        fileRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        fileRow.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 32));
        fileRow.add(filePath,  java.awt.BorderLayout.CENTER);
        fileRow.add(browseBtn, java.awt.BorderLayout.EAST);

        root.add(fileLabel);
        root.add(Box.createVerticalStrut(4));
        root.add(fileRow);
        root.add(Box.createVerticalStrut(14));

        // ===== PASSWORD ROW =====
        JLabel passLabel = new JLabel("Password:");
        passLabel.setFont(new java.awt.Font("Segoe UI", java.awt.Font.PLAIN, 11));
        passLabel.setForeground(ThemeManager.TEXT_MUTED);
        passLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        JPasswordField passField = new JPasswordField();
        passField.setBackground(ThemeManager.SURFACE2);
        passField.setForeground(ThemeManager.TEXT);
        passField.setCaretColor(ThemeManager.ACCENT);
        passField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ThemeManager.BORDER),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        passField.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 32));
        passField.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        // ===== PASSWORD STRENGTH BAR - shown for export operations only =====
        Thor.StrengthBarPanel strengthBar = new Thor.StrengthBarPanel();
        passField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void upd() { strengthBar.updateFromField(passField); }
            public void insertUpdate (javax.swing.event.DocumentEvent e) { upd(); }
            public void removeUpdate (javax.swing.event.DocumentEvent e) { upd(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { upd(); }
        });
        strengthBar.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        JLabel confirmLabel = new JLabel("Confirm Password:");
        confirmLabel.setFont(new java.awt.Font("Segoe UI", java.awt.Font.PLAIN, 11));
        confirmLabel.setForeground(ThemeManager.TEXT_MUTED);
        confirmLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        JPasswordField confirmField = new JPasswordField();
        confirmField.setBackground(ThemeManager.SURFACE2);
        confirmField.setForeground(ThemeManager.TEXT);
        confirmField.setCaretColor(ThemeManager.ACCENT);
        confirmField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ThemeManager.BORDER),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        confirmField.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 32));
        confirmField.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

        root.add(passLabel);
        root.add(Box.createVerticalStrut(4));
        root.add(passField);
        root.add(Box.createVerticalStrut(4));
        root.add(strengthBar);
        root.add(Box.createVerticalStrut(8));
        root.add(confirmLabel);
        root.add(Box.createVerticalStrut(4));
        root.add(confirmField);
        root.add(Box.createVerticalStrut(8));

        // ===== INFO LABEL =====
        JLabel infoLabel = new JLabel(" ");
        infoLabel.setFont(new java.awt.Font("Segoe UI", java.awt.Font.ITALIC, 11));
        infoLabel.setForeground(ThemeManager.TEXT_MUTED);
        infoLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        root.add(infoLabel);
        root.add(Box.createVerticalGlue());

        // ===== ACTION BUTTONS =====
        JButton actionBtn = new JButton("Go");
        JButton cancelBtn = new JButton("Cancel");
        ThemeManager.styleAccentButton(actionBtn);
        ThemeManager.styleSurfaceButton(cancelBtn);

        JPanel btnRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        btnRow.setBackground(ThemeManager.BG);
        btnRow.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        btnRow.add(cancelBtn);
        btnRow.add(actionBtn);
        root.add(btnRow);

        // ===== MUTABLE STATE - lambda safe =====
        final String[] selectedFile = { null };

        // ===== DROPDOWN CHANGE - update UI to match selected operation =====
        Runnable updateUiForOperation = () -> {
            Operation op        = (Operation) opCombo.getSelectedItem();
            isRamImport = op == Operation.IMPORT_BITWARDEN_RAM;
            isExport    = op == Operation.EXPORT_ODIN || op == Operation.EXPORT_BITWARDEN;

            // RAM disk import needs no file or password - the importer handles both
            fileLabel.setVisible(!isRamImport);
            fileRow.setVisible(!isRamImport);
            passLabel.setVisible(!isRamImport);
            passField.setVisible(!isRamImport);

            // Strength bar and confirm only shown for export operations
            strengthBar.setVisible(isExport);
            confirmLabel.setVisible(isExport);
            confirmField.setVisible(isExport);

            actionBtn.setText(isExport ? "Export" : "Import");

            infoLabel.setForeground(ThemeManager.TEXT_MUTED);
            infoLabel.setText(switch (op) {
                case EXPORT_ODIN          -> "Argon2id + AES-256-GCM encrypted. Profile: " + vaultLevel; 
                case EXPORT_BITWARDEN     -> "PBKDF2 + AES-256-CBC. Use this password when importing into Bitwarden.";
                case IMPORT_ODIN          -> "Select your Odin backup .json file then enter its password.";
                case IMPORT_BITWARDEN_ENC -> "Select your Bitwarden encrypted .json file then enter its password.";
                case IMPORT_BITWARDEN_RAM -> "File is saved to RAM only and wiped immediately after import.";
            });

            // Define the timestamp format matching: 20260514071740
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
            String timestamp = LocalDateTime.now().format(formatter);

            switch (op) {
                case EXPORT_ODIN          -> filename = "odin_encrypted_export_" + timestamp + ".json"; 
                case EXPORT_BITWARDEN     -> filename = "bitwarden_encrypted_export_" + timestamp + ".json";
                }

            dialog.revalidate();
            dialog.repaint();
        };

        opCombo.addActionListener(e -> updateUiForOperation.run());
        // Run once immediately to set initial state
        updateUiForOperation.run();

        // ===== BROWSE =====

        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();

            for (Component comp : fc.getComponents()) {
                ThemeManager.themeFileChooserComponents(comp);
            }
            
            if (filename != null && !filename.isEmpty()) {fc.setSelectedFile(new java.io.File(filename));}
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON (*.json)", "json"));
            fc.setCurrentDirectory(new File(System.getProperty("user.home") + "/Documents"));
            int result = fc.showDialog(dialog, "Select");
            if (result == JFileChooser.APPROVE_OPTION) {
                selectedFile[0] = fc.getSelectedFile().getAbsolutePath();
                filePath.setText(selectedFile[0]);
                filePath.setForeground(ThemeManager.TEXT);
                infoLabel.setForeground(ThemeManager.TEXT_MUTED);
                infoLabel.setText(fc.getSelectedFile().exists()
                    ? "File selected - ready to import."
                    : "New path - ready to export.");
            }
        });

        // ===== CANCEL =====
        cancelBtn.addActionListener(e -> dialog.dispose());

        // ===== Action Btn ======
        Runnable actionBtnaction = () -> {
            Operation op = (Operation) opCombo.getSelectedItem();
            infoLabel.setForeground(ThemeManager.TEXT_MUTED);

            switch (op) {

                // ===== EXPORT ODIN BACKUP =====
                case EXPORT_ODIN -> {
                    char[] pw  = passField.getPassword();
                    char[] pw2 = confirmField.getPassword();
                    if (pw.length == 0) {
                        infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                        infoLabel.setText("Password cannot be empty.");
                        Arrays.fill(pw, '\0'); Arrays.fill(pw2, '\0'); return;
                    }
                    if (!Arrays.equals(pw, pw2)) {
                        infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                        infoLabel.setText("Passwords do not match.");
                        Arrays.fill(pw, '\0'); Arrays.fill(pw2, '\0'); return;
                    }
                    if (selectedFile[0] == null) {
                        infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                        infoLabel.setText("Please select a file path first.");
                        Arrays.fill(pw, '\0'); Arrays.fill(pw2, '\0'); return;
                    }
                    String outPath = selectedFile[0].endsWith(".json") ? selectedFile[0] : selectedFile[0] + ".json";
                    actionBtn.setEnabled(false);
                    infoLabel.setText("Exporting... (Argon2id key derivation in progress)");
                    new Thread(() -> {
                        try {
                            String json = exportToJson(credentials, pw, profile);
                            Files.writeString(Path.of(outPath), json, StandardCharsets.UTF_8);
                            Arrays.fill(pw, '\0'); Arrays.fill(pw2, '\0');
                            SwingUtilities.invokeLater(() -> {
                                dialog.dispose();
                                ToastManager.show(parent, "Exported " + credentials.size() + " entries to " + outPath,
                                    ToastManager.ToastType.SUCCESS, 8_000);
                            });
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            Arrays.fill(pw, '\0'); Arrays.fill(pw2, '\0');
                            SwingUtilities.invokeLater(() -> {
                                infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                                infoLabel.setText("Export failed: " + ex.getMessage());
                                actionBtn.setEnabled(true);
                            });
                        }
                    }, "odin-export").start();
                }

                // ===== EXPORT BITWARDEN ENCRYPTED JSON =====
                case EXPORT_BITWARDEN -> {
                    char[] pw  = passField.getPassword();
                    char[] pw2 = confirmField.getPassword();
                    if (pw.length == 0) {
                        infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                        infoLabel.setText("Password cannot be empty.");
                        Arrays.fill(pw, '\0'); Arrays.fill(pw2, '\0'); return;
                    }
                    if (!Arrays.equals(pw, pw2)) {
                        infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                        infoLabel.setText("Passwords do not match.");
                        Arrays.fill(pw, '\0'); Arrays.fill(pw2, '\0'); return;
                    }
                    if (selectedFile[0] == null) {
                        infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                        infoLabel.setText("Please select a file path first.");
                        Arrays.fill(pw, '\0'); Arrays.fill(pw2, '\0'); return;
                    }
                    String outPath = selectedFile[0].endsWith(".json") ? selectedFile[0] : selectedFile[0] + ".json";
                    actionBtn.setEnabled(false);
                    infoLabel.setText("Exporting... (PBKDF2 key derivation - " + PBKDF2_ITERATIONS + " iterations)");
                    new Thread(() -> {
                        try {
                            String json = exportToBitwardenEncryptedJson(credentials, pw);
                            Files.writeString(Path.of(outPath), json, StandardCharsets.UTF_8);
                            Arrays.fill(pw, '\0'); Arrays.fill(pw2, '\0');
                            SwingUtilities.invokeLater(() -> {
                                dialog.dispose();
                                ToastManager.show(parent,
                                    "Exported " + credentials.size() + " entries to " + outPath + " (Bitwarden encrypted format)",
                                    ToastManager.ToastType.SUCCESS, 8_000);
                            });
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            Arrays.fill(pw, '\0'); Arrays.fill(pw2, '\0');
                            SwingUtilities.invokeLater(() -> {
                                infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                                infoLabel.setText("Export failed: " + ex.getMessage());
                                actionBtn.setEnabled(true);
                            });
                        }
                    }, "bw-encrypted-export").start();
                }

                // ===== IMPORT ODIN BACKUP =====
                case IMPORT_ODIN -> {
                    char[] pw = passField.getPassword();
                    if (pw.length == 0) {
                        infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                        infoLabel.setText("Password cannot be empty.");
                        Arrays.fill(pw, '\0'); return;
                    }
                    if (selectedFile[0] == null || !new File(selectedFile[0]).exists()) {
                        infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                        infoLabel.setText("Please select a valid .json file first.");
                        Arrays.fill(pw, '\0'); return;
                    }
                    actionBtn.setEnabled(false);
                    infoLabel.setText("Importing... (Argon2id key derivation in progress)");
                    new Thread(() -> {
                        try {
                            String json  = Files.readString(Path.of(selectedFile[0]), StandardCharsets.UTF_8);
                            int    count = importFromJson(json, pw);
                            Arrays.fill(pw, '\0');
                            SwingUtilities.invokeLater(() -> {
                                dialog.dispose(); onImportComplete.run();
                                ToastManager.show(parent, "Imported " + count + " entries.",
                                    ToastManager.ToastType.SUCCESS, 8_000);
                            });
                        } catch (SecurityException sex) {
                            Arrays.fill(pw, '\0');
                            SwingUtilities.invokeLater(() -> {
                                infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                                infoLabel.setText("Wrong password or corrupted file.");
                                actionBtn.setEnabled(true);
                            });
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            Arrays.fill(pw, '\0');
                            SwingUtilities.invokeLater(() -> {
                                infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                                infoLabel.setText("Import failed: " + ex.getMessage());
                                actionBtn.setEnabled(true);
                            });
                        }
                    }, "odin-import").start();
                }

                // ===== IMPORT BITWARDEN ENCRYPTED JSON =====
                // Uses the same PBKDF2 pipeline as the export but in reverse.
                // Salt encoding rule applies equally here - see class-level javadoc.
                case IMPORT_BITWARDEN_ENC -> {
                    char[] pw = passField.getPassword();
                    if (pw.length == 0) {
                        infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                        infoLabel.setText("Password cannot be empty.");
                        Arrays.fill(pw, '\0'); return;
                    }
                    if (selectedFile[0] == null || !new File(selectedFile[0]).exists()) {
                        infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                        infoLabel.setText("Please select a valid .json file first.");
                        Arrays.fill(pw, '\0'); return;
                    }
                    actionBtn.setEnabled(false);
                    infoLabel.setText("Importing... (PBKDF2 key derivation in progress)");
                    new Thread(() -> {
                        try {
                            String json  = Files.readString(Path.of(selectedFile[0]), StandardCharsets.UTF_8);
                            int    count = importFromBitwardenEncryptedJson(json, pw);
                            Arrays.fill(pw, '\0');
                            SwingUtilities.invokeLater(() -> {
                                dialog.dispose(); onImportComplete.run();
                                ToastManager.show(parent, "Imported " + count + " entries from Bitwarden.",
                                    ToastManager.ToastType.SUCCESS, 8_000);
                            });
                        } catch (SecurityException sex) {
                            Arrays.fill(pw, '\0');
                            SwingUtilities.invokeLater(() -> {
                                infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                                infoLabel.setText("Wrong password or corrupted file.");
                                actionBtn.setEnabled(true);
                            });
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            Arrays.fill(pw, '\0');
                            SwingUtilities.invokeLater(() -> {
                                infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                                infoLabel.setText("Import failed: " + ex.getMessage());
                                actionBtn.setEnabled(true);
                            });
                        }
                    }, "bw-encrypted-import").start();
                }

                // ===== IMPORT BITWARDEN UNENCRYPTED (RAM disk) =====
                case IMPORT_BITWARDEN_RAM -> {
                    actionBtn.setEnabled(false);
                    infoLabel.setText("Setting up secure RAM disk...");
                    new Thread(() -> {
                        try {
                            // RAM disk is acquired, user guided to save export there,
                            // file read into memory, RAM disk wiped and destroyed
                            String json = RamDiskImporter.run(dialog);
                            if (json == null) {
                                SwingUtilities.invokeLater(() -> {
                                    infoLabel.setText("Import cancelled.");
                                    actionBtn.setEnabled(true);
                                });
                                return;
                            }
                            int count = importFromBitwardenJson(json);
                            SwingUtilities.invokeLater(() -> {
                                dialog.dispose(); onImportComplete.run();
                                ToastManager.show(parent, "Imported " + count + " entries from Bitwarden.",
                                    ToastManager.ToastType.SUCCESS, 8_000);
                            });
                        } catch (RamDisk.RamDiskException ex) {
                            SwingUtilities.invokeLater(() -> {
                                infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                                infoLabel.setText("RAM disk error: " + ex.getMessage());
                                actionBtn.setEnabled(true);
                            });
                        } catch (SecurityException ex) {
                            SwingUtilities.invokeLater(() -> {
                                infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                                infoLabel.setText("Rejected: " + ex.getMessage());
                                actionBtn.setEnabled(true);
                            });
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            SwingUtilities.invokeLater(() -> {
                                infoLabel.setForeground(ThemeManager.DANGER != null ? ThemeManager.DANGER : java.awt.Color.RED);
                                infoLabel.setText("Import failed: " + ex.getMessage());
                                actionBtn.setEnabled(true);
                            });
                        }
                    }, "bw-ramdisk-import").start();
                }
            }
        };
        
        // ===== ACTION BUTTON - delegates to the selected operation =====
        confirmField.addActionListener(e -> actionBtnaction.run());
        actionBtn.addActionListener(e -> actionBtnaction.run());
        
        dialog.setContentPane(root);
        dialog.setVisible(true);
    }

    // ===================================================================================================
    // ===== CORE EXPORT - Odin format: Argon2id + AES-256-GCM    =========   ODIN EXPORTER
    // ===================================================================================================

    /**
     * Exports all credentials to an Odin encrypted backup JSON string.
     * Uses Argon2id with parameters from the vault's security profile.
     * Fields are decrypted from vault, stored plaintext in inner JSON,
     * then the whole inner blob is encrypted as one GCM CipherString.
     */
    public String exportToJson(List<Yggdrasil.Credential> credentials,
                               char[] exportPassword,
                               Argon2Profile.Profile profile) throws Exception {

        byte[] salt       = generateRandom(SALT_BYTES);
        byte[] stretchKey = deriveArgon2StretchedKey(exportPassword, salt,
                                profile.iterations(), profile.memoryKb(), profile.parallelism());
        byte[] encKey     = Arrays.copyOfRange(stretchKey, 0,        KEY_BYTES);
        // macKey retained for HKDF symmetry - not consumed by GCM
        byte[] macKey     = Arrays.copyOfRange(stretchKey, KEY_BYTES, KEY_BYTES * 2);

        JSONArray items = new JSONArray();
        for (Yggdrasil.Credential c : credentials) {
            JSONObject item = buildBitwardenItemPlaintext(c, true);
            if (item != null) items.put(item);
        }

        JSONObject inner = new JSONObject();
        inner.put("encrypted", false);
        inner.put("items",     items);

        // Encrypt entire inner JSON as one GCM CipherString
        String dataCipher       = encryptGcm(inner.toString().getBytes(StandardCharsets.UTF_8), encKey);
        // encKeyValidation: empty bytes are fine for Odin since we control both sides
        String encKeyValidation = encryptGcm(new byte[0], encKey);

        JSONObject outer = new JSONObject();
        outer.put("program",                       "odin"); 
        outer.put("encrypted",                     true);
        outer.put("passwordProtected",             true);
        outer.put("salt",                          Base64.getEncoder().encodeToString(salt));
        outer.put("kdfType",                       KDF_TYPE_ARGON2);
        outer.put("kdfIterations",                 profile.iterations());
        outer.put("kdfMemory",                     profile.memoryKb());
        outer.put("kdfParallelism",                profile.parallelism());
        outer.put("cipherVersion",                 CIPHER_VERSION_GCM);
        outer.put("encKeyValidation_DO_NOT_EDIT",  encKeyValidation);
        outer.put("data",                          dataCipher);

        Arrays.fill(stretchKey, (byte) 0);
        Arrays.fill(encKey,     (byte) 0);
        Arrays.fill(macKey,     (byte) 0);

        return outer.toString(2);
    }

    // ===================================================================
    // ===== BITWARDEN ENCRYPTED EXPORT
    // ===================================================================
    //
    // READ THE CLASS-LEVEL JAVADOC BEFORE MODIFYING THIS METHOD.
    //
    // Two non-obvious facts verified from the compiled Bitwarden CLI (bw.js):
    //
    //   1. SALT: the base64 string from the JSON is passed as UTF-8 bytes to
    //      PBKDF2. It is NOT decoded to raw bytes first. In JS:
    //        if (typeof salt === "string") { salt = new TextEncoder().encode(salt); }
    //      In Java: saltB64.getBytes(StandardCharsets.UTF_8)  <-- not Base64.decode()
    //
    //   2. encKeyValidation: must encrypt Utils.newGuid() (a UUID string), not
    //      empty bytes. BW importer decrypts this and checks for a non-empty
    //      string to confirm the key is valid before decrypting the data blob.
    //
    // ===================================================================

    /**
     * Exports all credentials to a Bitwarden-compatible encrypted JSON string.
     * The resulting file imports cleanly into Bitwarden via:
     *   Settings > Import items > Bitwarden (encrypted JSON) > enter password.
     */
    public String exportToBitwardenEncryptedJson(List<Yggdrasil.Credential> credentials,
                                                  char[] exportPassword) throws Exception {

        // Generate random salt - stored as base64 string, passed as UTF-8 bytes to PBKDF2
        byte[] saltRaw    = generateRandom(SALT_BYTES);
        String saltB64    = Base64.getEncoder().encodeToString(saltRaw);
        byte[] stretchKey = derivePbkdf2StretchedKey(exportPassword, saltB64, PBKDF2_ITERATIONS);
        byte[] encKey     = Arrays.copyOfRange(stretchKey, 0,        KEY_BYTES);
        byte[] macKey     = Arrays.copyOfRange(stretchKey, KEY_BYTES, KEY_BYTES * 2);

        // Build plaintext items - fields decrypted from vault, inner JSON encrypted as one blob
        JSONArray items = new JSONArray();
        for (Yggdrasil.Credential c : credentials) {
            JSONObject item = buildBitwardenItemPlaintext(c, false);
            if (item != null) items.put(item);
        }

        JSONObject inner = new JSONObject();
        inner.put("encrypted", false);
        inner.put("items",     items);

        String dataCipher = encryptCbc(
            inner.toString().getBytes(StandardCharsets.UTF_8), encKey, macKey);

        // encKeyValidation: BW source calls encryptString(Utils.newGuid(), key)
        // Encrypting empty bytes causes "invalid file password" - must be a UUID string
        String encKeyValidation = encryptCbc(
            UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8), encKey, macKey);

        // kdfType=0 (PBKDF2) - no kdfMemory or kdfParallelism since PBKDF2 does not use them
        JSONObject outer = new JSONObject();
        outer.put("encrypted",                    true);
        outer.put("passwordProtected",             true);
        outer.put("salt",                          saltB64);
        outer.put("kdfType",                       KDF_TYPE_PBKDF2);
        outer.put("kdfIterations",                 PBKDF2_ITERATIONS);
        outer.put("encKeyValidation_DO_NOT_EDIT",  encKeyValidation);
        outer.put("data",                          dataCipher);

        Arrays.fill(stretchKey, (byte) 0);
        Arrays.fill(encKey,     (byte) 0);
        Arrays.fill(macKey,     (byte) 0);

        return outer.toString(2);
    }

    // ===================================================================
    // ===== CORE IMPORT - Odin encrypted backup
    // ===================================================================

    /**
     * Imports an Odin encrypted backup JSON.
     * Reads KDF parameters from the file so any Argon2id profile can be imported.
     * Supports both cipherVersion 2 (AES-CBC legacy) and 3 (AES-GCM).
     *
     * @return count of entries successfully imported
     */
    public int importFromJson(String json, char[] exportPassword) throws Exception {

        JSONObject outer = new JSONObject(json);

        if (!outer.optBoolean("passwordProtected", false)) {
            throw new IllegalArgumentException(
                "Not an Odin password-protected backup. " +
                "For Bitwarden encrypted exports use the Bitwarden import option.");
        }

        // Read all KDF parameters from the file - supports any Argon2id profile
        byte[] salt        = Base64.getDecoder().decode(outer.getString("salt"));
        int kdfType        = outer.optInt("kdfType",        KDF_TYPE_ARGON2);
        int kdfIterations  = outer.optInt("kdfIterations",  3);
        int kdfMemory      = outer.optInt("kdfMemory",      65536);
        int kdfParallelism = outer.optInt("kdfParallelism", 4);
        // cipherVersion defaults to 2 (CBC) for backwards compat with old backups
        int cipherVersion  = outer.optInt("cipherVersion",  2);

        if (kdfType != KDF_TYPE_ARGON2) {
            throw new IllegalArgumentException(
                "Unsupported kdfType=" + kdfType + ". This backup was not created by Odin.");
        }

        if (DEBUG) {
            System.out.println("[ImportExport] cipherVersion: " + cipherVersion);
            System.out.println("[ImportExport] kdfIterations: " + kdfIterations);
            System.out.println("[ImportExport] kdfMemory:     " + kdfMemory);
            System.out.println("[ImportExport] kdfParallelism:" + kdfParallelism);
            System.out.println("[ImportExport] salt (b64):    " + outer.getString("salt"));
        }

        byte[] stretchKey = deriveArgon2StretchedKey(exportPassword, salt,
                                kdfIterations, kdfMemory, kdfParallelism);
        byte[] encKey     = Arrays.copyOfRange(stretchKey, 0,        KEY_BYTES);
        byte[] macKey     = Arrays.copyOfRange(stretchKey, KEY_BYTES, KEY_BYTES * 2);

        // Fast-fail on wrong password before decrypting the full data blob
        try {
            decryptCipherString(outer.getString("encKeyValidation_DO_NOT_EDIT"),
                                encKey, macKey, cipherVersion);
        } catch (Exception e) {
            Arrays.fill(stretchKey, (byte) 0);
            Arrays.fill(encKey,     (byte) 0);
            Arrays.fill(macKey,     (byte) 0);
            throw new SecurityException("Wrong password or corrupted backup file.");
        }

        byte[] plainBytes = decryptCipherString(outer.getString("data"), encKey, macKey, cipherVersion);
        String plainJson  = new String(plainBytes, StandardCharsets.UTF_8);
        Arrays.fill(plainBytes, (byte) 0);

        Arrays.fill(stretchKey, (byte) 0);
        Arrays.fill(encKey,     (byte) 0);
        Arrays.fill(macKey,     (byte) 0);

        JSONObject inner = new JSONObject(plainJson);
        JSONArray  items = inner.getJSONArray("items");
        int        count = 0;

        for (int i = 0; i < items.length(); i++) {
            try {
                Object[] parsed = parseBitwardenItem(items.getJSONObject(i), true);
                if (parsed == null) continue;
                char[]   tag        = (char[])  parsed[0];
                String   odinType   = (String)  parsed[1];
                char[][] dataFields = (char[][]) parsed[2];
                backend.addEntry(conn, tag, odinType, dataFields, dbType);
                for (char[] d : dataFields) if (d != null) Yggdrasil.wipeCharArray(d);
                Yggdrasil.wipeCharArray(tag);
                count++;
            } catch (Exception ex) {
                System.err.println("[ImportExport] Skipped item " + i + ": " + ex.getMessage());
            }
        }
        return count;
    }

    // ===================================================================
    // ===== IMPORT FROM BITWARDEN ENCRYPTED JSON
    // ===================================================================
    //
    // READ THE CLASS-LEVEL JAVADOC BEFORE MODIFYING THIS METHOD.
    //
    // The salt encoding rule is identical to the export path:
    //   salt stored in file  -> base64 string e.g. "GySYSCvSdeP2WDWOFAVjvw=="
    //   salt into PBKDF2     -> that string's UTF-8 bytes, NOT the decoded bytes
    //
    // This mirrors exactly what Bitwarden's importer does:
    //   deriveVaultExportKey(password, jdoc.salt, kdfConfig)
    //   -> deriveKeyFromPassword(password, salt, kdfConfig)
    //   -> if (typeof salt === "string") { salt = new TextEncoder().encode(salt); }
    //   -> pbkdf2(password, UTF8(saltString), iterations, 256)
    //
    // ===================================================================

    /**
     * Imports a Bitwarden encrypted JSON export (password-protected format).
     * Works with files exported from Bitwarden directly or from Odin's
     * "Export Bitwarden encrypted JSON" option.
     *
     * Supports kdfType 0 (PBKDF2-SHA256) only - the only type Bitwarden uses
     * for password-protected exports.
     *
     * @return count of entries successfully imported
     */
    public int importFromBitwardenEncryptedJson(String json, char[] exportPassword) throws Exception {

        JSONObject outer = new JSONObject(json);

        // Guard: must be password-protected encrypted
        if (!outer.optBoolean("passwordProtected", false) ||
            !outer.optBoolean("encrypted", false)) {
            throw new IllegalArgumentException(
                "Not a Bitwarden password-protected export. " +
                "For unencrypted exports use the RAM disk import option.");
        }

        // Read KDF parameters from the file
        int kdfType       = outer.optInt("kdfType",       KDF_TYPE_PBKDF2);
        int kdfIterations = outer.optInt("kdfIterations", PBKDF2_ITERATIONS);

        if (kdfType != KDF_TYPE_PBKDF2) {
            throw new IllegalArgumentException(
                "Unsupported kdfType=" + kdfType + ". " +
                "Only PBKDF2 (kdfType=0) password-protected exports are supported.");
        }

        // CRITICAL: salt is the base64 STRING from the JSON - pass as UTF-8 bytes to PBKDF2
        // Do NOT Base64-decode the salt. See class-level javadoc for the full explanation.
        String saltB64    = outer.getString("salt");

        if (DEBUG) {
            System.out.println("[ImportExport] BW encrypted import");
            System.out.println("[ImportExport] kdfIterations: " + kdfIterations);
            System.out.println("[ImportExport] salt (b64 string used as-is): " + saltB64);
        }

        byte[] stretchKey = derivePbkdf2StretchedKey(exportPassword, saltB64, kdfIterations);
        byte[] encKey     = Arrays.copyOfRange(stretchKey, 0,        KEY_BYTES);
        byte[] macKey     = Arrays.copyOfRange(stretchKey, KEY_BYTES, KEY_BYTES * 2);

        // Validate key against encKeyValidation before touching the data blob
        // Bitwarden stored a UUID string here - if decryption throws, password is wrong
        try {
            decryptCbc(outer.getString("encKeyValidation_DO_NOT_EDIT"), encKey, macKey);
        } catch (Exception e) {
            Arrays.fill(stretchKey, (byte) 0);
            Arrays.fill(encKey,     (byte) 0);
            Arrays.fill(macKey,     (byte) 0);
            throw new SecurityException("Wrong password or corrupted Bitwarden export file.");
        }

        // Decrypt the full data blob - result is inner plaintext JSON
        byte[] plainBytes = decryptCbc(outer.getString("data"), encKey, macKey);
        String plainJson  = new String(plainBytes, StandardCharsets.UTF_8);
        Arrays.fill(plainBytes, (byte) 0);

        Arrays.fill(stretchKey, (byte) 0);
        Arrays.fill(encKey,     (byte) 0);
        Arrays.fill(macKey,     (byte) 0);

        // Parse the inner JSON - fields are plaintext strings at this point
        JSONObject inner = new JSONObject(plainJson);
        JSONArray  items = inner.optJSONArray("items");
        if (items == null || items.length() == 0) {
            throw new IllegalArgumentException("No items found in Bitwarden export.");
        }

        int count = 0;
        for (int i = 0; i < items.length(); i++) {
            try {
                Object[] parsed = parseBitwardenItem(items.getJSONObject(i), false);
                if (parsed == null) continue;
                char[]   tag        = (char[])  parsed[0];
                String   odinType   = (String)  parsed[1];
                char[][] dataFields = (char[][]) parsed[2];
                backend.addEntry(conn, tag, odinType, dataFields, dbType);
                for (char[] d : dataFields) if (d != null) Yggdrasil.wipeCharArray(d);
                Yggdrasil.wipeCharArray(tag);
                count++;
            } catch (Exception ex) {
                System.err.println("[ImportExport] Skipped item " + i + ": " + ex.getMessage());
            }
        }
        return count;
    }

    // ===================================================================
    // ===== IMPORT FROM BITWARDEN UNENCRYPTED JSON (RAM disk)
    // ===================================================================

    /**
     * Imports a Bitwarden unencrypted JSON export.
     * Called after RamDiskImporter.run() reads the file into memory.
     * The file never touched persistent storage - pure RAM operation.
     *
     * @return count of entries successfully imported
     */
    public int importFromBitwardenJson(String json) throws Exception {

        JSONObject outer = new JSONObject(json);

        // Guard against accidentally passing an encrypted export here
        if (outer.optBoolean("encrypted", false)) {
            throw new IllegalArgumentException(
                "This is an encrypted export. Use the Bitwarden encrypted import option instead.");
        }

        JSONArray items = outer.optJSONArray("items");
        if (items == null || items.length() == 0) {
            throw new IllegalArgumentException("No items found in Bitwarden export.");
        }

        int count = 0;
        for (int i = 0; i < items.length(); i++) {
            try {
                // Fields are already plaintext in an unencrypted Bitwarden export
                Object[] parsed = parseBitwardenItem(items.getJSONObject(i), false);
                if (parsed == null) continue;
                char[]   tag        = (char[])  parsed[0];
                String   odinType   = (String)  parsed[1];
                char[][] dataFields = (char[][]) parsed[2];
                backend.addEntry(conn, tag, odinType, dataFields, dbType);
                for (char[] d : dataFields) if (d != null) Yggdrasil.wipeCharArray(d);
                Yggdrasil.wipeCharArray(tag);
                count++;
            } catch (Exception ex) {
                System.err.println("[ImportExport] Skipped item " + i + ": " + ex.getMessage());
            }
        }
        return count;
    }

    // ===============================================================================================================================
    // ===== ODIN / BITWARDEN EXPORT ITEM BUILDER (Odin credential -> plaintext JSON item)
    // ===============================================================================================================================

    /**
     * Converts one Odin credential to a Bitwarden-format JSON item with PLAINTEXT fields.
     * Decrypts each field from the vault. Encryption happens at the outer envelope level.
     */
    private JSONObject buildBitwardenItemPlaintext(Yggdrasil.Credential c, boolean odin) throws Exception {
        Futhark.EntryType type = Futhark.forKey(c.type);
        if (c.type == null) return null;

        JSONObject item = new JSONObject();
        item.put("id",             UUID.randomUUID().toString());
        item.put("organizationId", JSONObject.NULL);
        item.put("folderId",       JSONObject.NULL);
        item.put("favorite",       false);
        item.put("reprompt",       0);
        // Tag/name stored as plaintext - outer envelope protects everything
        item.put("name",           new String(c.tag));

        switch (c.type) {

            // ===== ACCOUNT -> login (type 1) =====
            // data0=URL, data1=username, data2=password, data3=notes
            case "account" -> {
                item.put("type", BW_LOGIN);
                JSONObject login = new JSONObject();
                login.put("uris",     buildUrisPlaintext(decryptField(c, 0)));
                login.put("username", decryptField(c, 1));
                login.put("password", decryptField(c, 2));
                login.put("totp",     JSONObject.NULL);
                item.put("login", login);
                item.put("notes", decryptField(c, 3));
            }

            // ===== CARD -> card (type 3) =====
            // data0=name, data1=number, data2=expiry (MM/YYYY), data3=cvv, data6=notes
            case "card" -> {
                item.put("type", BW_CARD);
                JSONObject card = new JSONObject();
                card.put("cardholderName", decryptField(c, 0));
                card.put("number",         decryptField(c, 1));
                // Expiry stored whole (MM/YYYY) - split for Bitwarden compatibility
                String   expiry   = decryptField(c, 2);
                String[] expParts = expiry.contains("/") ? expiry.split("/", 2) : new String[]{ expiry, "" };
                card.put("expMonth", expParts[0]);
                card.put("expYear",  expParts.length > 1 ? expParts[1] : "");
                card.put("code",     decryptField(c, 3));
                item.put("card",  card);
                item.put("notes", decryptField(c, 6));
            }

            // ===== ADDRESS -> identity (type 4) =====
            // data0=name, data1=street, data2=city, data3=state, data4=postal, data5=country, data6=notes
            case "address" -> {
                item.put("type", BW_IDENTITY);
                JSONObject identity = new JSONObject();
                identity.put("fullName",   decryptField(c, 0));
                identity.put("address1",   decryptField(c, 1));
                identity.put("city",       decryptField(c, 2));
                identity.put("state",      decryptField(c, 3));
                identity.put("postalCode", decryptField(c, 4));
                identity.put("country",    decryptField(c, 5));
                item.put("identity", identity);
                item.put("notes",    decryptField(c, 6));
            }

            // ===== SSH, VPN, PASSKEY -> secureNote + custom fields =====
            case "ssh", "vpn", "passkey" -> {
                if (odin) {
                    if (c.type.equals("ssh")){
                        item.put("type",       ODIN_SSH);
                        JSONObject odinItem = new JSONObject();
                        odinItem.put("hostname",        decryptField(c, 0));
                        odinItem.put("username",        decryptField(c, 1));
                        odinItem.put("priv_key",        decryptField(c, 2));
                        odinItem.put("pub_key",         decryptField(c, 3));
                        odinItem.put("passphrase",      decryptField(c, 4));
                        odinItem.put("type",            decryptField(c, 5));
                        odinItem.put("notes",           decryptField(c, 6));
                        item.put("odinSSH", odinItem);
                    } else if (c.type.equals("vpn")){
                        item.put("type",       ODIN_VPN);
                        JSONObject odinItem = new JSONObject();
                        odinItem.put("hostname",        decryptField(c, 0));
                        odinItem.put("username",        decryptField(c, 1));
                        odinItem.put("password-psk",    decryptField(c, 2));
                        odinItem.put("config-key",      decryptField(c, 3));
                        odinItem.put("protocol",        decryptField(c, 4));
                        odinItem.put("port",            decryptField(c, 5));
                        odinItem.put("notes",           decryptField(c, 6));
                        item.put("odinVPN", odinItem);
                    } else {
                        item.put("type",       ODIN_PASSKEY);
                        JSONObject odinItem = new JSONObject();
                        odinItem.put("site",            decryptField(c, 0));
                        odinItem.put("username",        decryptField(c, 1));
                        odinItem.put("cred_id",         decryptField(c, 2));
                        odinItem.put("priv_key",        decryptField(c, 3));
                        odinItem.put("pub_key",         decryptField(c, 4));
                        odinItem.put("algorithm",       decryptField(c, 5));
                        odinItem.put("notes",           decryptField(c, 6));
                        item.put("odinPasskey", odinItem);
                    }

                        // Build custom fields array for any additional sensitive metadata
                        JSONArray fields = new JSONArray();
                        if (type != null) {
                            for (int i = 0; i < type.fields.size(); i++) {
                                String val = decryptField(c, i);
                                if (val.isEmpty()) continue;
                                Futhark.Field f = type.fields.get(i);
                                JSONObject field = new JSONObject();
                                field.put("name",  f.label); /// TAG
                                field.put("value", val);
                                // 1 = hidden/sensitive, 0 = plain text
                                field.put("type",  (f.sensitive || f.password) ? 1 : 0);
                                fields.put(field);
                            }
                        }
                        item.put("fields", fields);

                } else {
                    // BITWARDEN - doesn't compare to our fields 
                item.put("type",       BW_NOTE);
                item.put("secureNote", new JSONObject().put("type", 0));
                item.put("notes",      JSONObject.NULL);
                JSONArray fields = new JSONArray();
                if (type != null) {
                    for (int i = 0; i < type.fields.size(); i++) {
                        String val = decryptField(c, i);
                        if (val.isEmpty()) continue;
                        Futhark.Field f = type.fields.get(i);
                        JSONObject field = new JSONObject();
                        field.put("name",  f.label); /// TAG
                        field.put("value", val);
                        // 1 = hidden/sensitive field, 0 = text field
                        field.put("type",  (f.sensitive || f.password) ? 1 : 0);
                        fields.put(field);
                    }
                }
                item.put("fields", fields);
                }
            }

                case "binary" -> {
                    if (odin) {
                        // Build the odinBinary sub-object with decrypted file metadata
                        item.put("type",       ODIN_BINARY);
                        
                        JSONObject odinItem = new JSONObject();
                        odinItem.put("system",       decryptField(c, 0));
                        odinItem.put("username",     decryptField(c, 1));
                        odinItem.put("base64",       decryptField(c, 2));
                        odinItem.put("filename",     decryptField(c, 3));
                        odinItem.put("sha256-file",  decryptField(c, 4));
                        odinItem.put("sha256-data",  decryptField(c, 5));
                        odinItem.put("notes",        decryptField(c, 6));
                        item.put("odinBinary", odinItem);

                        // Build custom fields array for any additional sensitive metadata
                        JSONArray fields = new JSONArray();
                        if (type != null) {
                            for (int i = 0; i < type.fields.size(); i++) {
                                String val = decryptField(c, i);
                                if (val.isEmpty()) continue;
                                Futhark.Field f = type.fields.get(i);
                                JSONObject field = new JSONObject();
                                field.put("name",  f.label); /// TAG
                                field.put("value", val);
                                // 1 = hidden/sensitive, 0 = plain text
                                field.put("type",  (f.sensitive || f.password) ? 1 : 0);
                                fields.put(field);
                            }
                        }
                        item.put("fields", fields);
                        } else { return null; }
                    }

                    case "note" -> {
                    if (odin) {
                        // Build the odinBinary sub-object with decrypted file metadata
                        item.put("type",       ODIN_NOTES);
                        
                        JSONObject odinItem = new JSONObject();
                        odinItem.put("subject",       decryptField(c, 0));
                        odinItem.put("notes",       decryptField(c, 1));
                        
                        item.put("odinBinary", odinItem);

                        // Build custom fields array for any additional sensitive metadata
                        JSONArray fields = new JSONArray();
                        if (type != null) {
                            for (int i = 0; i < type.fields.size(); i++) {
                                String val = decryptField(c, i);
                                if (val.isEmpty()) continue;
                                Futhark.Field f = type.fields.get(i);
                                JSONObject field = new JSONObject();
                                field.put("name",  f.label); /// TAG
                                field.put("value", val);
                                // 1 = hidden/sensitive, 0 = plain text
                                field.put("type",  (f.sensitive || f.password) ? 1 : 0);
                                fields.put(field);
                            }
                        }
                        item.put("fields", fields);
                        } else {
                            // ===== NOTE -> secureNote (type 2) =====
                            // data0=subject, data1=body
                                item.put("type",       BW_NOTE);
                                item.put("name",       decryptField(c, 0));
                                item.put("notes",      decryptField(c, 1));
                                item.put("secureNote", new JSONObject().put("type", 0));
                        }
                    }
            
                    case "docs" -> {
                    if (odin) {
                        // Build the odinDocs sub-object with decrypted file metadata
                        item.put("type",       ODIN_DOCS);
                        
                        JSONObject odinDocs = new JSONObject();
                        odinDocs.put("base64",       decryptField(c, 0));
                        odinDocs.put("filename",     decryptField(c, 1));
                        odinDocs.put("sha256-file",  decryptField(c, 2));
                        odinDocs.put("sha256-data",  decryptField(c, 3));
                        odinDocs.put("notes",        decryptField(c, 4));
                        item.put("odinDocs", odinDocs);

                        // Build custom fields array for any additional sensitive metadata
                        JSONArray fields = new JSONArray();
                        if (type != null) {
                            for (int i = 0; i < type.fields.size(); i++) {
                                String val = decryptField(c, i);
                                if (val.isEmpty()) continue;
                                Futhark.Field f = type.fields.get(i);
                                JSONObject field = new JSONObject();
                                field.put("name",  f.label); /// TAG
                                field.put("value", val);
                                // 1 = hidden/sensitive, 0 = plain text
                                field.put("type",  (f.sensitive || f.password) ? 1 : 0);
                                fields.put(field);
                            }
                        }
                        item.put("fields", fields);
                        } else { return null; }
                    }

            default -> { return null; }
        }
        return item;
    }

    // =================================================================================================================================
    // ===== ODIN / BITWARDEN IMPORT ITEM PARSER (Bitwarden JSON item -> Odin)
    // =================================================================================================================================

    /**
     * Parses one Bitwarden JSON item into Odin components.
     * Used by all three import paths. Fields are always plaintext at this point:
     *   - Odin backup: decrypted at outer envelope level before here
     *   - Bitwarden encrypted: decrypted at outer envelope level before here
     *   - Bitwarden unencrypted: already plaintext
     *
     * @return Object[]{ char[] tag, String odinType, char[][] dataFields } or null to skip
     */
    private Object[] parseBitwardenItem(JSONObject item, boolean odin) {
        try {
            int    bwType = item.getInt("type");
            String name   = item.optString("name",  "");
            String notes  = item.optString("notes", "");

            // 9 data slots - matches Futhark.DATA_COLUMNS
            char[][] data     = new char[9][];
            String   odinType=null;

            switch (bwType) {

                // ===== LOGIN -> account =====
                case BW_LOGIN -> {
                    odinType = "account";
                    JSONObject login = item.optJSONObject("login");
                    if (login != null) {
                        JSONArray uris = login.optJSONArray("uris");
                        if (uris != null && uris.length() > 0) {
                            // First URI only - Odin stores one URL per account entry
                            data[0] = uris.getJSONObject(0).optString("uri", "").toCharArray();
                        }
                        data[1] = login.optString("username", "").toCharArray();
                        data[2] = login.optString("password", "").toCharArray();
                    }
                    data[3] = notes.toCharArray();
                }

                // ===== SECURE NOTE -> note or Odin custom type =====
                case BW_NOTE -> {
                    if (!odin){
                    JSONArray fields = item.optJSONArray("fields");
                    if (fields != null && fields.length() > 0) {
                        // ===== Type detected by scoring field label matches across all Odin types =====
                        odinType = detectOdinTypeFromFields(fields);
                        Futhark.EntryType et = Futhark.forKey(odinType);
                        if (et != null) {
                            for (int i = 0; i < fields.length(); i++) {
                                JSONObject f  = fields.getJSONObject(i);
                                String     fn = f.optString("name",  "");
                                String     fv = f.optString("value", "");
                                int        di = labelToDataIndex(et, fn);
                                if (di >= 0 && di < 8) data[di] = fv.toCharArray();
                            }
                        }
                    } else {
                        odinType = "note";
                        data[0]  = name.toCharArray();
                        data[1]  = notes.toCharArray();
                    }
                }
            }

                // ===== CARD -> card =====
                case BW_CARD -> {
                    odinType = "card";
                    JSONObject card = item.optJSONObject("card");
                    if (card != null) {
                        data[0] = card.optString("cardholderName", "").toCharArray();
                        data[1] = card.optString("number",         "").toCharArray();
                        // Recombine month/year into single expiry field: MM/YYYY
                        data[2] = (card.optString("expMonth", "") + "/" +
                                   card.optString("expYear",  "")).toCharArray();
                        data[3] = card.optString("code", "").toCharArray();
                    }
                    data[6] = notes.toCharArray();
                }

                // ===== IDENTITY -> address =====
                case BW_IDENTITY -> {
                    odinType = "address";
                    JSONObject id = item.optJSONObject("identity");
                    if (id != null) {
                        data[0] = id.optString("fullName",   "").toCharArray();
                        data[1] = id.optString("address1",   "").toCharArray();
                        data[2] = id.optString("city",       "").toCharArray();
                        data[3] = id.optString("state",      "").toCharArray();
                        data[4] = id.optString("postalCode", "").toCharArray();
                        data[5] = id.optString("country",    "").toCharArray();
                    }
                    data[6] = notes.toCharArray();
                }

                case ODIN_BINARY -> {
                    odinType = "binary";
                    JSONObject id = item.optJSONObject("odinBinary");
                    if (id != null) {

                        data[0] = id.optString("system",   "").toCharArray();
                        data[1] = id.optString("username",   "").toCharArray();
                        data[2] = id.optString("base64",   "").toCharArray();
                        data[3] = id.optString("filename",   "").toCharArray();
                        data[4] = id.optString("sha256-file",       "").toCharArray();
                        data[5] = id.optString("sha256-data",      "").toCharArray();
                        data[6] = id.optString("notes", "").toCharArray();
                    }
            }

            case ODIN_NOTES -> {
                    odinType="note";
                    JSONObject id = item.optJSONObject("odinNote");
                    if (id != null) {
                        data[0] = id.optString("subject",   "").toCharArray();
                        data[1] = id.optString("note",   "").toCharArray();
                    }
            }

                case ODIN_DOCS -> {
                    odinType = "docs";
                    JSONObject id = item.optJSONObject("odinDocs");
                    if (id != null) {
                        data[0] = id.optString("base64",   "").toCharArray();
                        data[1] = id.optString("filename",   "").toCharArray();
                        data[2] = id.optString("sha256-file",       "").toCharArray();
                        data[3] = id.optString("sha256-data",      "").toCharArray();
                        data[4] = id.optString("notes", "").toCharArray();
                    }
            }

                default -> { return null; }
            }

            return new Object[]{ name.toCharArray(), odinType, data };

        } catch (Exception e) {
            System.err.println("[ImportExport] Parse error: " + e.getMessage());
            return null;
        }
    }

    // ===================================================================
    // ===== CRYPTO - Argon2id key derivation (Odin native)
    // ===================================================================

    /**
     * Derives a 512-bit stretched key using Argon2id + HKDF-Expand.
     *
     * Pipeline:
     *   Argon2id(password_utf8, salt, iterations, memoryKb, parallelism) -> masterKey (32 bytes)
     *   HKDF-Expand(masterKey, "enc") -> encKey (32 bytes)
     *   HKDF-Expand(masterKey, "mac") -> macKey (32 bytes)
     *   return encKey || macKey (64 bytes)
     */
    private byte[] deriveArgon2StretchedKey(char[] password, byte[] salt,
                                             int iterations, int memoryKb, int parallelism)
            throws Exception {

        byte[] passwordBytes = new String(password).getBytes(StandardCharsets.UTF_8);
        byte[] masterKey;
        try {
            Argon2Advanced argon2 = Argon2Factory.createAdvanced(Argon2Types.ARGON2id);
            masterKey = argon2.rawHash(iterations, memoryKb, parallelism, passwordBytes, salt);
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
        }

        if (DEBUG) {
            StringBuilder mkHex = new StringBuilder();
            for (byte b : masterKey) mkHex.append(String.format("%02x", b));
            System.out.println("[ImportExport] Argon2id masterKey: " + mkHex);
        }

        return stretchMasterKey(masterKey);
    }

    // ===================================================================
    // ===== CRYPTO - PBKDF2-SHA256 key derivation (Bitwarden compatible)
    // ===================================================================
    //
    // READ THE CLASS-LEVEL JAVADOC BEFORE MODIFYING THIS METHOD.
    //
    // saltB64 is the base64 STRING from the JSON file (e.g. "GySYSCvSdeP2WDWOFAVjvw==").
    // It is converted to UTF-8 bytes of that string before being passed to PBKDF2.
    // This matches Bitwarden's JS: new TextEncoder().encode(saltString)
    // Do NOT Base64-decode the salt here.
    //
    // ===================================================================

    /**
     * Derives a 512-bit stretched key using PBKDF2-SHA256 + HKDF-Expand.
     * Used for both Bitwarden encrypted export and import.
     *
     * @param saltB64 the base64 string exactly as stored in the JSON "salt" field
     */
    private byte[] derivePbkdf2StretchedKey(char[] password, String saltB64, int iterations)
            throws Exception {

        byte[] passwordBytes = new String(password).getBytes(StandardCharsets.UTF_8);
        // Salt = UTF-8 bytes of the base64 STRING - matches Bitwarden's TextEncoder behavior
        // NOT Base64.getDecoder().decode(saltB64)
        byte[] saltBytes     = saltB64.getBytes(StandardCharsets.UTF_8);

        byte[] masterKey;
        try {
            // BouncyCastle PKCS5S2 = PBKDF2-HMAC-SHA256 with explicit byte[] control
            // Java JCE PBEKeySpec(char[]) uses UTF-16BE internally - do not use it here
            org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator gen =
                new org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator(
                    new org.bouncycastle.crypto.digests.SHA256Digest());
            gen.init(passwordBytes, saltBytes, iterations);
            masterKey = ((org.bouncycastle.crypto.params.KeyParameter)
                gen.generateDerivedParameters(KEY_BYTES * 8)).getKey();
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
            Arrays.fill(saltBytes,     (byte) 0);
        }

        if (DEBUG) {
            StringBuilder mkHex = new StringBuilder();
            for (byte b : masterKey) mkHex.append(String.format("%02x", b));
            System.out.println("[ImportExport] PBKDF2 masterKey: " + mkHex);
        }

        return stretchMasterKey(masterKey);
    }

    /**
     * Shared HKDF-Expand step used by both Argon2id and PBKDF2 derivation paths.
     * Produces encKey || macKey (64 bytes) from a 32-byte master key.
     */
    private byte[] stretchMasterKey(byte[] masterKey) throws Exception {
        byte[] encKey = hkdfExpand(masterKey, "enc".getBytes(StandardCharsets.UTF_8), KEY_BYTES);
        byte[] macKey = hkdfExpand(masterKey, "mac".getBytes(StandardCharsets.UTF_8), KEY_BYTES);
        Arrays.fill(masterKey, (byte) 0);
        byte[] stretched = new byte[KEY_BYTES * 2];
        System.arraycopy(encKey, 0, stretched, 0,         KEY_BYTES);
        System.arraycopy(macKey, 0, stretched, KEY_BYTES, KEY_BYTES);
        Arrays.fill(encKey, (byte) 0);
        Arrays.fill(macKey, (byte) 0);
        return stretched;
    }

    /**
     * HKDF-Expand only (RFC 5869 Section 2.3) via Bouncy Castle.
     * T(1) = HMAC-SHA256(PRK, "" || info || 0x01)
     * Bitwarden skips the Extract step - KDF output is used as PRK directly.
     */
    private byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws Exception {
        org.bouncycastle.crypto.macs.HMac hmac =
            new org.bouncycastle.crypto.macs.HMac(
                new org.bouncycastle.crypto.digests.SHA256Digest());
        hmac.init(new org.bouncycastle.crypto.params.KeyParameter(prk));
        hmac.update(info, 0, info.length);
        hmac.update((byte) 0x01); // block counter - must be last per RFC 5869
        byte[] out = new byte[hmac.getMacSize()];
        hmac.doFinal(out, 0);
        return Arrays.copyOf(out, length);
    }

    // ===================================================================
    // ===== CRYPTO - AES-256-GCM (Odin native)
    // ===================================================================

    /**
     * Encrypts bytes to an Odin GCM CipherString: "3.{iv_b64}|{ct_with_tag_b64}"
     * 96-bit IV, 128-bit auth tag. Auth tag appended to ciphertext by JCE provider.
     * No separate HMAC - GCM provides authenticated encryption natively.
     */
    private String encryptGcm(byte[] plaintext, byte[] encKey) throws Exception {
        byte[] iv = generateRandom(IV_BYTES_GCM);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"),
            new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ctWithTag = cipher.doFinal(plaintext);
        Base64.Encoder b64 = Base64.getEncoder();
        return CIPHER_TYPE_GCM + "." + b64.encodeToString(iv) + "|" + b64.encodeToString(ctWithTag);
    }

    // ===================================================================
    // ===== CRYPTO - AES-256-CBC + HMAC-SHA256 (Bitwarden native)
    // ===================================================================

    /**
     * Encrypts bytes to a Bitwarden CBC CipherString: "2.{iv_b64}|{ct_b64}|{mac_b64}"
     * MAC covers IV || ciphertext (Encrypt-then-MAC). Bitwarden's native format.
     */
    private String encryptCbc(byte[] plaintext, byte[] encKey, byte[] macKey) throws Exception {
        byte[] iv = generateRandom(IV_BYTES_CBC);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        byte[] ct = cipher.doFinal(plaintext);
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(macKey, "HmacSHA256"));
        hmac.update(iv); hmac.update(ct);
        byte[] mac = hmac.doFinal();
        Base64.Encoder b64 = Base64.getEncoder();
        return CIPHER_TYPE_CBC + "." + b64.encodeToString(iv) + "|" +
               b64.encodeToString(ct) + "|" + b64.encodeToString(mac);
    }

    /**
     * Convenience CBC decrypt that does not need cipherVersion - used by Bitwarden import
     * where the format is always type "2" (CBC).
     */
    private byte[] decryptCbc(String cs, byte[] encKey, byte[] macKey) throws Exception {
        String body   = cs.substring(cs.indexOf('.') + 1);
        String[] parts = body.split("\\|");
        if (parts.length < 3) throw new IllegalArgumentException("Invalid CBC CipherString.");
        Base64.Decoder b64 = Base64.getDecoder();
        byte[] iv  = b64.decode(parts[0]);
        byte[] ct  = b64.decode(parts[1]);
        byte[] mac = b64.decode(parts[2]);
        // Verify MAC before decrypting - constant-time comparison prevents timing attacks
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(macKey, "HmacSHA256"));
        hmac.update(iv); hmac.update(ct);
        if (!MessageDigest.isEqual(hmac.doFinal(), mac))
            throw new SecurityException("MAC mismatch - wrong password or corrupted data.");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(ct);
    }

    /**
     * Decrypts a CipherString dispatching on type prefix or cipherVersion fallback.
     * Type "3" = AES-256-GCM (Odin current), type "2" = AES-256-CBC+HMAC (Odin legacy).
     */
    private byte[] decryptCipherString(String cs, byte[] encKey, byte[] macKey,
                                       int cipherVersion) throws Exception {
        int    dotIdx = cs.indexOf('.');
        String prefix = dotIdx >= 0 ? cs.substring(0, dotIdx) : "";
        String body   = dotIdx >= 0 ? cs.substring(dotIdx + 1) : cs;
        boolean isGcm = CIPHER_TYPE_GCM.equals(prefix) || cipherVersion == CIPHER_VERSION_GCM;
        String[] parts = body.split("\\|");
        Base64.Decoder b64 = Base64.getDecoder();

        if (isGcm) {
            // ===== AES-256-GCM path =====
            if (parts.length < 2) throw new IllegalArgumentException("Invalid GCM CipherString.");
            byte[] iv        = b64.decode(parts[0]);
            byte[] ctWithTag = b64.decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
            // GCM verifies auth tag automatically - throws AEADBadTagException on tamper
            return cipher.doFinal(ctWithTag);
        } else {
            // ===== AES-256-CBC legacy path =====
            return decryptCbc(cs, encKey, macKey);
        }
    }

    // ===================================================================
    // ===== UTILITY HELPERS
    // ===================================================================

    /** Decrypts one data slot from a vault credential. Returns empty string on null or error. */
    private String decryptField(Yggdrasil.Credential c, int fieldIndex) {
        try {
            byte[] enc = c.getDataField(fieldIndex);
            if (enc == null) return "";
            char[] val = backend.decryptData(enc, c.iv);
            String s   = new String(val);
            Yggdrasil.wipeCharArray(val);
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Builds a Bitwarden "uris" array from a single plaintext URL string.
     * Outer envelope handles all encryption - no encryption applied here.
     */
    private JSONArray buildUrisPlaintext(String uri) {
        JSONArray uris = new JSONArray();
        if (uri != null && !uri.isEmpty()) {
            JSONObject u = new JSONObject();
            u.put("match", JSONObject.NULL);
            u.put("uri",   uri);
            uris.put(u);
        }
        return uris;
    }

    /**
     * Detects original Odin type from Bitwarden custom fields.
     * Scores each Futhark type by matching field labels - highest score wins.
     */
    private String detectOdinTypeFromFields(JSONArray fields) {
        Set<String> labels = new HashSet<>();
        for (int i = 0; i < fields.length(); i++)
            labels.add(fields.getJSONObject(i).optString("name", "").toLowerCase());
        String bestType  = "note";
        int    bestScore = 0;
        for (Futhark.EntryType t : Futhark.allTypes()) {
            int score = 0;
            for (Futhark.Field f : t.fields)
                if (labels.contains(f.label.toLowerCase())) score++;
            if (score > bestScore) { bestScore = score; bestType = t.typeKey; }
        }
        return bestType;
    }

    /** Maps a field label to its 0-based data array index for a given type. */
    private int labelToDataIndex(Futhark.EntryType type, String label) {
        for (int i = 0; i < type.fields.size(); i++)
            if (type.fields.get(i).label.equalsIgnoreCase(label)) return i;
        return -1;
    }

    /** Generates cryptographically secure random bytes. */
    private byte[] generateRandom(int length) {
        byte[] b = new byte[length];
        new SecureRandom().nextBytes(b);
        return b;
    }
}