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

        final ItemData itemData = player().getRandomEquippedItem();
        if (itemData != null && !(Config.instance().getSettingData().disableCrackedItems)
                && !Ascendance.itemEscapesCracking(itemData) // round 494: Mender
                && !escapesEasyCrack(itemData)) { // round 512
            itemData.isCracked = true;
            player().equip(itemData); // un-equip
            InventoryScene.instance().clearItemDescription();

            stringBuilder.setLength(0);
            return stringBuilder.append(baseMessage)
                    .append("\n{GRADIENT=RED;GRAY;1;1}").append(itemData.getDisplayName()).append(" {ENDGRADIENT}")
                    .append(Forge.getLocalizer().getMessage("lblCracked")).toString();
        }

        return baseMessage;
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
