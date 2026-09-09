import forge.adventure.util.SaveFileData;
import java.io.*;
import java.util.zip.InflaterInputStream;

/** Read-only: dumps the round-148 weekly resource ledger exactly as stored. */
public class Ledger {
    static final String[] BUCKET = {"Mines", "Interest", "GuardLocal", "GuardRoaming", "Other"};
    static final String[] RES = {"Gold", "Shards", "Wood", "Stone"};

    public static void main(String[] args) throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        SaveFileData main;
        try (FileInputStream fis = new FileInputStream(args[0]);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            ois.readObject();
            main = (SaveFileData) ois.readObject();
        }
        SaveFileData p = main.readSubData("player");
        if (!p.containsKey("ledgerWeek")) { System.out.println("no ledger in this save"); return; }
        int week = p.readInt("ledgerWeek");
        System.out.println("ledgerWeek = " + week + "  (days " + (week * 7) + "-" + (week * 7 + 6) + ")"
                + "   hasLast=" + (p.containsKey("ledgerHasLast") && p.readBool("ledgerHasLast")));
        for (String key : new String[]{"ledgerThisIn", "ledgerThisOut", "ledgerLastIn", "ledgerLastOut"}) {
            if (!p.containsKey(key)) continue;
            String[] v = p.readString(key).split(",");
            System.out.println("\n--- " + key + " ---");
            for (int b = 0; b < BUCKET.length; b++) {
                StringBuilder sb = new StringBuilder();
                for (int r = 0; r < RES.length; r++) {
                    int cell = b * RES.length + r;
                    int n = cell < v.length ? Integer.parseInt(v[cell].trim()) : 0;
                    if (n != 0) sb.append(String.format("%s=%d  ", RES[r], n));
                }
                if (sb.length() > 0) System.out.printf("   %-13s %s%n", BUCKET[b], sb);
            }
        }
    }
}
