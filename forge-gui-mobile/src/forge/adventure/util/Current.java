package forge.adventure.util;

import forge.Forge;
import forge.adventure.data.ItemData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.scene.InventoryScene;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;

/**
 * Shortcut class to handle global access, may need some redesign
 */
public class Current {

    private static final StringBuilder stringBuilder = new StringBuilder(512);

    public static AdventurePlayer player()
    {
        return WorldSave.getCurrentSave().getPlayer();
    }
    public static World world()
    {
        return WorldSave.getCurrentSave().getWorld();
    }

    static Deck deck;
    public static Deck latestDeck() {
        return deck;
    }
    public static void setLatestDeck(Deck generateDeck) {
        deck = generateDeck;
    }

    public static String generateDefeatMessage(boolean hasDied) {
        final String key = hasDied ? "lblYouDied" : "lblYouLostTheLastGame";
        final String baseMessage = Forge.getLocalizer().getMessage(key, player().getName());

        final ItemData itemData = crackRandomEquippedItem(hasDied ? "died" : "boss");
        if (itemData != null) {
            stringBuilder.setLength(0);
            return stringBuilder.append(baseMessage)
                    .append("\n{GRADIENT=RED;GRAY;1;1}").append(itemData.getDisplayName()).append(" {ENDGRADIENT}")
                    .append(Forge.getLocalizer().getMessage("lblCracked")).toString();
        }

        return baseMessage;
    }

    /** Round 758 (the user: the death dialog "looks like the duel loss screen when an equipment breaks"): the death
     *  dialog says what dying did - where you wake, the gold the loss took - and sets the cracked item apart below it.
     *  The boss-defeat dialog keeps generateDefeatMessage(false). */
    public static String generateDeathMessage(String homeName, boolean healed, int goldLost) {
        stringBuilder.setLength(0);
        stringBuilder.append(Forge.getLocalizer().getMessage("lblYouDied", player().getName()));
        if (homeName != null && !homeName.isEmpty())
            stringBuilder.append("\nYou wake in ").append(homeName).append(healed ? ", healed." : ".");
        if (goldLost > 0)
            stringBuilder.append("\nLost [+Gold] ").append(goldLost).append(".");
        final ItemData itemData = crackRandomEquippedItem("died");
        if (itemData != null)
            stringBuilder.append("\n\n{GRADIENT=RED;GRAY;1;1}Your ").append(itemData.getDisplayName())
                    .append(" cracked.{ENDGRADIENT}");
        return stringBuilder.toString();
    }

    /** Cracks (and un-equips) one random equipped item, as every death and boss defeat does; null when none cracks.
     *  Round 758: [TFR-Defeat] says which item, or why none. */
    private static ItemData crackRandomEquippedItem(String screen) {
        final ItemData itemData = player().getRandomEquippedItem();
        String why = null;
        if (itemData == null)
            why = "nothing equipped";
        else if (Config.instance().getSettingData().disableCrackedItems)
            why = "cracking is off in Settings";
        else if (Ascendance.itemEscapesCracking(itemData)) // round 494: Mender
            why = itemData.name + " escapes (Mender)";
        else if (escapesEasyCrack(itemData)) // round 512
            why = itemData.name + " escapes (Easy/Normal save)";
        if (why != null) {
            System.out.println("[TFR-Defeat] " + screen + ": no item cracks - " + why);
            return null;
        }
        itemData.isCracked = true;
        player().equip(itemData); // un-equip
        InventoryScene.instance().clearItemDescription();
        System.out.println("[TFR-Defeat] " + screen + ": " + itemData.name + " cracks (" + itemData.equipmentSlot + ")");
        return itemData;
    }

    private static final java.util.Random CRACK_RAND = new java.util.Random();

    /** Round 512: on Easy and Normal an item with easyCrackSave (the eleven mana items that left the hands for the neck
     *  in round 506 - those difficulties crack only Boots, Body and Neck) escapes the crack that often. */
    private static boolean escapesEasyCrack(ItemData item) {
        if (item.easyCrackSave <= 0f || player().isHardorInsaneDifficulty() || CRACK_RAND.nextFloat() >= item.easyCrackSave)
            return false;
        System.out.println("[TFR-Crack] " + item.name + " escapes cracking (" + Math.round(item.easyCrackSave * 100)
                + "% on Easy/Normal)");
        return true;
    }
}
