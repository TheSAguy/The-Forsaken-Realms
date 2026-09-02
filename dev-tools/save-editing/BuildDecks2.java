import forge.adventure.util.SaveFileData;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Writes TWO decklists into two deck slots of a Forsaken Realms save.
 *
 * Same surgical shape as the round-66 BuildDeck: read the save, replace ONLY the player
 * subdata's "deck_&lt;slot&gt;" / "deck_name_&lt;slot&gt;" entries, write back exactly as
 * WorldSave.save() does (Deflater -> ObjectOutputStream, header then mainData). The world /
 * worldStage / pointOfInterestChanges byte arrays are never deserialized or touched.
 *
 * DRY RUN unless --write. Always takes its own .bak first.
 */
public class BuildDecks2 {

    static final LinkedHashMap<String, Integer> MONO_WHITE = new LinkedHashMap<>();
    static final String MONO_WHITE_NAME = "Dawn Offensive";
    static {
        // lands (18)
        MONO_WHITE.put("Plains", 18);
        // two-drops (10)
        MONO_WHITE.put("Candlegrove Witch", 3);   // 2/2, coven pumps
        MONO_WHITE.put("Unruly Mob", 3);          // grows off your own losses
        MONO_WHITE.put("Cathar Commando", 2);     // 3/1 flash
        MONO_WHITE.put("Freewind Falcon", 2);     // 1/1 flier, pro-red
        // three-drops (9)
        MONO_WHITE.put("Ritual Guardian", 4);     // 3/2
        MONO_WHITE.put("Solitary Camel", 3);      // 3/2, lifelink w/ desert
        MONO_WHITE.put("Inspiring Paladin", 1);   // 3/3 first strike on your turn
        MONO_WHITE.put("Blade Splicer", 1);       // 1/1 + a 3/3 Golem
        // four-drops (3)
        MONO_WHITE.put("Clarion Cathars", 3);     // 3/3 + a 1/1
        // spells (6)
        MONO_WHITE.put("Blessed Defiance", 4);    // +2/+0 and indestructible
        MONO_WHITE.put("Rebuke", 2);              // destroy target attacking creature
    }

    static final LinkedHashMap<String, Integer> WHITE_BLACK = new LinkedHashMap<>();
    static final String WHITE_BLACK_NAME = "Gallows Procession";
    static {
        // lands (18)
        WHITE_BLACK.put("Plains", 10);
        WHITE_BLACK.put("Swamp", 8);
        // creatures (18)
        WHITE_BLACK.put("Vampire Interloper", 2);     // 2/1 flier
        WHITE_BLACK.put("Candlegrove Witch", 3);
        WHITE_BLACK.put("Ritual Guardian", 4);
        WHITE_BLACK.put("Solitary Camel", 3);
        WHITE_BLACK.put("Clarion Cathars", 3);
        WHITE_BLACK.put("Angelic Quartermaster", 3);  // 3/3 flier
        // removal + spells (10)
        WHITE_BLACK.put("Fatal Push", 2);
        WHITE_BLACK.put("Murder", 2);
        WHITE_BLACK.put("Tragic Slip", 1);
        WHITE_BLACK.put("Rebuke", 2);
        WHITE_BLACK.put("Sigarda's Imprisonment", 3);
    }

    public static void main(String[] args) throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        String path = args[0];
        boolean write = args.length > 1 && "--write".equals(args[1]);

        Object header;
        SaveFileData main;
        try (FileInputStream fis = new FileInputStream(path);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            header = ois.readObject();
            main = (SaveFileData) ois.readObject();
        }
        SaveFileData player = main.readSubData("player");
        if (player == null) throw new IllegalStateException("no player subdata");

        String[] owned = (String[]) player.readObject("cards");
        LinkedHashMap<String, List<String[]>> byName = new LinkedHashMap<>();
        for (String raw : owned) {
            if (raw == null || raw.trim().isEmpty()) continue;
            String s = raw.trim();
            int count = 1;
            int sp = s.indexOf(' ');
            if (sp > 0) {
                try { count = Integer.parseInt(s.substring(0, sp)); s = s.substring(sp + 1); }
                catch (NumberFormatException ignored) { }
            }
            String printing = s;
            String name = printing;
            int bar = name.indexOf('|');
            if (bar > 0) name = name.substring(0, bar);
            byName.computeIfAbsent(name.trim(), k -> new ArrayList<>())
                  .add(new String[]{String.valueOf(count), printing});
        }

        List<String> d2 = resolve(byName, MONO_WHITE, 2, MONO_WHITE_NAME);
        List<String> d3 = resolve(byName, WHITE_BLACK, 3, WHITE_BLACK_NAME);
        if (d2 == null || d3 == null) { System.out.println("\nABORT - decklist does not match collection."); return; }

        for (int slot : new int[]{2, 3}) {
            String oldName = player.containsKey("deck_name_" + slot) ? player.readString("deck_name_" + slot) : "(none)";
            Object oldDeck = player.containsKey("deck_" + slot) ? player.readObject("deck_" + slot) : null;
            int oldCount = 0;
            if (oldDeck instanceof String[]) for (String s : (String[]) oldDeck) if (s != null && !s.trim().isEmpty()) oldCount++;
            System.out.println("SLOT " + slot + " CURRENTLY: name=\"" + oldName + "\" entries=" + oldCount
                    + (oldCount == 0 ? "   <-- empty, safe to fill" : "   <-- NOT EMPTY, would be overwritten"));
        }
        System.out.println("deckCount=" + player.readInt("deckCount") + "  selectedDeckIndex=" + player.readInt("selectedDeckIndex"));

        if (!write) { System.out.println("\n[DRY RUN] nothing written. Pass --write to apply."); return; }

        Path src = Paths.get(path), bak = Paths.get(path + ".prededit2.bak");
        Files.copy(src, bak, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("\nbackup -> " + bak);

        player.storeObject("deck_2", d2.toArray(new String[0]));
        player.store("deck_name_2", MONO_WHITE_NAME);
        player.storeObject("deck_3", d3.toArray(new String[0]));
        player.store("deck_name_3", WHITE_BLACK_NAME);
        main.store("player", player);

        Path tmp = Paths.get(path + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp.toFile());
             DeflaterOutputStream def = new DeflaterOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(def)) {
            oos.writeObject(header);
            oos.writeObject(main);
        }
        Files.move(tmp, src, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("WROTE " + src + "  (" + Files.size(src) + " bytes)");
    }

    static List<String> resolve(Map<String, List<String[]>> byName,
                                LinkedHashMap<String, Integer> deck, int slot, String name) {
        System.out.println("\n=== SLOT " + slot + ": \"" + name + "\" ===");
        List<String> lines = new ArrayList<>();
        int total = 0; boolean fatal = false;
        for (Map.Entry<String, Integer> want : deck.entrySet()) {
            String cn = want.getKey(); int need = want.getValue();
            List<String[]> have = byName.get(cn);
            if (have == null) { System.out.println("  !! NOT OWNED: " + cn); fatal = true; continue; }
            int sum = 0; for (String[] e : have) sum += Integer.parseInt(e[0]);
            if (sum < need) { System.out.println("  !! ONLY " + sum + " of " + need + ": " + cn); fatal = true; continue; }
            int rem = need;
            for (String[] e : have) {
                if (rem <= 0) break;
                int take = Math.min(Integer.parseInt(e[0]), rem);
                lines.add(take + " " + e[1]);
                rem -= take;
            }
            total += need;
            System.out.println("  ok  " + need + "x " + cn);
        }
        System.out.println("  TOTAL: " + total + " cards, " + lines.size() + " entries");
        return fatal ? null : lines;
    }
}
