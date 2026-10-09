package forge.adventure.data;

import com.badlogic.gdx.graphics.g2d.Sprite;
import forge.adventure.util.Config;

import java.io.Serializable;
import java.util.UUID;

/**
 * Data class that will be used to read Json configuration files
 * ItemData
 * contains the information for equipment and items.
 */
public class ItemData implements Serializable, Cloneable {
    private static final long serialVersionUID = 1L;
    public String name;
    public String equipmentSlot;
    /** Round 137 (user spec 2026-09-07): the name of a SECOND equipment slot this item unlocks
     *  while it is worn - "Right2" or "Left2". The paperdoll shows that slot only while something
     *  grants it, and AdventurePlayer drops whatever is in it the moment the granting item comes
     *  off. Null for every ordinary item. Safe to add: this class carries an explicit
     *  serialVersionUID, so the save format cannot move. */
    public String grantsEquipmentSlot;
    public EffectData effect;
    public String description; //Manual description of the item.
    public String iconName;
    public boolean questItem=false;
    // Trophy/signature-drop items (2026-08-13, user report - "Chandra's Stone"/"Medal of Ultimate
    // Victory" showing up in the Armory for sale): a boss-fight memento with a real, working grant
    // path (so NOT questItem - that flag also wipes on New Game+ and disables inventory delete,
    // neither of which applies here) that still shouldn't be pulled from the general weighted
    // sell pool. See ItemListData.getItemNamesByRarity().
    public boolean excludeFromGeneralSale=false;
    public int cost=1000;
    // Item economy (2026-08-10): Common/Uncommon/Rare/Mythic, matching MTG's own rarity naming.
    // For items with effect.startBattleWithCard(InCommandZone), set from that card's real printed
    // rarity (Forge's own card database, not guessed). For non-card items, set by cost/judgment.
    // Not runtime-derived - an explicit, one-time editorial tag shops can gate/weight on.
    public String rarity="Common";

    public boolean usableOnWorldMap;
    public boolean usableInPoi;
    public boolean isCracked;
    public boolean isEquipped;
    public Long longID;
    public String commandOnUse;
    public int shardsNeeded;
    public DialogData dialogOnUse;
    // Round 336: an item with a limited number of uses (the Bonfire kit): `uses` to a kit, `repairShards` to rebuild a
    // spent one. The count left lives on the player (AdventurePlayer.usesLeft), not on this shared catalog entry.
    public int uses;
    public int repairShards;
    /** Round 512: the chance this item escapes the crack a lost duel gives it on Easy and Normal (0 = none). The user,
     *  on the eleven Left/Right mana items that hang at the neck since round 506 (Easy and Normal crack only Boots, Body
     *  and Neck): "They should be able to crack, but let's say 50% less chance". 0 in an older save's copy until the
     *  catalog refresh. */
    public float easyCrackSave;


    public ItemData()
    {

    }
    public ItemData(ItemData cpy)
    {
        name              = cpy.name;
        equipmentSlot     = cpy.equipmentSlot;
        effect            = new EffectData(cpy.effect);
        description       = cpy.description;
        iconName          = cpy.iconName;
        questItem         = cpy.questItem;
        excludeFromGeneralSale = cpy.excludeFromGeneralSale;
        cost              = cpy.cost;
        rarity            = cpy.rarity;
        usableInPoi       = cpy.usableInPoi;
        usableOnWorldMap  = cpy.usableOnWorldMap;
        commandOnUse      = cpy.commandOnUse;
        shardsNeeded      = cpy.shardsNeeded;
        dialogOnUse       = cpy.dialogOnUse;
        uses              = cpy.uses; // round 336
        repairShards      = cpy.repairShards;
        easyCrackSave     = cpy.easyCrackSave; // round 512
    }

    public Sprite sprite() {
        return Config.instance().getItemSprite(displayIconName());
    }

    /** Round 349 (the user: "When in half ... update the icon in your inv to just look like the black half"): the icon
     *  this item shows right now. The player's own Yin-Yang rune shows its dark half (items.atlas YinYangRuneDark) while
     *  the light half lies on the world map (World.getYinYangAnchor); a rune in a shop or on a reward keeps the stone. */
    public String displayIconName() {
        if (!"YinYangRune".equals(iconName))
            return iconName;
        try {
            forge.adventure.world.WorldSave save = forge.adventure.world.WorldSave.getCurrentSave();
            if (save == null || save.getWorld() == null || save.getWorld().getYinYangAnchor() == null || save.getPlayer() == null)
                return iconName;
            for (ItemData owned : save.getPlayer().getItems())
                if (owned == this)
                    return "YinYangRuneDark";
            for (ItemData stored : save.getPlayer().getArmoryStorage())
                if (stored == this)
                    return "YinYangRuneDark";
        } catch (RuntimeException ignored) {
            // no game loaded yet - the whole stone
        }
        return iconName;
    }

    public String getDescription() {
        String result = "";
        String translatedDescription = forge.Forge.getLocalizer().getMessageorUseDefault(
            "adv.item." + makeKey(name) + ".description", "");
        String baseDescription = !translatedDescription.isEmpty() ? translatedDescription : this.description;
        if(baseDescription != null && !baseDescription.isEmpty())
            result += baseDescription + "\n";
        if(this.equipmentSlot != null && !this.equipmentSlot.isEmpty()) {
            // Round 502: a companion (an item that starts a creature in play - Ascendance's companion limit) says so
            // wherever it is shown, shops and rewards too.
            int creatures = forge.adventure.util.Ascendance.startingCreatures(this);
            result += "Slot: " + this.equipmentSlot + (creatures == 0 ? "" : " - Companion"
                    + (creatures > 1 ? " (" + creatures + " creatures)" : "")) + "\n";
        }
        if(effect != null)
            result += effect.getDescription();
        if(shardsNeeded != 0)
            result +=  shardsNeeded+" [+Shards]";
        if (uses > 0) { // round 336: the kit's fires left, from the player
            forge.adventure.player.AdventurePlayer player = forge.adventure.util.Current.player();
            int left = player == null ? uses : player.usesLeft(this);
            boolean lineOpen = !result.isEmpty() && result.charAt(result.length() - 1) != (char) 10;
            result += (lineOpen ? " - " : "") + "Uses left: " + left + "/" + uses
                    + (left <= 0 ? " (rebuild for " + repairShards + " [+Shards])" : "");
        }
        return result;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return forge.Forge.getLocalizer().getMessageorUseDefault(
            "adv.item." + makeKey(name) + ".displayName", name);
    }

    //Builds a .properties key from this item's English name by dropping the
    //characters that would otherwise break a properties key (space, ':'),
    //keeping everything else (letters, digits, apostrophes, hyphens...) as-is.
    //e.g. "Silver Challenge Coin" -> "SilverChallengeCoin"
    private static String makeKey(String text) {
        if (text == null) return "";
        return text.replace(":", "").replace(" ", "");
    }

    @Override
    public ItemData clone() {
        try {
            ItemData clone = (ItemData) super.clone();
            clone.longID = UUID.randomUUID().getMostSignificantBits();
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}
