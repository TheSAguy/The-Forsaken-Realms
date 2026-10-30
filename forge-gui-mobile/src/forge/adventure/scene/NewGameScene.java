package forge.adventure.scene;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Array;

import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.adventure.data.DialogData;
import forge.adventure.data.DifficultyData;
import forge.adventure.data.HeroListData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.*;
import forge.adventure.world.WorldSave;
import forge.card.CardEdition;
import forge.card.ColorSet;
import forge.deck.DeckProxy;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.screens.TransitionScreen;
import forge.sound.SoundSystem;
import forge.util.Localizer;
import forge.util.NameGenerator;

import java.util.Random;

/**
 * NewGame scene that contains the character creation
 */
public class NewGameScene extends MenuScene {

    TextField selectedName;
    ColorSet[] colorIds;
    CardEdition[] editionIds;
    private final Image avatarImage;
    private int avatarIndex = 0;
    private final Selector race;
    /** Round 322: the race selector's (alphabetical) position -> the heroes.json index - see the constructor. */
    private int[] raceOrder;
    private final Selector colorId;
    private final Selector gender;
    private final Selector mode;
    private final Selector difficulty;
    private final Selector starterEdition;
    private final TextraLabel starterEditionLabel;
    private final Array<String> custom;
    private final TextraLabel colorLabel;
    private final ImageButton difficultyHelp;
    private DialogData difficultySummary;
    private final ImageButton modeHelp;
    private DialogData modeSummary;
    private final ImageButton raceHelp;
    private DialogData raceSummary;
    private final Random rand = new Random();
    private String originalEditionLabelText;
    private Array<String> originalEditionNames;
    /** Round 150: parallel to the Standard mode dropdown once it lists the RACE's own sets. The
     *  last slot is null, meaning "all of them" - see updateStarterEditionListForRace(). */
    private CardEdition[] raceEditionIds;

    private final Array<AdventureModes> modes = new Array<>();
    /** Round 873: the "Starting sets" row (a Table in the layout) and its drop-downs, one per starting set. */
    private final Table startingSets;
    private final java.util.List<SelectBox<SetChoice>> setBoxes = new java.util.ArrayList<>();
    private String[] setPool = new String[0];
    private int setCount = -1;
    private boolean fillingSetBoxes;
    /** Round 882: a Doppelganger's lock icons, one per box; empty for every other race. */
    private final java.util.List<Image> setLocks = new java.util.ArrayList<>();
    private int openSets = -1; // round 882: how many of a Doppelganger's boxes start open now (the agent reads it)

    private NewGameScene() {
        super(Forge.isLandscapeMode() ? "ui/new_game.json" : "ui/new_game_portrait.json");
        gender = ui.findActor("gender");
        selectedName = ui.findActor("nameField");
        generateName();
        avatarImage = ui.findActor("avatarPreview");
        mode = ui.findActor("mode");
        modeHelp = ui.findActor("modeHelp");
        colorLabel = ui.findActor("colorIdL");
        String colorIdLabel = colorLabel.storedText;
        String deckLabel = "[BLACK]" + Forge.getLocalizer().getMessage("lblDeck") + ":";
        custom = new Array<>();
        colorId = ui.findActor("colorId");
        String[] colorSet = Config.instance().colorIds();
        String[] colorIdNames = Config.instance().colorIdNames();
        colorIds = new ColorSet[colorSet.length];
        for (int i = 0; i < colorIds.length; i++)
            colorIds[i] = ColorSet.fromNames(colorSet[i].toCharArray());
        Array<String> colorNames = new Array<>(colorIds.length);
        for (String idName : colorIdNames)
            colorNames.add(UIActor.localize(idName));
        colorId.setTextList(colorNames);

        for (DifficultyData diff : Config.instance().getConfigData().difficulties)//check first difficulty if exists
        {
            if (diff.starterDecks != null) {
                modes.add(AdventureModes.Standard);
                AdventureModes.Standard.setSelectionName(colorIdLabel);
                AdventureModes.Standard.setModes(colorNames);
            }

            if (diff.constructedStarterDecks != null) {
                modes.add(AdventureModes.Constructed);
                AdventureModes.Constructed.setSelectionName(colorIdLabel);
                AdventureModes.Constructed.setModes(colorNames);
            }
            if (diff.pileDecks != null) {
                modes.add(AdventureModes.Pile);
                AdventureModes.Pile.setSelectionName(colorIdLabel);
                AdventureModes.Pile.setModes(colorNames);
            }
            if (diff.commanderDecks != null) {
                modes.add(AdventureModes.Commander);
                AdventureModes.Commander.setSelectionName(colorIdLabel);
                AdventureModes.Commander.setModes(colorNames);
            }
            break;
        }

        starterEdition = ui.findActor("starterEdition");
        starterEditionLabel = ui.findActor("starterEditionL");
        originalEditionLabelText = starterEditionLabel.storedText;
        String[] starterEditions = Config.instance().starterEditions();
        String[] starterEditionNames = Config.instance().starterEditionNames();
        editionIds = new CardEdition[starterEditions.length];
        for (int i = 0; i < editionIds.length; i++)
            editionIds[i] = FModel.getMagicDb().getEditions().get(starterEditions[i]);
        originalEditionNames = new Array<>(editionIds.length);
        for (String editionName : starterEditionNames)
            originalEditionNames.add(UIActor.localize(editionName));
        starterEdition.setTextList(originalEditionNames);

        // Precon mode: deck names in colorId, set filter in starterEdition
        if (Config.instance().hasPreconDecks()) {
            modes.add(AdventureModes.Precon);
            AdventureModes.Precon.setSelectionName(deckLabel);
            AdventureModes.Precon.setModes(Config.instance().filterPreconDecks(0));
        }

        if (Config.instance().hasCommanderPreconDecks()) {
            modes.add(AdventureModes.CommanderPrecon);
            AdventureModes.CommanderPrecon.setSelectionName(deckLabel);
            AdventureModes.CommanderPrecon.setModes(Config.instance().filterCommanderPreconDecks(0));
        }

        modes.add(AdventureModes.Chaos);
        AdventureModes.Chaos.setSelectionName(deckLabel);
        AdventureModes.Chaos.setModes(new Array<>(new String[]{Forge.getLocalizer().getMessage("lblRandomDeck")}));
        for (DeckProxy deckProxy : DeckProxy.getAllCustomStarterDecks())
            custom.add(deckProxy.getName());
        if (!custom.isEmpty()) {
            modes.add(AdventureModes.Custom);
            AdventureModes.Custom.setSelectionName(deckLabel);
            AdventureModes.Custom.setModes(custom);
        }

        String[] modeNames = new String[modes.size];
        int constructedIndex = -1;

        for (int i = 0; i < modes.size; i++) {
            modeNames[i] = modes.get(i).getName();
            if (modes.get(i) == AdventureModes.Constructed) {
                constructedIndex = i;
            }
        }

        mode.setTextList(modeNames);
        mode.setCurrentIndex(constructedIndex != -1 ? constructedIndex : 0);

        AdventureModes initialMode = modes.get(mode.getCurrentIndex());
        updateModeSelectionState(initialMode);

        gender.setTextList(new String[]{Forge.getLocalizer().getMessage("lblMale") + "[%120][CYAN] \u2642",
                Forge.getLocalizer().getMessage("lblFemale") + "[%120][MAGENTA] \u2640"});
        gender.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                nameTT = 0.8f;
                super.clicked(event, x, y);
            }
        });
        gender.addListener(event -> NewGameScene.this.updateAvatar());

        mode.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent changeEvent, Actor actor) {
                updateModeSelectionState(modes.get(mode.getCurrentIndex()));
            }
        });
        starterEdition.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent changeEvent, Actor actor) {
                AdventureModes smode = modes.get(mode.getCurrentIndex());
                if (smode == AdventureModes.Precon) {
                    colorId.setTextList(Config.instance().filterPreconDecks(starterEdition.getCurrentIndex()));
                } else if (smode == AdventureModes.CommanderPrecon) {
                    colorId.setTextList(Config.instance().filterCommanderPreconDecks(starterEdition.getCurrentIndex()));
                }
            }
        });
        race = ui.findActor("race");
        race.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                avatarTT = 0.7f;
                super.clicked(event, x, y);
            }
        });
        race.addListener(event -> NewGameScene.this.updateAvatar());
        // Round 150: Standard's set list is the chosen race's own four expansions, so it has to be
        // rebuilt whenever the race changes.
        // Round 183 (code review D3): on a real CHANGE of the race selector, and only while Standard owns that
        // selector - a raw listener fired on every event (hover, touch-down) and would have overwritten the
        // Precon / Commander-Precon set list with the race's sets on a plane that ships precon decks.
        race.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (modes.get(mode.getCurrentIndex()) == AdventureModes.Standard)
                    NewGameScene.this.updateStarterEditionListForRace();
            }
        });
        // Round 322 (user: "Make the Race selection Alphabetical."): the selector lists the races alphabetically by
        // the name it shows, while everything downstream keeps the heroes.json INDEX - a save stores that index and
        // the list is append-only, so heroes.json itself is never reordered. selectedRaceIndex() maps back.
        Array<String> heroRaceNames = HeroListData.instance().getRaces();
        Integer[] byName = new Integer[heroRaceNames.size];
        for (int r = 0; r < byName.length; r++)
            byName[r] = r;
        java.util.Arrays.sort(byName, (a, b) -> heroRaceNames.get(a).compareToIgnoreCase(heroRaceNames.get(b)));
        raceOrder = new int[byName.length];
        Array<String> sortedRaceNames = new Array<>(byName.length);
        for (int r = 0; r < byName.length; r++) {
            raceOrder[r] = byName[r];
            sortedRaceNames.add(heroRaceNames.get(byName[r]));
        }
        race.setTextList(sortedRaceNames);
        raceHelp = ui.findActor("raceHelp");
        difficulty = ui.findActor("difficulty");
        difficultyHelp = ui.findActor("difficultyHelp");

        Array<String> diffList = new Array<>(colorIds.length);
        int i = 0;
        int startingDifficulty = 0;
        for (DifficultyData diff : Config.instance().getConfigData().difficulties) {
            if (diff.startingDifficulty)
                startingDifficulty = i;
            diffList.add(Forge.getLocalizer().getMessageorUseDefault("lbl" + diff.name, diff.name));
            i++;
        }
        difficulty.setTextList(diffList);
        difficulty.setCurrentIndex(startingDifficulty);
        difficultyNames = diffList; // round 823: the presets' own names, "Custom (Hard)" goes over them

        generateAvatar();
        gender.setCurrentIndex(rand.nextInt());
        colorId.setCurrentIndex(rand.nextInt());
        race.setCurrentIndex(rand.nextInt());
        // Round 873: a new race or a new count puts every starting-set drop-down back to Random.
        startingSets = ui.findActor("startingSets");
        race.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                refreshStartingSets();
            }
        });
        difficulty.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                refreshStartingSets();
            }
        });
        refreshStartingSets();
        ui.onButtonPress("back", NewGameScene.this::back);
        ui.onButtonPress("start", NewGameScene.this::start);
        if (ui.findActor("advanced") != null) // round 823: the custom difficulty panel (landscape; portrait is round 824)
            ui.onButtonPress("advanced", NewGameScene.this::openAdvanced);
        ui.onButtonPress("leftAvatar", NewGameScene.this::leftAvatar);
        ui.onButtonPress("rightAvatar", NewGameScene.this::rightAvatar);
        difficultyHelp.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                showDifficultyHelp();
            }
        });
        raceHelp.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                showRaceHelp();
            }
        });
        modeHelp.addListener(new ClickListener() {
            public void clicked(InputEvent e, float x, float y) {
                showModeHelp();
            }
        });
    }

    // class field
    private RewardActor previewActor;

    /** Round 823: the presets' names as the difficulty box lists them without a custom difficulty. */
    private Array<String> difficultyNames;

    /** Round 823: the Advanced panel on the box's preset and the pending changes; Done brings the preset back. */
    private boolean openAdvanced() {
        CustomDifficultyScene.instance().open(difficulty.getCurrentIndex(), CustomDifficulty.pending(), index -> {
            difficulty.setCurrentIndex(index);
            refreshDifficultyLabels();
        });
        return true;
    }

    /** Round 823: with changes pending, every preset in the box reads "Custom (name)" - the changes go with any of them. */
    private void refreshDifficultyLabels() {
        if (difficultyNames == null)
            return;
        boolean custom = !CustomDifficulty.pending().isEmpty();
        Array<String> list = new Array<>(difficultyNames.size);
        for (String n : difficultyNames)
            list.add(custom ? "Custom (" + n + ")" : n);
        difficulty.setTextList(list);
        refreshStartingSets(); // round 873: the Starting sets knob may have changed the count
    }

    // ---- round 873: the starting sets --------------------------------------------------------------------------

    /** One drop-down entry: a set code, or null for Random. The open list shows "DTK  Dragons of Tarkir". */
    private static final class SetChoice {
        final String code;
        final String name;

        SetChoice(String code) {
            this.code = code;
            CardEdition e = code == null ? null : FModel.getMagicDb().getEditions().get(code);
            this.name = code == null ? "Random" : e != null ? e.getName() : code;
        }

        @Override
        public String toString() {
            // Round 879: a long name is cut, so the open list fits a portrait screen ("Dungeons & Dragons: Adventu...")
            return code == null ? name : code + "  " + (name.length() > 24 ? name.substring(0, 21).trim() + "..." : name);
        }
    }

    /** The difficulty the box shows, with the pending custom changes on - what a Start would create. */
    private DifficultyData shownDifficulty() {
        return CustomDifficulty.withOverrides(CustomDifficulty.pending(),
                Config.instance().getConfigData().difficulties[difficulty.getCurrentIndex()]);
    }

    /**
     * Rebuilds the row when the race's sets or the count changed (all on Random); otherwise leaves the picks alone.
     * Round 882: a Doppelganger always has four boxes, so a new difficulty only moves its locks and keeps its picks.
     */
    private void refreshStartingSets() {
        if (startingSets == null || race == null || difficulty == null)
            return;
        java.util.List<String> pool = new java.util.ArrayList<>();
        if (Config.instance().getConfigData().editionProgressionEnabled) {
            String[] raw = AdventurePlayer.startingSetPool(selectedRaceIndex());
            if (raw != null)
                for (String code : raw)
                    if (!"(All)".equals(code) && !pool.contains(code))
                        pool.add(code);
        }
        boolean anySet = forge.adventure.util.EditionProgression.isAnySetRace(selectedRaceIndex());
        int open = Math.min(pool.size(), AdventurePlayer.startingSetCount(shownDifficulty()));
        int count = anySet ? Math.min(pool.size(), AdventurePlayer.RACE_SET_COUNT) : open;
        String[] newPool = pool.toArray(new String[0]);
        if (count == setCount && java.util.Arrays.equals(newPool, setPool)) {
            showSetLocks(open);
            return;
        }
        setPool = newPool;
        setCount = count;
        startingSets.clearChildren();
        setBoxes.clear();
        setLocks.clear();
        startingSets.align(anySet ? com.badlogic.gdx.utils.Align.left : com.badlogic.gdx.utils.Align.center);
        startingSets.padLeft(!anySet ? 0f : Forge.isLandscapeMode() ? -16f : -8f);
        if (pool.isEmpty())
            return;
        if (!anySet && count >= pool.size()) { // Easy: every set of the race, nothing to choose
            startingSets.add(Controls.newTextraLabel("[BLACK]All " + pool.size() + " sets")).expand().center();
            return;
        }
        float gap = Forge.isLandscapeMode() ? 4f : 3f;
        float w = (startingSets.getWidth() - gap * (count - 1)) / count;
        // Round 882: a Doppelganger's row starts left of the layout's Table (the padLeft above) and is wider, so the other
        // races' row stays as it was: landscape x 143-313, portrait x 88-256, four boxes and their locks.
        float lockW = Forge.isLandscapeMode() ? 8f : 9f;
        if (anySet) {
            gap = 2f;
            w = ((Forge.isLandscapeMode() ? 170f : 168f) - gap * (count - 1)) / count - lockW - 1f;
        }
        for (int i = 0; i < count; i++) {
            SelectBox<SetChoice> box = newSetBox(count > 1);
            box.setName("startingSet" + (i + 1)); // the agent clicks it by name
            box.setMaxListCount(MAX_SET_LIST_ROWS); // round 879: a Doppelganger's long list scrolls
            box.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    if (!fillingSetBoxes)
                        fillSetBoxes();
                }
            });
            setBoxes.add(box);
            if (!anySet) {
                startingSets.add(box).width(w).height(startingSets.getHeight()).padRight(i < count - 1 ? gap : 0);
                continue;
            }
            startingSets.add(box).width(w).height(startingSets.getHeight()).padRight(1f);
            Image lock = new Image();
            lock.setName("startingSetLock" + (i + 1));
            lock.setScaling(com.badlogic.gdx.utils.Scaling.fit);
            lock.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.disabled);
            setLocks.add(lock);
            startingSets.add(lock).width(lockW).height(startingSets.getHeight()).padRight(i < count - 1 ? gap : 0);
        }
        fillSetBoxes();
        showSetLocks(open);
    }

    /** Round 882: each lock icon shows whether its box starts open; a locked box can still be changed. */
    private void showSetLocks(int open) {
        openSets = open;
        for (int i = 0; i < setLocks.size(); i++)
            setLocks.get(i).setDrawable(setLockIcon(AdventurePlayer.raceSetStartsOpen(i, open)));
    }

    /**
     * Round 882: the two icons. The closed one is the stock padlock, cropped: that sprite is a wide gray plate in the top
     * half of its cell, and whole it draws too small to read here. The game has no open padlock, so the open one is the
     * skin's checkbox tick until Art makes one (swap it here).
     */
    private static Drawable setLockIcon(boolean open) {
        if (open)
            return Controls.getSkin().getDrawable("check");
        com.badlogic.gdx.graphics.g2d.TextureRegion r = forge.assets.FSkinImage.PADLOCK.getTextureRegion();
        int w = r.getRegionWidth(), h = r.getRegionHeight();
        return new TextureRegionDrawable(new com.badlogic.gdx.graphics.g2d.TextureRegion(r,
                w * 36 / 143, h * 6 / 200, w * 70 / 143, h * 87 / 200));
    }

    /** Round 879: the open list's height in rows; a longer pool (the Doppelganger's) scrolls. */
    private static final int MAX_SET_LIST_ROWS = 10;

    /** Each drop-down lists Random and the race's sets that no other drop-down holds. */
    private void fillSetBoxes() {
        fillingSetBoxes = true;
        try {
            for (SelectBox<SetChoice> box : setBoxes) {
                String own = box.getSelected() == null ? null : box.getSelected().code;
                Array<SetChoice> items = new Array<>();
                SetChoice keep = new SetChoice(null);
                items.add(keep);
                for (String code : setPool) {
                    boolean held = false;
                    for (SelectBox<SetChoice> other : setBoxes)
                        if (other != box && other.getSelected() != null && code.equals(other.getSelected().code))
                            held = true;
                    if (held)
                        continue;
                    SetChoice c = new SetChoice(code);
                    items.add(c);
                    if (code.equals(own))
                        keep = c;
                }
                box.setItems(items);
                box.setSelected(keep);
            }
        } finally {
            fillingSetBoxes = false;
        }
    }

    /** The codes the drop-downs picked (Random left out). */
    private java.util.List<String> chosenStartingSets() {
        java.util.List<String> codes = new java.util.ArrayList<>(boxStartingSets());
        codes.removeIf(java.util.Objects::isNull);
        return codes;
    }

    /** Round 882: the drop-downs in box order, null for an "Any" - a Doppelganger's boxes keep their places. */
    private java.util.List<String> boxStartingSets() {
        java.util.List<String> codes = new java.util.ArrayList<>();
        for (SelectBox<SetChoice> box : setBoxes)
            codes.add(box.getSelected() == null ? null : box.getSelected().code);
        return codes;
    }

    /** Round 873: the agent bridge's {@code startingsets sets=DTK,AFR} - sets the drop-downs in order ("" or "any" = Random).
     *  Round 879: a last "open" opens the first drop-down's list, for a screenshot of it. Round 882: "open4" the fourth's. */
    String selectStartingSetsForAgent(String csv) {
        int openBox = -1;
        int openAt = csv == null ? -1 : csv.toLowerCase().lastIndexOf("open");
        if (openAt >= 0) {
            String which = csv.substring(openAt + 4);
            openBox = which.isEmpty() ? 0 : Integer.parseInt(which) - 1;
            csv = csv.substring(0, openAt).replaceAll(",$", "");
        }
        String[] want = csv == null || csv.isEmpty() ? new String[0] : csv.split(",");
        for (int i = 0; i < setBoxes.size(); i++) {
            String code = i < want.length ? want[i].trim().toUpperCase() : "";
            SelectBox<SetChoice> box = setBoxes.get(i);
            SetChoice pick = box.getItems().first(); // Random
            for (SetChoice c : box.getItems())
                if (c.code != null && c.code.equals(code))
                    pick = c;
            box.setSelected(pick); // fires the box's ChangeListener, which refills the others
        }
        if (openBox >= 0 && openBox < setBoxes.size())
            setBoxes.get(openBox).showScrollPane();
        return "starting sets " + boxStartingSets() + " of " + setCount + (setLocks.isEmpty() ? "" : ", " + openSets + " open")
                + " (" + java.util.Arrays.toString(setPool) + ")";
    }

    /** Hands the picks to AdventurePlayer.create() and logs them. */
    private void takeStartingSets() {
        java.util.List<String> codes = chosenStartingSets();
        // Round 879: a Doppelganger rolls its four here. Round 882: from its boxes, so an "Any" keeps its place.
        AdventurePlayer.setChosenStartingSets(setLocks.isEmpty() ? codes : boxStartingSets(), selectedRaceIndex());
        System.out.println("[TFR-Research] new-game screen: " + setCount + " starting set(s) of " + java.util.Arrays.toString(setPool)
                + " - chosen " + codes + ", random " + Math.max(0, setCount - codes.size()));
    }

    /**
     * A drop-down in Controls.newComboBox's look. A narrow one (2-3 in the row) shows the code or "Any" in the box; the
     * open list always shows "DTK  Dragons of Tarkir".
     */
    private static SelectBox<SetChoice> newSetBox(boolean narrow) {
        SelectBox<SetChoice> box = new SelectBox<SetChoice>(Controls.getSkin()) {
            @Override
            protected Drawable getBackgroundDrawable() {
                if (isDisabled() && getStyle().backgroundDisabled != null)
                    return getStyle().backgroundDisabled;
                if (getScrollPane().hasParent() && getStyle().backgroundOpen != null)
                    return getStyle().backgroundOpen;
                return (isOver() || hasKeyboardFocus()) && getStyle().backgroundOver != null ? getStyle().backgroundOver : getStyle().background;
            }

            // Round 879: the open list's text - "DTK  Dragons of Tarkir", so a long pool (the Doppelganger's 58) reads
            // without knowing the codes. The box itself draws boxText below.
            @Override
            protected String toString(SetChoice c) {
                if (c == null)
                    return "";
                return c.code == null ? (narrow ? "Any" : "Random") : c.toString();
            }

            private String boxText(SetChoice c) {
                if (c == null)
                    return "";
                if (narrow)
                    return c.code == null ? "Any" : c.code;
                return c.code == null ? "Random" : c.name + " (" + c.code + ")";
            }

            @Override
            protected com.badlogic.gdx.graphics.g2d.GlyphLayout drawItem(com.badlogic.gdx.graphics.g2d.Batch batch,
                    com.badlogic.gdx.graphics.g2d.BitmapFont font, SetChoice item, float x, float y, float width) {
                String s = boxText(item);
                // Round 882: a code is never cut - a Doppelganger's four boxes leave barely its own width
                return font.draw(batch, s, x, y, 0, s.length(), width, com.badlogic.gdx.utils.Align.center, false, narrow ? null : "...");
            }

            // Round 879: the list is as wide as its longest name; one opened from the right-hand box stays on screen
            @Override
            protected SelectBoxScrollPane<SetChoice> newScrollPane() {
                return new SelectBoxScrollPane<SetChoice>(this) {
                    @Override
                    public void show(com.badlogic.gdx.scenes.scene2d.Stage stage) {
                        super.show(stage);
                        if (getX() + getWidth() > stage.getWidth())
                            setX(Math.max(0, stage.getWidth() - getWidth()));
                    }
                };
            }
        };
        box.setAlignment(com.badlogic.gdx.utils.Align.center);
        box.getList().setAlignment(com.badlogic.gdx.utils.Align.left);
        return box;
    }

    private static NewGameScene object;

    public static NewGameScene instance() {
        if (object == null)
            object = new NewGameScene();
        return object;
    }

    float avatarT = 1f, avatarTT = 1f;
    float nameT = 1f, nameTT = 1f;

    @Override
    public void act(float delta) {
        super.act(delta);
        if (avatarT > avatarTT) {
            avatarTT += (delta / 0.5f);
            generateAvatar();
        } else {
            avatarTT = avatarT;
        }
        if (nameT > nameTT) {
            nameTT += (delta / 0.5f);
            generateName();
        } else {
            nameTT = nameT;
        }
    }

    private void generateAvatar() {
        avatarIndex = rand.nextInt();
        updateAvatar();
    }

    private void generateName() {
        //gender should be either Male or Female
        String val = gender.getCurrentIndex() > 0 ? "Female" : "Male";
        selectedName.setText(NameGenerator.getRandomName(val, "Any", ""));
    }

    private void updateModeSelectionState(AdventureModes selectedMode) {
        colorLabel.setText(selectedMode.getSelectionName());
        boolean showEdition = selectedMode.usesStarterEditionSelector();
        starterEdition.setVisible(showEdition);
        starterEditionLabel.setVisible(showEdition);

        if (selectedMode == AdventureModes.Precon) {
            starterEdition.setTextList(Config.instance().getPreconSetNames());
            starterEditionLabel.setText("[BLACK]" + Forge.getLocalizer().getMessageorUseDefault("lblEdition", "Edition") + ":");
            colorId.setTextList(Config.instance().filterPreconDecks(starterEdition.getCurrentIndex()));
        } else if (selectedMode == AdventureModes.CommanderPrecon) {
            starterEdition.setTextList(Config.instance().getCommanderPreconSetNames());
            starterEditionLabel.setText("[BLACK]" + Forge.getLocalizer().getMessageorUseDefault("lblEdition", "Edition") + ":");
            colorId.setTextList(Config.instance().filterCommanderPreconDecks(starterEdition.getCurrentIndex()));
        } else if (selectedMode == AdventureModes.Standard) {
            starterEditionLabel.setText(originalEditionLabelText);
            updateStarterEditionListForRace();
            colorId.setTextList(selectedMode.getModes());
        } else {
            colorId.setTextList(selectedMode.getModes());
        }
    }

    /**
     * The color the player ACTUALLY picked, as the plane's own color id ("W"/"U"/"B"/"R"/"G"),
     * or null when the mode has no real pick. Deliberately separate from getStartingColor()
     * below: that returns a hardcoded White for Chaos/folder-deck modes and colorIds[0] for
     * Custom, which is fine for choosing a starter deck but must NOT be recorded as a deliberate
     * color choice - otherwise every Chaos player would be handed White's starting shops.
     * Feeds the shop-type blueprint seeding (AdventurePlayer.seedStartingShopTypes).
     */
    private String getStartingColorId() {
        AdventureModes currentMode = modes.get(mode.getCurrentIndex());
        if (currentMode.usesFolderDeckPicker() || currentMode == AdventureModes.Chaos
                || currentMode == AdventureModes.Custom)
            return null;
        String[] colorSet = Config.instance().colorIds();
        int idx = colorId.getCurrentIndex();
        return colorSet != null && idx >= 0 && idx < colorSet.length ? colorSet[idx] : null;
    }

    private ColorSet getStartingColor() {
        AdventureModes currentMode = modes.get(mode.getCurrentIndex());
        if (currentMode.usesFolderDeckPicker() || currentMode == AdventureModes.Chaos) {
            return ColorSet.fromNames("W".toCharArray());
        }
        if (currentMode == AdventureModes.Custom) {
            return colorIds[0];
        }
        int idx = colorId.getCurrentIndex();
        return colorIds[idx < colorIds.length ? idx : 0];
    }

    /**
     * Round 150. Standard's set dropdown lists the chosen RACE's own expansions plus an "all of
     * them" entry, rather than a fixed plane-wide list that had almost nothing in common with any
     * race's sets (of the nine, only DMU, BRO and ONE appear in a race table at all). Falls back to
     * the plane list for a race with no entry, which is also what the starter deck itself does.
     */
    private void updateStarterEditionListForRace() {
        if (race == null || starterEdition == null)
            return;
        // Round 879: a Doppelganger lists its whole pool; "(All my sets)" is the four its Start rolls.
        java.util.List<String> codes = forge.adventure.util.EditionProgression.isAnySetRace(selectedRaceIndex())
                ? java.util.Arrays.asList(AdventurePlayer.startingSetPool(selectedRaceIndex()))
                : forge.adventure.util.EditionProgression.raceEditionCodes(selectedRaceIndex());
        if (codes == null || codes.isEmpty()) {
            raceEditionIds = null;
            starterEdition.setTextList(originalEditionNames);
            return;
        }
        Array<String> names = new Array<>(codes.size() + 1);
        raceEditionIds = new CardEdition[codes.size() + 1];
        for (int i = 0; i < codes.size(); i++) {
            CardEdition edition = FModel.getMagicDb().getEditions().get(codes.get(i));
            raceEditionIds[i] = edition;
            names.add(edition != null ? edition.getName() : codes.get(i));
        }
        raceEditionIds[codes.size()] = null; // "all of them" - no single-set narrowing
        names.add(Forge.getLocalizer().getMessageorUseDefault("lblAllMySets", "(All my sets)"));
        starterEdition.setTextList(names);
    }

    private CardEdition getStartingEdition() {
        AdventureModes currentMode = modes.get(mode.getCurrentIndex());
        if (currentMode == AdventureModes.Standard) {
            int idx = starterEdition.getCurrentIndex();
            // Race-scoped list: a null entry is the deliberate "all of them" pick, which
            // Config.starterDeck reads as "use every one of this race's sets".
            if (raceEditionIds != null)
                return raceEditionIds[idx < raceEditionIds.length ? idx : raceEditionIds.length - 1];
            if (editionIds.length > 0)
                return editionIds[idx < editionIds.length ? idx : 0];
        }
        return editionIds.length > 0 ? editionIds[0] : null;
    }

    /** Round 322: the heroes.json index of the race the (alphabetical) selector shows. */
    private int selectedRaceIndex() {
        int shown = race.getCurrentIndex();
        return raceOrder == null || shown < 0 || shown >= raceOrder.length ? shown : raceOrder[shown];
    }

    /** Round 317: the agent bridge's {@code newgame race=...} - selects the race by its listed name (setCurrentIndex
     *  fires the selector's change listeners, so the avatar and the Standard set list follow). */
    boolean selectRaceForAgent(String name) {
        Array<String> names = race.getTextList();
        for (int i = 0; i < names.size; i++) {
            if (names.get(i).equalsIgnoreCase(name)) {
                race.setCurrentIndex(i);
                return true;
            }
        }
        System.out.println("[TFR-Agent] newgame: no race named \"" + name + "\" in " + names);
        return false;
    }

    /** Round 317: the agent bridge's {@code newgame gender=...} (index 0 = male, 1 = female, as the selector lists). */
    void selectGenderForAgent(boolean female) {
        gender.setCurrentIndex(female ? 1 : 0);
    }

    boolean started = false;

    public boolean start() {
        if (started)
            return true;
        started = true;
        if (selectedName.getText().isEmpty()) {
            generateName();
        }
        takeStartingSets(); // round 873
        Runnable runnable = () -> {
            started = false;
            //FModel.getPreferences().setPref(ForgePreferences.FPref.UI_ENABLE_MUSIC, false);
            WorldSave.generateNewWorld(selectedName.getText(),
                    gender.getCurrentIndex() == 0,
                    selectedRaceIndex(),
                    avatarIndex,
                    getStartingColor(),
                    Config.instance().getConfigData().difficulties[difficulty.getCurrentIndex()],
                    modes.get(mode.getCurrentIndex()), colorId.getCurrentIndex(),
                    getStartingEdition(), 0, getStartingColorId());
            GamePlayerUtil.getGuiPlayer().setName(selectedName.getText());
            SoundSystem.instance.changeBackgroundTrack();
            WorldStage.getInstance().enterSpawnPOI();
            if (AdventurePlayer.current().getQuests().stream().noneMatch(q -> q.getID() == 28)) {
                AdventurePlayer.current().addQuest("28", true); //Temporary link to Shandalar main questline
            }
            Forge.switchScene(GameScene.instance());
        };
        Forge.setTransitionScreen(new TransitionScreen(runnable, null, false, true, Forge.getLocalizer().getMessage("lblGeneratingWorld")));
        return true;
    }

    public boolean back() {
        Forge.switchScene(StartScene.instance());
        return true;
    }


    private void rightAvatar() {

        avatarIndex++;
        updateAvatar();
    }

    private void leftAvatar() {
        avatarIndex--;
        updateAvatar();
    }

    private boolean updateAvatar() {
        avatarImage.setDrawable(new TextureRegionDrawable(HeroListData.instance().getAvatar(selectedRaceIndex(), gender.getCurrentIndex() != 0, avatarIndex)));
        return false;
    }


    @Override
    public void enter() {
        updateAvatar();
        refreshDifficultyLabels(); // round 823: a started game took the pending changes
        if (Forge.createNewAdventureMap) {
            takeStartingSets(); // round 873
            FModel.getPreferences().setPref(ForgePreferences.FPref.UI_ENABLE_MUSIC, false);
            WorldSave.generateNewWorld(selectedName.getText(),
                    gender.getCurrentIndex() == 0,
                    selectedRaceIndex(),
                    avatarIndex,
                    getStartingColor(),
                    Config.instance().getConfigData().difficulties[difficulty.getCurrentIndex()],
                    modes.get(mode.getCurrentIndex()), colorId.getCurrentIndex(),
                    getStartingEdition(), 0, getStartingColorId());
            GamePlayerUtil.getGuiPlayer().setName(selectedName.getText());
            Forge.switchScene(GameScene.instance());
        }

        unselectActors();
        super.enter();
    }

    // Race Help (2026-08-15 user request, mirroring the existing difficultyHelp/modeHelp "?"
    // pattern): reads race/difficulty selection live at click time, same as showDifficultyHelp()
    // does below - no separate change-listener needed. Race->set lookup and the difficulty->count
    // count come from AdventurePlayer's own startingSetPool/startingSetCount (round 872), so this help
    // text can't drift from what a new game will actually do.
    private void showRaceHelp() {
        DialogData dismiss = new DialogData();
        dismiss.name = "OK";

        raceSummary = new DialogData();
        raceSummary.name = "Summary";

        if (!Config.instance().getConfigData().editionProgressionEnabled) {
            raceSummary.text = "Progressive Set Unlocks aren't enabled for this world - every "
                    + "card set is available from the start, regardless of race.";
        } else {
            // Round 879: the same pool the Starting sets row lists (the Doppelganger's is every race's sets)
            String[] pool = AdventurePlayer.startingSetPool(selectedRaceIndex());

            String setList = pool == null || pool.length == 0 ? "None" : String.join(", ", pool);
            int poolSize = pool == null ? 0 : pool.length;
            DifficultyData selectedDifficulty = Config.instance().getConfigData().difficulties[difficulty.getCurrentIndex()];
            // Round 872: the count is the difficulty's startingSets (or the Starting sets custom knob)
            int startingUnlockCount = Math.min(poolSize, AdventurePlayer.startingSetCount(shownDifficulty()));

            if (forge.adventure.util.EditionProgression.isAnySetRace(selectedRaceIndex()))
                raceSummary.text = String.format(
                        "Race: %s\nNo sets of its own: pick any four of the %d sets the other races start with under Starting sets, or leave Any for a random one. They are its four sets this game, and your starter deck is built from them.\n\nAt %s difficulty, %s unlocked (the check marks). The padlocked ones stay yours and unlock later at the Research Lab.",
                        race.getText(), poolSize, selectedDifficulty.name,
                        startingUnlockCount >= AdventurePlayer.RACE_SET_COUNT ? "all four start" : startingUnlockCount == 1 ? "only the first box starts"
                                : "the first " + startingUnlockCount + " boxes start");
            else
                raceSummary.text = String.format(
                        "Race: %s\nAssociated Sets: %s\n\nAt %s difficulty, you'll start with %d of these %d sets unlocked - pick them under Starting sets, or leave Random - the rest unlock later at the Research Lab.",
                        race.getText(), setList, selectedDifficulty.name, startingUnlockCount, poolSize);
        }

        raceSummary.options = new DialogData[1];
        raceSummary.options[0] = dismiss;
        loadDialog(raceSummary);
    }

    private void showDifficultyHelp() {
        Localizer localizer = Forge.getLocalizer();
        // Round 823: a custom difficulty's own values - the preset with the pending changes on, and a preview of the
        // settings they give (never put in place: a running game behind this screen keeps its own).
        java.util.Map<String, String> pendingCustom = CustomDifficulty.pending();
        DifficultyData selectedDifficulty = CustomDifficulty.withOverrides(pendingCustom,
                Config.instance().getConfigData().difficulties[difficulty.getCurrentIndex()]);
        String shownName = pendingCustom.isEmpty() ? selectedDifficulty.name : "Custom (" + selectedDifficulty.name + ")";
        boolean enableGeneticAI = Config.instance().getConfigData().enableGeneticAI;
        String startingEquipment = selectedDifficulty.startItems == null || selectedDifficulty.startItems.length == 0
                ? localizer.getMessage("lblNone")
                : String.join(", ", selectedDifficulty.startItems);

        difficultySummary = new DialogData();
        difficultySummary.name = localizer.getMessage("lblSummary");
        switch (selectedDifficulty.name) {
            case "Easy":
                difficultySummary.text = localizer.getMessage("advDifficultySummaryEasy", selectedDifficulty.name, startingEquipment);
                break;
            case "Normal":
                difficultySummary.text = localizer.getMessage("advDifficultySummaryNormal", selectedDifficulty.name, startingEquipment);
                break;
            case "Hard":
                if (enableGeneticAI) {
                    difficultySummary.text = localizer.getMessage("advDifficultySummaryHardGenetic", selectedDifficulty.name, startingEquipment);
                } else {
                    difficultySummary.text = localizer.getMessage("advDifficultySummaryHard", selectedDifficulty.name, startingEquipment);
                }
                break;
            case "Insane":
                difficultySummary.text = localizer.getMessage("advDifficultySummaryInsane", selectedDifficulty.name, startingEquipment);
                break;
            default:
                difficultySummary.text = localizer.getMessage("advDifficultySummaryCustom");
                break;
        }
        if (!pendingCustom.isEmpty()) // round 823
            difficultySummary.text += "\n\n[GOLD]Custom difficulty: " + pendingCustom.size()
                    + " setting(s) changed in Advanced... - the tabs here show the values this game will start with.";


        DialogData dismiss = new DialogData();
        dismiss.name = localizer.getMessage("lblOK");

        DialogData matchImpacts = new DialogData();
        // Round 177: a plane with a flat defeat gold loss (settings.json) shows the flat amount instead of the percentage
        // (the resource glyph, as every other amount in the mod's UI; TypingLabel renders it).
        forge.adventure.data.TuningData tuning = CustomDifficulty.previewTuning(pendingCustom); // round 823 (was getTuningData())
        // Round 359: the percentage with the flat amount as its minimum. Round 785: the minimum is waived in the first
        // defeatGoldLossMinWaivedWeeks weeks (TFR: week 1), and the text says so.
        int flatGoldLoss = tuning == null ? 0 : tuning.defeatGoldLossFor(selectedDifficulty.name);
        float goldLossPercent = tuning == null ? 0f : tuning.defeatGoldLossPercentFor(selectedDifficulty.name);
        String goldLossText;
        if (flatGoldLoss <= 0 && goldLossPercent <= 0f)
            goldLossText = (int) (selectedDifficulty.goldLoss * 100) + "%";
        else if (goldLossPercent <= 0f)
            goldLossText = flatGoldLoss + " [+Gold] (all of it if you carry less)"
                    + (tuning.defeatGoldLossMinWaivedWeeks > 0 ? " - none in week "
                    + (tuning.defeatGoldLossMinWaivedWeeks == 1 ? "1" : "1-" + tuning.defeatGoldLossMinWaivedWeeks) : "");
        else
            goldLossText = new java.text.DecimalFormat("0.#").format(goldLossPercent) + "%"
                    + (flatGoldLoss > 0 ? ", at least " + flatGoldLoss + " [+Gold] (all of it if you carry less)"
                    + (tuning.defeatGoldLossMinWaivedWeeks > 0 ? " - no minimum in week "
                    + (tuning.defeatGoldLossMinWaivedWeeks == 1 ? "1" : "1-" + tuning.defeatGoldLossMinWaivedWeeks) : "") : "");
        matchImpacts.text = String.format("Difficulty: %s\nStarting Life: %d\nEnemy Health: %d%%\nGold loss on defeat: %s\nLife loss on defeat: %d%%", shownName, selectedDifficulty.startingLife, (int) (selectedDifficulty.enemyLifeFactor * 100), goldLossText, (int) (selectedDifficulty.lifeLoss * 100));
        matchImpacts.name = "Duels";

        DialogData economyImpacts = new DialogData();
        economyImpacts.text = localizer.getMessage("advDifficultyEconomyImpacts", shownName, selectedDifficulty.startingMoney + " [+Gold]", selectedDifficulty.startingShards + " [+Shards]", (int) (selectedDifficulty.sellFactor * 100), (int) (selectedDifficulty.shardSellRatio * 100), (int) (selectedDifficulty.rewardMaxFactor * 100));
        economyImpacts.name = localizer.getMessage("lblEconomy");

        // Territory tab (2026-08-15, onboarding review finding: Mod Details' own "Difficulty"
        // section already claims difficulty "scales how many mages a color can field against you
        // at once... and how many towns you need to hold before earning each bonus attacking mage"
        // - but Mod Details can only be opened from a LIVE game (WorldStandingsScene needs
        // Current.world()), so a brand-new player choosing difficulty here had no way to see those
        // numbers before committing. Mirrors TerritoryControl.maxActiveMagesPerColor()'s own
        // formula exactly (base cap = 2 + difficultyIndex, one bonus mage per (11 - difficultyIndex)
        // towns held) rather than a separate hardcoded table, so the two can't drift apart.
        DialogData territoryImpacts = new DialogData();
        territoryImpacts.text = String.format("Difficulty: %s\nBase Mage Cap (vs you): %d\nTowns per Bonus Mage: %d\n(Full Territory Control details in World Standings > Mod Details, once your game has started.)",
                shownName, tuning == null ? 2 + difficulty.getCurrentIndex() : tuning.attackingMagesFor(selectedDifficulty.name),
                11 - difficulty.getCurrentIndex());
        territoryImpacts.name = "Territory";

        difficultySummary.options = new DialogData[4];
        difficultySummary.options[0] = matchImpacts;
        difficultySummary.options[1] = economyImpacts;
        difficultySummary.options[2] = territoryImpacts;
        difficultySummary.options[3] = dismiss;
        matchImpacts.options = new DialogData[4];
        matchImpacts.options[0] = difficultySummary;
        matchImpacts.options[1] = economyImpacts;
        matchImpacts.options[2] = territoryImpacts;
        matchImpacts.options[3] = dismiss;
        economyImpacts.options = new DialogData[4];
        economyImpacts.options[0] = difficultySummary;
        economyImpacts.options[1] = matchImpacts;
        economyImpacts.options[2] = territoryImpacts;
        economyImpacts.options[3] = dismiss;
        territoryImpacts.options = new DialogData[4];
        territoryImpacts.options[0] = difficultySummary;
        territoryImpacts.options[1] = matchImpacts;
        territoryImpacts.options[2] = economyImpacts;
        territoryImpacts.options[3] = dismiss;

        loadDialog(difficultySummary);
    }

    private void showModeHelp() {

        Localizer localizer = Forge.getLocalizer();
        AdventureModes selectedMode = modes.get(mode.getCurrentIndex());
        DifficultyData selectedDifficulty = Config.instance().getConfigData().difficulties[difficulty.getCurrentIndex()];
        boolean enableGeneticAI = Config.instance().getConfigData().enableGeneticAI;

        modeSummary = new DialogData();
        modeSummary.name = localizer.getMessage("lblSummary");

        StringBuilder summaryText = new StringBuilder();
        switch (selectedMode) {
            case Standard:
                summaryText.append(localizer.getMessage("advModeStandardSummary"));
                switch (selectedDifficulty.name) {
                    case "Easy":
                        summaryText.append(localizer.getMessage("advDiffEasyStandard"));
                        break;
                    case "Normal":
                        summaryText.append(localizer.getMessage("advDiffNormalStandard"));
                        break;
                    case "Hard":
                        summaryText.append(localizer.getMessage("advDiffHardStandard"));
                        break;
                    case "Insane":
                        summaryText.append(localizer.getMessage("advDiffInsaneStandard"));
                        break;
                    default:
                        difficultySummary.text = localizer.getMessage("advCannotDetermineStarterDeck");
                        break;
                }
                break;
            case Constructed:
                summaryText.append(localizer.getMessage("advModeConstructedSummary"));
                switch (selectedDifficulty.name) {
                    case "Easy":
                        summaryText.append(localizer.getMessage("advDiffEasyConstructed"));
                        break;
                    case "Normal":
                        summaryText.append(localizer.getMessage("advDiffNormalConstructed"));
                        break;
                    case "Hard":
                        summaryText.append(localizer.getMessage("advDiffHardConstructed"));
                        break;
                    case "Insane":
                        summaryText.append(localizer.getMessage("advDiffInsaneConstructed"));
                        break;
                    default:
                        difficultySummary.text = localizer.getMessage("advCannotDetermineStarterDeck");
                        break;
                }
                break;
            case Pile:
                summaryText.append(localizer.getMessage("advModePileSummary"));
                switch (selectedDifficulty.name) {
                    case "Easy":
                        summaryText.append(localizer.getMessage("advDiffEasyPile"));
                        break;
                    case "Normal":
                        summaryText.append(localizer.getMessage("advDiffNormalPile"));
                        break;
                    case "Hard":
                        summaryText.append(localizer.getMessage("advDiffHardPile"));
                        summaryText.append(localizer.getMessage("advPileLessRareReward"));
                        break;
                    case "Insane":
                        summaryText.append(localizer.getMessage("advDiffInsanePile"));
                        summaryText.append(localizer.getMessage("advPileLessRareReward"));
                        break;
                    default:
                        difficultySummary.text = localizer.getMessage("advCannotDetermineStarterDeck");
                        break;
                }
                break;
            case Chaos:
                summaryText.append(localizer.getMessage("advModeChaosSummary"));
                break;
            case Custom:
                if (enableGeneticAI) {
                    summaryText.append(localizer.getMessage("advModeCustomGeneticSummary"));
                } else {
                    summaryText.append(localizer.getMessage("advModeCustomSummary"));
                }
                break;
            case Precon:
                summaryText.append(localizer.getMessage("advModePreconSummary"));
                break;
            case Commander:
                summaryText.append(localizer.getMessage("advModeCommanderSummary"));
                break;
            case CommanderPrecon:
                summaryText.append(localizer.getMessage("advModeCommanderPreconSummary"));
                break;
            default:
                summaryText.append(localizer.getMessage("advNoModeSummaryAvailable"));
                break;
        }

        DialogData dismiss = new DialogData();
        dismiss.name = localizer.getMessage("lblOK");
        modeSummary.text = summaryText.toString();
        modeSummary.options = new DialogData[1];
        modeSummary.options[0] = dismiss;
        loadDialog(modeSummary);
    }
}
