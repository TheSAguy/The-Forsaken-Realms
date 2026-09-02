import forge.adventure.util.SaveFileData;

import java.io.*;
import java.util.*;
import java.util.zip.InflaterInputStream;

/**
 * READ-ONLY inspector for a Forsaken Realms .sav file. Opens nothing for write.
 * Mirrors WorldSave.load()'s own stream shape: Inflater -> ObjectInputStream,
 * header object first, then the main SaveFileData.
 */
public class DumpSave {

    public static void main(String[] args) throws Exception {
        // The save HEADER embeds a preview screenshot as a libGDX Pixmap, whose readObject calls
        // into the gdx2d native lib - without this the very first readObject() dies with
        // UnsatisfiedLinkError before we ever reach the player data.
        com.badlogic.gdx.utils.GdxNativesLoader.load();
        String path = args[0];
        try (FileInputStream fis = new FileInputStream(path);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {

            Object header = ois.readObject();
            System.out.println("HEADER: " + header.getClass().getName());
            try {
                for (java.lang.reflect.Field f : header.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v = f.get(header);
                    if (v != null && !(v instanceof byte[]))
                        System.out.println("   " + f.getName() + " = " + v);
                }
            } catch (Throwable t) { /* header detail is nice-to-have only */ }

            SaveFileData main = (SaveFileData) ois.readObject();
            System.out.println("\nTOP-LEVEL KEYS: " + new TreeSet<>(main.keySet()));

            SaveFileData p = main.readSubData("player");
            if (p == null) {
                System.out.println("!! no player subdata");
                return;
            }

            System.out.println("\n================ PLAYER ================");
            str(p, "name");
            str(p, "heroRace");
            str(p, "difficultyName");
            in(p, "life");
            in(p, "maxLife");
            in(p, "startingLife");
            in(p, "lifeLoss");
            in(p, "townLifeBonus");
            in(p, "gold");
            in(p, "wood");
            in(p, "stone");
            in(p, "shards");
            in(p, "deckCount");
            in(p, "selectedDeckIndex");
            in(p, "avatarIndex");
            bool(p, "fantasyMode");
            bool(p, "usingCustomDeck");
            obj(p, "colorIdentity");
            obj(p, "deckName");

            // ---- collection ----
            Object cardsObj = p.readObject("cards");
            String[] cards = (String[]) cardsObj;
            System.out.println("\n================ COLLECTION ================");
            System.out.println("raw entries: " + (cards == null ? 0 : cards.length));
            if (cards != null) {
                // Entries look like "<count> <Card Name>|<SET>|<art>" - aggregate by name.
                Map<String, Integer> byName = new TreeMap<>();
                int total = 0;
                for (String c : cards) {
                    if (c == null || c.trim().isEmpty()) continue;
                    String s = c.trim();
                    int count = 1;
                    int sp = s.indexOf(' ');
                    if (sp > 0) {
                        try {
                            count = Integer.parseInt(s.substring(0, sp));
                            s = s.substring(sp + 1);
                        } catch (NumberFormatException ignored) { }
                    }
                    String name = s;
                    int bar = name.indexOf('|');
                    if (bar > 0) name = name.substring(0, bar);
                    byName.merge(name.trim(), count, Integer::sum);
                    total += count;
                }
                System.out.println("total physical cards: " + total);
                System.out.println("distinct names: " + byName.size());
                System.out.println("--- BEGIN CARDS ---");
                for (Map.Entry<String, Integer> e : byName.entrySet())
                    System.out.println(e.getValue() + "x " + e.getKey());
                System.out.println("--- END CARDS ---");
            }

            // ---- current decks ----
            System.out.println("\n================ DECKS ================");
            for (String key : new TreeSet<>(p.keySet())) {
                if (key.toLowerCase().contains("deck")) {
                    byte[] raw = p.get(key);
                    System.out.println("[key] " + key + "  (" + (raw == null ? 0 : raw.length) + " bytes)");
                }
            }
            Object deckCards = p.readObject("deckCards");
            describeDeck("deckCards", deckCards);
            Object sideboard = p.readObject("sideBoardCards");
            describeDeck("sideBoardCards", sideboard);
        }
    }

    static void describeDeck(String label, Object o) {
        if (o == null) { System.out.println(label + ": null"); return; }
        System.out.println(label + ": " + o.getClass().getName());
        if (o instanceof String[][]) {
            String[][] arr = (String[][]) o;
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] == null || arr[i].length == 0) continue;
                int n = 0;
                for (String s : arr[i]) {
                    if (s == null || s.trim().isEmpty()) continue;
                    int sp = s.trim().indexOf(' ');
                    try { n += sp > 0 ? Integer.parseInt(s.trim().substring(0, sp)) : 1; } catch (Exception e) { n += 1; }
                }
                System.out.println("  deck[" + i + "] entries=" + arr[i].length + " cards=" + n);
                for (String s : arr[i]) if (s != null && !s.trim().isEmpty()) System.out.println("      " + s);
            }
        } else if (o instanceof String[]) {
            for (String s : (String[]) o) if (s != null && !s.trim().isEmpty()) System.out.println("      " + s);
        }
    }

    static void str(SaveFileData d, String k) {
        if (d.containsKey(k)) System.out.println(pad(k) + d.readString(k));
    }
    static void in(SaveFileData d, String k) {
        if (d.containsKey(k)) System.out.println(pad(k) + d.readInt(k));
    }
    static void bool(SaveFileData d, String k) {
        if (d.containsKey(k)) System.out.println(pad(k) + d.readBool(k));
    }
    static void obj(SaveFileData d, String k) {
        if (!d.containsKey(k)) return;
        Object o = d.readObject(k);
        if (o instanceof Object[]) System.out.println(pad(k) + Arrays.toString((Object[]) o));
        else System.out.println(pad(k) + o);
    }
    static String pad(String k) {
        StringBuilder sb = new StringBuilder(k).append(':');
        while (sb.length() < 22) sb.append(' ');
        return sb.toString();
    }
}
