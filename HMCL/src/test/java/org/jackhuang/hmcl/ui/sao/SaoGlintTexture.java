/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.sao;

import javax.imageio.ImageIO; import java.awt.image.BufferedImage; import java.io.File; import java.util.Random;

/// Paints the visor-glass reflection texture (1280 x 720 design units, any pixel size): soft sheens across the curved
/// glass and a faint bright rim along one edge. args: variant (double|corner|arc) out.png W H; the shipped ones are 2560 x 1440 in assets/sao-launcher/glint.
public final class SaoGlintTexture {

    /// This texture generator runs through its command-line entry point.
    private SaoGlintTexture() {
    }

    static double g(double d,double s) { return Math.exp(-0.5 * d * d / (s * s)); }

  /// A soft band: centre line through (x0,y0) at angle deg, cross-section sigma s, fading along its length with sigma len.
  /// bow bends the band like a reflection on curved glass (offset grows with the square of the distance along).
    static double band(double x,double y,double x0,double y0,double deg,double s,double len,double bow) {
        double c = Math.cos(Math.toRadians(deg)),sn = Math.sin(Math.toRadians(deg)),dx = x - x0,dy = y - y0;
        double along = dx * c + dy * sn, across = -dx * sn + dy * c - bow * along * along; return g(across,s) * g(along,len); }

    static double sm(double e0,double e1,double x) { double t = Math.max(0,Math.min(1,(x - e0) / (e1 - e0))); return t * t * (3 - 2 * t); }

  /// A flat-topped soft band: full inside half, falling to nothing over soft on each side.
    static double flat(double x,double y,double x0,double y0,double deg,double half,double soft,double len,double bow) {
        double c = Math.cos(Math.toRadians(deg)),sn = Math.sin(Math.toRadians(deg)),dx = x - x0,dy = y - y0;
        double along = dx * c + dy * sn, across = Math.abs(-dx * sn + dy * c - bow * along * along); return sm(half + soft,half - soft * 0.3,across) * g(along,len); }

  /// The rim: bright at the very edge, soft falloff inward; d = distance to the edge.
    static double rim(double d,double sharp,double soft) { return 0.65 * Math.exp(-d / sharp) + 0.35 * Math.exp(-d / soft); }

    public static void main(String[] a) throws Exception {
        String v = a[0]; int W = Integer.parseInt(a[2]),H = Integer.parseInt(a[3]); double k = W / 1280.0;
        BufferedImage o = new BufferedImage(W,H,BufferedImage.TYPE_INT_ARGB); Random rnd = new Random(7); double maxA = 0;
        for (int py = 0;py < H;py++) for (int px = 0;px < W;px++) {
                double x = (px + 0.5) / k, y = (py + 0.5) / k, A;
                double dR = 1280 - x, dT = y;
                switch (v) {
        // Corner sheen: one broad soft band across the top-right glass, a haze around it, a rim down the right edge.
                    case "corner": A = 0.22 * flat(x,y,1120,100,62,26,30,210,0.0006) + 0.06 * band(x,y,1140,90,62,90,300,0.0006)
                         + 0.18 * rim(dR,5,40) * g(y - 300,230); break;
        // Top sheen: a wide soft band following the visor's upper curve, brighter to the right, rim along the top.
                    case "arc": {
                        double cy = -1500, r = Math.hypot(x - 640,y - cy), d = r - 1640; double right = 0.35 + 0.65 * sm(250,1100,x);
                        A = right * g(x - 820,480) * (0.24 * sm(34,12,Math.abs(d)) + 0.06 * g(d,80))
                            + 0.16 * rim(dT,4,30) * g(x - 820,420); } break;
        // Double sheen: a broad band and a narrower one beside it, like a window's reflection, rim on the right edge.
                    default: A = 0.22 * flat(x,y,1075,105,58,22,26,210,0.0005) + 0.17 * flat(x,y,1192,148,58,7,10,190,0.0005)
                   + 0.05 * band(x,y,1120,110,58,90,300,0.0005) + 0.14 * rim(dR,5,35) * g(y - 280,220);
                }
      // A reflection shows on the dark glass and is outshone by the lit plates behind it: there it keeps 15 %, so their
      // text reads as before. The plates' boxes, in design pixels, with a soft edge wider than the breath.
                double lit = 0; double[][] plates = {{106,175,396,610},{406,183,874,603},{884,175,1174,610}};
                for (double[] b:plates) lit = Math.max(lit,sm(b[0] - 14,b[0] + 6,x) * sm(b[2] + 14,b[2] - 6,x) * sm(b[1] - 14,b[1] + 6,y) * sm(b[3] + 14,b[3] - 6,y));
                A *= 1 - 0.85 * lit;
                A = Math.min(1,A); maxA = Math.max(maxA,A);
                int ai = (int)Math.max(0,Math.min(255,Math.floor(A * 255 + rnd.nextDouble())));
                o.setRGB(px,py,ai << 24 | 0xdcefff);
            }
        ImageIO.write(o,"png",new File(a[1])); System.out.println(v + " max alpha " + String.format("%.3f",maxA));
    }
}
