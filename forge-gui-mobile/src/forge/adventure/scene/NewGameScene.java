package forge.adventure.scene;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton;
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
import forge.adventure.data.RaceEditionData;
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

    private final Array<AdventureModes> modes = new Array<>();

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
        race.setTextList(HeroListData.instance().getRaces());
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

        generateAvatar();
        gender.setCurrentIndex(rand.nextInt());
        colorId.setCurrentIndex(rand.nextInt());
        race.setCurrentIndex(rand.nextInt());
        ui.onButtonPress("back", NewGameScene.this::back);
        ui.onButtonPress("start", NewGameScene.this::start);
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
            starterEdition.setTextList(originalEditionNames);
            colorId.setTextList(selectedMode.getModes());
        } else {
            colorId.setTextList(selectedMode.getModes());
        }
    }

    /**
     * The colour the player ACTUALLY picked, as the plane's own colour id ("W"/"U"/"B"/"R"/"G"),
     * or null when the mode has no real pick. Deliberately separate from getStartingColor()
     * below: that returns a hardcoded White for Chaos/folder-deck modes and colorIds[0] for
     * Custom, which is fine for choosing a starter deck but must NOT be recorded as a deliberate
     * colour choice - otherwise every Chaos player would be handed White's starting shops.
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

    private CardEdition getStartingEdition() {
        AdventureModes currentMode = modes.get(mode.getCurrentIndex());
        if (currentMode == AdventureModes.Standard && editionIds.length > 0) {
            int idx = starterEdition.getCurrentIndex();
            return editionIds[idx < editionIds.length ? idx : 0];
        }
        return editionIds.length > 0 ? editionIds[0] : null;
    }

    boolean started = false;

    public boolean start() {
        if (started)
            return true;
        started = true;
        if (selectedName.getText().isEmpty()) {
            generateName();
        }
        Runnable runnable = () -> {
            started = false;
            //FModel.getPreferences().setPref(ForgePreferences.FPref.UI_ENABLE_MUSIC, false);
            WorldSave.generateNewWorld(selectedName.getText(),
                    gender.getCurrentIndex() == 0,
                    race.getCurrentIndex(),
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
        avatarImage.setDrawable(new TextureRegionDrawable(HeroListData.instance().getAvatar(race.getCurrentIndex(), gender.getCurrentIndex() != 0, avatarIndex)));
        return false;
    }


    @Override
    public void enter() {
        updateAvatar();
        if (Forge.createNewAdventureMap) {
            FModel.getPreferences().setPref(ForgePreferences.FPref.UI_ENABLE_MUSIC, false);
            WorldSave.generateNewWorld(selectedName.getText(),
                    gender.getCurrentIndex() == 0,
                    race.getCurrentIndex(),
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
    // formula are copied straight from AdventurePlayer.create()'s actual starting-unlock logic
    // (Config.getConfigData().raceEditions, the {4,3,2,1} array keyed by difficulty index) rather
    // than reimplemented, so this help text can't drift from what a new game will actually do -
    // it just can't show WHICH of the 4 sets you'll get, since that pick is genuinely randomized
    // at creation time.
    private void showRaceHelp() {
        DialogData dismiss = new DialogData();
        dismiss.name = "OK";

        raceSummary = new DialogData();
        raceSummary.name = "Summary";

        if (!Config.instance().getConfigData().editionProgressionEnabled) {
            raceSummary.text = "Progressive Set Unlocks aren't enabled for this world - every "
                    + "card set is available from the start, regardless of race.";
        } else {
            String rawRaceName = HeroListData.getRawRaceName(race.getCurrentIndex());
            String[] pool = null;
            RaceEditionData[] raceEditions = Config.instance().getConfigData().raceEditions;
            if (rawRaceName != null && raceEditions != null) {
                for (RaceEditionData entry : raceEditions) {
                    if (entry != null && rawRaceName.equalsIgnoreCase(entry.race)
                            && entry.editions != null && entry.editions.length > 0) {
                        pool = entry.editions;
                        break;
                    }
                }
            }
            if (pool == null)
                pool = Config.instance().getConfigData().starterEditions;

            String setList = pool == null || pool.length == 0 ? "None" : String.join(", ", pool);
            int poolSize = pool == null ? 0 : pool.length;
            DifficultyData selectedDifficulty = Config.instance().getConfigData().difficulties[difficulty.getCurrentIndex()];
            int[] startingUnlockCountByDifficultyIndex = {4, 3, 2, 1};
            int cappedIndex = Math.min(difficulty.getCurrentIndex(), startingUnlockCountByDifficultyIndex.length - 1);
            int startingUnlockCount = Math.min(poolSize, startingUnlockCountByDifficultyIndex[cappedIndex]);

            raceSummary.text = String.format(
                    "Race: %s\nAssociated Sets: %s\n\nAt %s difficulty, you'll start with %d of these %d sets unlocked (chosen at random) - the rest unlock later at the Research Lab.",
                    race.getText(), setList, selectedDifficulty.name, startingUnlockCount, poolSize);
        }

        raceSummary.options = new DialogData[1];
        raceSummary.options[0] = dismiss;
        loadDialog(raceSummary);
    }

    private void showDifficultyHelp() {
        DifficultyData selectedDifficulty = Config.instance().getConfigData().difficulties[difficulty.getCurrentIndex()];
        boolean enableGeneticAI = Config.instance().getConfigData().enableGeneticAI;
        String startingEquipment = selectedDifficulty.startItems == null || selectedDifficulty.startItems.length == 0
                ? "None"
                : String.join(", ", selectedDifficulty.startItems);

        difficultySummary = new DialogData();
        difficultySummary.name = "Summary";
        switch (selectedDifficulty.name) {
            case "Easy":
                difficultySummary.text = String.format("Difficulty: %s\nFor newer players or those who want a relaxed experience.\nStarter decks are monocolored.\nStarting equipment: %s", selectedDifficulty.name, startingEquipment);
                break;
            case "Normal":
                difficultySummary.text = String.format("Difficulty: %s\nHow Adventure Mode is intended to be played.\nStarter decks will include a second color.\nStarting equipment: %s", selectedDifficulty.name, startingEquipment);
                break;
            case "Hard":
                if (enableGeneticAI) {
                    difficultySummary.text = String.format("Difficulty: %s\nFor players who want a challenge.\nSome enemies will use genetic AI decks.\nStarter decks will include 2-3 colors.\nStarting equipment: %s", selectedDifficulty.name, startingEquipment);
                } else {
                    difficultySummary.text = String.format("Difficulty: %s\nFor players who want a challenge.\nStarter decks will include 2-3 colors.\nStarting equipment: %s", selectedDifficulty.name, startingEquipment);
                }
                break;
            case "Insane":
                difficultySummary.text = String.format("Difficulty: %s\nFor players who don't want to like the game.\nIdentical to Hard difficulty, but with even less forgiving and rewarding results.\nStarter decks will include 2-3 colors.\nStarting equipment: %s", selectedDifficulty.name, startingEquipment);
                break;
            default:
                difficultySummary.text = "((Custom difficulty settings))";
                break;
        }


        DialogData dismiss = new DialogData();
        //todo: add translation
        dismiss.name = "OK";

        DialogData matchImpacts = new DialogData();
        matchImpacts.text = String.format("Difficulty: %s\nStarting Life: %d\nEnemy Health: %d%%\nGold loss on defeat: %d%%\nLife loss on defeat: %d%%", selectedDifficulty.name, selectedDifficulty.startingLife, (int) (selectedDifficulty.enemyLifeFactor * 100), (int) (selectedDifficulty.goldLoss * 100), (int) (selectedDifficulty.lifeLoss * 100));
        matchImpacts.name = "Duels";

        DialogData economyImpacts = new DialogData();
        economyImpacts.text = String.format("Difficulty: %s\nStarting Gold: %d\nStarting Mana Shards: %d\nCard Sale Price: %d%%\nMana Shard Sale Price: %d%%\nRandom loot rate: %d%%", selectedDifficulty.name, selectedDifficulty.startingMoney, selectedDifficulty.startingShards, (int) (selectedDifficulty.sellFactor * 100), (int) (selectedDifficulty.shardSellRatio * 100), (int) (selectedDifficulty.rewardMaxFactor * 100));
        economyImpacts.name = "Economy";

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
                selectedDifficulty.name, 2 + difficulty.getCurrentIndex(), 11 - difficulty.getCurrentIndex());
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
