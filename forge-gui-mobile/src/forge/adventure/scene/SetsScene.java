package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.adventure.data.ItemData;
import forge.adventure.data.ItemListData;
import forge.adventure.data.ItemSetData;
import forge.adventure.data.RoamingGuardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.util.ItemSets;
import forge.adventure.util.Paths;
import forge.adventure.util.RoamingGuards;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Round 631 - the Sets page (the gear sets of round 629). The user: "an interface that shows the bonuses per set for both
 * player and guards". Opened from the inventory's Sets button (the doll's bottom-left square) and from a roaming guard's
 * Armory screen. Two views on one parchment page, as the treasure maps: every set - its seven pieces (worn ones checked,
 * missing ones dimmed), worn and owned counts and the bonus it gives now - and one set: each piece's place or where to
 * find it, and the 3 / 5 / 7 bonuses with the cards' rules text. Back from a set returns to the list; back from the list
 * leaves.
 * <p>
 * "Worn" is whoever the page is for (the player, or that guard); "owned" is always the player's - carried, stored or worn
 * by any guard - since a guard's gear comes from the player.
 * <p>
 * Round 637 (the user: "only have the key/icon show up when you wear one of the set pieces, and then, only display
 * information on that set when you click on the key. So other set info will be hidden unless you wear a piece of a
 * set"): the list holds only the sets with a piece worn, and with just one such set the page opens on it (Back leaves).
 * <p>
 * Round 686 (the user: "the Own column does not show how many you own, unless you are wearing it", and "if I remove one
 * of those pieces the whole column disappears.. That does not seem right"): the player's list holds every set they OWN a
 * piece of, worn or not - a set they own nothing of stays hidden. A guard's page still lists what that guard wears.
 */
public class SetsScene extends UIScene {
    private static SetsScene object;
    private static final String GREEN = "[#2b7a2b]";
    private static final String DIM = "[#6e604a]";
    private static final String INK = "[BLACK]";
    private static final String[] FAMILIES = {"Regalia", "Bane", "Legend"};
    private static final String[] SLOTS = {"Head", "Neck", "Body", "Right", "Left", "Legs", "Boots"};

    private final TextraLabel titleLabel;
    private final Table area;
    private RoamingGuardData guard; // null = the player
    private ItemSetData shown;       // null = the list
    private boolean onlyOne;         // round 637: one worn set - the page opened on it, so Back leaves
    private Texture equipOverlay;

    private SetsScene() {
        super(Forge.isLandscapeMode() ? "ui/sets.json" : "ui/sets_portrait.json");
        titleLabel = ui.findActor("title");
        area = ui.findActor("setArea");
        com.badlogic.gdx.scenes.scene2d.Actor frame = ui.findActor("scrollWindow");
        if (frame != null)
            frame.setTouchable(Touchable.disabled); // InfoTextScene's round-151 lesson: a touched Window jumps to the front
        ui.onButtonPress("return", this::onReturn);
    }

    public static SetsScene instance() {
        if (object == null)
            object = new SetsScene();
        return object;
    }

    /** True when this plane has gear sets (the buttons that open the page show only then). */
    public static boolean available() {
        return ItemSets.all().length > 0;
    }

    /** The page for the player ({@code guard} null) or for one roaming guard. */
    public static void show(RoamingGuardData guard) {
        SetsScene scene = instance();
        scene.guard = guard;
        List<ItemSetData> listed = scene.listedSets();
        scene.onlyOne = listed.size() == 1;
        scene.shown = scene.onlyOne ? listed.get(0) : null;
        scene.rebuild();
        if (Forge.getCurrentScene() != scene)
            Forge.switchScene(scene, true);
    }

    private void onReturn() {
        if (shown != null && !onlyOne) {
            shown = null;
            rebuild();
            return;
        }
        back();
    }

    /** The sets the page lists, the most pieces worn first: for the player every set they own a piece of (round 686), for
     *  a guard the sets that guard wears a piece of (round 637). */
    private List<ItemSetData> listedSets() {
        Map<ItemSetData, Integer> worn = ItemSets.worn(wornItems());
        List<ItemSetData> sets = guard == null ? ItemSets.ownedSets(Current.player()) : new ArrayList<>(worn.keySet());
        sets.sort((a, b) -> Integer.compare(count(worn, b), count(worn, a)));
        return sets;
    }

    // ------------------------------------------------------------------ what the page is about

    private List<ItemData> wornItems() {
        if (guard != null)
            return new ArrayList<>(guard.equipment);
        return ItemSets.wornBy(Current.player());
    }

    private String subject() {
        return guard == null ? "" : RoamingGuards.displayName(guard.tier) + " Guard - ";
    }

    private static int count(Map<ItemSetData, Integer> worn, ItemSetData set) {
        Integer n = worn.get(set);
        return n == null ? 0 : n;
    }

    /** What the set gives now, one short line ("" below 3 pieces). */
    private static String bonusNow(ItemSetData set, int pieces) {
        List<String> parts = new ArrayList<>();
        for (ItemSetData.Tier tier : ItemSets.reached(set, pieces))
            parts.add(tier.text);
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------ building

    private void rebuild() {
        area.clear();
        if (equipOverlay == null)
            equipOverlay = Forge.getAssets().getTexture(Config.instance().getFile(Paths.ITEMS_EQUIP));
        if (shown != null)
            buildOne(shown);
        else
            buildAll();
        System.out.println("[TFR-Sets] page: " + (guard == null ? "player" : RoamingGuards.displayName(guard.tier) + " guard")
                + (shown == null ? " - all sets" : " - " + shown.name));
    }

    private TextraLabel label(String text) {
        TextraLabel l = Controls.newTextraLabel(INK + text);
        l.setAlignment(Align.left);
        return l;
    }

    private TextraLabel wrapped(String text) {
        TextraLabel l = label(text);
        l.setWrap(true);
        return l;
    }

    /** A piece's icon at 16 x 16: dimmed when the player owns none, checked when the page's subject wears it. */
    private com.badlogic.gdx.scenes.scene2d.Actor icon(String itemName, boolean owned, boolean worn) {
        ItemData data = ItemListData.getItem(itemName);
        Sprite sprite = data == null ? null : data.sprite();
        if (sprite == null)
            return label("[%80]" + DIM + "?");
        Image image = new Image(sprite);
        if (!owned)
            image.setColor(new Color(0.55f, 0.5f, 0.45f, 0.45f));
        if (!worn)
            return image;
        Stack stack = new Stack();
        stack.add(image);
        stack.add(new Image(equipOverlay));
        return stack;
    }

    private void open(ItemSetData set) {
        shown = set;
        rebuild();
    }

    /** A click on the row and on its name opens the set; the name's own listener stops the event so it opens once. */
    private ClickListener opener(ItemSetData set, boolean stop) {
        return new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (stop)
                    event.stop();
                open(set);
            }
        };
    }

    private void buildAll() {
        AdventurePlayer player = Current.player();
        Map<ItemSetData, Integer> worn = ItemSets.worn(wornItems());
        List<ItemSetData> sets = listedSets(); // round 686: the player's owned sets; a guard's worn ones
        sets.sort((a, b) -> {
            int c = Integer.compare(count(worn, b), count(worn, a));
            if (c == 0)
                c = Integer.compare(ItemSets.ownedCount(player, b), ItemSets.ownedCount(player, a));
            if (c == 0)
                c = Integer.compare(Arrays.asList(FAMILIES).indexOf(a.family), Arrays.asList(FAMILIES).indexOf(b.family));
            return c;
        });
        int active = 0;
        for (ItemSetData set : sets)
            if (count(worn, set) >= 3)
                active++;
        titleLabel.setText(subject() + "Gear Sets - " + (guard == null ? "pieces of " : "wearing pieces of ") + sets.size()
                + (sets.size() == 1 ? " set" : " sets") + (guard == null ? " owned" : "") + ", " + active + " active");
        if (sets.isEmpty()) {
            area.add(label("[%80]" + DIM + (guard == null
                    ? "You own no gear set piece yet. Find one, and its set shows here."
                    : "No set piece is worn. Wear a piece of a gear set to see its set here."))).left();
            return;
        }

        boolean landscape = Forge.isLandscapeMode();
        Table list = new Table();
        list.top().left();
        if (landscape) { // the same widths as a row's cells
            Table header = new Table();
            header.add().width(122f + 141f);
            header.add(label("[%70]" + DIM + "worn")).left().width(30f);
            header.add(label("[%70]" + DIM + "owned")).left().width(30f);
            header.add().width(62f);
            list.add(header).left().row();
        }
        for (ItemSetData set : sets) {
            int pieces = count(worn, set);
            int owned = ItemSets.ownedCount(player, set);
            Table icons = new Table();
            icons.left();
            for (String item : set.items) {
                boolean has = ItemSets.owns(player, item);
                boolean on = false;
                for (ItemData w : wornItems())
                    if (w != null && !w.isCracked && item.equalsIgnoreCase(w.name))
                        on = true;
                icons.add(icon(item, has, on)).size(landscape ? 15f : 13f).pad(landscape ? 1f : 0.5f);
            }
            TextraLabel name = label("[%85]" + set.name);
            name.addListener(opener(set, true));
            Table names = new Table();
            names.left(); // the name column hugs the icons (a Table centers its content by default)
            names.add(name).left().row();
            names.add(label("[%65]" + DIM + ItemSets.familyLine(set))).left();
            String now = bonusNow(set, pieces);
            Table row = new Table();
            row.left();
            row.setTouchable(Touchable.enabled);
            row.addListener(opener(set, false));
            if (landscape) {
                row.add(icons).left().width(122f);
                row.add(names).left().width(138f).padLeft(3);
                row.add(label("[%85]" + (pieces >= 3 ? GREEN : "") + pieces + "/" + set.items.length)).width(30f);
                row.add(label("[%85]" + owned + "/" + set.items.length)).width(30f);
                row.add(label("[%65]" + GREEN + now)).left().width(62f);
            } else {
                Table counts = new Table();
                counts.add(label("[%70]" + (pieces >= 3 ? GREEN : "") + pieces + "/" + set.items.length + " worn")).right().row();
                counts.add(label("[%70]" + owned + "/" + set.items.length + " owned")).right();
                row.add(icons).left().colspan(2).row();
                row.add(names).left().width(170f);
                row.add(counts).right().width(70f);
                if (!now.isEmpty()) {
                    row.row();
                    row.add(label("[%65]" + GREEN + now)).left().colspan(2);
                }
            }
            list.add(row).left().padBottom(landscape ? 2 : 4).row();
        }
        ScrollPane pane = new ScrollPane(list, Controls.getSkin(), "nobg");
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        area.add(pane).expand().fill().row();
        area.add(label("[%65]" + DIM + "Tap a set for its pieces, its bonuses and where to find them.")).left().padTop(2);
        stage.setScrollFocus(pane);
    }

    private String slotOf(ItemSetData set, String itemName, int index) {
        ItemData data = ItemListData.getItem(itemName);
        if (data != null && data.equipmentSlot != null && !data.equipmentSlot.isEmpty())
            return data.equipmentSlot;
        return index < SLOTS.length ? SLOTS[index] : "";
    }

    private void buildOne(ItemSetData set) {
        AdventurePlayer player = Current.player();
        List<ItemData> wornItems = wornItems();
        int pieces = count(ItemSets.worn(wornItems), set);
        titleLabel.setText(subject() + set.name + " (" + ItemSets.familyLine(set).replace(" - ", ", ") + ") - " + pieces
                + " of " + set.items.length + " worn");
        boolean landscape = Forge.isLandscapeMode();
        float column = landscape ? 186f : 240f;

        Table piecesTable = new Table();
        piecesTable.top().left();
        for (int i = 0; i < set.items.length; i++) {
            String item = set.items[i];
            String where = ItemSets.locate(player, guard, item);
            boolean on = false;
            for (ItemData w : wornItems)
                if (w != null && !w.isCracked && item.equalsIgnoreCase(w.name))
                    on = true;
            String status;
            if (where != null)
                status = (on ? GREEN : INK) + slotOf(set, item, i) + " - " + where;
            else
                status = DIM + slotOf(set, item, i) + " - " + ItemSets.whereToFind(set, item);
            Table text = new Table();
            text.add(label("[%80]" + item)).left().row();
            text.add(wrapped("[%62]" + status)).left().width(column - 22f);
            piecesTable.add(icon(item, where != null, on)).size(16f).top().padRight(4).padTop(1);
            piecesTable.add(text).left().width(column - 22f).padBottom(3).row();
        }

        Table bonus = new Table();
        bonus.top().left();
        bonus.add(label("[%80]Bonus, on top of the items")).left().padBottom(3).row();
        List<ItemSetData.Tier> reached = ItemSets.reached(set, pieces);
        for (ItemSetData.Tier tier : set.tiers) {
            boolean on = reached.contains(tier);
            boolean replaced = pieces >= tier.pieces && !on;
            String head = tier.pieces + " pieces" + (on ? " (active)" : replaced ? " (replaced by the full card)" : "");
            String body = tier.oracle == null ? tier.text
                    : tier.pieces >= 5 ? tier.text + ": " + tier.oracle : tier.oracle;
            String color = on ? GREEN : replaced ? DIM : INK;
            bonus.add(label("[%75]" + color + head)).left().row();
            bonus.add(wrapped("[%65]" + color + body)).left().width(column - 6f).padLeft(6).padBottom(3).row();
        }
        if (set.where != null && !set.where.isEmpty()) {
            bonus.add(label("[%80]Where to find it")).left().padTop(4).row();
            bonus.add(wrapped("[%65]" + set.where + ".")).left().width(column - 6f).padLeft(6).row();
        }

        Table content = new Table();
        content.top().left();
        if (landscape) {
            content.add(piecesTable).top().left().width(column).padRight(8);
            content.add(bonus).top().left().width(column);
        } else {
            content.add(piecesTable).top().left().width(column).row();
            content.add(bonus).top().left().width(column).padTop(6);
        }
        ScrollPane pane = new ScrollPane(content, Controls.getSkin(), "nobg");
        pane.setScrollingDisabled(true, false);
        area.add(pane).expand().fill();
        stage.setScrollFocus(pane);
    }
}
