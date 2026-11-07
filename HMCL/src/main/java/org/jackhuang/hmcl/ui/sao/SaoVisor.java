/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 *
 * The visor of S01E01's view from inside the helmet, as the SAO Interface mod's SeriesPrelude draws it (same
 * author), drawn here into the launcher's software raster instead of the game's canvas.
 */
package org.jackhuang.hmcl.ui.sao;

import org.jetbrains.annotations.NotNullByDefault;

/// The NerveGear visor seen from inside: the glass over the dark ground, the arc of the ear pad at the bottom,
/// the recess at the top, the wearer's clock at the top left and the battery at the top right. Episode
/// coordinates are 1280 x 720, cover-fitted to the window.
@NotNullByDefault
final class SaoVisor {
    /// Not instantiable.
    private SaoVisor() {
    }

    /// Opaque alpha for a colour.
    private static int alpha(int rgb, float a) {
        return Math.round(255 * Math.max(0, Math.min(1, a))) << 24 | rgb & 0xffffff;
    }

    /// The ground the glass was measured over (the pack's dark loading grey).
    static final int GROUND = 0x252525;

    /// Renders the whole visor.
    ///
    /// @param width  pixels
    /// @param height pixels
    /// @param clock  whether to draw the clock and battery (the display is on)
    /// @return the raster
    static SaoRaster render(int width, int height, boolean clock) {
        SaoRaster c = new SaoRaster(width, height, GROUND);
        // Contain-fit: the clock and battery stay whole; the measured glass reaches past the picture on every side.
        os = Math.min(width / 1280f, height / 720f);
        ox = width / 2f - 640 * os;
        oy = height / 2f - 360 * os;
        glass(c, 1);
        arc(c, 1);
        recess(c, 1);
        if (clock) {
            clock(c, 1);
            battery(c, 1);
        }
        return c;
    }

    /// Renders the glass over the dark ground: the back of the space the windows float in.
    ///
    /// @param width  pixels
    /// @param height pixels
    /// @return the raster
    static SaoRaster renderGlass(int width, int height) {
        SaoRaster c = new SaoRaster(width, height, GROUND);
        fitTo(width, height);
        glass(c, 1);
        return c;
    }

    /// Renders the helmet in front of the windows on a see-through raster: the ear-pad arc, the recess, and with
    /// the display on the clock and the battery.
    ///
    /// @param width  pixels
    /// @param height pixels
    /// @param clock  whether the display is on
    /// @return the raster
    static SaoRaster renderFrame(int width, int height, boolean clock) {
        SaoRaster c = new SaoRaster(width, height);
        fitTo(width, height);
        // James, 7 Nov: "can u make this take up less space? by contracting it": the launcher's arc keeps the
        // episode's shape, squashed toward the bottom edge.
        squash = ARC_SQUASH;
        arc(c, 1);
        squash = 1;
        recess(c, 1);
        if (clock) {
            clock(c, 1);
            battery(c, 1);
        }
        return c;
    }

    /// Contain-fits the episode picture to a size.
    private static void fitTo(int width, int height) {
        os = Math.min(width / 1280f, height / 720f);
        ox = width / 2f - 640 * os;
        oy = height / 2f - 360 * os;
    }

    private static float ox,oy,os;
    /** How tall the bottom arc is drawn, 1 = as measured in the episode. */
    private static float squash=1;
    /** The launcher's arc height (James, 7 Nov: take up less space). */
    static final float ARC_SQUASH=0.45f;
    private static float X(float x){return ox+x*os;}
    private static float Y(float y){return oy+y*os;}
    private static int argb(int rgb,float a){return alpha(rgb,a);}
    private static int fade(int argb,float a){return Math.round((argb>>>24)*a)<<24|argb&0xffffff;}

    /** The helmet itself, fixed in the view (it moves with the head; the world behind the glass is what moves). */
    static void visor(SaoRaster c,int width,int height,float opacity){
        if(opacity<=0)return;
        os=Math.max(width/1280f,height/720f);ox=width/2f-640*os;oy=height/2f-360*os;
        glass(c,opacity);
        arc(c,opacity);
        recess(c,opacity);
        clock(c,opacity);
        battery(c,opacity);
    }
    /** The glass: one mesh on the measured 40 px grid, blended over the world behind it. */
    private static void glass(SaoRaster c,float a){
        int cols=SaoVisorData.GLASS_COLS,rows=SaoVisorData.GLASS_ROWS,n=0;
        float[] xy=new float[(cols-1)*(rows-1)*12];int[] ink=new int[(cols-1)*(rows-1)*6];
        int[] di={0,1,1,0,1,0},dj={0,0,1,0,1,1};
        for(int j=0;j+1<rows;j++)for(int i=0;i+1<cols;i++)for(int k=0;k<6;k++){
            int ci=i+di[k],cj=j+dj[k];
            xy[2*n]=X(-80+40*ci);xy[2*n+1]=Y(-80+40*cj);ink[n]=fade(SaoVisorData.GLASS[cj*cols+ci],a);n++;
        }
        c.mesh(xy,ink,n);
    }
    /** The arc at the bottom: bands below its measured edge in the episode's colours, with a fringe one device pixel wide above it. */
    private static void arc(SaoRaster c,float a){
        float[] edge=SaoVisorData.BOTTOM_EDGE,depth=SaoVisorData.DEPTHS;int[] rgb=SaoVisorData.BOTTOM_RGB;
        int cols=edge.length,rows=depth.length;
        // Rows: the fringe above the edge (transparent), then every depth, then a last row far below with the deepest colour.
        int bands=rows+1;float fringe=1/os;
        float[] xy=new float[(cols-1)*bands*12];int[] ink=new int[(cols-1)*bands*6];int n=0;
        for(int i=0;i+1<cols;i++){
            for(int b=0;b<bands;b++){
                float[] yy=new float[4];int[] cc=new int[4];
                for(int s=0;s<2;s++){
                    int ci=i+s;float e=720-(720-edge[ci])*squash;
                    // b = 0: fringe (e - fringe .. e); b >= 1: depth[b-1] .. depth[b] (or 900 for the last)
                    float top=b==0?e-fringe:e+depth[b-1]*squash,bottom=b==0?e:(b<rows?e+depth[b]*squash:900);
                    int ctop=b==0?0:argb(rgb[ci*rows+b-1],a),cbot=b==0?argb(rgb[ci*rows],a):argb(rgb[ci*rows+Math.min(rows-1,b)],a);
                    if(b==0)ctop=rgb[ci*rows]&0xffffff;
                    yy[s*2]=top;yy[s*2+1]=bottom;cc[s*2]=ctop;cc[s*2+1]=cbot;
                }
                float x0=X(-16+16*i),x1=X(16*i);
                float[] q={x0,Y(yy[0]),x1,Y(yy[2]),x1,Y(yy[3]),x0,Y(yy[0]),x1,Y(yy[3]),x0,Y(yy[1])};
                int[] qc={cc[0],cc[2],cc[3],cc[0],cc[3],cc[1]};
                System.arraycopy(q,0,xy,2*n,12);System.arraycopy(qc,0,ink,n,6);n+=6;
            }
        }
        c.mesh(xy,ink,n);
    }
    /** The inner edge of the recess at x, episode pixels. */
    private static float topY(float x){
        float[] t=SaoVisorData.TOP;float u=(x-t[0])/t[1];
        return t[2]+t[3]*(float)Math.sqrt(Math.max(0,1-u*u));
    }
    /**
     * The recess at the top: its dark inside, then along the normal of its edge the lit rim (4.69 px) and the dark line
     * outside it (2.12 px), each band with crisp edges and a fringe one device pixel wide toward the glass.
     */
    private static void recess(SaoRaster c,float a){
        float[] t=SaoVisorData.TOP;float rimW=SaoVisorData.RIM_W,lineW=SaoVisorData.LINE_W,fringe=1/os,reach=rimW+lineW+fringe;
        // The columns where the bands still reach into the picture.
        float x0=t[0],x1=t[0];
        while(x0>t[0]-t[1]+1&&topY(x0)+reach>-2)x0-=2;
        while(x1<t[0]+t[1]-1&&topY(x1)+reach>-2)x1+=2;
        int cols=Math.max(2,(int)Math.ceil((x1-x0)/6)+1);
        float[] px=new float[cols],py=new float[cols],nx=new float[cols],ny=new float[cols];
        for(int i=0;i<cols;i++){
            float x=x0+(x1-x0)*i/(cols-1),y=topY(x),d=.5f,sx=1,sy=topY(x+d)-topY(x-d);
            float l=(float)Math.sqrt(sx*sx*4*d*d+sy*sy);
            // Normal pointing out of the recess (down into the glass).
            px[i]=x;py[i]=y;nx[i]=-sy/l;ny[i]=2*d/l;
        }
        // The inside: from the edge up past the top of the picture (convex: the edge bows down).
        float[] in=new float[2*cols+4];
        for(int i=0;i<cols;i++){in[2*i]=X(px[i]);in[2*i+1]=Y(py[i]);}
        in[2*cols]=X(px[cols-1]);in[2*cols+1]=Y(-40);in[2*cols+2]=X(px[0]);in[2*cols+3]=Y(-40);
        c.polygon(argb(SaoVisorData.TOP_INSIDE,a),in);
        // The bands along the normal: rim, line, fringe.
        float[] off={0,rimW,rimW,rimW+lineW,rimW+lineW,reach};
        int[] ink={argb(SaoVisorData.TOP_RIM,a),argb(SaoVisorData.TOP_RIM,a),argb(SaoVisorData.TOP_LINE,a),argb(SaoVisorData.TOP_LINE,a),argb(SaoVisorData.TOP_LINE,a),SaoVisorData.TOP_LINE};
        float[] xy=new float[(cols-1)*3*12];int[] cc=new int[(cols-1)*3*6];int n=0;
        for(int i=0;i+1<cols;i++)for(int b=0;b<3;b++){
            float o0=off[2*b],o1=off[2*b+1];
            float ax=X(px[i]+nx[i]*o0),ay=Y(py[i]+ny[i]*o0),bx=X(px[i+1]+nx[i+1]*o0),by=Y(py[i+1]+ny[i+1]*o0);
            float cx=X(px[i+1]+nx[i+1]*o1),cy=Y(py[i+1]+ny[i+1]*o1),dx=X(px[i]+nx[i]*o1),dy=Y(py[i]+ny[i]*o1);
            float[] q={ax,ay,bx,by,cx,cy,ax,ay,cx,cy,dx,dy};int[] qc={ink[2*b],ink[2*b],ink[2*b+1],ink[2*b],ink[2*b+1],ink[2*b+1]};
            System.arraycopy(q,0,xy,2*n,12);System.arraycopy(qc,0,cc,n,6);n+=6;
        }
        c.mesh(xy,cc,n);
    }

    // ---- the clock (top left) and the battery (top right) ------------------------------------------------------------
    /** Measured on the episode's "12:59" / "13:00" (01:24.6, 01:25.8): pale figures with a thin darker edge and a soft rim. */
    private static final int FACE=0xa6b2b8,EDGE=0x46575d;
    /**
     * The figures: 27 units wide and 60 high, uprights 9, bars 8; the 2 and the 3 carry a short drop at the top left,
     * the 3 a short rise at the bottom left and a middle bar that stops short of the left; the 1 is an upright with a
     * slanted flag. Each of the four places has its own measured top-left corner, top, lean and height (the visor's
     * curve: the figures lean back more and stand taller toward the left).
     */
    private static final int[] SEGMENTS={0x3f,0x06,0x5b,0x4f,0x66,0x6d,0x7d,0x07,0x7f,0x6f};
    private static final float GW=27,GH=60,GV=9,GB=8;
    private static final float[] CELL_X={57,97,159,199},CELL_TOP={52,51,50.5f,49},CELL_LEAN={.29f,.26f,.21f,.18f},CELL_H={62,61.5f,60.5f,60};
    private static float cellX,cellTop,cellLean,cellScale;
    private static void cell(float x,float top,float lean,float h){cellX=x;cellTop=top;cellLean=lean;cellScale=h/GH;}
    private static void glyphPoly(SaoRaster c,int colour,float... uv){
        float[] xy=new float[uv.length];
        for(int i=0;i<uv.length;i+=2){float v=uv[i+1]*cellScale;xy[i]=X(cellX+uv[i]+v*cellLean);xy[i+1]=Y(cellTop+v);}
        c.polygon(colour,xy);
    }
    private static void glyphQuad(SaoRaster c,float u0,float v0,float u1,float v1,float o,int colour){
        glyphPoly(c,colour,u0-o,v0-o,u1+o,v0-o,u1+o,v1+o,u0-o,v1+o);
    }
    private static void digit(SaoRaster c,int d,float o,int colour){
        if(d==1){
            glyphQuad(c,9,0,17.5f,GH,o,colour);
            // Its flag: out to the left along the top, its underside rising toward the tip.
            glyphPoly(c,colour,-o,-o,9+o,-o,9+o,10+o,-o,5+o);
            return;
        }
        int bits=SEGMENTS[d];float mid=GH/2;
        if((bits&1)!=0)glyphQuad(c,0,0,GW,GB,o,colour);
        if((bits&2)!=0)glyphQuad(c,GW-GV,0,GW,mid+GB/2,o,colour);
        if((bits&4)!=0)glyphQuad(c,GW-GV,mid-GB/2,GW,GH,o,colour);
        if((bits&8)!=0)glyphQuad(c,0,GH-GB,GW,GH,o,colour);
        if((bits&16)!=0)glyphQuad(c,0,mid-GB/2,GV,GH,o,colour);
        if((bits&32)!=0)glyphQuad(c,0,0,GV,mid+GB/2,o,colour);
        if((bits&64)!=0)glyphQuad(c,d==3?7:0,mid-GB/2,GW,mid+GB/2,o,colour);
        if(d==2||d==3)glyphQuad(c,0,0,GV,14,o,colour);
        if(d==3)glyphQuad(c,0,GH-12,GV*.6f,GH,o,colour);
    }
    /** The wearer's own time of day, hours and minutes on 24 hours (the episode shows the time in the same place: 12:59, then 13:00). */
    private static void clock(SaoRaster c,float a){
        long ms=System.currentTimeMillis();
        long local=ms+java.util.TimeZone.getDefault().getOffset(ms);
        int minutes=(int)((local/60000)%1440);if(minutes<0)minutes+=1440;
        int[] d={minutes/600,minutes/60%10,minutes%60/10,minutes%10};
        // Three passes, as the episode's figures stand: a thin darker edge, a soft rim half a pixel out, the face.
        float[] grow={1.6f,.35f,0};int[] ink={argb(EDGE,a),argb(FACE,.5f*a),argb(FACE,a)};
        for(int pass=0;pass<3;pass++){
            for(int i=0;i<4;i++){cell(CELL_X[i],CELL_TOP[i],CELL_LEAN[i],CELL_H[i]);digit(c,d[i],grow[pass],ink[pass]);}
            cell(140,51,.24f,61);
            glyphQuad(c,0,6,8.5f,14,grow[pass],ink[pass]);
            glyphQuad(c,0,45,8.5f,53,grow[pass],ink[pass]);
        }
    }
    /** The battery's body corners (TL, TR, BR, BL), episode pixels: it stands in the visor's perspective. */
    private static final float[] BODY={1136.7f,51.7f,1230.7f,41,1226,83.3f,1132.3f,92.7f};
    private static final int CELL_LIGHT=0x95999d,CELL_LINE=0x3c4044,BOLT=0x1e2226;
    /** A point of the body by its shares u (left to right) and v (top to bottom). */
    private static float bu(float u,float v,int k){return (1-v)*((1-u)*BODY[k]+u*BODY[2+k])+v*((1-u)*BODY[6+k]+u*BODY[4+k]);}
    private static void bodyQuad(SaoRaster c,float u0,float v0,float u1,float v1,int colour){
        c.polygon(colour,X(bu(u0,v0,0)),Y(bu(u0,v0,1)),X(bu(u1,v0,0)),Y(bu(u1,v0,1)),X(bu(u1,v1,0)),Y(bu(u1,v1,1)),X(bu(u0,v1,0)),Y(bu(u0,v1,1)));
    }
    /** The battery, charging (measured on 01:25.8): a light body with a thin line inside its edge, the cap with a dark slit, a dark bolt. */
    private static void battery(SaoRaster c,float a){
        int light=argb(CELL_LIGHT,a),line=argb(CELL_LINE,a),bolt=argb(BOLT,a);
        // A soft rim one pixel out, then the body; the line 5 px inside its edge (in shares of its 91 x 43 px).
        float ru=1f/91,rv=1f/43;
        bodyQuad(c,-ru,-rv,1+ru,1+rv,argb(CELL_LIGHT,.4f*a));
        bodyQuad(c,0,0,1,1,light);
        float iu=5*ru,iv=5*rv,lu=2*ru,lv=2*rv;
        bodyQuad(c,iu,iv,1-iu,iv+lv,line);bodyQuad(c,iu,1-iv-lv,1-iu,1-iv,line);
        bodyQuad(c,iu,iv,iu+lu,1-iv,line);bodyQuad(c,1-iu-lu,iv,1-iu,1-iv,line);
        // The cap: a light block beyond the body's right edge, a dark slit between them.
        c.polygon(light,X(1228),Y(50.7f),X(1245),Y(49),X(1241),Y(72.7f),X(1226),Y(75));
        c.polygon(line,X(1227.5f),Y(57),X(1231),Y(56.5f),X(1230),Y(74.5f),X(1226.5f),Y(75));
        // The bolt (top tip, down to the left, a step right, the bottom tip, up to the right, a step left).
        float[] b={1191,51,1166,70.5f,1177.5f,69,1167,87,1192,65,1180.5f,66.5f};
        float[] xy=new float[12];for(int i=0;i<12;i+=2){xy[i]=X(b[i]);xy[i+1]=Y(b[i+1]);}
        c.polygon(bolt,xy);
    }
}
