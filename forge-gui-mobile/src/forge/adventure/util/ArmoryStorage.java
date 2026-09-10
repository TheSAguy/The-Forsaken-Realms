package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.adventure.data.EffectData;
import forge.adventure.data.ItemData;
import forge.adventure.data.RoamingGuardData;
import forge.adventure.player.AdventurePlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Armory storage and a roaming guard's equipment (MOD_SCOPE #118, round 163; user ask 2026-09-10:
 * "add a storage to the armory. The player can add items from his inventory there. Then on the Guard
 * management screen a way to access the inventory and add equipment to a guard").
 * <p>
 * <b>One owner per item.</b> An ItemData object is in exactly one of {the inventory, the storage, a
 * guard's equipment}, and every move is one of the five verbs below, each of which removes from the
 * source before adding to the destination and prints one [TFR-Armory] line. The round-141 sell exploit
 * and the round-146 deck desync were both "two containers disagreed about who owns a thing"; the guard
 * deck fixed that with an exact list, and equipment gets the same discipline.
 * <p>
 * The storage lives on the player (one per character, reachable from every player-owned Armory) -
 * see docs/design/2026-09-10-armory-storage.md for the decisions and what reversing each would cost.
 */
public final class ArmoryStorage {
    private ArmoryStorage() {}

    public static List<ItemData> items() {
        return AdventurePlayer.current().getArmoryStorage();
    }

    /** A slot a guard can wear: every doll slot but the two ability slots - an ability needs a player
     *  to trigger it. Gauntlet twin slots (Left2/Right2) are not granted to a guard. */
    public static boolean isGuardSlot(String slot) {
        return slot != null && !slot.isEmpty() && !slot.startsWith("Ability");
    }

    /** Wearable things only - and never something the player is wearing right now: depositing must
     *  not strip the doll behind the player's back. Take it off on the inventory screen first. */
    public static boolean canDeposit(ItemData item) {
        if (item == null || item.questItem || item.equipmentSlot == null || item.equipmentSlot.isEmpty())
            return false;
        return !item.isEquipped && !AdventurePlayer.current().getEquippedItems().contains(item.longID);
    }

    /** Same rule as the doll: a cracked item is unusable until repaired. */
    public static boolean guardCanWear(ItemData item) {
        return item != null && !item.questItem && !item.isCracked && isGuardSlot(item.equipmentSlot);
    }

    /** Inventory items the player may put into the storage, by slot then name. */
    public static List<ItemData> depositable() {
        List<ItemData> out = new ArrayList<>();
        for (ItemData item : AdventurePlayer.current().getItems())
            if (canDeposit(item))
                out.add(item);
        return sorted(out);
    }

    /** Everything stored, by slot then name. */
    public static List<ItemData> stored() {
        return sorted(new ArrayList<>(items()));
    }

    /** Stored items a guard could wear, by slot then name. */
    public static List<ItemData> wearable() {
        List<ItemData> out = new ArrayList<>();
        for (ItemData item : items())
            if (guardCanWear(item))
                out.add(item);
        return sorted(out);
    }

    public static List<ItemData> sorted(List<ItemData> list) {
        list.sort(Comparator.comparing((ItemData i) -> i.equipmentSlot == null ? "" : i.equipmentSlot)
                .thenComparing(i -> i.name == null ? "" : i.name));
        return list;
    }

    // ------------------------------------------------------------------ the five moves

    public static boolean deposit(ItemData item) {
        AdventurePlayer player = AdventurePlayer.current();
        if (!canDeposit(item) || !player.getItems().contains(item))
            return false;
        player.removeItem(item); // also unequips and drops granted slots, should it somehow be worn
        items().add(item);
        log(item, "inventory -> storage");
        return true;
    }

    public static boolean withdraw(ItemData item) {
        if (!items().remove(item))
            return false;
        AdventurePlayer.current().getItems().add(item);
        log(item, "storage -> inventory");
        return true;
    }

    public static ItemData worn(RoamingGuardData guard, String slot) {
        for (ItemData item : guard.equipment)
            if (item != null && slot != null && slot.equals(item.equipmentSlot))
                return item;
        return null;
    }

    /** Storage -> guard. One item per slot: whatever was in that slot goes back to the storage. */
    public static boolean giveToGuard(RoamingGuardData guard, ItemData item) {
        if (!guardCanWear(item) || !items().remove(item))
            return false;
        ItemData displaced = worn(guard, item.equipmentSlot);
        if (displaced != null) {
            guard.equipment.remove(displaced);
            items().add(displaced);
            log(displaced, RoamingGuards.displayName(guard.tier) + " guard -> storage (displaced)");
        }
        guard.equipment.add(item);
        log(item, "storage -> " + RoamingGuards.displayName(guard.tier) + " guard");
        return true;
    }

    public static boolean takeFromGuard(RoamingGuardData guard, ItemData item) {
        if (!guard.equipment.remove(item))
            return false;
        items().add(item);
        log(item, RoamingGuards.displayName(guard.tier) + " guard -> storage");
        return true;
    }

    /** Everything the guard wears goes back to the storage: on dismissal (every case - the user's
     *  forfeit rule is about the DECK; the steel is the player's), and on an unpaid disband. */
    public static int returnGear(RoamingGuardData guard) {
        int count = guard.equipment.size();
        for (ItemData item : new ArrayList<>(guard.equipment))
            takeFromGuard(guard, item);
        return count;
    }

    // ------------------------------------------------------------------ what the gear does

    /** The guard's own duel effects, in the shape DuelScene.applyEffects() takes. */
    public static Array<EffectData> effectsOf(RoamingGuardData guard) {
        Array<EffectData> out = new Array<>();
        for (ItemData item : guard.equipment)
            if (item != null && item.effect != null)
                out.add(item.effect);
        return out;
    }

    /** What the guard's gear does to its OPPONENT (a Medal's land for the mage, and so on). */
    public static Array<EffectData> opponentEffectsOf(RoamingGuardData guard) {
        Array<EffectData> out = new Array<>();
        for (ItemData item : guard.equipment)
            if (item != null && item.effect != null && item.effect.opponent != null)
                out.add(item.effect.opponent);
        return out;
    }

    /** Boots and blessings: the same product AdventurePlayer.equipmentSpeed() takes for the player. */
    public static float speedOf(RoamingGuardData guard) {
        float factor = 1f;
        for (ItemData item : guard.equipment)
            if (item != null && item.effect != null && item.effect.moveSpeed > 0f)
                factor *= item.effect.moveSpeed;
        return factor;
    }

    public static String gearNames(RoamingGuardData guard) {
        StringBuilder sb = new StringBuilder("[");
        for (ItemData item : guard.equipment)
            sb.append(sb.length() == 1 ? "" : ", ").append(item == null ? "?" : item.name);
        return sb.append("]").toString();
    }

    /** "Steel Sword (Right): [+Life] +2, Battlefield: 1x Rock" - one line, for dialog rows. */
    public static String describe(ItemData item) {
        String slot = item.equipmentSlot == null || item.equipmentSlot.isEmpty() ? "" : " (" + item.equipmentSlot + ")";
        String effect = item.effect == null ? "" : item.effect.getDescription().trim().replace(":\n", ": ").replace("\n", ", ");
        return item.name + slot + (effect.isEmpty() ? "" : ": " + effect) + (item.isCracked ? " [RED](cracked)[]" : "");
    }

    private static void log(ItemData item, String move) {
        System.out.println("[TFR-Armory] " + item.name + " (" + item.equipmentSlot + ") " + move
                + " - storage now " + items().size() + ", inventory " + AdventurePlayer.current().getItems().size());
    }
}
