package forge.adventure.util;

import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import forge.adventure.data.ItemData;
import forge.adventure.data.RoamingGuardData;
import forge.adventure.scene.UIScene;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The dialogs for the Armory storage and a guard's equipment (MOD_SCOPE #118, round 163). Same
 * close-then-reopen idiom and the same EconomyBuildings helpers as RoamingGuardUI, which is why this
 * lives in this package. Items are chosen from a PAGED picker (six to a page, two to a row) rather
 * than one button per item: an inventory can hold a hundred wearables, and a Dialog's button table
 * does not scroll.
 */
public final class ArmoryStorageUI {
    private ArmoryStorageUI() {}

    private static final int PAGE = 6;

    // ------------------------------------------------------------------ the storage (Armory page)

    /** The Armory's storage dialog. {@code onClose} runs when the player leaves it, so the Armory page
     *  can refresh the count on its button. */
    public static void open(UIScene scene, Runnable onClose) {
        List<ItemData> stored = ArmoryStorage.stored();
        List<ItemData> depositable = ArmoryStorage.depositable();
        Dialog dialog = new Dialog("Armory Storage", Controls.getSkin());
        EconomyBuildings.addContentRow(dialog, "[%90]Equipment kept here can be given to your roaming guards at the"
                + " Capitol. Stored: " + stored.size() + " item(s).");
        if (stored.isEmpty())
            EconomyBuildings.addContentRow(dialog, "[%85]Nothing stored yet.");
        for (ItemData item : stored)
            EconomyBuildings.addContentRow(dialog, "[%85]- " + ArmoryStorage.describe(item));
        if (depositable.isEmpty())
            EconomyBuildings.addContentRow(dialog, "[%85]Nothing in your inventory can be stored right now. Worn items"
                    + " are not listed - take them off on the inventory screen first.");
        int[] column = {0};
        EconomyBuildings.addHalfButton(dialog, column, "[%75]Deposit (" + depositable.size() + ")", !depositable.isEmpty(), () -> {
            scene.removeDialog();
            openPicker(scene, "Deposit", "Tap an item to move it into the storage.", ArmoryStorage::depositable, 0,
                    ArmoryStorage::deposit, () -> open(scene, onClose));
        });
        EconomyBuildings.addHalfButton(dialog, column, "[%75]Withdraw (" + stored.size() + ")", !stored.isEmpty(), () -> {
            scene.removeDialog();
            openPicker(scene, "Withdraw", "Tap an item to take it back into your inventory.", ArmoryStorage::stored, 0,
                    ArmoryStorage::withdraw, () -> open(scene, onClose));
        });
        EconomyBuildings.addHalfButton(dialog, column, "Close", true, () -> {
            scene.removeDialog();
            if (onClose != null)
                onClose.run();
        });
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        EconomyBuildings.makeContentScrollable(dialog);
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    // ------------------------------------------------------------------ a guard's equipment

    /** Reached from the roaming guard's manage screen. {@code back} reopens that screen. */
    public static void openGuardEquipment(UIScene scene, RoamingGuardData guard, Runnable back) {
        List<ItemData> worn = ArmoryStorage.sorted(new java.util.ArrayList<>(guard.equipment));
        List<ItemData> wearable = ArmoryStorage.wearable();
        Dialog dialog = new Dialog(RoamingGuards.displayName(guard.tier) + " - Equipment", Controls.getSkin());
        EconomyBuildings.addContentRow(dialog, "[%90]One item per slot. What it wears fights with it, in watched and"
                + " simulated duels alike; boots make it walk faster. Storage holds " + wearable.size()
                + " wearable item(s).");
        if (worn.isEmpty())
            EconomyBuildings.addContentRow(dialog, "[%85]Wearing nothing.");
        for (ItemData item : worn)
            EconomyBuildings.addContentRow(dialog, "[%85]- " + ArmoryStorage.describe(item));
        int[] column = {0};
        for (ItemData item : worn)
            EconomyBuildings.addHalfButton(dialog, column, fit("Remove " + item.name), true, () -> {
                ArmoryStorage.takeFromGuard(guard, item);
                scene.removeDialog();
                openGuardEquipment(scene, guard, back);
            });
        EconomyBuildings.addHalfButton(dialog, column, "[%75]Add from storage", !wearable.isEmpty(), () -> {
            scene.removeDialog();
            openPicker(scene, "Equip from Storage",
                    "Tap an item to put it on this guard. Anything already in that slot returns to the storage.",
                    ArmoryStorage::wearable, 0, item -> ArmoryStorage.giveToGuard(guard, item),
                    () -> openGuardEquipment(scene, guard, back));
        });
        EconomyBuildings.addHalfButton(dialog, column, "Back", true, () -> {
            scene.removeDialog();
            back.run();
        });
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        EconomyBuildings.makeContentScrollable(dialog);
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    // ------------------------------------------------------------------ the paged picker

    /**
     * A paged list of items, PAGE to a page and two to a row. The content rows carry the full
     * description of each numbered item; the buttons carry the number and the name. Picking one runs
     * {@code onPick} and reopens the picker on the same page (bounded to what is left), so several
     * moves in a row are quick. {@code pool} is re-read on every open because a pick changes it.
     */
    static void openPicker(UIScene scene, String title, String intro, Supplier<List<ItemData>> pool, int page,
                           Consumer<ItemData> onPick, Runnable back) {
        List<ItemData> items = pool.get();
        int pages = Math.max(1, (items.size() + PAGE - 1) / PAGE);
        int at = Math.max(0, Math.min(page, pages - 1));
        Dialog dialog = new Dialog(title, Controls.getSkin());
        EconomyBuildings.addContentRow(dialog, "[%90]" + intro + " Page " + (at + 1) + "/" + pages + ", "
                + items.size() + " item(s).");
        if (items.isEmpty())
            EconomyBuildings.addContentRow(dialog, "[%85]Nothing here.");
        int from = at * PAGE, to = Math.min(items.size(), from + PAGE);
        for (int i = from; i < to; i++)
            EconomyBuildings.addContentRow(dialog, "[%80]" + (i - from + 1) + ". " + ArmoryStorage.describe(items.get(i)));
        int[] column = {0};
        for (int i = from; i < to; i++) {
            ItemData item = items.get(i);
            EconomyBuildings.addHalfButton(dialog, column, fit((i - from + 1) + ". " + item.name), true, () -> {
                onPick.accept(item);
                scene.removeDialog();
                openPicker(scene, title, intro, pool, at, onPick, back);
            });
        }
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        EconomyBuildings.addHalfButton(dialog, column, "Prev", at > 0, () -> {
            scene.removeDialog();
            openPicker(scene, title, intro, pool, at - 1, onPick, back);
        });
        EconomyBuildings.addHalfButton(dialog, column, "Next", at < pages - 1, () -> {
            scene.removeDialog();
            openPicker(scene, title, intro, pool, at + 1, onPick, back);
        });
        EconomyBuildings.addHalfButton(dialog, column, "Back", true, () -> {
            scene.removeDialog();
            back.run();
        });
        EconomyBuildings.finishHalfButtonRow(dialog, column);
        EconomyBuildings.makeContentScrollable(dialog);
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    /** Scales a button label down with its length so a long item name stays inside the half button
     *  (140px landscape / 118px portrait); the full name is always in the content rows above. */
    static String fit(String text) {
        // Round 164 (Android pass): the half button is 118px in portrait, not 140 - cut sooner there.
        int cap = forge.Forge.isLandscapeMode() ? 30 : 25;
        String shown = text.length() > cap ? text.substring(0, cap - 1) + "." : text;
        int len = shown.length();
        return (len <= 14 ? "[%75]" : len <= 20 ? "[%65]" : "[%55]") + shown;
    }
}
