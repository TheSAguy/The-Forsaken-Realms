package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import forge.adventure.data.SettingData;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeProfileProperties;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Round 698 (the user: "have the game log just automatically zipped and posted each time you exit a game", then "Let's
 * go with Discord webhook" and "Default should be 'Yes', so they can just click continue"). With the player's consent
 * (the one-time question on the start screen, then the Settings switch), the PREVIOUS session's log - the dated archive
 * forge.<stamp>.log the engine writes at the next startup (ExceptionHandler), so a crash or a force-close is covered,
 * which an exit hook never sees - is scrubbed of the PC user name, zipped and posted to the plane's Discord webhook on a
 * background thread. One file per launch, never the same one twice (SettingData.logShareLastSent), no retries.
 * <p>
 * The webhook URL is not in git: the plane's log_share.txt is gitignored and written from C:\TFR\secrets by the
 * packager (desktop) and by the Android release steps. No file, no sharing - and the question is not asked.
 */
public final class LogShare {
    private LogShare() {
    }

    /** The plane file holding the webhook URL (gitignored). */
    public static final String WEBHOOK_FILE = "log_share.txt";
    private static final Pattern ARCHIVE = Pattern.compile("forge\\.\\d{8}-\\d{6}(\\.\\d+)?\\.log");
    /** "C:\Users\Name\..." / "/Users/Name/..." / "/home/name/..." - the name goes. */
    private static final Pattern USER_PATH = Pattern.compile("(?i)([\\\\/](?:Users|home)[\\\\/])[^\\\\/\\s:\"'<>|]+");
    private static final long MAX_BYTES = 8L * 1024 * 1024; // Discord's attachment limit is 10 MB
    private static boolean startedThisRun;

    /** The webhook URL shipped with this build, or null (no file, or not a Discord webhook). */
    static String webhook() {
        try {
            FileHandle handle = Config.instance().getFile(WEBHOOK_FILE);
            if (handle == null || !handle.exists())
                return null;
            String url = handle.readString("UTF-8").trim();
            return url.startsWith("https://discord.com/api/webhooks/") || url.startsWith("https://discordapp.com/api/webhooks/")
                    ? url : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** True when this build can share (a webhook shipped) - the question and the Settings row show only then. */
    public static boolean available() {
        return webhook() != null;
    }

    /** True when the start screen should ask the one-time question. */
    public static boolean shouldAsk() {
        SettingData s = Config.instance().getSettingData();
        return available() && !s.logShareAsked;
    }

    /** The player's answer (or the Settings switch). Yes on the first answer sends the previous session at once. */
    public static void setEnabled(boolean enabled) {
        SettingData s = Config.instance().getSettingData();
        s.logShareAsked = true;
        s.logShareEnabled = enabled;
        Config.instance().saveSettings();
        System.out.println("[TFR-LogShare] sharing " + (enabled ? "on" : "off") + " (the player's choice)");
        if (enabled)
            uploadPreviousSession();
    }

    public static boolean isEnabled() {
        return Config.instance().getSettingData().logShareEnabled;
    }

    /** Once per run: post the newest archived log not sent yet, if sharing is on. Never throws, never blocks. */
    public static void uploadPreviousSession() {
        if (startedThisRun)
            return;
        SettingData s = Config.instance().getSettingData();
        String url = webhook();
        if (url == null || !s.logShareEnabled)
            return;
        File log = newestArchive();
        if (log == null) {
            System.out.println("[TFR-LogShare] no earlier session log to send");
            return;
        }
        if (log.getName().equals(s.logShareLastSent)) {
            System.out.println("[TFR-LogShare] " + log.getName() + " was already sent");
            return;
        }
        if (s.logShareInstallId == null || s.logShareInstallId.isEmpty())
            s.logShareInstallId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        startedThisRun = true;
        final String installId = s.logShareInstallId;
        final String caption = caption(installId, log.getName());
        Thread t = new Thread(() -> post(url, log, installId, caption), "TFR-LogShare");
        t.setDaemon(true);
        t.start();
    }

    private static File newestArchive() {
        File dir = new File(ForgeProfileProperties.getUserDir());
        File[] archives = dir.listFiles(f -> f.isFile() && ARCHIVE.matcher(f.getName()).matches());
        if (archives == null || archives.length == 0)
            return null;
        File newest = archives[0];
        for (File f : archives)
            if (f.lastModified() > newest.lastModified())
                newest = f;
        return newest;
    }

    private static String caption(String installId, String logName) {
        String version = Config.instance().getConfigData().modVersion;
        String platform = GuiBase.isAndroid() ? "Android " + GuiBase.getAndroidAPILevel() : System.getProperty("os.name", "PC");
        return "**TFR " + (version == null ? "?" : version) + "** | " + platform + " | install `" + installId + "` | " + logName;
    }

    private static void post(String url, File log, String installId, String caption) {
        try {
            if (log.length() > MAX_BYTES * 4) {
                System.out.println("[TFR-LogShare] " + log.getName() + " is " + log.length() / 1024 + " KB - too big, not sent");
                return;
            }
            String text = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
            text = USER_PATH.matcher(text).replaceAll("$1<user>");
            String user = System.getProperty("user.name", "");
            if (user.length() >= 3 && !"root".equals(user))
                text = text.replace(user, "<user>");
            ByteArrayOutputStream zipped = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(zipped)) {
                zip.putNextEntry(new ZipEntry(log.getName()));
                zip.write(text.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            if (zipped.size() > MAX_BYTES) {
                System.out.println("[TFR-LogShare] the zip is " + zipped.size() / 1024 + " KB - too big, not sent");
                return;
            }
            String stamp = log.getName().replace("forge.", "").replace(".log", "");
            String fileName = "tfr1-" + installId + "-" + stamp + ".zip";
            int code = sendMultipart(url, caption, fileName, zipped.toByteArray());
            if (code >= 200 && code < 300) {
                SettingData s = Config.instance().getSettingData();
                s.logShareLastSent = log.getName();
                com.badlogic.gdx.Gdx.app.postRunnable(() -> Config.instance().saveSettings()); // settings write on the game thread
                System.out.println("[TFR-LogShare] sent " + log.getName() + " as " + fileName + " (" + zipped.size() / 1024
                        + " KB zipped, HTTP " + code + ")");
            } else {
                System.out.println("[TFR-LogShare] " + log.getName() + " not sent - HTTP " + code);
            }
        } catch (Exception e) {
            System.out.println("[TFR-LogShare] " + log.getName() + " not sent - " + e);
        }
    }

    /** Discord webhook: payload_json (the message text) + files[0] (the zip), multipart/form-data. */
    private static int sendMultipart(String url, String content, String fileName, byte[] data) throws Exception {
        String boundary = "----TFRLogShare" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("User-Agent", "TheForsakenRealms-LogShare");
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        String json = "{\"content\":\"" + content.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        try (OutputStream out = c.getOutputStream()) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                    + "Content-Type: application/json\r\n\r\n" + json + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"" + fileName
                    + "\"\r\nContent-Type: application/zip\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {
            if (in != null)
                while (in.read() != -1) { /* drain */ }
        }
        c.disconnect();
        return code;
    }
}
