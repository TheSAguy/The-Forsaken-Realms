package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Scaling;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.adventure.data.EnemyData;
import forge.adventure.data.ItemData;
import forge.adventure.data.ItemListData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.util.EnemyAvatars;
import forge.adventure.util.Paths;
import forge.adventure.util.RealmJournal;
import forge.adventure.util.TfrAchievements;
import forge.deck.Deck;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Round 897 - the Realm Journal, opened from the character sheet. Two tabs, Creatures and Legends, of the enemies this
 * save has met (and, dimmed with no facts, what the profile recalls of other runs); a creature's page says what the Alt
 * plates know of each rank, and a battled rank opens its deck in the read-only DeckPreviewScene. The Sets page's own
 * layouts, so no new ui json: list and page side by side in landscape; in portrait the list, then a tap opens the page,
 * and Back returns. What it knows is util/RealmJournal.
 * <p>
 * The tab, filters, list page, chosen entry and scroll are fields, so Back from a deck rebuilds the same view: enter()
 * builds every time, because leaving frees the avatar pages. A list page is {@link #PAGE_ROWS} rows at most.
 */
public class JournalScene extends UIScene {
    private static final int PAGE_ROWS = 40;
    private static final String INK = "[BLACK]";
    private static final String DIM = "[#6e604a]";
    /** The plates' rank colors (TfrPlates) are for a dark plate; these are the same four, darkened for parchment. */
    private static final String[] RANK_COLOR = {"[#566146]", "[#22688f]", "[#7b3296]", "[#8f6a10]"};
    private static final String[] RANKS = {"Apprentice", "Adept", "Master", "Archmage"};
    private static final String[] SORTS = {"Name", "Most fought"};

    private static JournalScene object;

    private final TextraLabel titleLabel;
    private final Table area;
    private boolean legends;
    private String land;        // null = any
    private int rank = -1;      // -1 = any
    private int sort;
    private int page;
    private String chosen;      // the entry's name, or null
    private boolean detailOpen; // portrait: the page over the list
    private float listScroll;
    private ScrollPane listPane;

    private JournalScene() {
        super(Forge.isLandscapeMode() ? "ui/sets.json" : "ui/sets_portrait.json");
        titleLabel = ui.findActor("title");
        area = ui.findActor("setArea");
        Actor frame = ui.findActor("scrollWindow");
        if (frame != null)
            frame.setTouchable(Touchable.disabled); // InfoTextScene's round-151 lesson: a touched Window jumps to the front
        ui.onButtonPress("return", this::back);
    }

    public static JournalScene instance() {
        if (object == null)
            object = new JournalScene();
        return object;
    }

    /** The character sheet's button: the top of the list, on the filters left from last time. */
    public static void show() {
        JournalScene scene = instance();
        scene.page = 0;
        scene.chosen = null;
        scene.detailOpen = false;
        scene.listScroll = 0f;
        Forge.switchScene(scene, true);
    }

    @Override
    public void enter() {
        super.enter();
        rebuild();
    }

    @Override
    public boolean leave() {
        keepScroll();
        EnemyAvatars.release();
        return super.leave();
    }

    /** Portrait's page closes back to the list; else leave. */
    @Override
    public boolean back() {
        if (detailOpen) {
            detailOpen = false;
            rebuild();
            return true;
        }
        EnemyAvatars.release(); // switchToLast does not call leave
        return super.back();
    }

    /** The list changed: rebuild must not keep the old pane's scroll. */
    private void topOfList() {
        listPane = null;
        listScroll = 0f;
    }

    private void keepScroll() {
        if (listPane != null)
            listScroll = listPane.getScrollY();
    }

    // ------------------------------------------------------------------ what the page is about

    private List<RealmJournal.Entry> shownEntries(List<RealmJournal.Entry> all) {
        List<RealmJournal.Entry> out = new ArrayList<>();
        for (RealmJournal.Entry e : all) {
            if (e.bossTab() != legends)
                continue;
            if (!legends && land != null && !RealmJournal.lands(e.name).contains(land))
                continue;
            if (rank >= 0 && !battledAt(e, rank))
                continue;
            out.add(e);
        }
        if (sort == 1)
            out.sort((a, b) -> Integer.compare(b.fights(), a.fights())); // stable: the names stay in order within a count
        return out;
    }

    private static boolean battledAt(RealmJournal.Entry e, int wanted) {
        for (EnemyData r : e.ranks)
            if (EnemyData.tierRank(r.tier) == wanted && !e.earlierRun && RealmJournal.knowledge(r) == 2)
                return true;
        return false;
    }

    // ------------------------------------------------------------------ building

    private void rebuild() {
        keepScroll();
        area.clear();
        listPane = null;
        AdventurePlayer player = Current.player();
        Set<String> guide = TfrAchievements.seenNames(TfrAchievements.FIELD_GUIDE);
        Set<String> hunted = TfrAchievements.seenNames(TfrAchievements.LEGEND_HUNTER);
        List<RealmJournal.Entry> all = RealmJournal.entries(player, guide, hunted);
        List<RealmJournal.Entry> shown = shownEntries(all);
        int pages = Math.max(1, (shown.size() + PAGE_ROWS - 1) / PAGE_ROWS);
        page = Math.max(0, Math.min(page, pages - 1));
        RealmJournal.Entry current = find(shown, chosen);
        boolean landscape = Forge.isLandscapeMode();
        if (current == null && landscape && !shown.isEmpty())
            current = shown.get(Math.min(page * PAGE_ROWS, shown.size() - 1));
        chosen = current == null ? null : current.name;
        if (current == null)
            detailOpen = false;

        titleLabel.setText("Realm Journal");
        area.top().left();
        if (!legends || !landscape || !detailOpen)
            area.add(guideBar(guide, hunted)).left().fillX().row();
        if (!landscape && detailOpen) {
            area.add(detail(current, 244f)).expand().fill().top().left();
        } else {
            area.add(controls(landscape)).left().padTop(2f).row();
            Table list = list(shown, landscape, pages);
            if (landscape) {
                Table body = new Table();
                body.top().left();
                body.add(list).top().left().width(214f).expandY().fillY();
                body.add(current == null ? new Table() : detail(current, 168f)).top().left().width(168f).padLeft(6f).expandY().fillY();
                area.add(body).expand().fill().padTop(2f);
            } else {
                area.add(list).expand().fill().padTop(2f);
            }
        }
        area.layout();
        if (listPane != null) {
            stage.setScrollFocus(listPane);
            area.validate();
            listPane.layout();
            listPane.setScrollY(listScroll);
            listPane.updateVisualScroll();
        }
        System.out.println("[TFR-Journal] page: " + (legends ? "legends" : "creatures") + ", " + shown.size() + " of " + all.size()
                + " entries, land " + land + ", rank " + rank + ", list page " + (page + 1) + "/" + pages
                + ", scroll " + Math.round(listScroll) + (chosen == null ? "" : ", open on " + chosen));
    }

    private static RealmJournal.Entry find(List<RealmJournal.Entry> entries, String name) {
        if (name != null)
            for (RealmJournal.Entry e : entries)
                if (e.name.equals(name))
                    return e;
        return null;
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

    /** "Field Guide: 214 of 848 creatures" and its bar; on a land, that land's count; on Legends, the legends beaten. */
    private Table guideBar(Set<String> guide, Set<String> hunted) {
        int have, of;
        String text;
        if (legends) {
            have = hunted.size();
            of = RealmJournal.legendCount();
            text = have + " of " + of + " legends beaten";
        } else {
            have = RealmJournal.recorded(guide, land);
            of = land == null ? RealmJournal.rosterSize() : RealmJournal.rosterSize(land);
            text = "Field Guide: " + have + " of " + of + (land == null ? " creatures" : " on " + land + " land");
        }
        Table bar = new Table();
        bar.left();
        bar.add(label("[%70]" + text)).left().padRight(4f);
        float width = 110f, fill = of <= 0 ? 0f : Math.min(1f, have / (float) of);
        Table track = new Table();
        track.left();
        track.setBackground(tint(new Color(0.13f, 0.11f, 0.2f, 1f)));
        if (fill > 0f)
            track.add(new Image(tint(new Color(0.72f, 0.55f, 0.18f, 1f)))).width(Math.max(1f, width * fill)).height(3f);
        bar.add(track).width(width).height(3f).left();
        return bar;
    }

    private static TextureRegionDrawable tintBase;

    private static com.badlogic.gdx.scenes.scene2d.utils.Drawable tint(Color color) {
        if (tintBase == null)
            tintBase = new TextureRegionDrawable(new TextureRegion(Forge.getAssets().getWhiteTexture()));
        return tintBase.tint(color);
    }

    private Table controls(boolean landscape) {
        Table row = new Table();
        row.left();
        row.add(tab("Creatures", !legends, false)).height(15f);
        row.add(tab("Legends", legends, true)).height(15f).padLeft(2f).padRight(6f);
        if (!legends) {
            for (int i = 0; i < RealmJournal.LANDS.length; i++)
                row.add(landPip(RealmJournal.LANDS[i], RealmJournal.LAND_PIPS.charAt(i))).size(13f).padLeft(1f);
        }
        if (!landscape)
            row.row();
        TextraButton rankButton = Controls.newTextButton("[%70]" + (rank < 0 ? "Any rank" : RANKS[rank]), () -> {
            rank = rank == 3 ? -1 : rank + 1;
            page = 0;
            topOfList();
            rebuild();
        });
        TextraButton sortButton = Controls.newTextButton("[%70]" + SORTS[sort], () -> {
            sort = (sort + 1) % SORTS.length;
            page = 0;
            topOfList();
            rebuild();
        });
        Table right = new Table();
        right.add(rankButton).height(15f).width(52f);
        right.add(sortButton).height(15f).width(56f).padLeft(2f);
        if (landscape)
            row.add(right).padLeft(5f);
        else
            row.add(right).colspan(8).left().padTop(2f);
        return row;
    }

    private TextraButton tab(String text, boolean on, boolean toLegends) {
        return Controls.newTextButton("[%75]" + (on ? "[+OK] " : "") + text, () -> {
            if (legends == toLegends)
                return;
            legends = toLegends;
            page = 0;
            chosen = null;
            detailOpen = false;
            topOfList();
            rebuild();
        });
    }

    private Actor landPip(String name, char letter) {
        TextureAtlas pips = Config.instance().getAtlas(Paths.PIXELMANA_ATLAS);
        TextureAtlas.AtlasRegion pip = pips.findRegion("pm" + letter);
        Button.ButtonStyle style = new Button.ButtonStyle();
        Button button = new Button(style);
        button.setName("land " + name);
        if (pip != null) {
            Image image = new Image(pip);
            image.setScaling(Scaling.fit);
            if (land != null && !land.equals(name))
                image.setColor(1f, 1f, 1f, 0.35f);
            button.add(image).size(land != null && land.equals(name) ? 13f : 11f);
        } else {
            button.add(label("[%70]" + letter));
        }
        button.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                land = name.equals(land) ? null : name;
                page = 0;
                chosen = null;
                topOfList();
                rebuild();
            }
        });
        return button;
    }

    private Table list(List<RealmJournal.Entry> shown, boolean landscape, int pages) {
        Table outer = new Table();
        outer.top().left();
        if (shown.isEmpty()) {
            outer.add(wrapped("[%75]" + DIM + (legends ? "No legend or boss fought yet." : "Nothing here yet. Battle a creature and it is recorded.")))
                    .left().top().width(landscape ? 200f : 240f);
            return outer;
        }
        float width = landscape ? 204f : 240f;
        Table rows = new Table();
        rows.top().left();
        int from = page * PAGE_ROWS, to = Math.min(from + PAGE_ROWS, shown.size());
        for (RealmJournal.Entry e : shown.subList(from, to))
            rows.add(row(e)).left().width(width).height(22f).padBottom(1f).row();
        listPane = new ScrollPane(rows, Controls.getSkin(), "nobg");
        listPane.setScrollingDisabled(true, false);
        listPane.setFadeScrollBars(false);
        outer.add(listPane).expand().fill().left().row();
        if (pages > 1) {
            Table pager = new Table();
            pager.add(Controls.newTextButton("[%75]<", () -> turn(-1))).size(18f, 13f);
            pager.add(label("[%70]" + (page + 1) + " / " + pages)).pad(0f, 6f, 0f, 6f);
            pager.add(Controls.newTextButton("[%75]>", () -> turn(1))).size(18f, 13f);
            outer.add(pager).left().padTop(1f);
        }
        return outer;
    }

    private void turn(int by) {
        page += by;
        topOfList();
        rebuild();
    }

    /** A Button with no art, so a row is a tap target (and the agent's ui list names it). */
    private Button row(RealmJournal.Entry e) {
        Button.ButtonStyle style = new Button.ButtonStyle();
        if (e.name.equals(chosen)) // a Button draws its style's background, never setBackground's
            style.up = tint(new Color(0.95f, 0.89f, 0.74f, 1f));
        Button row = new Button(style);
        row.setName(e.name);
        row.left();
        boolean landscape = Forge.isLandscapeMode();
        Actor face = avatar(e.catalog);
        if (e.earlierRun)
            face.setColor(0.55f, 0.5f, 0.45f, 0.45f);
        row.add(face).size(18f).padRight(3f);
        Table text = new Table();
        text.left();
        text.add(label("[%80]" + (e.earlierRun ? DIM : "") + e.catalog.getDisplayName())).left().row();
        text.add(e.earlierRun ? label("[%60]" + DIM + "Met in an earlier run") : facts(e)).left();
        row.add(text).left().expandX().fillX();
        if (!e.earlierRun) {
            Actor coin = coin(e);
            if (coin != null)
                row.add(coin).size(10f).padRight(2f);
            row.add(label("[%70]" + e.wins + " - " + e.losses)).right().padRight(landscape ? 6f : 8f);
        }
        row.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                chosen = e.name;
                detailOpen = !landscape;
                rebuild();
            }
        });
        return row;
    }

    private Actor coin(RealmJournal.Entry e) {
        if (!Current.player().owesCoinRansom(e.name))
            return null;
        ItemData item = ItemListData.getItem(AdventurePlayer.BRONZE_COIN_ITEM);
        Sprite sprite = item == null ? null : item.sprite();
        return sprite == null ? null : new Image(sprite);
    }

    /** The ranks battled in their colors, then the colors known. */
    private Table facts(RealmJournal.Entry e) {
        StringBuilder words = new StringBuilder();
        boolean colors = false;
        for (EnemyData r : e.ranks) {
            int known = RealmJournal.knowledge(r);
            colors |= known > 0;
            if (known == 2) {
                int rankIndex = Math.max(0, Math.min(3, EnemyData.tierRank(r.tier)));
                words.append(RANK_COLOR[rankIndex]).append(RANKS[rankIndex]).append(" ");
            }
        }
        Table line = new Table();
        line.left();
        if (words.length() > 0)
            line.add(label("[%60]" + words.toString().trim())).left().padRight(2f);
        if (colors)
            line.add(pips(e.catalog)).left();
        return line;
    }

    private Table pips(EnemyData d) {
        Table pips = new Table();
        String letters = d.colors == null ? "" : d.colors.toUpperCase(Locale.ROOT).replaceAll("[^WUBRGC]", "");
        TextureAtlas atlas = Config.instance().getAtlas(Paths.PIXELMANA_ATLAS);
        for (int i = 0; i < letters.length(); i++) {
            TextureAtlas.AtlasRegion pip = atlas.findRegion("pm" + letters.charAt(i));
            if (pip != null)
                pips.add(new Image(pip)).size(7f).padRight(1f);
        }
        return pips;
    }

    private Actor avatar(EnemyData d) {
        TextureRegion face = EnemyAvatars.region(d);
        if (face == null)
            return new Table(); // no atlas load for one face: an empty cell, and the bake tool's audit lists it
        Image image = new Image(face);
        image.setScaling(Scaling.fit);
        return image;
    }

    private Table detail(RealmJournal.Entry e, float width) {
        Table page = new Table();
        page.top().left();
        Table head = new Table();
        head.left();
        Actor face = avatar(e.catalog);
        if (e.earlierRun)
            face.setColor(0.55f, 0.5f, 0.45f, 0.45f);
        head.add(face).size(44f).padRight(5f);
        Table who = new Table();
        who.left();
        who.add(wrapped("[%95]" + e.catalog.getDisplayName())).left().width(width - 52f).row();
        if (e.earlierRun) {
            who.add(label("[%70]" + DIM + "Met in an earlier run")).left().row();
        } else {
            who.add(label("[%75]Won " + e.wins + ", lost " + e.losses)).left().row();
            Set<String> lands = RealmJournal.lands(e.name);
            if (!lands.isEmpty())
                who.add(wrapped("[%65]" + DIM + "Roams " + String.join(", ", lands) + " land")).left().width(width - 52f).row();
        }
        head.add(who).left().expandX().fillX();
        page.add(head).left().width(width).row();
        if (e.earlierRun) {
            page.add(wrapped("[%70]" + DIM + "A new run forgets it. Meet it again to learn what it plays.")).left().width(width).padTop(8f).row();
            return page;
        }
        page.add(label("[%80]What you know")).left().padTop(6f).padBottom(2f).row();
        for (EnemyData r : e.ranks)
            rankLines(page, e, r, width);
        page.add(wrapped("[%60]" + DIM + "Battle a rank to learn its life and deck. Life is before land, night and Notoriety."))
                .left().width(width).padTop(5f).row();
        ScrollPane pane = new ScrollPane(page, Controls.getSkin(), "nobg");
        pane.setScrollingDisabled(true, false);
        Table holder = new Table();
        holder.top().left();
        holder.add(pane).expand().fill().top().left();
        return holder;
    }

    private void rankLines(Table page, RealmJournal.Entry e, EnemyData r, float width) {
        int known = RealmJournal.knowledge(r);
        int rankIndex = Math.max(0, Math.min(3, EnemyData.tierRank(r.tier)));
        Table line = new Table();
        line.left();
        line.add(label("[%75]" + RANK_COLOR[rankIndex] + RANKS[rankIndex])).left().width(52f);
        if (known > 0)
            line.add(pips(r)).left().width(36f);
        else
            line.add(label("[%75]" + DIM + "?")).left().width(36f);
        int life = Math.round(r.life * Current.player().getDifficulty().enemyLifeFactor);
        line.add(label("[%75]" + (known == 2 ? "[WHITE][+Life]" + INK + " " + life : DIM + "?"))).left().width(34f);
        String note = null;
        if (known == 2) {
            if (r.copyPlayerDeck) {
                note = "Copies your deck";
            } else if (RealmJournal.fixedDeck(r) || Current.player().getStatistic().foughtDeck(r.getName(), r.tier) != null) {
                line.add(Controls.newTextButton("[%70]Deck", () -> openDeck(r))).left().size(30f, 14f);
            } else {
                note = "Fight it again to record its deck";
            }
        }
        page.add(line).left().padBottom(1f).row();
        if (note != null)
            page.add(label("[%60]" + DIM + note)).left().padLeft(4f).padBottom(1f).row();
    }

    private void openDeck(EnemyData r) {
        Deck deck = RealmJournal.deckToShow(r);
        if (deck != null)
            Forge.switchScene(DeckPreviewScene.getInstance(deck, RealmJournal.caption(r)));
    }
}
