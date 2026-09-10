package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.utils.Array;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.adventure.data.ItemData;
import forge.adventure.data.RoamingGuardData;
import forge.adventure.stage.ConsoleCommandInterpreter;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.MapStage;
import forge.adventure.util.AdventureQuestController;
import forge.adventure.util.ArmoryStorage;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.util.MapDialog;
import forge.adventure.util.Paths;
import forge.adventure.util.RoamingGuards;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The Armory storage screen (MOD_SCOPE #118, round 168; user mock-up 2026-09-10: "It should basically
 * look just like the current Inventory tab, but where the current description comes up, it will just
 * be another storage tab... When you click on the Equipment button for the guard, the same interface
 * will come up. This time for the guard, vs. player.").
 * <p>
 * Same shape as {@link InventoryScene} - the doll on the left, an item grid on the right - with a
 * second grid, the Armory storage, where the inventory screen shows an item's description, and a
 * Transfer button that moves the selected item between the two grids. Two modes:
 * <ul>
 * <li><b>Player</b> (the Armory page's Storage button): the doll and the lower grid are the player's;
 *     Sell / Dispose / Use / Equip behave as on the inventory screen; Transfer deposits (never a worn
 *     or quest item) or withdraws.</li>
 * <li><b>Guard</b> (a roaming guard's Equipment button): the doll and the lower grid are the guard's
 *     worn items - a guard wears everything it holds, one item per slot - and Transfer gives an item
 *     from the storage (into its slot, swapping the old piece back) or takes one back. Sell, Dispose,
 *     Use and Equip do not apply and are hidden.</li>
 * </ul>
 * Every move goes through {@link ArmoryStorage}'s verbs, so an item is never in two places at once.
 */
public class ArmoryScene extends UIScene {
    private static ArmoryScene object;

    public static ArmoryScene instance() {
        if (object == null)
            object = new ArmoryScene();
        return object;
    }

    private static final String SLOT_BORDER_NAME = "slotBorder";
    private static final String SLOT_ITEM_NAME = "slotItem";

    /** Null in player mode. */
    private RoamingGuardData guard;
    private final Table storageGrid;
    private final Table inventoryGrid;
    private final int storageColumns;
    private final int inventoryColumns;
    private final HashMap<String, Button> equipmentSlots = new HashMap<>();
    private final HashMap<Button, ItemData> itemAt = new HashMap<>();
    private final HashSet<Button> inStorage = new HashSet<>();
    private final Array<Button> gridButtons = new Array<>();
    private final TextraButton equipButton;
    private final TextraButton useButton;
    private final TextraButton sellButton;
    private final TextraButton deleteButton;
    private final TextraButton transferButton;
    private final TextraLabel dollTitle;
    private final TextraLabel storageTitle;
    private final TextraLabel inventoryTitle;
    private final Texture equipOverlay;
    private final Texture unusableOverlay;
    private NinePatchDrawable slotBorderDrawable;
    private Button selected;
    private String selectedSlot;

    private ArmoryScene() {
        super(Forge.isLandscapeMode() ? "ui/armory.json" : "ui/armory_portrait.json");
        equipOverlay = Forge.getAssets().getTexture(Config.instance().getFile(Paths.ITEMS_EQUIP));
        unusableOverlay = Forge.getAssets().getTexture(Config.instance().getFile(Paths.ITEMS_UNUSABLE));
        ui.onButtonPress("return", this::done);
        ui.onButtonPress("equip", this::equip);
        ui.onButtonPress("use", this::use);
        ui.onButtonPress("sell", this::showSellConfirm);
        ui.onButtonPress("delete", this::showDeleteConfirm);
        ui.onButtonPress("transfer", this::transfer);
        equipButton = ui.findActor("equip");
        useButton = ui.findActor("use");
        sellButton = ui.findActor("sell");
        deleteButton = ui.findActor("delete");
        transferButton = ui.findActor("transfer");
        dollTitle = ui.findActor("dollTitle");
        storageTitle = ui.findActor("storageTitle");
        inventoryTitle = ui.findActor("inventoryTitle");

        Array<Actor> children = ui.getChildren();
        for (int i = 0, n = children.size; i < n; i++) {
            Actor child = children.get(i);
            if (child.getName() == null || !child.getName().startsWith("Equipment_"))
                continue;
            String slotName = child.getName().split("_")[1];
            equipmentSlots.put(slotName, (Button) child);
            child.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    Button button = (Button) actor;
                    if (button.isChecked()) {
                        for (Button other : equipmentSlots.values()) {
                            if (other != button && other.isChecked())
                                other.setChecked(false);
                        }
                        selectedSlot = slotName;
                        refresh();
                        ItemData worn = wornIn(slotName);
                        if (worn != null)
                            reselect(worn);
                        else
                            setSelected(null);
                    } else {
                        boolean anyChecked = false;
                        for (Button other : equipmentSlots.values()) {
                            if (other.isChecked()) {
                                anyChecked = true;
                                break;
                            }
                        }
                        if (!anyChecked) {
                            selectedSlot = null;
                            refresh();
                        }
                    }
                }
            });
        }

        storageGrid = new Table(Controls.getSkin());
        ScrollPane storagePane = ui.findActor("storage");
        storagePane.setScrollingDisabled(true, false);
        storagePane.setActor(storageGrid);
        storageColumns = columnsFor(storagePane);

        inventoryGrid = new Table(Controls.getSkin());
        ScrollPane inventoryPane = ui.findActor("inventory");
        inventoryPane.setScrollingDisabled(true, false);
        inventoryPane.setActor(inventoryGrid);
        inventoryColumns = columnsFor(inventoryPane);
    }

    private int columnsFor(ScrollPane pane) {
        int columns = (int) (pane.getWidth() / createSlot().getWidth()) - 1;
        return Math.max(1, columns);
    }

    /** Opens the screen for the player ({@code guard} null) or for a roaming guard. */
    public void open(RoamingGuardData guard) {
        this.guard = guard;
        Forge.switchScene(this);
    }

    @Override
    public void enter() {
        selectedSlot = null;
        for (Button slot : equipmentSlots.values()) {
            removeSlotBorder(slot);
            slot.setChecked(false);
        }
        refresh();
        setSelected(null);
        super.enter();
    }

    public void done() {
        selectedSlot = null;
        for (Button slot : equipmentSlots.values()) {
            removeSlotBorder(slot);
            slot.setChecked(false);
        }
        GameHUD.getInstance().getTouchpad().setVisible(false);
        Forge.switchToLast();
    }

    @Override
    public void act(float delta) {
        stage.act(delta);
    }

    // ------------------------------------------------------------------ what is where

    private boolean playerMode() {
        return guard == null;
    }

    private ItemData wornIn(String slot) {
        if (playerMode())
            return Current.player().getEquippedItem(Current.player().itemInSlot(slot));
        return ArmoryStorage.worn(guard, slot);
    }

    private List<ItemData> ownItems() {
        List<ItemData> items = new ArrayList<>();
        if (playerMode()) {
            for (ItemData item : Current.player().getItems()) {
                if (item != null)
                    items.add(item);
            }
        } else {
            items.addAll(guard.equipment);
        }
        return ArmoryStorage.sorted(items);
    }

    private List<ItemData> filtered(List<ItemData> items) {
        List<ItemData> out = new ArrayList<>();
        for (ItemData item : items) {
            if (item == null)
                continue;
            if (item.sprite() == null) {
                System.err.print("Can not find sprite name " + item.iconName + "\n");
                continue;
            }
            if (selectedSlot != null && !selectedSlot.equals(item.equipmentSlot))
                continue;
            out.add(item);
        }
        return out;
    }

    private boolean isWorn(ItemData item) {
        if (playerMode())
            return item.isEquipped && item.longID != null && Current.player().getEquippedItems().contains(item.longID);
        return guard.equipment.contains(item);
    }

    // ------------------------------------------------------------------ building the screen

    private void refresh() {
        clearSelectable();
        itemAt.clear();
        inStorage.clear();
        gridButtons.clear();
        storageGrid.clear();
        inventoryGrid.clear();

        fill(storageGrid, filtered(ArmoryStorage.stored()), storageColumns, true);
        fill(inventoryGrid, filtered(ownItems()), inventoryColumns, false);

        storageTitle.setText("[%70]Armory Storage (" + ArmoryStorage.items().size() + ")");
        if (playerMode()) {
            dollTitle.setText("[%70]" + Current.player().getName());
            inventoryTitle.setText("[%70]Inventory (" + Current.player().getItems().size() + ")");
        } else {
            String rank = RoamingGuards.displayName(guard.tier);
            dollTitle.setText("[%70]" + rank + " Guard");
            inventoryTitle.setText("[%70]" + rank + " Guard - worn (" + guard.equipment.size() + ")");
        }
        dollTitle.layout();
        storageTitle.layout();
        inventoryTitle.layout();

        boolean player = playerMode();
        sellButton.setVisible(player);
        deleteButton.setVisible(player);
        useButton.setVisible(player);
        equipButton.setVisible(player);

        refreshDoll();
    }

    private void fill(Table grid, List<ItemData> items, int columns, boolean storage) {
        for (int i = 0; i < items.size(); i++) {
            if (i % columns == 0)
                grid.row();
            Button button = createSlot();
            grid.add(button).top().left().space(1);
            addToSelectable(new Selectable(button) {
                @Override
                public void onSelect(UIScene scene) {
                    setSelected(button);
                    super.onSelect(scene);
                }
            });
            gridButtons.add(button);

            ItemData item = items.get(i);
            Image img = new Image(item.sprite());
            img.setX((button.getWidth() - img.getWidth()) / 2);
            img.setY((button.getHeight() - img.getHeight()) / 2);
            button.addActor(img);
            itemAt.put(button, item);
            if (storage)
                inStorage.add(button);
            if (!storage && isWorn(item)) {
                Image overlay = new Image(equipOverlay);
                overlay.setX(img.getX());
                overlay.setY(img.getY());
                button.addActor(overlay);
            } else if (item.isCracked) {
                Image overlay = new Image(unusableOverlay);
                overlay.setX(img.getX());
                overlay.setY(img.getY());
                button.addActor(overlay);
            }
            button.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    if (((Button) actor).isChecked())
                        setSelected((Button) actor);
                }
            });
        }
    }

    private void refreshDoll() {
        java.util.Set<String> granted = playerMode() ? Current.player().grantedEquipmentSlots() : java.util.Collections.emptySet();
        for (Map.Entry<String, Button> slot : equipmentSlots.entrySet()) {
            String name = slot.getKey();
            Button slotButton = slot.getValue();
            if ("Left2".equals(name) || "Right2".equals(name))
                slotButton.setVisible(playerMode() && granted.contains(name));
            else if (name.startsWith("Ability"))
                slotButton.setVisible(playerMode()); // a guard cannot trigger an ability (ArmoryStorage.isGuardSlot)
            Actor old = slotButton.findActor(SLOT_ITEM_NAME);
            if (old != null)
                old.remove();
            removeSlotBorder(slotButton);
            ItemData worn = wornIn(name);
            if (worn != null && worn.sprite() != null) {
                Image img = new Image(worn.sprite());
                img.setName(SLOT_ITEM_NAME);
                img.setX((slotButton.getWidth() - img.getWidth()) / 2);
                img.setY((slotButton.getHeight() - img.getHeight()) / 2);
                slotButton.addActor(img);
            }
            if (name.equals(selectedSlot))
                addSlotBorder(slotButton);
        }
    }

    private Button createSlot() {
        return new ImageButton(Controls.getSkin(), "item_frame");
    }

    // ------------------------------------------------------------------ selection

    private void setSelected(Button button) {
        selected = button;
        for (Button other : gridButtons) {
            if (other != button && other.isChecked())
                other.setChecked(false);
        }
        if (button == null || itemAt.get(button) == null) {
            selected = null;
            deleteButton.setDisabled(true);
            sellButton.setDisabled(true);
            useButton.setDisabled(true);
            equipButton.setDisabled(true);
            transferButton.setDisabled(true);
            transferButton.setText("[%75]Transfer");
            transferButton.layout();
            return;
        }
        ItemData data = itemAt.get(button);
        boolean stored = inStorage.contains(button);
        if (playerMode()) {
            deleteButton.setDisabled(stored || data.questItem);
            sellButton.setDisabled(stored || data.questItem);
            sellButton.setText("[%75]Sell " + sellPrice(data) + "[+GoldCoin]");
            sellButton.layout();
            boolean inPoi = MapStage.getInstance().isInMap();
            boolean usable = inPoi && data.usableInPoi || !inPoi && data.usableOnWorldMap;
            useButton.setDisabled(stored || !usable || Current.player().getShards() < data.shardsNeeded);
            useButton.setText(data.shardsNeeded == 0 ? "Use" : "Use " + data.shardsNeeded + "[+Shards]");
            useButton.layout();
            boolean wearable = data.equipmentSlot != null && !data.equipmentSlot.isEmpty() && !data.isCracked;
            equipButton.setDisabled(stored || !wearable);
            equipButton.setText(!stored && isWorn(data) ? "Unequip" : "Equip");
            equipButton.layout();
            transferButton.setDisabled(!stored && !ArmoryStorage.canDeposit(data));
            transferButton.setText(stored ? "[%70]To Inventory" : "[%70]To Storage");
        } else {
            transferButton.setDisabled(stored && !ArmoryStorage.guardCanWear(data));
            transferButton.setText(stored ? "[%75]Give" : "[%75]Take Back");
        }
        transferButton.layout();
    }

    /** Point the selection at the freshly built button for {@code data} after a rebuild - the same
     *  round-160 rule the inventory screen follows, since every refresh() builds new actors. */
    private void reselect(ItemData data) {
        for (Map.Entry<Button, ItemData> entry : itemAt.entrySet()) {
            if (entry.getValue() == data) {
                entry.getKey().setChecked(true);
                setSelected(entry.getKey());
                return;
            }
        }
        setSelected(null);
    }

    private ItemData selectedItem() {
        return selected == null ? null : itemAt.get(selected);
    }

    // ------------------------------------------------------------------ the actions

    private void transfer() {
        ItemData data = selectedItem();
        if (data == null)
            return;
        boolean stored = inStorage.contains(selected);
        boolean moved;
        if (playerMode())
            moved = stored ? ArmoryStorage.withdraw(data) : ArmoryStorage.deposit(data);
        else
            moved = stored ? ArmoryStorage.giveToGuard(guard, data) : ArmoryStorage.takeFromGuard(guard, data);
        if (!moved)
            System.out.println("[TFR-Armory] transfer refused for " + data.name + " (stored=" + stored + ", guard=" + (guard != null) + ")");
        refresh();
        reselect(data);
    }

    private void equip() {
        ItemData data = selectedItem();
        if (data == null || !playerMode() || inStorage.contains(selected))
            return;
        Current.player().equip(data);
        refresh();
        reselect(data);
    }

    private static int sellPrice(ItemData data) {
        return (int) (data.cost * 0.25f); // the inventory screen's rule (2026-08-23)
    }

    private void showSellConfirm() {
        ItemData data = selectedItem();
        if (data == null || !playerMode() || inStorage.contains(selected) || data.questItem)
            return;
        final int price = sellPrice(data);
        Dialog dialog = createGenericDialog("", "Sell " + data.name + " for " + price + "[+GoldCoin]?",
                Forge.getLocalizer().getMessage("lblYes"), Forge.getLocalizer().getMessage("lblNo"), () -> {
                    // pay only for an item the player still holds (round 141's rule)
                    if (Current.player().getItems().contains(data)) {
                        Current.player().giveGold(price);
                        Current.player().removeItem(data);
                    }
                    removeDialog();
                    refresh();
                    setSelected(null);
                }, this::removeDialog);
        showDialog(dialog);
    }

    private void showDeleteConfirm() {
        ItemData data = selectedItem();
        if (data == null || !playerMode() || inStorage.contains(selected) || data.questItem)
            return;
        Dialog dialog = createGenericDialog("", Forge.getLocalizer().getMessage("lblDelete"),
                Forge.getLocalizer().getMessage("lblYes"), Forge.getLocalizer().getMessage("lblNo"), () -> {
                    data.isEquipped = false;
                    Current.player().removeItem(data);
                    removeDialog();
                    refresh();
                    setSelected(null);
                }, this::removeDialog);
        showDialog(dialog);
    }

    private void use() {
        ItemData data = selectedItem();
        if (data == null || !playerMode() || inStorage.contains(selected))
            return;
        Dialog dialog = createGenericDialog("", null, Forge.getLocalizer().getMessage("lblYes"),
                Forge.getLocalizer().getMessage("lblNo"), () -> {
                    removeDialog();
                    triggerUse(data);
                }, this::removeDialog);
        TextraLabel label = Controls.newTextraLabel("Use " + data.name + "?\n" + data.getDescription());
        label.setWrap(true);
        dialog.getContentTable().add(label).width(Forge.isLandscapeMode() ? 250f : 230f).row();
        showDialog(dialog);
    }

    /** The inventory screen's use flow: pay the shards, leave, run the item's command or dialog. */
    private void triggerUse(ItemData data) {
        Current.player().addShards(-data.shardsNeeded);
        done();
        if (data.commandOnUse != null && !data.commandOnUse.isEmpty())
            ConsoleCommandInterpreter.getInstance().command(data.commandOnUse);
        if (data.dialogOnUse != null && data.dialogOnUse.text != null && !data.dialogOnUse.text.isEmpty()) {
            MapDialog dialog = new MapDialog(data.dialogOnUse, MapStage.getInstance(), 0, null);
            MapStage.getInstance().showDialog();
            dialog.activate();
            dialog.addDialogCompleteListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent changeEvent, Actor actor) {
                    AdventureQuestController.instance().showQuestDialogs(MapStage.getInstance());
                }
            });
        }
        AdventureQuestController.instance().updateItemUsed(data);
    }

    // ------------------------------------------------------------------ the doll's selection border

    private NinePatchDrawable getSlotBorderDrawable() {
        if (slotBorderDrawable == null) {
            int border = 4;
            int size = border * 2 + 2;
            Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
            pm.setColor(new Color(1f, 0.9f, 0.05f, 1f));
            pm.fill();
            pm.setBlending(Pixmap.Blending.None);
            pm.setColor(0f, 0f, 0f, 0f);
            pm.fillRectangle(border, border, size - border * 2, size - border * 2);
            Texture tex = new Texture(pm);
            pm.dispose();
            slotBorderDrawable = new NinePatchDrawable(new NinePatch(tex, border, border, border, border));
        }
        return slotBorderDrawable;
    }

    private void addSlotBorder(Button button) {
        Image border = new Image(getSlotBorderDrawable());
        border.setName(SLOT_BORDER_NAME);
        border.setSize(button.getWidth(), button.getHeight());
        button.addActor(border);
    }

    private void removeSlotBorder(Button button) {
        Actor border = button.findActor(SLOT_BORDER_NAME);
        if (border != null)
            border.remove();
    }
}
