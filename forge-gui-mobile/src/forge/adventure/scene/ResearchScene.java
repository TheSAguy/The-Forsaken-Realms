package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.util.EconomyBuildings;
import forge.adventure.util.EditionProgression;
import forge.adventure.util.PrintingIndex;
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
 * Design choices made here that go beyond the user's literal spec, flagged rather than silently
 * assumed:
 * <ul>
 *   <li>Only shows editions the player has found at LEAST ONE card from (owned count &gt; 0) -
 *   showing all ~80-120 real editions at 0/N from turn one would bury the handful actually worth
 *   acting on. The user asked for "a list of all editions"; this narrows that to "all editions
 *   worth listing right now" for readability. Fully researched editions still drop off entirely.</li>
 *   <li>Sorted by progress toward the threshold (closest first) - surfaces what's actually
 *   actionable without the player needing to scan/sort themselves.</li>
 *   <li>Research cost (300g base, difficulty-scaled via EconomyBuildings.scaledCost() same as
 *   every other cost this mod has - see COST_GOLD) is Claude's own proposal, not user-specified.</li>
 * </ul>
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
    private static final int THRESHOLD_MIN = 5;
    // 2026-08-12 user cost table: research costs shards now, not gold.
    private static final int COST_SHARDS = 100;

    private final Table scrollContainer;
    private final Window scrollWindow;
    private final Table root;
    private final CheckBox hideUnfoundCheckBox;
    // Round 313 (user: "add a button to hide the second group. The sets you found cards for, but not enough to
    // research"): "Hide partial", beside "Hide unfound". Starts unchecked - both keep their state for the session.
    private final CheckBox hidePartialCheckBox;
    // Not final - its own click handler below needs to reference it (to update its label text)
    // from inside the same lambda passed to its own constructor call.
    private TextraButton showResearchedButton;
    // Not persisted - deliberately resets to "hidden" each time the screen opens, same as the
    // hide-unfound checkbox always starting checked (see its own setChecked(true) below).
    private boolean showResearched = false;
    private final ScrollPane scroller;
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
        hidePartialCheckBox = Controls.newCheckBox("Hide partial");
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
            showResearchedButton.setText(showResearched ? "Hide Researched" : "Show Researched");
            buildList();
        });
        // Round 313: the two filters share the left cell - side by side, or stacked in the narrow portrait pane.
        Table filters = new Table();
        filters.add(hideUnfoundCheckBox).align(Align.left).padRight(12);
        if (!Forge.isLandscapeMode())
            filters.row();
        filters.add(hidePartialCheckBox).align(Align.left);
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
        // Lazy completion check (also runs from the daily tick - see EconomyBuildings.
        // processDaysPassed() - but re-checking here too means a research that finished while the
        // player was elsewhere in-game still shows as complete the instant they open this screen).
        AdventurePlayer.current().checkResearchCompletion(Current.world().getCurrentDay());
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

    private void buildList() {
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

        int cost = EconomyBuildings.scaledCost(AdventurePlayer.researchShardCost());
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

    private static String editionDisplayName(String code) {
        // Direct keyed lookup, NOT a getMasterEditionList() scan - the master list excludes
        // non-booster editions (the whole Jumpstart starter family), so a scan showed the raw
        // code ("Researching: J25") for exactly the editions most likely to appear here.
        CardEdition ed = FModel.getMagicDb().getEditions().get(code);
        return ed != null ? ed.getName() + " (" + ed.getCode() + ")" : code;
    }
}
