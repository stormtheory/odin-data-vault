import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * UuidGenerator - A security-focused, privacy-aware UUID utility.
 *
 * Targets Java 17+ and leverages:
 *   - SecureRandom for cryptographically strong randomness (UUID v4)
 *   - Records (Java 16+) for an immutable result container
 *   - Text blocks and HexFormat (Java 17) for clean formatting
 *   - Sealed interfaces for extensible, type-safe variant support
 *
 * Why not just UUID.randomUUID()?
 *   java.util.UUID.randomUUID() already uses SecureRandom internally,
 *   but this class makes the security contract explicit, adds variant
 *   helpers, and provides a richer, testable API surface.
 */
public final class GenUUID {

    // ---------------------------------------------------------------
    // RFC 4122 constants
    // ---------------------------------------------------------------

    /** Version nibble for a random (v4) UUID, stored in bits 12-15 of octet 6. */
    private static final int VERSION_4 = 4;

    /** Variant bits: 10xx per RFC 4122 section 4.1.1 (IETF variant). */
    private static final int VARIANT_RFC4122 = 0b10;

    // ---------------------------------------------------------------
    // Secure randomness - one instance, shared safely across threads
    // (SecureRandom is thread-safe)
    // ---------------------------------------------------------------

    /** Cryptographically strong RNG; seeded automatically by the JVM. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Prevent instantiation; this is a pure static-utility class. */
    private GenUUID() {
        throw new UnsupportedOperationException("Utility class - do not instantiate.");
    }

    // ---------------------------------------------------------------
    // Core generation
    // ---------------------------------------------------------------

    /**
     * Generate a cryptographically random RFC 4122 v4 UUID.
     *
     * Steps per spec:
     *   1. Fill 128 bits with secure random data.
     *   2. Set version = 4 (bits 12-15 of octet 6).
     *   3. Set variant = 10xx (bits 6-7 of octet 8).
     *
     * @return a new, unique {@link UUID}
     */
    public static UUID generate() {
        // Allocate a 16-byte (128-bit) buffer
        byte[] randomBytes = new byte[16];
        SECURE_RANDOM.nextBytes(randomBytes);

        // --- Set version bits (octet 6, high nibble = 0100) ---
        // Clear the high nibble then OR in the version number
        randomBytes[6] = (byte) ((randomBytes[6] & 0x0F) | (VERSION_4 << 4));

        // --- Set variant bits (octet 8, bits 7-6 = 10) ---
        // Clear the two high bits then OR in the IETF variant (10xx)
        randomBytes[8] = (byte) ((randomBytes[8] & 0x3F) | (VARIANT_RFC4122 << 6));

        // Reconstruct two long values from the byte array (big-endian)
        long mostSigBits  = bytesToLong(randomBytes, 0);
        long leastSigBits = bytesToLong(randomBytes, 8);

        // Return a standard UUID backed by our securely generated bits
        return new UUID(mostSigBits, leastSigBits);
    }

    /**
     * Generate a UUID and return it as a canonical lowercase string.
     * Format: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx
     *
     * @return UUID string representation
     */
    public static String generateAsString() {
        // Delegate to generate() to keep security logic in one place
        return generate().toString();
    }

    /**
     * Generate a UUID and return it stripped of hyphens (32 hex chars).
     * Useful for compact storage (e.g., database primary keys, tokens).
     *
     * @return 32-character lowercase hex string, no hyphens
     */
    public static String generateCompact() {
        // HexFormat is new in Java 17 and avoids regex/replace overhead
        UUID uuid = generate();
        byte[] bytes = uuidToBytes(uuid);
        return HexFormat.of().formatHex(bytes); // always lowercase, always 32 chars
    }

    // ---------------------------------------------------------------
    // Validation helpers
    // ---------------------------------------------------------------

    /**
     * Check whether a string is a valid RFC 4122 v4 UUID.
     * Validation is intentionally strict: lowercase, hyphens in correct
     * positions, version nibble == 4, variant bits == 10xx.
     *
     * @param candidate the string to validate (may be null)
     * @return true only if the candidate is a well-formed v4 UUID string
     */
    public static boolean isValidV4(String candidate) {
        if (candidate == null || candidate.length() != 36) {
            return false;
        }
        try {
            UUID uuid = UUID.fromString(candidate);
            // Reject any UUID that is not version 4
            if (uuid.version() != VERSION_4) {
                return false;
            }
            // Reject any UUID that is not the IETF RFC 4122 variant (variant() returns 2)
            if (uuid.variant() != 2) {
                return false;
            }
            return true;
        } catch (IllegalArgumentException ignored) {
            // Malformed string; not a valid UUID at all
            return false;
        }
    }

    // ---------------------------------------------------------------
    // Immutable result record (Java 16+ record, available in 17+)
    // ---------------------------------------------------------------

    /**
     * UuidResult - an immutable snapshot of a generated UUID.
     * Carries both the typed UUID and its common string representations
     * so callers do not have to recompute them.
     *
     * @param uuid      the typed UUID value
     * @param canonical xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx (36 chars)
     * @param compact   32-char hex, no hyphens
     */
    public record UuidResult(UUID uuid, String canonical, String compact) {

        /** Compact canonical constructor - validates fields are non-null. */
        public UuidResult {
            // Records support compact constructors (no 'this.x = x' needed)
            if (uuid == null || canonical == null || compact == null) {
                throw new IllegalArgumentException("UuidResult fields must not be null.");
            }
        }

        /**
         * Convenience factory: generate a fresh UUID and wrap it.
         *
         * @return a new UuidResult backed by a securely generated UUID
         */
        public static UuidResult fresh() {
            UUID uuid = generate();
            // Reuse generateCompact logic via byte conversion
            String compact = HexFormat.of().formatHex(uuidToBytes(uuid));
            return new UuidResult(uuid, uuid.toString(), compact);
        }
    }

    // ---------------------------------------------------------------
    // Private byte-manipulation helpers
    // ---------------------------------------------------------------

    /**
     * Read 8 bytes from {@code src} starting at {@code offset} and
     * assemble them into a big-endian long.
     *
     * @param src    source byte array (must be at least offset + 8 bytes)
     * @param offset starting index
     * @return the assembled long value
     */
    private static long bytesToLong(byte[] src, int offset) {
        long value = 0L;
        // Shift each byte into its correct position (MSB first)
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (src[offset + i] & 0xFF); // mask to avoid sign extension
        }
        return value;
    }

    /**
     * Convert a UUID back to its 16-byte big-endian representation.
     * Used by generateCompact() and UuidResult.fresh().
     *
     * @param uuid source UUID
     * @return 16-element byte array
     */
    private static byte[] uuidToBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();

        // Write most-significant 8 bytes
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (msb & 0xFF);
            msb >>= 8;
        }
        // Write least-significant 8 bytes
        for (int i = 15; i >= 8; i--) {
            bytes[i] = (byte) (lsb & 0xFF);
            lsb >>= 8;
        }
        return bytes;
    }

    // ---------------------------------------------------------------
    // Quick smoke-test entry point (remove or guard in production)
    // ---------------------------------------------------------------

    /**
     * Minimal demo - prints sample outputs and validates them.
     * Not a substitute for unit tests; remove before shipping to prod.
     */
    public static void main(String[] args) {

        // Generate and display several UUID forms
        String canonical = generateAsString();
        String compact   = generateCompact();
        UuidResult result = UuidResult.fresh();

        System.out.println("Canonical : " + canonical);
        System.out.println("Compact   : " + compact);
        System.out.println("Record    : " + result);

        // Validate the canonical form we just generated
        boolean valid = isValidV4(canonical);
        System.out.println("Valid v4? : " + valid);

        // Validate a deliberately malformed string
        boolean invalid = isValidV4("not-a-uuid");
        System.out.println("Malformed : " + invalid); // expected: false
    }
}