package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.util.EconomyBuildings;
import forge.adventure.util.EditionProgression;
import forge.adventure.util.PrintingIndex;
import forge.adventure.util.ResearchMeter;
import forge.adventure.util.RewardActor;
import forge.card.CardDb;
import forge.card.CardEdition;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Progressive Set Unlocks (MOD_SCOPE.md #4) - the Research Lab's screen. Modeled structurally on
 * QuestLogScene (a Window + scrollable Table of rows, one action button per row) rather than
 * SpellSmithScene's fuller layout - the Lab genuinely only needs a scrollable list, not a shop-
 * style purchase flow, so the simpler existing pattern was the better fit once actually compared.
 * <p>
 * Round 751 (research v2, util/ResearchMeter): every set can be researched from day 1. Each line shows the set's meter
 * and what starting it costs now in shards and days; tapping the line opens the set's window - its meter, the spare
 * cards that could be sacrificed (double points, no gold), and the round-452 card view. Only a game started on round 751
 * or later (AdventurePlayer.isResearchV2); an older save keeps the classic list (buildListClassic), its 10% gate and all.
 * <p>
 * Round 764 (the user chose to "show days instead of a meter"): a v2 line and window show days, shards and how many new
 * commons take the next day off (ResearchMeter.nextDayLine); a set at 0 days reads Ready and offers no Sacrifice; a
 * running research can still be fed, and finishes the moment it reaches 0 days.
 * <p>
 * Round 766 (the user: "We need to add a button, Found cards. This will open a list of all cards of that set you found.
 * You should from there easily choose what you want to sacrifice or sell."): a v2 row with a card of its set owned has
 * Found Cards (openFoundCards) - each owned card of the set with its copies, spares, sell price and the days one copy
 * takes off, and its own Sell and Sacrifice.
 * <p>
 * Round 817: a set box on the list ("All Sets" or one set, applySetFilter), and Found Cards gets a set box with All Sets
 * plus the deck builder's color/type icons, rarity buttons and group-by (ResearchCardFilters).
 */
public class ResearchScene extends UIScene {
    private static ResearchScene object;

    public static ResearchScene instance() {
        if (object == null)
            object = new ResearchScene();
        return object;
    }

    // Fraction of an edition's own real card count, floor 5 - user's own refined spec (2026-08-12,
    // "10% of an expansion vs. 10 cards... standard across the different expansions and card
    // counts"). The floor keeps a tiny supplemental set from becoming a 1-2 card unlock. The
    // fraction itself moved to TuningData (2026-08-22 user request) - see
    // TuningData.researchThresholdFraction for the tunable default/rationale; THRESHOLD_MIN stays
    // a fixed constant here, not asked to be tunable.
    // Round 751: only the stock path's threshold popup still reads these (AdventurePlayer.maybeNotifyResearchThreshold,
    // skipped while research v2 is on).
    private static final int THRESHOLD_MIN = 5;

    private final Table scrollContainer;
    private final Window scrollWindow;
    private final Table root;
    private final CheckBox hideUnfoundCheckBox;
    // Round 313 (user: "add a button to hide the second group. The sets you found cards for, but not enough to
    // research"): "Hide partial", beside "Hide unfound". Round 751: the sets no card was given to ("Hide untouched" since round 764).
    private final CheckBox hidePartialCheckBox;
    // Not final - its own click handler below needs to reference it (to update its label text)
    // from inside the same lambda passed to its own constructor call.
    private TextraButton showResearchedButton;
    // Not persisted - deliberately resets to "hidden" each time the screen opens, same as the
    // hide-unfound checkbox always starting checked (see its own setChecked(true) below).
    private boolean showResearched = false;
    private String lastSaveKey; // round 764 follow-up: the game the filters were set in
    private final ScrollPane scroller;
    // Round 817 (the user: "On the main page, let's add a Set filter"): "All Sets" or one set; the box offers the sets the
    // list would show with the other filters, and a picked set narrows the list to it. Kept for the same game, like the
    // check boxes; cleared by Show/Hide Researched (the other view has other sets).
    private final com.badlogic.gdx.scenes.scene2d.ui.SelectBox<String> listSetBox;
    private final List<String> listSetCodes = new ArrayList<>();
    private String listSet;
    private boolean fillingSetBox;
    // Round 452: set while a set's card view is open, so the way back keeps the view, the filters and the place in the
    // list (enter() otherwise starts a fresh visit).
    private boolean returningFromSetView = false;
    private float scrollYBeforeSetView = 0f;

    private ResearchScene() {
        super(Forge.isLandscapeMode() ? "ui/research.json" : "ui/research_portrait.json");
        scrollWindow = ui.findActor("scrollWindow");
        root = ui.findActor("researchList");
        ui.onButtonPress("return", this::back);

        hideUnfoundCheckBox = Controls.newCheckBox("Hide unfound");
        hideUnfoundCheckBox.setChecked(true);
        hideUnfoundCheckBox.getLabel().setColor(Color.BLACK);
        hideUnfoundCheckBox.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                buildList();
            }
        });
        hidePartialCheckBox = Controls.newCheckBox("Hide partial"); // round 764: "Hide untouched" in a v2 game (enter())
        hidePartialCheckBox.setChecked(false);
        hidePartialCheckBox.getLabel().setColor(Color.BLACK);
        hidePartialCheckBox.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                buildList();
            }
        });
        showResearchedButton = Controls.newTextButton("Show Researched", () -> {
            showResearched = !showResearched;
            listSet = null; // round 817
            showResearchedButton.setText(showResearched ? "Hide Researched" : "Show Researched");
            buildList();
        });
        // Round 313: the two filters share the left cell - side by side, or stacked in the narrow portrait pane.
        @SuppressWarnings("unchecked")
        com.badlogic.gdx.scenes.scene2d.ui.SelectBox<String> setBox = Controls.newComboBox();
        listSetBox = setBox;
        listSetBox.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (fillingSetBox)
                    return;
                int i = listSetBox.getSelectedIndex();
                listSet = i <= 0 || i > listSetCodes.size() ? null : listSetCodes.get(i - 1);
                buildList();
            }
        });
        Table filters = new Table();
        filters.add(hideUnfoundCheckBox).align(Align.left).padRight(Forge.isLandscapeMode() ? 8 : 12);
        if (!Forge.isLandscapeMode())
            filters.row();
        filters.add(hidePartialCheckBox).align(Align.left);
        // Round 817: the set box after the check boxes - beside them in landscape, under them in portrait.
        if (Forge.isLandscapeMode()) {
            filters.add(listSetBox).width(118f).padLeft(8);
        } else {
            filters.row();
            filters.add(listSetBox).width(150f).align(Align.left).padTop(2);
        }
        root.add(filters).align(Align.left);
        root.add(showResearchedButton).align(Align.right);
        root.row().padTop(4);

        scrollContainer = new Table(Controls.getSkin());
        scrollContainer.top().left(); // round 100: never center a too-wide row (Android portrait cut the left edge)
        scrollContainer.row();
        scroller = new ScrollPane(scrollContainer);
        // Vertical only - matches QuestLogScene's detailScroller.setScrollingDisabled(true, false).
        scroller.setScrollingDisabled(true, false);
        root.add(scroller).colspan(2).expand().fill();
        // UIScene's constructor only auto-assigns stage scroll focus to ScrollPanes declared
        // directly in the JSON layout (research.json has none - this one's built in code, same as
        // QuestLogScene's). Without an explicit focus, mouse-wheel scroll events never reach this
        // pane at all (real bug, user-reported 2026-08-12: "I see about 7 expansions on screen,
        // can't scroll at all").
        stage.setScrollFocus(scroller);
    }

    @Override
    public void dispose() { }

    @Override
    public void enter() {
        super.enter();
        AdventurePlayer player = AdventurePlayer.current();
        // Round 751: one checkbox, the system's own filter - in a v2 game the sets no card was given to (round 764
        // follow-up: "Hide untouched", was "Hide unfed" - meter language).
        hidePartialCheckBox.getLabel().setText(ResearchMeter.enabled(player) ? "Hide untouched" : "Hide partial");
        // Round 764 follow-up: the scene is a singleton, so its filters outlived the game they were set in - a new game
        // could open on an empty list with "Hide untouched" still ticked. Another save (a new game, a load) starts
        // them fresh; the same game keeps them.
        String saveKey = Current.world().getSeed() + "|" + player.getName();
        if (!saveKey.equals(lastSaveKey)) {
            lastSaveKey = saveKey;
            showResearched = false;
            listSet = null; // round 817
            foundFilters.reset();
            showResearchedButton.setText("Show Researched");
            hideUnfoundCheckBox.setProgrammaticChangeEvents(false);
            hidePartialCheckBox.setProgrammaticChangeEvents(false);
            hideUnfoundCheckBox.setChecked(true);
            hidePartialCheckBox.setChecked(false);
            hideUnfoundCheckBox.setProgrammaticChangeEvents(true);
            hidePartialCheckBox.setProgrammaticChangeEvents(true);
        }
        // Lazy completion check (also runs from the daily tick - see EconomyBuildings.
        // processDaysPassed() - but re-checking here too means a research that finished while the
        // player was elsewhere in-game still shows as complete the instant they open this screen).
        player.checkResearchCompletion(Current.world().getCurrentDay());
        if (returningFromSetView) { // round 452: back from a set's cards - the same view, at the same place in the list
            returningFromSetView = false;
            buildList();
            scroller.layout();
            scroller.setScrollY(scrollYBeforeSetView);
            scroller.updateVisualScroll();
            return;
        }
        // The scene is a singleton, so the toggle survives between visits unless reset here -
        // every open starts on the normal (unresearched) view with the button label matching.
        showResearched = false;
        showResearchedButton.setText("Show Researched");
        buildList();
    }

    @Override
    public boolean back() {
        // switchToLast() returns to the town/Capitol map the player was standing in, matching
        // every other in-town building scene (SpellSmithScene.done(), ShardTraderScene). The old
        // switchScene(GameScene.instance()) jumped straight to the OVERWORLD, silently ejecting
        // the player from the Capitol on lab exit (real bug, user-reported 2026-08-12).
        Forge.switchToLast();
        return true;
    }

    /** ceil(total * TuningData.researchThresholdFraction), floor THRESHOLD_MIN. */
    private static int thresholdFor(int totalCardsInEdition) {
        float fraction = Config.instance().getTuningData().researchThresholdFraction;
        return Math.max(THRESHOLD_MIN, (int) Math.ceil(totalCardsInEdition * fraction));
    }

    // Session-lazy total-cards-per-edition cache backing thresholdForEditionCode() below - the
    // threshold-crossed popup (AdventurePlayer.maybeNotifyResearchThreshold()) runs on every
    // single card pickup, and re-deriving totals from RewardData.getAllCards() (tens of
    // thousands of cards) per pickup would be silly. buildList() above deliberately does NOT use
    // this cache - the on-screen list stays freshly derived every open, exactly as before.
    // Round 371 (the user: "How can I have discovered 181 cards ... but the entire set only has 94 cards"): the found
    // count summed COPIES of every card printed in the set - basic lands included (a collection holds dozens of XLN
    // basics) - while the total counted the set's cards in the legal pool. Both now count the same thing: DISTINCT card
    // names of the set that the legal pool holds. Cached per session, dropped when the pool is rebuilt.
    private static Map<String, java.util.Set<String>> cachedLegalNamesByEdition;

    /** Drops the totals cache - called by RewardData.invalidateCardPool() whenever the legal
     *  card pool itself is rebuilt, so the popup's thresholds always track the live pool. */
    public static void invalidateThresholdCache() {
        cachedLegalNamesByEdition = null;
    }

    /** Round 371: each edition's distinct card names. Round 372 (the user: "the entire Print count (minus lands) needs
     *  to be part of each set"): every card PRINTED in the set that the pool can hand out, basic lands excluded
     *  (PrintingIndex) - was only the cards whose one pool printing was this set (Amonkhet: 29 of 254). */
    private static Map<String, java.util.Set<String>> legalNamesByEdition() {
        return forge.adventure.util.PrintingIndex.byEdition();
    }

    /** Round 371: the edition's card count for research - distinct names in the legal pool. */
    public static int researchTotal(String editionCode) {
        java.util.Set<String> names = legalNamesByEdition().get(editionCode);
        return names == null ? 0 : names.size();
    }

    /** Round 371: per edition, how many of its legal-pool cards the collection holds at least one of (by name, in that
     *  edition's printing) - the "found" half of the research line and of the threshold popup. */
    public static Map<String, Integer> discoveredByEdition(Iterable<Map.Entry<PaperCard, Integer>> cards) {
        Map<String, java.util.Set<String>> legal = legalNamesByEdition();
        Map<String, java.util.Set<String>> found = new HashMap<>();
        for (Map.Entry<PaperCard, Integer> entry : cards) {
            PaperCard pc = entry.getKey();
            if (pc == null || entry.getValue() == null || entry.getValue() <= 0)
                continue;
            java.util.Set<String> pool = legal.get(pc.getEdition());
            if (pool != null && pool.contains(pc.getName()))
                found.computeIfAbsent(pc.getEdition(), k -> new java.util.HashSet<>()).add(pc.getName());
        }
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, java.util.Set<String>> e : found.entrySet())
            out.put(e.getKey(), e.getValue().size());
        return out;
    }

    /** The research threshold for one edition, same formula/counting the on-screen list uses
     *  (thresholdFor() over the live legal card pool). Integer.MAX_VALUE for an edition with no
     *  cards in the pool at all - callers treat that as "can never be researched, never notify". */
    public static int thresholdForEditionCode(String editionCode) {
        int total = researchTotal(editionCode);
        return total <= 0 ? Integer.MAX_VALUE : thresholdFor(total);
    }

    /** Round 751: a game started on research v2 gets the meter list; an older save keeps the classic one unchanged. */
    private void buildList() {
        if (ResearchMeter.enabled(AdventurePlayer.current()))
            buildListV2();
        else
            buildListClassic();
    }

    /** The research list before round 751 - the 10% gate (kept for saves from before research v2). */
    private void buildListClassic() {
        // Selectables must be rebuilt alongside the rows (2026-08-12 review finding): without
        // this, every rebuild - and buildList now runs from enter(), both filter toggles, and
        // each purchase - LEAKED the previous rows' buttons into the singleton's selectable
        // list. Detached actors still report isVisible()==true, so controller/keyboard
        // navigation could focus an undrawn orphan and fire its stale purchase lambda
        // (takeGold + startResearch with a stale captured day). research.json declares no
        // selectable elements, so clearing here drops nothing but our own rows.
        clearSelectable();
        addToSelectable(hideUnfoundCheckBox);
        addToSelectable(hidePartialCheckBox); // round 313
        addToSelectable(showResearchedButton);
        addToSelectable(listSetBox); // round 817
        scrollContainer.clear();
        AdventurePlayer player = AdventurePlayer.current();
        int currentDay = Current.world().getCurrentDay();
        java.util.Map<String, Integer> inProgressAll = player.getResearchInProgress();
        boolean anyInProgress = !inProgressAll.isEmpty();

        // Round 313: fewest days left first, ties alphabetical - the same order the list below opens with.
        List<String> running = new ArrayList<>(inProgressAll.keySet());
        running.sort((a, b) -> {
            int byDays = Integer.compare(player.getResearchDaysLeft(a, currentDay), player.getResearchDaysLeft(b, currentDay));
            return byDays != 0 ? byDays : editionDisplayName(a).compareToIgnoreCase(editionDisplayName(b));
        });
        if (listSet != null) // round 817: a picked set - its own header only
            running.removeIf(c -> !c.equals(listSet));
        for (String inProgress : running) {
            int daysLeft = player.getResearchDaysLeft(inProgress, currentDay);
            TypingLabel header = Controls.newTypingLabel("Researching: " + editionDisplayName(inProgress)
                    + " - " + daysLeft + (daysLeft == 1 ? " day" : " days") + " remaining");
            header.skipToTheEnd();
            header.setWrap(true);
            header.setColor(Color.BLACK);
            scrollContainer.add(header).colspan(2).align(Align.left).expandX();
            scrollContainer.row().padTop(8);
        }

        // Live-derived owned-card count per edition - no separate persisted counter, recomputed
        // fresh every time this screen opens so it can never drift from the player's real
        // collection.
        // Round 371: distinct legal-pool cards found, against the set's distinct legal-pool cards (was copies of every
        // printing, basics included, against the pool's printings).
        Map<String, Integer> ownedByEdition = discoveredByEdition(player.getCards());
        Map<String, Integer> totalByEdition = new HashMap<>();
        for (Map.Entry<String, java.util.Set<String>> e : legalNamesByEdition().entrySet())
            totalByEdition.put(e.getKey(), e.getValue().size());

        boolean hideUnfound = hideUnfoundCheckBox.isChecked();
        boolean hidePartial = hidePartialCheckBox.isChecked(); // round 313
        List<CardEdition> candidates = new ArrayList<>();
        if (showResearched) {
            // Researched-only view (user spec 2026-08-12: the toggle "should ONLY show those").
            // Sourced from unlockedEditions DIRECTLY, not by filtering the master list - the
            // starter editions (JMP/J22/J25 family) have no booster template, so they are absent
            // from getMasterEditionList() and a master-list filter would silently never show
            // them, which is exactly why the first version of this toggle looked like it did
            // nothing on a fresh save (the only unlocked editions were all starters).
            for (String code : player.getUnlockedEditions()) {
                CardEdition ed = FModel.getMagicDb().getEditions().get(code);
                if (ed != null)
                    candidates.add(ed);
            }
        } else {
            for (CardEdition ed : EditionProgression.getMasterEditionList()) {
                if (player.hasUnlockedEdition(ed.getCode()))
                    continue; // researched already - lives in the toggle's own view now
                if (hideUnfound && ownedByEdition.getOrDefault(ed.getCode(), 0) <= 0)
                    continue; // not discovered yet, and the hide-unfound checkbox is on
                if (hidePartial && groupOf(ed.getCode(), ownedByEdition, totalByEdition, player) == 2)
                    continue; // round 313: some found, not enough to research, and hide-partial is on
                candidates.add(ed);
            }
        }
        candidates = applySetFilter(candidates); // round 817
        // Round 313 (user: "Sort as, Currently Researching (Lest days remaining on top, if tied, alphabetically.) Then
        // Sets you can research, alphabetically. Then sets you have found cards for, alphabetically. Then the sets you
        // have not found cards for, alphabetically."), replacing round 2026-08-12's cards-owned order. The researched
        // view is alphabetical.
        final boolean researchedView = showResearched;
        candidates.sort((a, b) -> {
            if (!researchedView) {
                int ga = groupOf(a.getCode(), ownedByEdition, totalByEdition, player);
                int gb = groupOf(b.getCode(), ownedByEdition, totalByEdition, player);
                if (ga != gb)
                    return Integer.compare(ga, gb);
                if (ga == 0) {
                    int byDays = Integer.compare(player.getResearchDaysLeft(a.getCode(), currentDay),
                            player.getResearchDaysLeft(b.getCode(), currentDay));
                    if (byDays != 0)
                        return byDays;
                }
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });

        // Round 551: the Researcher perk's discount on top of the difficulty's scaling.
        int cost = Math.round(EconomyBuildings.scaledCost(AdventurePlayer.researchShardCost())
                * forge.adventure.util.Ascendance.researchCostFactor());
        for (CardEdition ed : candidates) {
            String code = ed.getCode();
            int owned = ownedByEdition.getOrDefault(code, 0);
            int total = totalByEdition.getOrDefault(code, 0);
            int threshold = thresholdFor(total);
            boolean researched = player.hasUnlockedEdition(code);
            boolean eligible = owned >= threshold;
            boolean canAfford = player.getShards() >= cost;

            // "Name (CODE) (owned/needed) - N cards" (user spec 2026-08-12: show the expansion's
            // total card count alongside what's needed to start researching; the "(CODE)" suffix
            // added 2026-08-15 to match SpellSmith's own edition-name format, which gets it for
            // free from CardEdition's own toString() - this list builds its own label text
            // instead, so the code has to be added explicitly here).
            // Round 452: a set you can research or have researched carries a magnifier - tap its line to see its cards.
            boolean viewable = researched || eligible;
            String line = ed.getName() + " (" + ed.getCode() + ") (" + owned + "/" + threshold + ") - "
                    + String.format("%,d", total) + " cards";
            Color lineColor = researched ? Color.DARK_GRAY : (eligible ? Color.BLACK : Color.GRAY);
            // The label's tint would darken the glyph too (a black silhouette), so a viewable line is colored by markup
            // and the magnifier drawn untinted.
            TypingLabel nameLabel = Controls.newTypingLabel(viewable
                    ? "[#" + lineColor + "]" + line + "[#ffffffff] [+Magnifier]" : line);
            nameLabel.skipToTheEnd();
            nameLabel.setWrap(true);
            nameLabel.setColor(viewable ? Color.WHITE : lineColor);
            if (viewable)
                nameLabel.addListener(new ClickListener() {
                    @Override
                    public void clicked(InputEvent event, float x, float y) {
                        openSetView(code);
                    }
                });
            boolean portrait = !Forge.isLandscapeMode(); // round 100: 258px pane in portrait - set line on its own row, button below
            scrollContainer.add(nameLabel).align(Align.left).width(portrait ? 236f : 250f).colspan(portrait ? 2 : 1);
            if (portrait)
                scrollContainer.row();

            if (researched) {
                TypingLabel doneLabel = Controls.newTypingLabel("Researched");
                doneLabel.skipToTheEnd();
                doneLabel.setColor(Color.DARK_GRAY);
                scrollContainer.add(doneLabel).align(Align.left).expandX().padLeft(6);
            } else {
                // Resource glyph, not a letter suffix - standing standard for cost UI
                // (user spec 2026-08-12; cost switched gold -> shards same day, user table).
                TextraButton researchButton = Controls.newTextButton(player.isResearching(code)
                        ? "Researching (" + player.getResearchDaysLeft(code, currentDay) + "d)"
                        : "Research (" + cost + "[+Shards])", () -> {
                    player.takeShards(cost);
                    player.startResearch(code, currentDay);
                    buildList();
                });
                // Several editions may be researched at once (user spec 2026-09-03); only the one
                // already running is locked out.
                researchButton.setDisabled(!eligible || player.isResearching(code) || !canAfford);
                scrollContainer.add(researchButton).align(Align.left).expandX().padLeft(6);
                addToSelectable(researchButton);
            }
            scrollContainer.row().padTop(5);
        }

        if (candidates.isEmpty() && !anyInProgress) {
            TypingLabel empty = Controls.newTypingLabel(
                    "Explore the world and defeat monsters to discover cards from new expansions - "
                    + "they'll show up here once you've found at least one.");
            empty.skipToTheEnd();
            empty.setWrap(true);
            empty.setColor(Color.DARK_GRAY);
            scrollContainer.add(empty).colspan(2).align(Align.left).expandX().width(Forge.isLandscapeMode() ? 340 : 236);
        }
    }

    /** Round 313: an unresearched edition's group - 0 researching now, 1 ready to research, 2 some cards found but
     *  not enough (partial), 3 none found (unfound). */
    private static int groupOf(String code, Map<String, Integer> ownedByEdition, Map<String, Integer> totalByEdition,
                               AdventurePlayer player) {
        if (player.isResearching(code))
            return 0;
        int owned = ownedByEdition.getOrDefault(code, 0);
        if (owned <= 0)
            return 3;
        return owned >= thresholdFor(totalByEdition.getOrDefault(code, 0)) ? 1 : 2;
    }

    /** Round 817: fills the set box with the sets the list would show (by name; a picked set stays in it) and narrows
     *  the list to the picked set. */
    private List<CardEdition> applySetFilter(List<CardEdition> candidates) {
        List<CardEdition> byName = new ArrayList<>(candidates);
        byName.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        listSetCodes.clear();
        for (CardEdition ed : byName)
            listSetCodes.add(ed.getCode());
        if (listSet != null && !listSetCodes.contains(listSet))
            listSetCodes.add(0, listSet);
        String[] items = new String[listSetCodes.size() + 1];
        items[0] = "All Sets";
        for (int i = 0; i < listSetCodes.size(); i++)
            items[i + 1] = editionDisplayName(listSetCodes.get(i));
        fillingSetBox = true; // this SelectBox fires on setItems/setSelectedIndex
        try {
            listSetBox.setItems(items);
            listSetBox.setSelectedIndex(listSet == null ? 0 : listSetCodes.indexOf(listSet) + 1);
        } finally {
            fillingSetBox = false;
        }
        if (listSet == null)
            return candidates;
        List<CardEdition> out = new ArrayList<>();
        for (CardEdition ed : candidates)
            if (ed.getCode().equals(listSet))
                out.add(ed);
        return out;
    }

    /** Round 751: research v2's list - every set, its meter, and what starting it costs now in shards and days. */
    private void buildListV2() {
        // Selectables must be rebuilt alongside the rows (2026-08-12 review finding): without
        // this, every rebuild - and buildList now runs from enter(), both filter toggles, and
        // each purchase - LEAKED the previous rows' buttons into the singleton's selectable
        // list. Detached actors still report isVisible()==true, so controller/keyboard
        // navigation could focus an undrawn orphan and fire its stale purchase lambda
        // (takeGold + startResearch with a stale captured day). research.json declares no
        // selectable elements, so clearing here drops nothing but our own rows.
        clearSelectable();
        addToSelectable(hideUnfoundCheckBox);
        addToSelectable(hidePartialCheckBox); // round 313
        addToSelectable(showResearchedButton);
        addToSelectable(listSetBox); // round 817
        scrollContainer.clear();
        AdventurePlayer player = AdventurePlayer.current();
        int currentDay = Current.world().getCurrentDay();
        java.util.Map<String, Integer> inProgressAll = player.getResearchInProgress();
        boolean anyInProgress = !inProgressAll.isEmpty();

        // Round 313: fewest days left first, ties alphabetical - the same order the list below opens with.
        List<String> running = new ArrayList<>(inProgressAll.keySet());
        running.sort((a, b) -> {
            int byDays = Integer.compare(player.getResearchDaysLeft(a, currentDay), player.getResearchDaysLeft(b, currentDay));
            return byDays != 0 ? byDays : editionDisplayName(a).compareToIgnoreCase(editionDisplayName(b));
        });
        if (listSet != null) // round 817: a picked set - its own header only
            running.removeIf(c -> !c.equals(listSet));
        for (String inProgress : running) {
            int daysLeft = player.getResearchDaysLeft(inProgress, currentDay);
            TypingLabel header = Controls.newTypingLabel("Researching: " + editionDisplayName(inProgress)
                    + " - " + daysLeft + (daysLeft == 1 ? " day" : " days") + " remaining");
            header.skipToTheEnd();
            header.setWrap(true);
            header.setColor(Color.BLACK);
            // Round 764 follow-up: a width, so a long set name wraps instead of running off the pane.
            scrollContainer.add(header).colspan(3).align(Align.left).expandX().width(rowWidth());
            scrollContainer.row().padTop(8);
        }

        // Live-derived owned-card count per edition - no separate persisted counter, recomputed
        // fresh every time this screen opens so it can never drift from the player's real
        // collection. Round 751: "unfound" = no card of the set owned and nothing fed.
        Map<String, Integer> ownedByEdition = discoveredByEdition(player.getCards());
        Map<String, Integer> copiesByEdition = ownedCopiesByEdition(player); // round 766: who gets Found Cards
        Map<String, Integer> foundByEdition = foundCardsByEdition(player); // round 817: the button's (N)

        boolean hideUnfound = hideUnfoundCheckBox.isChecked();
        boolean hideUnfed = hidePartialCheckBox.isChecked(); // round 764: "Hide untouched" in a v2 game
        List<CardEdition> candidates = new ArrayList<>();
        if (showResearched) {
            // Researched-only view (user spec 2026-08-12: the toggle "should ONLY show those").
            // Sourced from unlockedEditions DIRECTLY, not by filtering the master list - the
            // starter editions (JMP/J22/J25 family) have no booster template, so they are absent
            // from getMasterEditionList() and a master-list filter would silently never show
            // them, which is exactly why the first version of this toggle looked like it did
            // nothing on a fresh save (the only unlocked editions were all starters).
            for (String code : player.getUnlockedEditions()) {
                CardEdition ed = FModel.getMagicDb().getEditions().get(code);
                if (ed != null)
                    candidates.add(ed);
            }
        } else {
            for (CardEdition ed : EditionProgression.getMasterEditionList()) {
                String code = ed.getCode();
                if (player.hasUnlockedEdition(code))
                    continue; // researched already - lives in the toggle's own view now
                boolean fed = ResearchMeter.points(player, code) > 0f;
                if (hideUnfound && !fed && !player.isResearching(code) && ownedByEdition.getOrDefault(code, 0) <= 0)
                    continue; // nothing found or fed yet, and the hide-unfound checkbox is on
                if (hideUnfed && !fed && !player.isResearching(code))
                    continue; // round 751: an empty meter, and hide-unfed is on
                candidates.add(ed);
            }
        }
        candidates = applySetFilter(candidates); // round 817
        // Round 751: researching first (fewest days left), then the fullest meters, then A-Z. The researched view is
        // alphabetical.
        final boolean researchedView = showResearched;
        candidates.sort((a, b) -> {
            if (!researchedView) {
                boolean ra = player.isResearching(a.getCode()), rb = player.isResearching(b.getCode());
                if (ra != rb)
                    return ra ? -1 : 1;
                if (ra) {
                    int byDays = Integer.compare(player.getResearchDaysLeft(a.getCode(), currentDay),
                            player.getResearchDaysLeft(b.getCode(), currentDay));
                    if (byDays != 0)
                        return byDays;
                } else {
                    // Round 764: fewest days first (Ready on top), then the most fed.
                    int byDays = Integer.compare(ResearchMeter.daysIfStarted(player, a.getCode()),
                            ResearchMeter.daysIfStarted(player, b.getCode()));
                    if (byDays != 0)
                        return byDays;
                    int byFill = Float.compare(ResearchMeter.fill(player, b.getCode()), ResearchMeter.fill(player, a.getCode()));
                    if (byFill != 0)
                        return byFill;
                }
            }
            return a.getName().compareToIgnoreCase(b.getName());
        });

        for (CardEdition ed : candidates) {
            String code = ed.getCode();
            int total = researchTotal(code);
            boolean researched = player.hasUnlockedEdition(code);

            // Round 764 follow-up (the user's shot: a long name ran into the buttons and "0/27" fell to a second line):
            // line 1 is "Name (CODE) - N cards" and the magnifier in a fixed-width column beside the buttons; line 2,
            // under the whole row, is statusLine - days and shards, the next-day hint, or Ready. Every line opens its
            // set's window; a researched one still opens the card view (round 452's magnifier).
            String line = ed.getName() + " (" + code + ") - " + String.format("%,d", total) + " cards";
            Color lineColor = researched ? Color.DARK_GRAY : Color.BLACK;
            // The label's tint would darken the glyph too (a black silhouette), so the line is colored by markup and the
            // magnifier drawn untinted (round 452).
            TypingLabel nameLabel = Controls.newTypingLabel("[#" + lineColor + "]" + line + "[#ffffffff] [+Magnifier]");
            nameLabel.skipToTheEnd();
            nameLabel.setWrap(true);
            nameLabel.setColor(Color.WHITE);
            nameLabel.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    if (researched)
                        openSetView(code);
                    else
                        openSetDialog(code);
                }
            });
            boolean portrait = !Forge.isLandscapeMode(); // round 100: 258px pane in portrait - set line on its own row, button below
            scrollContainer.add(nameLabel).align(Align.left).width(portrait ? rowWidth() : NAME_WIDTH).colspan(portrait ? 3 : 1);
            if (portrait)
                scrollContainer.row();

            // Round 766: Found Cards between the text and the Research button, on a set with a card of it owned.
            if (!researched && copiesByEdition.getOrDefault(code, 0) > 0) {
                // Round 817 (the user: "In the "Found Cards" put how many you have found"): the cards its page lists.
                TextraButton foundButton = Controls.newTextButton("Found Cards (" + foundByEdition.getOrDefault(code, 0) + ")",
                        () -> openFoundCards(code, false));
                scrollContainer.add(foundButton).width(FOUND_WIDTH).align(Align.left).padLeft(4);
                addToSelectable(foundButton);
            } else {
                scrollContainer.add().width(FOUND_WIDTH).padLeft(4);
            }
            if (researched) {
                TypingLabel doneLabel = Controls.newTypingLabel("Researched");
                doneLabel.skipToTheEnd();
                doneLabel.setColor(Color.DARK_GRAY);
                scrollContainer.add(doneLabel).align(Align.left).expandX().padLeft(4);
            } else {
                int cost = ResearchMeter.startCost(player, code);
                // Round 764 follow-up: the price and days moved to line 2, so the button is just "Research" - no shard
                // glyph mid-label (its advance read as a stray space before the comma).
                TextraButton researchButton = Controls.newTextButton(player.isResearching(code) ? "Researching" : "Research", () -> {
                    ResearchMeter.start(player, code, Current.world().getCurrentDay());
                    buildList();
                });
                // Several editions may be researched at once (user spec 2026-09-03); only the one
                // already running is locked out.
                researchButton.setDisabled(player.isResearching(code) || player.getShards() < cost);
                scrollContainer.add(researchButton).align(Align.left).expandX().padLeft(4);
                addToSelectable(researchButton);
                scrollContainer.row();
                // Colored by markup, white around the shard glyph - a label tint would turn the glyph into a dark
                // silhouette (round 452's magnifier note).
                String gray = "[#" + Color.DARK_GRAY + "]";
                TypingLabel status = Controls.newTypingLabel(gray + statusLine(player, code, currentDay)
                        .replace("[+Shards]", "[#ffffffff][+Shards]" + gray));
                status.skipToTheEnd();
                status.setWrap(true);
                status.setColor(Color.WHITE);
                scrollContainer.add(status).colspan(3).align(Align.left).width(rowWidth()).padTop(1);
            }
            scrollContainer.row().padTop(6);
        }

        if (candidates.isEmpty() && listSet != null) { // round 817
            TypingLabel empty = Controls.newTypingLabel(editionDisplayName(listSet) + " is hidden by the boxes above.");
            empty.skipToTheEnd();
            empty.setWrap(true);
            empty.setColor(Color.DARK_GRAY);
            scrollContainer.add(empty).colspan(3).align(Align.left).expandX().width(Forge.isLandscapeMode() ? 340 : 236);
        } else if (candidates.isEmpty() && !anyInProgress) {
            TypingLabel empty = Controls.newTypingLabel(
                    "Every set can be researched here. Cards you sell anywhere, or sacrifice here, shorten their own "
                    + "set's research - untick Hide unfound to see every set.");
            empty.skipToTheEnd();
            empty.setWrap(true);
            empty.setColor(Color.DARK_GRAY);
            scrollContainer.add(empty).colspan(3).align(Align.left).expandX().width(Forge.isLandscapeMode() ? 340 : 236);
        }
    }

    // Round 764 follow-up: the v2 row's columns, landscape (portrait gives the text the whole row).
    // Round 817: Found Cards wider for its "(N)" (a TextraButton's own width did not grow with it), the name narrower.
    private static final float NAME_WIDTH = 204f, FOUND_WIDTH = 88f;

    /** The whole row's width: line 2 and the "Researching" headers. */
    private static float rowWidth() {
        return Forge.isLandscapeMode() ? 372f : 236f;
    }

    /** Round 764 follow-up: a v2 row's line 2 - "28 days - one more new common takes 5 days off. Start: 40[+Shards]",
     *  "Ready, no wait. Start: 20[+Shards]" or "Researching: 22 days left - one more new common takes 2 days off." The
     *  shard glyph ends the line: its own advance reads as a stray space before any comma or period after it. */
    private static String statusLine(AdventurePlayer player, String code, int currentDay) {
        int days = ResearchMeter.days(player, code, currentDay);
        String next = ResearchMeter.nextDayLine(player, code, currentDay);
        String hint = next.isEmpty() ? "" : " - " + Character.toLowerCase(next.charAt(0)) + next.substring(1);
        if (player.isResearching(code))
            return "Researching: " + ResearchMeter.daysText(days) + " left" + hint + ".";
        if (forge.adventure.util.RealmMilestones.royalGrantReady()) // round 774: a realm boon pays the next start
            return "Royal Grant: free, done at once.";
        String start = "Start: " + ResearchMeter.startCost(player, code) + "[+Shards]";
        if (days == 0)
            return "Ready, no wait. " + start;
        return ResearchMeter.daysText(days) + hint + ". " + start;
    }

    /**
     * Round 751: a set's window - what starting it costs now, and its spare cards. Sacrifice (double, no gold) is the
     * Lab's own: "Sacrifice extras" keeps one copy of each card, "Sacrifice all spare" keeps only what a deck uses. Both
     * skip favorites. "View cards" is round 452's set view.
     * <p>
     * Round 764: days, never the meter - "Research now: 45[+Shards], 14 days", the next-day line, and what each Sacrifice
     * would make the days. A Ready set (0 days) offers no Sacrifice; a running research says the start's shards stay spent.
     */
    private void openSetDialog(String code) {
        AdventurePlayer player = AdventurePlayer.current();
        int day = Current.world().getCurrentDay();
        boolean running = player.isResearching(code);
        int days = ResearchMeter.days(player, code, day);
        StringBuilder body = new StringBuilder();
        body.append(String.format("%,d", researchTotal(code))).append(" cards.\n");
        if (running) {
            body.append("Researching: ").append(ResearchMeter.daysText(days)).append(" left. Cards given now shorten it;")
                    .append(" the [+Shards] paid to start stay spent.\n");
        } else if (forge.adventure.util.RealmMilestones.royalGrantReady()) { // round 774: a realm boon pays this start
            body.append("Royal Grant: research it now for free, done at once.\n");
        } else if (days == 0) {
            body.append("Ready: research it now for ").append(ResearchMeter.startCost(player, code))
                    .append("[+Shards], with no wait. More of its cards only pay gold.\n");
        } else {
            body.append("Research now: ").append(ResearchMeter.startCost(player, code)).append("[+Shards], ")
                    .append(ResearchMeter.daysText(days));
            if (ResearchMeter.points(player, code) > 0f) { // the unfed price only once cards have changed it
                // Round 776: the unfed days as the Lab row shows them at no fill - Researcher and the Research towns
                // included (ResearchMeter.lengthDaysAt at 0 points), not the bare researchDaysV2.
                int unfed = Math.max(0, (int) Math.ceil((double) ResearchMeter.unfedDays()
                        * forge.adventure.util.Ascendance.researchDaysFactor()
                        * forge.adventure.util.TownSpecialties.researchDaysFactor() - 1e-6));
                body.append(" (").append(ResearchMeter.baseCost()).append("[+Shards], ")
                        .append(ResearchMeter.daysText(unfed)).append(" with none given)");
            }
            body.append(".\n");
        }
        ResearchMeter.Plan extras = null, all = null;
        if (days > 0) {
            int[] r = Config.instance().getTuningData().researchPointsByRarity;
            String next = ResearchMeter.nextDayLine(player, code, day);
            if (!next.isEmpty())
                body.append(next).append("; a sacrifice here counts double, for no gold.\n");
            body.append("A card counts for its own set, sold anywhere. A new card counts by rarity - an uncommon as ")
                    .append(ratio(r, 1)).append(" commons, a rare ").append(ratio(r, 2)).append(", a mythic ")
                    .append(ratio(r, 3)).append(" - and a later copy a quarter of that.\n");
            extras = ResearchMeter.planSacrifice(player, code, true, day);
            all = ResearchMeter.planSacrifice(player, code, false, day);
            body.append("Sacrifice all spare: ").append(planText(all, running)).append(".\nKeeping one of each: ")
                    .append(planText(extras, running)).append(".");
        }

        Dialog dialog = new Dialog(editionDisplayName(code), Controls.getSkin());
        TextraLabel label = Controls.newTextraLabel(body.toString().trim());
        label.setWrap(true);
        dialog.getContentTable().add(label).width(dialogBodyMaxWidth());
        if (days > 0) { // round 764: a Ready set has nothing left to lower
            final ResearchMeter.Plan extrasPlan = extras, allPlan = all;
            TextraButton extrasButton = Controls.newTextButton("Sacrifice extras", () -> confirmSacrifice(code, true, extrasPlan));
            extrasButton.setDisabled(extras.cards <= 0);
            TextraButton allButton = Controls.newTextButton("Sacrifice all spare", () -> confirmSacrifice(code, false, allPlan));
            allButton.setDisabled(all.cards <= 0);
            dialog.button(extrasButton);
            dialog.button(allButton);
            dialog.getButtonTable().row();
        }
        dialog.button(Controls.newTextButton("View cards", () -> {
            removeDialog();
            openSetView(code);
        }));
        if (ownedCopiesByEdition(player).getOrDefault(code, 0) > 0) // round 766
            dialog.button(Controls.newTextButton("Found Cards", () -> {
                removeDialog();
                openFoundCards(code, false);
            }));
        dialog.button(Controls.newTextButton("Close", this::removeDialog));
        showDialog(dialog);
    }

    private void confirmSacrifice(String code, boolean keepOne, ResearchMeter.Plan plan) {
        boolean running = AdventurePlayer.current().isResearching(code);
        showDialog(createGenericDialog("Sacrifice", "Sacrifice " + plan.cards + (plan.cards == 1 ? " card" : " cards")
                + " of " + editionDisplayName(code) + "? Research: " + ResearchMeter.daysText(plan.daysBefore) + " -> "
                + daysAfterText(plan, running) + ". They leave your collection, for no gold.",
                "Sacrifice", "Cancel", () -> {
                    removeDialog(); // the confirm
                    removeDialog(); // the set window
                    AdventurePlayer player = AdventurePlayer.current();
                    ResearchMeter.sacrifice(player, code, keepOne, Current.world().getCurrentDay());
                    buildList();
                    if (player.hasUnlockedEdition(code)) // round 764: fed to 0 days left - done at once
                        showDialog(createGenericDialog(editionDisplayName(code), editionDisplayName(code)
                                + " is researched - its cards now stock your shops.",
                                Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, this::removeDialog));
                    else
                        openSetDialog(code);
                }, this::removeDialog));
    }

    // ---- round 766: Found Cards -----------------------------------------------------------------------------------

    /** Round 766: Found Cards acts on every spare copy of a card instead of one (the window's checkbox; this visit). */
    private boolean foundAllSpare = false;
    private float foundScrollY = 0f;
    // Round 817: the window's set (null = All Sets) and the deck builder's color/type/rarity filters and group-by. The
    // filters stay while the same game runs (cleared on another save in enter()); the set is the row's on every open.
    private String foundSet;
    private final ResearchCardFilters foundFilters = new ResearchCardFilters();

    /** Round 766: copies owned per set, counted by printing - a row with any gets Found Cards. */
    private static Map<String, Integer> ownedCopiesByEdition(AdventurePlayer player) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<PaperCard, Integer> e : player.getCards())
            if (e.getKey() != null && e.getValue() != null && e.getValue() > 0)
                out.merge(e.getKey().getEdition(), e.getValue(), Integer::sum);
        return out;
    }

    /** Round 817: the distinct cards (printings) owned per set - what that set's Found Cards page lists with no filter
     *  on ("M" in "N of M cards shown"), and the "(N)" on its button. Copies of one card count once. */
    private static Map<String, Integer> foundCardsByEdition(AdventurePlayer player) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<PaperCard, Integer> e : player.getCards())
            if (e.getKey() != null && e.getValue() != null && e.getValue() > 0)
                out.merge(e.getKey().getEdition(), 1, Integer::sum);
        return out;
    }

    /** Round 817: the sets "All Sets" covers - every set the Lab lists that is still under research (not researched) with
     *  a card of it owned: the rows that carry Found Cards. By name. */
    private static List<String> foundCardSets(AdventurePlayer player) {
        Map<String, Integer> copies = ownedCopiesByEdition(player);
        List<String> out = new ArrayList<>();
        for (CardEdition ed : EditionProgression.getMasterEditionList())
            if (copies.getOrDefault(ed.getCode(), 0) > 0 && !player.hasUnlockedEdition(ed.getCode()))
                out.add(ed.getCode());
        out.sort((a, b) -> editionDisplayName(a).compareToIgnoreCase(editionDisplayName(b)));
        return out;
    }

    /**
     * Round 766: the set's owned cards with Sell and Sacrifice - one copy, or every spare copy with "All spare copies"
     * ticked. Deck copies and favorites are never offered (spareCopies). A Ready set has no Sacrifice. Each act updates the
     * days here and on the Lab's row at once. Built in the Lab rather than the deck editor's collection: the editor has no
     * Sacrifice, no one-set filter and no way to refresh the Lab, and round 452's card view is read-only.
     * <p>
     * Round 766 follow-up (the user: "you have to be able to see the card picture. I don't know what each card does
     * without seeing the image"): the cards are a grid of their pictures - the shop's own RewardActor, face up, so pointing
     * at one shows it full size (a tap on Android opens the card view) - each with "2 owned, 2 spare" under it. A click
     * chooses a card: the line under the grid gives its name, the days one copy takes off sacrificed and sold, and the
     * gold, and Sell and Sacrifice act on it. The details sit under the grid, not under every picture, so a picture stays
     * big enough to read its art and type line at 1280x720.
     * <p>
     * Round 817 (the user: "On the sell page, I also want to add a set filter, Including one that reads "All Sets" ...
     * color / type filters ... And also the Group by filter from the deck builder"): a set box beside the header (code null
     * = All Sets: every card of the sets still under research), the deck builder's color and type icons, SpellSmith's
     * rarity buttons and the deck builder's "Groups:" box (ResearchCardFilters), and "N of M cards shown". A filter, the set
     * or a Sell/Sacrifice refills the window in place (fillFound) - no fade. A card always feeds its own set; with All Sets
     * the header counts the sets and the chosen card's line names its set and days. "Sell all over 4" takes the cards shown.
     */
    private void openFoundCards(String code, boolean keepScroll) {
        disposeFoundActors();
        AdventurePlayer player = AdventurePlayer.current();
        foundSet = code;
        boolean landscape = Forge.isLandscapeMode();
        // Round 817: a Dialog hides itself on any of its buttons - the window now stays open and refills in place (Close
        // closes it), so that hide is cancelled. Before, every act reopened the window, which hid it.
        Dialog dialog = new Dialog("", Controls.getSkin()) {
            @Override
            protected void result(Object object) {
                cancel();
            }
        };
        foundDialog = dialog;
        float width = Math.min(440f, stage.getWidth() - 24f);
        float setWidth = landscape ? 120f : width;

        List<String> sets = foundCardSets(player);
        if (code != null && !sets.contains(code)) // opened from a set's own window
            sets.add(0, code);
        foundHead = Controls.newTextraLabel("");
        foundHead.setWrap(true);
        com.badlogic.gdx.scenes.scene2d.ui.SelectBox<String> setBox = ResearchCardFilters.setBox(sets, code, picked -> {
            foundSet = picked;
            foundScrollY = 0f;
            fillFound();
        });
        Table top = new Table();
        if (landscape) {
            top.add(foundHead).width(width - setWidth - 6f).top().left();
            top.add(setBox).width(setWidth).height(BOX_H).padLeft(6f).top().right();
        } else {
            top.add(foundHead).width(width).left().row();
            top.add(setBox).width(setWidth).padTop(2f).left();
        }
        dialog.getContentTable().add(top).width(width).row();

        Runnable onFilter = () -> {
            foundScrollY = 0f;
            fillFound();
        };
        Table band = new Table();
        com.badlogic.gdx.scenes.scene2d.ui.SelectBox<String> groupBox = foundFilters.groupBox(onFilter);
        foundCount = Controls.newTextraLabel("");
        if (landscape) {
            band.add(foundFilters.iconRow(onFilter)).left().expandX();
            band.add(groupBox).width(setWidth).height(BOX_H).right().row();
            band.add(foundFilters.rarityRow(onFilter, 50f)).left().padTop(2f);
            band.add(foundCount).width(setWidth).padTop(2f).right();
        } else {
            band.add(foundFilters.iconRow(onFilter)).left().row();
            band.add(foundFilters.rarityRow(onFilter, 54f)).left().padTop(2f).row();
            band.add(groupBox).width(setWidth).left().padTop(2f).row();
            band.add(foundCount).left().padTop(1f);
        }
        dialog.getContentTable().add(band).width(width).padTop(2f).row();

        foundGrid = new Table(Controls.getSkin());
        foundGrid.top().left();
        foundColumns = Math.max(1, (int) ((width - 8f) / (FOUND_CARD_W + 8f)));
        foundGridWidth = width;
        ScrollPane pane = new ScrollPane(foundGrid);
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        // Round 817: the filter band's rows come out of the grid (two in landscape, four in portrait), and 10 more in
        // landscape for a two-line detail (All Sets names the set) - one row of pictures still shows at 270.
        float bandHeight = landscape ? 40f : 172f; // portrait: also its 4-line header, set box and two button rows
        dialog.getContentTable().add(pane).width(width)
                .height(Math.max(FOUND_CARD_H + 6f, stage.getHeight() - 132f - bandHeight)).padTop(2).row();
        foundPane = pane;
        foundDetail = Controls.newTextraLabel("");
        foundDetail.setWrap(true);
        dialog.getContentTable().add(foundDetail).width(width).padTop(2).row();

        foundSell = Controls.newTextButton("Sell", () -> {
            PaperCard pc = foundSelected;
            int spare = pc == null ? 0 : ResearchMeter.spareCopies(AdventurePlayer.current(), pc);
            if (spare <= 0)
                return;
            ResearchMeter.sellFromLab(AdventurePlayer.current(), pc, foundAllSpare ? spare : 1, Current.world().getCurrentDay());
            afterFoundAct(Collections.singletonList(pc.getEdition()));
        });
        dialog.button(foundSell);
        // Round 817: always there - disabled while the chosen card's set is Ready (was left out on a Ready set's window).
        foundSacrifice = Controls.newTextButton("Sacrifice", () -> {
            PaperCard pc = foundSelected;
            int spare = pc == null ? 0 : ResearchMeter.spareCopies(AdventurePlayer.current(), pc);
            if (spare <= 0)
                return;
            ResearchMeter.sacrificeFromLab(AdventurePlayer.current(), pc, foundAllSpare ? spare : 1, Current.world().getCurrentDay());
            afterFoundAct(Collections.singletonList(pc.getEdition()));
        });
        dialog.button(foundSacrifice);
        // Round 766 follow-up: "Sell all over 4" (copies over 4 of every card, never a deck's or a favorite's), on a Ready
        // set too. Round 817: of the cards shown; disabled when none of them has more than 4.
        foundOverFour = Controls.newTextButton("Sell all over " + ResearchMeter.KEEP_COPIES, this::confirmOverFour);
        dialog.button(foundOverFour);
        if (!landscape) // round 817: five controls overran the 246-px portrait window
            dialog.getButtonTable().row();
        CheckBox allBox = Controls.newCheckBox("All spare copies");
        allBox.setChecked(foundAllSpare);
        allBox.getLabel().setColor(Color.WHITE);
        allBox.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                foundAllSpare = allBox.isChecked();
                refreshFoundSelection();
            }
        });
        dialog.getButtonTable().add(allBox).padLeft(6f).padRight(6f);
        dialog.button(Controls.newTextButton("Close", () -> {
            disposeFoundActors();
            foundDialog = null;
            removeDialog();
            stage.setScrollFocus(scroller);
        }));
        if (!keepScroll)
            foundScrollY = 0f;
        fillFound();
        showDialog(dialog);
        stage.setScrollFocus(pane);
        pane.layout();
        pane.setScrollY(foundScrollY);
        pane.updateVisualScroll();
    }

    /** Round 817: the window's title, header, card grid (in groups), count and chosen card for the set and filters now -
     *  in place. */
    private void fillFound() {
        if (foundDialog == null)
            return;
        disposeFoundActors();
        AdventurePlayer player = AdventurePlayer.current();
        int day = Current.world().getCurrentDay();
        String set = foundSet;
        List<String> scope = set == null ? foundCardSets(player) : Collections.singletonList(set);
        java.util.Set<String> inScope = new java.util.HashSet<>(scope);
        List<PaperCard> owned = new ArrayList<>();
        for (Map.Entry<PaperCard, Integer> e : player.getCards())
            if (e.getKey() != null && inScope.contains(e.getKey().getEdition()) && e.getValue() != null && e.getValue() > 0)
                owned.add(e.getKey());
        java.util.function.Predicate<PaperCard> shownRule = foundFilters.predicate();
        List<PaperCard> shown = new ArrayList<>();
        for (PaperCard pc : owned)
            if (shownRule.test(pc))
                shown.add(pc);
        shown.sort((a, b) -> {
            boolean sa = ResearchMeter.spareCopies(player, a) > 0, sb = ResearchMeter.spareCopies(player, b) > 0;
            return sa != sb ? (sa ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName());
        });
        // The chosen card stays chosen across a Sell or Sacrifice while a copy is shown; else the first with a spare.
        if (foundSelected == null || !shown.contains(foundSelected))
            foundSelected = shown.isEmpty() ? null : shown.get(0);

        foundDialog.getTitleLabel().setText("Found Cards - " + (set == null ? "All Sets" : editionDisplayName(set)));
        String head;
        if (set == null) {
            head = scope.size() + (scope.size() == 1 ? " set" : " sets") + " under research, " + owned.size()
                    + (owned.size() == 1 ? " found card" : " found cards") + ".\nEach card feeds its own set. Click a card to choose it.";
        } else {
            boolean ready = ResearchMeter.days(player, set, day) == 0;
            head = statusLine(player, set, day) + (ready && !player.isResearching(set)
                    ? "\nIts cards only pay gold now. Click a card to choose it." : "\nA sacrifice counts double, for no gold. Click a card to choose it.");
        }
        foundHead.setText(head);
        foundCount.setText("[%80][#c8c8c8ff]" + shown.size() + " of " + owned.size() + " cards shown");

        foundCaptions.clear();
        foundGrid.clear();
        Map<String, List<PaperCard>> groups = foundFilters.group(shown);
        for (Map.Entry<String, List<PaperCard>> g : groups.entrySet()) {
            if (!g.getKey().isEmpty()) {
                TextraLabel header = Controls.newTextraLabel("[#ffd24aff]" + g.getKey() + " (" + g.getValue().size() + ")");
                foundGrid.add(header).colspan(foundColumns).left().padTop(2f).padLeft(4f).row();
            }
            int i = 0;
            for (PaperCard pc : g.getValue()) {
                RewardActor picture = new RewardActor(new forge.adventure.util.Reward(pc), false, null, false);
                foundActors.add(picture);
                picture.addListener(new ClickListener() {
                    @Override
                    public void clicked(InputEvent event, float x, float y) {
                        foundSelected = pc;
                        refreshFoundSelection();
                    }
                });
                TextraLabel caption = Controls.newTextraLabel("");
                foundCaptions.put(pc, caption);
                Table cell = new Table();
                cell.add(picture).size(FOUND_CARD_W, FOUND_CARD_H).row();
                cell.add(caption).padTop(1f);
                foundGrid.add(cell).pad(4f).top();
                if (++i % foundColumns == 0)
                    foundGrid.row();
            }
            foundGrid.row();
        }
        if (shown.isEmpty()) {
            TextraLabel none = Controls.newTextraLabel(owned.isEmpty()
                    ? (set == null ? "No found cards left in the sets under research." : "No cards of this set left in your collection.")
                    : "No card matches the filters.");
            none.setWrap(true);
            foundGrid.add(none).width(foundGridWidth - 8f).align(Align.left);
        }
        int over = 0;
        for (String s : scope)
            over += ResearchMeter.planOverFour(player, s, day, shownRule).cards;
        foundOverFour.setDisabled(over <= 0);
        System.out.println("[TFR-Research] Found Cards: " + (set == null ? "All Sets (" + scope.size() + " sets)" : set) + ", "
                + shown.size() + " of " + owned.size() + " shown" + (foundFilters.active() ? " (filtered)" : "")
                + (foundFilters.groupBy() != null ? ", groups: " + foundFilters.groupBy().getName() : ""));
        refreshFoundSelection();
    }

    // Round 766 follow-up: a picture's size in the grid - its art and type line read at 1280x720 - and the grid's state.
    private static final float FOUND_CARD_W = 64f, FOUND_CARD_H = 89f;
    // Round 817: the set and Groups boxes in landscape - at the skin's own ~22 the window overran 270 with a two-line detail.
    private static final float BOX_H = 16f;
    private final List<RewardActor> foundActors = new ArrayList<>();
    private final Map<PaperCard, TextraLabel> foundCaptions = new HashMap<>();
    private PaperCard foundSelected;
    private TextraLabel foundDetail, foundHead, foundCount;
    private TextraButton foundSell, foundSacrifice, foundOverFour;
    private ScrollPane foundPane;
    private Dialog foundDialog;
    private Table foundGrid;
    private int foundColumns = 1;
    private float foundGridWidth;

    /** Round 766 follow-up: the captions (the chosen card's in gold), the detail line and the buttons for the chosen card -
     *  in place, without rebuilding the pictures. Round 817: Ready is the chosen card's own set's. */
    private void refreshFoundSelection() {
        AdventurePlayer player = AdventurePlayer.current();
        int day = Current.world().getCurrentDay();
        for (Map.Entry<PaperCard, TextraLabel> e : foundCaptions.entrySet()) {
            PaperCard pc = e.getKey();
            boolean chosen = pc.equals(foundSelected);
            e.getValue().setText("[%75]" + (chosen ? "[#ffd24aff]" : "") + player.getCards().count(pc) + " owned, "
                    + ResearchMeter.spareCopies(player, pc) + " spare");
        }
        PaperCard pc = foundSelected;
        int spare = pc == null ? 0 : ResearchMeter.spareCopies(player, pc);
        boolean ready = pc != null && ResearchMeter.days(player, pc.getEdition(), day) == 0;
        String detail;
        if (pc == null)
            detail = "";
        else {
            boolean all = foundSet == null; // round 817: the card's set, named, with its days
            detail = "[#ffd24aff]" + pc.getName() + "[#ffffffff] (" + rarityLetter(pc) + (all ? ", " + pc.getEdition() : "")
                    + ") - " + player.getCards().count(pc) + " owned, " + spare + " spare. ";
            if (all)
                detail += pc.getEdition() + ": " + setDaysText(player, pc.getEdition(), day) + ". ";
            if (pc.hasNoSellValue())
                detail += "No sale value.";
            else if (spare <= 0)
                detail += "In a deck or a favorite - kept.";
            else if (ready) // the gold glyph ends the line (its advance reads as a space before punctuation)
                detail += "Sold for " + player.cardSellPrice(pc) + "[+Gold]";
            else
                detail += "Sacrificed: " + daysOffText(ResearchMeter.daysOffForOne(player, pc, true, day)) + ". Sold: "
                        + daysOffText(ResearchMeter.daysOffForOne(player, pc, false, day)) + " and " + player.cardSellPrice(pc) + "[+Gold]";
        }
        if (foundDetail != null)
            foundDetail.setText(detail);
        if (foundSell != null) {
            foundSell.setText(foundAllSpare ? "Sell all" : "Sell");
            foundSell.setDisabled(spare <= 0);
        }
        if (foundSacrifice != null) {
            foundSacrifice.setText(foundAllSpare ? "Sacrifice all" : "Sacrifice");
            foundSacrifice.setDisabled(spare <= 0 || ready);
        }
        // Round 817: the window is sized when it opens; a refill or a choice that adds a line (All Sets' detail, a long
        // name) squeezed the content up over the title. Size and center it again once it is on the stage.
        if (foundDialog != null && foundDialog.getStage() != null) {
            foundDialog.pack();
            foundDialog.setPosition(Math.round((stage.getWidth() - foundDialog.getWidth()) / 2f),
                    Math.round((stage.getHeight() - foundDialog.getHeight()) / 2f));
        }
    }

    /** Round 817: "37 days", "22 days left", "Ready". */
    private static String setDaysText(AdventurePlayer player, String code, int day) {
        int days = ResearchMeter.days(player, code, day);
        if (player.isResearching(code))
            return ResearchMeter.daysText(days) + " left";
        return days == 0 ? "Ready" : ResearchMeter.daysText(days);
    }

    /** Round 766 follow-up: UIScene.act puts the top dialog in front every frame (round 120), which left a picture's
     *  full-size tooltip - a Container on the stage, added on hover - behind the Found Cards window. While the window is
     *  open, lift such containers back above it, after UIScene has ordered the dialog. */
    @Override
    public void act(float delta) {
        super.act(delta);
        if (foundActors.isEmpty() && foundDialog == null)
            return;
        // Round 817: a set or Groups box's open list is a stage actor too - it went behind the window the same way.
        for (Actor a : new com.badlogic.gdx.utils.Array<>(stage.getActors()))
            if ((a instanceof com.badlogic.gdx.scenes.scene2d.ui.Container
                    || a instanceof com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxScrollPane) && a.isVisible())
                a.toFront();
    }

    /** The pictures' tooltips and textures go with the window (WorldStage's Duplicate dialog does the same). */
    private void disposeFoundActors() {
        for (RewardActor a : foundActors) {
            a.removeTooltip();
            a.remove();
        }
        foundActors.clear();
    }

    /** Round 766 follow-up: "Sell all over 4" asks first - the cards, the gold and what it does to the days. Round 817: the
     *  cards shown only, across the window's sets. */
    private void confirmOverFour() {
        AdventurePlayer player = AdventurePlayer.current();
        int day = Current.world().getCurrentDay();
        List<String> scope = foundSet == null ? foundCardSets(player) : Collections.singletonList(foundSet);
        java.util.function.Predicate<PaperCard> shown = foundFilters.predicate();
        int cards = 0, gold = 0;
        List<String> research = new ArrayList<>();
        List<String> touched = new ArrayList<>();
        for (String s : scope) {
            ResearchMeter.Plan plan = ResearchMeter.planOverFour(player, s, day, shown);
            if (plan.cards <= 0)
                continue;
            touched.add(s);
            cards += plan.cards;
            gold += plan.gold;
            boolean running = player.isResearching(s);
            String change = plan.daysBefore == 0 ? "stays Ready"
                    : ResearchMeter.daysText(plan.daysBefore) + (running ? " left" : "") + " -> " + daysAfterText(plan, running);
            research.add(foundSet == null ? s + " " + change : change);
        }
        if (cards <= 0)
            return;
        String researchText;
        if (foundSet != null)
            researchText = research.get(0).equals("stays Ready") ? "Research: stays Ready - its cards only pay gold."
                    : "Research: " + research.get(0) + ".";
        else
            researchText = "Research: " + String.join(", ", research.subList(0, Math.min(4, research.size())))
                    + (research.size() > 4 ? " and " + (research.size() - 4) + " more" : "") + ".";
        String which = foundFilters.active() ? " shown" : "";
        // The gold glyph ends its line (its advance reads as a space before punctuation).
        showDialog(createGenericDialog("Sell all over " + ResearchMeter.KEEP_COPIES, "Sell the copies over "
                + ResearchMeter.KEEP_COPIES + " of each card" + which + " - " + cards + (cards == 1 ? " card" : " cards")
                + (touched.size() > 1 ? " of " + touched.size() + " sets" : "") + " - for " + gold + "[+Gold]\n" + researchText
                + " Deck copies and favorites are kept.",
                "Sell", "Cancel", () -> {
                    removeDialog(); // the confirm
                    for (String s : touched)
                        ResearchMeter.sellOverFour(AdventurePlayer.current(), s, Current.world().getCurrentDay(), shown);
                    afterFoundAct(touched);
                }, this::removeDialog));
    }

    /** Round 766: after a Sell or Sacrifice - the Lab's row and the window show the new days; a research fed to 0 days
     *  left is done. Round 817: the window refills in place, keeping its scroll; the sets acted on are checked. */
    private void afterFoundAct(java.util.Collection<String> sets) {
        buildList();
        AdventurePlayer player = AdventurePlayer.current();
        List<String> done = new ArrayList<>();
        for (String s : sets)
            if (player.hasUnlockedEdition(s))
                done.add(s);
        if (!done.isEmpty()) {
            disposeFoundActors();
            foundDialog = null;
            removeDialog();
            List<String> names = new ArrayList<>();
            for (String s : done)
                names.add(editionDisplayName(s));
            String list = String.join(", ", names);
            showDialog(createGenericDialog(list, list + (done.size() == 1 ? " is" : " are")
                    + " researched - " + (done.size() == 1 ? "its" : "their") + " cards now stock your shops.",
                    Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, this::removeDialog));
            return;
        }
        foundScrollY = foundPane != null ? foundPane.getScrollY() : 0f;
        fillFound();
        if (foundPane != null) {
            foundPane.layout();
            foundPane.setScrollY(foundScrollY);
            foundPane.updateVisualScroll();
        }
    }

    private static String daysOffText(int days) {
        return days <= 0 ? "under a day off" : ResearchMeter.daysText(days) + " off";
    }

    private static String rarityLetter(PaperCard pc) {
        forge.card.CardRarity r = pc.getRarity();
        if (r == forge.card.CardRarity.MythicRare)
            return "M";
        if (r == forge.card.CardRarity.Rare || r == forge.card.CardRarity.Special)
            return "R";
        return r == forge.card.CardRarity.Uncommon ? "U" : "C";
    }

    /** Round 764: "12 cards, 14 days -> 3 days", "5 cards, 14 days -> Ready", "none". */
    private static String planText(ResearchMeter.Plan plan, boolean running) {
        if (plan.cards <= 0)
            return "none";
        return plan.cards + (plan.cards == 1 ? " card, " : " cards, ") + ResearchMeter.daysText(plan.daysBefore) + " -> "
                + daysAfterText(plan, running);
    }

    private static String daysAfterText(ResearchMeter.Plan plan, boolean running) {
        if (plan.daysAfter > 0)
            return ResearchMeter.daysText(plan.daysAfter);
        return running ? "done at once" : "Ready";
    }

    /** Round 764: a rarity's first-copy weight in commons ("2", "1.5"). */
    private static String ratio(int[] byRarity, int i) {
        int[] r = byRarity != null && byRarity.length > 3 ? byRarity : new int[]{1, 2, 3, 5};
        return ResearchMeter.fmt(r[0] > 0 ? (float) r[i] / r[0] : r[i]);
    }

    /**
     * Round 452 (the user: "add a view button/icon (maybe the magnifying glass) in the research lab, for sets you can
     * research or have researched. When you click on it, you can view all the cards in that set ... kinda like the deck
     * builder"). The cards are the ones the Lab counts - {@link PrintingIndex#namesIn}, the "N cards" on the set's line -
     * one of each in the set's own printing, shown read-only by the deck editor's preview (DeckPreviewScene, the Inn's
     * Jumpstart preview: tap a card to zoom it, nothing to buy or move).
     */
    private void openSetView(String code) {
        CardDb db = FModel.getMagicDb().getCommonCards();
        List<String> names = new ArrayList<>(PrintingIndex.namesIn(code));
        Collections.sort(names);
        Deck deck = new Deck(editionDisplayName(code));
        int shown = 0, otherPrinting = 0;
        for (String name : names) {
            PaperCard pc = db.getCard(name, code);
            if (pc == null) {
                pc = db.getCard(name);
                if (pc != null)
                    otherPrinting++;
            }
            if (pc == null)
                continue;
            deck.getMain().add(pc);
            shown++;
        }
        System.out.println("[TFR-SetView] " + code + ": " + shown + " of " + names.size() + " card(s) shown"
                + (otherPrinting > 0 ? ", " + otherPrinting + " in another set's printing" : ""));
        scrollYBeforeSetView = scroller.getScrollY();
        returningFromSetView = true;
        Forge.switchScene(DeckPreviewScene.getInstance(deck, "Cards"));
    }

    private static String editionDisplayName(String code) {
        // Direct keyed lookup, NOT a getMasterEditionList() scan - the master list excludes
        // non-booster editions (the whole Jumpstart starter family), so a scan showed the raw
        // code ("Researching: J25") for exactly the editions most likely to appear here.
        CardEdition ed = FModel.getMagicDb().getEditions().get(code);
        return ed != null ? ed.getName() + " (" + ed.getCode() + ")" : code;
    }
}
