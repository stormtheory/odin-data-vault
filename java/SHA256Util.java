import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;

/**
 * SHA256Util - Comprehensive SHA-256 hashing utility.
 *
 * Covers the most common use cases:
 *   1. Hash a String
 *   2. Hash a byte array (e.g. already-encrypted data, images)
 *   3. Hash a file via streaming (no OOM risk on large files)
 *   4. HMAC-SHA-256 (keyed hash for message authentication)
 *   5. Verify a hash (constant-time comparison to prevent timing attacks)
 *
 * All methods return lowercase hex strings unless noted.
 * No third-party dependencies; uses java.security only.
 *
 * Requires: Java 17+
 */
public class SHA256Util {

    // Algorithm constants; centralised so a future migration to SHA-3 is one change
    private static final String HASH_ALGO = "SHA-256";
    private static final String HMAC_ALGO = "HmacSHA256";

    // Chunk size for streaming file hashes; 8 KB avoids loading large files into memory
    private static final int BUFFER_SIZE = 8 * 1024;

    // HexFormat for converting byte[] digest to hex string; uppercase optional
    private static final HexFormat HEX = HexFormat.of();

    // -------------------------------------------------------------------------
    // 1. Hash a String
    // -------------------------------------------------------------------------

    /**
     * Hash a UTF-8 string with SHA-256.
     *
     * Always use an explicit charset (UTF-8 here) to ensure identical output
     * across JVMs and operating systems regardless of platform default encoding.
     *
     * @param input plaintext string to hash
     * @return 64-char lowercase hex digest
     */
    public static String hashString(String input) {
        if (input == null) throw new IllegalArgumentException("Input must not be null");
        return hashBytes(input.getBytes(StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------------------
    // 2. Hash a byte array
    // -------------------------------------------------------------------------

    /**
     * Hash a raw byte array with SHA-256.
     *
     * Use this for already-binary data: encrypted blobs, image bytes, etc.
     * MessageDigest is not thread-safe; always get a fresh instance.
     *
     * @param data byte array to hash
     * @return 64-char lowercase hex digest
     */
    public static String hashBytes(byte[] data) {
        if (data == null) throw new IllegalArgumentException("Data must not be null");
        try {
            // MessageDigest.getInstance is documented to never throw for SHA-256 on any JVM
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGO);
            byte[] hash = digest.digest(data);
            return HEX.formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java SE spec; this should never happen
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    }

    // -------------------------------------------------------------------------
    // 3. Hash a file (streaming, safe for large files)
    // -------------------------------------------------------------------------

    /**
     * Hash a file with SHA-256 using a streaming read.
     *
     * Reads the file in BUFFER_SIZE chunks and feeds each chunk to the digest.
     * This keeps memory usage constant at ~BUFFER_SIZE regardless of file size,
     * making it safe for multi-GB files (photos, videos, encrypted archives).
     *
     * @param filePath path to the file to hash
     * @return 64-char lowercase hex digest
     * @throws IOException if the file cannot be read
     */
    public static String hashFile(Path filePath) throws IOException {
        if (filePath == null) throw new IllegalArgumentException("File path must not be null");

        // Resolve to a real path to block symlink traversal
        Path realPath = filePath.toRealPath();

        if (!Files.isRegularFile(realPath)) {
            throw new IllegalArgumentException("Not a regular file: " + realPath);
        }

        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGO);
            byte[] buffer        = new byte[BUFFER_SIZE];

            try (InputStream in = new BufferedInputStream(Files.newInputStream(realPath))) {
                int bytesRead;

                // Feed each chunk into the digest without accumulating in memory
                while ((bytesRead = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            // Finalise the digest only after all bytes have been processed
            return HEX.formatHex(digest.digest());

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    }

    // -------------------------------------------------------------------------
    // 4. HMAC-SHA-256 (message authentication code)
    // -------------------------------------------------------------------------

    /**
     * Compute an HMAC-SHA-256 of a message using a secret key.
     *
     * HMAC adds a secret key to the hash, making it unforgeable without the key.
     * Use this for:
     *   - API request signing
     *   - Webhook payload verification
     *   - Secure cookie integrity checks
     *
     * Never use a plain SHA-256 hash for authentication; HMAC is the right tool.
     *
     * @param message   the data to authenticate (UTF-8 encoded)
     * @param secretKey the secret key bytes (use a cryptographically random key)
     * @return 64-char lowercase hex HMAC digest
     */
    public static String hmac(String message, byte[] secretKey) {
        if (message   == null) throw new IllegalArgumentException("Message must not be null");
        if (secretKey == null || secretKey.length == 0)
            throw new IllegalArgumentException("Secret key must not be null or empty");

        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);

            // Wrap the raw key bytes in a SecretKeySpec for use with Mac
            SecretKeySpec keySpec = new SecretKeySpec(secretKey, HMAC_ALGO);
            mac.init(keySpec);

            byte[] hmacBytes = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hmacBytes);

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA-256 failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // 5. Constant-time hash verification
    // -------------------------------------------------------------------------

    /**
     * Verify a SHA-256 hash in constant time.
     *
     * NEVER use String.equals() or Arrays.equals() to compare hashes in security
     * contexts.  Those methods short-circuit on the first differing byte, leaking
     * timing information that an attacker can exploit to brute-force a hash.
     *
     * MessageDigest.isEqual() uses a constant-time XOR comparison so every call
     * takes the same amount of time regardless of where the strings differ.
     *
     * @param expectedHex  the trusted hex digest (e.g. stored in DB)
     * @param actualHex    the computed hex digest to verify
     * @return true if the digests match
     */
    public static boolean verifyHash(String expectedHex, String actualHex) {
        if (expectedHex == null || actualHex == null) return false;

        // Convert hex strings back to raw bytes for constant-time comparison
        byte[] expected = HexFormat.of().parseHex(expectedHex);
        byte[] actual   = HexFormat.of().parseHex(actualHex);

        // MessageDigest.isEqual is guaranteed constant-time by the Java spec
        return MessageDigest.isEqual(expected, actual);
    }

    // -------------------------------------------------------------------------
    // Demo main: exercises all five methods
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {

        // 1. Hash a string
        String msg    = "Hello, passkey world!";
        String strHash = hashString(msg);
        System.out.println("String hash : " + strHash);

        // 2. Hash raw bytes (simulate an encrypted blob)
        byte[] blob      = { 0x00, 0x1F, 0x4A, (byte)0xFF, 0x33 };
        String byteHash  = hashBytes(blob);
        System.out.println("Bytes hash  : " + byteHash);

        // 3. Hash a file (create a temp file for the demo)
        Path tmp = Files.createTempFile("sha256demo", ".bin");
        Files.write(tmp, "Sample file content for hashing".getBytes(StandardCharsets.UTF_8));
        String fileHash = hashFile(tmp);
        System.out.println("File hash   : " + fileHash);
        Files.deleteIfExists(tmp);

        // 4. HMAC with a demo key (in production use SecureRandom to generate this)
        byte[] key       = "super-secret-key-32-bytes-long!!".getBytes(StandardCharsets.UTF_8);
        String hmacResult = hmac(msg, key);
        System.out.println("HMAC-SHA256 : " + hmacResult);

        // 5. Constant-time verification
        String recomputed = hashString(msg);
        boolean valid     = verifyHash(strHash, recomputed);
        System.out.println("Hash valid  : " + valid);

        // 5b. Tampered hash should fail
        String tampered   = "0000" + strHash.substring(4);
        boolean invalid   = verifyHash(strHash, tampered);
        System.out.println("Tampered    : " + invalid);
    }
}
