package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.ItemData;
import forge.card.CardEdition;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * User-editable content filter tables (user spec 2026-08-12): three CSV files in the plane's own
 * folder ("config tables/expansions.csv", "items.csv", "enemies.csv"), each row one entity with
 * its key details plus an Include (Y/N) column. Flipping Include to N removes that expansion/
 * item/enemy from the game. Decisions confirmed with the user:
 * <ul>
 *   <li>CSV format (Excel-friendly; RFC-4180 quoting since card/item names contain commas).</li>
 *   <li>Quest-critical content is PROTECTED: a quest item with Include=N stays in the game (the
 *   flag is ignored, with a log line); quest-scripted enemy spawns bypass the enemy filter at the
 *   spawn site; an arena pool that would filter to empty falls back to the unfiltered pool.</li>
 *   <li>Regeneration merges: user edits survive - existing rows keep their Include flag, new
 *   content (game updates) is appended with Include=Y, rows for content that no longer exists
 *   are dropped. Deleting a CSV regenerates it fresh, all-Y.</li>
 * </ul>
 * Opt-in via ConfigData.contentFilterTablesEnabled (CLAUDE.md ground rule) - with the flag off
 * (every stock plane) nothing is generated, read, or filtered.
 * <p>
 * Wiring (deliberately at existing single choke points, not per-call-site):
 * expansions -> {@link #applyEditionExclusions} merges excluded codes into the loaded ConfigData's
 * restrictedEditions at Config-construction time, so every existing consumer (master edition list,
 * booster generation, SpellSmith, token filter) honors it with zero new checks; items ->
 * ItemListData's static loader hands its freshly-loaded list to {@link #filterItems} (generation
 * happens from that same list, so there is no circular dependency); enemies -> WorldData's enemy
 * loader calls {@link #filterEnemies}, and quest/arena call sites consult
 * {@link #isEnemyIncluded} as documented there.
 */
public class ContentFilterTables {
    private static final String DIR = "config tables/";
    private static final String EXPANSIONS_CSV = DIR + "expansions.csv";
    private static final String ITEMS_CSV = DIR + "items.csv";
    private static final String ENEMIES_CSV = DIR + "enemies.csv";

    private static Set<String> excludedEditionCodes;   // null until loaded
    private static Set<String> excludedItemNames;      // lower-cased
    private static Set<String> excludedEnemyNames;     // lower-cased

    public static boolean isEnabled() {
        ConfigData configData = Config.instance().getConfigData();
        return configData != null && configData.contentFilterTablesEnabled;
    }

    // ------------------------------------------------------------------ expansions

    /**
     * Generates/merges the expansions table and folds every Include=N code into the given
     * (in-memory, already-loaded) ConfigData's restrictedEditions - the single funnel every
     * edition-consuming system already honors. Called once from Config's constructor, right
     * after config.json is parsed and before applyTokenEditionFilter().
     */
    public static void applyEditionExclusions(ConfigData configData) {
        if (configData == null || !configData.contentFilterTablesEnabled)
            return;
        try {
            excludedEditionCodes = loadOrRegenerateExpansions();
        } catch (Exception e) {
            System.err.println("[ContentFilter] expansions table failed, no exclusions applied: " + e);
            excludedEditionCodes = new HashSet<>();
            return;
        }
        if (excludedEditionCodes.isEmpty())
            return;
        Set<String> merged = new HashSet<>();
        if (configData.restrictedEditions != null)
            merged.addAll(Arrays.asList(configData.restrictedEditions));
        merged.addAll(excludedEditionCodes);
        configData.restrictedEditions = merged.toArray(new String[0]);
        System.out.println("[ContentFilter] " + excludedEditionCodes.size()
                + " expansion(s) excluded via config table: " + excludedEditionCodes);
    }

    private static Set<String> loadOrRegenerateExpansions() {
        Map<String, String[]> existing = readCsv(EXPANSIONS_CSV);
        // Row key = edition code. Columns: Code,Name,Type,CardCount,ReleaseDate,Include
        Map<String, String[]> rows = new LinkedHashMap<>();
        for (CardEdition edition : FModel.getMagicDb().getEditions()) {
            if (edition.getObtainableCards().isEmpty())
                continue; // promo/token-only pseudo-editions would triple the table for no gain
            String code = edition.getCode();
            String include = existing.containsKey(code.toLowerCase()) ? existing.get(code.toLowerCase())[5] : "Y";
            rows.put(code.toLowerCase(), new String[]{
                    code, edition.getName(), String.valueOf(edition.getType()),
                    String.valueOf(edition.getObtainableCards().size()),
                    edition.getDate() == null ? "" : String.format("%tF", edition.getDate()),
                    normalizeYN(include)});
        }
        writeCsv(EXPANSIONS_CSV, new String[]{"Code", "Name", "Type", "CardCount", "ReleaseDate", "Include"}, rows);
        Set<String> excluded = new HashSet<>();
        for (String[] row : rows.values())
            if ("N".equals(row[5]))
                excluded.add(row[0]);
        return excluded;
    }

    // ------------------------------------------------------------------ items

    /**
     * Generates/merges the items table from the freshly-loaded catalog, then REMOVES excluded
     * items from it in place. Called by ItemListData's static loader with its own list, so this
     * class never reaches back into ItemListData (no init cycle). Quest items are protected:
     * Include=N on a questItem row is ignored (logged), per the user's confirmed choice.
     */
    // Quest items with NO discoverable grant path anywhere in this mod's data, stock Shandalar's
    // own (unmodified) quests.json/enemies.json, any other bundled plane, or the Forge Java
    // source tree - confirmed 2026-08-12 by a full cross-reference audit (every itemName/
    // itemNames/addItem/removeItem field across shops.json, enemies.json, quests.json,
    // points_of_interest.json, config.json, and all 37 plane .tmx files, plus a source grep).
    // 22 of these are STOCK Forge items (present in common/world/items.json pre-mod) that are
    // equally dead in vanilla, unmodded Shandalar - not something this project lost; "Ghost
    // rune" is this project's own addition and has no such excuse. Not auto-excluded (still
    // Include=Y, still spawnable if some future path is found) - purely informational so this
    // doesn't need re-auditing by hand later.
    private static final Set<String> KNOWN_UNUSED_ITEMS = new HashSet<>(Arrays.asList(
            "Basement Key", "Black rune", "Blue rune", "Cultist's Key", "Fifth Shard", "First Shard",
            "Fourth Shard", "Ghost rune", "Green rune", "Grolnok's Key", "Illusionist's Key",
            "Landscape Sketchbook - Coldsnap", "Landscape Sketchbook - Mirage",
            "Landscape Sketchbook - Urza's Saga", "Outer Gate Key", "Red rune", "Rusty Old Key",
            "Second Shard", "Sorin's Key", "Third Shard", "Tibalt's Key", "Torturer's Key", "White rune"));

    public static void filterItems(Array<ItemData> itemList) {
        if (!isEnabled() || itemList == null)
            return;
        try {
            Map<String, String[]> existing = readCsv(ITEMS_CSV);
            // Columns: Name,Rarity,Cost,Slot,Quest,Effect,Include,Notes
            Map<String, String[]> rows = new LinkedHashMap<>();
            for (ItemData item : new Array.ArrayIterator<>(itemList)) {
                if (item == null || item.name == null)
                    continue;
                String key = item.name.toLowerCase();
                String include = existing.containsKey(key) ? existing.get(key)[6] : "Y";
                rows.put(key, new String[]{
                        item.name,
                        item.rarity == null ? "" : String.valueOf(item.rarity),
                        String.valueOf(item.cost),
                        item.equipmentSlot == null ? "" : item.equipmentSlot,
                        item.questItem ? "Y" : "N",
                        oneLine(item.getDescription()),
                        normalizeYN(include),
                        KNOWN_UNUSED_ITEMS.contains(item.name) ? "Currently Unused" : ""});
            }
            writeCsv(ITEMS_CSV, new String[]{"Name", "Rarity", "Cost", "Slot", "Quest", "Effect", "Include", "Notes"}, rows);
            excludedItemNames = new HashSet<>();
            for (String[] row : rows.values()) {
                if (!"N".equals(row[6]))
                    continue;
                if ("Y".equals(row[4])) {
                    System.out.println("[ContentFilter] item \"" + row[0]
                            + "\" is a quest item - Include=N ignored (quest content is protected)");
                    continue;
                }
                excludedItemNames.add(row[0].toLowerCase());
            }
            if (excludedItemNames.isEmpty())
                return;
            for (int i = itemList.size - 1; i >= 0; i--) {
                ItemData item = itemList.get(i);
                if (item != null && item.name != null && excludedItemNames.contains(item.name.toLowerCase()))
                    itemList.removeIndex(i);
            }
            System.out.println("[ContentFilter] " + excludedItemNames.size()
                    + " item(s) excluded via config table: " + excludedItemNames);
        } catch (Exception e) {
            System.err.println("[ContentFilter] items table failed, no exclusions applied: " + e);
            excludedItemNames = new HashSet<>();
        }
    }

    // ------------------------------------------------------------------ enemies

    /**
     * Generates/merges the enemies table from the freshly-loaded catalog. Unlike items, the
     * catalog itself is NOT filtered - EnemyData must stay resolvable for quest-scripted spawns
     * (protected per the user's choice) and for enemies already alive in a save. Random-spawn
     * and arena call sites consult {@link #isEnemyIncluded} instead; an arena pool that filters
     * to empty falls back to the unfiltered pool at the call site.
     */
    public static void registerEnemies(EnemyData[] enemies) {
        if (!isEnabled() || enemies == null)
            return;
        try {
            Map<String, String[]> existing = readCsv(ENEMIES_CSV);
            // Columns: Name,Colors,Deck,Life,Tier,Boss,Difficulty,Include
            Map<String, String[]> rows = new LinkedHashMap<>();
            for (EnemyData enemy : enemies) {
                if (enemy == null || enemy.name == null)
                    continue;
                String key = enemy.name.toLowerCase();
                String include = existing.containsKey(key) ? existing.get(key)[7] : "Y";
                String deck = enemy.deck != null && enemy.deck.length > 0 && enemy.deck[0] != null
                        ? enemy.deck[0].replaceAll(".*/", "").replace(".dck", "") : "";
                rows.put(key, new String[]{
                        enemy.name,
                        enemy.colors == null ? "" : enemy.colors,
                        deck,
                        String.valueOf(enemy.life),
                        enemy.tier == null ? "" : String.valueOf(enemy.tier),
                        enemy.boss ? "Y" : "N",
                        String.valueOf(enemy.difficulty),
                        normalizeYN(include)});
            }
            writeCsv(ENEMIES_CSV, new String[]{"Name", "Colors", "Deck", "Life", "Tier", "Boss", "Difficulty", "Include"}, rows);
            excludedEnemyNames = new HashSet<>();
            for (String[] row : rows.values())
                if ("N".equals(row[7]))
                    excludedEnemyNames.add(row[0].toLowerCase());
            if (!excludedEnemyNames.isEmpty())
                System.out.println("[ContentFilter] " + excludedEnemyNames.size()
                        + " enemy/enemies excluded via config table: " + excludedEnemyNames);
        } catch (Exception e) {
            System.err.println("[ContentFilter] enemies table failed, no exclusions applied: " + e);
            excludedEnemyNames = new HashSet<>();
        }
    }

    /** False only when the feature is on AND this enemy's row says Include=N. Callers decide
     *  what "excluded" means for their context (random spawns skip; quest spawns don't ask). */
    public static boolean isEnemyIncluded(String enemyName) {
        if (enemyName == null)
            return true;
        if (matchesPlayerHeroArt(enemyName))
            return false;
        if (excludedEnemyNames == null)
            return true;
        return !excludedEnemyNames.contains(enemyName.toLowerCase());
    }

    /**
     * Round 156 (user request): "We used all the Hero artwork as Enemy artwork also. But I want to
     * remove the one that matches the current Active hero... This way the Player (and his guards)
     * will always be unique, and we should also only lose 1 enemy per game, so not noticeable."
     * <p>
     * Exactly one enemy is hidden - the one drawn with the sprite sheet the player themself is
     * walking around in, matched on the atlas FILE rather than on any name, so it keeps working if
     * either list is renamed. The hero sheets live in {@code sprites/heroes/} and the enemy copies
     * in {@code sprites/enemy/heroes/} under the same file name, except the five dragons: a hero
     * is {@code dragonplayer_b} where the enemy is {@code dragonkin_b}, so that one pair is
     * rewritten before comparing.
     * <p>
     * Deliberately here rather than in WorldData.getAllEnemies(): the catalog itself must stay
     * resolvable for quest-scripted spawns, enemies already alive in a save, territory mages and
     * the statistics screen - only the random-spawn, arena and map sites consult this.
     */
    private static boolean matchesPlayerHeroArt(String enemyName) {
        String heroAtlas = playerHeroAtlas();
        if (heroAtlas == null)
            return false;
        forge.adventure.data.EnemyData enemy = forge.adventure.data.WorldData.getEnemy(enemyName);
        if (enemy == null || enemy.sprite == null)
            return false;
        return heroAtlas.equalsIgnoreCase(baseName(enemy.sprite));
    }

    /** The player's own sprite sheet, normalised to a bare file name, or null before a game exists. */
    private static String playerHeroAtlas() {
        try {
            forge.adventure.player.AdventurePlayer player = forge.adventure.player.AdventurePlayer.current();
            if (player == null)
                return null;
            String sprite = player.spriteName();
            if (sprite == null || sprite.isEmpty())
                return null;
            // dragonplayer_b -> dragonkin_b: the only pair whose hero and enemy sheets differ in
            // name rather than just in folder.
            return baseName(sprite).replace("dragonplayer_", "dragonkin_");
        } catch (RuntimeException e) {
            return null; // no world yet
        }
    }

    private static String baseName(String path) {
        String s = path.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0)
            s = s.substring(slash + 1);
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    // ------------------------------------------------------------------ CSV plumbing

    private static String normalizeYN(String value) {
        return value != null && value.trim().equalsIgnoreCase("N") ? "N" : "Y";
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace("\r", " ").replace("\n", " ").trim();
    }

    /** Reads an existing table into rowKey(lower-cased first column) -> full row. Missing file
     *  or malformed rows -> empty/skipped (regeneration recreates everything). */
    private static Map<String, String[]> readCsv(String planePath) {
        Map<String, String[]> rows = new HashMap<>();
        FileHandle file = new FileHandle(Config.instance().getFilePath(planePath));
        if (!file.exists())
            return rows;
        String[] lines = file.readString("UTF-8").split("\r?\n");
        for (int i = 1; i < lines.length; i++) { // skip header
            if (lines[i].isEmpty())
                continue;
            String[] fields = parseCsvLine(lines[i]);
            if (fields.length >= 2 && !fields[0].isEmpty())
                rows.put(fields[0].toLowerCase(), fields);
        }
        return rows;
    }

    private static void writeCsv(String planePath, String[] header, Map<String, String[]> rows) {
        StringBuilder sb = new StringBuilder();
        appendCsvLine(sb, header);
        for (String[] row : rows.values())
            appendCsvLine(sb, row);
        FileHandle file = new FileHandle(Config.instance().getFilePath(planePath));
        file.parent().mkdirs();
        // Only rewrite when content actually changed - keeps the file's timestamp meaningful
        // and avoids churning a file the user may have open in Excel.
        if (file.exists() && sb.toString().equals(file.readString("UTF-8")))
            return;
        file.writeString(sb.toString(), false, "UTF-8");
        System.out.println("[ContentFilter] wrote " + planePath + " (" + rows.size() + " rows)");
    }

    private static void appendCsvLine(StringBuilder sb, String[] fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0)
                sb.append(',');
            String field = fields[i] == null ? "" : fields[i];
            if (field.contains(",") || field.contains("\"") || field.contains("\n"))
                field = "\"" + field.replace("\"", "\"\"") + "\"";
            sb.append(field);
        }
        sb.append('\n');
    }

    /** Minimal RFC-4180 field splitter (quotes, escaped quotes, commas inside quotes). */
    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else
                        inQuotes = false;
                } else
                    current.append(c);
            } else if (c == '"')
                inQuotes = true;
            else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else
                current.append(c);
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}
