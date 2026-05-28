// Select a profile based on your threat model and hardware budget
public final class Argon2Profile {

    private Argon2Profile() {}

    // Common interface so profiles can be passed around polymorphically (magically)
    public interface Profile {
        int iterations();
        int memoryKb();
        int parallelism();
    }

    // --- OWASP 2023 minimum - suitable for low-risk, high-throughput scenarios ---
    public static final Profile MINIMUM = new Profile() {
        public int iterations()  { return 2;     }
        public int memoryKb()    { return 19456; } // 19 MB in KB
        public int parallelism() { return 1;     }
    };

    // --- Warden Default - the Warden recommends the default setting for most users. --- Recommended for Mobile with minimual RAM
    public static final Profile WARDEN = new Profile() {
        public int iterations()  { return 6;     }
        public int memoryKb()    { return 32768; } // 32 MB in KB
        public int parallelism() { return 4;     }
    };

    // --- RFC 9106 second recommended choice - balanced for most applications ---
    public static final Profile BALANCED = new Profile() {
        public int iterations()  { return 3;     }
        public int memoryKb()    { return 65536; } // 64 MB in KB
        public int parallelism() { return 4;     }
    };
    
    // --- RFC 9106 first recommended choice - high-value credential storage ---
    public static final Profile HIGH = new Profile() {
        public int iterations()  { return 4;       }
        public int memoryKb()    { return 1048576; } // 1 GB in KB
        public int parallelism() { return 4;       }
    };

    // --- Beyond RFC 9106 - exceeds vault-grade, secrets manager, recovery codes, or master-key derivation ---
    public static final Profile PARANOID = new Profile() {
        public int iterations()  { return 6;       }
        public int memoryKb()    { return 2097152; } // 2 GB -  RFC 9106 for vault-grade security
        public int parallelism() { return 8;       }
    };

    public static Profile profileSelector(String VaultLevel) {
        Profile profile = switch (VaultLevel) {
            case "MINIMUM"  -> Argon2Profile.MINIMUM;
            case "WARDEN"   -> Argon2Profile.WARDEN;
            case "BALANCED" -> Argon2Profile.BALANCED;
            case "HIGH"     -> Argon2Profile.HIGH;
            case "PARANOID" -> Argon2Profile.PARANOID;
            default -> throw new IllegalArgumentException("Unknown security profile: " + VaultLevel);
        };
        return profile;
    };
}