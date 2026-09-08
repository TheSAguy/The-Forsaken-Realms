import forge.adventure.util.SaveFileData;

import java.io.*;
import java.util.*;
import java.util.zip.InflaterInputStream;

/** Read-only: deck slot CONTENTS plus the roaming-guard roster and the decks they hold. */
public class Decks {
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

        for (int slot = 0; slot < 10; slot++) {
            if (!p.containsKey("deck_" + slot)) continue;
            Object o = p.readObject("deck_" + slot);
            if (!(o instanceof String[]) || ((String[]) o).length == 0) continue;
            String nm = p.containsKey("deck_name_" + slot) ? p.readString("deck_name_" + slot) : "?";
            System.out.println("=== SLOT " + slot + ": " + nm + " ===");
            dump((String[]) o);
        }

        int n = p.containsKey("roamingGuardCount") ? p.readInt("roamingGuardCount") : 0;
        System.out.println("\n=== ROAMING GUARDS (" + n + ") ===");
        for (int i = 0; i < n; i++) {
            SaveFileData g = p.readSubData("roamingGuard_" + i);
            System.out.printf("  [%d] tier=%s life=%s engage=%s deck=\"%s\" deployed=%s poi=%s down=%s%n",
                    i, g.readString("tier"), g.readInt("maxLife"), g.readString("engage"),
                    g.readString("deckName"), g.readBool("deployed"),
                    g.readString("missionPoiId"), g.readInt("downUntilDay"));
            Object c = g.containsKey("deckCards") ? g.readObject("deckCards") : null;
            if (c instanceof String[]) dump((String[]) c);
        }
    }

    private static void dump(String[] rows) {
        int total = 0;
        TreeMap<String, Integer> byName = new TreeMap<>();
        for (String raw : rows) {
            if (raw == null || raw.trim().isEmpty()) continue;
            String s = raw.trim();
            int count = 1, sp = s.indexOf(' ');
            if (sp > 0) {
                try { count = Integer.parseInt(s.substring(0, sp)); s = s.substring(sp + 1); }
                catch (NumberFormatException ignored) { }
            }
            int bar = s.indexOf('|');
            byName.merge(bar > 0 ? s.substring(0, bar).trim() : s.trim(), count, Integer::sum);
            total += count;
        }
        for (Map.Entry<String, Integer> e : byName.entrySet())
            System.out.println("     " + e.getValue() + "x " + e.getKey());
        System.out.println("     -- total " + total + " --");
    }
}
