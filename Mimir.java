import java.io.File;
import java.security.SecureRandom;
import java.sql.*;

public class Mimir 
{
    public static void registerShutdownHook(boolean isWindows, boolean DEBUG) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Shutdown Hook] Running cleanup command...");

            try {
                // SECURITY CRITICAL: wipe password
                Yggdrasil.cleanupWipeDown(DEBUG);
                cleanSQLiteNativeLibs(DEBUG);
                try{
                    BinaryViewer.wipeAll();
                } catch (Exception ell){System.out.println(ell);}

                // Safe process execution
                ProcessBuilder pb = isWindows
                        ? new ProcessBuilder("cmd.exe", "/c", "echo Goodbye...")
                        : new ProcessBuilder("echo", "Goodbye...");

                pb.inheritIO();

                Process process = pb.start();
                int exitCode = process.waitFor();

                System.out.println(exitCode);

            } catch (Exception e) {
                System.err.println("[Shutdown Hook] Failed to run command: " + e.getMessage());
            }
        }, "shutdown-hook-thread"));
    }

    private static void cleanSQLiteNativeLibs(boolean DEBUG) {
        // -- Use the same resolved tmpdir that was set in configureSQLiteTmpDir() --
        //    This guarantees we clean the right directory on every platform
        System.out.println("Cleaning SQLite bridge");
        String sqliteTmpPath = System.getProperty("org.sqlite.tmpdir",
                            System.getProperty("java.io.tmpdir"));
        if (DEBUG) System.out.println("[SQLite Cleanup] TmpPath: " + sqliteTmpPath);

        File tmpDir = new File(sqliteTmpPath);
        if (!tmpDir.exists() || !tmpDir.isDirectory()) return;

        // -- Match all SQLite-JDBC native libs regardless of version or UUID --
        //    Pattern: sqlite-{version}-{UUID}-sqlitejdbc.{dll|so|dylib}
        File[] matches = tmpDir.listFiles((dir, name) ->
            name.startsWith("sqlite-") &&
            (name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib"))
        );

        if (matches == null) return;

        for (File f : matches) {
            // -- Only delete files, never directories --
            if (!f.isFile()) continue;

            if (!f.delete()) {
                // -- Fallback for Windows-locked dlls - JVM deletes on full exit --
                f.deleteOnExit();
                if (DEBUG) System.out.println("[SQLite Cleanup] Deferred delete (locked): " + f.getName());
            } else {
                if (DEBUG) System.out.println("[SQLite Cleanup] Deleted: " + f.getName());
            }
        }
        System.out.println("Cleaned SQLite bridge [DONE]");
    }

    /**
     * Resolves the correct SQLite native library temp directory per platform.
     * SQLite-JDBC needs an EXECUTABLE directory to extract and load its native
     * lib - /tmp is often mounted noexec on hardened Linux (STIG/CIS).
     *
     * Platform strategy:
     *   Windows - %TEMP% (%USERPROFILE%\AppData\Local\Temp) is always executable
     *   Linux   - user.home avoids noexec /tmp; falls back to working dir
     *   Mac     - ~/Library/Caches is the idiomatic temp space, avoids /tmp
     *             (Mac STIGs are less aggressive than Linux but /tmp can still
     *              be restricted in hardened enterprise MDM environments)
     *
     * Set before any SQLite class is loaded - must be called at the very top
     * of main() before new Connection() or any JDBC touch.
     */
    protected static void configureSQLiteTmpDir() {
        String os = System.getProperty("os.name", "").toLowerCase();

        // -- Platform-specific safe executable temp locations --
        String TMP_WINDOWS = System.getenv("TEMP") != null
            ? System.getenv("TEMP")
            : System.getProperty("user.home") + "\\AppData\\Local\\Temp\\sqlite-tmp";

        String TMP_LINUX = System.getProperty("user.home") + "/.sqlite-tmp";

        String TMP_MAC = System.getProperty("user.home") + "/Library/Caches/sqlite-tmp";

        // -- Select based on OS --
        String sqliteTmp;
        if (os.contains("win")) {
            sqliteTmp = TMP_WINDOWS;
        } else if (os.contains("mac") || os.contains("darwin")) {
            sqliteTmp = TMP_MAC;
        } else {
            // Linux and anything else - user.home subdir avoids noexec /tmp
            sqliteTmp = TMP_LINUX;
        }

        // -- Ensure the directory exists and is executable --
        File tmpDir = new File(sqliteTmp);
        if (!tmpDir.exists()) {
            tmpDir.mkdirs(); // create if missing (e.g. first run)
        }

        // -- Verify it is actually usable before committing --
        //    canExecute() on a directory = can list/traverse it (exec bit)
        if (!tmpDir.canWrite() || !tmpDir.canExecute()) {
            // Last resort fallback - working directory beside the jar
            // This is what -Dorg.sqlite.tmpdir=. was doing in the scripts
            sqliteTmp = System.getProperty("user.dir");
        }

        // -- Set the property before any SQLite/JDBC class loads --
        System.setProperty("org.sqlite.tmpdir", sqliteTmp);

        //System.out.println("[SQLite] tmpdir -> " + sqliteTmp);
    }

    protected static String Pull_DB_Text_Meta_item(Connection conn, String key) throws Exception {
        String sql = "SELECT Tvalue FROM meta WHERE key = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Meta key not found: " + key);
                }
                return rs.getString(1);
            }
        }
    }

    protected static void Update_DB_Text_Meta_item(Connection conn, String key, String value) throws Exception {
        String sql = "UPDATE meta SET Tvalue = ? WHERE key = ?";
        try (PreparedStatement update = conn.prepareStatement(sql)) {
            update.setString(1, value);
            update.setString(2, key);
            int rows = update.executeUpdate();
            if (rows == 0) {
                throw new IllegalStateException("Update affected 0 rows - key not found: " + key);
            }
        }
    }
    // ===== DELETE ENTRY ===== ----- Yup
    protected void deleteEntry(Connection conn, int id) throws Exception {
        String sql = "DELETE FROM vault WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            int rows = stmt.executeUpdate();
            if (rows == 0) {
                throw new IllegalStateException("Delete affected 0 rows - vault id not found: " + id);
            }
        }
    }

    // ===== User DELETE ===== ----- Yup
    protected void userdelEntry(Connection conn, String user_id) throws Exception {
        String sql = "DELETE FROM users WHERE user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user_id);
            int rows = stmt.executeUpdate();
            if (rows == 0) {
                throw new IllegalStateException("Delete affected 0 rows - user_id not found: " + user_id);
            }
        }
    }

    public static char[] generatePassword(int length, boolean useABC, boolean use123, boolean useSpec) {
        // Build the character pool from enabled sets
        // Simple password generator
        String chars = "";
        if (useABC)  chars += "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        if (use123)  chars += "0123456789";
        if (useSpec) chars += "!@#$%^*()-_=+[]:.?";

        if (chars.isEmpty()) chars = "abcdefghijklmnopqrstuvwxyz";

        SecureRandom random = new SecureRandom();
        char[] password = new char[length];
        for (int i = 0; i < length; i++) {
            password[i] = chars.charAt(random.nextInt(chars.length()));
        }
        return password;
    }
}
