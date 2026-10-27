package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.github.tommyettinger.textra.TextraLabel;
import forge.adventure.data.ConfigData;
import forge.gui.GuiBase;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Round 782: a light "new version" notice for PC. The stock updater stays off on desktop (it would install plain Forge
 * over the game - AssetsDownloader.checkForUpdates), so PC players only heard about releases on Discord. Now the game
 * asks GitHub once per launch, in the background, for the newest release; if its "tfr-vX.YZ" tag is newer than this
 * version, a small notice in the start screen's top-left corner says so, with a button that opens the release page in
 * the browser. Nothing is downloaded or installed (a PC install is a zip; a half-updated folder is how games break),
 * and nothing about the player is sent - a plain GET with a fixed User-Agent.
 * <p>
 * Per-plane opt-in: ConfigData.updateCheckUrl (the GitHub API's releases/latest) and releasePageUrl (where the button
 * goes - the itch.io page later, a one-line data change). Stock planes set neither and see nothing. Android keeps its own
 * retargeted updater, so this is desktop only. Offline or any error: silent, one [TFR-Update] line.
 * <p>
 * Test override: TFR_PRETEND_VERSION=1.20 (environment) or -Dtfr.pretendVersion=1.20 makes the game compare as that
 * version.
 */
public final class UpdateNotice {
    private UpdateNotice() {
    }

    private static boolean started;
    private static volatile String newerVersion; // "1.24" once the check found a newer release
    private static boolean dismissed;
    private static Group root;
    private static Table notice;

    /** StartScene.enter: starts the check on the first call, and shows the notice once a newer release is known. */
    public static void attach(Group startSceneUi) {
        root = startSceneUi;
        if (!started) {
            started = true;
            start();
        }
        show();
    }

    private static void start() {
        if (GuiBase.isAndroid())
            return; // the retargeted in-app updater covers Android
        ConfigData config = Config.instance().getConfigData();
        if (config == null || config.updateCheckUrl == null || config.updateCheckUrl.isEmpty()
                || config.releasePageUrl == null || config.releasePageUrl.isEmpty() || config.modVersion == null)
            return; // stock planes: nothing
        String local = config.modVersion;
        String pretend = System.getProperty("tfr.pretendVersion", System.getenv("TFR_PRETEND_VERSION"));
        if (pretend != null && !pretend.trim().isEmpty()) {
            System.out.println("[TFR-Update] test override: comparing as v" + pretend.trim() + " (this is v" + local + ")");
            local = pretend.trim();
        }
        final String thisVersion = local;
        final String url = config.updateCheckUrl;
        Thread t = new Thread(() -> check(url, thisVersion), "TFR-Update");
        t.setDaemon(true);
        t.start();
    }

    private static void check(String url, String thisVersion) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("User-Agent", "TheForsakenRealms-UpdateCheck");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code != 200) {
                System.out.println("[TFR-Update] check failed - HTTP " + code + ", no notice");
                return;
            }
            String body;
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; )
                    out.write(buf, 0, n);
                body = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            JsonValue json = new JsonReader().parse(body);
            String tag = json.getString("tag_name", "");
            if (!tag.startsWith("tfr-v")) {
                System.out.println("[TFR-Update] newest release tag '" + tag + "' is not a tfr-v tag, no notice");
                return;
            }
            String newest = tag.substring("tfr-v".length());
            boolean newer = isRemoteNewer(newest, thisVersion);
            System.out.println("[TFR-Update] checked: newest " + tag + ", this version v" + thisVersion + " - "
                    + (newer ? "notice shown" : "up to date, no notice"));
            if (newer) {
                newerVersion = newest;
                Gdx.app.postRunnable(UpdateNotice::show);
            }
        } catch (Exception e) {
            System.out.println("[TFR-Update] check failed - " + e + ", no notice");
        }
    }

    /** Mirrors AssetsDownloader.isRemoteNewer (round 282, private there): part by part, so 1.9 < 1.10; unparseable
     *  versions never count as newer here - a notice that nags is worse than none. */
    static boolean isRemoteNewer(String remote, String local) {
        String[] r = remote.trim().split("\\."), l = local.trim().split("\\.");
        for (int i = 0; i < Math.max(r.length, l.length); i++) {
            int rp, lp;
            try {
                rp = i < r.length ? Integer.parseInt(r[i].trim()) : 0;
                lp = i < l.length ? Integer.parseInt(l[i].trim()) : 0;
            } catch (NumberFormatException e) {
                return false;
            }
            if (rp != lp)
                return rp > lp;
        }
        return false;
    }

    private static void show() {
        if (root == null || newerVersion == null || dismissed || (notice != null && notice.getParent() == root))
            return;
        final String pageUrl = Config.instance().getConfigData().releasePageUrl;
        notice = new Table();
        // a dark panel like the buttons' face, so the line reads over any part of the title art
        notice.setBackground(Controls.getSkin().newDrawable("white-pixel", new Color(0.09f, 0.08f, 0.14f, 0.85f)));
        notice.pad(4);
        TextraLabel label = Controls.newTextraLabel("TFR v" + newerVersion + " is available");
        label.setColor(Color.WHITE);
        notice.add(label).colspan(2).left().padBottom(2).row();
        notice.add(Controls.newTextButton("Download page", () -> {
            System.out.println("[TFR-Update] opened " + pageUrl);
            Gdx.net.openURI(pageUrl);
        })).width(100).height(24).left();
        notice.add(Controls.newTextButton("X", () -> {
            System.out.println("[TFR-Update] notice dismissed for this launch");
            dismissed = true;
            notice.remove();
        })).width(24).height(24).padLeft(4).left();
        notice.pack();
        notice.setPosition(8, root.getHeight() - notice.getHeight() - 8);
        root.addActor(notice);
    }
}
