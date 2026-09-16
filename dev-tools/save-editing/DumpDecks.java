import forge.adventure.util.SaveFileData;

import java.io.*;
import java.util.zip.InflaterInputStream;

/**
 * Read-only: prints every deck slot of a save as a plain decklist, so the copy-limit audit
 * (dev-tools/deck_legality_audit.py) can read the decks a character is ACTUALLY carrying rather
 * than only the deck files in the repo. Written for the 2026-09-16 report that a deck built into
 * a save slot held "5 copies of one card and 6 of another".
 *
 * Never writes. Safe to run while the game is open.
 *
 *   java -cp "<game jar>;<this dir>" DumpDecks <save.sav>
 *
 * Output, one block per non-empty slot:
 *   === SLOT 2 "Dawnbreak Tribunal" ===
 *   4 Oblivion Ring
 *   ...
 */
public class DumpDecks {
    public static void main(String[] args) throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        if (args.length < 1) {
            System.out.println("usage: DumpDecks <save.sav>");
            return;
        }
        SaveFileData main;
        try (FileInputStream fis = new FileInputStream(args[0]);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            ois.readObject();                      // header
            main = (SaveFileData) ois.readObject();
        }
        SaveFileData p = main.readSubData("player");

        for (int slot = 0; slot < 10; slot++) {
            if (!p.containsKey("deck_" + slot)) continue;
            Object o = p.readObject("deck_" + slot);
            if (!(o instanceof String[])) continue;
            String[] rows = (String[]) o;
            int entries = 0;
            for (String s : rows) if (s != null && !s.trim().isEmpty()) entries++;
            if (entries == 0) continue;
            String nm = p.containsKey("deck_name_" + slot) ? p.readString("deck_name_" + slot) : "(unnamed)";
            System.out.printf("=== SLOT %d \"%s\" ===%n", slot, nm);
            for (String raw : rows) {
                if (raw == null || raw.trim().isEmpty()) continue;
                // rows look like "4 Oblivion Ring|ROE" - keep the count, drop the printing, so the
                // audit sums by NAME (the same card under two set codes is still the same card).
                String s = raw.trim();
                int count = 1;
                int sp = s.indexOf(' ');
                if (sp > 0) {
                    try {
                        count = Integer.parseInt(s.substring(0, sp));
                        s = s.substring(sp + 1);
                    } catch (NumberFormatException ignored) {
                        // no leading count - treat the whole row as one card
                    }
                }
                int bar = s.indexOf('|');
                if (bar > 0) s = s.substring(0, bar);
                System.out.printf("%d %s%n", count, s.trim());
            }
            System.out.println();
        }
    }
}
