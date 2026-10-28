package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ActorGestureListener;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.github.tommyettinger.textra.TextraButton;
import forge.adventure.util.Controls;
import forge.assets.FSkin;
import forge.assets.FSkinImageInterface;
import forge.card.CardRarity;
import forge.gui.UiCommand;
import forge.item.PaperCard;
import forge.itemmanager.GroupDef;
import forge.itemmanager.SFilterUtil;
import forge.itemmanager.SItemManagerUtil.StatTypes;
import forge.localinstance.skin.FSkinProp;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Round 817 (the user: "like the deck building interface, I want to add color / type filters. And also the Group by
 * filter from the deck builder"): the Research Lab's Found Cards filters. The deck builder's own widgets (ItemManager's
 * CardColorFilter, CardTypeFilter and ImageView's "Groups:" box) are Forge-toolkit FContainers drawn by an FScreen, and
 * cannot sit in the Lab's scene2d window, so this reuses everything under them instead: the same skin icons and
 * predicates (StatTypes), the deck builder's color rule itself (SFilterUtil.buildColorFilter, through a small IButton
 * adapter, long-press "only this one" included - SFilterUtil.showOnlyStat) and its grouping (GroupDef, the card group-by
 * options less PW Deck Sort, a deck-building order). The rarity buttons are SpellSmith's, as many at once as wanted.
 * <p>
 * The state lives as long as the Lab's singleton and is cleared with the Lab's other filters on another save.
 */
public final class ResearchCardFilters {
    private static final StatTypes[] COLORS = {StatTypes.WHITE, StatTypes.BLUE, StatTypes.BLACK, StatTypes.RED,
            StatTypes.GREEN, StatTypes.COLORLESS, StatTypes.MULTICOLOR};
    // CardTypeFilter's order.
    private static final StatTypes[] TYPES = {StatTypes.LAND, StatTypes.ARTIFACT, StatTypes.CREATURE, StatTypes.ENCHANTMENT,
            StatTypes.PLANESWALKER, StatTypes.INSTANT, StatTypes.SORCERY, StatTypes.BATTLE};
    private static final GroupDef[] GROUPS = {GroupDef.DEFAULT, GroupDef.CARD_TYPE, GroupDef.COLOR, GroupDef.COLOR_IDENTITY,
            GroupDef.SET, GroupDef.CARD_RARITY};
    static final String[] RARITIES = {"Common", "Uncommon", "Rare", "Mythic"};
    static final float ICON = 13f;

    private final Map<StatTypes, Toggle> toggles = new EnumMap<>(StatTypes.class);
    private final Set<String> rarities = new java.util.HashSet<>();
    private GroupDef groupBy = null;

    public ResearchCardFilters() {
        for (StatTypes st : COLORS)
            toggles.put(st, new Toggle());
        for (StatTypes st : TYPES)
            toggles.put(st, new Toggle());
    }

    /** Everything shown, no groups - a new game's start. */
    public void reset() {
        for (Toggle t : toggles.values())
            t.setSelected(true);
        rarities.clear();
        groupBy = null;
    }

    /** True while any filter hides something. */
    public boolean active() {
        return !rarities.isEmpty() || toggles.values().stream().anyMatch(t -> !t.isSelected());
    }

    public Predicate<PaperCard> predicate() {
        Map<StatTypes, Toggle> colorMap = new EnumMap<>(StatTypes.class);
        for (StatTypes st : COLORS)
            colorMap.put(st, toggles.get(st));
        Predicate<PaperCard> color = SFilterUtil.buildColorFilter(colorMap);
        // CardTypeFilter.buildPredicate: every type on = every card (a type outside the list, like a Kindred, too).
        boolean allTypes = true;
        List<StatTypes> on = new java.util.ArrayList<>();
        for (StatTypes st : TYPES) {
            if (toggles.get(st).isSelected())
                on.add(st);
            else
                allTypes = false;
        }
        final boolean everyType = allTypes;
        Set<String> rar = new java.util.HashSet<>(rarities);
        return pc -> {
            if (!color.test(pc))
                return false;
            if (!everyType && on.stream().noneMatch(st -> st.predicate.test(pc.getRules())))
                return false;
            return rar.isEmpty() || rar.contains(rarityName(pc));
        };
    }

    static String rarityName(PaperCard pc) {
        CardRarity r = pc.getRarity();
        if (r == CardRarity.MythicRare)
            return "Mythic";
        if (r == CardRarity.Rare || r == CardRarity.Special)
            return "Rare";
        return r == CardRarity.Uncommon ? "Uncommon" : "Common";
    }

    public GroupDef groupBy() {
        return groupBy;
    }

    /** The group names in order and each card's group; a card outside them goes to "Other" (the deck builder's rule). */
    public Map<String, List<PaperCard>> group(List<PaperCard> cards) {
        Map<String, List<PaperCard>> out = new LinkedHashMap<>();
        if (groupBy == null) {
            out.put("", new java.util.ArrayList<>(cards));
            return out;
        }
        String[] names = groupBy.getGroups();
        for (String n : names)
            out.put(n, new java.util.ArrayList<>());
        out.put("Other", new java.util.ArrayList<>());
        for (PaperCard pc : cards) {
            int i = groupBy == GroupDef.SET ? -2 : groupBy.getItemGroupIndex(pc);
            String key = i == -2 ? forge.adventure.util.ResearchMeter.editionName(pc.getEdition())
                    : (i >= 0 && i < names.length ? names[i] : "Other");
            out.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(pc);
        }
        out.values().removeIf(List::isEmpty);
        return out;
    }

    // ---- widgets --------------------------------------------------------------------------------------------------

    /** The deck builder's 7 color and 8 type icons: a tap turns one off or on, a long press keeps only that one. */
    public Table iconRow(Runnable onChange) {
        Table row = new Table();
        addIcons(row, COLORS, onChange);
        row.add().width(5f);
        addIcons(row, TYPES, onChange);
        return row;
    }

    private void addIcons(Table row, StatTypes[] group, Runnable onChange) {
        Map<StatTypes, Toggle> groupMap = new EnumMap<>(StatTypes.class);
        for (StatTypes st : group)
            groupMap.put(st, toggles.get(st));
        for (StatTypes st : group) {
            Toggle t = toggles.get(st);
            Actor icon = icon(st);
            t.actor = icon;
            t.paint();
            icon.addListener(new ActorGestureListener() {
                @Override
                public void tap(InputEvent event, float x, float y, int count, int button) {
                    t.setSelected(!t.isSelected());
                    for (Toggle o : groupMap.values())
                        o.paint();
                    onChange.run();
                }

                @Override
                public boolean longPress(Actor actor, float x, float y) {
                    SFilterUtil.showOnlyStat(st, t, groupMap);
                    for (Toggle o : groupMap.values())
                        o.paint();
                    onChange.run();
                    return true;
                }
            });
            row.add(icon).size(ICON, ICON).padRight(1f);
        }
    }

    private static Actor icon(StatTypes st) {
        FSkinImageInterface img = FSkin.getImages().get(st.skinProp);
        TextureRegion region = img == null ? null : img.getTextureRegion();
        if (region == null) { // a skin without the picture: the type's first letter
            TextraButton b = Controls.newTextButton(st.name().substring(0, 1));
            return b;
        }
        return new Image(new TextureRegionDrawable(region));
    }

    /** SpellSmith's four rarity buttons; none on = every rarity. */
    public Table rarityRow(Runnable onChange, float buttonWidth) {
        Table row = new Table();
        for (String r : RARITIES) {
            TextraButton[] holder = new TextraButton[1];
            holder[0] = Controls.newTextButton("[%80]" + r, () -> {
                if (!rarities.remove(r))
                    rarities.add(r);
                holder[0].setColor(rarities.contains(r) ? Color.RED : Color.WHITE);
                onChange.run();
            });
            holder[0].setColor(rarities.contains(r) ? Color.RED : Color.WHITE);
            row.add(holder[0]).width(buttonWidth).height(13f).padRight(2f);
        }
        return row;
    }

    /** The deck builder's "Groups:" box. */
    public SelectBox<String> groupBox(Runnable onChange) {
        @SuppressWarnings("unchecked")
        SelectBox<String> box = Controls.newComboBox();
        String[] items = new String[GROUPS.length + 1];
        items[0] = "Groups: (" + forge.Forge.getLocalizer().getMessage("lblNone") + ")";
        for (int i = 0; i < GROUPS.length; i++)
            items[i + 1] = "Groups: " + GROUPS[i].getName();
        box.setItems(items);
        int sel = 0;
        for (int i = 0; i < GROUPS.length; i++)
            if (GROUPS[i] == groupBy)
                sel = i + 1;
        box.setSelectedIndex(sel);
        box.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                int i = box.getSelectedIndex();
                groupBy = i <= 0 ? null : GROUPS[i - 1];
                onChange.run();
            }
        });
        return box;
    }

    /** A set box: "All Sets" first, then each set by name; the code of the chosen one (null for All Sets) goes to onPick. */
    public static SelectBox<String> setBox(List<String> codes, String selected, java.util.function.Consumer<String> onPick) {
        @SuppressWarnings("unchecked")
        SelectBox<String> box = Controls.newComboBox();
        String[] items = new String[codes.size() + 1];
        items[0] = "All Sets";
        int sel = 0;
        for (int i = 0; i < codes.size(); i++) {
            items[i + 1] = forge.adventure.util.ResearchMeter.editionName(codes.get(i));
            if (codes.get(i).equals(selected))
                sel = i + 1;
        }
        box.setItems(items);
        box.setSelectedIndex(sel);
        box.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                int i = box.getSelectedIndex();
                onPick.accept(i <= 0 ? null : codes.get(i - 1));
            }
        });
        return box;
    }

    /** One icon's on/off, as the IButton SFilterUtil reads; drawn dim while off. */
    private static final class Toggle implements forge.gui.interfaces.IButton {
        private boolean selected = true;
        Actor actor;

        void paint() {
            if (actor != null)
                actor.setColor(1f, 1f, 1f, selected ? 1f : 0.3f);
        }

        @Override public boolean isSelected() { return selected; }
        @Override public void setSelected(boolean b0) { selected = b0; }
        @Override public boolean requestFocusInWindow() { return false; }
        @Override public void setCommand(UiCommand command0) { }
        @Override public void setImage(FSkinProp color) { }
        @Override public void setTextColor(int r, int g, int b) { }
        @Override public String getText() { return ""; }
        @Override public void setText(String text0) { }
        @Override public boolean isEnabled() { return true; }
        @Override public void setEnabled(boolean b0) { }
        @Override public boolean isVisible() { return true; }
        @Override public void setVisible(boolean b0) { }
        @Override public String getToolTipText() { return ""; }
        @Override public void setToolTipText(String s0) { }
    }
}
