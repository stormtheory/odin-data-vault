import java.util.*;

/**
 * ===== ENTRY SCHEMA =====
 * Defines what each data1–data10 column means for every entry type.
 *
 * The vault table stores generic columns (data1..data10).  This class is the
 * single source of truth that maps those column names to human-readable labels
 * and marks which fields contain sensitive data (shown as **** in the table
 * and requiring the Show button to reveal).
 *
 * Adding a new entry type in the future = add one static block here.
 * Nothing else in the codebase needs to change for field labelling.
 *
 * Schema stored in the meta table as JSON under key "entry_schema" so the
 * mapping travels with the vault file.
 */
public class Futhark {

    // ===== FIELD DESCRIPTOR =====
    public static class Field {
        public final String columnName;  // e.g. "data1" - maps to actual DB column
        public final String label;       // human label shown in UI e.g. "Username"
        public final boolean editable;  // true = 
        public final boolean sensitive;  // true = show as ****, needs Show button
        public final boolean password;   // true = show as ****, needs Show button
        public final boolean multiline;  // true = use JTextArea in detail panel

        public Field(String columnName, String label,boolean editable, boolean sensitive, boolean password, boolean multiline) {
            this.editable   = editable;
            this.columnName = columnName;
            this.label      = label;
            this.sensitive  = sensitive;
            this.password   = password;
            this.multiline  = multiline;
        }

        /** Convenience - single-line non-sensitive field. */
        public Field(String columnName, String label, boolean editable) {
            this(columnName, label, editable, false);
        }

        /** Convenience - single-line, explicit sensitivity. */
        public Field(String columnName, String label, boolean editable, boolean sensitive) {
            this(columnName, label, editable, sensitive, false);
        }

        /** Convenience - single-line, explicit sensitivity. */
        public Field(String columnName, String label, boolean editable, boolean sensitive, boolean password) {
            this(columnName, label, editable, sensitive, password , false);
        }
    }

    // ===== TYPE DESCRIPTOR =====
    public static class EntryType {
        public final String typeKey;          // stored in DB vault.type column
        public final String displayName;      // shown in sidebar and dropdown
        public final String icon;             // emoji icon for sidebar and table
        public final List<Field> fields;      // ordered list of field descriptors
        // Which fields appear as summary columns in the main table (max 3 recommended)
        // These are column indices into the fields list (0-based)
        public final int[] tableColumnIndices;

        public EntryType(String typeKey, String displayName, String icon,
                         List<Field> fields, int[] tableColumnIndices) {
            this.typeKey           = typeKey;
            this.displayName       = displayName;
            this.icon              = icon;
            this.fields            = Collections.unmodifiableList(fields);
            this.tableColumnIndices = tableColumnIndices;
        }
    }

    // ===== REGISTERED TYPES =====
    // Ordered - this order controls sidebar display order
    private static final List<EntryType> ALL_TYPES = new ArrayList<>();
    private static final Map<String, EntryType> BY_KEY = new LinkedHashMap<>();

    static {
        // ===== ACCOUNT =====
        register(new EntryType(
            "account", "Accounts", "🔑",
            List.of(
                new Field("data0", "URL",  true),
                new Field("data1", "Username",  true),
                new Field("data2", "Password",  true,  true, true, false),
                new Field("data3", "Notes",  true,  true, false, true)
                
            ),
            new int[]{0,1,2}  // Which columns to show in table
        ));

        // ===== NOTES =====
        register(new EntryType(
            "note", "Notes", "📝",
            List.of(
                new Field("data0", "Subject",  true, false, false, false),
                new Field("data1", "Notes",  true,  true, false, true)
            ),
            new int[]{0,1}
        ));

        // ===== ADDRESS =====
        register(new EntryType(
            "address", "Addresses", "📍",
            List.of(
                new Field("data0", "Full Name",  true),
                new Field("data1", "Street",  true),
                new Field("data2", "City",  true),
                new Field("data3", "State / Province",  true),
                new Field("data4", "Postal Code",  true),
                new Field("data5", "Country",  true),
                new Field("data6", "Notes",  true,  false, false, true)
            ),
            new int[]{0, 1, 2, 3}  // Name, Street, City in table
        ));

        // ===== CREDIT / DEBIT CARD =====
        register(new EntryType(
            "card", "Cards", "💳",
            List.of(
                new Field("data0", "Cardholder Name",  true, true),
                new Field("data1", "Card Number",  true,  true, false, false),  // sensitive
                new Field("data2", "Expiry",  true, true),
                new Field("data3", "CVV",  true,          true, false, false),  // sensitive
                new Field("data4", "PIN",  true,           true, false, false),  // sensitive
                new Field("data5", "Bank / Issuer",  true, true),
                new Field("data6", "Notes",  true,  false, false, true)
            ),
            new int[]{0,1,2,3,5}  // Cardholder, Expiry, Issuer in table
        ));

        // ===== PASSKEY =====
        register(new EntryType(
            "passkey", "Passkeys", "🛡",
            List.of(
                new Field("data0", "Relying Party (Site)", true),
                new Field("data1", "Username / Handle", true),
                new Field("data2", "Credential ID", true),
                new Field("data3", "Private Key",  true,  true, false, true),  // sensitive
                new Field("data4", "Public Key",  true,   false, false, true),
                new Field("data5", "Algorithm", true),
                new Field("data6", "Notes",  true,  false, false, true)
            ),
            new int[]{0, 1, 2}  // Site, Username, Cred ID in table
        ));

        // ===== SSH KEY =====
        register(new EntryType(
            "ssh", "SSH Keys", "🖥",
            List.of(
                new Field("data0", "Hostname", true),
                new Field("data1", "Username", true),
                new Field("data2", "Private Key",  true,  true, false,  true),  // sensitive + multiline
                new Field("data3", "Public Key",  true,   false, false, true),  // multiline
                new Field("data4", "Passphrase",  true,   true),          // sensitive
                new Field("data5", "Key Type",  true,     false),          // e.g. ed25519, rsa4096
                new Field("data6", "Notes",  true,  false, false, true)
            ),
            new int[]{0, 1, 5}  // Hostname, Username, Key Type in table
        ));

        // ===== VPN KEY =====
        register(new EntryType(
            "vpn", "VPN Keys", "🔒",
            List.of(
                new Field("data0", "Hostname / Endpoint", true),
                new Field("data1", "Username", true),
                new Field("data2", "Password / PSK",  true, false, true),   // sensitive
                new Field("data3", "Config / Key",    true, true, false, true), // sensitive + multiline
                new Field("data4", "Protocol", true),                 // e.g. WireGuard, OpenVPN
                new Field("data5", "Port", true),
                new Field("data6", "Notes",  true,false, false, true)
            ),
            new int[]{0, 1, 4}
        ));

            // ===== BINARY KEY =====
        register(new EntryType(
            "binary", "Binary Keys", "🔒",
            List.of(
                new Field("data0", "System", true),
                new Field("data1", "Username", true),
                new Field("data2", "Base64",  false, true, false, false),
                new Field("data3", "Filename",  false, false, false, false),
                new Field("data4", "SHA256 File",  false, false, false, false),
                new Field("data5", "SHA256 Data",  false, false, false, false),
                new Field("data6", "Notes",  true, false, false, true)
            ),
            new int[]{0, 1, 2, 3, 4}
        ));

                    // ===== BINARY KEY =====
        register(new EntryType(
            "docs", "Docs/Pictures", "🔒",
            List.of(
                new Field("data0", "Base64",  false, true, false, false),
                new Field("data1", "Filename",  false, false, false, false),
                new Field("data2", "SHA256 File",  false, false, false, false),
                new Field("data3", "SHA256 Data",  false, false, false, false),
                new Field("data4", "Notes",  true, true, false, true)
            ),
            new int[]{0, 1, 2, 3}
        ));
    }

    // ===== REGISTRATION HELPER =====
    private static void register(EntryType type) {
        ALL_TYPES.add(type);
        BY_KEY.put(type.typeKey, type);
    }

    // ===== PUBLIC API =====

    /** All registered entry types in sidebar display order. */
    public static List<EntryType> allTypes() {
        return Collections.unmodifiableList(ALL_TYPES);
    }

    /** Look up a type by its DB key string.  Returns null if unknown. */
    public static EntryType forKey(String key) {
        if (key == null) return null;
        return BY_KEY.get(key.trim().toLowerCase());
    }

    /**
     * Returns the table-column header array for a given entry type.
     * Always: ["Type", "Tag"] then the type's tableColumnIndices fields.
     */
    public static String[] tableHeaders(EntryType type) {
        List<String> headers = new ArrayList<>(List.of("ID", "Type", "Tag"));
        if (type != null) {
            for (int idx : type.tableColumnIndices) {
                headers.add(type.fields.get(idx).label);
            }
        }
        //headers.add("Actions"); // always last
        return headers.toArray(new String[0]);
    }

    /**
     * Returns the "all types" table headers - generic column names used when
     * displaying a mixed view.
     */
    public static String[] genericTableHeaders() {
        return new String[]{"ID", "Type", "Tag", "Field 1", "Field 2"};
    }

    /**
     * For a given entry type, returns which field indices are sensitive.
     * Used by the detail panel to know which fields need Show buttons.
     */
    public static List<Integer> sensitiveFieldIndices(EntryType type) {
         if (type == null) return List.of();
         List<Integer> result = new ArrayList<>();
         for (int i = 0; i < type.fields.size(); i++) {
             if (type.fields.get(i).sensitive) { result.add(i);} else if (type.fields.get(i).password) {result.add(i);}// else if (type.fields.get(i).multiline) {result.add(i);}
         }
         return result;
     }

    /**
     * Column names in the vault table for data fields (fixed order).
     * Used when building SELECT and INSERT SQL statements.
     */
    public static final String[] DATA_COLUMNS =
        {"data0", "data1", "data2", "data3", "data4", "data5", "data6", "data7", "data8"};
}
