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
    }

    public Sprite sprite() {
        return Config.instance().getItemSprite(iconName);
    }

    public String getDescription() {
        String result = "";
        if(this.description != null && !this.description.isEmpty())
            result += description + "\n";
        if(this.equipmentSlot != null && !this.equipmentSlot.isEmpty())
            result += "Slot: " + this.equipmentSlot + "\n";
        if(effect != null)
            result += effect.getDescription();
        if(shardsNeeded != 0)
            result +=  shardsNeeded+" [+Shards]";
        return result;
    }

    public String getName() {
        return name;
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
