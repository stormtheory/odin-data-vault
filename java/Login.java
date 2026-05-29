import java.awt.*;
import java.io.File;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

// ===== LOGIN WINDOW - owns full startup sequence, hands off to Odin for main window =====
public class Login extends JFrame {

    // ===== CONFIG - mirrors Odin constants =====
    private static final String DATABASE_VER    = "0";
    private static int    PASSWORD_LENGTH = 12; // Default for Vault Creation and Changes   // 12 is the safest with all Argon2id levels
    private static final int    PASSWORD_MAX_LEN = 70;
    // ===== STARTUP STATE =====
    private static Boolean      notInAJar       = false;
    private static boolean      arg_vaultPath   = false;
    private static boolean      arg_VaultLevel  = false;
    private static String       vaultPath;
    private static String       DATABASE_TYPE;
    private static String       theme_override  = "false";
    public  static boolean      DEBUG           = false;
    private static long         IDLE_TIMEOUT_MINUTES = 10;
    private static char[][]     creds;

    // ===== INSTANCE STATE =====
    private Connection          conn;
    private String              VaultLevel      = "HIGH"; // Default for Vault Creation
    private ImageIcon           dialogIcon      = null;
    private ImageIcon           appIcon         = null;
    private char[]              masterPassword  = new char[0];
    private String              username        = "single-user";
    private boolean             passwordGood    = false;
    private boolean             isWindows       = System.getProperty("os.name")
                                                    .toLowerCase().startsWith("windows");

    // ===== loginRunning - set false by Odin.buildMainUI() to close login window =====
    public volatile boolean     loginRunning    = true;

    // ===== Odin reference - receives credentials and builds main window =====
    private Odin                odin            = new Odin();

    private static final String[] RUNE_MESSAGES = {
        // ===== FORGE + FIRE =====
        "Firing up the forge...",
        "The forge breathes deep...",
        "Stoking the sacred flame...",
        "The embers remember your word...",

        // ===== ALLFATHER + RAVENS =====
        "Summoning the Allfather's key...",
        "Odin's ravens confirm the word...",
        "Huginn circles the cipher...",
        "Muninn recalls the sacred hash...",
        "The Allfather watches the gate...",

        // ===== RUNES =====
        "The runes stir in the deep...",
        "Reading the elder futhark...",
        "Carving the bind rune...",
        "The runes align in the dark...",
        "Inscribing the vault seal...",
        "Ancient script unlocks the way...",

        // ===== NORNS + FATE =====
        "Consulting the Norns...",
        "Urd weighs your past...",
        "Verdandi spins the present thread...",
        "Skuld reads what is to come...",
        "The threads of fate are checked...",

        // ===== YGGDRASIL =====
        "Yggdrasil routes the request...",
        "The world tree stirs...",
        "Roots reach into Niflheim...",
        "Branches touch the vault...",
        "The great ash tree remembers...",

        // ===== REALMS =====
        "The vault stirs from Helheim...",
        "Bifrost channel secured...",
        "Crossing the rainbow bridge...",
        "Asgard acknowledges the key...",
        "Midgard holds the secret safe...",
        "The nine realms stand witness...",

        // ===== MEAD + WISDOM =====
        "The mead of poetry flows...",
        "Wisdom poured from Mimir's well...",
        "Kvasir's knowledge unlocks the seal...",
        "The sacred mead grants passage...",

        // ===== MJOLNIR + WEAPONS =====
        "Mjolnir seals the cipher...",
        "Gungnir marks the vault door...",
        "The hammer strikes the final lock...",
        "Mjolnir returns to the worthy...",

        // ===== MISC NORSE =====
        "The Valkyries stand guard...",
        "Fenrir sleeps behind the gate...",
        "Jormungandr coils the perimeter...",
        "The wolf hears no false word...",
        "Tyr holds the balance...",
        "Freya blesses the passage...",
        "The horn of Heimdall is silent...",
        "Skadi watches from the peaks...",

        // ===== CLOSE =====
        "The vault remembers.",
        "The gate opens for the worthy.",
        "Your word was true."
    };

    // ===== HELP MENU =====
    public static void helpMenu() {
        System.out.println("""
        ---- Help Menu ---

            --vault_file      [dir_path/filename.db]
            --timeout         [time_in_mins]
            --security_level  [MINIMUM, BALANCED, HIGH, PARANOID] Default override the level for vault creation. **WARNING, consider your options before setting less then HIGH**
            --password_length [1-40] Default override of password length requirements for creation/change of Odin passwords. **WARNING, consider your options before setting less then 16**
            --dark            Dark mode override
            --light           Light mode override

            -d                Debug
            -h                Help Menu

        """);
        System.exit(0);
    }

    // ===== STARTUP ENTRY POINT - called from Odin.main() on a background thread =====
    public void start(String[] args) throws Exception {

        // ===== ARG PARSING =====
        // To add:
        //   RAM disk location
        //   --RAMdiskOverride
        //   Fix VaultLevel default

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--vault_file":
                case "--vault-file":
                    arg_vaultPath = true;
                    vaultPath = args[++i];
                    System.out.println("Vault Path set: " + vaultPath);
                    break;
                case "--security_level":
                case "--security-level":
                    arg_VaultLevel = true;
                    VaultLevel = args[++i];
                    System.out.println("Default Security Level set: " + VaultLevel);
                    break;
                case "--password_length":
                case "--password-length":
                    PASSWORD_LENGTH = Integer.getInteger(args[++i]);
                    System.out.println("Password Length Requirement: " + PASSWORD_LENGTH);
                    break;
                case "--timeout":
                    IDLE_TIMEOUT_MINUTES = Long.parseLong(args[++i]);
                    break;
                case "--light":
                    theme_override = "light";
                    break;
                case "--dark":
                    theme_override = "dark";
                    break;
                case "-d":
                    DEBUG = true;
                    break;
                case "-h":
                    helpMenu();
                    break;
                case "--test": // DO NOT USE FOR PRODUCTION FOR DEVELOPER TESTING ONLY
                    theme_override = "dark";
                    DEBUG = true;
                    PASSWORD_LENGTH = 3;
                    arg_VaultLevel = true;
                    VaultLevel = "PARANOID";
                    arg_vaultPath = true;
                    vaultPath = "/tmp/runes.db";
                    System.out.println("Default Security Level set: " + VaultLevel);
                    System.out.println("Vault Path set: " + vaultPath);
                    System.out.println("Password Length Requirement: " + PASSWORD_LENGTH);
                    break;
            }
        }

        // ===== THEME - must run before any Swing component is built =====
        ThemeManager.detect(DEBUG, theme_override);

        // ===== DEFAULT VAULT PATH =====
        if (!arg_vaultPath) {
            vaultPath = System.getProperty("user.home") + "/Documents/vault.db";
        }

        java.io.File dbFile = new java.io.File(vaultPath);
        boolean isNew = !dbFile.exists();

        // ===== LOAD ICONS =====
        // Load multiple sizes - OS picks best for taskbar, alt-tab, title bar
        List<Image> icons = new ArrayList<>();
        String[] iconSizesFolder = {"icons/shield/vault-shield2-bg-256.png"};
        
        String[] iconSizesJar = {"icons/shield/vault-shield2-bg-256.png"};

        // Load all available icon sizes into the window icon list
        for (String path : iconSizesJar) {
            URL iconUrl = getClass().getResource(path);
            if (iconUrl != null) {
               Image rawImage = new ImageIcon(iconUrl).getImage();
                if (path.contains("256") && dialogIcon == null) {
                    Image scaled = rawImage.getScaledInstance(64, 64, Image.SCALE_SMOOTH);
                    dialogIcon = new ImageIcon(scaled);
                }
            } else {
                if (DEBUG) System.out.println("[Login] Icon not found in JAR: " + path);
                notInAJar = true;
                System.out.println("Not in a Jar");
                break;
            }
        }

        // Load the main application icon used for scaled display (e.g. taskbar, title bar)
        if (!notInAJar){
        URL appIconUrl = getClass().getResource("icons/shield/vault-shield2-bg-256.png");
        if (appIconUrl != null) {
            Image rawImage = new ImageIcon(appIconUrl).getImage();
            icons.add(rawImage);
            Image scaled = rawImage.getScaledInstance(64, 64, Image.SCALE_SMOOTH);
            appIcon = new ImageIcon(scaled);
        } else {
            if (DEBUG) System.out.println("[Login] App icon not found in JAR: password_manager_icon58.png");
            notInAJar = true;
            System.out.println("Not in a Jar");
        }}

        if (notInAJar){
        for (String path : iconSizesFolder) {
            File iconFile = new File(path);
            if (iconFile.exists()) {
                // ===== Use 256px version scaled down for dialogs - only set once =====
                if (path.contains("256") && dialogIcon == null) {
                    Image scaled = new ImageIcon(iconFile.getAbsolutePath()).getImage().getScaledInstance(64, 64, Image.SCALE_SMOOTH);
                    dialogIcon = new ImageIcon(scaled);
                }
            } else {
                if (DEBUG) System.out.println("[Login] Dialog icon not found: " + iconFile.getAbsolutePath());
            }
        }
        File appiconFile = new File("icons/shield/vault-shield2-bg-256.png");
        if (appiconFile.exists()) {
            icons.add(new ImageIcon(appiconFile.getAbsolutePath()).getImage());
            Image scaled = new ImageIcon(appiconFile.getAbsolutePath()).getImage().getScaledInstance(64, 64, Image.SCALE_SMOOTH);
            appIcon = new ImageIcon(scaled);
        } else {
            if (DEBUG) System.out.println("[Login] App icon not found: " + appiconFile.getAbsolutePath());
        }
        }

        // ===== NO VAULT FOUND =====
        if (isNew) {
            // ===== Mutable state container - lambdas can't reassign local vars directly =====
            final String[]  resolvedPath  = { vaultPath }; // [0] updated by btnLocate
            final boolean[] resolvedIsNew = { true };      // [0] flipped false if existing vault found

            JButton btnCreate = new JButton("Create New Vault");
            JButton btnLocate = new JButton("Locate Existing Vault");
            JButton kdfBtn = new JButton("Strength Chart");
            kdfBtn.addActionListener(e -> KdfChartDialog.show());
            ThemeManager.styleSurfaceButton(kdfBtn);
            
            JButton btnCancel = new JButton("Cancel");
            ThemeManager.styleSurfaceButton(btnCreate);
            ThemeManager.styleSurfaceButton(btnLocate);
            ThemeManager.styleSurfaceButton(btnCancel);
            
            JOptionPane vaultPane = new JOptionPane(
                "No vault found at " + vaultPath + "\nCreate a new vault or locate an existing one?",
                JOptionPane.QUESTION_MESSAGE,
                JOptionPane.DEFAULT_OPTION,
                dialogIcon,
                new Object[]{ btnCreate, btnLocate, kdfBtn, btnCancel },
                null
            );

            JDialog vaultDialog = vaultPane.createDialog(null, "Vault Not Found");

            vaultDialog.setModal(true); // ===== Block until user resolves ===== 
            // ===== CANCEL - exit before any vault is touched =====
            btnCancel.addActionListener(e -> { vaultDialog.dispose(); System.exit(0); });

            // ===== CREATE - fall through to new vault creation =====
            btnCreate.addActionListener(e -> vaultDialog.dispose());

            // ===== LOCATE - open file chooser =====
            btnLocate.addActionListener(e -> {
                vaultDialog.dispose();
                JFileChooser fc = new JFileChooser();
                for (Component comp : fc.getComponents()) {
                ThemeManager.themeFileChooserComponents(comp);
                }
                fc.setDialogTitle("Locate your vault.db file");
                fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "SQLite Database (*.db)", "db"));
                fc.setCurrentDirectory(new File(System.getProperty("user.home") + "/Documents"));
                if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    resolvedPath[0]  = fc.getSelectedFile().getAbsolutePath();
                    resolvedIsNew[0] = false; // ===== Existing vault - skip init =====
                } else {
                    System.exit(1); // ===== User abandoned chooser - no valid path =====
                }
            });

            // ===== X-CLOSE treated as cancel =====
            vaultDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            vaultDialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent e) {
                    vaultDialog.dispose(); System.exit(0);
                }
            });

            vaultDialog.setVisible(true); // ===== Blocks until button disposes it =====

            // ===== Flush resolved state after dialog unblocks =====
            vaultPath = resolvedPath[0];
            isNew     = resolvedIsNew[0];
            dbFile    = new File(vaultPath);
        }

        // ===== LOAD SQLITE JDBC DRIVER =====
        Class.forName("org.sqlite.JDBC");

        // ===== VAULT CHECKS =======================================================================================================
        try{
        if (!isNew) {
            conn          = DriverManager.getConnection("jdbc:sqlite:" + vaultPath);
            DATABASE_TYPE = Mimir.Pull_DB_Text_Meta_item(conn, "type");
            VaultLevel    = Mimir.Pull_DB_Text_Meta_item(conn, "vault_level");
        }} catch (Exception e) {isNew = true; System.out.println("ERROR: Database maybe corrupted");}
        try{
        if (!isNew) {    
            theme_override = Mimir.Pull_DB_Text_Meta_item(conn, "theme");
            if (theme_override.equals("light") || theme_override.equals("dark") || theme_override.equals("system")) {
                if (!theme_override.equals("system")) {
                    ThemeManager.detect(DEBUG, theme_override);
                    System.out.println("[DB Check] Theme DB override setting to: " + theme_override);
                }
            }}} catch (Exception f) {if (DEBUG) System.out.println("DB Theme error: " + f);}
        
        try{
        if (!isNew) {    
            String raw = Mimir.Pull_DB_Text_Meta_item(conn, "client-timeout");
            long timeout_override = (raw != null && !raw.isBlank()) ? Integer.parseInt(raw.trim()) : IDLE_TIMEOUT_MINUTES;
            System.out.println("[DB Check] DB override Timeout setting to: " + timeout_override);
            }} catch (Exception t) {if (DEBUG) System.out.println("[DB Check] DB Timeout error: " + t);}


        // ===== SHUTDOWN HOOK =====
        Mimir.registerShutdownHook(isWindows, DEBUG);

        // ===== NEW VAULT - create master password, then finish startup =====
        if (isNew) {
            System.out.println("Creating new Vault");
            creds = odin.createNewMasterPass(conn, true, arg_VaultLevel, VaultLevel, PASSWORD_LENGTH);
            masterPassword = creds[1];
            username = new String(creds[2]);
            VaultLevel = new String(creds[3]);
            DATABASE_TYPE = new String(creds[4]);
            System.out.println("Vault Password Set and user: " + username);
            passwordGood   = true; // ===== Unblocks the polling loop in start() =====
            finishStartup(isNew, icons);
            
        } else {
            // ===== EXISTING VAULT - show login window on EDT =====
            // start() thread polls behind it while user types
            SwingUtilities.invokeLater(() -> {
                buildUI(icons); // ===== Build UI now - conn, vaultPath, DATABASE_TYPE all ready =====
                setVisible(true);
            });

            // ===== POLL until callback fires passwordGood =====
            while (!passwordGood) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    // ===== Re-set interrupt flag; never swallow silently =====
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            finishStartup(isNew, icons);
        }
    }

    // ===== FINISH STARTUP - crypto init, load data, hand off to Odin =====
    private void finishStartup(boolean isNew, List<Image> icons) throws Exception {
        if (masterPassword == null || masterPassword.length == 0 ) {
            System.out.println("ERROR: No Master Password Found.");
            System.exit(1);
        }
        if (username == null || username.isEmpty()) {
        System.out.println("ERROR: No username given.");
            System.exit(1);
        }

        // ===== NEW VAULT - create DB schema =====
        if (isNew) {
            conn = DriverManager.getConnection("jdbc:sqlite:" + vaultPath);
            odin.backend.BuildDatabase(conn, username, DATABASE_VER, DATABASE_TYPE, VaultLevel);
        }

        // ===== GET SALT - random per vault, prevents rainbow table attacks =====
        byte[] vault_salt = odin.backend.getOrCreateVaultSalt(conn);

        // ===== INIT BACKEND - derives encryption key from password + salt =====
        odin.backend.GetFiredUp(masterPassword, vault_salt, conn, username, DATABASE_TYPE, DEBUG);
        Yggdrasil.wipeCharArray(masterPassword); // ===== Wipe password from memory =====

        // ===== LOAD ALL CREDENTIALS =====
        try {
            odin.credentials = odin.backend.loadAll(conn);
        } catch (javax.crypto.AEADBadTagException e) {
            // ===== Tag mismatch = wrong key or corrupted data =====
            JOptionPane.showMessageDialog(null,
                "Failed to decrypt - wrong master password or corrupted data.",
                "Decryption Error", JOptionPane.ERROR_MESSAGE, dialogIcon);
            System.exit(1);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                "Unexpected error reading vault entry.",
                "Error", JOptionPane.ERROR_MESSAGE, dialogIcon);
            System.exit(1);
        }

        // ===== PASS CONN + VARS TO ODIN before building UI =====
        odin.conn             = conn;
        odin.DATABASE_TYPE    = DATABASE_TYPE;
        odin.username         = username;
        odin.VaultLevel       = VaultLevel;
        odin.dialogIcon       = dialogIcon;
        odin.appIcon          = appIcon;
        odin.PASSWORD_LENGTH  = PASSWORD_LENGTH;
        odin.IDLE_TIMEOUT_MINUTES = IDLE_TIMEOUT_MINUTES;
        odin.DEBUG            = DEBUG;
            
        // ===== BUILD MAIN WINDOW on EDT - login stays visible until this completes =====
        SwingUtilities.invokeLater(() -> {
            odin.buildMainUI(icons);
            // ===== Signal login window to close - rune timer detects this =====
            loginRunning = false;
            if (isNew) dispose(); // ===== New vault skips rune timer - close immediately =====
        });
    }

    // ===== BUILD LOGIN UI - called from EDT once conn + vars are ready =====
    private void buildUI(List<Image> icons) {
        setTitle("Odin Runa Login");
        setSize(520, 310);
        setLocationRelativeTo(null);
        setResizable(false);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setIconImages(icons);

        // ===== OUTER PANEL =====
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(ThemeManager.BG);
        outer.setBorder(BorderFactory.createLineBorder(ThemeManager.BORDER, 1));
        
        // ===== LEFT PANEL - icon + app name =====
        JPanel leftPanel = new JPanel();
        leftPanel.setBackground(ThemeManager.LEFT_LOGIN);
        leftPanel.setPreferredSize(new Dimension(140, 0));
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
        leftPanel.setBorder(BorderFactory.createEmptyBorder(30, 16, 30, 16));

        if (dialogIcon != null) {
            JLabel iconLabel = new JLabel(dialogIcon);
            iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            leftPanel.add(iconLabel);
            leftPanel.add(Box.createVerticalStrut(12));
        }

        JLabel appName = new JLabel("Odin");
        appName.setFont(new Font("Segoe UI", Font.BOLD, 14));
        appName.setForeground(ThemeManager.TEXT);
        appName.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel appName3 = new JLabel("Data Vault");
        appName3.setFont(new Font("Segoe UI", Font.BOLD, 14));
        appName3.setForeground(ThemeManager.ACCENT);
        appName3.setAlignmentX(Component.CENTER_ALIGNMENT);
        leftPanel.add(appName);
        leftPanel.add(appName3);

        // ===== RIGHT PANEL - fields =====
        JPanel rightPanel = new JPanel();
        rightPanel.setBackground(ThemeManager.BG);
        rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
        rightPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 16, 20));

        // ===== VAULT PATH DISPLAY =====
        JLabel pathLabel = new JLabel("Vault:");
        pathLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        pathLabel.setForeground(ThemeManager.TEXT_MUTED);
        pathLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // ===== Truncate long paths - full path in tooltip =====
        String displayPath = vaultPath.length() > 45
            ? "..." + vaultPath.substring(vaultPath.length() - 42)
            : vaultPath;
        JLabel pathValue = new JLabel(displayPath);
        pathValue.setFont(new Font("Segoe UI Mono", Font.PLAIN, 11));
        pathValue.setForeground(ThemeManager.ACCENT);
        pathValue.setToolTipText(vaultPath);
        pathValue.setAlignmentX(Component.LEFT_ALIGNMENT);

        // ===== USERNAME FIELD - multi-user vault only =====
        JLabel usernameLabel = new JLabel("Username");
        usernameLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        usernameLabel.setForeground(ThemeManager.TEXT_MUTED);
        usernameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextField usernameField = new JTextField();
        usernameField.setBackground(ThemeManager.SURFACE2);
        usernameField.setForeground(ThemeManager.TEXT);
        usernameField.setCaretColor(ThemeManager.ACCENT);
        usernameField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ThemeManager.BORDER),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        usernameField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        // ===== Only show username for multi-user vaults =====
        boolean isMulti = DATABASE_TYPE != null && DATABASE_TYPE.equals("m");
        usernameLabel.setVisible(isMulti);
        usernameField.setVisible(isMulti);

        // ===== PASSWORD FIELD =====
        JLabel passLabel = new JLabel("Master Password");
        passLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        passLabel.setForeground(ThemeManager.TEXT_MUTED);
        passLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPasswordField pf = new JPasswordField();
        pf.setBackground(ThemeManager.SURFACE2);
        pf.setForeground(ThemeManager.TEXT);
        pf.setCaretColor(ThemeManager.ACCENT);
        pf.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ThemeManager.BORDER),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        pf.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        pf.setAlignmentX(Component.LEFT_ALIGNMENT);

        // ===== SEARCH FOR VAULT BUTTON =====
        JButton searchBtn = new JButton("🔍 Search for Vault");
        ThemeManager.styleSurfaceButton(searchBtn);
        searchBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        searchBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            for (Component comp : fc.getComponents()) {
                ThemeManager.themeFileChooserComponents(comp);
            }
            fc.setDialogTitle("Locate your vault.db file");
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "SQLite Database (*.db)", "db"));
            fc.setCurrentDirectory(new File(System.getProperty("user.home") + "/Documents"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                vaultPath = fc.getSelectedFile().getAbsolutePath();
                // ===== Update display and reconnect to new vault =====
                String dp = vaultPath.length() > 45
                    ? "..." + vaultPath.substring(vaultPath.length() - 42)
                    : vaultPath;
                pathValue.setText(dp);
                pathValue.setToolTipText(vaultPath);
                try {
                    if (conn != null) conn.close();
                    conn = DriverManager.getConnection("jdbc:sqlite:" + vaultPath);
                } catch (Exception ex) { ex.printStackTrace(); }
            }
        });

        // ===== CONNECT TO SERVER BUTTON - future feature stub =====
        JButton serverBtn = new JButton("🌐 Connect to Server");
        ThemeManager.styleSurfaceButton(serverBtn);
        serverBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        serverBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        serverBtn.addActionListener(e ->
            JOptionPane.showMessageDialog(this,
                "Server sync is a coming feature.\nStay tuned!",
                "Coming Soon", JOptionPane.INFORMATION_MESSAGE, dialogIcon)
        );

        // ===== LOGIN BUTTON =====
        JButton loginBtn = new JButton("Unlock Vault");
        ThemeManager.styleAccentButton(loginBtn);
        loginBtn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        loginBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        loginBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        // ===== STATUS LABEL - Norse flavor messages during load =====
        JLabel statusLabel = new JLabel("Speak the word.");
        statusLabel.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // ===== doLogin - fires on button click or Enter key =====
        Runnable doLogin = () -> {
            char[] enteredPassword = pf.getPassword();
            if (enteredPassword.length == 0) {
                JOptionPane.showMessageDialog(this, "Password cannot be empty.",
                    "The runes are silent.", JOptionPane.ERROR_MESSAGE);
                return;
            } else if (enteredPassword.length >= PASSWORD_MAX_LEN) {
                JOptionPane.showMessageDialog(this, "Password can not be longer than " + PASSWORD_MAX_LEN + " characters.",
                    "The runes are silent.", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // ===== Store credentials - finishStartup() reads these =====
            masterPassword = enteredPassword;
            username       = isMulti ? usernameField.getText().trim() : "single-user";
            passwordGood   = true; // ===== Unblocks the polling loop in start() =====

            // ===== LOCK inputs - prevent double-submit =====
            loginBtn.setEnabled(false);
            pf.setEnabled(false);
            loginBtn.setText("The forge awakens...");

            // ===== RUNE TIMER - animates while finishStartup() runs behind this =====
            int[] step = {0};
            javax.swing.Timer runeTimer = new javax.swing.Timer(820, null);
            runeTimer.addActionListener(tick -> {
                if (loginRunning) {
                    // ===== Main window not ready yet - keep cycling rune messages =====
                    statusLabel.setText(RUNE_MESSAGES[step[0] % RUNE_MESSAGES.length]);
                    step[0]++;
                } else {
                    // ===== loginRunning set false by buildMainUI() - close login =====
                    runeTimer.stop();
                    dispose();
                }
            });
            runeTimer.start();
        };

        // ===== WIRE BUTTON AND ENTER KEY =====
        loginBtn.addActionListener(e -> doLogin.run());
        pf.addActionListener(e -> doLogin.run());

        // ===== ASSEMBLE RIGHT PANEL =====
        rightPanel.add(pathLabel);
        rightPanel.add(Box.createVerticalStrut(2));
        rightPanel.add(pathValue);
        rightPanel.add(Box.createVerticalStrut(12));
        rightPanel.add(usernameLabel);
        if (isMulti) rightPanel.add(Box.createVerticalStrut(4));
        rightPanel.add(usernameField);
        rightPanel.add(Box.createVerticalStrut(8));
        rightPanel.add(passLabel);
        rightPanel.add(Box.createVerticalStrut(4));
        rightPanel.add(pf);
        rightPanel.add(Box.createVerticalStrut(12));
        rightPanel.add(loginBtn);
        rightPanel.add(Box.createVerticalStrut(8));
        rightPanel.add(statusLabel);

        // ===== BOTTOM ROW - search + server buttons =====
        JPanel bottomRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        bottomRow.setBackground(ThemeManager.BG);
        bottomRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        bottomRow.add(searchBtn);
        bottomRow.add(serverBtn);
        rightPanel.add(bottomRow);

        // ===== ASSEMBLE OUTER =====
        outer.add(leftPanel,  BorderLayout.WEST);
        outer.add(rightPanel, BorderLayout.CENTER);
        setContentPane(outer);
    }

}