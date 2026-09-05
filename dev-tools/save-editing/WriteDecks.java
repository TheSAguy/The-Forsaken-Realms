import forge.adventure.util.SaveFileData;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Generic deck writer for a Forsaken Realms save (same surgical shape as dev-tools/save-editing/BuildDecks2):
 *   java WriteDecks <save.sav> [--write] [--show] slot=<n>:<Deck Name>:<listfile> ...
 * A list file holds "<count> <Card Name>" lines (# comments allowed). Cards resolve to the printings the
 * player owns; basic lands (Plains/Island/Swamp/Mountain/Forest) fall back to a plain "<n> <Name>" row when
 * the collection has fewer than requested. Dry run unless --write; always writes <save>.prededit3.bak first.
 * Only player.deck_<n> / deck_name_<n> change; world / worldStage / pointOfInterestChanges bytes pass through.
 */
public class WriteDecks {
    static final Set<String> BASICS = new HashSet<>(Arrays.asList("Plains", "Island", "Swamp", "Mountain", "Forest"));
    // starting basics in this plane are Star Trek (TRK) prints; free copies are unsellable, exactly as the editor's Add Basic Lands does
    static final Map<String, String> FREE_BASIC = new HashMap<>();
    static { FREE_BASIC.put("Plains", "Plains|TRK|[317]|#{noSellValue=true}"); FREE_BASIC.put("Island", "Island|TRK|[319]|#{noSellValue=true}");
             FREE_BASIC.put("Swamp", "Swamp|TRK|[321]|#{noSellValue=true}"); FREE_BASIC.put("Mountain", "Mountain|TRK|[323]|#{noSellValue=true}");
             FREE_BASIC.put("Forest", "Forest|TRK|[325]|#{noSellValue=true}"); }
    static final LinkedHashMap<String, Integer> NEW_BASICS = new LinkedHashMap<>();   // free basics the collection must gain

    public static void main(String[] args) throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        String path = args[0];
        boolean write = false, show = false;
        List<String[]> jobs = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if ("--write".equals(args[i])) write = true;
            else if ("--show".equals(args[i])) show = true;
            else if (args[i].startsWith("slot=")) {
                String[] parts = args[i].substring(5).split(":", 3);
                jobs.add(parts);
            }
        }
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
        System.out.println("life=" + player.readInt("life") + "/" + player.readInt("maxLife") + " gold=" + player.readInt("gold")
                + " deckCount=" + player.readInt("deckCount") + " selectedDeckIndex=" + player.readInt("selectedDeckIndex"));

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
            byName.computeIfAbsent(name.trim(), k -> new ArrayList<>()).add(new String[]{String.valueOf(count), printing});
        }
        if (show) {
            for (String raw : owned) if (raw != null && (raw.contains("Plains") || raw.contains("Swamp") || raw.contains("noSell"))) System.out.println("COLLECTION ROW: " + raw);
            for (int slot = 0; slot < player.readInt("deckCount"); slot++) {
                String nm = player.containsKey("deck_name_" + slot) ? player.readString("deck_name_" + slot) : "(none)";
                Object d = player.containsKey("deck_" + slot) ? player.readObject("deck_" + slot) : null;
                if (d instanceof String[] && ((String[]) d).length > 0) {
                    System.out.println("--- slot " + slot + " \"" + nm + "\" ---");
                    for (String row : (String[]) d) System.out.println("    " + row);
                }
            }
        }
        Map<Integer, String[]> resolved = new LinkedHashMap<>();
        Map<Integer, String> names = new LinkedHashMap<>();
        boolean fatal = false;
        for (String[] job : jobs) {
            int slot = Integer.parseInt(job[0]);
            String deckName = job[1];
            List<String> lines = new ArrayList<>();
            int total = 0;
            System.out.println("\n=== SLOT " + slot + ": \"" + deckName + "\" from " + job[2] + " ===");
            for (String l : Files.readAllLines(Paths.get(job[2]))) {
                l = l.trim();
                if (l.isEmpty() || l.startsWith("#")) continue;
                int sp = l.indexOf(' ');
                int need = Integer.parseInt(l.substring(0, sp));
                String cn = l.substring(sp + 1).trim();
                List<String[]> have = byName.get(cn);
                int sum = 0;
                if (have != null) for (String[] e : have) sum += Integer.parseInt(e[0]);
                int rem = need;
                if (have != null) {
                    for (String[] e : have) {
                        if (rem <= 0) break;
                        int take = Math.min(Integer.parseInt(e[0]), rem);
                        lines.add(take + " " + e[1]);
                        rem -= take;
                    }
                }
                if (rem > 0) {
                    if (BASICS.contains(cn)) { lines.add(rem + " " + FREE_BASIC.get(cn)); NEW_BASICS.merge(cn, rem, Math::max); System.out.println("  (basic) " + rem + " free unsellable " + cn + " beyond the " + sum + " owned"); }
                    else { System.out.println("  !! ONLY " + sum + " of " + need + ": " + cn); fatal = true; }
                }
                total += need;
            }
            System.out.println("  " + total + " cards, " + lines.size() + " rows");
            for (String r : lines) System.out.println("    " + r);
            String oldName = player.containsKey("deck_name_" + slot) ? player.readString("deck_name_" + slot) : "(none)";
            Object oldDeck = player.containsKey("deck_" + slot) ? player.readObject("deck_" + slot) : null;
            int oldCount = 0; if (oldDeck instanceof String[]) for (String r : (String[]) oldDeck) if (r != null && !r.trim().isEmpty()) oldCount++;
            System.out.println("  slot " + slot + " currently: \"" + oldName + "\" entries=" + oldCount + (oldCount == 0 ? "  <-- empty, safe to fill" : "  <-- NOT EMPTY, would be overwritten"));
            resolved.put(slot, lines.toArray(new String[0]));
            names.put(slot, deckName);
        }
        if (fatal) { System.out.println("\nABORT - a list does not match the collection."); return; }
        if (!NEW_BASICS.isEmpty()) System.out.println("free basics the collection gains (largest shortfall per land, decks share the pool): " + NEW_BASICS);
        if (!write) { System.out.println("\n[DRY RUN] nothing written. Pass --write to apply."); return; }

        Path src = Paths.get(path), bak = Paths.get(path + ".prededit3.bak");
        Files.copy(src, bak, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("\nbackup -> " + bak);
        if (!NEW_BASICS.isEmpty()) {
            List<String> rows = new ArrayList<>(Arrays.asList(owned));
            for (Map.Entry<String, Integer> nb : NEW_BASICS.entrySet()) rows.add(nb.getValue() + " " + FREE_BASIC.get(nb.getKey()));
            player.storeObject("cards", rows.toArray(new String[0]));
            System.out.println("collection: " + owned.length + " rows -> " + rows.size() + " rows");
        }
        for (Map.Entry<Integer, String[]> e : resolved.entrySet()) {
            player.storeObject("deck_" + e.getKey(), e.getValue());
            player.store("deck_name_" + e.getKey(), names.get(e.getKey()));
        }
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
        // verify by re-reading
        try (FileInputStream fis = new FileInputStream(path);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            ois.readObject();
            SaveFileData m2 = (SaveFileData) ois.readObject();
            SaveFileData p2 = m2.readSubData("player");
            System.out.println("VERIFY life=" + p2.readInt("life") + " gold=" + p2.readInt("gold") + " cards=" + ((String[]) p2.readObject("cards")).length);
            for (int slot = 0; slot < p2.readInt("deckCount"); slot++) {
                Object d = p2.containsKey("deck_" + slot) ? p2.readObject("deck_" + slot) : null;
                System.out.println("VERIFY slot " + slot + " \"" + p2.readString("deck_name_" + slot) + "\" rows=" + (d instanceof String[] ? ((String[]) d).length : 0));
            }
        }
    }
}
