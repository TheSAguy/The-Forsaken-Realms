package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import forge.adventure.data.BiomeData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.WorldData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.PlayerStatistic;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Round 897: what the Realm Journal (JournalScene) knows - the user: "I like the Realm Journal. Let's also add that you
 * can see the decks of enemies you've fought." Design and picks: notes/realm-journal-proposal.md.
 * <ul>
 *   <li>Every fact goes through TfrPlates.knowledge, the plates' own answer, so the Journal is never a cheat sheet: 2 this
 *   rank battled (colors, life, deck), 1 its family (colors), 0 nothing.</li>
 *   <li>The Field Guide's list is the non-boss, non-Archmage names on the land rosters with a spawn rate, built here so
 *   new content and the content filter stay right. A creature counts once beaten at any rank.</li>
 *   <li>Decks: a family plays another deck at each rank, so the gate is per rank, and a duel's start opens it. A rank
 *   with one .dck is rebuilt the way the duel builds it, on the button press only. Any other (rolled .json, several
 *   .dck) has no list to rebuild, so DuelScene keeps the latest deck fought, in PlayerStatistic.</li>
 * </ul>
 * Logs [TFR-Journal]. ConfigData.realmJournalEnabled turns it on.
 */
public final class RealmJournal {
    private static Map<String, Set<String>> roster;

    private RealmJournal() {
    }

    public static boolean enabled() {
        return Config.instance().getConfigData().realmJournalEnabled;
    }

    /** Name -> the lands it roams, for the creatures the Field Guide counts. */
    private static Map<String, Set<String>> roster() {
        if (roster != null)
            return roster;
        Map<String, Set<String>> built = new TreeMap<>();
        for (BiomeData biome : WorldSave.getCurrentSave().getWorld().getData().GetBiomes()) {
            if (biome == null || biome.enemies == null || !LAND_LIST.contains(biome.name))
                continue;
            for (String name : biome.enemies) {
                EnemyData d = WorldData.getEnemy(name);
                if (d == null || d.boss || LegendSpawns.isMember(d) || d.spawnRate <= 0f || EnemyData.tierRank(d.tier) >= 3
                        || !ContentFilterTables.isEnemyIncluded(name))
                    continue;
                built.computeIfAbsent(name, k -> new TreeSet<>()).add(biome.name);
            }
        }
        roster = built;
        System.out.println("[TFR-Journal] Field Guide: " + built.size() + " creatures on the land rosters");
        return roster;
    }

    public static int rosterSize() {
        return roster().size();
    }

    /** The land rosters' size for one land. */
    public static int rosterSize(String land) {
        int n = 0;
        for (Set<String> lands : roster().values())
            if (lands.contains(land))
                n++;
        return n;
    }

    public static boolean counts(String name) {
        return name != null && roster().containsKey(name);
    }

    /** The lands a creature roams, empty for one that does not. */
    public static Set<String> lands(String name) {
        Set<String> lands = roster().get(name);
        return lands == null ? new TreeSet<>() : lands;
    }

    /** The Field Guide's names on this land that the profile has recorded; every land's with land null. */
    public static int recorded(Set<String> seen, String land) {
        int n = 0;
        for (String name : seen)
            if (counts(name) && (land == null || lands(name).contains(land)))
                n++;
        return n;
    }

    public static int legendCount() {
        int n = 0;
        for (EnemyData d : new com.badlogic.gdx.utils.Array.ArrayIterator<>(WorldData.getAllEnemies()))
            if (d != null && LegendSpawns.isMember(d))
                n++;
        return n;
    }

    /** One creature's page: its catalog entry, its ranks low to high, its record in this save. */
    public static final class Entry {
        public EnemyData catalog;
        public String name;
        public int wins, losses;
        /** In the profile's list only: beaten in another save or run. The plates forget it, and the Journal shows no fact. */
        public boolean earlierRun;
        public final List<EnemyData> ranks = new ArrayList<>();

        public boolean bossTab() {
            return catalog.boss || LegendSpawns.isMember(catalog);
        }

        public int fights() {
            return wins + losses;
        }
    }

    /** Everything this save met, and what the profile's two lists recall of other runs. */
    public static List<Entry> entries(AdventurePlayer player, Set<String> fieldGuide, Set<String> legends) {
        PlayerStatistic stats = player.getStatistic();
        Map<String, Entry> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, Pair<Integer, Integer>> e : stats.getWinLossRecord().entrySet()) {
            Entry entry = entry(out, e.getKey(), false);
            if (entry != null && e.getValue() != null) {
                entry.wins = e.getValue().getLeft() == null ? 0 : e.getValue().getLeft();
                entry.losses = e.getValue().getRight() == null ? 0 : e.getValue().getRight();
            }
        }
        for (String key : stats.getRanksBattled()) {
            int bar = key.lastIndexOf('|');
            entry(out, bar < 0 ? key : key.substring(0, bar), false);
        }
        for (String name : fieldGuide)
            entry(out, name, true);
        for (String name : legends)
            entry(out, name, true);
        return new ArrayList<>(out.values());
    }

    private static Entry entry(Map<String, Entry> out, String name, boolean earlierRun) {
        Entry have = out.get(name);
        if (have != null)
            return have;
        EnemyData catalog = WorldData.getEnemy(name);
        if (catalog == null)
            return null; // a renamed enemy drops out, as on Statistics
        Entry e = new Entry();
        e.catalog = catalog;
        e.name = catalog.getName();
        e.earlierRun = earlierRun;
        e.ranks.addAll(EnemyTiers.expand(catalog));
        out.put(name, e);
        return e;
    }

    /** The plates' answer for this rank: 2 battled, 1 its family, 0 nothing. */
    public static int knowledge(EnemyData rank) {
        return TfrPlates.knowledge(rank, rank.getName());
    }

    /** True when this rank plays one .dck and only that, so what is rebuilt is what is played. */
    public static boolean fixedDeck(EnemyData rank) {
        if (rank.copyPlayerDeck || rank.deck == null || rank.deck.length != 1 || rank.deck[0] == null
                || !rank.deck[0].endsWith(".dck"))
            return false;
        FileHandle file = Config.instance().getFile(rank.deck[0]);
        return file != null && file.exists();
    }

    /** DuelScene, beside recordRankBattled, for the player's own duels: the seat's deck, when it has no fixed list. */
    public static void recordFought(PlayerStatistic stats, EnemyData enemy, Deck deck) {
        if (enabled() && deck != null && enemy != null && !enemy.copyPlayerDeck && enemy.fixedDeck == null && !fixedDeck(enemy))
            stats.recordFoughtDeck(enemy, deck);
    }

    /** A rank's deck to show, built now - the duel's own path for a fixed one, the snapshot else - or null. A new object
     *  each time: DeckPreviewScene keeps its screen while it is handed the same Deck. Main deck only: the duel plays that. */
    public static Deck deckToShow(EnemyData rank) {
        AdventurePlayer player = Current.player();
        Deck built;
        boolean fixed = fixedDeck(rank);
        if (fixed) {
            boolean useGeneticAI = Config.instance().getConfigData().enableGeneticAI
                    && (player.isUsingCustomDeck() || player.isHardorInsaneDifficulty()); // as DuelScene.enter
            built = rank.generateDeck(player.isFantasyMode(), useGeneticAI);
        } else {
            built = player.getStatistic().foughtDeck(rank.getName(), rank.tier);
        }
        if (built == null)
            return null;
        Deck shown = new Deck(rank.getDisplayName());
        shown.getMain().addAll(built.getMain());
        System.out.println("[TFR-Journal] deck view: " + rank.getName() + " (" + EnemyData.tierDisplayName(rank.tier) + "), "
                + (fixed ? "fixed list" : "last deck fought") + ", " + shown.getMain().countAll() + " cards");
        return shown;
    }

    /** The view's caption: what the page is, the creature and its rank. */
    public static String caption(EnemyData rank) {
        String who = rank.getDisplayName() + " - " + EnemyData.tierDisplayName(rank.tier);
        return fixedDeck(rank) ? who : "Last deck fought: " + who;
    }

    /** The filter row's lands, as the biomes name them; {@link #LAND_PIPS} holds each one's mana letter. */
    public static final String[] LANDS = {"white", "blue", "black", "red", "green", "waste"};
    private static final List<String> LAND_LIST = java.util.Arrays.asList(LANDS);
    public static final String LAND_PIPS = "WUBRGC";
}
