import forge.adventure.util.SaveFileData;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Round 148. Edits the decks HELD BY ROAMING GUARDS, which WriteDecks cannot reach - those live in
 * player.roamingGuard_&lt;i&gt;.deckCards, not in a deck slot.
 *
 *   java FixGuardDecks &lt;save.sav&gt; [--write] pull=&lt;guard&gt;:&lt;Card Name&gt; add=&lt;guard&gt;:&lt;n&gt;:&lt;Basic&gt; ...
 *
 * pull  removes one copy of a card from that guard's deck and puts the exact printing back into the
 *       player's collection, so nothing is destroyed.
 * add   appends free unsellable TRK basics to that guard's deck, the same trick WriteDecks uses for
 *       the editor's Add Basic Lands - for topping a deck back up to the legal minimum.
 *
 * Dry run unless --write; always takes a .preguard&lt;N&gt;.bak first, and re-reads to verify.
 */
public class FixGuardDecks {
    static final Map<String, String> FREE_BASIC = new HashMap<>();
    static {
        FREE_BASIC.put("Plains", "Plains|TRK|[317]|#{noSellValue=true}");
        FREE_BASIC.put("Island", "Island|TRK|[319]|#{noSellValue=true}");
        FREE_BASIC.put("Swamp", "Swamp|TRK|[321]|#{noSellValue=true}");
        FREE_BASIC.put("Mountain", "Mountain|TRK|[323]|#{noSellValue=true}");
        FREE_BASIC.put("Forest", "Forest|TRK|[325]|#{noSellValue=true}");
    }

    public static void main(String[] args) throws Exception {
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        String path = args[0];
        boolean write = false;
        List<String[]> pulls = new ArrayList<>(), adds = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if ("--write".equals(args[i])) write = true;
            else if (args[i].startsWith("pull=")) pulls.add(args[i].substring(5).split(":", 2));
            else if (args[i].startsWith("add=")) adds.add(args[i].substring(4).split(":", 3));
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
        int count = player.containsKey("roamingGuardCount") ? player.readInt("roamingGuardCount") : 0;
        System.out.println("roaming guards: " + count);

        List<String> collection = new ArrayList<>(Arrays.asList((String[]) player.readObject("cards")));
        Map<Integer, SaveFileData> guards = new LinkedHashMap<>();
        Map<Integer, List<String>> decks = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            SaveFileData g = player.readSubData("roamingGuard_" + i);
            guards.put(i, g);
            Object cards = g.containsKey("deckCards") ? g.readObject("deckCards") : null;
            List<String> rows = new ArrayList<>();
            if (cards instanceof String[]) rows.addAll(Arrays.asList((String[]) cards));
            decks.put(i, rows);
            System.out.println("  [" + i + "] " + g.readString("tier") + " \"" + g.readString("deckName")
                    + "\" " + total(rows) + " cards in " + rows.size() + " rows");
        }

        boolean fatal = false;
        for (String[] job : pulls) {
            int idx = Integer.parseInt(job[0]);
            String card = job[1];
            List<String> rows = decks.get(idx);
            if (rows == null) { System.out.println("  !! no guard " + idx); fatal = true; continue; }
            String hit = null;
            for (String row : rows) {
                String body = row.substring(row.indexOf(' ') + 1);
                String name = body.contains("|") ? body.substring(0, body.indexOf('|')) : body;
                if (name.trim().equals(card)) { hit = row; break; }
            }
            if (hit == null) { System.out.println("  !! guard " + idx + " has no " + card); fatal = true; continue; }
            int n = Integer.parseInt(hit.substring(0, hit.indexOf(' ')));
            String body = hit.substring(hit.indexOf(' ') + 1);
            rows.remove(hit);
            if (n > 1) rows.add((n - 1) + " " + body);
            collection.add("1 " + body);
            System.out.println("  guard " + idx + ": pulled 1 " + card + " -> back into the collection as \"1 " + body + "\"");
        }
        for (String[] job : adds) {
            int idx = Integer.parseInt(job[0]);
            int n = Integer.parseInt(job[1]);
            String basic = job[2];
            if (!FREE_BASIC.containsKey(basic)) { System.out.println("  !! not a basic: " + basic); fatal = true; continue; }
            decks.get(idx).add(n + " " + FREE_BASIC.get(basic));
            System.out.println("  guard " + idx + ": added " + n + " free unsellable " + basic);
        }
        for (Map.Entry<Integer, List<String>> e : decks.entrySet())
            System.out.println("  [" + e.getKey() + "] now " + total(e.getValue()) + " cards in " + e.getValue().size() + " rows");
        if (fatal) { System.out.println("\nABORT."); return; }
        if (!write) { System.out.println("\n[DRY RUN] nothing written. Pass --write to apply."); return; }

        Path src = Paths.get(path), bak = Paths.get(path + ".preguard1.bak");
        for (int n = 1; Files.exists(bak); n++) bak = Paths.get(path + ".preguard" + (n + 1) + ".bak");
        Files.copy(src, bak);
        System.out.println("\nbackup -> " + bak);

        player.storeObject("cards", collection.toArray(new String[0]));
        for (Map.Entry<Integer, List<String>> e : decks.entrySet()) {
            SaveFileData g = guards.get(e.getKey());
            g.storeObject("deckCards", e.getValue().toArray(new String[0]));
            player.store("roamingGuard_" + e.getKey(), g);
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

        try (FileInputStream fis = new FileInputStream(path);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            ois.readObject();
            SaveFileData m2 = (SaveFileData) ois.readObject();
            SaveFileData p2 = m2.readSubData("player");
            System.out.println("VERIFY gold=" + p2.readInt("gold") + " collection rows="
                    + ((String[]) p2.readObject("cards")).length);
            for (int i = 0; i < p2.readInt("roamingGuardCount"); i++) {
                SaveFileData g = p2.readSubData("roamingGuard_" + i);
                List<String> rows = Arrays.asList((String[]) g.readObject("deckCards"));
                System.out.println("VERIFY guard " + i + " \"" + g.readString("deckName") + "\" "
                        + total(rows) + " cards, engage=" + g.readString("engage"));
            }
        }
    }

    static int total(List<String> rows) {
        int sum = 0;
        for (String row : rows) {
            if (row == null || row.trim().isEmpty()) continue;
            int sp = row.trim().indexOf(' ');
            try { sum += sp > 0 ? Integer.parseInt(row.trim().substring(0, sp)) : 1; }
            catch (NumberFormatException e) { sum += 1; }
        }
        return sum;
    }
}
