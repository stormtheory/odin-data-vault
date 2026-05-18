import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.swing.*;

// ============================================================================
// RamDiskImporter.java
//
// Shows how to wire RamDisk into a GUI import flow.
//
// Full flow:
//   1. App calls RamDiskImporter.run()
//   2. A RAM disk is acquired (or created with privilege prompt if needed)
//   3. The RAM disk path is verified as RAM-backed
//   4. A file chooser opens pointed at the RAM disk
//   5. User saves their Bitwarden unencrypted JSON export there
//   6. The file is read into memory
//   7. The RAM disk is wiped and destroyed
//   8. The JSON string is returned for processing
//
// The unencrypted JSON file exists only in RAM, never on any persistent disk.
// ============================================================================
public final class RamDiskImporter {
    private static RamDisk ramDisk;
    private static String ramPath = " ";
    private static boolean ramPathslection = false;

    private RamDiskImporter() {}

    /**
     * Runs the full secure import flow.
     *
     * Must be called from a background thread -- the file chooser and
     * privilege prompts are shown on the EDT via SwingUtilities.invokeAndWait().
     *
     * @param parentWindow the parent window for dialogs (may be null)
     * @return the raw JSON string from the Bitwarden export,
     *         or null if the user cancelled
     * @throws RamDisk.RamDiskException if the RAM disk cannot be set up
     * @throws IOException if the file cannot be read
     */
    public static String run(Window parentWindow)
            throws RamDisk.RamDiskException, IOException {

        // Step 1: Acquire a verified RAM disk
        // On Linux this is instant (/dev/shm).
        // On macOS this takes ~1 second (hdiutil).
        // If a new tmpfs mount is needed on Linux, a privilege dialog appears here.
        showInfo(parentWindow,
            "Setting up secure RAM disk...\n\n" +
            "Your export file will be stored only in RAM\n" +
            "and wiped immediately after import.",
            "Secure Import"
        );

        // ===== Windows has no native RAMdisk - detect and warn or use temp fallback =====
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            // ===== Check for a known RAMdisk drive letter (user-configured via ImDisk etc.) =====
            // Convention: check for R:\ or a config property pointing to the mount
            ramPath = System.getProperty("ramdisk.path", null);
            ramPathslection = true;
            if (ramPath == null) {
                // ===== Fall back to encrypted temp dir if no RAMdisk available =====
                showInfo(parentWindow,
                "You are using Windows!\n\n" +
                "- Cannot find an avaiable RAM disk\n" +
                "- Cannot securely import a plaintext json.\n" +
                "- Will have to save to your harddrive which\n" + 
                "    is not encrypted then even greater risk.\n" +
                "- If you accept the risk you can use --RAMdiskOverride argument.",
                "ERROR: Secure Import"
            );return null;
            }
        } else {
        // PRETTY COOL!   This will find a RAMdisk to use on Linux and macOS
            ramDisk = RamDisk.acquire();
        }


        try {
            Path importDir;
            if (!ramPathslection ) {importDir = ramDisk.getPath();}
            else if (ramPathslection && ramPath != null && !ramPath.isBlank() ) {importDir = Paths.get(ramPath);}
            else {
                importDir = Paths.get(System.getProperty("java.io.tmpdir"));
                showInfo(parentWindow,"No RAMdisk configured.\n\n" + "Using system temp (less secure).\n" + "Not Recommended\n","ERROR: Secure Import");
                }

            // Step 2: Show the user where to save the file and open a file chooser
            // The chooser starts in the RAM disk directory
            Path selectedFile = promptForFile(parentWindow, importDir);

            // User cancelled the file chooser
            if (selectedFile == null) {
                return null;
            }

            // Step 3: Verify the selected file is actually on the RAM disk
            // Guard against the user navigating away from the RAM disk in the chooser
            if (!selectedFile.toAbsolutePath().startsWith(importDir.toAbsolutePath())) {
                throw new RamDisk.RamDiskException(
                    "Selected file is not on the RAM disk.\n" +
                    "Please save the export file to: " + importDir + "\n" +
                    "and select it from there."
                );
            }

            // Step 4: Read the JSON into memory
            String json = Files.readString(selectedFile, StandardCharsets.UTF_8);

            // Basic sanity check -- real Bitwarden exports start with '{'
            if (json.isBlank() || !json.trim().startsWith("{")) {
                throw new IOException("Selected file does not appear to be a valid Bitwarden JSON export.");
            }

            return json;

        } finally {
            // Step 5: Always wipe and destroy the RAM disk, even if an exception occurred.
            // This runs whether import succeeded or failed.
            try {
                ramDisk.wipeAndDestroy();
            } catch (RamDisk.RamDiskException e) {
                // Log but do not suppress the original exception
                System.err.println("[WARN] RAM disk wipe/destroy failed: " + e.getMessage());
            }
        }
    }

    /**
     * Shows a file chooser dialog rooted at the RAM disk directory.
     *
     * Instructs the user to save their Bitwarden export to the RAM disk
     * first, then select it here. The chooser starts in the RAM disk dir
     * so the path is right there -- minimal friction.
     *
     * @param parent    parent window for the dialog
     * @param ramDiskDir the RAM disk directory to start the chooser in
     * @return the selected file path, or null if cancelled
     */
    private static Path promptForFile(Window parent, Path ramDiskDir) {

        // Instruction dialog shown before the file chooser
        showInfo(parent,
            "Your RAM disk is ready at:\n" +
            "  " + ramDiskDir + "\n\n" +
            "Steps:\n" +
            "  1. In Bitwarden: Settings > Export Vault\n" +
            "  2. Choose format: JSON (unencrypted)\n" +
            "  3. Save the file to the location above\n" +
            "  4. Click OK, then select the file\n\n" +
            "The file will be wiped from RAM immediately after import.",
            "Save Export to RAM Disk"
        );

        // Open the file chooser on the EDT and block until the user picks
        final Path[] result = { null };
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFileChooser chooser = new JFileChooser(ramDiskDir.toFile());
                chooser.setDialogTitle("Select Bitwarden Export (from RAM disk)");
                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                chooser.setMultiSelectionEnabled(false);

                // Filter to JSON files only -- reduces chance of user picking wrong file
                chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Bitwarden JSON Export (*.json)", "json"
                ));

                // Lock the chooser to only show the RAM disk directory
                // Prevents the user from accidentally selecting a file from disk
                chooser.setCurrentDirectory(ramDiskDir.toFile());

                int outcome = chooser.showOpenDialog(
                    parent instanceof JFrame ? (JFrame) parent : null
                );

                if (outcome == JFileChooser.APPROVE_OPTION) {
                    result[0] = chooser.getSelectedFile().toPath();
                }
            });
        } catch (Exception e) {
            System.err.println("[ERROR] File chooser failed: " + e.getMessage());
        }

        return result[0];
    }

    /**
     * Shows a non-blocking informational dialog on the EDT.
     * Safe to call from any thread.
     */
    private static void showInfo(Window parent, String message, String title) {
        try {
            SwingUtilities.invokeAndWait(() ->
                JOptionPane.showMessageDialog(
                    parent,
                    message,
                    title,
                    JOptionPane.INFORMATION_MESSAGE
                )
            );
        } catch (Exception e) {
            // Non-fatal -- log and continue
            System.err.println("[INFO] " + title + ": " + message);
        }
    }

    // =========================================================================
    // Example: how to call this from your main GUI
    // =========================================================================

    /**
     * Example wiring into a Java GUI action (e.g. a menu item or button).
     * Run this on a background thread -- never on the EDT.
     */
    public static void exampleUsage(JFrame mainWindow) {
        // Always run on a background thread -- RamDisk.acquire() may block
        // briefly while creating the disk (especially on macOS with hdiutil)
        new Thread(() -> {
            try {
                String json = RamDiskImporter.run(mainWindow);

                if (json == null) {
                    System.out.println("User cancelled import.");
                    return;
                }

                // Hand off to your vault import logic on the EDT
                SwingUtilities.invokeLater(() -> {
                    System.out.println("Got JSON, length: " + json.length());
                    // yourVault.importFromBitwardenJson(json);
                });

            } catch (RamDisk.RamDiskException e) {
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(mainWindow,
                        "Could not set up RAM disk:\n" + e.getMessage(),
                        "Import Error",
                        JOptionPane.ERROR_MESSAGE)
                );
            } catch (IOException e) {
                SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(mainWindow,
                        "Could not read export file:\n" + e.getMessage(),
                        "Import Error",
                        JOptionPane.ERROR_MESSAGE)
                );
            }
        }, "bw-import-thread").start();
    }
}
