/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.jackhuang.hmcl.sao;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.*;

/// Immutable account appearance sources; previews point only to public immutable ARGUS images in production.
/// @param linked whether the account server currently stores an independently proven Microsoft UUID
/// @param skinSource saved skin preference, or its automatic initial choice
/// @param capeSource saved cape preference, or its automatic initial choice
/// @param effectiveSkinSource available source currently used by the signed game profile
/// @param effectiveCapeSource available cape source currently used by the signed game profile
/// @param images available ownSkin, premiumSkin, ownCape, mojang, optifine and minecraftcapes previews
/// @param lastRefresh completion time of the last provider refresh, in epoch milliseconds, or zero
/// @param refreshAfter remaining manual cooldown in seconds, rounded upward by the server
/// @param status idle, syncing, synced or failed; labels are exposed by statusLabel
@NotNullByDefault
public record SaoTextureState(boolean linked,String skinSource,String capeSource,String effectiveSkinSource,
                              String effectiveCapeSource,@Unmodifiable Map<String,Image> images,long lastRefresh,
                              long refreshAfter,String status) {
    /// Immutable hash/model descriptor and public preview URI; fake samples use local PNG files.
    @NotNullByDefault
    public record Image(String hash,String model,URI url) {
        /// Validates content identity and arm model independently of image-loading UI.
        public Image {if(!hash.matches("[0-9a-f]{64}")||!Set.of("","slim").contains(model))throw new IllegalArgumentException("Invalid appearance image");}
    }
    /// Copies available images and validates all public state values before publication.
    public SaoTextureState {
        images=Map.copyOf(images);
        if(!Set.of("own","premium").contains(skinSource)||!Set.of("own","premium").contains(effectiveSkinSource)
                ||!Set.of("own","mojang","optifine","minecraftcapes","none").contains(capeSource)
                ||!Set.of("own","mojang","optifine","minecraftcapes","none").contains(effectiveCapeSource)
                ||!Set.of("ownSkin","premiumSkin","ownCape","mojang","optifine","minecraftcapes").containsAll(images.keySet())
                ||!Set.of("idle","syncing","synced","failed").contains(status)||lastRefresh<0||refreshAfter<0)
            throw new IllegalArgumentException("Invalid appearance state");
    }
    /// Cleared appearance state for signout, unsupported old services or a service without account support.
    public static SaoTextureState empty(){return new SaoTextureState(false,"own","own","own","none",Map.of(),0,0,"idle");}
    /// Concise game-style synchronization label, independent of arbitrary server diagnostics.
    public String statusLabel(){if(!linked)return "未绑定微软账号";return switch(status){case "synced"->"已同步";case "failed"->"同步失败";case "syncing"->"同步中";default->"未同步";};}
}
