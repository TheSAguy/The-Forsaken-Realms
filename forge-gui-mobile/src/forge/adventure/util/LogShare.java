package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import forge.adventure.data.SettingData;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeProfileProperties;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * background thread.
 * <p>
 * Round 885 (the user: "Send unsent logs, oldest first, up to 3 per launch"; a 14-line stub had taken the turn of a real
 * session): each launch sends the unsent archives, oldest first, at most {@link #MAX_PER_LAUNCH} tries. Never one from
 * before sharing was last turned on (SettingData.logShareOptInAt), so a Yes sends nothing at once: the first log is the
 * session the player said Yes in, at the next launch. Never one already sent (SettingData.logShareSent). A failed
 * upload stays unsent and is tried again next launch. An install opted in before this round has no opt-in time: its
 * first launch here takes the newest archive's time, so it sends what the old build would have, that one file at most.
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

    /** One log line without the PC user name: a user folder in a path, then the name itself (3+ letters, not root).
     *  Round 934 moved it here from the send, so Export Logs scrubs the same way. */
    static String scrub(String line) {
        line = USER_PATH.matcher(line).replaceAll("$1<user>");
        String user = System.getProperty("user.name", "");
        return user.length() >= 3 && !"root".equals(user) ? line.replace(user, "<user>") : line;
    }

    /**
     * Round 934 (Fable P3-57, privacy): Export Logs zipped the raw logs, user name and all (every path line, its own
     * "[TFR-Logs] exported to" line included), and its dialog tells players to attach the zip on Discord. Stock
     * LogExporter builds the zip in a temp folder (it snapshots the running log); this writes each of its logs into
     * destDir a line at a time through {@link #scrub}, under the same name. Null when there was no log.
     */
    public static File exportScrubbed(File destDir) throws java.io.IOException {
        File temp = java.nio.file.Files.createTempDirectory("tfr-logs").toFile();
        try {
            File raw = forge.util.LogExporter.exportLogs(temp);
            if (raw == null)
                return null;
            File out = new File(destDir, raw.getName());
            int lines = 0;
            try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new FileInputStream(raw));
                 java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(out))) {
                for (java.util.zip.ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                    // Not closed: that would close the zip streams. Each entry's reads end at the entry's end.
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    Writer writer = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
                    for (String line = reader.readLine(); line != null; line = reader.readLine(), lines++) {
                        writer.write(scrub(line));
                        writer.write('\n');
                    }
                    writer.flush();
                    zip.closeEntry();
                }
            }
            System.out.println("[TFR-Logs] export scrubbed of the user name: " + lines + " line(s)");
            return out;
        } finally {
            File[] left = temp.listFiles();
            if (left != null)
                for (File f : left)
                    f.delete();
            temp.delete();
        }
    }
    static final int MAX_PER_LAUNCH = 3;
    private static boolean startedThisRun;

    private enum Outcome { SENT, TOO_BIG, REJECTED, UNREACHABLE }

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

    /** The player's answer (or the Settings switch). Turning it on starts the clock: no earlier log is ever sent. */
    public static void setEnabled(boolean enabled) {
        SettingData s = Config.instance().getSettingData();
        if (enabled && !s.logShareEnabled)
            s.logShareOptInAt = System.currentTimeMillis() / 1000 * 1000; // whole seconds, as an archive's name is
        s.logShareAsked = true;
        s.logShareEnabled = enabled;
        Config.instance().saveSettings();
        System.out.println("[TFR-LogShare] sharing " + (enabled ? "on" : "off") + " (the player's choice)");
    }

    public static boolean isEnabled() {
        return Config.instance().getSettingData().logShareEnabled;
    }

    /** Once per run: post the unsent archived logs, oldest first, if sharing is on. Never throws, never blocks. */
    public static void uploadPreviousSession() {
        if (startedThisRun)
            return;
        SettingData s = Config.instance().getSettingData();
        String url = webhook();
        if (url == null || !s.logShareEnabled)
            return;
        startedThisRun = true;
        File[] archives = new File(ForgeProfileProperties.getUserDir())
                .listFiles(f -> f.isFile() && ARCHIVE.matcher(f.getName()).matches());
        if (archives == null)
            archives = new File[0];
        if (s.logShareOptInAt == 0) {
            s.logShareOptInAt = newestTime(archives);
            Config.instance().saveSettings();
            System.out.println("[TFR-LogShare] opted in before round 885: older logs stay here, the newest of "
                    + archives.length + " is the only one that can go");
        }
        Set<String> sent = new HashSet<>();
        if (s.logShareSent != null)
            sent.addAll(Arrays.asList(s.logShareSent));
        if (s.logShareLastSent != null)
            sent.add(s.logShareLastSent);
        List<File> queue = pending(archives, sent, s.logShareOptInAt);
        if (queue.isEmpty())
            return;
        if (s.logShareInstallId == null || s.logShareInstallId.isEmpty())
            s.logShareInstallId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        final String installId = s.logShareInstallId;
        Thread t = new Thread(() -> sendAll(url, queue, installId), "TFR-LogShare");
        t.setDaemon(true);
        t.start();
    }

    /** When the log's session ended: the earlier of its name and its file time, so a restored copy can't look new. */
    static long sessionTime(File log) {
        try {
            long stamped = new SimpleDateFormat("yyyyMMdd-HHmmss").parse(log.getName().substring(6, 21)).getTime();
            return Math.min(stamped, log.lastModified());
        } catch (ParseException e) {
            return 0; // reads as older than any opt-in, so it never goes
        }
    }

    private static long newestTime(File[] archives) {
        long newest = 0;
        for (File f : archives)
            newest = Math.max(newest, sessionTime(f));
        return newest == 0 ? System.currentTimeMillis() : newest;
    }

    /** The archives that may go, oldest first: not sent yet and not from before sharing was turned on. */
    static List<File> pending(File[] archives, Set<String> sent, long optInAt) {
        List<File> queue = new ArrayList<>();
        int alreadySent = 0, beforeOptIn = 0;
        for (File f : archives) {
            if (sent.contains(f.getName()))
                alreadySent++;
            else if (sessionTime(f) < optInAt)
                beforeOptIn++;
            else
                queue.add(f);
        }
        queue.sort(Comparator.comparingLong(LogShare::sessionTime).thenComparing(File::getName));
        System.out.println("[TFR-LogShare] " + archives.length + " earlier session logs: " + queue.size() + " to send, "
                + alreadySent + " skipped as already sent, " + beforeOptIn + " skipped as from before sharing was on");
        return queue;
    }

    /** The background thread: a failed log stays unsent for the next launch; no network ends this launch's turn. */
    private static void sendAll(String url, List<File> queue, String installId) {
        int tried = 0;
        for (File log : queue) {
            if (tried == MAX_PER_LAUNCH) {
                System.out.println("[TFR-LogShare] cap of " + MAX_PER_LAUNCH + " reached, " + log.getName()
                        + " and any after it wait for the next launch");
                return;
            }
            if (!isEnabled()) {
                System.out.println("[TFR-LogShare] sharing was turned off, " + log.getName() + " not sent");
                return;
            }
            Outcome outcome = post(url, log, installId, caption(installId, log.getName()));
            if (outcome == Outcome.UNREACHABLE)
                return;
            if (outcome != Outcome.TOO_BIG)
                tried++;
            if (outcome != Outcome.REJECTED)
                markDone(log.getName(), outcome == Outcome.SENT);
        }
    }

    /** Settings write on the game thread; also forgets names whose file is gone, so the list stays short. */
    private static void markDone(String name, boolean sent) {
        com.badlogic.gdx.Gdx.app.postRunnable(() -> {
            SettingData s = Config.instance().getSettingData();
            File dir = new File(ForgeProfileProperties.getUserDir());
            List<String> done = new ArrayList<>();
            if (s.logShareSent != null)
                for (String old : s.logShareSent)
                    if (new File(dir, old).exists())
                        done.add(old);
            done.add(name);
            s.logShareSent = done.toArray(new String[0]);
            if (sent)
                s.logShareLastSent = name;
            Config.instance().saveSettings();
        });
    }

    private static String caption(String installId, String logName) {
        String version = Config.instance().getConfigData().modVersion;
        String platform = GuiBase.isAndroid() ? "Android " + GuiBase.getAndroidAPILevel() : System.getProperty("os.name", "PC");
        return "**TFR " + (version == null ? "?" : version) + "** | " + platform + " | install `" + installId + "` | " + logName;
    }

    private static Outcome post(String url, File log, String installId, String caption) {
        try {
            if (log.length() > MAX_BYTES * 4) {
                System.out.println("[TFR-LogShare] " + log.getName() + " is " + log.length() / 1024 + " KB - too big, not sent");
                return Outcome.TOO_BIG;
            }
            ByteArrayOutputStream zipped = new ByteArrayOutputStream();
            // Round 906: a line at a time, where the whole log as a String peaked near 130 MB of heap at the 32 MB limit
            // (P3-20). The limit stays: a log that size zips well under Discord's. Neither scrub spans a line break.
            try (ZipOutputStream zip = new ZipOutputStream(zipped);
                 BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(log), StandardCharsets.UTF_8))) {
                zip.putNextEntry(new ZipEntry(log.getName()));
                Writer out = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
                for (String line = in.readLine(); line != null; line = in.readLine()) {
                    out.write(scrub(line));
                    out.write('\n');
                }
                out.flush();
                zip.closeEntry();
            }
            if (zipped.size() > MAX_BYTES) {
                System.out.println("[TFR-LogShare] the zip is " + zipped.size() / 1024 + " KB - too big, not sent");
                return Outcome.TOO_BIG;
            }
            String stamp = log.getName().replace("forge.", "").replace(".log", "");
            String fileName = "tfr1-" + installId + "-" + stamp + ".zip";
            int code = sendMultipart(url, caption, fileName, zipped.toByteArray());
            if (code >= 200 && code < 300) {
                System.out.println("[TFR-LogShare] sent " + log.getName() + " as " + fileName + " (" + zipped.size() / 1024
                        + " KB zipped, HTTP " + code + ")");
                return Outcome.SENT;
            }
            System.out.println("[TFR-LogShare] " + log.getName() + " not sent - HTTP " + code);
            return Outcome.REJECTED;
        } catch (Exception | OutOfMemoryError e) {
            System.out.println("[TFR-LogShare] " + log.getName() + " not sent - " + e);
            return Outcome.UNREACHABLE;
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
