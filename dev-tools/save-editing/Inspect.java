import forge.adventure.util.SaveFileData;

import java.io.*;
import java.util.*;
import java.util.zip.InflaterInputStream;

/** Read-only: dumps deck slots and the collection WITH counts. Never writes. */
public class Inspect {
    public static void main(String[] args) throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        String path = args[0];

        SaveFileData main;
        try (FileInputStream fis = new FileInputStream(path);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            ois.readObject();                      // header
            main = (SaveFileData) ois.readObject();
        }
        SaveFileData p = main.readSubData("player");

        System.out.println("=== PLAYER ===");
        for (String k : new String[]{"name", "life", "maxLife", "gold", "shards", "wood", "stone",
                                     "deckCount", "selectedDeckIndex"}) {
            if (!p.containsKey(k)) { System.out.println("  " + k + " = (absent)"); continue; }
            Object v;
            try { v = p.readInt(k); } catch (Exception e) {
                try { v = p.readString(k); } catch (Exception e2) { v = "(unreadable)"; }
            }
            System.out.println("  " + k + " = " + v);
        }

        System.out.println("\n=== DECK SLOTS ===");
        for (int slot = 0; slot < 10; slot++) {
            boolean hasDeck = p.containsKey("deck_" + slot);
            boolean hasName = p.containsKey("deck_name_" + slot);
            if (!hasDeck && !hasName) continue;
            String nm = hasName ? p.readString("deck_name_" + slot) : "(unnamed)";
            Object o = hasDeck ? p.readObject("deck_" + slot) : null;
            int entries = 0, cards = 0;
            if (o instanceof String[]) {
                for (String s : (String[]) o) {
                    if (s == null || s.trim().isEmpty()) continue;
                    entries++;
                    int sp = s.trim().indexOf(' ');
                    try { cards += sp > 0 ? Integer.parseInt(s.trim().substring(0, sp)) : 1; }
                    catch (NumberFormatException e) { cards += 1; }
                }
            }
            System.out.printf("  slot %d : \"%s\"  entries=%d cards=%d%s%n",
                    slot, nm, entries, cards, entries == 0 ? "   <-- EMPTY" : "");
        }

        System.out.println("\n=== COLLECTION (with counts) ===");
        String[] owned = (String[]) p.readObject("cards");
        TreeMap<String, Integer> byName = new TreeMap<>();
        int totalCards = 0;
        for (String raw : owned) {
            if (raw == null || raw.trim().isEmpty()) continue;
            String s = raw.trim();
            int count = 1;
            int sp = s.indexOf(' ');
            if (sp > 0) {
                try { count = Integer.parseInt(s.substring(0, sp)); s = s.substring(sp + 1); }
                catch (NumberFormatException ignored) { }
            }
            String name = s;
            int bar = name.indexOf('|');
            if (bar > 0) name = name.substring(0, bar);
            byName.merge(name.trim(), count, Integer::sum);
            totalCards += count;
        }
        System.out.println("  distinct names : " + byName.size());
        System.out.println("  total cards    : " + totalCards);
        System.out.println("  printings rows : " + owned.length);
        System.out.println("\n  --- FULL COLLECTION ---");
        byName.forEach((k, v) -> System.out.printf("   %3dx %s%n", v, k));

    }
}
