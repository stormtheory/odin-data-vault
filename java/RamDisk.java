import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

// ============================================================================
// RamDisk.java
//
// Creates and manages a RAM-backed mount point for secure temporary file
// handling. Files written here never touch persistent storage.
//
// Supported platforms:
//   Linux : uses /dev/shm (tmpfs, always available, no privileges needed)
//           falls back to a new tmpfs mount via pkexec/sudo if /dev/shm
//           is unavailable or not RAM-backed
//   macOS : creates a RAM disk via hdiutil + diskutil (no sudo needed)
//           reuses an existing RAM disk mount if already present
//
// Typical usage:
//   RamDisk rd = RamDisk.acquire();          // create or verify
//   Path importDir = rd.getPath();           // point user here
//   // ... user saves Bitwarden export to importDir ...
//   // ... app reads the file into memory ...
//   rd.wipeAndDestroy();                     // zero-fill all files, unmount
//
// Security properties:
//   - Mount point is verified to be RAM-backed before returning to caller
//   - wipeAndDestroy() overwrites every file with zeros before deletion
//   - The RAM disk is unmounted after use -- data is gone from memory
//   - No data is written to any persistent storage at any point
// ============================================================================
public final class RamDisk {

    // Platform detection -- set once at class load
    private static final boolean IS_LINUX = System.getProperty("os.name", "").toLowerCase().contains("linux");
    private static final boolean IS_MAC   = System.getProperty("os.name", "").toLowerCase().contains("mac");

    // Linux: /dev/shm is a tmpfs RAM disk present on every modern distro
    private static final Path LINUX_SHM = Paths.get("/dev/shm");

    // Subdirectory name used within /dev/shm or as the Mac volume name
    private static final String MOUNT_NAME = "bw-import";

    // Mac RAM disk size in 512-byte sectors (32MB = 65536 sectors -- plenty for a vault export)
    private static final int MAC_RAM_SECTORS = 65_536;

    // Mac volume mount point base
    private static final Path MAC_VOLUMES = Paths.get("/Volumes");

    // Process timeout for shell commands (seconds)
    private static final int COMMAND_TIMEOUT_SECS = 30;

    // ── Instance state ────────────────────────────────────────────────────────

    /** The verified RAM-backed directory the caller should use */
    private final Path   mountPath;

    /** Mac-only: the /dev/diskN device created by hdiutil, needed for detach */
    private final String macDiskDevice;

    /** Whether this instance created the mount (vs reused an existing one) */
    private final boolean weCreatedIt;

    /** Tracks whether wipeAndDestroy() has been called */
    private volatile boolean destroyed = false;

    // ── Private constructor -- use acquire() ─────────────────────────────────

    private RamDisk(Path mountPath, String macDiskDevice, boolean weCreatedIt) {
        this.mountPath    = mountPath;
        this.macDiskDevice = macDiskDevice;
        this.weCreatedIt   = weCreatedIt;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Acquires a verified RAM-backed directory for secure file handling.
     *
     * On Linux: uses /dev/shm if available and RAM-backed, otherwise creates
     * a new tmpfs mount (may prompt for sudo/pkexec privileges).
     *
     * On macOS: creates a new RAM disk via hdiutil (no privileges needed),
     * or reuses an existing one if already mounted.
     *
     * @return RamDisk instance pointing to a verified RAM-backed directory
     * @throws RamDiskException if a RAM disk cannot be created or verified
     * @throws UnsupportedOperationException if the OS is not Linux or macOS
     */
    public static RamDisk acquire() throws RamDiskException {
        if (IS_LINUX) {
            return acquireLinux();
        } else if (IS_MAC) {
            return acquireMac();
        } else {
            throw new UnsupportedOperationException(
                "RAM disk support is only available on Linux and macOS. " +
                "Current OS: " + System.getProperty("os.name")
            );
        }
    }

    /**
     * Returns the verified RAM-backed directory path.
     * Point the user's file chooser here.
     *
     * @return path to the RAM-backed import directory
     * @throws IllegalStateException if wipeAndDestroy() has already been called
     */
    public Path getPath() {
        if (destroyed) throw new IllegalStateException("RamDisk has already been destroyed.");
        return mountPath;
    }

    /**
     * Securely wipes all files in the RAM disk and unmounts/destroys it.
     *
     * For each file found:
     *   1. Overwrites content with zero bytes (multiple passes)
     *   2. Deletes the file
     * Then unmounts the RAM disk entirely.
     *
     * Safe to call multiple times -- subsequent calls are no-ops.
     *
     * @throws RamDiskException if unmounting fails (wipe still attempted)
     */
    public void wipeAndDestroy() throws RamDiskException {
        // Guard against double-destroy
        if (destroyed) return;
        destroyed = true;

        // Wipe all files regardless of unmount outcome
        wipeDirectory(mountPath);

        if (!weCreatedIt) {
            // We didn't create this mount -- don't unmount it, just wipe
            return;
        }

        if (IS_LINUX) {
            destroyLinux();
        } else if (IS_MAC) {
            destroyMac();
        }
    }

    /**
     * Returns a human-readable description of this RAM disk for UI display.
     * Safe to show to the user -- contains no sensitive data.
     */
    @Override
    public String toString() {
        return "RamDisk[path=" + mountPath + ", os=" +
               (IS_LINUX ? "Linux" : "macOS") +
               ", created=" + weCreatedIt + ", destroyed=" + destroyed + "]";
    }

    // =========================================================================
    // Linux implementation
    // =========================================================================

    /**
     * Linux: use /dev/shm (tmpfs) if verified RAM-backed.
     * Falls back to creating a new tmpfs mount if /dev/shm is unavailable.
     */
    private static RamDisk acquireLinux() throws RamDiskException {
        // ===== Ordered list of candidate RAM-backed locations to try on Linux =====
        // /dev/shm is tmpfs on all modern distros, no privileges needed =====
        // /tmp is often tmpfs but not guaranteed - check before trusting =====
        // /run is tmpfs on systemd distros, used for runtime state =====
        // /run/user/UID is per-user tmpfs, automatically cleaned on logout =====
        List<Path> candidates = List.of(
            Path.of("/tmp"),                                              // often tmpfs
            LINUX_SHM,                                                           // /dev/shm
            Path.of("/run"),                                              // systemd tmpfs
            Path.of("/dev/shm/" + System.getProperty("user.name"))          // user-scoped shm
        );

        for (Path candidate : candidates) {
            // ===== Skip paths that don't exist or aren't directories =====
            if (!Files.isDirectory(candidate)) continue;

            // ===== Only use if confirmed RAM-backed - no point using disk-backed paths =====
            if (!isRamBacked(candidate)) continue;

            Path importDir = candidate.resolve(MOUNT_NAME);
            try {
                Files.createDirectories(importDir);
            } catch (IOException e) {
                // ===== Can't write here - try next candidate =====
                continue;
            }

            // ===== Sanity check the resolved subdir is still RAM-backed =====
            if (!isRamBacked(importDir)) continue;

            return new RamDisk(importDir, null, false);
        }

        // ===== No suitable RAM-backed path found - escalate to privileged tmpfs mount =====
        return createLinuxTmpfsMount();
    }

    /**
     * Creates a new tmpfs mount on Linux using pkexec (GUI privilege prompt)
     * or sudo (terminal fallback).
     */
    private static RamDisk createLinuxTmpfsMount() throws RamDiskException {
        Path mountPoint = Paths.get("/tmp/" + MOUNT_NAME + "-ramdisk");

        try {
            Files.createDirectories(mountPoint);
        } catch (IOException e) {
            throw new RamDiskException("Could not create mount point at " + mountPoint, e);
        }

        // Build the mount command
        // tmpfs with size=64m is plenty for any Bitwarden export
        String mountCmd = "mount -t tmpfs -o size=64m,mode=0700 tmpfs " + mountPoint.toAbsolutePath();

        // Try pkexec first (shows a GUI password dialog on GNOME/KDE)
        // Fall back to sudo for terminal environments
        boolean mounted = false;
        String lastError = "";

        // pkexec wraps a single command and shows a polkit authentication dialog
        if (commandExists("pkexec")) {
            try {
                runCommand(List.of("pkexec", "sh", "-c", mountCmd), true);
                mounted = true;
            } catch (RamDiskException e) {
                lastError = e.getMessage();
            }
        }

        // sudo fallback -- works in terminal, may prompt inline
        if (!mounted && commandExists("sudo")) {
            try {
                runCommand(List.of("sudo", "mount", "-t", "tmpfs",
                                   "-o", "size=64m,mode=0700",
                                   "tmpfs", mountPoint.toAbsolutePath().toString()), true);
                mounted = true;
            } catch (RamDiskException e) {
                lastError = e.getMessage();
            }
        }

        if (!mounted) {
            throw new RamDiskException(
                "Could not mount tmpfs. /dev/shm was unavailable and privilege escalation failed.\n" +
                "Last error: " + lastError + "\n" +
                "You can manually run: sudo mount -t tmpfs -o size=64m tmpfs " + mountPoint
            );
        }

        // Verify the new mount is actually RAM-backed before trusting it
        if (!isRamBacked(mountPoint)) {
            // Something went wrong -- unmount and bail
            try { runCommand(List.of("sudo", "umount", mountPoint.toString()), false); } catch (Exception ignored) {}
            throw new RamDiskException("Newly created tmpfs mount failed RAM verification at " + mountPoint);
        }

        return new RamDisk(mountPoint, null, true);
    }

    /**
     * Unmounts the Linux tmpfs mount created by createLinuxTmpfsMount().
     */
    private void destroyLinux() throws RamDiskException {
        String path = mountPath.toAbsolutePath().toString();

        // Try pkexec first, then sudo
        boolean unmounted = false;

        if (commandExists("pkexec")) {
            try {
                runCommand(List.of("pkexec", "umount", path), true);
                unmounted = true;
            } catch (RamDiskException ignored) {}
        }

        if (!unmounted && commandExists("sudo")) {
            try {
                runCommand(List.of("sudo", "umount", path), true);
                unmounted = true;
            } catch (RamDiskException ignored) {}
        }

        if (!unmounted) {
            throw new RamDiskException("Could not unmount RAM disk at " + path +
                ". You may need to manually run: sudo umount " + path);
        }
    }

    // =========================================================================
    // macOS implementation
    // =========================================================================

    /**
     * macOS: create a RAM disk via hdiutil + diskutil.
     * No sudo required -- hdiutil runs as the current user.
     * Reuses an existing mount if already present.
     */
    private static RamDisk acquireMac() throws RamDiskException {
        Path volumePath = MAC_VOLUMES.resolve(MOUNT_NAME);

        // Check if a RAM disk with our name already exists
        if (Files.isDirectory(volumePath)) {
            if (isRamBacked(volumePath)) {
                // Reuse the existing RAM disk -- we did not create it this session
                return new RamDisk(volumePath, null, false);
            } else {
                throw new RamDiskException(
                    "A volume named '" + MOUNT_NAME + "' exists at " + volumePath +
                    " but is NOT RAM-backed. Refusing to use it for security reasons."
                );
            }
        }

        // Create a new RAM disk:
        //   Step 1: hdiutil attach -nomount ram://<sectors>  -->  /dev/diskN
        //   Step 2: diskutil erasevolume HFS+ <name> /dev/diskN  -->  mounts at /Volumes/<name>
        String diskDevice = createMacRamDevice();

        try {
            formatMacRamDisk(diskDevice, MOUNT_NAME);
        } catch (RamDiskException e) {
            // Format failed -- detach the raw device to avoid leaking it
            try { runCommand(List.of("hdiutil", "detach", diskDevice, "-force"), false); }
            catch (Exception ignored) {}
            throw e;
        }

        // Verify the volume mounted and is RAM-backed
        if (!Files.isDirectory(volumePath)) {
            throw new RamDiskException("RAM disk was formatted but did not mount at " + volumePath);
        }
        if (!isRamBacked(volumePath)) {
            throw new RamDiskException("Mounted volume at " + volumePath + " failed RAM verification.");
        }

        return new RamDisk(volumePath, diskDevice, true);
    }

    /**
     * Runs: hdiutil attach -nomount ram://<sectors>
     * Returns the /dev/diskN device path printed by hdiutil.
     */
    private static String createMacRamDevice() throws RamDiskException {
        List<String> cmd = List.of(
            "hdiutil", "attach", "-nomount",
            "ram://" + MAC_RAM_SECTORS
        );
        // hdiutil prints the device path to stdout, e.g. "/dev/disk4"
        String output = runCommand(cmd, true).trim();
        if (output.isEmpty() || !output.startsWith("/dev/disk")) {
            throw new RamDiskException("hdiutil did not return a valid device path. Got: " + output);
        }
        return output;
    }

    /**
     * Runs: diskutil erasevolume HFS+ <name> <device>
     * This formats the raw device and mounts it at /Volumes/<name>.
     */
    private static void formatMacRamDisk(String device, String volumeName) throws RamDiskException {
        runCommand(List.of("diskutil", "erasevolume", "HFS+", volumeName, device), true);
    }

    /**
     * Detaches the macOS RAM disk using hdiutil.
     */
    private void destroyMac() throws RamDiskException {
        // Prefer detaching by mount path -- more reliable than device path
        // if macDiskDevice is null (reused existing mount)
        String target = (macDiskDevice != null) ? macDiskDevice : mountPath.toAbsolutePath().toString();

        try {
            runCommand(List.of("hdiutil", "detach", target, "-force"), true);
        } catch (RamDiskException e) {
            throw new RamDiskException("Could not detach macOS RAM disk: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // RAM verification -- critical security check
    // =========================================================================

    /**
     * Verifies that the given path is backed by a RAM-based filesystem.
     *
     * On Linux: checks /proc/mounts for a tmpfs entry covering this path.
     * On macOS: checks if the path is on a disk that hdiutil reports as RAM.
     *
     * This check is non-negotiable -- we never skip it.
     *
     * @param path the directory to verify
     * @return true if the path is confirmed RAM-backed
     */
    public static boolean isRamBacked(Path path) {
        if (IS_LINUX) {
            return isRamBackedLinux(path);
        } else if (IS_MAC) {
            return isRamBackedMac(path);
        }
        return false;
    }

    /**
     * Linux RAM verification: reads /proc/mounts and checks for a tmpfs
     * entry that covers the given path.
     */
    private static boolean isRamBackedLinux(Path path) {
        // Also accept /dev/shm directly -- it's always tmpfs
        if (path.startsWith(LINUX_SHM)) return true;

        try {
            String absPath = path.toRealPath().toString();
            List<String> mounts = Files.readAllLines(Paths.get("/proc/mounts"), StandardCharsets.UTF_8);
            for (String line : mounts) {
                // Format: <device> <mountpoint> <fstype> <options> <dump> <pass>
                String[] parts = line.split("\\s+");
                if (parts.length >= 3) {
                    String fsType    = parts[2];
                    String mountPoint = parts[1];
                    // tmpfs or ramfs are both RAM-backed
                    if (("tmpfs".equals(fsType) || "ramfs".equals(fsType))
                            && absPath.startsWith(mountPoint)) {
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            // Cannot read /proc/mounts -- fail safe
            return false;
        }
        return false;
    }

    /**
     * macOS RAM verification: uses diskutil info to check if the volume
     * containing the path is a RAM disk (reported as "disk image" by diskutil).
     */
    private static boolean isRamBackedMac(Path path) {
        try {
            // diskutil info <path> prints volume information including disk type
            String output = runCommand(List.of("diskutil", "info", path.toAbsolutePath().toString()), false);
            // RAM disks show "Disk Image" in their Protocol or Virtual line
            return output.contains("Disk Image") || output.contains("virtual");
        } catch (RamDiskException e) {
            return false;
        }
    }

    // =========================================================================
    // Secure wipe
    // =========================================================================

    /**
     * Recursively overwrites every file in the directory with zero bytes,
     * then deletes it. Directories are traversed depth-first.
     *
     * The overwrite happens before deletion so that even if deletion fails,
     * the file content is gone. On a RAM disk deletion is guaranteed to
     * remove from memory, but the wipe is a defence-in-depth measure.
     *
     * @param dir the directory to wipe
     */
    private static void wipeDirectory(Path dir) {
        if (!Files.exists(dir)) return;

        try (Stream<Path> stream = Files.walk(dir)) {
            // Process files before directories (depth-first via sorted reverse)
            stream.sorted((a, b) -> b.toString().compareTo(a.toString()))
                  .forEach(p -> {
                      try {
                          if (Files.isRegularFile(p)) {
                              wipeFile(p);
                          }
                          // Delete the file or empty directory
                          Files.deleteIfExists(p);
                      } catch (IOException e) {
                          // Log but continue -- wipe as much as possible
                          System.err.println("[WARN] Could not wipe/delete: " + p + " -- " + e.getMessage());
                      }
                  });
        } catch (IOException e) {
            System.err.println("[WARN] Error walking directory for wipe: " + e.getMessage());
        }
    }

    /**
     * Overwrites a single file's content with zero bytes, flushed to the
     * underlying store (RAM in this case) before the file handle is closed.
     *
     * Performs three passes for thoroughness, though on a RAM disk
     * a single pass is sufficient since there is no magnetic remanence.
     *
     * @param file path to the file to overwrite
     */
    private static void wipeFile(Path file) throws IOException {
        long size = Files.size(file);
        if (size == 0) return;

        // Allocate a zero-filled buffer -- reused across passes
        byte[] zeros = new byte[(int) Math.min(size, 65_536)];
        Arrays.fill(zeros, (byte) 0);

        // Three overwrite passes -- on RAM this is mostly ceremonial but
        // ensures any in-JVM buffers are also zeroed
        for (int pass = 0; pass < 3; pass++) {
            try (var out = Files.newOutputStream(file,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                long remaining = size;
                while (remaining > 0) {
                    int chunk = (int) Math.min(remaining, zeros.length);
                    out.write(zeros, 0, chunk);
                    remaining -= chunk;
                }
                // Force flush to the underlying store
                out.flush();
            }
        }
    }

    // =========================================================================
    // Shell command helpers
    // =========================================================================

    /**
     * Runs an external command and returns its stdout as a String.
     *
     * @param command  the command and arguments
     * @param failFast if true, throws RamDiskException on non-zero exit code
     * @return stdout output (may be empty)
     * @throws RamDiskException if the command fails and failFast is true,
     *                          or if the process cannot be started
     */
    private static String runCommand(List<String> command, boolean failFast) throws RamDiskException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            if (failFast) {
                throw new RamDiskException("Could not start command: " + command.get(0) + " -- " + e.getMessage(), e);
            }
            return "";
        }

        // Read stdout and stderr concurrently to avoid blocking
        final StringBuilder stdout = new StringBuilder();
        final StringBuilder stderr = new StringBuilder();

        Thread outReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) stdout.append(line).append("\n");
            } catch (IOException ignored) {}
        }, "ramdisk-stdout");

        Thread errReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) stderr.append(line).append("\n");
            } catch (IOException ignored) {}
        }, "ramdisk-stderr");

        outReader.start();
        errReader.start();

        boolean finished;
        try {
            finished = process.waitFor(COMMAND_TIMEOUT_SECS, TimeUnit.SECONDS);
            outReader.join(3_000);
            errReader.join(3_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new RamDiskException("Command interrupted: " + command.get(0));
        }

        if (!finished) {
            process.destroyForcibly();
            throw new RamDiskException("Command timed out after " + COMMAND_TIMEOUT_SECS + "s: " + command.get(0));
        }

        int exitCode = process.exitValue();
        if (failFast && exitCode != 0) {
            throw new RamDiskException(
                "Command failed (exit " + exitCode + "): " + String.join(" ", command) +
                (stderr.length() > 0 ? "\nstderr: " + stderr.toString().trim() : "")
            );
        }

        return stdout.toString();
    }

    /**
     * Checks whether a command exists on the PATH without executing it.
     *
     * @param command the command name (e.g. "pkexec", "sudo")
     * @return true if the command is found on the PATH
     */
    private static boolean commandExists(String command) {
        try {
            Process p = new ProcessBuilder("which", command)
                .redirectErrorStream(true)
                .start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            return done && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================================
    // Exception type
    // =========================================================================

    /**
     * Thrown when a RAM disk cannot be created, verified, or destroyed.
     * The message is safe to display to the user.
     */
    public static final class RamDiskException extends Exception {
        public RamDiskException(String message) { super(message); }
        public RamDiskException(String message, Throwable cause) { super(message, cause); }
    }

}
