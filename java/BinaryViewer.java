import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.List;

// ============================================================================
// BinaryViewer.java
//
// Secure viewer for binary vault entries (images, PDFs, documents).
//
// Security model:
//   - Images:  decoded bytes stay in JVM heap as BufferedImage, never touch disk
//   - PDFs:    written to RamDisk, rendered to List<BufferedImage>, wiped immediately
//   - Unknown: metadata display only, export via RamDisk write then user copy
//   - All RamDisk temp files wiped via wipeAndDestroy() on dialog close
//   - Shutdown hook registered once at class load as belt-and-suspenders wipe
//
// Dependencies:
//   - Apache PDFBox 3.0.2 (PDF rendering only)
//   - RamDisk.java (temp file handling)
//
// Usage:
//   DetectedType type = BinaryViewer.detect(firstBytes, filename);
//   ImageIcon    thumb = BinaryViewer.thumbnail(decoded, 120, 80);
//   BinaryViewer.showViewerDialog(parent, decoded, type, filename);
//   BinaryViewer.exportFile(parent, decoded, filename);
// ============================================================================
public final class BinaryViewer {
    protected static boolean readyToWipe = false; 

    // ===== Active RamDisk instances - tracked for shutdown hook wipe =====
    private static final List<RamDisk> activeRamDisks = Collections.synchronizedList(new ArrayList<>());

    // ===== Shutdown hook registered once at class load =====
    // Belt-and-suspenders: wipes all RAM disks if JVM exits unexpectedly
    // static {
    //     Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    //         if (readyToWipe){
    //         synchronized (activeRamDisks) {
    //             for (RamDisk rd : activeRamDisks) {
    //                 try { rd.wipeAndDestroy(); }
    //                 catch (Exception e) {
    //                     System.err.println("[BinaryViewer] Shutdown wipe failed: " + e.getMessage());
    //                 }
    //             }
    //             activeRamDisks.clear();
    //         System.out.println("[BinaryViewer] wipeAll done.");
    //     }}
    //     }, "binary-viewer-shutdown-wipe"));
    // }

    // ===== Magic byte signatures for file type detection =====
    // Always check magic bytes first; filename extension is fallback only
    private static final byte[] MAGIC_PDF  = { 0x25, 0x50, 0x44, 0x46 };           // %PDF
    private static final byte[] MAGIC_PNG  = { (byte)0x89, 0x50, 0x4E, 0x47 };     // .PNG
    private static final byte[] MAGIC_JPG  = { (byte)0xFF, (byte)0xD8, (byte)0xFF };// JPEG SOI
    private static final byte[] MAGIC_GIF  = { 0x47, 0x49, 0x46, 0x38 };           // GIF8
    private static final byte[] MAGIC_BMP  = { 0x42, 0x4D };                        // BM
    private static final byte[] MAGIC_ZIP  = { 0x50, 0x4B, 0x03, 0x04 };           // PK (DOCX/XLSX)
    private static final byte[] MAGIC_WEBP = { 0x52, 0x49, 0x46, 0x46 };           // RIFF (check +8 for WEBP)

    // ===== Thumbnail dimensions for the detail panel =====
    private static final int THUMB_W = 120;
    private static final int THUMB_H = 80;

    // ===== PDF render DPI - 96 matches screen density without excess memory =====
    private static final float PDF_DPI = 96f;

    // =========================================================================
    // Public enum: file type categories
    // =========================================================================

    public enum DetectedType {
        IMAGE_PNG, IMAGE_JPG, IMAGE_GIF, IMAGE_BMP, IMAGE_WEBP,
        PDF,
        TEXT,
        DOCX, XLSX,
        UNKNOWN;

        /** Returns true for any image variant */
        public boolean isImage() {
            return this == IMAGE_PNG || this == IMAGE_JPG ||
                   this == IMAGE_GIF || this == IMAGE_BMP || this == IMAGE_WEBP;
        }
    }

    // =========================================================================
    // Detection
    // =========================================================================

    /**
     * Detects the file type by inspecting magic bytes first, then falling
     * back to the filename extension. Never trusts extension alone.
     *
     * @param firstBytes first 16+ bytes of the decoded file content
     * @param filename   original filename from the vault Filename field
     * @return detected type, never null
     */
    public static DetectedType detect(byte[] firstBytes, String filename) {
        readyToWipe = true;
        if (firstBytes != null && firstBytes.length >= 4) {

            // ===== Magic byte checks in order of specificity =====
            if (startsWith(firstBytes, MAGIC_PDF))  return DetectedType.PDF;
            if (startsWith(firstBytes, MAGIC_PNG))  return DetectedType.IMAGE_PNG;
            if (startsWith(firstBytes, MAGIC_JPG))  return DetectedType.IMAGE_JPG;
            if (startsWith(firstBytes, MAGIC_GIF))  return DetectedType.IMAGE_GIF;
            if (startsWith(firstBytes, MAGIC_BMP))  return DetectedType.IMAGE_BMP;

            // ===== WEBP: RIFF header + "WEBP" at offset 8 =====
            if (startsWith(firstBytes, MAGIC_WEBP) && firstBytes.length >= 12) {
                if (firstBytes[8] == 0x57 && firstBytes[9] == 0x45 &&
                    firstBytes[10] == 0x42 && firstBytes[11] == 0x50) {
                    return DetectedType.IMAGE_WEBP;
                }
            }

            // ===== ZIP-based: DOCX and XLSX share PK magic bytes =====
            // Use extension to disambiguate after confirming ZIP signature
            if (startsWith(firstBytes, MAGIC_ZIP)) {
                String ext = extension(filename);
                if (ext.equals("xlsx") || ext.equals("xls")) return DetectedType.XLSX;
                if (ext.equals("docx") || ext.equals("doc")) return DetectedType.DOCX;
                // Unknown ZIP-based format
                return DetectedType.UNKNOWN;
            }
        }

        // ===== Fallback: extension only =====
        String ext = extension(filename);
        return switch (ext) {
            case "pdf"          -> DetectedType.PDF;
            case "png"          -> DetectedType.IMAGE_PNG;
            case "jpg", "jpeg"  -> DetectedType.IMAGE_JPG;
            case "gif"          -> DetectedType.IMAGE_GIF;
            case "bmp"          -> DetectedType.IMAGE_BMP;
            case "webp"         -> DetectedType.IMAGE_WEBP;
            case "txt", "md",
                 "csv", "log"   -> DetectedType.TEXT;
            case "docx", "doc"  -> DetectedType.DOCX;
            case "xlsx", "xls"  -> DetectedType.XLSX;
            default             -> DetectedType.UNKNOWN;
        };
    }

    // =========================================================================
    // Thumbnail
    // =========================================================================

    /**
     * Generates a thumbnail ImageIcon for display in the detail panel.
     *
     * Images: decoded in memory, scaled, original bytes wiped after.
     * PDF:    first page rendered via PDFBox, temp file wiped immediately.
     * Other:  returns null (caller should show file type icon instead).
     *
     * @param decoded  raw decoded file bytes (not Base64, already decoded)
     * @param type     detected file type
     * @return         scaled ImageIcon or null if thumbnailing not supported
     */
    public static ImageIcon thumbnail(byte[] decoded, DetectedType type) {
        if (decoded == null || decoded.length == 0) return null;

        try {
            if (type.isImage()) {
                // ===== Image thumbnail: stays entirely in JVM heap =====
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(decoded));
                if (img == null) return null;
                return new ImageIcon(scaleImage(img, THUMB_W, THUMB_H));
            }

            if (type == DetectedType.PDF) {
                // ===== PDF thumbnail: write to RamDisk, render page 0, wipe immediately =====
                return pdfThumbnail(decoded);
            }

        } catch (Exception e) {
            System.err.println("[BinaryViewer] Thumbnail failed: " + e.getMessage());
        }
        return null;
    }

    // =========================================================================
    // Viewer dialog
    // =========================================================================

    /**
     * Opens a secure viewer dialog for the given file content.
     *
     * Images: rendered fully in JVM heap, no disk I/O.
     * PDFs:   rendered page-by-page via PDFBox, stacked vertically in scroll pane.
     *         RamDisk temp file wiped immediately after all pages are rendered.
     * Text:   displayed in a JTextArea.
     * Other:  metadata display with Export button only.
     *
     * @param parent   parent frame for dialog centering
     * @param decoded  raw decoded bytes
     * @param type     detected file type
     * @param filename original filename for dialog title and export default name
     */
    public static void showViewerDialog(JFrame parent, byte[] decoded,DetectedType type, String filename) {
        readyToWipe = true;
        JDialog dialog = new JDialog(parent, filename, true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setSize(760, 820);
        dialog.setMinimumSize(new Dimension(520, 400));
        dialog.setLocationRelativeTo(parent);

        // ===== Root panel: dark background matching vault theme =====
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(ThemeManager.BG);

        // ===== Build content area based on type =====
        JComponent contentArea = buildContentArea(decoded, type, filename, dialog);
        root.add(contentArea, BorderLayout.CENTER);

        // ===== Bottom toolbar: Export + Close =====
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        toolbar.setBackground(ThemeManager.SURFACE);
        toolbar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ThemeManager.BORDER));

        JButton exportBtn = new JButton("Export File");
        JButton closeBtn  = new JButton("Close");
        ThemeManager.styleSurfaceButton(exportBtn);
        ThemeManager.styleSurfaceButton(closeBtn);

        // ===== Export: write to RamDisk first, then user picks destination =====
        exportBtn.addActionListener(e -> exportFile(parent, decoded, filename));

        // ===== Close: dispose dialog, decoded bytes caller is responsible for wiping =====
        closeBtn.addActionListener(e -> dialog.dispose());

        toolbar.add(exportBtn);
        toolbar.add(closeBtn);
        root.add(toolbar, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.setVisible(true);
    }

    // =========================================================================
    // Export
    // =========================================================================

    /**
     * Exports decoded bytes to a user-chosen location via JFileChooser.
     *
     * Security flow:
     *   1. Write decoded bytes to RamDisk temp file (owner-only permissions)
     *   2. User picks destination via JFileChooser
     *   3. Copy from RamDisk to destination
     *   4. Wipe and destroy RamDisk immediately after copy
     *   5. Wipe decoded byte[] after write
     *
     * @param parent   parent frame for dialog centering
     * @param decoded  raw decoded bytes to export
     * @param filename suggested filename for the save dialog
     */
    public static void exportFile(JFrame parent, byte[] decoded, String filename) {
        RamDisk ramDisk = null;
        Path    tmpFile = null;

        try {
            // ===== Acquire a verified RAM-backed temp location =====
            ramDisk = RamDisk.acquire();
            activeRamDisks.add(ramDisk);

            // ===== Write to RamDisk with owner-only permissions =====
            tmpFile = ramDisk.getPath().resolve(filename);
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            Files.createFile(tmpFile, PosixFilePermissions.asFileAttribute(ownerOnly));
            Files.write(tmpFile, decoded, StandardOpenOption.WRITE);

            // ===== User picks export destination =====
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new java.io.File(filename));
            fc.setDialogTitle("Export File");
            int result = fc.showSaveDialog(parent);

            if (result == JFileChooser.APPROVE_OPTION) {
                Path dest = fc.getSelectedFile().toPath();
                // ===== Copy from RamDisk to user destination =====
                Files.copy(tmpFile, dest, StandardCopyOption.REPLACE_EXISTING);
                JOptionPane.showMessageDialog(parent,
                    "Exported to: " + dest,
                    "Export Complete", JOptionPane.INFORMATION_MESSAGE);
            }

        } catch (RamDisk.RamDiskException e) {
            JOptionPane.showMessageDialog(parent,
                "RAM disk unavailable: " + e.getMessage(),
                "Export Failed", JOptionPane.ERROR_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent,
                "Export failed: " + e.getMessage(),
                "Export Failed", JOptionPane.ERROR_MESSAGE);
        } finally {
            // ===== Always wipe RamDisk regardless of outcome =====
            if (ramDisk != null) {
                try {
                    ramDisk.wipeAndDestroy();
                    activeRamDisks.remove(ramDisk);
                } catch (RamDisk.RamDiskException e) {
                    System.err.println("[BinaryViewer] RamDisk wipe failed after export: " + e.getMessage());
                }
            }
            // ===== Wipe decoded bytes from heap after write =====
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
        }
    }

    /**
     * Wipes all active RamDisk instances.
     * Called by idle timeout manager and on vault lock.
     */
    public static void wipeAll() {
        if (readyToWipe){
        synchronized (activeRamDisks) {
            for (RamDisk rd : activeRamDisks) {
                try { rd.wipeAndDestroy(); }
                catch (Exception e) {
                    System.err.println("[BinaryViewer] wipeAll failed: " + e.getMessage());
                }
            }
            activeRamDisks.clear();
        }
        System.out.println("[BinaryViewer] wipeAll done.");
    }}

    // =========================================================================
    // Private: content area builder
    // =========================================================================

    /**
     * Builds the main content area of the viewer dialog based on file type.
     * Images and text stay entirely in JVM heap.
     * PDFs are rendered via PDFBox; temp file wiped before this method returns.
     */
    private static JComponent buildContentArea(byte[] decoded, DetectedType type,
                                                String filename, JDialog dialog) {
        // ===== IMAGE =====
        if (type.isImage()) {
            return buildImageViewer(decoded);
        }

        // ===== PDF =====
        if (type == DetectedType.PDF) {
            return buildPdfViewer(decoded);
        }

        // ===== TEXT =====
        if (type == DetectedType.TEXT) {
            return buildTextViewer(decoded);
        }

        // ===== DOCX / XLSX / UNKNOWN: metadata only =====
        return buildMetadataPanel(decoded, filename, type);
    }

    // =========================================================================
    // Private: image viewer
    // =========================================================================

    /**
     * Renders an image scaled to fit the panel, no disk I/O.
     * Uses a custom JPanel that rescales on resize for pixel-perfect display.
     */
    private static JComponent buildImageViewer(byte[] decoded) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(decoded));
        } catch (IOException e) {
            return errorLabel("Failed to decode image: " + e.getMessage());
        }
        if (img == null) return errorLabel("Unsupported image format.");

        // ===== Custom panel: scales image to fit on every resize event =====
        BufferedImage finalImg = img;
        JPanel imagePanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (finalImg == null) return;

                // ===== Maintain aspect ratio within panel bounds =====
                int pw = getWidth();
                int ph = getHeight();
                double scale = Math.min((double) pw / finalImg.getWidth(),
                                        (double) ph / finalImg.getHeight());
                int w = (int)(finalImg.getWidth()  * scale);
                int h = (int)(finalImg.getHeight() * scale);
                int x = (pw - w) / 2;
                int y = (ph - h) / 2;

                // ===== High quality scaling =====
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                                    RenderingHints.VALUE_RENDER_QUALITY);
                g2.drawImage(finalImg, x, y, w, h, null);
            }
        };
        imagePanel.setBackground(ThemeManager.BG);

        JScrollPane scroll = new JScrollPane(imagePanel);
        scroll.setBorder(null);
        scroll.setBackground(ThemeManager.BG);
        scroll.getViewport().setBackground(ThemeManager.BG);
        return scroll;
    }

    // =========================================================================
    // Private: PDF viewer
    // =========================================================================

    /**
     * Renders a PDF to a vertically stacked list of BufferedImages.
     *
     * Security flow:
     *   1. Write bytes to RamDisk with owner-only permissions
     *   2. PDFBox renders each page to BufferedImage
     *   3. RamDisk wiped immediately after all pages rendered
     *   4. Decoded bytes wiped from heap
     *   5. Pages displayed as JLabels in a scroll pane
     */
    private static JComponent buildPdfViewer(byte[] decoded) {
        List<BufferedImage> pages = new ArrayList<>();
        RamDisk ramDisk = null;

        try {
            // ===== Write to RamDisk for PDFBox to read =====
            ramDisk = RamDisk.acquire();
            activeRamDisks.add(ramDisk);

            Path tmpPdf = ramDisk.getPath().resolve("odin_preview.pdf");

            // ===== Owner-only permissions before writing any content =====
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            Files.createFile(tmpPdf, PosixFilePermissions.asFileAttribute(ownerOnly));
            Files.write(tmpPdf, decoded, StandardOpenOption.WRITE);

            // ===== Render all pages to BufferedImage list =====
            try (PDDocument doc = Loader.loadPDF(tmpPdf.toFile())) {
                PDFRenderer renderer = new PDFRenderer(doc);
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    // ===== Render at screen DPI; pages are stacked vertically =====
                    BufferedImage page = renderer.renderImageWithDPI(i, PDF_DPI);
                    pages.add(page);
                }
            }

        } catch (RamDisk.RamDiskException e) {
            return errorLabel("RAM disk unavailable for PDF rendering: " + e.getMessage());
        } catch (IOException e) {
            return errorLabel("Failed to render PDF: " + e.getMessage());
        } finally {
            // ===== Wipe RamDisk immediately after render, before showing UI =====
            if (ramDisk != null) {
                try {
                    ramDisk.wipeAndDestroy();
                    activeRamDisks.remove(ramDisk);
                } catch (RamDisk.RamDiskException e) {
                    System.err.println("[BinaryViewer] PDF RamDisk wipe failed: " + e.getMessage());
                }
            }
            // ===== Wipe decoded bytes; pages are now in BufferedImage list =====
            Arrays.fill(decoded, (byte) 0);
        }

        // ===== Build vertically stacked page panel =====
        JPanel pagesPanel = new JPanel();
        pagesPanel.setLayout(new BoxLayout(pagesPanel, BoxLayout.Y_AXIS));
        pagesPanel.setBackground(ThemeManager.BG);
        pagesPanel.setBorder(new EmptyBorder(12, 12, 12, 12));

        for (int i = 0; i < pages.size(); i++) {
            BufferedImage page = pages.get(i);

            // ===== Page number label above each page =====
            JLabel pageNum = new JLabel("Page " + (i + 1) + " of " + pages.size());
            pageNum.setForeground(ThemeManager.TEXT_MUTED);
            pageNum.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            pageNum.setAlignmentX(Component.CENTER_ALIGNMENT);
            pageNum.setBorder(new EmptyBorder(8, 0, 4, 0));
            pagesPanel.add(pageNum);

            // ===== Page image label; full width, natural height =====
            JLabel pageLabel = new JLabel(new ImageIcon(page));
            pageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            pageLabel.setBorder(BorderFactory.createLineBorder(ThemeManager.BORDER));
            pagesPanel.add(pageLabel);
            pagesPanel.add(Box.createVerticalStrut(16));
        }

        // ===== Wrap in scroll pane for vertical scrolling through all pages =====
        JScrollPane scroll = new JScrollPane(pagesPanel);
        scroll.setBorder(null);
        scroll.setBackground(ThemeManager.BG);
        scroll.getViewport().setBackground(ThemeManager.BG);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        return scroll;
    }

    // =========================================================================
    // Private: text viewer
    // =========================================================================

    /**
     * Displays text content in a read-only monospaced JTextArea.
     * Bytes decoded as UTF-8; stays entirely in JVM heap.
     */
    private static JComponent buildTextViewer(byte[] decoded) {
        String text;
        try {
            text = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            text = "[Could not decode text content]";
        }

        JTextArea ta = new JTextArea(text);
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        ta.setForeground(ThemeManager.TEXT);
        ta.setBackground(ThemeManager.SURFACE2);
        ta.setCaretColor(ThemeManager.ACCENT);
        ta.setEditable(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setBorder(new EmptyBorder(12, 14, 12, 14));

        JScrollPane scroll = new JScrollPane(ta);
        scroll.setBorder(null);
        scroll.setBackground(ThemeManager.SURFACE2);
        scroll.getViewport().setBackground(ThemeManager.SURFACE2);
        return scroll;
    }

    // =========================================================================
    // Private: metadata panel (DOCX, XLSX, UNKNOWN)
    // =========================================================================

    /**
     * Shows file metadata for types that cannot be rendered inline.
     * Export button is the only action.
     */
    private static JComponent buildMetadataPanel(byte[] decoded, String filename, DetectedType type) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ThemeManager.BG);
        panel.setBorder(new EmptyBorder(32, 32, 32, 32));

        // ===== File type icon substitute =====
        String icon = switch (type) {
            case DOCX    -> "📄";
            case XLSX    -> "📊";
            default      -> "📦";
        };

        JLabel iconLabel = new JLabel(icon + "  " + filename);
        iconLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        iconLabel.setForeground(ThemeManager.TEXT);
        iconLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(iconLabel);
        panel.add(Box.createVerticalStrut(20));

        // ===== File size =====
        String sizeStr = humanReadableSize(decoded != null ? decoded.length : 0);
        addMetaRow(panel, "File Size", sizeStr);
        addMetaRow(panel, "Type",      type.name());
        addMetaRow(panel, "Preview",   "Not available for this format. Use Export to open.");

        panel.add(Box.createVerticalGlue());
        return new JScrollPane(panel);
    }

    // =========================================================================
    // Private: PDF thumbnail helper
    // =========================================================================

    /**
     * Renders the first page of a PDF as a thumbnail.
     * Writes to RamDisk, renders page 0, wipes RamDisk immediately.
     * Returns null on any failure rather than throwing.
     */
    private static ImageIcon pdfThumbnail(byte[] decoded) {
        RamDisk ramDisk = null;
        try {
            ramDisk = RamDisk.acquire();
            activeRamDisks.add(ramDisk);

            Path tmpPdf = ramDisk.getPath().resolve("odin_thumb.pdf");

            // ===== Owner-only permissions before writing =====
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            Files.createFile(tmpPdf, PosixFilePermissions.asFileAttribute(ownerOnly));
            Files.write(tmpPdf, decoded, StandardOpenOption.WRITE);

            try (PDDocument doc = Loader.loadPDF(tmpPdf.toFile())) {
                PDFRenderer renderer = new PDFRenderer(doc);
                // ===== Render first page only for thumbnail =====
                BufferedImage page = renderer.renderImageWithDPI(0, 72f);
                return new ImageIcon(scaleImage(page, THUMB_W, THUMB_H));
            }

        } catch (Exception e) {
            System.err.println("[BinaryViewer] PDF thumbnail failed: " + e.getMessage());
            return null;
        } finally {
            // ===== Wipe RamDisk immediately after thumbnail render =====
            if (ramDisk != null) {
                try {
                    ramDisk.wipeAndDestroy();
                    activeRamDisks.remove(ramDisk);
                } catch (RamDisk.RamDiskException e) {
                    System.err.println("[BinaryViewer] PDF thumb RamDisk wipe failed: " + e.getMessage());
                }
            }
        }
    }

    // =========================================================================
    // Utility helpers
    // =========================================================================

    /** Scales a BufferedImage to fit within maxW x maxH preserving aspect ratio */
    private static Image scaleImage(BufferedImage img, int maxW, int maxH) {
        double scale = Math.min((double) maxW / img.getWidth(),
                                (double) maxH / img.getHeight());
        int w = Math.max(1, (int)(img.getWidth()  * scale));
        int h = Math.max(1, (int)(img.getHeight() * scale));
        return img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
    }

    /** Checks whether a byte array starts with the given magic bytes */
    private static boolean startsWith(byte[] data, byte[] magic) {
        if (data.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) return false;
        }
        return true;
    }

    /** Extracts lowercase file extension from a filename, empty string if none */
    private static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }

    /** Formats a byte count as a human-readable string (KB, MB) */
    private static String humanReadableSize(long bytes) {
        if (bytes < 1024)        return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    /** Adds a two-column label/value row to a metadata panel */
    private static void addMetaRow(JPanel panel, String label, String value) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setBackground(ThemeManager.BG);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

        JLabel lbl = new JLabel(label + ":");
        lbl.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lbl.setForeground(ThemeManager.TEXT_MUTED);
        lbl.setPreferredSize(new Dimension(100, 0));

        JLabel val = new JLabel(value);
        val.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        val.setForeground(ThemeManager.TEXT);

        row.add(lbl, BorderLayout.WEST);
        row.add(val, BorderLayout.CENTER);
        panel.add(row);
        panel.add(Box.createVerticalStrut(6));
    }

    /** Returns a scroll-wrapped error label for display in the viewer dialog */
    private static JComponent errorLabel(String message) {
        JLabel lbl = new JLabel(message);
        lbl.setForeground(ThemeManager.TEXT_MUTED);
        lbl.setFont(new Font("Segoe UI", Font.ITALIC, 13));
        lbl.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(ThemeManager.BG);
        p.add(lbl, BorderLayout.CENTER);
        return p;
    }
}
