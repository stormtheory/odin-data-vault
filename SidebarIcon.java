import java.awt.*;
import java.awt.geom.*;
import javax.swing.*;

/**
 * ===== SIDEBAR ICON =====
 * Pure Java2D painted icons for the sidebar - no emoji font, no SVG lib,
 * no image files required.  Each icon is drawn programmatically using
 * Graphics2D shapes, making them crisp at any DPI and fully cross-platform.
 *
 * Icons (all entry types + folder):
 *   account   - person silhouette (head circle + shoulder arc)
 *   note      - note page with folded corner + ruled lines + pencil
 *   address   - map pin teardrop with inner ring
 *   card      - credit/debit card with EMV chip and number dots
 *   passkey   - shield outline with fingerprint arc stack
 *   ssh       - terminal window with prompt chevron and key symbol
 *   vpn       - globe with latitude/longitude lines + lock overlay
 *   binary    - binary digit grid (0/1 pattern) with bracket framing
 *   docs      - note page variant (reuses paintNote)
 *   folder    - classic folder shape with tab, open-top body, highlight
 *
 * All icons use ThemeManager.ACCENT as the primary color so they
 * automatically adapt when the theme changes between dark and light.
 *
 * Usage:
 *   JLabel lbl = new JLabel(SidebarIcon.forType("account", 22));
 */
public class SidebarIcon implements Icon {

    // ===== ICON TYPE =====
    // ===== BINARY and FOLDER are new additions - BINARY now has its own painter =====
    public enum Type { ACCOUNT, NOTE, ADDRESS, CARD, PASSKEY, SSH, VPN, BINARY, DOCUMENTS, FOLDER }

    private final Type type;
    private final int  size; // rendered square size in pixels

    public SidebarIcon(Type type, int size) {
        this.type = type;
        this.size = size;
    }

    // ===== FACTORY =====
    /**
     * Returns a SidebarIcon for the given vault entry type key.
     * All types have custom icons - never returns null.
     *
     * @param typeKey  entry type key - "account","note","address",
     *                 "card","passkey","ssh","vpn","binary","docs","folder"
     * @param size     desired icon size in pixels (icon renders square)
     */
    public static Icon forType(String typeKey, int size) {
        return switch (typeKey) {
            case "account" -> new SidebarIcon(Type.ACCOUNT,   size);
            case "note"    -> new SidebarIcon(Type.NOTE,      size);
            case "address" -> new SidebarIcon(Type.ADDRESS,   size);
            case "card"    -> new SidebarIcon(Type.CARD,      size);
            case "passkey" -> new SidebarIcon(Type.PASSKEY,   size);
            case "ssh"     -> new SidebarIcon(Type.SSH,       size);
            case "vpn"     -> new SidebarIcon(Type.VPN,       size);
            // ===== BINARY now has its own distinct icon - no longer reuses VPN =====
            case "binary"  -> new SidebarIcon(Type.BINARY,    size);
            case "docs"    -> new SidebarIcon(Type.DOCUMENTS, size);
            // ===== FOLDER - used for sidebar folder entries =====
            case "folder"  -> new SidebarIcon(Type.FOLDER,   size);
            // ===== Fallback - should never hit with current entry types =====
            default        -> new SidebarIcon(Type.ACCOUNT,   size);
        };
    }

    // ===== ICON INTERFACE =====
    public int getIconWidth()  { return size; }
    public int getIconHeight() { return size; }

    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        // ===== Smooth curves and precise strokes at any size =====
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,    RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY);
        // ===== Text antialiasing needed for the binary digit grid =====
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // ===== Translate to icon origin - all paint methods use local (0,0) =====
        g2.translate(x, y);
        switch (type) {
            case ACCOUNT   -> paintAccount(g2);
            case NOTE      -> paintNote(g2);
            case ADDRESS   -> paintAddress(g2);
            case CARD      -> paintCard(g2);
            case PASSKEY   -> paintPasskey(g2);
            case SSH       -> paintSSH(g2);
            case VPN       -> paintVPN(g2);
            // ===== BINARY routes to its own dedicated painter =====
            case BINARY    -> paintBinary(g2);
            case DOCUMENTS -> paintNote(g2);
            // ===== FOLDER routes to its own dedicated painter =====
            case FOLDER    -> paintFolder(g2);
        }
        g2.dispose();
    }

    // ===== SHARED HELPER =====
    // ===== Accent color with given alpha - used throughout all paint methods =====
    private static Color acA(int alpha) {
        Color a = ThemeManager.ACCENT;
        return new Color(a.getRed(), a.getGreen(), a.getBlue(),
                         Math.max(0, Math.min(255, alpha)));
    }

    // ===== ACCOUNT - person silhouette =====
    // ===== Head: filled circle. Body: open bezier arc for shoulders. =====
    private void paintAccount(Graphics2D g2) {
        float s   = size;
        float hR  = s * 0.27f;   // head radius
        float hCX = s * 0.50f;   // head center x
        float hCY = s * 0.32f;   // head center y

        // ===== Head fill =====
        g2.setColor(acA(230));
        g2.fill(new Ellipse2D.Float(hCX - hR, hCY - hR, hR * 2, hR * 2));

        // ===== Subtle specular highlight - top-left of head =====
        g2.setColor(new Color(255, 255, 255, 38));
        float hlR = hR * 0.38f;
        g2.fill(new Ellipse2D.Float(hCX - hR * 0.46f - hlR, hCY - hR * 0.46f - hlR,
                                    hlR * 2, hlR * 2));

        // ===== Shoulder arc - bezier curve, open stroke, bottom of icon =====
        g2.setColor(acA(220));
        g2.setStroke(new BasicStroke(s * 0.13f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float arcW  = s * 0.86f;
        float arcX  = (s - arcW) / 2f;
        float peakY = hCY + hR + s * 0.08f; // arc peak just below head
        Path2D.Float sh = new Path2D.Float();
        sh.moveTo(arcX, s * 0.97f);
        sh.curveTo(arcX, peakY, arcX + arcW, peakY, arcX + arcW, s * 0.97f);
        g2.draw(sh);
    }

    // ===== NOTE - ruled page with folded corner and pencil =====
    private void paintNote(Graphics2D g2) {
        float s      = size;
        float pageX  = s * 0.08f;
        float pageY  = s * 0.06f;
        float pageW  = s * 0.68f;
        float pageH  = s * 0.86f;
        float corner = s * 0.20f; // folded corner triangle leg length

        // ===== Page fill - very low alpha, just a surface hint =====
        g2.setColor(acA(28));
        g2.fill(new RoundRectangle2D.Float(pageX, pageY, pageW, pageH, 5, 5));
        // ===== Page border =====
        g2.setColor(acA(195));
        g2.setStroke(new BasicStroke(s * 0.07f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new RoundRectangle2D.Float(pageX, pageY, pageW, pageH, 5, 5));

        // ===== Folded corner - top-right triangle =====
        float cx = pageX + pageW - corner;
        Path2D.Float fold = new Path2D.Float();
        fold.moveTo(cx, pageY);
        fold.lineTo(cx + corner, pageY + corner);
        fold.lineTo(cx, pageY + corner);
        fold.closePath();
        g2.setColor(acA(105));
        g2.fill(fold);
        g2.setColor(acA(195));
        g2.draw(fold);

        // ===== Three ruled lines - last line is shorter for natural look =====
        float lx1     = pageX + s * 0.10f;
        float lx2     = pageX + pageW - s * 0.14f;
        float firstY  = pageY + pageH * 0.38f;
        float spacing = pageH * 0.195f;
        g2.setStroke(new BasicStroke(s * 0.065f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(acA(185));
        for (int i = 0; i < 3; i++) {
            float ly = firstY + i * spacing;
            float rx = (i == 2) ? lx1 + (lx2 - lx1) * 0.58f : lx2;
            g2.draw(new Line2D.Float(lx1, ly, rx, ly));
        }

        // ===== Pencil - bottom right, rotated ~42 degrees =====
        Graphics2D gp = (Graphics2D) g2.create();
        gp.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gp.translate(s * 0.76f, s * 0.72f);
        gp.rotate(Math.toRadians(-42));
        float pw = s * 0.14f;
        float ph = s * 0.40f;
        float px = -pw / 2f;
        float py = -ph;
        // ===== Pencil body =====
        gp.setColor(acA(215));
        gp.fill(new RoundRectangle2D.Float(px, py, pw, ph, 2, 2));
        // ===== Wood tip triangle =====
        Path2D.Float tip = new Path2D.Float();
        tip.moveTo(px, 0); tip.lineTo(px + pw, 0); tip.lineTo(0, s * 0.12f);
        tip.closePath();
        gp.setColor(new Color(230, 215, 195, 200));
        gp.fill(tip);
        // ===== Eraser band highlight at top =====
        gp.setColor(new Color(255, 255, 255, 45));
        gp.fill(new Rectangle2D.Float(px, py, pw, s * 0.07f));
        gp.dispose();
    }

    // ===== ADDRESS - teardrop map pin =====
    private void paintAddress(Graphics2D g2) {
        float s  = size;
        float cx = s * 0.50f; // pin center x
        float cy = s * 0.36f; // pin head center y
        float r  = s * 0.33f; // pin head radius

        // ===== Teardrop: two bezier curves from top, meeting at point bottom =====
        Path2D.Float pin = new Path2D.Float();
        pin.moveTo(cx, cy - r);
        pin.curveTo(cx + r, cy - r, cx + r, cy + r * 0.5f, cx, s * 0.96f);
        pin.curveTo(cx - r, cy + r * 0.5f, cx - r, cy - r, cx, cy - r);
        pin.closePath();
        g2.setColor(acA(215));
        g2.fill(pin);

        // ===== Inner ring - surface-colored circle creating the pin hole =====
        float ir = r * 0.44f;
        Color bg = ThemeManager.SURFACE;
        g2.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 145));
        g2.fill(new Ellipse2D.Float(cx - ir, cy - ir, ir * 2, ir * 2));

        // ===== Specular highlight - small ellipse, top-left of pin head =====
        AffineTransform saved = g2.getTransform();
        g2.translate(cx - r * 0.32f, cy - r * 0.32f);
        g2.rotate(Math.toRadians(-25));
        g2.setColor(new Color(255, 255, 255, 50));
        g2.fill(new Ellipse2D.Float(-r * 0.22f, -r * 0.13f, r * 0.44f, r * 0.26f));
        g2.setTransform(saved);
    }

    // ===== CARD - credit/debit card with EMV chip and number dots =====
    private void paintCard(Graphics2D g2) {
        float s  = size;
        float cX = s * 0.05f;   // card body left x
        float cY = s * 0.18f;   // card body top y
        float cW = s * 0.90f;   // card width
        float cH = s * 0.62f;   // card height

        // ===== Card body fill (very low alpha) =====
        g2.setColor(acA(28));
        g2.fill(new RoundRectangle2D.Float(cX, cY, cW, cH, 6, 6));
        // ===== Card border =====
        g2.setColor(acA(200));
        g2.setStroke(new BasicStroke(s * 0.07f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new RoundRectangle2D.Float(cX, cY, cW, cH, 6, 6));

        // ===== Magnetic stripe - dark band across upper portion of card =====
        float stripeY = cY + cH * 0.18f;
        float stripeH = cH * 0.26f;
        g2.setColor(acA(95));
        g2.fill(new Rectangle2D.Float(cX, stripeY, cW, stripeH));

        // ===== EMV chip - small rounded rectangle, left side below stripe =====
        float chipX = cX + cW * 0.12f;
        float chipY = stripeY + stripeH + cH * 0.12f;
        float chipW = cW * 0.22f;
        float chipH = cH * 0.32f;
        g2.setColor(acA(175));
        g2.fill(new RoundRectangle2D.Float(chipX, chipY, chipW, chipH, 2, 2));
        // ===== Chip contact lines =====
        g2.setColor(acA(60));
        g2.setStroke(new BasicStroke(s * 0.05f));
        float cl = chipY + chipH * 0.28f;
        for (int i = 0; i < 3; i++) {
            g2.draw(new Line2D.Float(chipX + chipW * 0.15f, cl + i * chipH * 0.22f,
                                     chipX + chipW * 0.85f, cl + i * chipH * 0.22f));
        }

        // ===== Card number dots - four small dots right of chip =====
        g2.setColor(acA(130));
        float dotY  = chipY + chipH * 0.5f;
        float dotSX = chipX + chipW + cW * 0.08f;
        float dotR  = s * 0.036f;
        for (int i = 0; i < 4; i++) {
            float dx = dotSX + i * cW * 0.065f;
            g2.fill(new Ellipse2D.Float(dx - dotR, dotY - dotR, dotR * 2, dotR * 2));
        }
    }

    // ===== PASSKEY - shield with fingerprint arc stack =====
    private void paintPasskey(Graphics2D g2) {
        float s  = size;
        float cx = s * 0.50f;

        // ===== Shield path - pointed bottom, straight sides, rounded shoulders =====
        float sw = s * 0.80f;
        float sx = (s - sw) / 2f;
        float st = s * 0.08f;   // top y
        float sm = s * 0.52f;   // widest point y
        float sb = s * 0.94f;   // bottom point y
        Path2D.Float shield = new Path2D.Float();
        shield.moveTo(cx, st);
        shield.lineTo(sx + sw, st + sw * 0.18f);
        shield.lineTo(sx + sw, sm);
        shield.curveTo(sx + sw, sm + sw * 0.28f, cx, sb, cx, sb);
        shield.curveTo(cx, sb, sx, sm + sw * 0.28f, sx, sm);
        shield.lineTo(sx, st + sw * 0.18f);
        shield.closePath();
        // ===== Shield fill =====
        g2.setColor(acA(25));
        g2.fill(shield);
        // ===== Shield border =====
        g2.setColor(acA(200));
        g2.setStroke(new BasicStroke(s * 0.07f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(shield);

        // ===== Fingerprint arcs - three concentric open semicircles =====
        float fpCX = cx;
        float fpCY = s * 0.63f; // arc center
        g2.setStroke(new BasicStroke(s * 0.07f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        // ===== Inner arc =====
        float r1 = s * 0.11f;
        g2.setColor(acA(220));
        g2.draw(new Arc2D.Float(fpCX - r1, fpCY - r1, r1 * 2, r1 * 2, 0, 180, Arc2D.OPEN));
        // ===== Middle arc =====
        float r2 = s * 0.20f;
        g2.setColor(acA(160));
        g2.draw(new Arc2D.Float(fpCX - r2, fpCY - r2, r2 * 2, r2 * 2, 10, 160, Arc2D.OPEN));
        // ===== Outer arc =====
        float r3 = s * 0.29f;
        g2.setColor(acA(95));
        g2.draw(new Arc2D.Float(fpCX - r3, fpCY - r3, r3 * 2, r3 * 2, 20, 140, Arc2D.OPEN));
        // ===== Center dot =====
        float dr = s * 0.055f;
        g2.setColor(acA(230));
        g2.fill(new Ellipse2D.Float(fpCX - dr, fpCY - dr, dr * 2, dr * 2));
    }

    // ===== SSH - terminal window with prompt chevron and key =====
    private void paintSSH(Graphics2D g2) {
        float s    = size;
        float tX   = s * 0.04f;
        float tY   = s * 0.08f;
        float tW   = s * 0.92f;
        float tH   = s * 0.84f;
        float barH = tH * 0.22f; // title bar height

        // ===== Terminal body fill and border =====
        g2.setColor(acA(22));
        g2.fill(new RoundRectangle2D.Float(tX, tY, tW, tH, 6, 6));
        g2.setColor(acA(195));
        g2.setStroke(new BasicStroke(s * 0.065f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new RoundRectangle2D.Float(tX, tY, tW, tH, 6, 6));

        // ===== Title bar fill =====
        g2.setColor(acA(60));
        g2.fill(new RoundRectangle2D.Float(tX, tY, tW, barH + 4, 6, 6));
        g2.fill(new Rectangle2D.Float(tX, tY + barH - 2, tW, 6));

        // ===== Traffic-light dots in title bar (decreasing alpha = close/min/max) =====
        float[] dotAlpha = {200, 140, 80};
        for (int i = 0; i < 3; i++) {
            float dx = tX + s * 0.10f + i * s * 0.14f;
            float dy = tY + barH * 0.50f;
            float dr = s * 0.052f;
            g2.setColor(acA((int) dotAlpha[i]));
            g2.fill(new Ellipse2D.Float(dx - dr, dy - dr, dr * 2, dr * 2));
        }

        // ===== Prompt chevron > in body area =====
        float pX  = tX + s * 0.10f;
        float pMY = tY + barH + (tH - barH) * 0.35f;
        float pS  = s * 0.12f;
        g2.setColor(acA(210));
        g2.setStroke(new BasicStroke(s * 0.09f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Line2D.Float(pX, pMY - pS, pX + pS, pMY));
        g2.draw(new Line2D.Float(pX + pS, pMY, pX, pMY + pS));

        // ===== Key symbol - bow (circle) + shaft + two teeth =====
        float kCX = tX + tW * 0.63f;
        float kCY = tY + barH + (tH - barH) * 0.40f;
        float kR  = s * 0.13f;   // bow radius
        float shW = s * 0.28f;   // shaft length
        float shH = s * 0.072f;  // shaft thickness

        // ===== Bow outline =====
        g2.setColor(acA(210));
        g2.setStroke(new BasicStroke(s * 0.075f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Ellipse2D.Float(kCX - kR, kCY - kR, kR * 2, kR * 2));

        // ===== Key hole - small filled circle inside bow =====
        float khR = kR * 0.36f;
        g2.fill(new Ellipse2D.Float(kCX - khR, kCY - khR, khR * 2, khR * 2));

        // ===== Shaft =====
        float shX = kCX + kR;
        float shY = kCY - shH / 2f;
        g2.setColor(acA(210));
        g2.setStroke(new BasicStroke(s * 0.06f));
        g2.fill(new Rectangle2D.Float(shX, shY, shW, shH));

        // ===== Two teeth hanging down from shaft =====
        g2.setStroke(new BasicStroke(s * 0.07f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(acA(195));
        float toothH = shH * 1.4f;
        float t1X = shX + shW * 0.32f;
        float t2X = shX + shW * 0.62f;
        g2.draw(new Line2D.Float(t1X, shY + shH, t1X, shY + shH + toothH));
        g2.draw(new Line2D.Float(t2X, shY + shH, t2X, shY + shH + toothH));
    }

    // ===== VPN - globe with lock overlay =====
    private void paintVPN(Graphics2D g2) {
        float s  = size;
        float cx = s * 0.50f;
        float cy = s * 0.50f;
        float r  = s * 0.40f; // globe radius

        // ===== Globe fill and outline =====
        g2.setColor(acA(22));
        g2.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        g2.setColor(acA(195));
        g2.setStroke(new BasicStroke(s * 0.065f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));

        // ===== Longitude meridian - vertical ellipse =====
        g2.setColor(acA(100));
        g2.setStroke(new BasicStroke(s * 0.055f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Ellipse2D.Float(cx - r * 0.52f, cy - r, r * 1.04f, r * 2));

        // ===== Two latitude lines - upper and lower arcs =====
        float latR = r * 0.82f;
        g2.draw(new Arc2D.Float(cx - latR, cy - r * 0.46f - s * 0.04f,
                                latR * 2, s * 0.08f, 0, 180, Arc2D.OPEN));
        g2.draw(new Arc2D.Float(cx - latR, cy + r * 0.38f - s * 0.04f,
                                latR * 2, s * 0.08f, 180, 180, Arc2D.OPEN));
        // ===== Equator =====
        g2.draw(new Line2D.Float(cx - r, cy, cx + r, cy));

        // ===== Lock - padlock centered on globe =====
        float lW  = r * 0.82f;
        float lH  = r * 0.62f;
        float lX  = cx - lW / 2f;
        float lBY = cy - lH * 0.10f; // lock body top

        // ===== Shackle arc above lock body =====
        float shkR  = lW * 0.28f;
        g2.setColor(acA(215));
        g2.setStroke(new BasicStroke(s * 0.095f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Arc2D.Float(cx - shkR, lBY - shkR * 1.4f,
                                shkR * 2, shkR * 2.8f, 0, 180, Arc2D.OPEN));

        // ===== Lock body - surface fill first to occlude globe lines behind it =====
        Color bg = ThemeManager.SURFACE;
        g2.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 210));
        g2.fill(new RoundRectangle2D.Float(lX, lBY, lW, lH, 4, 4));
        // ===== Accent fill over surface =====
        g2.setColor(acA(210));
        g2.fill(new RoundRectangle2D.Float(lX, lBY, lW, lH, 4, 4));
        // ===== Border stroke =====
        g2.setStroke(new BasicStroke(s * 0.07f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(acA(220));
        g2.draw(new RoundRectangle2D.Float(lX, lBY, lW, lH, 4, 4));

        // ===== Keyhole - circle + slot cutout in lock body =====
        float khCX = cx;
        float khCY = lBY + lH * 0.40f;
        float khR  = lW * 0.14f;
        Color bgD  = ThemeManager.BG;
        g2.setColor(new Color(bgD.getRed(), bgD.getGreen(), bgD.getBlue(), 190));
        g2.fill(new Ellipse2D.Float(khCX - khR, khCY - khR, khR * 2, khR * 2));
        g2.fill(new Rectangle2D.Float(khCX - khR * 0.5f, khCY, khR, lH * 0.32f));
    }

    // ===== BINARY - two rows of large 0/1 digits filling the icon =====
    // ===== No brackets, no grid lines - just clean bold binary numbers. =====
    // ===== Row 1: "1011"  Row 2: "0110" =====
    // ===== '1' digits render at full accent alpha; '0' digits are dimmed. =====
    private void paintBinary(Graphics2D g2) {
        float s = size;

        // ===== Two rows of 4 digits each - fills the icon top to bottom =====
        String[] rows = { "1011", "0110" };

        // ===== Font large enough to fill ~half the icon height per row =====
        // ===== Derive size from icon size so it scales at any pixel count =====
        int   fontSize = Math.max(6, Math.round(s * 0.36f));
        Font  font     = new Font(Font.MONOSPACED, Font.BOLD, fontSize);
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();

        // ===== Vertical center of each row =====
        // ===== Two rows share the icon height with a small gap between them =====
        float rowH   = s * 0.44f;  // height allocated per row
        float row0CY = s * 0.28f;  // center y of first row
        float row1CY = s * 0.72f;  // center y of second row
        float[] rowCY = { row0CY, row1CY };

        for (int r = 0; r < rows.length; r++) {
            String text = rows[r];
            // ===== Measure total width so we can center the string =====
            int totalW = fm.stringWidth(text);
            float startX = (s - totalW) / 2f;
            float baseY  = rowCY[r] + (fm.getAscent() - fm.getDescent()) / 2f;

            // ===== Draw each digit individually to apply per-digit alpha =====
            float cx = startX;
            for (int i = 0; i < text.length(); i++) {
                String digit = String.valueOf(text.charAt(i));
                // ===== '1' is full accent brightness; '0' is clearly dimmed =====
                g2.setColor(acA(digit.equals("1") ? 220 : 75));
                g2.drawString(digit, cx, baseY);
                cx += fm.stringWidth(digit);
            }
        }
    }

    // ===== FOLDER - classic folder shape with tab and highlight =====
    // ===== Body: rounded rectangle. Tab: small raised rect top-left. =====
    // ===== Inner highlight line gives depth at the open top edge.   =====
    private void paintFolder(Graphics2D g2) {
        float s  = size;

        // ===== Folder body dimensions =====
        float fX = s * 0.05f;   // body left x
        float fY = s * 0.28f;   // body top y (below tab)
        float fW = s * 0.90f;   // body width
        float fH = s * 0.58f;   // body height
        float fR = s * 0.08f;   // corner radius

        // ===== Tab dimensions - sits above body, flush left =====
        float tW = fW * 0.42f;  // tab width
        float tH = s  * 0.14f;  // tab height
        float tX = fX;           // tab left x - flush with body left
        float tY = fY - tH + fR; // tab top y - overlaps body corner slightly

        // ===== Tab shape - rounded top-left and top-right corners only =====
        Path2D.Float tab = new Path2D.Float();
        tab.moveTo(tX + fR, tY);
        tab.lineTo(tX + tW - fR, tY);
        tab.quadTo(tX + tW, tY, tX + tW, tY + fR);
        tab.lineTo(tX + tW, tY + tH);
        tab.lineTo(tX, tY + tH);
        tab.lineTo(tX, tY + fR);
        tab.quadTo(tX, tY, tX + fR, tY);
        tab.closePath();

        // ===== Draw tab fill then border =====
        g2.setColor(acA(55));
        g2.fill(tab);
        g2.setColor(acA(185));
        g2.setStroke(new BasicStroke(s * 0.065f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(tab);

        // ===== Folder body fill =====
        g2.setColor(acA(35));
        g2.fill(new RoundRectangle2D.Float(fX, fY, fW, fH, fR, fR));

        // ===== Folder body border =====
        g2.setColor(acA(200));
        g2.setStroke(new BasicStroke(s * 0.07f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new RoundRectangle2D.Float(fX, fY, fW, fH, fR, fR));

        // ===== Inner highlight line just below the open top edge =====
        // ===== Gives a sense of depth - looks like the folder is open =====
        g2.setColor(acA(70));
        g2.setStroke(new BasicStroke(s * 0.055f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Line2D.Float(
            fX + fR,      fY + s * 0.09f,
            fX + fW - fR, fY + s * 0.09f
        ));

        // ===== Specular glint on tab top-left corner =====
        g2.setColor(new Color(255, 255, 255, 40));
        g2.fill(new Ellipse2D.Float(tX + fR * 0.5f, tY + fR * 0.4f, s * 0.08f, s * 0.05f));
    }
}