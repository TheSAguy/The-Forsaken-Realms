package forge.adventure.scene;

import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.scenes.scene2d.Action;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.utils.Array;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.ArenaData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.RewardData;
import forge.adventure.data.WorldData;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.IAfterMatch;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.*;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;
import forge.gui.FThreads;
import forge.screens.TransitionScreen;

import java.util.Random;

/**
 * Displays the rewards of a fight or a treasure
 */
public class ArenaScene extends UIScene implements IAfterMatch {
    private static ArenaScene object;
    private final float gridSize;
    private ArenaData arenaData;
    private final TextraButton startButton;

    public static ArenaScene instance() {
        if (object == null)
            object = new ArenaScene();
        return object;
    }

    private final TextraButton doneButton;
    private final TextraLabel goldLabel;

    private final Group arenaPlane;
    private final Table arenaTable;
    private final Random rand = new Random();

    final Sprite fighterSpot;
    final Sprite lostOverlay;
    final Sprite up;
    final Sprite upWin;
    final Sprite side;
    final Sprite sideWin;
    final Sprite edge;
    final Sprite edgeM;
    final Sprite edgeWin;
    final Sprite edgeWinM;
    boolean enable = true;
    boolean arenaStarted = false;
    Dialog startDialog, concedeDialog;

    // Arena Level 2 upgrade + Normal/Challenging toggle, moved from a pre-entry MapStage gating
    // dialog into the Arena screen itself (user request 2026-08-11: "have the Upgrade be an
    // option inside the arena interface vs. a gating menu... a button for switching between
    // Normal vs. Challenging"). Collision now enters straight into this scene (Normal mode,
    // MapStage's "arena" case) instead of stopping at a chooser dialog first; the raw JSON for
    // both pools is stashed here (rather than parsed ArenaData) so the toggle can re-parse
    // whichever pool it's switching TO on demand, same as the old dialog's two callbacks did.
    private MapStage arenaMapStage;
    private int arenaObjectId = -1;
    private String regularArenaJson, challengeArenaJson;
    private boolean challengeMode = false;
    private final TextraButton arenaUpgradeButton, arenaModeToggleButton;

    // Deck Tester (user spec 2026-08-11, MOD_SCOPE.md #20): a 3rd Arena option, Level 2 only -
    // player picks 2 of their own saved decks, pilots one themselves, the AI pilots the other, as
    // a single ordinary duel (no bracket, no rewards, no reputation effect - see
    // launchDeckTester()'s own comment). Separate from the Upgrade/toggle buttons above since it's
    // available WHENEVER level >= 2, not mutually exclusive with the mode toggle - both show at
    // once at Level 2, so this gets its own row rather than sharing a position.
    private final TextraButton deckTesterButton;
    // True only while a Deck Tester duel is in flight - setWinner() (the IAfterMatch callback
    // DuelScene invokes on this scene once ANY duel launched while ArenaScene was active ends,
    // bracket or not) checks this FIRST and skips all bracket-manipulation logic when set, since
    // a Deck Tester match has nothing to do with the current bracket's fighters/rounds state.
    private boolean deckTesterMatch = false;

    // Explicit width for the 3 wide programmatic buttons above (round 5 off-screen fix) - see the
    // constructor's own comment for why doneButton's 48-unit width was never a safe size
    // reference. 220 leaves a wide margin before the gold/start buttons at x=380 (canvas is 480
    // wide total, per ui/arena.json) while still comfortably fitting the longest label
    // ("Switch to Challenging Arena") at the [%80] scale applied where text is set.
    private static final float ARENA_WIDE_BUTTON_WIDTH = 220f;
    // Deck Tester's button, sharing a row with the toggle instead of its own row (round 7 fix) -
    // see the constructor's own comment for the space math.
    private static final float ARENA_DECK_TESTER_BUTTON_WIDTH = 140f;

    private ArenaScene() {
        super(Forge.isLandscapeMode() ? "ui/arena.json" : "ui/arena_portrait.json");
        fighterSpot = Config.instance().getAtlasSprite(Paths.ARENA_ATLAS, "Spot");
        lostOverlay = Config.instance().getAtlasSprite(Paths.ARENA_ATLAS, "Lost");
        up = Config.instance().getAtlasSprite(Paths.ARENA_ATLAS, "Up");
        upWin = Config.instance().getAtlasSprite(Paths.ARENA_ATLAS, "UpWin");
        side = Config.instance().getAtlasSprite(Paths.ARENA_ATLAS, "Side");
        sideWin = Config.instance().getAtlasSprite(Paths.ARENA_ATLAS, "SideWin");
        edge = Config.instance().getAtlasSprite(Paths.ARENA_ATLAS, "Edge");
        edgeM = Config.instance().getAtlasSprite(Paths.ARENA_ATLAS, "EdgeFlip");
        edgeM.setFlip(true, false);
        edgeWin = Config.instance().getAtlasSprite(Paths.ARENA_ATLAS, "EdgeWin");
        edgeWinM = Config.instance().getAtlasSprite(Paths.ARENA_ATLAS, "EdgeWinFlip");
        edgeWinM.setFlip(true, false);
        gridSize = fighterSpot.getRegionWidth();

        goldLabel = ui.findActor("gold");
        ui.onButtonPress("done", () -> {
            if (!enable)
                return;
            if (!arenaStarted)
                ArenaScene.this.done();
            else
                showAreYouSure();
        });
        ui.onButtonPress("start", this::startButton);
        doneButton = ui.findActor("done");
        ScrollPane pane = ui.findActor("arena");
        arenaPlane = new Table();
        arenaTable = new Table();
        pane.setActor(arenaPlane);

        startButton = ui.findActor("start");

        // Arena Level 2 upgrade + Normal/Challenging toggle (2026-08-11) - programmatic buttons,
        // not added to the shared ui/arena.json (every plane's Arena loads it), same pattern
        // RewardScene's guardsButton/upgradeButton already use. Positioned above the done button,
        // stacked (upgrade above toggle) - at most one is ever visible at a time (upgrade before
        // Level 2, toggle after), so they never actually overlap on screen.
        //
        // BUG FIX (2026-08-11, round 5 - user report: "Upgrade / switch Arena button is off the
        // screen on the left"): this screen's whole canvas is only 480x270 (ui/arena.json), and
        // "done" is a tiny 48-wide button pinned at x=5. The original formula right-aligned each
        // wide button's RIGHT edge to doneButton's right edge (doneButton.getX() + doneButton.
        // getWidth() - thisButton.getWidth()) - fine for a button narrower than doneButton, but at
        // 2.2x doneButton's width that puts the LEFT edge at 5 + 48 - 105.6 = -52.6, well past the
        // left edge of the canvas. Left-aligning to doneButton.getX() instead keeps the whole
        // button on-screen (there's ~325 units of genuinely open space between doneButton's right
        // edge at 53 and the gold/start buttons starting at x=380) - and a fixed, explicit width
        // (ARENA_WIDE_BUTTON_WIDTH) replaces the doneButton-relative multiplier, since doneButton's
        // own 48-unit width was never a meaningful size reference for these much longer labels.
        arenaUpgradeButton = Controls.newTextButton("[%80]Upgrade to Level 2 (" + EconomyBuildings.costLabel(0, EconomyBuildings.ARENA_UPGRADE_WOOD, EconomyBuildings.ARENA_UPGRADE_STONE, 0) + ")", this::promptUpgradeArena);
        arenaUpgradeButton.setSize(ARENA_WIDE_BUTTON_WIDTH, doneButton.getHeight() * 0.8f);
        arenaUpgradeButton.setPosition(doneButton.getX(), doneButton.getY() + doneButton.getHeight() + 10f);
        arenaUpgradeButton.setVisible(false);
        ui.addActor(arenaUpgradeButton);

        arenaModeToggleButton = Controls.newTextButton("", this::toggleArenaMode);
        arenaModeToggleButton.setSize(ARENA_WIDE_BUTTON_WIDTH, doneButton.getHeight() * 0.8f);
        arenaModeToggleButton.setPosition(doneButton.getX(), doneButton.getY() + doneButton.getHeight() + 10f);
        arenaModeToggleButton.setVisible(false);
        ui.addActor(arenaModeToggleButton);

        // Deck Tester button - same row as the toggle, immediately to its right (2026-08-11, round
        // 7 - user report: the earlier "separate row above" placement overlapped the bracket-tree
        // view). arenaUpgradeButton/arenaModeToggleButton never show at the same time as each other
        // (mutually exclusive by level), and deckTesterButton is only ever visible when the toggle
        // COULD be (both need level >= 2) - never alongside arenaUpgradeButton (level < 2) - so this
        // row never has more than 2 buttons in it. Narrower than the other two (140 vs 220): "Deck
        // Tester" is a short label, and this leaves the ~145 units of space actually available
        // between the toggle's right edge (5+220=225, plus a 10-unit gap) and the gold/start
        // buttons starting at x=380.
        deckTesterButton = Controls.newTextButton("Deck Tester", this::promptDeckTester);
        deckTesterButton.setSize(ARENA_DECK_TESTER_BUTTON_WIDTH, doneButton.getHeight() * 0.8f);
        deckTesterButton.setPosition(doneButton.getX() + ARENA_WIDE_BUTTON_WIDTH + 10f, doneButton.getY() + doneButton.getHeight() + 10f);
        deckTesterButton.setVisible(false);
        ui.addActor(deckTesterButton);
    }

    /** Entry point for MapStage's "arena" collision case (2026-08-11) - replaces the old pre-entry
     *  gating dialog (EconomyBuildings.openArenaEntryDialog()): straight into this scene, always
     *  Normal mode first. challengeJson is null wherever this arena has no "arenaChallenge" tmx
     *  property (every arena but the player Capitol's) - the toggle button just never appears. */
    public void enterArenaBuilding(MapStage stage, int objectId, String regularJson, String challengeJson) {
        arenaMapStage = stage;
        arenaObjectId = objectId;
        regularArenaJson = regularJson;
        challengeArenaJson = challengeJson;
        challengeMode = false;
        ArenaData data = JSONStringLoader.parse(ArenaData.class, regularArenaJson, "");
        loadArenaData(data, WorldSave.getCurrentSave().getWorld().getRandom().nextLong(), false);
    }

    /** Ad-hoc entry point for a bracket with no MapStage/building behind it (2026-08-26, Chest's
     *  Illegal Arena Match - user report: "There is an upgrade button on the Arena interface.
     *  Remove that (NOT in Player city, just the Chest Illegal Arena Match)"). This scene is a
     *  singleton, and loadArenaData() itself never calls refreshArenaBuildingButtons() (only the
     *  Start-button flow and done() do) - so a caller that skips enterArenaBuilding() entirely
     *  (as ChestEvents does) would otherwise inherit whatever arenaMapStage/button visibility was
     *  left over from the player's LAST real Capitol arena visit. Explicitly clears that context
     *  and re-evaluates the buttons before returning, so Upgrade/Toggle/DeckTester are correctly
     *  hidden for this run regardless of prior state. */
    public void loadArenaDataStandalone(ArenaData data, long seed) {
        arenaMapStage = null;
        arenaObjectId = -1;
        challengeArenaJson = null;
        loadArenaData(data, seed, false);
        refreshArenaBuildingButtons();
    }

    private int arenaBuildingLevel() {
        if (arenaMapStage == null || arenaMapStage.getChanges() == null || arenaObjectId < 0)
            return 1;
        return arenaMapStage.getChanges().getBuildingLevel(arenaObjectId);
    }

    /** Shows/hides the upgrade and toggle buttons for the current level/mode/match state - called
     *  after load, after upgrading, and after a match starts/ends (never offer either mid-match). */
    private void refreshArenaBuildingButtons() {
        // Stock planes' capitals (common/maps/map/main_story/*_capital.tmx) carry arena objects
        // too, so without this gate Shandalar players got the mod's upgrade economy (2026-08-12
        // review finding; CLAUDE.md opt-in ground rule). Flag off = plain stock arena.
        // AI-capitals ownership gate (2026-08-13, user spec: "let's have those be game default...
        // don't add anything new to them") - same isCurrentTownPlayerOwned check RewardScene's
        // Armory buttons already got in the 2026-08-13 round for the identical exploit shape (see
        // TownRestoration.isCurrentTownPlayerOwned's own comment). Without it, arenaUpgradesEnabled
        // being plane-wide meant the player could pay to upgrade an AI capital's own Arena too.
        boolean playerOwnedTown = arenaMapStage != null && arenaMapStage.getChanges() != null
                && TownRestoration.isCurrentTownPlayerOwned(arenaMapStage.getChanges());
        if (arenaMapStage == null || !Config.instance().getConfigData().arenaUpgradesEnabled || !playerOwnedTown) {
            arenaUpgradeButton.setVisible(false);
            arenaModeToggleButton.setVisible(false);
            deckTesterButton.setVisible(false);
            return;
        }
        boolean midMatch = arenaStarted || roundsWon != 0;
        int level = arenaBuildingLevel();
        arenaUpgradeButton.setVisible(!midMatch && level < 2);
        // Text refreshed here too (round 4, difficulty price multiplier), not just at
        // construction - the label was previously baked in once from the raw constant.
        arenaUpgradeButton.setText("[%80]Upgrade to Level 2 (" + EconomyBuildings.costLabel(0, EconomyBuildings.ARENA_UPGRADE_WOOD, EconomyBuildings.ARENA_UPGRADE_STONE, 0) + ")");
        // Greyed out when unaffordable (2026-08-26 user request: "Grey our the upgrade button if
        // you can't afford the upgrade") - promptUpgradeArena() already re-checks this itself
        // before spending, but nothing previously reflected it in the button's own visual state.
        arenaUpgradeButton.setDisabled(!EconomyBuildings.canAffordCost(0, EconomyBuildings.ARENA_UPGRADE_WOOD, EconomyBuildings.ARENA_UPGRADE_STONE, 0));
        boolean toggleAvailable = !midMatch && level >= 2 && challengeArenaJson != null;
        arenaModeToggleButton.setVisible(toggleAvailable);
        if (toggleAvailable)
            arenaModeToggleButton.setText(challengeMode ? "[%80]Switch to Normal Arena" : "[%80]Switch to Challenging Arena");
        // Deck Tester (user spec 2026-08-11: "only be available at Arena lvl2") - independent of
        // challengeArenaJson (unlike the toggle above), since deck testing has nothing to do with
        // whether this arena even has a Challenge pool.
        deckTesterButton.setVisible(!midMatch && level >= 2);
    }

    private void promptUpgradeArena() {
        if (arenaMapStage == null || arenaMapStage.getChanges() == null)
            return;
        // Defense-in-depth (2026-08-13, AI-capital gate) - arenaUpgradeButton is already hidden
        // at AI capitals via refreshArenaBuildingButtons()'s own playerOwnedTown check, this
        // guards against any other path (e.g. controller/gamepad focus) still invoking it.
        if (!TownRestoration.isCurrentTownPlayerOwned(arenaMapStage.getChanges()))
            return;
        // 2026-08-12 cost table: Arena upgrade is 300 stone + 300 wood.
        if (!EconomyBuildings.canAffordCost(0, EconomyBuildings.ARENA_UPGRADE_WOOD, EconomyBuildings.ARENA_UPGRADE_STONE, 0))
            return;
        showDialog(createGenericDialog("", "Upgrade this Arena to Level 2 for "
                        + EconomyBuildings.costLabel(0, EconomyBuildings.ARENA_UPGRADE_WOOD, EconomyBuildings.ARENA_UPGRADE_STONE, 0)
                        + "?\nUnlocks the Challenging Arena.",
                Forge.getLocalizer().getMessage("lblYes"), Forge.getLocalizer().getMessage("lblNo"), () -> {
                    removeDialog();
                    EconomyBuildings.payCost(0, EconomyBuildings.ARENA_UPGRADE_WOOD, EconomyBuildings.ARENA_UPGRADE_STONE, 0);
                    arenaMapStage.getChanges().setBuildingLevel(arenaObjectId, 2);
                    refreshArenaBuildingButtons();
                }, this::removeDialog));
    }

    private void toggleArenaMode() {
        if (arenaStarted || roundsWon != 0)
            return; // safety net - the button is hidden mid-match, but a queued click shouldn't slip through
        challengeMode = !challengeMode;
        String json = challengeMode ? challengeArenaJson : regularArenaJson;
        if (json == null) {
            challengeMode = !challengeMode; // no pool for the target mode - revert silently
            return;
        }
        ArenaData data = JSONStringLoader.parse(ArenaData.class, json, "");
        loadArenaData(data, WorldSave.getCurrentSave().getWorld().getRandom().nextLong(), challengeMode);
    }

    /** Deck Tester's 3 modes (renamed/split 2026-08-13, user spec). PLAYER_VS_AI is the original
     *  mode (was labeled "Coin Flip") - the player pilots one deck, the AI pilots the other.
     *  AI_VS_AI_WATCH is the existing "Simulated" mode, renamed for clarity now that a second
     *  AI-vs-AI mode exists. AI_VS_AI_NO_WATCH is new - both decks AI-piloted, run headlessly in
     *  the background (no scene switch, no visible duel) for a player-chosen number of games, with
     *  only the final win/loss tally shown - see DeckTesterSimulator. */
    private enum DeckTesterMode {
        PLAYER_VS_AI,
        AI_VS_AI_WATCH,
        AI_VS_AI_NO_WATCH
    }

    /** Deck Tester step 1 (user spec 2026-08-11, MOD_SCOPE.md #20): "which deck will YOU pilot" -
     *  lists every non-empty saved deck slot as a button. Built fresh each open, same convention
     *  as EconomyBuildings' Manage Guards dialog (buildManageGuardsDialog()). */
    private void promptDeckTester() {
        if (arenaMapStage == null || arenaStarted || roundsWon != 0 || !enable)
            return;
        int deckCount = AdventurePlayer.current().getDeckCount();
        boolean anyDeck = false;
        for (int i = 0; i < deckCount; i++) {
            if (!Current.player().isEmptyDeck(i)) {
                anyDeck = true;
                break;
            }
        }
        if (!anyDeck) {
            showDialog(createGenericDialog("Deck Tester", "You have no saved decks to test with yet.",
                    Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, this::removeDialog));
            return;
        }
        // Mode choice (user spec, 2026-08-13, renamed/extended 2026-08-13): "Player vs. AI" is the
        // original mode - the player picks one deck to pilot themselves and one for the AI to
        // pilot. "AI vs. AI - Watch" - both decks are AI-piloted, a fully-automated matchup the
        // player watches play out. "AI vs. AI - No Watch" - same, but run headlessly in the
        // background for several games in a row with just a final tally shown.
        Dialog modeDialog = new Dialog("Deck Tester", Controls.getSkin());
        TypingLabel modeLabel = Controls.newTypingLabel("Choose a mode:");
        modeLabel.setWrap(true);
        modeLabel.skipToTheEnd();
        modeDialog.getContentTable().add(modeLabel).width(250f).row();
        modeDialog.getButtonTable().add(Controls.newTextButton("[%80]Player vs. AI", () -> {
            removeDialog();
            promptDeckTesterFirstDeck(DeckTesterMode.PLAYER_VS_AI);
        })).width(240f).row();
        modeDialog.getButtonTable().add(Controls.newTextButton("[%80]AI vs. AI - Watch", () -> {
            removeDialog();
            promptDeckTesterFirstDeck(DeckTesterMode.AI_VS_AI_WATCH);
        })).width(240f).row();
        modeDialog.getButtonTable().add(Controls.newTextButton("[%80]AI vs. AI - No Watch", () -> {
            removeDialog();
            promptDeckTesterFirstDeck(DeckTesterMode.AI_VS_AI_NO_WATCH);
        })).width(240f).row();
        modeDialog.getButtonTable().add(Controls.newTextButton(Forge.getLocalizer().getMessage("lblCancel"), this::removeDialog)).width(240f).row();
        modeDialog.setKeepWithinStage(true);
        showDialog(modeDialog);
    }

    /** Deck Tester step 1 (all 3 modes) - pick the first deck. "Player vs. AI" frames it as "the
     *  deck YOU will pilot" (unchanged wording from before the mode choice existed); both AI-vs-AI
     *  modes frame it neutrally since neither seat is player-controlled. */
    private void promptDeckTesterFirstDeck(DeckTesterMode mode) {
        int deckCount = AdventurePlayer.current().getDeckCount();
        Dialog dialog = new Dialog("Deck Tester", Controls.getSkin());
        TypingLabel label = Controls.newTypingLabel(mode == DeckTesterMode.PLAYER_VS_AI ? "Choose the deck YOU will pilot:" : "Choose the first deck:");
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();
        for (int i = 0; i < deckCount; i++) {
            if (Current.player().isEmptyDeck(i))
                continue;
            int firstDeckIndex = i;
            String name = Current.player().getDeck(i).getName();
            dialog.getButtonTable().add(Controls.newTextButton(name, () -> {
                removeDialog();
                promptDeckTesterSecondDeck(firstDeckIndex, mode);
            })).width(240f).row();
        }
        dialog.getButtonTable().add(Controls.newTextButton(Forge.getLocalizer().getMessage("lblCancel"), this::removeDialog)).width(240f).row();
        dialog.setKeepWithinStage(true);
        showDialog(dialog);
    }

    /** Deck Tester step 2 - pick the second deck. No exclusion of firstDeckIndex - a same-deck
     *  mirror test is a legitimate use case, not a mistake to guard against. */
    private void promptDeckTesterSecondDeck(int firstDeckIndex, DeckTesterMode mode) {
        int deckCount = AdventurePlayer.current().getDeckCount();
        Dialog dialog = new Dialog("Deck Tester", Controls.getSkin());
        TypingLabel label = Controls.newTypingLabel(mode == DeckTesterMode.PLAYER_VS_AI ? "Choose the deck the AI will pilot:" : "Choose the second deck:");
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();
        for (int i = 0; i < deckCount; i++) {
            if (Current.player().isEmptyDeck(i))
                continue;
            int secondDeckIndex = i;
            String name = Current.player().getDeck(i).getName();
            dialog.getButtonTable().add(Controls.newTextButton(name, () -> {
                removeDialog();
                switch (mode) {
                    case PLAYER_VS_AI:
                        launchDeckTester(firstDeckIndex, secondDeckIndex);
                        break;
                    case AI_VS_AI_WATCH:
                        launchDeckTesterSimulated(firstDeckIndex, secondDeckIndex);
                        break;
                    case AI_VS_AI_NO_WATCH:
                        promptMatchCount(firstDeckIndex, secondDeckIndex);
                        break;
                }
            })).width(240f).row();
        }
        dialog.getButtonTable().add(Controls.newTextButton(Forge.getLocalizer().getMessage("lblCancel"), this::removeDialog)).width(240f).row();
        dialog.setKeepWithinStage(true);
        showDialog(dialog);
    }

    /** "AI vs. AI - No Watch" step 3 (new, user spec 2026-08-13) - how many games to run. */
    private void promptMatchCount(int deckAIndex, int deckBIndex) {
        Dialog dialog = new Dialog("Deck Tester", Controls.getSkin());
        TypingLabel label = Controls.newTypingLabel("How many matches?");
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();
        for (int count : new int[]{5, 10, 20}) {
            dialog.getButtonTable().add(Controls.newTextButton(count + " matches", () -> {
                removeDialog();
                launchDeckTesterBatch(deckAIndex, deckBIndex, count);
            })).width(240f).row();
        }
        dialog.getButtonTable().add(Controls.newTextButton(Forge.getLocalizer().getMessage("lblCancel"), this::removeDialog)).width(240f).row();
        dialog.setKeepWithinStage(true);
        showDialog(dialog);
    }

    /** "AI vs. AI - No Watch" step 4 (new, user spec 2026-08-13) - runs `count` independent games
     *  headlessly via DeckTesterSimulator (no HostedMatch/DuelScene/MatchController at all, so no
     *  scene switch and no spectator pacing tax - see that class's own doc comment), showing a
     *  live-updating progress dialog and a final win/loss tally. `enable=false` for the duration -
     *  mirrors the same gate a real duel gets via deckTesterMatch/enable, but reset directly in
     *  the completion callback below instead of through setWinner()/IAfterMatch, since a headless
     *  batch never goes through DuelScene at all and setWinner() will never fire for it. */
    private void launchDeckTesterBatch(int deckAIndex, int deckBIndex, int count) {
        Deck deckA = Current.player().getDeck(deckAIndex);
        Deck deckB = Current.player().getDeck(deckBIndex);
        String nameA = deckA.getName();
        String nameB = deckB.getName();
        enable = false;

        Dialog progressDialog = new Dialog("Deck Tester", Controls.getSkin());
        TextraLabel progressLabel = Controls.newTextraLabel("Simulating matches... (0/" + count + " complete)");
        progressDialog.getContentTable().add(progressLabel).width(250f).row();
        // End Test (user spec, follow-up to the 2026-08-13 freeze fix): lets the player abort a
        // batch that's taking too long (a genuinely slow/large AI deck, not just the createGame()
        // hang that fix addressed) instead of waiting out the worst case. The click listener is
        // wired at construction (TextraButton has no post-hoc setter), but the Handle it needs to
        // cancel doesn't exist until runBatch() returns below - so it closes over a one-element
        // holder array instead, filled in once runBatch() hands back its Handle.
        final DeckTesterSimulator.Handle[] handleHolder = new DeckTesterSimulator.Handle[1];
        TextraButton endTestButton = Controls.newTextButton("End Test", () -> {
            if (handleHolder[0] != null)
                handleHolder[0].cancel();
        });
        progressDialog.getButtonTable().add(endTestButton).width(240f).row();
        progressDialog.setKeepWithinStage(true);
        showDialog(progressDialog);

        handleHolder[0] = DeckTesterSimulator.runBatch(nameA, deckA, nameB, deckB, count,
                completed -> progressLabel.setText("Simulating matches... (" + completed + "/" + count + " complete)"),
                result -> {
                    removeDialog();
                    enable = true;
                    boolean endedEarly = result.completed < result.total;
                    String resultText = nameA + " won " + result.deckAWins + "\n"
                            + nameB + " won " + result.deckBWins
                            + (result.draws > 0 ? "\nDraws/timeouts: " + result.draws : "")
                            + (endedEarly ? "\n(ended early - " + result.completed + "/" + result.total + " games ran)" : "");
                    showDialog(createGenericDialog("Deck Tester Results", resultText,
                            Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, this::removeDialog));
                });
    }

    /** Launches an ordinary duel where the AI pilots a specific one of the PLAYER's own saved
     *  decks (via the new EnemyData.fixedDeck field) while the player pilots another of their own
     *  saved decks (via a temporary selected-deck-slot swap, restored immediately after
     *  initDuels() has synchronously copied it - see EnemyData.fixedDeck's own comment for why
     *  this doesn't need to persist any longer than that). No ante, no rewards, no bracket - this
     *  is purely for the player to test decks against each other, not to progress the Arena run. */
    private void launchDeckTester(int playerDeckIndex, int aiDeckIndex) {
        EnemyData base = WorldData.getEnemy("Doppelganger");
        if (base == null)
            return;
        EnemyData testerData = new EnemyData(base);
        testerData.copyPlayerDeck = false;
        testerData.fixedDeck = Current.player().getDeck(aiDeckIndex);
        testerData.nameOverride = "Deck Tester";
        testerData.noAnte = true;
        testerData.rewards = new RewardData[0];
        EnemySprite testerEnemy = new EnemySprite(testerData);

        int originalSlot = Current.player().getSelectedDeckIndex();
        Current.player().setSelectedDeckSlot(playerDeckIndex);
        deckTesterMatch = true;
        enable = false;
        DuelScene duelScene = DuelScene.instance();
        duelScene.initDuels(WorldStage.getInstance().getPlayerSprite(), testerEnemy, false, null);
        Current.player().setSelectedDeckSlot(originalSlot);
        FThreads.invokeInEdtNowOrLater(() -> Forge.setTransitionScreen(new TransitionScreen(() ->
                Forge.switchScene(duelScene),
                Forge.takeScreenshot(), true, false, false, false, "", Current.player().avatar(),
                testerEnemy.getAtlasPath(), Current.player().getName(), testerEnemy.getName())));
    }

    /** Deck Tester "Simulated" mode (user spec, 2026-08-13) - both decks AI-piloted, a watchable
     *  auto-played match instead of the player piloting one side. Identical shell trick as
     *  launchDeckTester() above (a "Doppelganger" EnemyData clone with a fixedDeck), the only
     *  difference is DuelScene.initDuels()'s new aiControlsPlayerSide=true - the temporary
     *  selected-deck-slot swap is still needed since that's still how the "player" seat's deck
     *  gets sourced, it's just AI-controlled now rather than human-controlled. No ante, no
     *  rewards, no bracket - purely for comparing two decks against each other. */
    private void launchDeckTesterSimulated(int deckAIndex, int deckBIndex) {
        EnemyData base = WorldData.getEnemy("Doppelganger");
        if (base == null)
            return;
        EnemyData testerData = new EnemyData(base);
        testerData.copyPlayerDeck = false;
        testerData.fixedDeck = Current.player().getDeck(deckBIndex);
        testerData.nameOverride = "Deck Tester";
        testerData.noAnte = true;
        testerData.rewards = new RewardData[0];
        EnemySprite testerEnemy = new EnemySprite(testerData);

        int originalSlot = Current.player().getSelectedDeckIndex();
        Current.player().setSelectedDeckSlot(deckAIndex);
        deckTesterMatch = true;
        enable = false;
        DuelScene duelScene = DuelScene.instance();
        duelScene.initDuels(WorldStage.getInstance().getPlayerSprite(), testerEnemy, false, null, true);
        Current.player().setSelectedDeckSlot(originalSlot);
        FThreads.invokeInEdtNowOrLater(() -> Forge.setTransitionScreen(new TransitionScreen(() ->
                Forge.switchScene(duelScene),
                Forge.takeScreenshot(), true, false, false, false, "", Current.player().avatar(),
                testerEnemy.getAtlasPath(), Current.player().getName(), testerEnemy.getName())));
    }

    private void showAreYouSure() {
        if (concedeDialog == null) {
            concedeDialog = createGenericDialog(Forge.getLocalizer().getMessage("lblConcedeTitle"),
                    "\n" + Forge.getLocalizer().getMessage("lblConcedeCurrentGame"),
                    Forge.getLocalizer().getMessage("lblYes"),
                    Forge.getLocalizer().getMessage("lblNo"), () -> {
                        this.lose();
                        removeDialog();
                    }, this::removeDialog);
        }
        showDialog(concedeDialog);
    }

    private void lose() {
        doneButton.setText("[%80][+Exit]");
        doneButton.layout();
        startButton.setDisabled(true);
        arenaStarted = false;
        AdventureQuestController.instance().updateArenaComplete(false);
        AdventureQuestController.instance().showQuestDialogs(MapStage.getInstance());
    }

    private void startDialog() {
        if (startDialog == null) {
            startDialog = createGenericDialog(Forge.getLocalizer().getMessage("lblStart"),
                    Forge.getLocalizer().getMessage("lblStartArena"), Forge.getLocalizer().getMessage("lblYes"),
                    Forge.getLocalizer().getMessage("lblNo"), () -> {
                        this.startArena();
                        removeDialog();
                    }, this::removeDialog);
        }
        showDialog(startDialog);
    }

    private void startButton() {
        if (!enable)
            return;
        if (roundsWon == 0) {
            startDialog();
        } else {
            startRound();
        }
    }

    int roundsWon = 0;

    private void startArena() {
        // Same setDisabled()-doesn't-gate-clicks trap the Capital toll had (2026-08-30 negative
        // gold report - see WorldStage.showCapitalTollDialog and AdventurePlayer.takeGold): the
        // only affordability check for the entry fee was startButton.setDisabled() in
        // loadArenaData(), which greys the button without detaching its handler. Re-checked here,
        // at the point the fee is actually taken, so a still-live click cannot start a run the
        // player cannot pay for.
        if (arenaData != null && arenaData.entryFee > Current.player().getGold()) {
            System.out.println("[TFR-Gold] Arena start refused - entry fee " + arenaData.entryFee
                    + " exceeds player gold " + Current.player().getGold());
            return;
        }
        enable = false;
        goldLabel.setVisible(false);
        arenaStarted = true;
        startButton.setText("[%80][+OK]");
        startButton.layout();
        doneButton.setText("[%80][+Exit]");
        doneButton.layout();
        Forge.setCursor(null, Forge.magnifyToggle ? "1" : "2");
        Current.player().takeGold(arenaData.entryFee);
        refreshArenaBuildingButtons(); // hide Upgrade/toggle for the duration of the run
        startRound();
    }

    @Override
    public void setWinner(boolean winner, boolean isArena) {
        // Deck Tester (2026-08-11) - DuelScene.afterGameEnd() invokes this IAfterMatch callback
        // after ANY duel launched while ArenaScene was the active scene, bracket or not. A Deck
        // Tester match has no bracket state (fighters/enemies/roundsWon are whatever the last real
        // Arena run left them at, possibly empty) - skip the bracket logic entirely and just
        // restore the screen.
        if (deckTesterMatch) {
            deckTesterMatch = false;
            enable = true;
            refreshArenaBuildingButtons();
            return;
        }
        enable = false;
        Array<ArenaRecord> winners = new Array<>();
        Array<EnemySprite> winnersEnemies = new Array<>();
        for (int i = 0; i < fighters.size - 2; i += 2) {
            int matchHP = enemies.get(i).getData().life + enemies.get(i+1).getData().life;
            boolean leftWon = rand.nextInt(matchHP) < enemies.get(i).getData().life;
            if (leftWon) {
                winners.add(fighters.get(i));
                winnersEnemies.add(enemies.get(i));
                moveFighter(fighters.get(i).actor, true);
                markLostFighter(fighters.get(i + 1).actor);
            } else {
                markLostFighter(fighters.get(i).actor);
                moveFighter(fighters.get(i + 1).actor, false);
                winners.add(fighters.get(i + 1));
                winnersEnemies.add(enemies.get(i + 1));
            }
        }
        if (winner) {
            markLostFighter(fighters.get(fighters.size - 2).actor);
            moveFighter(fighters.get(fighters.size - 1).actor, false);
            winners.add(fighters.get(fighters.size - 1));
            roundsWon++;
            // The player's opponent this round is always the LAST enemy (see startRound()) -
            // remembered for the Challenge Arena's last-defeated-foe card drop in done().
            lastDefeatedEnemyData = enemies.get(enemies.size - 1).getData();
            // Bronze Coin ransom (user request 2026-09-01): note the foe now, pay the coin out in
            // done() with the rest of the bracket's loot. Only recorded - owesCoinRansom() is
            // re-checked at payout time, so a name noted here that somehow stops being owed
            // simply pays nothing.
            //
            // Keyed on the SPRITE's getName(), not lastDefeatedEnemyData.getName(): the mark was
            // written by DuelScene from `enemy.getName()`, which returns a map-authored
            // nameOverride when one is present and only falls through to EnemyData's name
            // otherwise. Arena enemies do not carry overrides today, so the two agree - but
            // reading it off the sprite means they cannot silently diverge later, and a
            // mismatched key here would fail by never returning the coin.
            String beatenName = enemies.get(enemies.size - 1).getName();
            if (Current.player().owesCoinRansom(beatenName)
                    && !coinRansomFoesBeaten.contains(beatenName, false)) {
                coinRansomFoesBeaten.add(beatenName);
                // Noted now, paid in done(). Logged at BOTH ends (user request 2026-09-02) because
                // the two are separated by the rest of the bracket - if the coin never arrives,
                // this line tells you whether the problem was noticing the foe or paying out.
                System.out.println("[TFR-ArenaCoin] noted " + beatenName + " holds a Bronze Coin"
                        + " - beaten in round " + roundsWon + "/" + arenaData.rounds
                        + ", pending payout at bracket end (" + coinRansomFoesBeaten.size
                        + " pending)");
            }
        } else {
            markLostFighter(fighters.get(fighters.size - 1).actor);
            moveFighter(fighters.get(fighters.size - 2).actor, true);
            winners.add(fighters.get(fighters.size - 2));
            lose();
        }

        fighters = winners;
        enemies = winnersEnemies;
        if (roundsWon >= arenaData.rounds) {
            arenaStarted = false;
            startButton.setDisabled(true);
            doneButton.setText("[%80][+Exit]");
            doneButton.layout();
            AdventureQuestController.instance().updateArenaComplete(true);
            AdventureQuestController.instance().showQuestDialogs(MapStage.getInstance());
        }
        if (!Forge.isLandscapeMode())
            drawArena();//update
    }

    private void moveFighter(Actor actor, boolean leftPlayer) {
        Image spotImg = new Image(upWin);
        double stepsToTheSide = Math.pow(2, roundsWon);
        float widthDiff = actor.getWidth() - spotImg.getWidth();
        spotImg.setPosition(actor.getX() + widthDiff / 2, actor.getY() + gridSize + widthDiff / 2);
        arenaPlane.addActor(spotImg);
        for (int i = 0; i < stepsToTheSide; i++) {
            Image leftImg;
            if (i == 0)
                leftImg = new Image(leftPlayer ? edgeWin : edgeWinM);
            else
                leftImg = new Image(sideWin);
            leftImg.setPosition(actor.getX() + (i * (leftPlayer ? 1 : -1)) * gridSize + widthDiff / 2, actor.getY() + gridSize * 2 + widthDiff / 2);
            arenaPlane.addActor(leftImg);
        }
        if (Forge.isLandscapeMode()) {
            actor.toFront();
            actor.addAction(Actions.sequence(Actions.moveBy(0f, gridSize * 2f, 1), Actions.moveBy((float) (gridSize * stepsToTheSide * (leftPlayer ? 1 : -1)), 0f, 1), new Action() {
                @Override
                public boolean act(float v) {
                    enable = true;
                    return true;
                }
            }));
        } else {
            enable = true;
        }
    }

    /** "WU" -> {"white","blue"} etc., matching the color-name strings the arena reward tables'
     *  own "colors" entries use (see CardPredicate). Null for empty/colorless - no color filter,
     *  so a colorless foe's drop can be any rare+, artifacts included. */
    private static String[] colorNamesFor(String colorLetters) {
        if (colorLetters == null || colorLetters.isEmpty())
            return null;
        java.util.List<String> names = new java.util.ArrayList<>();
        for (char c : colorLetters.toUpperCase().toCharArray()) {
            switch (c) {
                case 'W': names.add("white"); break;
                case 'U': names.add("blue"); break;
                case 'B': names.add("black"); break;
                case 'R': names.add("red"); break;
                case 'G': names.add("green"); break;
            }
        }
        return names.isEmpty() ? null : names.toArray(new String[0]);
    }

    private void markLostFighter(Actor fighter) {
        Image lost = new Image(lostOverlay);
        float widthDiff = fighter.getWidth() - lost.getWidth();
        lost.setPosition(fighter.getX() + widthDiff / 2, fighter.getY() + widthDiff / 2);
        arenaPlane.addActor(lost);
    }

    boolean started = false;

    private void startRound() {
        if (started)
            return;
        started = true;
        DuelScene duelScene = DuelScene.instance();
        EnemySprite enemy = enemies.get(enemies.size - 1);
        FThreads.invokeInEdtNowOrLater(() -> Forge.setTransitionScreen(new TransitionScreen(() -> {
            started = false;
            duelScene.initDuels(WorldStage.getInstance().getPlayerSprite(), enemy, true, null);
            Forge.switchScene(duelScene);
        }, Forge.takeScreenshot(), true, false, false, false, "", Current.player().avatar(), enemy.getAtlasPath(), Current.player().getName(), enemy.getTieredDisplayName())
                .withEnemyStatKey(enemy.getName())));
    }

    /**
     * Raw names of coin-holding enemies the player has beaten in the bracket currently running.
     * The mark itself is deliberately NOT cleared when the round is won - only in done(), where
     * the coin is actually paid out (user request 2026-09-01: "For Arena matches, add it to the
     * final arena payout"). Clearing early would lose the coin outright for a player who wins the
     * round and then quits the arena without collecting, since the bracket's rewards are assembled
     * once, at the end. Cleared at the start of every bracket so a previous run's names cannot
     * leak into this one's payout.
     */
    private final Array<String> coinRansomFoesBeaten = new Array<>();

    public boolean start() {
        return true;
    }


    public boolean done() {
        GameHUD.getInstance().getTouchpad().setVisible(false);
        Forge.switchToLast();
        if (roundsWon != 0) {
            Array<Reward> data = new Array<>();
            for (int i = 0; i < roundsWon; i++) {
                for (int j = 0; j < arenaData.rewards[i].length; j++) {
                    data.addAll(arenaData.rewards[i][j].generate(false, null, true));
                }
            }
            // Champion bounty (user decision 2026-08-12): arena-EXCLUSIVE enemies (spawnRate 0 -
            // they exist nowhere but this pool, so their EnemyData rewards would otherwise never
            // pay out; enemy rewards only flow through the overworld/dungeon post-duel handlers,
            // which arena duels never reach) pay their own reward list ON TOP of the round tables
            // when the player wins the ENTIRE bracket that included them. Ordinary pool enemies
            // (roaming bosses etc.) are unaffected - they keep paying their rewards in the wild.
            if (roundsWon == arenaData.rounds) {
                for (EnemyData champion : new Array.ArrayIterator<>(bracketChampions)) {
                    for (RewardData rewardData : champion.rewards)
                        data.addAll(rewardData.generate(false, null, true));
                }
            }
            // Last-defeated-foe drop (user spec 2026-08-12, Challenge only): "you get 1 card
            // (rare+) from the last duel you win... + regular rewards" - a Rare-or-Mythic card
            // in the beaten enemy's colors, so every champion in the pool has a themed drop
            // comparable to the 5 arena-exclusive champions' signature bounty. Applies to the
            // last round WON even on a partial run (lose round 2 -> the drop is themed to the
            // round-1 opponent).
            if (challengeMode && lastDefeatedEnemyData != null) {
                RewardData foeDrop = new RewardData();
                foeDrop.type = "card";
                foeDrop.count = 1;
                foeDrop.rarity = new String[]{"Rare", "Mythic Rare"};
                String[] foeColors = colorNamesFor(lastDefeatedEnemyData.colors);
                if (foeColors != null)
                    foeDrop.colors = foeColors;
                data.addAll(foeDrop.generate(false, null, true));
            }
            // Bronze Coin ransom reclaim (user request 2026-09-01), paid with the bracket's own
            // loot rather than silently at the moment the round was won. Placed AFTER every other
            // table so the coin reads as a distinct extra rather than getting lost mid-page.
            for (String foe : new Array.ArrayIterator<>(coinRansomFoesBeaten)) {
                boolean paid = Current.player().appendCoinRansomReward(data, foe);
                System.out.println("[TFR-ArenaCoin] bracket payout: " + foe + " -> "
                        + (paid ? "Bronze Coin added to the loot page"
                                : "NOT paid - the mark was already gone (check [TFR-CoinRansom])"));
            }
            if (coinRansomFoesBeaten.isEmpty())
                System.out.println("[TFR-ArenaCoin] bracket payout: no coin-holding foes were"
                        + " beaten this bracket - nothing owed");
            coinRansomFoesBeaten.clear();
            RewardScene.instance().loadRewards(data, RewardScene.Type.Loot, null);
            Forge.switchScene(RewardScene.instance());
        } else {
            // roundsWon == 0: no reward screen is shown at all, so there is nowhere to put a coin.
            // Nothing is lost - the marks were never cleared, so the coins stay claimable on a
            // future win. Just drop the notes so they cannot leak into the next bracket.
            if (!coinRansomFoesBeaten.isEmpty())
                System.out.println("[TFR-ArenaCoin] bracket ended with 0 rounds won - dropping "
                        + coinRansomFoesBeaten.size + " pending note(s) UNPAID. The ransom marks"
                        + " themselves are untouched, so those coins stay claimable on a later win.");
            coinRansomFoesBeaten.clear();
        }
        return true;
    }

    @Override
    public void act(float delta) {
        stage.act(delta);
    }


    Array<EnemySprite> enemies = new Array<>();
    Array<ArenaRecord> fighters = new Array<>();
    // Arena-exclusive (spawnRate 0) enemies present in the CURRENT bracket - see done()'s
    // champion-bounty block. Rebuilt on every loadArenaData().
    Array<EnemyData> bracketChampions = new Array<>();
    // The opponent beaten in the player's most recent round win - drives the Challenge Arena's
    // "1 rare+ card from the last duel you win" drop (user spec 2026-08-12).
    EnemyData lastDefeatedEnemyData = null;
    Actor player;

    public void loadArenaData(ArenaData data, long seed) {
        loadArenaData(data, seed, false);
    }

    /** isChallenge (2026-08-11, Arena Level 2 Challenge mode, MOD_SCOPE.md #20): forces every
     *  fight in this run to best-of-1 regardless of each enemy's own EnemyData.gamesPerMatch -
     *  about a third of the Challenge pool (bosses/mini-bosses/Planeswalkers) default to
     *  gamesPerMatch=3 in enemies.json, and the user's spec was explicit that Challenge is
     *  best-of-1 across the board, same as Regular Arena's wizard pool already is by default. */
    public void loadArenaData(ArenaData data, long seed, boolean isChallenge) {
        // Keep the mode field in sync regardless of which entry path called us (toggle vs
        // direct challenge entry) - done()'s last-defeated-foe drop reads it after the run.
        challengeMode = isChallenge;
        startButton.setText("[%80][+OK]");
        startButton.layout();
        doneButton.setText("[%80][+Exit]");
        doneButton.layout();
        arenaData = data;
        //rand.setSeed(seed); allow to reshuffle arena enemies for now

        enemies.clear();
        fighters.clear();
        arenaPlane.clear();
        bracketChampions.clear();
        lastDefeatedEnemyData = null;
        roundsWon = 0;
        coinRansomFoesBeaten.clear();
        int numberOfEnemies = (int) (Math.pow(2f, data.rounds) - 1);


        // Content filter tables (user spec 2026-08-12): drop Include=N names from the pool, but
        // fall back to the UNFILTERED pool if that would empty it (user-confirmed choice) - an
        // empty pool would spin the while(null) resolution loop below forever.
        java.util.List<String> poolNames = new java.util.ArrayList<>();
        for (String name : data.enemyPool)
            if (ContentFilterTables.isEnemyIncluded(name))
                poolNames.add(name);
        if (poolNames.isEmpty()) {
            if (data.enemyPool.length > 0)
                System.out.println("[ContentFilter] arena pool fully excluded - falling back to unfiltered pool");
            poolNames = java.util.Arrays.asList(data.enemyPool);
        }

        for (int i = 0; i < numberOfEnemies; i++) {
            EnemyData enemyData = null;
            while (enemyData == null)
                enemyData = WorldData.getEnemy(poolNames.get(rand.nextInt(poolNames.size())));
            // Arena matches disable ante (user spec 2026-08-11) - clone rather than mutate the
            // shared roster EnemyData, same pattern the Capitol-defense duel uses for its own
            // one-off gamesPerMatch override, so this enemy's non-Arena appearances are unaffected.
            EnemyData arenaEnemyData = new EnemyData(enemyData);
            arenaEnemyData.noAnte = true;
            if (isChallenge)
                arenaEnemyData.gamesPerMatch = 1;
            // Arena-exclusive champions carry a bounty - see done(). Recorded from the ORIGINAL
            // EnemyData (the clone above is display/match-rules only, rewards identical).
            if (enemyData.spawnRate <= 0 && enemyData.rewards != null && enemyData.rewards.length > 0)
                bracketChampions.add(enemyData);
            EnemySprite enemy = new EnemySprite(arenaEnemyData);
            enemies.add(enemy);
            fighters.add(new ArenaRecord(new Image(enemy.getAvatar()), enemyData.getName()));
        }
        fighters.add(new ArenaRecord(new Image(Current.player().avatar()), Current.player().getName()));
        player = fighters.get(fighters.size - 1).actor;

        goldLabel.setText("[+GoldCoin] " + data.entryFee);
        goldLabel.layout();
        goldLabel.setVisible(true);

        startButton.setDisabled(data.entryFee > Current.player().getGold());
        int currentSpots = numberOfEnemies + 1;
        int gridWidth = currentSpots * 2;
        int gridHeight = data.rounds + 1;
        arenaPlane.setSize(gridWidth * gridSize, gridHeight * gridSize * 2);
        int fighterIndex = 0;
        for (int x = 0; x < gridWidth; x++) {
            for (int y = 0; y < gridHeight; y++) {
                if (x % Math.pow(2, y + 1) == Math.pow(2, y)) {
                    if (y == 0) {
                        if (fighterIndex < fighters.size) {
                            float widthDiff = gridSize - fighters.get(fighterIndex).actor.getWidth();
                            fighters.get(fighterIndex).actor.setPosition(x * gridSize + widthDiff / 2, y * gridSize * 2 + widthDiff / 2);
                            arenaPlane.addActor(fighters.get(fighterIndex).actor);
                            fighterIndex++;
                        }
                    }
                    Image spotImg = new Image(fighterSpot);
                    spotImg.setPosition(x * gridSize, y * gridSize * 2);
                    arenaPlane.addActor(spotImg);

                    if (y != gridHeight - 1) {
                        Image upImg = new Image(up);
                        upImg.setPosition(x * gridSize, y * gridSize * 2 + gridSize);
                        arenaPlane.addActor(upImg);
                    }
                    if (y != 0) {
                        for (int i = 0; i < Math.pow(2, (y - 1)); i++) {
                            Image leftImg;
                            Image rightImg;
                            if (i == Math.pow(2, (y - 1)) - 1) {
                                leftImg = new Image(edge);
                                rightImg = new Image(edgeM);
                            } else {
                                leftImg = new Image(side);
                                rightImg = new Image(side);
                            }
                            leftImg.setPosition((x - (i + 1)) * gridSize, y * gridSize * 2);
                            rightImg.setPosition((x + (i + 1)) * gridSize, y * gridSize * 2);
                            arenaPlane.addActor(leftImg);
                            arenaPlane.addActor(rightImg);
                        }
                    }
                }
            }
        }
        drawArena();
        refreshArenaBuildingButtons();
    }

    void drawArena() {
        //center the arenaPlane
        ScrollPane pane = ui.findActor("arena");
        if (pane != null) {
            pane.clear();
            arenaTable.clear();
            if (Forge.isLandscapeMode()) {
                arenaTable.add(Controls.newTextraLabel("[;][%150]" + GameScene.instance().getAdventurePlayerLocation(true, true) + " Arena")).top();
                arenaTable.row();
                arenaTable.add(arenaPlane).width(arenaPlane.getWidth()).height(arenaPlane.getHeight());
                pane.setActor(arenaTable);
            } else {
                arenaTable.add(Controls.newTextraLabel("[;][%150]" + GameScene.instance().getAdventurePlayerLocation(true, true) + " Arena")).colspan(3).top();
                arenaTable.row();
                int size = fighters.size;
                int pv = 0;
                for (int x = 0; x < size; x++) {
                    ArenaRecord record = fighters.get(x);
                    int divider = size == 1 ? 2 : size == 2 ? 3 : size;
                    arenaTable.add(record.actor).pad(20, 5, 20, 5).size(pane.getWidth() / divider);
                    pv++;
                    if (pv == 1) {
                        if (size > 1)
                            arenaTable.add(Controls.newTextraLabel("[%135]VS")).padLeft(5).padRight(5);
                        else {
                            arenaTable.row();
                            arenaTable.add(Controls.newTextraLabel("[%135]Winner!")).padLeft(5).padRight(5);
                        }
                    }
                    if (pv == 2) {
                        arenaTable.row();
                        pv = 0;
                    }

                }
                pane.setActor(arenaTable);
            }
        }
    }

    class ArenaRecord {
        Actor actor;
        String name;

        ArenaRecord(Actor a, String n) {
            actor = a;
            name = n;
        }
    }
}
