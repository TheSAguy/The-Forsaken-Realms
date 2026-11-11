package forge.app;

import forge.util.TextUtil;
import org.apache.commons.text.StringEscapeUtils;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class GitLogs {
    // TFR round 1012 (logs-1111 P3-23): both feeds used URL.openStream(), which never times out on Android, and the
    // card database waits for this check - a stalled connection held a 1.28 launch on the splash for 54 s. Both feeds
    // now open with a 5 s connect and a 5 s read timeout; on a timeout the check returns its empty result and the
    // game loads normally. The release check logs one [TFR-Update] line: ok, timed out or failed, with its time.
    static final int FEED_TIMEOUT_MS = 5000;

    static InputStream openFeed(String feedUrl, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(feedUrl).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        return c.getInputStream();
    }

    static String describe(Exception e, long ms) {
        return e instanceof SocketTimeoutException
                ? "timed out after " + ms + " ms (" + e.getMessage() + "); playing on"
                : "failed after " + ms + " ms: " + e + "; playing on";
    }

    public String getLatest(String commitsAtom, Date buildDateOriginal, Date maxDate) {
        String message = "";
        try {
            InputStream inputStream = openFeed(commitsAtom, FEED_TIMEOUT_MS); // TFR round 1012
            List<AtomReader.Entry> entries = new AtomReader().parse(inputStream);
            StringBuilder logs = new StringBuilder();
            SimpleDateFormat simpleDate = TextUtil.getSimpleDate();
            SimpleDateFormat atomDate = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ss");
            int c = 0;
            for (AtomReader.Entry entry : entries) {
                if (entry.title == null)
                    continue;
                String title = TextUtil.stripNonValidXMLCharacters(entry.title);
                if (title.contains("Merge"))
                    continue;
                if (entry.updated == null)
                    continue;
                Date feedDate = atomDate.parse(entry.updated);
                if (buildDateOriginal != null && feedDate.toInstant().isBefore(buildDateOriginal.toInstant()))
                    continue;
                if (maxDate != null && feedDate.toInstant().isAfter(maxDate.toInstant()))
                    continue;
                logs.append(simpleDate.format(feedDate)).append(" | ").append(StringEscapeUtils.unescapeXml(title).replace("\n", "").replace("        ", "")).append("\n\n");
                if (c >= 15)
                    break;
                c++;
            }
            if (logs.length() > 0)
                message += ("\n\nLatest Changes:\n\n" + logs);
            inputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return message;
    }

    public String getLatestReleaseTag(String releaseAtom) {
        String tag = "";
        final long start = System.currentTimeMillis(); // TFR round 1012
        try {
            InputStream inputStream = openFeed(releaseAtom, FEED_TIMEOUT_MS); // TFR round 1012
            List<AtomReader.Entry> entries = new AtomReader().parse(inputStream);
            for (AtomReader.Entry entry : entries) {
                if (entry.link != null) {
                    try {
                        String val = entry.link;
                        // TFR: this fork's release tags are "tfr-vX.YZ" (upstream's are
                        // "forge-..."); with the old marker lastIndexOf returned -1, the
                        // substring threw, and the tag came back empty on every launch.
                        tag = val.substring(val.lastIndexOf("tfr-v"));
                        break;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            inputStream.close();
            System.out.println("[TFR-Update] release check: ok in " + (System.currentTimeMillis() - start) + " ms, tag "
                    + (tag.isEmpty() ? "none found" : tag)); // TFR round 1012
        } catch (Exception e) {
            System.out.println("[TFR-Update] release check: " + describe(e, System.currentTimeMillis() - start)); // TFR round 1012
            e.printStackTrace();
        }
        return tag;
    }
}
