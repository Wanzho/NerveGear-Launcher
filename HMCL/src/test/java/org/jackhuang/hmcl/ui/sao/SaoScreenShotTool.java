/*
 * 刀剑神域·誓约 启动器, a fork of Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 * Licensed under the GNU General Public License v3 or later; see the LICENSE file.
 */
package org.jackhuang.hmcl.ui.sao;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import org.jackhuang.hmcl.sao.SaoPackState;
import org.jetbrains.annotations.NotNullByDefault;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/// Renders the SAO screen off-screen to PNGs, one per pack state, with no window, no focus and no sound
/// (run with `-Dglass.taskbarApplication=false -Dsao.mute=true`).
@NotNullByDefault
public final class SaoScreenShotTool {
    /// Not instantiable.
    private SaoScreenShotTool() {
    }

    /// Writes the shots.
    ///
    /// @param args the output folder, then optional width and height
    /// @throws Exception when rendering or writing fails
    public static void main(String[] args) {
        // Never hang on a failure: a stuck tool once held the shared build slot for three hours.
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    /// Writes every shot.
    ///
    /// @param args the output folder
    /// @throws Exception on any failure
    private static void run(String[] args) throws Exception {
        // A throwaway HMCL config (never ~/.sao-oath-launcher or ~/.hmcl), wiped each run so the first-start licence
        // shows, and no Microsoft app id so the sign-in dialog can never start a real sign-in or open a browser.
        Path dlgHome = Path.of(System.getProperty("user.home"), "Developer", "sao-launcher-runs", "logo45", "dlg-home");
        if (Files.exists(dlgHome)) try (var walk = Files.walk(dlgHome)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        }
        Files.createDirectories(dlgHome);
        System.setProperty("sao.home", dlgHome.resolve("user").toString());
        System.setProperty("sao.dir", dlgHome.resolve("local").toString());
        System.setProperty("sao.dependencies.dir", dlgHome.resolve("deps").toString());
        System.setProperty("hmcl.microsoft.auth.id", "");
        org.jackhuang.hmcl.setting.SettingsManager.init();
        if (!org.jackhuang.hmcl.Metadata.HMCL_USER_HOME.startsWith(dlgHome) || !org.jackhuang.hmcl.Metadata.HMCL_LOCAL_HOME.startsWith(dlgHome))
            throw new IllegalStateException("config is not in the throwaway folder");
        System.out.println("HMCL config in " + dlgHome + ", language " + org.jackhuang.hmcl.util.i18n.I18n.getLocale()
                + ": " + org.jackhuang.hmcl.util.i18n.I18n.i18n("button.ok"));
        Path base = Path.of(args.length > 0 ? args[0] : "sao-shots");
        int[][] sizes = {{1024, 576, 0}, {1920, 1080, 0}, {1920, 1080, 1}};
        Files.createDirectories(base);
        Platform.setImplicitExit(false);
        CountDownLatch up = new CountDownLatch(1);
        Platform.startup(up::countDown);
        up.await();
        System.out.println("SCENE3D supported: " + Platform.isSupported(javafx.application.ConditionalFeature.SCENE3D));
        // The real launcher's font setup before the first scene (Launcher.start): Chinese in mainland Simplified
        // forms (James, 8 Nov, and again 9 Nov: "怎么又犯之前修好的错误"). No shot without it.
        fx(SaoCjkFont::install);
        if (!SaoCjkFont.active()) throw new IllegalStateException("SaoCjkFont.install() could not patch the fonts");
        checkSimplified(base);
        // Never open a browser: About's link buttons go to an inert opener, and each must hand over its own link.
        java.util.List<String> opened = new java.util.ArrayList<>();
        SaoUi.linkOpener = opened::add;
        fx(() -> SaoApps.aboutLinks().getChildren().forEach(n -> ((javafx.scene.control.Button) n).fire()));
        if (!opened.equals(java.util.List.of(SaoUi.AFDIAN_URL, SaoUi.QUARK_URL)))
            throw new IllegalStateException("About links opened " + opened);
        System.out.println("About links OK: " + opened);
        // SAO_SHOTS=boot: the power-on and the Link Start hand-over at set times (James's boot, 13 Nov).
        if ("boot".equals(System.getenv("SAO_SHOTS"))) {
            shootBoot(base);
            Platform.exit();
            System.exit(0);
        }
        // SAO_SHOTS=motion: real-time films of the motion (James, 14 Nov: "可以给所有东西都加动效/动画吗").
        if ("motion".equals(System.getenv("SAO_SHOTS"))) {
            shootMotion(base);
            Platform.exit();
            System.exit(0);
        }
        // SAO_SHOTS=maint: only the 服务器 window's maintenance shots (maint1, 10 Nov).
        if ("maint".equals(System.getenv("SAO_SHOTS"))) {
            shootMaint(base);
            Platform.exit();
            System.exit(0);
        }
        // SAO_SHOTS=wire-dark: only the wire1 dark shots (a re-shoot after a dark-only colour fix).
        if ("wire-dark".equals(System.getenv("SAO_SHOTS"))) {
            shootWire(base, true);
            Platform.exit();
            System.exit(0);
        }
        checkThemes();
        shootDialogs(base);
        shootWire(base, false);
        // Every shot twice: 浅色 into the folder, 深色 (James, 9 Nov) into dark/.
        for (boolean dark : new boolean[]{false, true})
        for (int[] size : sizes) {
            Path out = dark ? base.resolve("dark") : base;
            Files.createDirectories(out);
            int w = size[0], h = size[1];
            boolean win = size[2] == 1, big = w > 1500 && !win;
            // The small size and the second big one show the Windows/Linux layout of the window buttons (top right),
            // the first big one the Mac's.
            System.setProperty("sao.windowButtons", big ? "left" : "right");
            SaoFakeService fake = new SaoFakeService();
            fake.setPreference("theme", dark ? "dark" : "light");
            SaoHelmetPage[] page = new SaoHelmetPage[1];
            Scene[] scene = new Scene[1];
            fx(() -> {
                page[0] = new SaoHelmetPage(fake);
                scene[0] = new Scene(page[0], w, h);
            });
            Thread.sleep(2500);
            String p = w + "x" + h + (win ? "-win-" : "-");
            if (win) {
                // Signed in, as on the Mac, with the buttons on the right.
                fx(() -> fake.forceOath(true));
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "21-home-signed-in.png"));
                fx(() -> page[0].forceNodes());
                Thread.sleep(500);
                WritableImage[] im = new WritableImage[1];
                fx(() -> im[0] = scene[0].snapshot(null));
                write(im[0], out.resolve(p + "40-server-nodes.png"));
                // The nickname edited in place: a free name, then a taken one.
                for (String[] t : new String[][]{{"Kirito_Black", "41-name-edit-free"}, {"Asuna", "42-name-edit-taken"}}) {
                    fx(() -> page[0].forceNameEdit(t[0]));
                    Thread.sleep(900);
                    fx(() -> im[0] = scene[0].snapshot(null));
                    write(im[0], out.resolve(p + t[1] + ".png"));
                }
                // 好友: a conversation, a group, adding a friend, a new group.
                Object[][] friendShots = {{"d-asuna", 0, false, false, "50-friends-chat"}, {"g-fuurinkazan", 1, false, false, "51-friends-group"},
                        {null, 0, true, false, "52-friends-add"}, {null, 1, false, true, "53-friends-new-group"}};
                for (Object[] f : friendShots) {
                    fx(() -> {
                        page[0].forceApp(-1);
                        SaoFriendsApp.openFirst = (String) f[0];
                        SaoFriendsApp.tabFirst = (Integer) f[1];
                        SaoFriendsApp.addFirst = (Boolean) f[2];
                        SaoFriendsApp.newGroupFirst = (Boolean) f[3];
                        page[0].forceApp(1);
                    });
                    Thread.sleep(1300);
                    fx(() -> im[0] = scene[0].snapshot(null));
                    write(im[0], out.resolve(p + f[4] + ".png"));
                }
                fx(() -> page[0].forceApp(-1));
                Thread.sleep(400);
                fx(() -> page[0].forceNameConfirm("Kirito_Black"));
                Thread.sleep(500);
                fx(() -> im[0] = scene[0].snapshot(null));
                write(im[0], out.resolve(p + "43-name-confirm.png"));
            } else if (big) {
                String[] apps = {"settings", "friends", "skin", "worlds", "multiplayer", "packs", "screenshots", "logs", "java", "downloads", "about"};
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "00-home.png"));
                for (int i = 0; i < apps.length; i++) {
                    shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, i, out.resolve(p + String.format("%02d-%s.png", i + 1, apps[i])));
                }
                // The skin editor over the helmet, then turned as a drag 80 px to the right would turn it.
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, 2, out.resolve(p + "60-skin-library.png"));
                fx(() -> SaoSkinApp.forceEdit.run());
                Thread.sleep(700);
                WritableImage[] ed = new WritableImage[1];
                fx(() -> ed[0] = scene[0].snapshot(null));
                write(ed[0], out.resolve(p + "61-skin-editor.png"));
                fx(() -> SaoSkinApp.forceTurn(80));
                Thread.sleep(300);
                fx(() -> ed[0] = scene[0].snapshot(null));
                write(ed[0], out.resolve(p + "62-skin-editor-dragged-right.png"));
                fx(SaoHelmetPage::uncover);
                Thread.sleep(300);
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, 0, out.resolve(p + "20-settings-uninstall.png"));
                fx(() -> page[0].forceDanger());
                Thread.sleep(500);
                WritableImage[] img = new WritableImage[1];
                fx(() -> img[0] = scene[0].snapshot(null));
                write(img[0], out.resolve(p + "20-settings-uninstall.png"));
                // The battery, as the computer reports it: charging, on battery, and low (red).
                int[][] batt = {{85, 1}, {60, 0}, {15, 0}, {12, 1}};
                for (int[] b : batt) {
                    SaoBattery.now = new SaoBattery.State(b[0], b[1] == 1);
                    SaoBattery.version++;
                    shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1,
                            out.resolve(p + "30-battery-" + b[0] + (b[1] == 1 ? "-charging" : "") + ".png"));
                }
                SaoBattery.now = new SaoBattery.State(100, true);
                SaoBattery.version++;
                // Signed in: 确定退出吗？; then signed out, the ARGUS ID form and the 正版 tab.
                fx(() -> fake.forceOath(true));
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "tmp.png"));
                fx(() -> page[0].forceConfirmOut());
                Thread.sleep(400);
                fx(() -> img[0] = scene[0].snapshot(null));
                write(img[0], out.resolve(p + "31-confirm-logout.png"));
                fx(() -> {
                    fake.remembered = null;
                    fake.oathLogout();
                });
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "32-signed-out.png"));
                // The password typed (its dots), then the remembered one's prompt, then 离线游玩 in the right column.
                fx(() -> page[0].forceSignIn("Kirito_07", "linkstart"));
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "33-signed-out-typed.png"));
                fx(() -> {
                    fake.remembered = "Kirito_07";
                    page[0].forceSignIn("", "");
                    page[0].forceSignIn("Kirito_07", "");
                });
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "34-signed-out-remembered.png"));
                fx(() -> page[0].forceOffline(true));
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "35-offline.png"));
                fx(() -> {
                    page[0].forceOffline(false);
                    page[0].forceSignIn("", "");
                    fake.remembered = null;
                });
                // 誓约账号: signed in (verified, then waiting for the code), and each page of its window.
                fx(() -> fake.forceOath(true));
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "21-home-signed-in.png"));
                // 好友在线 on one line, as many as fit and then +N (James, 9 Nov), with 0, 2, 5 and 30 friends online.
                for (int n : new int[]{0, 2, 5, 30}) {
                    fx(() -> fake.forceFriends(n));
                    shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "21-home-friends-" + n + ".png"));
                    fx(() -> {
                        var line = page[0].friendsLine;
                        System.out.println("friends " + n + ": " + (line == null ? "no line (没有好友在线)"
                                : "shown " + line.shown + ", +N " + (line.more.isVisible() ? ((javafx.scene.text.Text)
                                ((javafx.scene.layout.HBox) line.more).getChildren().get(0)).getText() : "hidden")));
                    });
                }
                // +N opens 好友.
                fx(() -> {
                    var line = page[0].friendsLine;
                    if (line == null || !line.more.isVisible()) {
                        System.out.println("+N check FAILED: no +N with 30 friends");
                        return;
                    }
                    line.more.getOnMouseClicked().handle(null);
                    System.out.println("+N clicked");
                });
                Thread.sleep(1300);
                fx(() -> img[0] = scene[0].snapshot(null));
                write(img[0], out.resolve(p + "21-home-friends-more-clicked.png"));
                fx(() -> page[0].forceApp(-1));
                // 公告 in their own window (James, 9 Nov): the long 11-09 entry, then a short one replacing it.
                for (int k : new int[]{0, 3}) {
                    fx(() -> page[0].openNews(fake.news().get(k)));
                    Thread.sleep(1300);
                    fx(() -> img[0] = scene[0].snapshot(null));
                    write(img[0], out.resolve(p + (k == 0 ? "70-news-long.png" : "71-news-short.png")));
                }
                fx(() -> page[0].forceApp(-1));
                fx(() -> fake.forceFriends(2));
                // 外观 switches live: the same page turned to the other look without a rebuild.
                fx(() -> page[0].setDark(!dark));
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "21-home-live-switched.png"));
                fx(() -> page[0].setDark(dark));
                fx(() -> fake.forceOath(false));
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "22-home-verify-pending.png"));
                SaoAccountApp.Page[] pages = {SaoAccountApp.Page.REGISTER, SaoAccountApp.Page.VERIFY, SaoAccountApp.Page.FORGOT, SaoAccountApp.Page.MANAGE};
                for (int i = 0; i < pages.length; i++) {
                    SaoAccountApp.Page pg = pages[i];
                    shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "tmp.png"));
                    fx(() -> page[0].forceAccount(pg));
                    Thread.sleep(1200);
                    fx(() -> img[0] = scene[0].snapshot(null));
                    write(img[0], out.resolve(p + String.format("%02d-account-%s.png", 23 + i, pg.name().toLowerCase(java.util.Locale.ROOT))));
                }
                Files.deleteIfExists(out.resolve(p + "tmp.png"));
                // 微软正版登录 (James 13 Nov: HMCL's dialog replaced): every state of a sign-in.
                org.jackhuang.hmcl.sao.SaoMicrosoftFlow.State[] ms = {
                        new org.jackhuang.hmcl.sao.SaoMicrosoftFlow.State(org.jackhuang.hmcl.sao.SaoMicrosoftFlow.Kind.BROWSER, "https://login.live.com/oauth20_authorize.srf?client_id=x", "", "", "", "", ""),
                        new org.jackhuang.hmcl.sao.SaoMicrosoftFlow.State(org.jackhuang.hmcl.sao.SaoMicrosoftFlow.Kind.DEVICE_CODE, "", "J46BA8HC", "https://www.microsoft.com/link", "", "", ""),
                        new org.jackhuang.hmcl.sao.SaoMicrosoftFlow.State(org.jackhuang.hmcl.sao.SaoMicrosoftFlow.Kind.SIGNING_IN, "", "", "", "正在验证 Xbox 账号", "", ""),
                        new org.jackhuang.hmcl.sao.SaoMicrosoftFlow.State(org.jackhuang.hmcl.sao.SaoMicrosoftFlow.Kind.FAILED, "", "", "", "", "", "这个微软账号还没有购买 Minecraft"),
                        new org.jackhuang.hmcl.sao.SaoMicrosoftFlow.State(org.jackhuang.hmcl.sao.SaoMicrosoftFlow.Kind.DONE, "", "", "", "", "Wzho", "")};
                String[] msNames = {"browser", "device", "signing", "failed", "done"};
                for (boolean d : new boolean[]{false, true}) {
                    fx(() -> page[0].setDark(d));
                    for (int i = 0; i < ms.length; i++) {
                        org.jackhuang.hmcl.sao.SaoMicrosoftFlow.State st = ms[i];
                        shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, -1, out.resolve(p + "tmp.png"));
                        fx(() -> page[0].openMicrosoft(() -> new FixedFlow(st)));
                        Thread.sleep(1000);
                        fx(() -> img[0] = scene[0].snapshot(null));
                        write(img[0], out.resolve(p + String.format("%02d-microsoft-%s-%s.png", 40 + i, msNames[i], d ? "dark" : "light")));
                    }
                }
                fx(() -> page[0].setDark(dark));
                Files.deleteIfExists(out.resolve(p + "tmp.png"));
            } else {
                shot(scene[0], page[0], fake, SaoPackState.WORKING, 0.42, "安装 NeoForge", 0, 0, -1, out.resolve(p + "01-home-installing.png"));
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, 0, out.resolve(p + "10-app-settings.png"));
            }
        }
        Platform.exit();
        System.exit(0);
    }

    /// A sign-in that stays in one state (the 微软正版登录 shots).
    private record FixedFlow(org.jackhuang.hmcl.sao.SaoMicrosoftFlow.State fixed) implements org.jackhuang.hmcl.sao.SaoMicrosoftFlow {
        @Override public javafx.beans.property.ReadOnlyObjectProperty<State> state() { return new javafx.beans.property.SimpleObjectProperty<>(fixed); }
        @Override public void openBrowser() { }
        @Override public void useDeviceCode() { }
        @Override public void cancel() { }
    }

    /// Puts the page in a state, an app and a head turn, waits for the windows to settle and writes a shot.
    /// The power-on and the Link Start hand-over as a frame sequence (25 fps of animation time) at 1920 x 1080, for a
    /// review film: power-on, a short rest, Link Start, the loading row (its long wait shown faster), the hand-over.
    private static void shootBoot(Path base) throws Exception {
        Path out = base.resolve("boot");
        if (Files.exists(out)) try (var walk = Files.walk(out)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        }
        Files.createDirectories(out);
        System.setProperty("sao.windowButtons", "left");
        SaoFakeService fake = new SaoFakeService();
        fake.setPreference("theme", "light");
        fake.forceInstalled("0.8.1");
        SaoHelmetPage[] page = new SaoHelmetPage[1];
        Scene[] scene = new Scene[1];
        fx(() -> {
            page[0] = new SaoHelmetPage(fake);
            scene[0] = new Scene(page[0], 1920, 1080);
        });
        Thread.sleep(3500);
        fx(() -> fake.forceOath(true));
        fx(() -> page[0].debugCloseApp());
        Thread.sleep(900);
        WritableImage[] im = new WritableImage[1];
        int[] n = {0};
        java.util.function.Consumer<Runnable> frame = r -> {
            try {
                fx(r);
                Thread.sleep(120);
                fx(() -> im[0] = scene[0].snapshot(null));
                write(im[0], out.resolve(String.format("f%04d.png", n[0]++)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        for (double t = 0; t <= SaoBoot.OPEN_MS + 1; t += 40) {
            double tt = t;
            frame.accept(() -> page[0].boot().debugOpening(tt));
        }
        for (int i = 0; i < 25; i++) frame.accept(() -> { });
        // Link Start: the put-away in real time, the loading row's wait compressed, then the hand-over in real time.
        for (double u = 0; u <= 1300; u += 40) {
            double uu = u;
            frame.accept(() -> page[0].boot().debugPutAway(uu, Math.min(0.06, uu / 20000), -1));
        }
        for (double u = 1300; u <= 8000; u += 200) {
            double uu = u, pr = 0.06 + 0.84 * (u - 1300) / 6700;
            frame.accept(() -> page[0].boot().debugPutAway(uu, pr, -1));
        }
        for (double u = 8000; u <= 8700; u += 40) {
            double uu = u;
            frame.accept(() -> page[0].boot().debugPutAway(uu, 0.9, 8000));
        }
    }

    private static void shot(Scene scene, SaoHelmetPage page, SaoFakeService fake, SaoPackState st, double p, String label,
                             double hx, double hy, int app, Path file) throws Exception {
        fx(() -> {
            fake.force(st, p, label, null);
            page.forceLit();
            page.forceApp(-1);
            page.forceHead(hx, hy);
            if (app >= 0) page.forceApp(app);
        });
        Thread.sleep(app >= 0 ? 1200 : 700);
        WritableImage[] img = new WritableImage[1];
        fx(() -> img[0] = scene.snapshot(null));
        write(img[0], file);
    }

    /// Draws 置 直 设 in the SAO face (the launcher's text) and compares each with PingFang SC and with the Japanese
    /// Hiragino Sans; fails unless they match PingFang. Writes `font-check.png` (rows: SAO, PingFang SC, Hiragino).
    ///
    /// @param out the shot folder
    /// @throws Exception when they do not match or rendering fails
    private static void checkSimplified(Path out) throws Exception {
        String[] chars = {"置", "直", "设", "置"};
        javafx.scene.text.Font[] fonts = {SaoAssets.sao(48, false), new javafx.scene.text.Font("PingFang SC Regular", 48),
                new javafx.scene.text.Font("Hiragino Sans W3", 48)};
        WritableImage[][] img = new WritableImage[fonts.length][chars.length];
        fx(() -> {
            for (int f = 0; f < fonts.length; f++)
                for (int c = 0; c < chars.length; c++) {
                    javafx.scene.text.Text t = new javafx.scene.text.Text(chars[c]);
                    t.setFont(fonts[f]);
                    t.setFontSmoothingType(javafx.scene.text.FontSmoothingType.GRAY);
                    t.setTextOrigin(javafx.geometry.VPos.TOP);
                    javafx.scene.layout.Pane p = new javafx.scene.layout.Pane(t);
                    p.setStyle("-fx-background-color: white;");
                    new Scene(p, 64, 64);
                    img[f][c] = p.snapshot(null, new WritableImage(64, 64));
                }
        });
        System.out.println("font faces: " + fonts[1].getName() + " / " + fonts[2].getName());
        if (!fonts[1].getName().startsWith("PingFang")) throw new IllegalStateException("PingFang SC not loaded: " + fonts[1].getName());
        BufferedImage sheet = new BufferedImage(64 * chars.length, 64 * fonts.length, BufferedImage.TYPE_INT_RGB);
        for (int f = 0; f < fonts.length; f++)
            for (int c = 0; c < chars.length; c++) {
                PixelReader r = img[f][c].getPixelReader();
                for (int y = 0; y < 64; y++)
                    for (int x = 0; x < 64; x++) sheet.setRGB(c * 64 + x, f * 64 + y, r.getArgb(x, y));
            }
        ImageIO.write(sheet, "png", out.resolve("font-check.png").toFile());
        for (int c = 0; c < chars.length; c++) {
            double vsPingFang = diff(img[0][c], img[1][c]), vsHiragino = diff(img[0][c], img[2][c]);
            System.out.printf("font %s: differs from PingFang SC %.4f, from Hiragino Sans %.4f%n", chars[c], vsPingFang, vsHiragino);
            if (vsPingFang > 0.02 || vsPingFang * 3 > vsHiragino)
                throw new IllegalStateException(chars[c] + " is not drawn in PingFang SC (Simplified forms)");
        }
        System.out.println("font check OK: Simplified forms");
    }

    /// HMCL's own pop-ups (James, 9 Nov: "所有hmcl可能会跳的弹窗都restyle了吗"), each in 浅色 and 深色, as the real window
    /// shows them: in-window dialogs through DialogUtils over the helmet with HMCL's root.css and sao-hmcl.css, and the
    /// start-up alert's panel. Nothing here signs in, downloads or opens a window.
    private static void shootDialogs(Path base) throws Exception {
        Path out = base.resolve("dialogs");
        Files.createDirectories(out);
        for (boolean dark : new boolean[]{false, true}) {
            SaoFakeService fake = new SaoFakeService();
            fake.setPreference("theme", dark ? "dark" : "light");
            fake.forceOath(true);
            SaoHelmetPage[] page = new SaoHelmetPage[1];
            Scene[] scene = new Scene[1];
            fx(() -> {
                page[0] = new SaoHelmetPage(fake);
                scene[0] = new Scene(new javafx.scene.layout.StackPane(page[0]), 1920, 1080);
                for (String css : new String[]{"/assets/css/font.css", "/assets/css/root.css", "/assets/css/sao-hmcl.css"})
                    scene[0].getStylesheets().add(java.util.Objects.requireNonNull(SaoScreenShotTool.class.getResource(css)).toExternalForm());
                scene[0].getStylesheets().add(SaoDialogs.fontSheet());
                page[0].setDark(dark);
            });
            Thread.sleep(2000);
            String tag = dark ? "dark-" : "light-";
            java.util.List<Object[]> panes = new java.util.ArrayList<>();
            fx(() -> {
                panes.add(new Object[]{"agreement", agreementPane()});
                panes.add(new Object[]{"warning-software-rendering", new org.jackhuang.hmcl.ui.construct.MessageDialogPane.Builder(
                        org.jackhuang.hmcl.util.i18n.I18n.i18n("warning.software_rendering"), org.jackhuang.hmcl.util.i18n.I18n.i18n("message.warning"),
                        org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.WARNING).ok(null)
                        .addCancel(org.jackhuang.hmcl.util.i18n.I18n.i18n("button.do_not_show_again"), () -> { }).build()});
                panes.add(new Object[]{"info-cache-directory", new org.jackhuang.hmcl.ui.construct.MessageDialogPane.Builder(
                        org.jackhuang.hmcl.util.i18n.I18n.i18n("launcher.cache_directory.invalid"), null,
                        org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.INFO).ok(null).build()});
                panes.add(new Object[]{"error", new org.jackhuang.hmcl.ui.construct.MessageDialogPane.Builder(
                        "Microsoft 登录失败，请重试", org.jackhuang.hmcl.util.i18n.I18n.i18n("message.error"),
                        org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.ERROR).ok(null).build()});
                panes.add(new Object[]{"question-unsupported-platform", new org.jackhuang.hmcl.ui.construct.MessageDialogPane.Builder(
                        org.jackhuang.hmcl.util.i18n.I18n.i18n("fatal.unsupported_platform"), null,
                        org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.WARNING).ok(null).build()});
                // Microsoft sign-in: the first step, and the device-code step with a made-up code (no app id is set,
                // so no sign-in task can start; the step is set directly).
                // The pane is built with no app id (step Init, so no sign-in task can start); then a fake id is set
                // only while the step is redrawn (an empty id draws an empty body), and cleared again at once.
                for (String[] want : new String[][]{{"microsoft-login", "Init"}, {"microsoft-login-device-code", "WaitForScanQrCode"}}) {
                    try {
                        var ms = new org.jackhuang.hmcl.ui.account.MicrosoftAccountLoginPane(null, a -> { }, () -> { }, false);
                        java.lang.reflect.Field f = ms.getClass().getDeclaredField("step");
                        f.setAccessible(true);
                        Class<?> k = Class.forName("org.jackhuang.hmcl.ui.account.MicrosoftAccountLoginPane$Step$" + want[1]);
                        var ctor = k.getDeclaredConstructors()[0];
                        ctor.setAccessible(true);
                        Object st = ctor.getParameterCount() == 2
                                ? ctor.newInstance("SAO-0807", "https://www.microsoft.com/link") : ctor.newInstance();
                        @SuppressWarnings("unchecked")
                        var step = (javafx.beans.property.ObjectProperty<Object>) f.get(ms);
                        System.setProperty("hmcl.microsoft.auth.id", "sao-shot-fake-id");
                        try {
                            step.set(st);
                        } finally {
                            System.setProperty("hmcl.microsoft.auth.id", "");
                        }
                        panes.add(new Object[]{want[0], ms});
                    } catch (ReflectiveOperationException e) {
                        System.out.println(want[0] + " step FAILED (unverified): " + e);
                    }
                }
                var task = new org.jackhuang.hmcl.ui.construct.TaskExecutorDialogPane(org.jackhuang.hmcl.util.TaskCancellationAction.NORMAL);
                task.setTitle(org.jackhuang.hmcl.util.i18n.I18n.i18n("modpack.installing"));
                // Three made-up rows: tasks that only report a fixed progress and wait (no files, no network);
                // they are cancelled when the dialog closes.
                var fakeExec = org.jackhuang.hmcl.task.Task.allOf(
                        new FakeTask("下载 刀剑神域·誓约 整合包", 0.82),
                        new FakeTask("下载 NeoForge 47.1.106", 0.46),
                        new FakeTask("下载 资源文件 (1234/5120)", 0.24)).executor(false);
                task.setExecutor(fakeExec, false);
                fakeExec.start();
                fakeExecs.add(fakeExec);
                panes.add(new Object[]{"task-progress", task});
            });
            // The dialogs open through HMCL's own path (DialogUtils into the window's dialog container), scaled with
            // the SAO windows as in the launcher.
            javafx.scene.layout.StackPane[] root = new javafx.scene.layout.StackPane[1];
            fx(() -> {
                root[0] = (javafx.scene.layout.StackPane) scene[0].getRoot();
                SaoDialogs.scaleDialogs(root[0]);
            });
            // Wait for the helmet's first frames before the first dialog (the licence comes first).
            Thread.sleep(2500);
            fx(() -> scene[0].snapshot(null));
            Thread.sleep(800);
            for (Object[] pn : panes) {
                javafx.scene.Node node = (javafx.scene.Node) pn[1];
                fx(() -> org.jackhuang.hmcl.ui.DialogUtils.show(root[0], node));
                Thread.sleep(1500);
                WritableImage[] im = new WritableImage[1];
                fx(() -> im[0] = scene[0].snapshot(null));
                write(im[0], out.resolve(tag + pn[0] + ".png"));
                System.out.println("dialog shot: " + tag + pn[0]);
                fx(() -> node.fireEvent(new org.jackhuang.hmcl.ui.construct.DialogCloseEvent()));
                Thread.sleep(900);
            }
            for (var ex : fakeExecs) ex.cancel();
            fakeExecs.clear();
            // A start-up alert (its own window in the launcher; here its panel over the helmet).
            fx(() -> {
                SaoDialogs.setDark(dark);
                javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING,
                        org.jackhuang.hmcl.util.i18n.I18n.i18n("fatal.config_unsupported_version"));
                a.setHeaderText(null);
                a.setGraphic(null);
                javafx.scene.control.DialogPane dp = a.getDialogPane();
                SaoDialogs.stylePane(dp);
                dp.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
                javafx.scene.layout.StackPane r = new javafx.scene.layout.StackPane(page[0], dp);
                scene[0].setRoot(r);
                page[0].setDark(dark);
            });
            Thread.sleep(800);
            WritableImage[] im = new WritableImage[1];
            fx(() -> im[0] = scene[0].snapshot(null));
            write(im[0], out.resolve(tag + "startup-alert.png"));
            System.out.println("dialog shot: " + tag + "startup-alert");
        }
    }

    /// The licence dialog exactly as Controllers.initialize builds it on the first start.
    private static com.jfoenix.controls.JFXDialogLayout agreementPane() {
        com.jfoenix.controls.JFXDialogLayout agreementPane = new com.jfoenix.controls.JFXDialogLayout();
        agreementPane.setHeading(new javafx.scene.control.Label(org.jackhuang.hmcl.util.i18n.I18n.i18n("launcher.agreement")));
        agreementPane.setBody(new javafx.scene.control.Label(org.jackhuang.hmcl.util.i18n.I18n.i18n("launcher.agreement.hint")));
        org.jackhuang.hmcl.ui.construct.JFXHyperlink link = new org.jackhuang.hmcl.ui.construct.JFXHyperlink(
                org.jackhuang.hmcl.util.i18n.I18n.i18n("launcher.agreement"));
        com.jfoenix.controls.JFXButton yes = new com.jfoenix.controls.JFXButton(org.jackhuang.hmcl.util.i18n.I18n.i18n("launcher.agreement.accept"));
        yes.getStyleClass().add("dialog-accept");
        com.jfoenix.controls.JFXButton no = new com.jfoenix.controls.JFXButton(org.jackhuang.hmcl.util.i18n.I18n.i18n("launcher.agreement.decline"));
        no.getStyleClass().add("dialog-cancel");
        agreementPane.setActions(link, yes, no);
        return agreementPane;
    }

    /// 外观 (James, 9 Nov: "按跟随系统/时间来定"): 跟随系统 follows the system, and 按时间 (19:00 to 07:00 dark) when
    /// the system does not say; saved 浅色 / 深色 keep working; unknown values are 跟随系统.
    private static void checkThemes() {
        java.time.LocalTime nine = java.time.LocalTime.of(21, 0), ten = java.time.LocalTime.of(10, 0);
        Object[][] cases = {
                {SaoTheme.Mode.SYSTEM, true, ten, true, "system dark at 10:00 -> dark"},
                {SaoTheme.Mode.SYSTEM, false, nine, false, "system light at 21:00 -> light"},
                {SaoTheme.Mode.SYSTEM, null, nine, true, "system unknown at 21:00 -> dark"},
                {SaoTheme.Mode.SYSTEM, null, ten, false, "system unknown at 10:00 -> light"},
                {SaoTheme.Mode.TIME, null, java.time.LocalTime.of(6, 59), true, "time 06:59 -> dark"},
                {SaoTheme.Mode.TIME, null, java.time.LocalTime.of(7, 0), false, "time 07:00 -> light"},
                {SaoTheme.Mode.TIME, null, java.time.LocalTime.of(19, 0), true, "time 19:00 -> dark"},
                {SaoTheme.Mode.of("light"), true, nine, false, "saved light -> light"},
                {SaoTheme.Mode.of("dark"), false, ten, true, "saved dark -> dark"},
        };
        for (Object[] c : cases) {
            boolean got = SaoTheme.decide((SaoTheme.Mode) c[0], (Boolean) c[1], (java.time.LocalTime) c[2]);
            if (got != (Boolean) c[3]) throw new IllegalStateException("theme check failed: " + c[4]);
            System.out.println("theme OK: " + c[4]);
        }
        if (SaoTheme.Mode.of("whatever") != SaoTheme.Mode.SYSTEM) throw new IllegalStateException("default is not 跟随系统");
        System.out.println("theme: this machine's system dark mode = " + SaoTheme.systemDark()
                + ", logo for 浅色 = " + SaoAssets.logoFile(false) + ", for 深色 = " + SaoAssets.logoFile(true));
    }

    /// How different two glyphs are: each is aligned by its ink box (fonts place the baseline differently), then the
    /// share of inked pixels whose grey differs by more than 64.
    private static double diff(WritableImage a, WritableImage b) {
        PixelReader ra = a.getPixelReader(), rb = b.getPixelReader();
        int[] ba = ink(ra), bb = ink(rb);
        int w = Math.max(ba[2] - ba[0], bb[2] - bb[0]) + 1, h = Math.max(ba[3] - ba[1], bb[3] - bb[1]) + 1, n = 0, inked = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int pa = grey(ra, ba[0] + x, ba[1] + y), pb = grey(rb, bb[0] + x, bb[1] + y);
                if (pa < 128 || pb < 128) inked++;
                if (Math.abs(pa - pb) > 64) n++;
            }
        return inked == 0 ? 1 : n / (double) inked;
    }

    /// The ink box of a 64 x 64 glyph: x0, y0, x1, y1.
    private static int[] ink(PixelReader r) {
        int x0 = 64, y0 = 64, x1 = -1, y1 = -1;
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++)
                if (grey(r, x, y) < 128) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
        if (x1 < 0) throw new IllegalStateException("a glyph drew nothing");
        return new int[]{x0, y0, x1, y1};
    }

    /// The grey at a point, white outside the 64 x 64 cell.
    private static int grey(PixelReader r, int x, int y) {
        return x < 0 || y < 0 || x >= 64 || y >= 64 ? 255 : r.getArgb(x, y) & 0xff;
    }

    /// Runs a job on the JavaFX thread and waits for it.
    ///
    /// @param r the job
    /// @throws InterruptedException when interrupted
    private static void fx(Runnable r) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                r.run();
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("FX thread stuck");
    }

    /// Writes a JavaFX image as PNG.
    ///
    /// @param img  the image
    /// @param file the file
    /// @throws Exception when writing fails
    private static void write(WritableImage img, Path file) throws Exception {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader r = img.getPixelReader();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) b.setRGB(x, y, r.getArgb(x, y));
        ImageIO.write(b, "png", file.toFile());
    }

    private static final java.util.List<org.jackhuang.hmcl.task.TaskExecutor> fakeExecs = new java.util.ArrayList<>();

    /** A task row for the shots: shows a fixed progress, then waits until cancelled. */
    private static final class FakeTask extends org.jackhuang.hmcl.task.Task<Object> {
        private final double progress;

        FakeTask(String name, double progress) {
            this.progress = progress;
            setName(name);
        }

        @Override
        public void execute() throws Exception {
            updateProgress(progress);
            Thread.sleep(120_000);
        }
    }

    /// wire1 (10 Nov): the backend's pack, quick-update, launcher and progress states through the real screens, each
    /// in 浅色 and 深色, 1920 × 1080, into `wire/`. The fake stands in for the backend; nothing installs, downloads
    /// or opens a browser.
    private static void shootWire(Path base, boolean darkOnly) throws Exception {
        Path out = base.resolve("wire");
        Files.createDirectories(out);
        String first = "请把在夸克里下载的模组文件拖进来";
        Path cand = Path.of(System.getProperty("user.home"), "Downloads", "SAO OATH0.8.2.zip");
        for (boolean dark : darkOnly ? new boolean[]{true} : new boolean[]{false, true}) {
            String tag = dark ? "dark-" : "light-";
            System.setProperty("sao.windowButtons", "left");
            SaoFakeService fake = new SaoFakeService();
            fake.setPreference("theme", dark ? "dark" : "light");
            org.jackhuang.hmcl.sao.SaoLauncherService svc = org.jackhuang.hmcl.sao.SaoExtrasSamples.decorate(fake);
            SaoHelmetPage[] page = new SaoHelmetPage[1];
            Scene[] scene = new Scene[1];
            fx(() -> {
                fake.forceOath(true);
                page[0] = new SaoHelmetPage(svc);
                scene[0] = new Scene(page[0], 1920, 1080);
            });
            Thread.sleep(2500);
            fx(() -> scene[0].snapshot(null));
            Thread.sleep(600);
            var noMeta = new org.jackhuang.hmcl.sao.SaoData.UpdateStatus(null, false, false, false, "");
            var noLauncher = new org.jackhuang.hmcl.sao.SaoData.LauncherUpdate("", false, "");
            var meta82 = SaoFakeService.meta("0.8.2", "0.1.0", "0.1.0", false);
            var idle = org.jackhuang.hmcl.sao.SaoInstallProgress.phase(org.jackhuang.hmcl.sao.SaoInstallProgress.Stage.IDLE, "");

            // 1-4: first run, a Downloads find, checking a drop, a drop that failed.
            Object[][] packShots = {
                    {"01-first-run", null, null},
                    {"02-candidate-found", cand, null},
                    {"03-verifying", cand, "pending"},
                    {"04-verify-failed", null, "文件不完整或不是这个版本，请重新下载"}};
            for (Object[] ps : packShots) {
                fx(() -> {
                    page[0].forceApp(-1);
                    fake.force(org.jackhuang.hmcl.sao.SaoPackState.NOT_INSTALLED, -1, "", null);
                    fake.forceInstall(idle);
                    fake.forceUpdates(new org.jackhuang.hmcl.sao.SaoData.UpdateStatus(meta82, true, false, false, ""),
                            new org.jackhuang.hmcl.sao.SaoData.DownloadPrompt(true, (Path) ps[1], first, java.util.List.of(),
                                    java.util.List.of(), java.util.List.of(), true), noLauncher);
                    fake.dropOutcome = (String) ps[2];
                });
                Thread.sleep(300);
                fx(() -> page[0].openPack(SaoPackApp.Mode.PACK));
                Thread.sleep(900);
                if (ps[2] != null) fx(() -> SaoPackApp.accept(svc, java.util.List.of(cand)));
                wireShot(scene[0], out.resolve(tag + ps[0] + ".png"));
                if (fake.pendingDrop != null) fx(() -> {
                    fake.pendingDrop.complete(null);
                    fake.pendingDrop = null;
                });
            }
            // 5: quick updates, [0.8.1] in, [0.8.2] and [0.8.3] still missing.
            fx(() -> {
                page[0].forceApp(-1);
                fake.dropOutcome = null;
                fake.force(org.jackhuang.hmcl.sao.SaoPackState.READY, 1, "", null);
                fake.forceInstalled("0.8");
                fake.forceUpdates(new org.jackhuang.hmcl.sao.SaoData.UpdateStatus(SaoFakeService.meta("0.8.3", "0.1.0", "0.1.0", false), true, false, false, ""),
                        new org.jackhuang.hmcl.sao.SaoData.DownloadPrompt(true, null, "请把 [0.8.2] 和 [0.8.3] 的快速更新包拖进来",
                                java.util.List.of("0.8.1", "0.8.2", "0.8.3"), java.util.List.of("0.8.1"), java.util.List.of("0.8.2", "0.8.3"), false),
                        noLauncher);
            });
            wireShot(scene[0], out.resolve(tag + "05-home-pack-update.png"));
            fx(() -> page[0].openPack(SaoPackApp.Mode.PACK));
            Thread.sleep(900);
            fx(() -> fake.forceUpdates(fake.updateStatus().get(), new org.jackhuang.hmcl.sao.SaoData.DownloadPrompt(true, null,
                    "请把 [0.8.2] 和 [0.8.3] 的快速更新包拖进来", java.util.List.of("0.8.1", "0.8.2", "0.8.3"),
                    java.util.List.of("0.8.1"), java.util.List.of("0.8.2", "0.8.3"), false), noLauncher));
            wireShot(scene[0], out.resolve(tag + "06-quick-update-1-of-3.png"));
            // 7-8: launcher below the minimum: the home row, then Link Start pressed (the gate's window, display on).
            var blockedMeta = SaoFakeService.meta("0.8.2", "0.1.1", "0.1.1", true);
            fx(() -> {
                page[0].forceApp(-1);
                fake.forceInstalled("0.8.2");
                fake.forceUpdates(new org.jackhuang.hmcl.sao.SaoData.UpdateStatus(blockedMeta, false, true, true,
                        "启动器版本过低，请先从夸克下载完整启动器后再 Link Start（最低版本 0.1.1）"),
                        new org.jackhuang.hmcl.sao.SaoData.DownloadPrompt(false, null, ""), noLauncher);
            });
            wireShot(scene[0], out.resolve(tag + "07-launcher-blocked-home.png"));
            fx(() -> page[0].forceLinkStart());
            Thread.sleep(600);
            wireShot(scene[0], out.resolve(tag + "08-launcher-blocked-link-start.png"));
            fx(() -> System.out.println("blocked Link Start: state " + fake.state().get() + ", display on " + page[0].isAwakeForTools()));
            // 9: an optional newer launcher with a JAR; 10: the verified JAR waiting for a restart.
            fx(() -> {
                page[0].forceApp(-1);
                fake.forceUpdates(new org.jackhuang.hmcl.sao.SaoData.UpdateStatus(SaoFakeService.meta("0.8.2", "0.1.1", "0.1.0", true), false, false, true,
                        "请把 [NerveGear启动器 0.1.1] 的更新包拖进来"), new org.jackhuang.hmcl.sao.SaoData.DownloadPrompt(false, null, ""), noLauncher);
                page[0].openPack(SaoPackApp.Mode.LAUNCHER);
            });
            wireShot(scene[0], out.resolve(tag + "09-launcher-update-optional.png"));
            fx(() -> {
                page[0].forceApp(-1);
                fake.forceUpdates(new org.jackhuang.hmcl.sao.SaoData.UpdateStatus(SaoFakeService.meta("0.8.2", "0.1.1", "0.1.0", true), false, false, false, ""),
                        new org.jackhuang.hmcl.sao.SaoData.DownloadPrompt(false, null, ""),
                        new org.jackhuang.hmcl.sao.SaoData.LauncherUpdate("0.1.1", true, "启动器更新已校验，重新打开启动器后生效"));
                page[0].openPack(SaoPackApp.Mode.LAUNCHER);
            });
            wireShot(scene[0], out.resolve(tag + "10-self-update-ready.png"));
            // 11-12: downloading mods 37/178 with measured bytes and speed: the Link row, then 下载 with one failure.
            fx(() -> {
                page[0].forceApp(-1);
                fake.forceUpdates(noMeta, new org.jackhuang.hmcl.sao.SaoData.DownloadPrompt(false, null, ""), noLauncher);
                fake.force(org.jackhuang.hmcl.sao.SaoPackState.WORKING, 37 / 178.0, "下载模组 37/178", null);
                fake.forceInstall(new org.jackhuang.hmcl.sao.SaoInstallProgress(org.jackhuang.hmcl.sao.SaoInstallProgress.Stage.DOWNLOAD_MODS,
                        "下载模组 37/178", 37, 178, 412L << 20, 1946L << 20, 430L << 20, 6.3 * (1 << 20), java.util.List.of(
                        new org.jackhuang.hmcl.sao.SaoInstallProgress.Failure("mods/example-mod-1.2.jar", java.util.List.of(
                                new org.jackhuang.hmcl.sao.SaoInstallProgress.Attempt("https://mod.mcimirror.top/x", "mod.mcimirror.top", 404),
                                new org.jackhuang.hmcl.sao.SaoInstallProgress.Attempt("https://cdn.modrinth.com/x", "cdn.modrinth.com", 0)),
                                "校验失败"))));
            });
            wireShot(scene[0], out.resolve(tag + "11-downloading-37-of-178.png"));
            fx(() -> page[0].forceApp(9));
            Thread.sleep(500);
            wireShot(scene[0], out.resolve(tag + "12-downloads-window.png"));
            // 13: 关于 with QQ群 from links(); 14: 设置 with 画质 and 游戏启动后.
            fx(() -> {
                page[0].forceApp(-1);
                fake.forceInstall(idle);
                fake.force(org.jackhuang.hmcl.sao.SaoPackState.READY, 1, "", null);
                page[0].forceApp(10);
            });
            wireShot(scene[0], out.resolve(tag + "13-about-qq.png"));
            fx(() -> page[0].forceApp(0));
            wireShot(scene[0], out.resolve(tag + "14-settings.png"));
            fx(() -> page[0].forceApp(-1));
            System.out.println("wire " + tag + "drops " + fake.drops.size() + ", opened " + fake.opened);
        }
    }

    /// Waits for the windows to settle and writes one wire shot (Simplified glyphs are patched for every shot).
    private static void wireShot(Scene scene, Path file) throws Exception {
        Thread.sleep(1300);
        if (!SaoCjkFont.active()) throw new IllegalStateException("Simplified font patch lost before " + file);
        WritableImage[] im = new WritableImage[1];
        fx(() -> im[0] = scene.snapshot(null));
        write(im[0], file);
        System.out.println("wire shot: " + file.getFileName());
    }

    /// maint1 (10 Nov): the 服务器 window with the signed maintenance notice, in 浅色 and 深色, into `maint/`.
    private static void shootMaint(Path base) throws Exception {
        Path out = base.resolve("maint");
        Files.createDirectories(out);
        String longMessage = "服务器正在停机维护，本次维护将更新第一层的地图与任务数据，修复多人组队时剑技光效不同步的问题，调整商店物价与掉落表，并对数据库进行备份与迁移。维护期间无法登录游戏服务器，ARGUS ID 账号、皮肤与好友功能不受影响。维护结束时间可能根据实际进度提前或延后，请留意启动器公告与QQ群通知。给各位玩家带来的不便，敬请谅解，感谢大家的耐心等待与支持！维护完成后将第一时间在公告中通知，届时请重新打开启动器检查更新。";
        System.out.println("long maintenance message: " + longMessage.length() + " characters");
        var online = new org.jackhuang.hmcl.sao.SaoData.ServerStatus(true, 23, 100, 38, "刀剑神域·誓约 · 第一层", java.util.List.of("Kirito", "Asuna", "Klein", "Agil"));
        var offline = new org.jackhuang.hmcl.sao.SaoData.ServerStatus(false, 0, 100, -1, "", java.util.List.of());
        Object[][] states = {
                {"01-on-until", "服务器维护中，暂时无法进入游戏", "11-10 18:00", online},
                {"02-on-no-ping", "服务器维护中，暂时无法进入游戏", "11-10 18:00", null},
                {"03-on-offline", "服务器维护中，暂时无法进入游戏", null, offline},
                {"04-off", null, null, online},
                {"05-on-long-message", longMessage, "11-10 18:00", online}};
        for (boolean dark : new boolean[]{false, true}) {
            String tag = dark ? "dark-" : "light-";
            System.setProperty("sao.windowButtons", "left");
            SaoFakeService fake = new SaoFakeService();
            fake.setPreference("theme", dark ? "dark" : "light");
            SaoHelmetPage[] page = new SaoHelmetPage[1];
            Scene[] scene = new Scene[1];
            fx(() -> {
                fake.forceOath(true);
                fake.force(org.jackhuang.hmcl.sao.SaoPackState.READY, 1, "", null);
                page[0] = new SaoHelmetPage(fake);
                scene[0] = new Scene(page[0], 1920, 1080);
            });
            Thread.sleep(2500);
            fx(() -> scene[0].snapshot(null));
            Thread.sleep(600);
            for (Object[] st : states) {
                fx(() -> {
                    page[0].forceApp(-1);
                    page[0].forceLit();
                    fake.forceServer((org.jackhuang.hmcl.sao.SaoData.ServerStatus) st[3]);
                    fake.forceMaintenance((String) st[1], (String) st[2]);
                });
                Thread.sleep(1300);
                if (!SaoCjkFont.active()) throw new IllegalStateException("Simplified font patch lost");
                WritableImage[] im = new WritableImage[1];
                fx(() -> im[0] = scene[0].snapshot(null));
                write(im[0], out.resolve(tag + st[0] + ".png"));
                System.out.println("maint shot: " + tag + st[0]);
            }
        }
    }

    // ------------------------------------------------------------------ motion films

    /// One step of a film: at a time, do something on the FX thread.
    private record Cue(double atMs, Runnable act) { }

    /// Films the page in real time: frames as fast as snapshots go, each with its real time, so a video made from
    /// them plays at real speed (frames.txt is an ffmpeg concat list with each frame's duration).
    private static void film(Scene scene, Path dir, double totalMs, java.util.List<Cue> cues) throws Exception {
        if (Files.exists(dir)) try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        }
        Files.createDirectories(dir);
        java.util.concurrent.ExecutorService writers = java.util.concurrent.Executors.newFixedThreadPool(6);
        // At most this many frames wait for their PNG; past that the film waits (memory stays small).
        java.util.concurrent.Semaphore room = new java.util.concurrent.Semaphore(24);
        java.util.List<java.util.concurrent.Future<?>> pending = new java.util.ArrayList<>();
        java.util.List<Double> times = new java.util.ArrayList<>();
        java.util.ArrayDeque<Cue> left = new java.util.ArrayDeque<>(cues);
        long t0 = System.nanoTime();
        WritableImage[] im = new WritableImage[1];
        int n = 0;
        while (true) {
            double now = (System.nanoTime() - t0) / 1e6;
            if (now > totalMs) break;
            while (!left.isEmpty() && left.peek().atMs() <= now) fx(left.poll().act());
            // About 60 frames a second is plenty for a 30 fps video; snapshots alone would go far faster.
            double next = n * 1000.0 / 60;
            if (now < next) Thread.sleep((long) Math.ceil(next - now));
            room.acquire();
            int[][] px = new int[1][];
            int[] wh = new int[2];
            fx(() -> {
                im[0] = scene.snapshot(im[0]);
                wh[0] = (int) im[0].getWidth();
                wh[1] = (int) im[0].getHeight();
                px[0] = new int[wh[0] * wh[1]];
                im[0].getPixelReader().getPixels(0, 0, wh[0], wh[1],
                        javafx.scene.image.WritablePixelFormat.getIntArgbInstance(), px[0], 0, wh[0]);
            });
            times.add((System.nanoTime() - t0) / 1e6);
            Path f = dir.resolve(String.format("f%04d.png", n++));
            int[] pixels = px[0];
            pending.add(writers.submit(() -> {
                try {
                    BufferedImage bi = new BufferedImage(wh[0], wh[1], BufferedImage.TYPE_INT_ARGB);
                    bi.getRaster().setDataElements(0, 0, wh[0], wh[1], pixels);
                    ImageIO.write(bi, "png", f.toFile());
                } finally {
                    room.release();
                }
                return null;
            }));
        }
        for (var p : pending) p.get();
        writers.shutdown();
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < times.size(); i++) {
            double d = (i + 1 < times.size() ? times.get(i + 1) - times.get(i) : 33) / 1000;
            list.append(String.format("file 'f%04d.png'%nduration %.4f%n", i, d));
        }
        list.append(String.format("file 'f%04d.png'%n", times.size() - 1));
        Files.writeString(dir.resolve("frames.txt"), list);
        System.out.printf("film %s: %d frames in %.1f s (%.1f fps)%n", dir.getFileName(), times.size(), totalMs / 1000,
                times.size() / (totalMs / 1000));
    }

    /// Sends a mouse event to the centre of a node through the scene, as a real pointer would (hover, press, click).
    private static void mouse(Scene scene, javafx.event.EventType<javafx.scene.input.MouseEvent> type, javafx.scene.Node n) {
        javafx.geometry.Bounds b = n.getLayoutBounds();
        javafx.geometry.Point2D p = n.localToScene(new javafx.geometry.Point2D(b.getCenterX(), b.getCenterY()), true);
        boolean button = type != javafx.scene.input.MouseEvent.MOUSE_MOVED;
        javafx.scene.input.MouseEvent e = new javafx.scene.input.MouseEvent(type, p.getX(), p.getY(), p.getX(), p.getY(),
                button ? javafx.scene.input.MouseButton.PRIMARY : javafx.scene.input.MouseButton.NONE, button ? 1 : 0,
                false, false, false, false, type == javafx.scene.input.MouseEvent.MOUSE_PRESSED, false, false,
                false, false, true, null);
        try {
            Class.forName("com.sun.javafx.scene.SceneHelper").getMethod("processMouseEvent", Scene.class,
                    javafx.scene.input.MouseEvent.class).invoke(null, scene, e);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /// Hover, press and release on a node, spread over cues from a time.
    private static void click(java.util.List<Cue> cues, Scene scene, double at, java.util.function.Supplier<javafx.scene.Node> n) {
        // Found once: the click may change the node (登录 → 登录中…).
        javafx.scene.Node[] at0 = new javafx.scene.Node[1];
        cues.add(new Cue(at, () -> mouse(scene, javafx.scene.input.MouseEvent.MOUSE_MOVED, at0[0] = n.get())));
        cues.add(new Cue(at + 450, () -> mouse(scene, javafx.scene.input.MouseEvent.MOUSE_PRESSED, at0[0])));
        cues.add(new Cue(at + 600, () -> {
            mouse(scene, javafx.scene.input.MouseEvent.MOUSE_RELEASED, at0[0]);
            mouse(scene, javafx.scene.input.MouseEvent.MOUSE_CLICKED, at0[0]);
        }));
    }

    /// The first node under the page matching a test.
    private static javafx.scene.Node find(javafx.scene.Parent root, java.util.function.Predicate<javafx.scene.Node> test) {
        java.util.ArrayDeque<javafx.scene.Node> q = new java.util.ArrayDeque<>(java.util.List.of(root));
        while (!q.isEmpty()) {
            javafx.scene.Node n = q.poll();
            if ((n.isVisible() || n.getStyleClass().contains("vr-tip")) && test.test(n)) return n;
            if (n instanceof javafx.scene.SubScene ss) q.add(ss.getRoot());
            if (n instanceof javafx.scene.Parent p) q.addAll(p.getChildrenUnmodifiable());
        }
        throw new IllegalStateException("no such node");
    }

    private static javafx.scene.Node button(javafx.scene.Parent root, String text) {
        return find(root, n -> n instanceof javafx.scene.control.Button b && text.equals(b.getText()));
    }

    /// The motion films: signing in (a mistake, then success), an app window opening with a switch, the Microsoft
    /// sign-in window to its end. 1280 x 720, 深色 unless SAO_MOTION_THEME=light.
    /// SAO_MOTION_ONLY=skin,window films only those (all when unset).
    private static boolean want(String film) {
        String only = System.getenv("SAO_MOTION_ONLY");
        return only == null || java.util.Arrays.asList(only.split(",")).contains(film);
    }

    private static void shootMotion(Path base) throws Exception {
        Path out = base.resolve("motion");
        System.setProperty("sao.windowButtons", "left");
        SaoFakeService fake = new SaoFakeService();
        fake.setPreference("theme", "light");
        fake.forceInstalled("0.8.1");
        fake.loginDelayMs = 1600;
        fake.setPreference("theme", System.getenv().getOrDefault("SAO_MOTION_THEME", "dark"));
        SaoHelmetPage[] page = new SaoHelmetPage[1];
        Scene[] scene = new Scene[1];
        fx(() -> {
            page[0] = new SaoHelmetPage(fake);
            scene[0] = new Scene(page[0], 1280, 720);
        });
        Thread.sleep(3500);
        fx(() -> page[0].debugCloseApp());
        fx(fake::oathLogout);
        Thread.sleep(1500);
        Scene sc = scene[0];
        SaoHelmetPage pg = page[0];

        // 1. Signing in: an empty name (the button gives, the field and the line shake), then a name and a password
        // (the field glows), 登录 (breathes while it works), signed in (rows come in, the name lands with a glow).
        java.util.List<Cue> a = new java.util.ArrayList<>();
        a.add(new Cue(0, () -> {
            ((javafx.scene.control.TextField) find(pg, n -> n instanceof javafx.scene.control.TextField t
                    && "用户名／邮箱".equals(t.getPromptText()))).setText("");
        }));
        click(a, sc, 700, () -> button(pg, "登录"));
        a.add(new Cue(2600, () -> {
            javafx.scene.control.TextField f = (javafx.scene.control.TextField) find(pg, n -> n instanceof javafx.scene.control.TextField t
                    && "用户名／邮箱".equals(t.getPromptText()));
            f.requestFocus();
            f.setText("Kirito_07");
        }));
        a.add(new Cue(3400, () -> {
            javafx.scene.control.PasswordField f = (javafx.scene.control.PasswordField) find(pg, n -> n instanceof javafx.scene.control.PasswordField);
            f.requestFocus();
            f.setText("starburst");
        }));
        click(a, sc, 4300, () -> button(pg, "登录"));
        if (want("login")) film(sc, out.resolve("01-login"), 10500, a);
        else { fx(() -> { fake.loginDelayMs = 0; fake.oathLogin("Kirito_07", "starburst".toCharArray(), true); }); Thread.sleep(2500); }

        // 2. A window: the 设置 dock button, the window flies in and its content comes in after the glass, a switch
        // flips twice, the window goes.
        java.util.List<Cue> b = new java.util.ArrayList<>();
        java.util.function.Supplier<javafx.scene.Node> dock = () -> {
            javafx.scene.Node tip = find(pg, n -> n.getStyleClass().contains("vr-tip")
                    && n instanceof javafx.scene.layout.StackPane s && s.getChildren().get(0) instanceof javafx.scene.text.Text l
                    && "设置".equals(l.getText()));
            return ((javafx.scene.layout.StackPane) tip.getParent()).getChildren().get(0);
        };
        click(b, sc, 600, dock);
        java.util.function.Supplier<javafx.scene.Node> toggle = () -> find(pg, n -> n.getStyleClass().contains("vr-switch"));
        click(b, sc, 2600, toggle);
        click(b, sc, 3900, toggle);
        b.add(new Cue(5600, pg::debugCloseApp));
        if (want("window")) film(sc, out.resolve("02-window"), 7600, b);

        // 3. 微软正版登录: opens, browser, signing in, done (the tick glows), closes by itself.
        java.util.List<Cue> c = new java.util.ArrayList<>();
        c.add(new Cue(500, () -> pg.openMicrosoft(fake::microsoftSignIn)));
        if (want("microsoft")) film(sc, out.resolve("03-microsoft"), 8200, c);

        // 4. The skin changes twice (the band of light runs down the figure), then the same skin comes back on a refresh
        // (nothing plays).
        Path kai = Files.createTempFile("sao-motion-kai-", ".png"), noor = Files.createTempFile("sao-motion-noor-", ".png");
        try (var in = SaoScreenShotTool.class.getResourceAsStream("/assets/img/skin/wide/kai.png")) { Files.copy(in, kai, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        try (var in = SaoScreenShotTool.class.getResourceAsStream("/assets/img/skin/slim/noor.png")) { Files.copy(in, noor, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        java.util.List<Cue> d = new java.util.ArrayList<>();
        d.add(new Cue(600, () -> { fake.setSkin(kai, false); pg.debugRefreshAccount(); }));
        d.add(new Cue(2400, () -> { fake.setSkin(noor, true); pg.debugRefreshAccount(); }));
        d.add(new Cue(4200, pg::debugRefreshAccount));
        if (want("skin")) film(sc, out.resolve("04-skin"), 5600, d);
    }
}
