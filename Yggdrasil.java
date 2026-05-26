import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Security;
import java.time.Instant;

import javax.security.auth.DestroyFailedException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.sql.*;
import java.util.*;

import de.mkammerer.argon2.Argon2Advanced;
import de.mkammerer.argon2.Argon2Factory;
import de.mkammerer.argon2.Argon2Factory.Argon2Types;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.modes.GCMModeCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public class Yggdrasil {
    public static boolean DEBUG = false; // set to false before production

    // ===== CONFIG =====
    private static final int GCM_TAG_LENGTH = 128; // GCM authentication tag size in bits - maximum tag size is 128
    private static final int IV_LENGTH      = 12;  // NIST SP 800-38D explicitly recommends IV length in bytes so, 12×8=96bit --- We do not recommend chnaging this.
    private static final int SALT_SIZE      = 32;  // Argon2 salt size in bytes - 16 is the minimum ---- 32 is the recommended

    private static byte[] User_AES_Key;
    private static byte[] Vault_KEY;
    private static byte[] Vault_Use_Key;
    public boolean arg_VaultLevel = false;
    protected String VaultLevel = "";
    protected String VK_STATUS  = "";
    protected String Vault_Status  = "";
    private static byte[] user_salt;
    protected Mimir databaseutilities = new Mimir();
    private static List<Credential> credentials = new ArrayList<>();

    // ===== DATA CLASS =====
    // One Credential instance per vault row - all sensitive fields stay encrypted
    // in memory; only tag, username, notes are decrypted on load for table display
    protected static class Credential {
        protected int    id;
        protected String type;          // entry type key - "account", "note", "card" etc.
        protected char[] tag;           // decrypted label/name - shown in table
        protected boolean favorite;
        protected String folderId;
        protected String creationDate;
        protected String revisionDate;
        protected char[] totp;

        // ===== GENERIC DATA FIELDS =====
        // Mirrors data0..data8 DB columns.  Stored as encrypted bytes.
        // Decrypted only on demand (Show button / double-click copy).
        // Index 0 = data0, index 1 = data1, ... index 8 = data8
        protected byte[][] dataFields = new byte[9][];

        // IV used for all encrypted fields in this row
        protected byte[] iv;

        /**
         * Returns the encrypted byte array for the given field index (0-based).
         * Returns null if the field is unused / null in the database.
         */
        protected byte[] getDataField(int index) {
            if (index < 0 || index >= dataFields.length) return null;
            return dataFields[index];
        }

        /**
         * Wipe plaintext fields - call when the credential is evicted from memory.
         * Does NOT wipe encrypted bytes - those are needed for on-demand decryption.
         * Call close_wipe() separately when the vault is shutting down.
         */
        protected void wipe() {
            if (tag   != null) { Arrays.fill(tag,   '\0'); tag   = null; }
        }

        /**
         * Wipe encrypted bytes and IV - call only at full shutdown.
         * Even ciphertext shouldn't linger in memory longer than necessary.
         */
        protected void close_wipe() {
            if (iv != null) { Arrays.fill(iv, (byte) 0); iv = null; }
            for (int i = 0; i < dataFields.length; i++) {
                if (dataFields[i] != null) {
                    Arrays.fill(dataFields[i], (byte) 0);
                    dataFields[i] = null;
                }
            }
        }
    }

    // ===== INIT (GetFiredUp) =====
    // A salt is random data added before key derivation - prevents rainbow table attacks
    protected void GetFiredUp(char[] masterPassword, byte[] vault_salt, Connection conn, String username, String type, boolean debug) throws Exception {
        DEBUG = debug;
        System.out.println(username);
        System.out.println("masterPassword empty? " +
            (masterPassword == null || masterPassword.length == 0 || masterPassword[0] == '\0'));

        Security.addProvider(new BouncyCastleProvider());

        int[] params   = loadArgon2Params(conn, username);
        user_salt      = loadUserSalt(conn, username);
        User_AES_Key   = deriveKey(masterPassword, params, username, user_salt, conn);
        VK_STATUS      = Mimir.Pull_DB_Text_Meta_item(conn, "vk_status");
        Vault_Status   = Mimir.Pull_DB_Text_Meta_item(conn, "vault_status");

        if (type.equals("m")) {
            if (VK_STATUS.equals("gen")) {
                // ===== FIRST LOGIN - generate vault key and wrap with user key =====
                Vault_KEY = generateVaultKey();
                byte[] wrappedKey = wrapVaultKey(User_AES_Key);
                try (PreparedStatement update = conn.prepareStatement(
                    "UPDATE users SET wrapped_vk = ? WHERE user_id = ?")) {
                    update.setBytes(1, wrappedKey);
                    update.setString(2, username);
                    update.executeUpdate();
                }
                Mimir.Update_DB_Text_Meta_item(conn, "vk_status", "true");
                wipeByteArray(wrappedKey);

            } else if (VK_STATUS.equals("true")) {
                // ===== EXISTING VAULT - unwrap vault key with user key =====
                String sql = "SELECT wrapped_vk FROM users WHERE user_id = ?";
                PreparedStatement stmt = conn.prepareStatement(sql);
                stmt.setString(1, username);
                ResultSet rs = stmt.executeQuery();

                // AESWrapPad - unwraps the vault key using the user's derived key
                Cipher cipher = Cipher.getInstance("AESWrapPad");
                cipher.init(Cipher.UNWRAP_MODE, new SecretKeySpec(User_AES_Key, "AES"));
                SecretKey tempKey = (SecretKey) cipher.unwrap(
                    rs.getBytes("wrapped_vk"), "AES", Cipher.SECRET_KEY);
                Vault_KEY = tempKey.getEncoded();
                try { tempKey.destroy(); } catch (DestroyFailedException e) { /* expected */ }
                wipeByteArray(User_AES_Key);
            }
        }

        // Multi-user uses the vault key; single-user uses the user key directly
        Vault_Use_Key = type.equals("m") ? Vault_KEY : User_AES_Key;

        wipeCharArray(masterPassword);

        // Setup tasks
        if (Vault_Status.equals("new")){
            byte[] iv = generateIV();
            byte[] uuid = encryptData(GenUUID.generateAsString().toCharArray(), iv, Vault_Use_Key);
            set_DB_Creation_User(conn, username);

        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO server(uuid, iv) " +
                "VALUES(?, ?)")) {
                insert.setBytes(1, uuid);
                insert.setBytes(2, iv);
                insert.executeUpdate();
            }
        }
        set_DB_LastLogin_User(conn, username);
    }

    // ===== CHANGE MASTER PASSWORD =====
    protected void changeMasterPass(Connection conn, char[] masterPassword, String username)
        throws Exception {
        String DATABASE_TYPE = Mimir.Pull_DB_Text_Meta_item(conn, "type");
        VaultLevel = Mimir.Pull_DB_Text_Meta_item(conn, "vault_level");

        Argon2Profile.Profile profile =  Argon2Profile.profileSelector(VaultLevel);
        int[] params = {profile.iterations(), profile.memoryKb(), profile.parallelism()};

        // Generate a fresh salt - never reuse a salt even for the same user
        byte[] new_user_salt = new byte[SALT_SIZE];
        new SecureRandom().nextBytes(new_user_salt);

        byte[] new_User_AES_Key = deriveKey(masterPassword, params, username, new_user_salt, conn);

        if (DATABASE_TYPE.equals("m")) {
            // Re-wrap the existing vault key with the new user key
            byte[] wrappedKey = wrapVaultKey(new_User_AES_Key);
            try (PreparedStatement update = conn.prepareStatement(
                "UPDATE users SET wrapped_vk = ? WHERE user_id = ?")) {
                update.setBytes(1, wrappedKey);
                update.setString(2, username);
                update.executeUpdate();
            }
            wipeByteArray(wrappedKey);
            wipeByteArray(new_User_AES_Key);
        }

        // Save new salt and Argon2 parameters
        try (PreparedStatement update = conn.prepareStatement(
            "UPDATE users SET salt = ?, argon2_iter = ?, argon2_mem = ?, argon2_para = ? WHERE user_id = ?")) {
            update.setBytes(1, new_user_salt);
            update.setInt(2, profile.iterations());
            update.setInt(3, profile.memoryKb());
            update.setInt(4, profile.parallelism());
            update.setString(5, username);
            update.executeUpdate();
        }

        if (DATABASE_TYPE.equals("s")) {
            // Single-user vault: all entries must be re-encrypted with the new key
            // IMPORTANT: user must not close the app during this operation
            System.out.println("Starting full vault re-encryption...");
            decryptEncryptWholeVault(conn, Vault_Use_Key, new_User_AES_Key);
            Vault_Use_Key = new_User_AES_Key;
            System.out.println("Vault re-encryption complete.");
        }
    }

    // ===== RE-ENCRYPT WHOLE VAULT =====
    // Used during single-user password change - decrypts all rows with old key
    // and re-encrypts with new key.  Each row gets a fresh IV.
    protected void decryptEncryptWholeVault(Connection conn, byte[] old_key, byte[] new_key)
        throws Exception {
        // SELECT all data columns - vault is the renamed vault table
        String sql = "SELECT id, type, folderId, tag, data0, data1, data2, data3, data4, data5, data6, data7, data8, revisionDate, creationDate, iv FROM vault";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                byte[] new_iv = generateIV();

                // Re-encrypt tag and notes
                byte[] enc_tag   = encryptData(decryptData(rs.getBytes("tag"),   rs.getBytes("iv"), old_key), new_iv, new_key);
                byte[] enc_type   = encryptData(decryptData(rs.getBytes("type"),   rs.getBytes("iv"), old_key), new_iv, new_key);
                byte[] enc_folderId   = encryptData(decryptData(rs.getBytes("folderId"),   rs.getBytes("iv"), old_key), new_iv, new_key);
                byte[] enc_revisionDate   = encryptData(decryptData(rs.getBytes("revisionDate"),   rs.getBytes("iv"), old_key), new_iv, new_key);
                byte[] enc_creationDate   = encryptData(decryptData(rs.getBytes("creationDate"),   rs.getBytes("iv"), old_key), new_iv, new_key);

                // Re-encrypt all data fields that are non-null
                byte[][] enc_data = new byte[9][];
                for (int i = 0; i < Futhark.DATA_COLUMNS.length; i++) {
                    byte[] raw = rs.getBytes(Futhark.DATA_COLUMNS[i]);
                    if (raw != null) {
                        enc_data[i] = encryptData(decryptData(raw, rs.getBytes("iv"), old_key), new_iv, new_key);
                    }
                }

                // Build dynamic UPDATE statement
                StringBuilder updateSql = new StringBuilder(
                    "UPDATE vault SET tag=?, data0=?, data1=?, data2=?, data3=?, data4=?, data5=?, data6=?, data7=?, data8=?, type=?, folderId=?, revisionDate=?, creationDate=?, iv=? WHERE id=?");
                try (PreparedStatement update = conn.prepareStatement(updateSql.toString())) {
                    update.setBytes(1, enc_tag);
                    for (int i = 0; i < 9; i++) {
                        update.setBytes(i + 3, enc_data[i]); // nulls handled by JDBC as SQL NULL
                    }
                    update.setBytes(11, enc_type);
                    update.setBytes(12, enc_folderId);
                    update.setBytes(13, enc_revisionDate);
                    update.setBytes(14, enc_creationDate);
                    update.setBytes(15, new_iv);
                    update.setInt(16, rs.getInt("id"));
                    int rows = update.executeUpdate();
                    if (rows == 0) {
                        throw new IllegalStateException("Re-encrypt failed - id not found: " + rs.getInt("id"));
                    }
                    System.out.println("Re-encrypted entry: " + rs.getInt("id"));
                }

                // Wipe all temporary encrypted byte arrays
                wipeByteArray(enc_tag);
                wipeByteArray(new_iv);
                for (byte[] d : enc_data) wipeByteArray(d);
            }
        }
    }

    // ===== CLEANUP WIPE DOWN =====
    // Called by shutdown hook - zeros all key material and credential data in memory
    protected static void cleanupWipeDown(boolean DEBUG) throws Exception {
        System.out.println("Cleaning Backend");
        wipeByteArray(User_AES_Key);
        wipeByteArray(Vault_Use_Key);
        wipeByteArray(Vault_KEY);
        System.out.println("Cleaned Key Arrays  [DONE]");
        wipeCredentialList(credentials, true, DEBUG);
    }

    // Wipe all credentials in the list - clears both plaintext and ciphertext fields
    protected static void wipeCredentialList(List<Credential> list, boolean EXIT, boolean DEBUG) {
        if (list == null) return;
        for (Credential c : list) {
            if (c != null) {
                c.wipe();
                c.close_wipe();
            }
        }
        if (EXIT) {System.out.println("Cleaned List Array  [DONE]");} else if (DEBUG){System.out.println("Cleaned List Array");}
        list.clear();
    }

    // ===== LOAD ARGON2 PARAMS FROM DB =====
    // Always use the parameters the vault was CREATED with - never hardcode at derive-time
    private int[] loadArgon2Params(Connection conn, String username) throws Exception {
        String sql = "SELECT argon2_iter, argon2_mem, argon2_para FROM users WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("User not found loading Argon2 params: " + username);
                }
                return new int[]{
                    rs.getInt("argon2_iter"),
                    rs.getInt("argon2_mem"),
                    rs.getInt("argon2_para")
                };
            }
        }
    }

    private byte[] loadUserSalt(Connection conn, String username) throws Exception {
        String sql = "SELECT salt FROM users WHERE user_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                // Guard - returning null salt would silently weaken Argon2
                if (!rs.next()) {
                    throw new IllegalStateException("User not found loading salt: " + username);
                }
                byte[] salt = rs.getBytes("salt");
                if (salt == null || salt.length < 16) {
                    throw new IllegalStateException("Invalid or missing salt for user: " + username);
                }
                return salt;
            }
        }
    }

    // ===== KEY DERIVATION =====
    // Argon2id - memory-hard, resistant to GPU and side-channel attacks
    // Produces raw bytes used directly as AES-256 key - no PBKDF2 involvement
    private byte[] deriveKey(char[] password, int[] params, String username,
                              byte[] u_salt, Connection conn) throws Exception {
        Argon2Advanced argon2 = (Argon2Advanced) Argon2Factory.createAdvanced(Argon2Types.ARGON2id);

        // rawHash() returns raw bytes - exactly what AES needs as input
        byte[] keyBytes = argon2.rawHash(params[0], params[1], params[2], password, u_salt);

        // AES-256 requires exactly 32 bytes
        if (keyBytes.length < 32) {
            throw new SecurityException("Derived key too short for AES-256");
        }
        return keyBytes;
    }

    private static byte[] generateVaultKey() throws Exception {
        // BouncyCastle SecureRandom - avoids JDK default provider variance
        SecureRandom random = SecureRandom.getInstance("DEFAULT", "BC");
        byte[] keyBytes = new byte[32]; // 256-bit key - fully wipeable byte[]
        random.nextBytes(keyBytes);
        return keyBytes;
    }

    // ===== ENCRYPT =====
    // AES-256-GCM: AES is the lock 🔒, GCM is the tamper seal 🧾
    // IV is random per-entry - same input never produces same ciphertext
    private static byte[] encryptData(char[] plaintext, byte[] iv, byte[] key) throws Exception {
        byte[] data = new String(plaintext).getBytes(StandardCharsets.UTF_8);
        return encryptData(data, iv, key);
    }

    // Overload - accepts byte[] directly for re-encryption during vault password change
    private static byte[] encryptData(byte[] data, byte[] iv, byte[] key) throws Exception {
        KeyParameter keyParam = new KeyParameter(key);
        GCMModeCipher cipher  = GCMBlockCipher.newInstance(AESEngine.newInstance());
        AEADParameters params = new AEADParameters(keyParam, GCM_TAG_LENGTH, iv);
        cipher.init(true, params); // true = encrypt

        byte[] encrypted = new byte[cipher.getOutputSize(data.length)];
        int len = cipher.processBytes(data, 0, data.length, encrypted, 0);
        cipher.doFinal(encrypted, len);

        wipeByteArray(data);
        Arrays.fill(keyParam.getKey(), (byte) 0);
        return encrypted;
    }

    // ===== DECRYPT (ON DEMAND ONLY) =====
    // Returns plaintext as char[] - caller must wipe after use
    // Three-arg version: explicit key passed in (for re-encryption, loading)
    protected char[] decryptData(byte[] encrypted_data, byte[] iv) throws Exception {
        return decryptData(encrypted_data, iv, Vault_Use_Key);
    }

    protected char[] decryptData(byte[] encrypted_data, byte[] iv, byte[] key) throws Exception {
        KeyParameter keyParam = new KeyParameter(key);
        GCMModeCipher cipher  = GCMBlockCipher.newInstance(AESEngine.newInstance());
        AEADParameters params = new AEADParameters(keyParam, GCM_TAG_LENGTH, iv);
        cipher.init(false, params); // false = decrypt

        byte[] decrypted = new byte[cipher.getOutputSize(encrypted_data.length)];
        int len = cipher.processBytes(encrypted_data, 0, encrypted_data.length, decrypted, 0);
        cipher.doFinal(decrypted, len);

        char[] plaintext = new String(decrypted, StandardCharsets.UTF_8).toCharArray();
        wipeByteArray(decrypted);
        Arrays.fill(keyParam.getKey(), (byte) 0);
        return plaintext;
    }

    // ===== LOAD ALL CREDENTIALS FROM DB =====
    // Decrypts tag and notes on load (shown in table/detail).
    // All other fields (data0..data8) kept encrypted - decrypted on demand only.
    protected List<Credential> loadAll(Connection conn) throws Exception {
        wipeCredentialList(credentials, false, DEBUG); // wipe previous list before replacing
        List<Credential> result = new ArrayList<>();

        String sql = "SELECT id, type, tag, data0, data1, data2, data3, data4, data5, data6, data7, data8, folderId, creationDate, revisionDate, iv FROM vault";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                Credential c = new Credential();
                c.id    = rs.getInt("id");
                c.iv    = rs.getBytes("iv");
                
                // Decrypt type and tag - needed for table display without on-demand
                c.type  = new String(decryptData(rs.getBytes("type"),   c.iv, Vault_Use_Key));
                c.tag   = decryptData(rs.getBytes("tag"),   c.iv, Vault_Use_Key);
                c.folderId = new String(decryptData(rs.getBytes("folderId"),   c.iv, Vault_Use_Key));
                c.creationDate  = new String(decryptData(rs.getBytes("creationDate"),   c.iv, Vault_Use_Key));
                c.revisionDate  = new String(decryptData(rs.getBytes("revisionDate"),   c.iv, Vault_Use_Key));
                
                // Store all data fields as encrypted bytes - not decrypted until needed
                for (int i = 0; i < Futhark.DATA_COLUMNS.length; i++) {
                    c.dataFields[i] = rs.getBytes(Futhark.DATA_COLUMNS[i]);
                }

                result.add(c);
            }
        }
        return result;
    }

    // ===== ADD ENTRY =====
    // Encrypts all fields and writes one row to the vault table.
    // dataFields array maps index 0→data0, 1→data1, ... 8→data8
    protected void addEntry(Connection conn, char[] tag, char[] type, char[][] dataFields, String dbType, String folderId) throws Exception {
        throw new UnsupportedOperationException("addEntry(6-arg) must be overridden in subclass: " + this.getClass().getName());
    }
    protected void addEntry(Connection conn, char[] tag, char[] type, char[][] dataFields, String dbType, String folderId, String creationDate, String revisionDate) throws Exception {
        byte[] iv = generateIV(); // single IV per row
        byte[] enc_type  = encryptData(type,  iv, Vault_Use_Key);
        byte[] enc_tag   = encryptData(tag,   iv, Vault_Use_Key);

        if (folderId == null || folderId.isBlank()) folderId = "2245"; //defaut
        if (creationDate == null || creationDate.isBlank() || creationDate.equals("2245")) creationDate = timeCheck_UTC_time();
        if (revisionDate == null || revisionDate.isBlank()) revisionDate = creationDate;

        if (DEBUG) System.out.println("Folder: " + folderId + " | Creation: " + creationDate + " | Revision: " + revisionDate);

        byte[] enc_fI   = encryptData(folderId.toCharArray(),   iv, Vault_Use_Key);
        byte[] enc_cd   = encryptData(creationDate.toCharArray(),   iv, Vault_Use_Key);
        byte[] enc_rd   = encryptData(revisionDate.toCharArray(),   iv, Vault_Use_Key);

        // Encrypt each data field - null slots write SQL NULL
        byte[][] enc_data = new byte[Futhark.DATA_COLUMNS.length][];
        for (int i = 0; i < dataFields.length && i < enc_data.length; i++) {
            if (dataFields[i] != null && dataFields[i].length > 0) {
                enc_data[i] = encryptData(dataFields[i], iv, Vault_Use_Key);
            }
        }            

        String sql = "INSERT INTO vault(type, tag, data0, data1, data2, data3, data4, data5, data6, data7, data8, creationDate, revisionDate, folderId, iv) " +
                     "VALUES ( ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBytes(1, enc_type);
            stmt.setBytes(2,  enc_tag);
            for (int i = 0; i < 8; i++) {
                stmt.setBytes(i + 3, enc_data[i]); // null → SQL NULL via JDBC
            }
            stmt.setBytes(12, enc_cd);
            stmt.setBytes(13, enc_rd);
            stmt.setBytes(14, enc_fI);
            stmt.setBytes(15, iv);
            stmt.executeUpdate();
            if (DEBUG) System.out.println("Data Saved.");
        }

        // Wipe all temporary sensitive arrays immediately after write
        wipeCharArray(tag);
        for (char[] d : dataFields) if (d != null) wipeCharArray(d);
        wipeByteArray(enc_tag);
        for (byte[] d : enc_data) wipeByteArray(d);
        wipeByteArray(iv);
    }
    
    protected void updateEntry(Connection conn, int id, char[] tag, char[][] dataFields, String folderId, char[] type, String creationDate) throws Exception {
        byte[] iv = generateIV();
        byte[] enc_type = encryptData(type, iv, Vault_Use_Key);
        byte[] enc_cD = encryptData(creationDate.toCharArray(), iv, Vault_Use_Key);
        byte[] enc_rD = encryptData(timeCheck_UTC_time().toCharArray(), iv, Vault_Use_Key);
        byte[] enc_fI = encryptData(folderId.toCharArray(), iv, Vault_Use_Key);
        byte[] enc_tag = encryptData(tag, iv, Vault_Use_Key);

        // Encrypt each data field - null slots write SQL NULL
        byte[][] enc_data = new byte[Futhark.DATA_COLUMNS.length][];
        for (int i = 0; i < dataFields.length && i < enc_data.length; i++) {
            if (dataFields[i] != null && dataFields[i].length > 0) {
                enc_data[i] = encryptData(dataFields[i], iv, Vault_Use_Key);
            }
        }

        String sql = "UPDATE vault SET tag=?, data0=?, data1=?, data2=?, data3=?, data4=?, data5=?, data6=?, data7=?, data8=?, type=?, creationDate=?, revisionDate=?, folderId=?, iv=? WHERE id=?";

        try (PreparedStatement update = conn.prepareStatement(sql)) {
            update.setBytes(1,  enc_tag);       // tag
            for (int i = 0; i < 9; i++) {
                update.setBytes(i + 2, enc_data[i]); // data0..data8 = indices 2..10
            }
            update.setBytes(11, enc_type);
            update.setBytes(12, enc_cD);
            update.setBytes(13, enc_rD);
            update.setBytes(14, enc_fI);
            update.setBytes(15, iv);
            update.setInt(16,   id);
            update.executeUpdate();
        }
        if (DEBUG) System.out.println("Data Saved.");
        // Wipe all temporary sensitive arrays immediately after write
        wipeCharArray(tag);
        for (char[] d : dataFields) if (d != null) wipeCharArray(d);
        wipeByteArray(enc_tag);
        for (byte[] d : enc_data) wipeByteArray(d);
        wipeByteArray(iv);
    }

    // ===== WRAP VAULT KEY =====
    // AESWrapPad wraps the vault key using the user's derived key
    // Wrapped key is stored in users table - unwrapped at login
    private byte[] wrapVaultKey(byte[] userKey) throws Exception {
        Cipher cipher = Cipher.getInstance("AESWrapPad");
        cipher.init(Cipher.WRAP_MODE, new SecretKeySpec(userKey, "AES"));
        return cipher.wrap(new SecretKeySpec(Vault_KEY, "AES"));
    }

// ##################################### USERS #######################################################################################
// ##################################### BLOB ########################################################################################
    protected void set_DB_LastLogin_User(Connection conn, String username) throws Exception {
        byte[] iv = Mimir.Pull_DB_IV_User_item(conn, username);
        byte[] enc_last_login = encryptData(timeCheck_UTC_time().toCharArray(), iv, Vault_Use_Key);
        

        String sql = "UPDATE users SET last_login=? WHERE user_id=?";

        try (PreparedStatement update = conn.prepareStatement(sql)) {
            update.setBytes(1,  enc_last_login);
            update.setString(2,   username);
            update.executeUpdate();
        }
        if (DEBUG) System.out.println("Login updated.");
        wipeByteArray(iv);
    }

    protected static void set_DB_Creation_User(Connection conn, String username) throws Exception {
        byte[] iv = generateIV();
        byte[] enc_created_at = encryptData(timeCheck_UTC_time().toCharArray(), iv, Vault_Use_Key);
        

        String sql = "UPDATE users SET created_at=?, iv=? WHERE user_id=?";

        try (PreparedStatement update = conn.prepareStatement(sql)) {
            update.setBytes(1,  enc_created_at);
            update.setBytes(2,  iv);
            update.setString(3,   username);
            update.executeUpdate();
        }
        if (DEBUG) System.out.println("Login updated.");
        wipeByteArray(iv);
        wipeByteArray(enc_created_at);
    }

    // ===== ADD USER =====
    // Creates a new user row with their own salt, derived key, and wrapped vault key
    protected void useraddEntry(Connection conn, String newUsername, char[] newPassword)
        throws Exception {
        VaultLevel = Mimir.Pull_DB_Text_Meta_item(conn, "vault_level");
        Argon2Profile.Profile profile =  Argon2Profile.profileSelector(VaultLevel);

        byte[] new_salt = new byte[SALT_SIZE];
        new SecureRandom().nextBytes(new_salt);

        int[] params = {profile.iterations(), profile.memoryKb(), profile.parallelism()};
        byte[] new_User_AES_Key = deriveKey(newPassword, params, newUsername, new_salt, conn);
        byte[] newWrappedKey    = wrapVaultKey(new_User_AES_Key);

        try (PreparedStatement insert = conn.prepareStatement(
            "INSERT INTO users(user_id, role, wrapped_vk, salt, argon2_iter, argon2_mem, argon2_para) " +
            "VALUES(?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, newUsername);
            insert.setString(2, "member");
            insert.setBytes(3,  newWrappedKey);
            insert.setBytes(4,  new_salt);
            insert.setInt(5,    profile.iterations());
            insert.setInt(6,    profile.memoryKb());
            insert.setInt(7,    profile.parallelism());
            insert.executeUpdate();
            if (DEBUG) System.out.println("Data Saved.");
        }
        set_DB_Creation_User(conn, newUsername);
        wipeByteArray(new_User_AES_Key);
        wipeByteArray(newWrappedKey);
        wipeCharArray(newPassword);
    }

    // ===== IV GENERATION =====
    // Initialization Vector - random per entry so identical inputs produce different ciphertext
    private static byte[] generateIV() {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    // ===== MEMORY WIPE UTILITIES =====
    // Zero out sensitive data to minimise window of exposure in JVM heap
    protected static void wipeCharArray(char[] data) {
        if (data != null) Arrays.fill(data, '\0');
    }

    protected static void wipeByteArray(byte[] data) {
        if (data != null) Arrays.fill(data, (byte) 0);
    }

    // ===== BUILD DATABASE =====
    // Creates all tables and inserts initial meta rows for a new vault
    protected void BuildDatabase(Connection conn, String username, String version,
                                  String type, String VaultLevel) throws Exception {
        Statement stmt = conn.createStatement();

        Argon2Profile.Profile profile =  Argon2Profile.profileSelector(VaultLevel);

        // ===== VAULT TABLE =====
        // Generic data0..data10 columns - field meaning defined by EntrySchema per type
        // Notes and tag are always present; data columns are type-specific
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS vault (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                type         BLOB,
                favorite     INTEGER NOT NULL DEFAULT 0 CHECK (favorite IN (0, 1)),
                folderId     BLOB,
                tag          BLOB,
                data0        BLOB,
                data1        BLOB,
                data2        BLOB,
                data3        BLOB,
                data4        BLOB,
                data5        BLOB,
                data6        BLOB,
                data7        BLOB,
                data8        BLOB,
                data9        BLOB,
                data10       BLOB,
                creationDate BLOB,
                revisionDate BLOB,
                iv           BLOB
            )
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS folders (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                folderId     BLOB,
                name         BLOB,
                desc         BLOB,
                color        INTEGER,
                iv           BLOB
            )
        """);

        stmt.execute("""
            CREATE TABLE IF NOT EXISTS server (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid         BLOB,
                address      BLOB,
                username     BLOB,
                passkey      BLOB,
                syncDate     BLOB,
                iv           BLOB
            )
        """);

        // ===== META TABLE =====
        // Key-value store for vault configuration (type[single|multi], vault_level, vault_salt, etc.)
        stmt.execute("""
            CREATE TABLE meta (
                key    TEXT PRIMARY KEY,
                Bvalue BLOB,
                Tvalue TEXT,
                iv     BLOB
            )
        """);

        // ===== USERS TABLE =====
        // Stores per-user salt, Argon2 parameters, and wrapped vault key
        stmt.execute("""
            CREATE TABLE users (
                user_id     TEXT PRIMARY KEY,
                role        TEXT,
                wrapped_vk  BLOB,
                salt        BLOB,
                argon2_iter INTEGER,
                argon2_mem  INTEGER,
                argon2_para INTEGER,
                created_at  BLOB,
                last_login  BLOB,
                forceReKey  TEXT,     
                iv          BLOB
            )
        """);

        // ===== INSERT META ROWS =====
        try (PreparedStatement insert = conn.prepareStatement(
            "INSERT INTO meta(key, Tvalue) VALUES(?, ?)")) {

            insert.setString(1, "version"); insert.setString(2, version); insert.addBatch();
            insert.setString(1, "type");    insert.setString(2, type);    insert.addBatch();
            insert.setString(1, "vault_level"); insert.setString(2, VaultLevel); insert.addBatch();

            // vk_status tracks whether the vault key has been generated and wrapped
            Vault_Status = "new";
            if (type.equals("m")) {
                VK_STATUS = "gen";
                insert.setString(1, "vk_status"); insert.setString(2, "gen"); insert.addBatch();
                insert.setString(1, "vault_status"); insert.setString(2, "new"); insert.addBatch();
            } else if (type.equals("s")) {
                insert.setString(1, "vk_status"); insert.setString(2, "na"); insert.addBatch();
                insert.setString(1, "vault_status"); insert.setString(2, "new"); insert.addBatch();
            }
            insert.executeBatch();
        }

        // ===== INSERT USER ROW =====
        user_salt = new byte[SALT_SIZE];
        new SecureRandom().nextBytes(user_salt);

        if (type.equals("m")) {
            try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO users(user_id, role, salt, argon2_iter, argon2_mem, argon2_para) " +
                "VALUES(?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, username);
                insert.setString(2, "admin");
                insert.setBytes(3,  user_salt);
                insert.setInt(4,    profile.iterations());
                insert.setInt(5,    profile.memoryKb());
                insert.setInt(6,    profile.parallelism());
                insert.executeUpdate();
            }
        } else if (type.equals("s")) {
            try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO users(user_id, salt, argon2_iter, argon2_mem, argon2_para) " +
                "VALUES(?, ?, ?, ?, ?)")) {
                insert.setString(1, username);
                insert.setBytes(2,  user_salt);
                insert.setInt(3,    profile.iterations());
                insert.setInt(4,    profile.memoryKb());
                insert.setInt(5,    profile.parallelism());
                insert.executeUpdate();
            }
        }
    }

    // ===== VAULT SALT HANDLING =====
    // Salt is random per vault - prevents rainbow table attacks across vaults
    protected byte[] getOrCreateVaultSalt(Connection conn) throws Exception {
        Statement stmt = conn.createStatement();
        stmt.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value BLOB)");

        PreparedStatement ps = conn.prepareStatement("SELECT Bvalue FROM meta WHERE key='vault_salt'");
        ResultSet rs = ps.executeQuery();
        if (rs.next()) {
            return rs.getBytes(1);
        }

        // Generate a new vault salt and persist it
        byte[] vault_salt = new byte[SALT_SIZE];
        new SecureRandom().nextBytes(vault_salt);

        try (PreparedStatement insert = conn.prepareStatement(
            "INSERT INTO meta(key, Bvalue) VALUES(?, ?)")) {
            insert.setString(1, "vault_salt");
            insert.setBytes(2,  vault_salt);
            insert.executeUpdate();
        }
        return vault_salt;
    }

    /**
    * Returns the current UTC timestamp in ISO-8601 format.
    * Example output: "2025-07-30T23:37:16.386Z"
    *
    * @return ISO-8601 UTC timestamp string with millisecond precision
    */
    public static String timeCheck_UTC_time() {
        // Instant.now() captures current UTC time; toString() formats to ISO-8601 with 'Z' suffix
        return Instant.now().toString();
    }
}
