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
        checkThemes();
        shootDialogs(base);
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
            } else {
                shot(scene[0], page[0], fake, SaoPackState.WORKING, 0.42, "安装 NeoForge", 0, 0, -1, out.resolve(p + "01-home-installing.png"));
                shot(scene[0], page[0], fake, SaoPackState.READY, 1, "", 0, 0, 0, out.resolve(p + "10-app-settings.png"));
            }
        }
        Platform.exit();
        System.exit(0);
    }

    /// Puts the page in a state, an app and a head turn, waits for the windows to settle and writes a shot.
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
}
