/* SPDX-License-Identifier: GPL-3.0-or-later */
import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/// Builds the NerveGear启动器 app icon from the locked 誓约 ring (James, 11 Nov: "icon用誓约的那个环").
///
/// The ring's own pixels are composited, never redrawn: logo-33-只有圆环-裁切.png (the two tapered arcs with
/// their glow, alpha), scaled and centred on the dark navy of the logo's dark preview, inside the macOS icon
/// grid's rounded square (824 of 1024, corner 185) with the grid's margin. Windows and Linux use the same art.
///
/// Run headless from the repository root:
///   java -Djava.awt.headless=true packaging/MakeIcon.java "<path>/logo-33-只有圆环-裁切.png" packaging HMCL/src/main/resources/assets/img
/// Writes packaging/icon.png (1024) and the in-app icons icon.png (32), icon@2x (64), icon@4x (128),
/// icon@8x (256) and icon-mac.png (512). icon-title*.png (the title-bar mark) is not touched.
public final class MakeIcon {
    /// The navy of the logo's dark preview (logo-33-预览-深底和浅底.jpg).
    static final Color NAVY = new Color(0x0c, 0x12, 0x22);
    static final int SIZE = 1024, SQUARE = 824, CORNER = 185;
    /// The ring's height as a share of the square: large enough to read at 16 px, its glow inside the square.
    static final double RING = 0.92;

    public static void main(String[] args) throws Exception {
        BufferedImage ring = ImageIO.read(new File(args[0]));
        BufferedImage master = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = master.createGraphics();
        quality(g);
        int off = (SIZE - SQUARE) / 2;
        RoundRectangle2D square = new RoundRectangle2D.Double(off, off, SQUARE, SQUARE, CORNER * 2, CORNER * 2);
        g.setColor(NAVY);
        g.fill(square);
        // The ring, scaled as one picture and centred on the square; clipped to the square so no glow spills.
        double k = SQUARE * RING / ring.getHeight();
        int w = (int) Math.round(ring.getWidth() * k), h = (int) Math.round(ring.getHeight() * k);
        g.setClip(square);
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(scale(ring, w, h), (SIZE - w) / 2, (SIZE - h) / 2, null);
        g.dispose();

        File pack = new File(args[1]);
        ImageIO.write(master, "png", new File(pack, "icon.png"));
        if (args.length > 2) {
            File img = new File(args[2]);
            ImageIO.write(scale(master, 32, 32), "png", new File(img, "icon.png"));
            ImageIO.write(scale(master, 64, 64), "png", new File(img, "icon@2x.png"));
            ImageIO.write(scale(master, 128, 128), "png", new File(img, "icon@4x.png"));
            ImageIO.write(scale(master, 256, 256), "png", new File(img, "icon@8x.png"));
            ImageIO.write(scale(master, 512, 512), "png", new File(img, "icon-mac.png"));
        }
        System.out.println("ring " + ring.getWidth() + "x" + ring.getHeight() + " -> " + w + "x" + h + " on " + SQUARE + " of " + SIZE);
    }

    /// Area-averaged scaling in halving steps, so small sizes keep the arcs and the glow without aliasing.
    static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) cur = once(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        return cur.getWidth() == w && cur.getHeight() == h ? cur : once(cur, w, h);
    }

    static BufferedImage once(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        quality(g);
        // Down: area-averaged. Up (the ring onto the 1024 grid): bicubic.
        if (w <= src.getWidth()) g.drawImage(src.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        else g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }
}
