package forge.adventure.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.tuple.Pair;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * TFR round 991 (logs-1108c P3-13): no booster pack's lowest shelf price falls under what its cards resell for.
 * Reads the real price list, restricted list and edition files, and plays the worst case: the player's own town
 * (0.75) with a Market and the Merchant Guild (0.8), town reputation 20 (0.9), Haggler 3/3 (0.85) and the Fortune
 * Coin (0.85) - 0.390 of the list price - against Easy's sell factor (0.6) with the Appraiser at its cap (1.2), in a
 * research v2 game (town sell factor 1) and in a classic save at town reputation 20 (1.1).
 */
public class PackFloorTest {
    private static final float LOWEST_SHELF = 0.75f * 0.8f * 0.9f * 0.85f * 0.85f;
    private static final float EASY = 0.6f, APPRAISER = 1.2f;

    private static File res() {
        for (String p : new String[] {"../forge-gui/res", "forge-gui/res"}) {
            File f = new File(p);
            if (new File(f, "editions").isDirectory())
                return f;
        }
        throw new IllegalStateException("forge-gui/res not found from " + new File(".").getAbsolutePath());
    }

    private static List<String> lines(File f) throws IOException {
        return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
    }

    private static Map<String, Integer> priceList() throws IOException {
        Map<String, Integer> m = new HashMap<>();
        for (String l : lines(new File(res(), "adventure/common/world/cardprices.txt"))) {
            int eq = l.indexOf('=');
            if (l.trim().isEmpty() || l.startsWith("#") || eq <= 0)
                continue;
            try {
                m.put(l.substring(0, eq).trim(), Integer.parseInt(l.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    private static Set<String> restricted() throws IOException {
        Set<String> s = new HashSet<>();
        File f = new File(res(), "adventure/The Forsaken Realms/config tables/restricted_cards.json");
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(String.join("\n", lines(f)).replaceAll("//[^\n]*", ""));
        while (m.find())
            s.add(m.group(1));
        return s;
    }

    /** One set: its booster template and its [cards] entries as {rarity, name}. */
    private static final class SetFile {
        final List<Pair<String, Integer>> slots = new ArrayList<>();
        final List<String[]> cards = new ArrayList<>();
    }

    private static SetFile set(String fileName) throws IOException {
        SetFile s = new SetFile();
        boolean inCards = false;
        Pattern card = Pattern.compile("^\\S+\\s+([CURMLS])\\s+(.+?)(\\s+@.*)?$");
        for (String raw : lines(new File(res(), "editions/" + fileName + ".txt"))) {
            String l = raw.trim();
            if (l.startsWith("Booster=")) {
                for (String part : l.substring(8).split(",")) {
                    String[] p = part.trim().split(" ", 2);
                    s.slots.add(Pair.of(p[1], Integer.parseInt(p[0])));
                }
            } else if (l.startsWith("[")) {
                inCards = l.equals("[cards]");
            } else if (inCards && !l.isEmpty()) {
                Matcher m = card.matcher(l);
                if (m.matches())
                    s.cards.add(new String[] {m.group(1), m.group(2).trim()});
            }
        }
        return s;
    }

    private static int defaultPrice(String rarity) {
        switch (rarity) {
            case "U": return PackFloor.UNCOMMON;
            case "R": return PackFloor.RARE;
            case "M": return PackFloor.MYTHIC;
            default: return PackFloor.COMMON;
        }
    }

    /** A card's sell price as AdventurePlayer.cardSellPrice works it out, step by step with its truncations. */
    private static int cardResale(int list, float town) {
        int base = (int) (list * EASY);
        base = (int) (base * town);
        return Math.round(base * APPRAISER);
    }

    /** The average resale of a pack's cards, card by card (independent of PackFloor's slot math). */
    private static double bestResale(SetFile s, Map<String, Integer> prices, Set<String> restricted, float town) {
        Map<String, List<Integer>> byRarity = new HashMap<>();
        for (String[] c : s.cards) {
            if (restricted.contains(c[1]) || !"CURM".contains(c[0]))
                continue;
            int list = prices.containsKey(c[1]) ? prices.get(c[1]) : defaultPrice(c[0]);
            byRarity.computeIfAbsent(c[0], k -> new ArrayList<>()).add(cardResale(list, town));
        }
        double total = 0;
        for (Pair<String, Integer> slot : s.slots) {
            String k = slot.getKey().toLowerCase();
            double each;
            if (k.startsWith("raremythic"))
                each = (7 * avg(byRarity, "R", town) + avg(byRarity, "M", town)) / 8;
            else if (k.startsWith("uncommonrare")) {
                List<Integer> both = new ArrayList<>(byRarity.getOrDefault("U", new ArrayList<>()));
                both.addAll(byRarity.getOrDefault("R", new ArrayList<>()));
                each = both.stream().mapToInt(Integer::intValue).average().orElse(0);
            } else if (k.startsWith("mythic"))
                each = avg(byRarity, "M", town);
            else if (k.startsWith("rare"))
                each = avg(byRarity, "R", town);
            else if (k.startsWith("uncommon"))
                each = avg(byRarity, "U", town);
            else if (k.contains("land"))
                each = cardResale(PackFloor.LAND, town);
            else
                each = avg(byRarity, "C", town);
            total += each * slot.getValue();
        }
        return total;
    }

    private static double avg(Map<String, List<Integer>> byRarity, String r, float town) {
        List<Integer> l = byRarity.get(r);
        return l == null || l.isEmpty() ? cardResale(defaultPrice(r), town) : l.stream().mapToInt(Integer::intValue).average().orElse(0);
    }

    /** The set's cards as the game feeds them to the floor: each at its sell price in this town. */
    private static PackFloor.SetPrices setSells(SetFile s, Map<String, Integer> prices, Set<String> restricted, float town) {
        PackFloor.SetPrices p = new PackFloor.SetPrices(list -> cardResale(list, town));
        for (String[] c : s.cards)
            if (!restricted.contains(c[1]))
                p.add(c[0].charAt(0), prices.containsKey(c[1]) ? prices.get(c[1]) : defaultPrice(c[0]));
        return p;
    }

    /** The shelf price as RewardScene.BuyButton sets it: the list price times the factor, raised to the floor + 1. */
    private static int lowestShelf(int listPrice, int floor) {
        int price = (int) (listPrice * LOWEST_SHELF);
        return price < floor + 1 ? floor + 1 : price;
    }

    @Test
    public void noListedPackSellsAtAProfitAtTheLowestShelfPrice() throws IOException {
        Map<String, Integer> prices = priceList();
        Set<String> restricted = restricted();
        String[][] sets = {
                {"LEG", "Legends"}, {"LEA", "Limited Edition Alpha"}, {"LEB", "Limited Edition Beta"},
                {"ARN", "Arabian Nights"}, {"FEM", "Fallen Empires"}, {"THS", "Theros"}, {"M14", "Magic 2014"}};
        for (String[] code : sets) {
            SetFile s = set(code[1]);
            int listPrice = prices.getOrDefault(code[0], 1000);
            for (float town : new float[] {1f, 1.1f}) {
                int floor = Math.round(PackFloor.packValue(s.slots, setSells(s, prices, restricted, town)));
                int shelf = lowestShelf(listPrice, floor);
                double resale = bestResale(s, prices, restricted, town);
                AssertJUnit.assertTrue(code[0] + " (town " + town + "): shelf " + shelf + " vs resale " + resale,
                        shelf > resale);
            }
        }
    }

    @Test
    public void theOldSetsSitABitAboveANormalPack() throws IOException {
        // The user's "rare old set" price: above the 1,000 of a normal pack, and nowhere near the old 50,000.
        Map<String, Integer> prices = priceList();
        AssertJUnit.assertEquals(2000, (int) prices.get("LEG"));
        AssertJUnit.assertEquals(3000, (int) prices.get("LEA"));
        AssertJUnit.assertEquals(3000, (int) prices.get("LEB"));
        AssertJUnit.assertEquals(200, (int) prices.get("FEM"));
    }

    @Test
    public void aSetWithNoListedCardKeepsRound932sFloor() {
        // Round 932's flat prices: 10 commons 500, 3 uncommons 450, a rare-or-mythic (7 x 300 + 500) / 8 = 325, a land 5.
        List<Pair<String, Integer>> slots = new ArrayList<>();
        slots.add(Pair.of("Common", 10));
        slots.add(Pair.of("Uncommon", 3));
        slots.add(Pair.of("RareMythic", 1));
        slots.add(Pair.of("BasicLand", 1));
        PackFloor.SetPrices flat = new PackFloor.SetPrices().add('C', 50).add('U', 150).add('R', 300).add('M', 500);
        AssertJUnit.assertEquals(1280f, PackFloor.packValue(slots, flat), 0.001f);
        AssertJUnit.assertEquals(1280f, PackFloor.packValue(slots, new PackFloor.SetPrices()), 0.001f);
        // In sell terms (Easy, full Appraiser, v2): a common 30 -> 36, uncommon 90 -> 108, rare 180 -> 216, mythic
        // 300 -> 360, a land 3 -> 4: 360 + 324 + (7 x 216 + 360) / 8 + 4 = 922.
        AssertJUnit.assertEquals(922f, PackFloor.packValue(slots, new PackFloor.SetPrices(l -> cardResale(l, 1f))), 0.001f);
        AssertJUnit.assertEquals(225f, PackFloor.slotValue("UncommonRare", new PackFloor.SetPrices()), 0.001f);
    }

    @Test
    public void aListedCardRaisesItsSlotOnly() {
        PackFloor.SetPrices p = new PackFloor.SetPrices().add('U', 150).add('U', 150).add('U', 5100).add('R', 300);
        AssertJUnit.assertEquals(1800f, PackFloor.slotValue("Uncommon", p), 0.001f);
        AssertJUnit.assertEquals(300f, PackFloor.slotValue("Rare", p), 0.001f);
        AssertJUnit.assertEquals(50f, PackFloor.slotValue("Common", p), 0.001f);
        AssertJUnit.assertEquals((150 + 150 + 5100 + 300) / 4f, PackFloor.slotValue("UncommonRare", p), 0.001f);
        AssertJUnit.assertEquals(5f, PackFloor.slotValue("BasicLand", p), 0.001f);
    }
}
