/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.jetbrains.annotations.*;

/// Deterministic local appearance samples for frontend development; no account service or public image requests.
@NotNullByDefault
public final class SaoTextureSamples {
    /// Prevents construction of the fixture utility.
    private SaoTextureSamples() {}
    /// Writes six small local PNG previews with distinct colors and valid Minecraft skin/cape dimensions.
    public static @Unmodifiable Map<String,SaoTextureState.Image> images() {
        try {
            Path directory=Files.createTempDirectory("argus-appearance-samples-");directory.toFile().deleteOnExit();
            var values=new LinkedHashMap<String,SaoTextureState.Image>();int color=0;
            for(String source:List.of("ownSkin","premiumSkin","ownCape","mojang","optifine","minecraftcapes")){
                boolean skin=source.endsWith("Skin");int height=skin?64:32;var image=new BufferedImage(64,height,BufferedImage.TYPE_INT_ARGB);
                int primary=new int[]{0xff7c65bb,0xff36bdcf,0xffa96bd7,0xffca3643,0xff50a760,0xff4b79df}[color++];
                for(int y=0;y<height;y++)for(int x=0;x<64;x++)image.setRGB(x,y,((x/4+y/4)&1)==0?primary:0xffe6eef5);
                var output=new ByteArrayOutputStream();if(!ImageIO.write(image,"PNG",output))throw new IOException("Sample encoding failed");
                byte[] png=output.toByteArray();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));Path file=directory.resolve(source+".png");Files.write(file,png);file.toFile().deleteOnExit();
                values.put(source,new SaoTextureState.Image(hash,source.equals("premiumSkin")?"slim":"",file.toUri()));
            }return Map.copyOf(values);
        }catch(IOException|GeneralSecurityException failure){return Map.of();}
    }
}
