package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.adventure.util.Config;
import forge.adventure.util.ContentFilterTables;
import forge.adventure.util.Paths;

public class ItemListData {
    private static Array<ItemData> itemList;
    static {
        Json json = new Json();
        FileHandle handle = Config.instance().getFile(Paths.ITEMS);
        if (handle.exists()) {
            itemList = json.fromJson(Array.class, ItemData.class, handle);
            // Content filter tables (user spec 2026-08-12): generates/merges the items CSV from
            // this freshly-loaded catalog, then removes Include=N items in place (quest items
            // protected). This class is the single choke point every item lookup goes through
            // (getItem/getSketchBooks), so filtering here covers shops, drops, rewards, and
            // starter items alike. No-op unless contentFilterTablesEnabled.
            ContentFilterTables.filterItems(itemList);
        }
    }
    /** Round 512: items renamed since a save, a map or a reward list was written - the old name (lower case) resolves to
     *  the item under its new one. The user, on the four mana creatures that hang at the neck since round 506: "Let's
     *  rename also please". AdventurePlayer's catalog refresh renames a saved copy on load. Round 605 (the user:
     *  "Let's make sure all Boot items have boot icons. (Possibly re-name)"): the Boots-slot rings, anklets, pants
     *  and skirts. Round 630: the misspelled "Thrones of Eldraine" sketchbook (no such edition - it unlocked nothing)
     *  becomes the working Throne of Eldraine one. */
    private static final java.util.Map<String, String> RENAMED = java.util.Map.ofEntries(
            java.util.Map.entry("landscape sketchbook - thrones of eldraine", "Landscape Sketchbook - Throne of Eldraine"),
            java.util.Map.entry("joraga boots", "Joraga Leaf Pendant"),
            java.util.Map.entry("utopia anklet", "Utopia Necklace"),
            java.util.Map.entry("petalmane pants", "Petalmane Charm"),
            java.util.Map.entry("scarecrow socks", "Scarecrow's Feather"),
            java.util.Map.entry("cutthroat boots", "Cutthroat Skirt"),
            java.util.Map.entry("symbiote bondband", "Symbiote Striders"),
            java.util.Map.entry("imposter's sliverband", "Imposter's Slivertreads"),
            java.util.Map.entry("sky skiff turnkey", "Sky Skiff Boots"),
            java.util.Map.entry("enduring sliverband", "Enduring Slivertreads"),
            java.util.Map.entry("jade anklet", "Jade Slippers"),
            java.util.Map.entry("coiled anklet", "Coiled Sandals"),
            java.util.Map.entry("witch-maw anklet", "Witch-Maw Slippers"),
            java.util.Map.entry("mamba bondband", "Mamba Striders"),
            java.util.Map.entry("gixian graft", "Gixian Graftboots"),
            java.util.Map.entry("artificial sliverband", "Artificial Slivertreads"),
            java.util.Map.entry("yore-tiller anklet", "Yore-Tiller Clogs"),
            java.util.Map.entry("silvergill tailband", "Silvergill Flippers"),
            java.util.Map.entry("deathspitter boots", "Deathspitter Skirt"),
            java.util.Map.entry("lookout's climbing boots", "Lookout's Leggings"),
            java.util.Map.entry("bearhide boots", "Bearhide Breeches"),
            java.util.Map.entry("glimmerbell bondband", "Glimmerbell Striders"),
            java.util.Map.entry("bloodsworn bite", "Bloodsworn Boots"),
            java.util.Map.entry("victual sliverband", "Victual Slivertreads"),
            java.util.Map.entry("dune-brood anklet", "Dune-Brood Sandals"),
            java.util.Map.entry("packsong boots", "Packsong Pants"),
            java.util.Map.entry("acidic sliverband", "Acidic Slivertreads"),
            java.util.Map.entry("anklet of the end", "Boots of the End"),
            java.util.Map.entry("spectral sliverband", "Spectral Slivertreads"),
            java.util.Map.entry("sabertooth bondband", "Sabertooth Striders"),
            java.util.Map.entry("dragonhide boots", "Draped Dragonhide"),
            java.util.Map.entry("cryptologist's flippers", "Cryptologist's Breeches"),
            java.util.Map.entry("blistering boots", "Blistering Breeches"),
            java.util.Map.entry("sporecap boots", "Spore Skirt"),
            java.util.Map.entry("barrier boots", "Barrier Breeches"),
            java.util.Map.entry("glint-eye anklet", "Glint-Eye Slippers"),
            java.util.Map.entry("ink-treader anklet", "Ink-Treaders"),
            java.util.Map.entry("raptor bondband", "Raptor Striders"),
            java.util.Map.entry("plated sliverband", "Plated Slivertreads"),
            java.util.Map.entry("pack leader boots", "Pack Leader Pants"),
            java.util.Map.entry("girlfriend's heels", "Girlfriend's Skirt"),
            java.util.Map.entry("leech waders", "Leech Breeches"),
            java.util.Map.entry("alesha's war boots", "Alesha's War Skirt"),
            java.util.Map.entry("acolyte's anklet", "Acolyte's Sandals"),
            java.util.Map.entry("generous boots", "Generous Pants"),
            java.util.Map.entry("faerie anklet", "Faerie Slippers"),
            java.util.Map.entry("fleshwright boots", "Fleshwright Chaps"),
            java.util.Map.entry("immortal anklet", "Immortal Treads"),
            java.util.Map.entry("evershrike anklet", "Evershrike Slippers"),
            java.util.Map.entry("flamecaster boots", "Flamecaster Pants"),
            java.util.Map.entry("volatile prayerbook", "Volatile Pendant"),
            java.util.Map.entry("lookout's harness", "Lookout's Leggings"),
            java.util.Map.entry("cryptologist's fins", "Cryptologist's Breeches"));

    /** Round 691: items taken out of the game - AdventurePlayer removes a saved copy on load. "Cheat" is stock Forge's
     *  test pendant (Blightsteel Colossus, Urabrask and Avatar of Slaughter on the battlefield); our copy carried a
     *  Mythic rarity, so it dropped from every random Mythic pool (Nova on Discord, v1.22: "Seems like a dev test
     *  item"). The user: remove owned copies with a notice and no refund. Its entry stays in items.json,
     *  excludeFromGeneralSale, so nothing draws it. Lower case. */
    private static final java.util.Set<String> WITHDRAWN = java.util.Set.of("cheat");

    public static boolean isWithdrawn(String name) {
        return name != null && WITHDRAWN.contains(name.toLowerCase(java.util.Locale.ROOT));
    }

    public static ItemData getItem(String name) {
        if (itemList == null || name == null)
            return null;
        for (ItemData orig : new Array.ArrayIterator<>(itemList)) {
            if (orig.name.equalsIgnoreCase(name))
                return orig.clone();
        }
        String renamed = RENAMED.get(name.toLowerCase(java.util.Locale.ROOT));
        return renamed == null ? null : getItem(renamed);
    }
    /** All shop-worthy item names of one rarity (Common/Uncommon/Rare/Mythic) - quest items,
     *  Landscape Sketchbooks, and excludeFromGeneralSale trophy items excluded, same rule
     *  getSketchBooks() applies in reverse. Backs the "itemRarity" dynamic reward pools
     *  (2026-08-12, user request: armories drew from hand lists of ~22 while the catalog holds
     *  500+ eligible items). Reads the live (already content-filter-table-filtered) list, so
     *  excluded items never appear and new items join automatically. */
    public static java.util.List<String> getItemNamesByRarity(String rarity) {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (itemList == null || rarity == null)
            return names;
        for (ItemData item : new Array.ArrayIterator<>(itemList)) {
            if (item == null || item.name == null || item.questItem || item.excludeFromGeneralSale)
                continue;
            if (item.name.contains("Landscape Sketchbook"))
                continue;
            if (rarity.equalsIgnoreCase(item.rarity))
                names.add(item.name);
        }
        return names;
    }

    public static Array<ItemData> getSketchBooks() {
        Array<ItemData> sketchbooks = new Array<>();
        if (itemList == null)
            return sketchbooks;
        for (ItemData orig : new Array.ArrayIterator<>(itemList)) {
            if (orig.questItem || !orig.getName().contains("Landscape Sketchbook"))
                continue;
            sketchbooks.add(orig.clone());
        }
        return sketchbooks;
    }
}
