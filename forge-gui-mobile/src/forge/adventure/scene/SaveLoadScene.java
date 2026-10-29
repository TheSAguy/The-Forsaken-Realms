package forge.adventure.scene;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.IntMap;
import com.badlogic.gdx.utils.Scaling;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.adventure.data.DifficultyData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.world.WorldSave;
import forge.adventure.world.WorldSaveHeader;
import forge.screens.TransitionScreen;
import forge.sound.SoundSystem;
import forge.util.TextUtil;

import java.io.File;
import java.text.DateFormat;

/**
 * Scene to load and save the game.
 */
public class SaveLoadScene extends UIScene {
    /** Round 140 (S2-6): the data error already reported, so the dialog fires once per problem
     *  rather than on every visit to this screen. */
    private String shownDataError = null;

    private static final int NUMBEROFSAVESLOTS = 11;
    private final IntMap<Selectable<TextraButton>> buttons = new IntMap<>();
    /** Round 838: each slot's own save name, without the difficulty tag its button shows - the Save dialog's default. */
    private final IntMap<String> slotNames = new IntMap<>();
    IntMap<WorldSaveHeader> previews = new IntMap<>();
    /** Round 861: slots whose .sav is cut off, shown (and loaded) from their .old. */
    private final com.badlogic.gdx.utils.IntSet recovered = new com.badlogic.gdx.utils.IntSet();
    Table layout;
    Modes mode;
    TextField textInput;
    TextraLabel header;
    int currentSlot = 0, lastSelectedSlot = 0;
    Image previewImage;
    TextraLabel previewDate, playerLocation;
    TextraButton saveLoadButton, back;
    Selectable<TextraButton> quickSave;
    Selectable<TextraButton> autoSave;
    SelectBox difficulty;
    ScrollPane scrollPane;
    char ASCII_179 = '│';
    Dialog saveDialog;
    TextraButton sortToggleButton;
    enum SortMode { SLOT, RECENT }
    SortMode sortMode = SortMode.SLOT;

    private SaveLoadScene() {
        super(Forge.isLandscapeMode() ? "ui/save_load.json" : "ui/save_load_portrait.json");

        Table root = new Table();
        layout = new Table();
        scrollPane = new ScrollPane(layout);
        Window window = ui.findActor("saveSlots");
        window.add(root);
        textInput = Controls.newTextField("");
        int c = 0;
        String[] diffList = new String[Config.instance().getConfigData().difficulties.length];
        for (DifficultyData diff : Config.instance().getConfigData().difficulties) {
            diffList[c] = Forge.getLocalizer().getMessageorUseDefault("lbl" + diff.name, diff.name);
            c++;
        }

        difficulty = Controls.newComboBox(diffList, null, o -> {
            //DifficultyData difficulty1 = Config.instance().getConfigData().difficulties[difficulty.getSelectedIndex()];
            return null;
        });
        previewImage = ui.findActor("preview");
        previewDate = ui.findActor("saveDate");
        playerLocation = Controls.newTextraLabel("");
        playerLocation.setText("");
        playerLocation.setX(previewImage.getX());
        playerLocation.setY(previewImage.getY() + 5);
        ui.addActor(playerLocation);
        header = Controls.newTextraLabel(Forge.getLocalizer().getMessage("lblSave"));
        root.row();
        root.add(header).grow();
        root.add(difficulty);
        root.row();
        root.add(scrollPane).colspan(2).width(window.getWidth() - 20);
        autoSave = addSaveSlot(Forge.getLocalizer().getMessage("lblAutoSave"), WorldSave.AUTO_SAVE_SLOT);
        quickSave = addSaveSlot(Forge.getLocalizer().getMessage("lblQuickSave"), WorldSave.QUICK_SAVE_SLOT);
        for (int i = 1; i < NUMBEROFSAVESLOTS; i++)
            addSaveSlot(Forge.getLocalizer().getMessage("lblSlot") + ": " + i, i);

        saveLoadButton = ui.findActor("save");
        saveLoadButton.setText(Forge.getLocalizer().getMessage("lblSave"));
        ui.onButtonPress("save", SaveLoadScene.this::loadSave);
        back = ui.findActor("return");
        ui.onButtonPress("return", SaveLoadScene.this::back);
        difficulty.setSelectedIndex(1);
        difficulty.setAlignment(Align.center);
        difficulty.setX(scrollPane.getWidth() - difficulty.getWidth() + 5);
        difficulty.setY(scrollPane.getTop() - difficulty.getHeight() - 5);

        // Add sort toggle button logic
        sortToggleButton = ui.findActor("sortToggle");
        if (sortToggleButton != null) {
            sortToggleButton.setText("Sort by Recent");
            sortToggleButton.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    toggleSortMode();
                }
            });
        }
    }

    private void toggleSortMode() {
        if (sortMode == SortMode.SLOT) {
            sortMode = SortMode.RECENT;
            if (sortToggleButton != null) {
                sortToggleButton.setText("Sort by Slot");
            }
        } else {
            sortMode = SortMode.SLOT;
            if (sortToggleButton != null) {
                sortToggleButton.setText("Sort by Recent");
            }
        }
        refreshSaveSlots();
    }

    private void refreshSaveSlots() {
        layout.clear();
        buttons.clear();
        slotNames.clear();
        addSaveSlot(Forge.getLocalizer().getMessage("lblAutoSave"), WorldSave.AUTO_SAVE_SLOT);
        addSaveSlot(Forge.getLocalizer().getMessage("lblQuickSave"), WorldSave.QUICK_SAVE_SLOT);
        java.util.List<Integer> slotOrder = new java.util.ArrayList<>();
        for (int i = 1; i < NUMBEROFSAVESLOTS; i++) {
            slotOrder.add(i);
        }
        if (sortMode == SortMode.RECENT) {
            // Sort by most recent save date (descending)
            java.util.Map<Integer, java.util.Date> slotDates = new java.util.HashMap<>();
            for (int i : slotOrder) {
                WorldSaveHeader h = previews.get(i);
                if (h != null && h.saveDate != null) slotDates.put(i, h.saveDate);
            }
            slotOrder.sort((a, b) -> {
                java.util.Date da = slotDates.get(a);
                java.util.Date db = slotDates.get(b);
                if (da == null && db == null) return Integer.compare(a, b);
                if (da == null) return 1;
                if (db == null) return -1;
                return db.compareTo(da);
            });
        }
        for (int i : slotOrder) {
            addSaveSlot(Forge.getLocalizer().getMessage("lblSlot") + ": " + i, i);
        }
        layout.invalidateHierarchy();
    }

    private static SaveLoadScene object;

    public static SaveLoadScene instance() {
        if (object == null)
            object = new SaveLoadScene();
        return object;
    }

    public class SaveSlot extends Selectable<TextraButton> {
        private final int slotNumber;

        public SaveSlot(int slotNumber) {
            super(Controls.newTextButton("..."));
            this.slotNumber = slotNumber;
            SaveSlot self = this;
            actor.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    try {
                        if (!actor.isDisabled()) {
                            selectActor(self);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }

        @Override
        public void onSelect(UIScene scene) {
            super.onSelect(scene);
            updateSlot(slotNumber);
        }
    }

    private Selectable<TextraButton> addSaveSlot(String name, int i) {
        String displayName = name;
        String tag = "";
        if (previews.containsKey(i)) {
            WorldSaveHeader header = previews.get(i);
            if (header != null) {
                displayName = getSplitHeaderName(header, false);
                // Round 838 (the user: "Could we somehow show what difficulty that are playing on?"): the save's
                // difficulty on its own button, a dot and the word in the dot's color ("Vic 2", red "Insane").
                String words = slotDifficulty(header);
                if (words != null)
                    tag = "   [%85]" + dotColor(presetOf(words)) + "• " + words;
                if (recovered.contains(i))
                    tag += "  [%85][ORANGE](recovered)"; // round 861: the .old's game; the preview says why
            }
        }
        slotNames.put(i, displayName);
        layout.add(Controls.newLabel(name)).align(Align.left).pad(2, 5, 2, 10);
        SaveSlot button = new SaveSlot(i);
        button.actor.setText(displayName + tag);
        layout.add(button.actor).fill(true, false).expand(true, false).align(Align.left).expandX();
        buttons.put(i, button);
        layout.row();
        addToSelectable(button);
        return button;

    }

    public boolean select(int slot) {
        if (!buttons.containsKey(slot))
            return false;
        selectActor(buttons.get(slot));
        return updateSlot(slot);
    }

    private boolean updateSlot(int slot) {

        currentSlot = slot;
        if (slot > 0)
            lastSelectedSlot = slot;
        if (previews.containsKey(slot)) {
            WorldSaveHeader worldSaveHeader = previews.get(slot);
            if (worldSaveHeader.preview != null) {
                previewImage.setDrawable(new TextureRegionDrawable(new Texture(worldSaveHeader.preview)));
                previewImage.setScaling(Scaling.fit);
                previewImage.layout();
                previewImage.setVisible(true);
                previewDate.setVisible(true);
                String date = worldSaveHeader.saveDate == null ? ""
                        : "[%98]" + DateFormat.getDateInstance().format(worldSaveHeader.saveDate) + " " + DateFormat.getTimeInstance(DateFormat.SHORT).format(worldSaveHeader.saveDate);
                // Round 861: a cut-off slot shows the save before it - say so under the date.
                if (recovered.contains(slot))
                    date += "\n[%80][ORANGE]Last save was cut off";
                previewDate.setText(date);
                //getLocation
                playerLocation.setText(getSplitHeaderName(worldSaveHeader, true));
            }
        } else {
            if (previewImage != null)
                previewImage.setVisible(false);
            if (previewDate != null)
                previewDate.setVisible(false);
        }
        return true;
    }

    boolean loaded = false;

    public void loadSave() {
        if (loaded)
            return;
        loaded = true;
        switch (mode) {
            case Save:
                if (TileMapScene.instance().currentMap().isInMap()) {
                    //Access to screen should be disabled, but stop the process just in case.
                    //Saving needs to be disabled inside maps until we can capture and load exact map state
                    //Otherwise location based events for quests can be skipped by saving and then loading outside the map
                    Dialog noSave = createGenericDialog("", Forge.getLocalizer().getMessage("lblGameNotSaved"), Forge.getLocalizer().getMessage("lblOK"), null, null, null);
                    showDialog(noSave);
                    return;
                }
                if (currentSlot > 0) {
                    //prevent NPE, allowed saveslot is 1 to 10..
                    textInput.setText(slotNames.containsKey(currentSlot) ? slotNames.get(currentSlot)
                            : buttons.get(currentSlot).actor.getText()); // round 838: not the difficulty tag
                    if (saveDialog == null) {
                        saveDialog = createGenericDialog(Forge.getLocalizer().getMessage("lblSave"), null,
                                Forge.getLocalizer().getMessage("lblOK"),
                                Forge.getLocalizer().getMessage("lblAbort"), () -> {
                                    this.save();
                                    removeDialog();
                                }, this::removeDialog);
                        saveDialog.getContentTable().add(Controls.newLabel(Forge.getLocalizer().getMessage("lblNameYourSaveFile"))).colspan(2).pad(2, 15, 2, 15);
                        saveDialog.getContentTable().row();
                        saveDialog.getContentTable().add(Controls.newLabel(Forge.getLocalizer().getMessage("lblName") + ": ")).align(Align.left).pad(2, 15, 2, 2);
                        saveDialog.getContentTable().add(textInput).fillX().expandX().padRight(15);
                        saveDialog.getContentTable().row();
                    }
                    showDialog(saveDialog);
                    stage.setKeyboardFocus(textInput);
                }
                loaded = false;
                break;
            case Load:
                try {
                    MapViewScene.instance().clearBookMarks();
                    Forge.setTransitionScreen(new TransitionScreen(() -> {
                        loaded = false;
                        if (WorldSave.load(currentSlot)) {
                            SoundSystem.instance.changeBackgroundTrack();
                            Forge.switchScene(GameScene.instance());
                        } else {
                            Forge.clearTransitionScreen();
                            // Round 140 (code review S1-1): a failed load used to drop the player
                            // into a freshly generated random world with their own character, or -
                            // once WorldSave stopped doing that - back onto this menu with no
                            // explanation at all, which reads as a dead button. Say what happened,
                            // and say the save is intact, because the honest recovery is to report
                            // it rather than to keep clicking Load.
                            forge.gui.FThreads.invokeInEdtNowOrLater(this::showLoadFailedDialog);
                        }
                    }, null, false, true, Forge.getLocalizer().getMessage("lblLoadingWorld")));
                } catch (Exception e) {
                    Forge.clearTransitionScreen();
                }
                break;
            case NewGamePlus:
                askNewGamePlusCarry(); // round 400: what carries over, then startNewGamePlus()
                break;
        }
    }

    // Round 400 (the user: "when doing a NG+ to ask the player if they want resources spent to restore towns and building
    // to be refunded also ... Maybe 3 check-boxes. 1 - Cards Only, 2 - Cards and Current Resources on hand, 3 - Cards and
    // Current Resources and Invested resources", then "let's make a 4th check, items yes/no. Default Yes"; option 2, what
    // New Game+ always did, is the default). Start opens this; the three are one choice, the items box its own.
    static final int CARRY_CARDS_ONLY = 0, CARRY_CURRENT = 1, CARRY_INVESTED = 2;

    private void askNewGamePlusCarry() {
        com.badlogic.gdx.scenes.scene2d.ui.CheckBox cardsOnly = Controls.newCheckBox("Cards only");
        com.badlogic.gdx.scenes.scene2d.ui.CheckBox current = Controls.newCheckBox("Cards + current resources");
        com.badlogic.gdx.scenes.scene2d.ui.CheckBox invested = Controls.newCheckBox("Cards + current + invested resources");
        com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup<com.badlogic.gdx.scenes.scene2d.ui.CheckBox> carry =
                new com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup<>(cardsOnly, current, invested);
        carry.setMaxCheckCount(1);
        carry.setMinCheckCount(1);
        current.setChecked(true);
        com.badlogic.gdx.scenes.scene2d.ui.CheckBox keepItems = Controls.newCheckBox("Keep items and equipment");
        keepItems.setChecked(true);
        // Round 837 (the user: "Can we ensure that the NG+ always defaults to the player's game they are coming from";
        // logs-1028d P1: from a fresh launch the old top-right box fell back to Normal, and an Insane player's New
        // Game+ silently ran on Normal). The one picker, here on the last screen before Start, pre-selected from the
        // CHOSEN slot's own difficulty as its header records it (slotDifficulty) - from a fresh launch, mid-game, or
        // after picking another slot. A save too old to record it offers "Same as this save", resolved on the load.
        DifficultyData[] presets = Config.instance().getConfigData().difficulties;
        String saveWords = slotDifficulty(previews.get(currentSlot));
        String savePreset = saveWords == null ? null : presetOf(saveWords);
        int offset = saveWords == null ? 1 : 0;
        String[] names = new String[presets.length + offset];
        if (offset == 1)
            names[0] = SAME_AS_SAVE;
        int selected = 0;
        for (int i = 0; i < presets.length; i++) {
            names[i + offset] = Forge.getLocalizer().getMessageorUseDefault("lbl" + presets[i].name, presets[i].name);
            if (presets[i].name.equalsIgnoreCase(savePreset))
                selected = i + offset;
        }
        TextraLabel diffNote = Controls.newTextraLabel("");
        SelectBox<String> pick = Controls.newComboBox(names, names[selected], o -> null);
        Runnable describe = () -> {
            int i = pick.getSelectedIndex() - offset;
            boolean same = i < 0 || presets[i].name.equalsIgnoreCase(savePreset);
            boolean custom = saveWords != null && !saveWords.equalsIgnoreCase(savePreset);
            diffNote.setText("[%80]" + (i < 0 ? "" : same ? "(this save's)" : saveWords == null ? "" : "(this save: " + saveWords + ")")
                    + (custom ? (same ? " - its custom settings come along" : " - custom settings come along") : ""));
        };
        pick.addListener(new com.badlogic.gdx.scenes.scene2d.utils.ChangeListener() {
            @Override
            public void changed(ChangeEvent event, com.badlogic.gdx.scenes.scene2d.Actor actor) {
                describe.run();
            }
        });
        describe.run();
        // Round 839: the settings Advanced... edits for the new run (null = the save's own, as round 837 carries them)
        @SuppressWarnings("unchecked")
        final java.util.Map<String, String>[] edited = new java.util.Map[]{null};
        Dialog dialog = createGenericDialog("New Game+", "What comes with you into the new run?", Forge.getLocalizer().getMessage("lblStart"),
                Forge.getLocalizer().getMessage("lblCancel"), () -> {
                    int choice = cardsOnly.isChecked() ? CARRY_CARDS_ONLY : invested.isChecked() ? CARRY_INVESTED : CARRY_CURRENT;
                    boolean items = keepItems.isChecked();
                    int i = pick.getSelectedIndex() - offset;
                    removeDialog();
                    startNewGamePlus(choice, items, i < 0 ? null : presets[i], edited[0]);
                }, () -> {
                    loaded = false;
                    removeDialog();
                });
        com.badlogic.gdx.scenes.scene2d.ui.Table content = dialog.getContentTable();
        content.row();
        content.add(cardsOnly).left().padLeft(10).row();
        content.add(current).left().padLeft(10).row();
        content.add(invested).left().padLeft(10).row();
        TextraLabel note = Controls.newTextraLabel("[%80]Invested: what your towns, buildings and the Capitol cost to build.\n"
                + "Research and blueprints start over.");
        content.add(note).left().padLeft(10).padBottom(4).row();
        content.add(keepItems).left().padLeft(10).row();
        com.badlogic.gdx.scenes.scene2d.ui.Table difficultyRow = new com.badlogic.gdx.scenes.scene2d.ui.Table();
        difficultyRow.add(Controls.newLabel("Difficulty:")).padRight(6);
        difficultyRow.add(pick).padRight(6);
        if (Forge.isLandscapeMode())
            difficultyRow.add(diffNote);
        content.add(difficultyRow).left().padLeft(10).padTop(4).row();
        if (!Forge.isLandscapeMode()) // portrait is 270 wide: the note on its own line
            content.add(diffNote).left().padLeft(10).row();
        // Round 839 (the user's OK, through the manager, on notes/r839-mock.png): "Custom settings: N changed" and
        // Advanced..., which opens the custom difficulty panel on the chosen slot's own settings - read from its file
        // (CustomDifficulty.peekSave), the slot is only loaded at Start. Done keeps the edit here; Start lays it over the
        // loaded save before the new world is generated. N leaves the rules out, as the panel's own count does.
        if (!forge.adventure.util.CustomDifficulty.knobs().isEmpty()) {
            forge.adventure.util.CustomDifficulty.SavePeek peek = forge.adventure.util.CustomDifficulty.peekSave(currentSlot);
            TextraLabel customCount = Controls.newTextraLabel("");
            Runnable countText = () -> {
                java.util.Map<String, String> m = edited[0] != null ? edited[0] : peek.overrides;
                int n = forge.adventure.util.CustomDifficulty.countChanges(m);
                String tail = edited[0] != null ? " (edited)" : "";
                customCount.setText(n == 0 ? "none" + tail : "[GOLD]" + n + " changed" + tail);
                customCount.invalidateHierarchy();
                dialog.pack();
                dialog.setPosition(Math.round((stage.getWidth() - dialog.getWidth()) / 2),
                        Math.round((stage.getHeight() - dialog.getHeight()) / 2));
            };
            TextraButton advanced = Controls.newTextButton("Advanced...", () -> {
                int i = pick.getSelectedIndex() - offset;
                String name = i >= 0 ? presets[i].name : peek.preset != null ? peek.preset : savePreset;
                int at = 0;
                for (int p = 0; p < presets.length; p++)
                    if (presets[p].name.equalsIgnoreCase(name))
                        at = p;
                final int openedOn = at;
                forge.adventure.scene.CustomDifficultyScene.instance().openNewGamePlus(at, peek, edited[0],
                        index -> { // the dialog's box follows only a preset changed in the panel ("Same as this save" stays)
                            if (index != openedOn)
                                pick.setSelectedIndex(Math.min(names.length - 1, index + offset));
                        },
                        settings -> {
                            edited[0] = settings;
                            countText.run();
                        });
            });
            advanced.setName("ngPlusAdvanced");
            com.badlogic.gdx.scenes.scene2d.ui.Table customRow = new com.badlogic.gdx.scenes.scene2d.ui.Table();
            customRow.add(Controls.newLabel("Custom settings:")).padRight(6);
            customRow.add(customCount).padRight(10);
            customRow.add(advanced).width(Forge.isLandscapeMode() ? 92 : 100).height(Forge.isLandscapeMode() ? 22 : 26);
            content.add(customRow).left().padLeft(10).padTop(4).row();
            content.add(Controls.newTextraLabel("[%80][LIGHT_GRAY]Changes apply to the new run only.")).left().padLeft(10).row();
            countText.run();
        }
        showDialog(dialog);
    }

    /** Round 837: the picker's first entry for a save whose header does not record its difficulty. */
    private static final String SAME_AS_SAVE = "Same as this save";

    /**
     * Round 837/838: a slot's difficulty as its header records it, in CustomDifficulty.displayName's words ("Insane",
     * "Custom (Hard)"), or null. A save from round 838 on carries the words as the name's third segment
     * (getSaveFileSuffix); an older one only the colored dot before its location, read back to the preset's name.
     */
    String slotDifficulty(WorldSaveHeader header) {
        if (header == null || header.name == null || !header.name.contains(Character.toString(ASCII_179)))
            return null;
        String[] split = TextUtil.split(header.name, ASCII_179);
        if (split.length > 2 && !split[2].trim().isEmpty())
            return split[2].trim();
        if (split.length < 2)
            return null;
        String location = split[1];
        if (location.contains("[CYAN]"))
            return "Easy";
        if (location.contains("[GREEN]"))
            return "Normal";
        if (location.contains("[GOLD]"))
            return "Hard";
        if (location.contains("[RED]"))
            return "Insane";
        return null;
    }

    /** Round 837: the plane's own preset of that name, or null. */
    private static DifficultyData configPreset(String name) {
        for (DifficultyData d : Config.instance().getConfigData().difficulties)
            if (d != null && d.name != null && d.name.equalsIgnoreCase(name))
                return d;
        return null;
    }

    /** "Custom (Hard)" -> "Hard"; a preset's name stays itself. */
    static String presetOf(String words) {
        int open = words.indexOf('('), close = words.lastIndexOf(')');
        return open >= 0 && close > open ? words.substring(open + 1, close).trim() : words;
    }

    /** The save dot's color markup for a preset (getSaveFileSuffix's colors). */
    static String dotColor(String preset) {
        switch (preset == null ? "" : preset.toLowerCase()) {
            case "easy":
                return "[CYAN]";
            case "normal":
                return "[GREEN]";
            case "hard":
                return "[GOLD]";
            case "insane":
                return "[RED]";
            default:
                return "[WHITE]";
        }
    }

    private void startNewGamePlus(int carry, boolean keepItems, DifficultyData chosen, java.util.Map<String, String> editedSettings) {
        try {
            Forge.setTransitionScreen(new TransitionScreen(() -> {
                loaded = false;
                if (WorldSave.load(currentSlot)) {
                    // Round 314 (user: "When doing a NG+, be sure to add the gold the player has in his bank to the
                    // new game also"): the bank's balance lives on the Capitol's PointOfInterestChanges, which
                    // clearChanges() wipes with the rest of the old world - counted first, paid into the purse
                    // after the reset below.
                    int banked = 0;
                    for (forge.adventure.pointofintrest.PointOfInterestChanges c : WorldSave.getCurrentSave().getAllPointOfInterestChanges())
                        banked += Math.max(0, c.getBankBalance());
                    final int bankedGold = banked;
                    // Round 400: what the held towns and buildings cost - read now, while the old world and the old
                    // run's difficulty (its prices) are still in place.
                    final int[] investedRefund = carry == CARRY_INVESTED
                            ? forge.adventure.util.NewGamePlusRefund.invested(WorldSave.getCurrentSave().getWorld()) : null;
                    // Round 425: the towns held now set the AI's one-off day-8 wave - counted before the old world goes.
                    final int heldTowns = carry == CARRY_INVESTED
                            ? forge.adventure.util.NewGamePlusRefund.heldTowns(WorldSave.getCurrentSave().getWorld()) : 0;
                    System.out.println("[TFR-NewGamePlus] carrying: " + (carry == CARRY_CARDS_ONLY ? "cards only"
                            : carry == CARRY_INVESTED ? "cards + current + invested resources" : "cards + current resources")
                            + ", items " + (keepItems ? "kept" : "left behind"));
                    WorldSave.getCurrentSave().clearChanges();
                    // Round 837: the picker's preset, or ("Same as this save") the loaded save's own - never a
                    // fallback to Normal. The config preset goes to updateDifficulty, which lays a custom save's
                    // overrides over it (round 822).
                    String was = forge.adventure.util.CustomDifficulty.displayName(Current.player());
                    // Round 839: the dialog's Advanced... edit, if any, replaces the save's settings - before the World
                    // generation below reads them, and before updateDifficulty lays them over the preset
                    if (editedSettings != null)
                        forge.adventure.util.CustomDifficulty.replaceForNewGamePlus(Current.player(), editedSettings);
                    DifficultyData target = chosen != null ? chosen : configPreset(Current.player().getDifficulty().name);
                    // Round 241: world-gen thins the wasteland towns by difficulty, and the new
                    // difficulty is only applied to the player below - so the World is told first.
                    WorldSave.getCurrentSave().getWorld().setGenerationDifficulty(target != null
                            ? target.name : Current.player().getDifficulty().name);
                    if (WorldSave.getCurrentSave().getWorld().generateNew(0)) {
                        if (target != null)
                            Current.player().updateDifficulty(target);
                        System.out.println("[TFR-NewGamePlus] difficulty " + forge.adventure.util.CustomDifficulty.displayName(Current.player())
                                + " (the save was " + was + (chosen == null ? ", kept as \"" + SAME_AS_SAVE + "\"" : "") + ")");
                        Current.player().setWorldPosY((int) (WorldSave.getCurrentSave().getWorld().getData().playerStartPosY * WorldSave.getCurrentSave().getWorld().getData().height * WorldSave.getCurrentSave().getWorld().getTileSize()));
                        Current.player().setWorldPosX((int) (WorldSave.getCurrentSave().getWorld().getData().playerStartPosX * WorldSave.getCurrentSave().getWorld().getData().width * WorldSave.getCurrentSave().getWorld().getTileSize()));
                        // "A New Game+ should basically be a new game, + your Cards,
                        // Equipment and resources" (user spec 2026-08-31). Everything that
                        // used to be reset piecemeal here - quests, quest flags,
                        // statistics - now lives in one place alongside clear()/create(),
                        // together with the per-run state this path was silently
                        // inheriting: shop-type unlocks, starting editions, character
                        // flags, color reputation, research timers and Bronze Coin marks.
                        //
                        // Ordering is load-bearing: updateDifficulty() must already have
                        // run (the edition seed is difficulty-scaled), and this must run
                        // before reservePlayerEditions() below, which reads the re-seeded
                        // set, and before addQuest("28"), which needs the cleared quest
                        // list and the newGamePlus flag already in place.
                        Current.player().resetForNewGamePlus();
                        // Round 400: the roaming guards' decks and gear come home first, so the items choice below
                        // decides about the gear with everything else.
                        forge.adventure.util.RoamingGuards.releaseAllForNewRun();
                        Current.player().applyNewGamePlusCarry(carry != CARRY_CARDS_ONLY, keepItems); // round 400
                        if (bankedGold > 0 && carry == CARRY_CARDS_ONLY) {
                            System.out.println("[TFR-NewGamePlus] cards only: the bank's " + bankedGold + " gold left behind");
                        } else if (bankedGold > 0) { // round 314: a transfer, not income - the ledger's IGNORED bucket
                            forge.adventure.util.ResourceLedger.run(forge.adventure.util.ResourceLedger.Bucket.IGNORED,
                                    () -> Current.player().giveGold(bankedGold));
                            System.out.println("[TFR-NewGamePlus] the bank's " + bankedGold
                                    + " gold carried into the new run - purse now " + Current.player().getGold());
                        }
                        if (investedRefund != null) { // round 400: a refund, not income - IGNORED as well
                            forge.adventure.util.ResourceLedger.run(forge.adventure.util.ResourceLedger.Bucket.IGNORED, () -> {
                                Current.player().giveGold(investedRefund[0]);
                                Current.player().addWood(investedRefund[1]);
                                Current.player().addStone(investedRefund[2]);
                                Current.player().addShards(investedRefund[3]);
                            });
                            System.out.println("[TFR-NewGamePlus] refunded what was built: "
                                    + forge.adventure.util.NewGamePlusRefund.label(investedRefund) + " - purse now "
                                    + Current.player().getGold() + " gold, " + Current.player().getWood() + " wood, "
                                    + Current.player().getStone() + " stone, " + Current.player().getShards() + " shards");
                            // Round 425: the AI's answer to a rebuilt realm - one-off mages per color on day 8, by the
                            // NEW run's difficulty (updateDifficulty above), outside the cap.
                            forge.adventure.data.TuningData tuning = Config.instance().getTuningData();
                            int wave = tuning.ngPlusWaveMagesFor(Current.player().getDifficulty().name, heldTowns);
                            WorldSave.getCurrentSave().getWorld().setNgPlusWaveMages(wave);
                            System.out.println("[TFR-NewGamePlus] " + heldTowns + " town(s) held -> " + wave
                                    + " one-off mage(s) per AI color on day " + tuning.ngPlusWaveDay + " ("
                                    + Current.player().getDifficulty().name + ", at most " + tuning.ngPlusWaveMaxMages + ")");
                        }
                        // Mirrors WorldSave's own New Game ordering: the color shards only
                        // exist after generateNew() has re-seeded them, and the player's
                        // own editions must be carved out of the AI colors' pools again -
                        // the New Game path does this right after create(), and New Game+
                        // had no equivalent, so the exclusivity pass never ran.
                        forge.adventure.util.EditionProgression.reservePlayerEditions(
                                WorldSave.getCurrentSave().getWorld(), Current.player());
                        Current.player().removeAllQuestItems();
                        // Every run starts with a full challenge-coin purse (user spec
                        // 2026-08-31). Deliberately AFTER removeAllQuestItems() so the
                        // grant can never be swept up by it - the three coins are not
                        // flagged questItem today, but ordering it this way means a later
                        // data edit that does flag them cannot silently empty the purse.
                        // Round 400: exactly the starting five, whatever was carried and whether or not
                        // items were kept - a hoarded surplus no longer survives (the user's rule).
                        Current.player().resetChallengeCoins();
                        AdventurePlayer.current().addQuest("28", true);
                        WorldSave.getCurrentSave().clearBookmarks();
                        WorldStage.getInstance().enterSpawnPOI();
                        SoundSystem.instance.changeBackgroundTrack();
                        Forge.switchScene(GameScene.instance());
                        if (investedRefund != null) // round 400: authored markup, as the Ring's gift notice
                            forge.adventure.stage.GameHUD.getInstance().addNotification("[BLACK]Refunded for what you built: [WHITE][+Gold][BLACK] "
                                    + investedRefund[0] + "  [WHITE][+Wood][BLACK] " + investedRefund[1] + "  [WHITE][+Stone][BLACK] "
                                    + investedRefund[2] + "  [WHITE][+Shards][BLACK] " + investedRefund[3], true);
                    } else {
                        Forge.clearTransitionScreen();
                    }
                } else {
                    Forge.clearTransitionScreen();
                }
            }, null, false, true, Forge.getLocalizer().getMessage("lblGeneratingWorld")));
        } catch (Exception e) {
            loaded = false;
            Forge.clearTransitionScreen();
        }
    }


    public void save() {
        if (!TileMapScene.instance().currentMap().isInMap()) {
            if (WorldSave.getCurrentSave().save(textInput.getText() + getSaveFileSuffix(), currentSlot)) {
                updateFiles();
                //ensure the dialog is hidden before switching

                Scene restoreScene = Forge.switchToLast();
                if (restoreScene != null) {
                    restoreScene = Forge.switchToLast();
                }

                if (restoreScene == null) {
                    restoreScene = GameScene.instance();
                }

                Forge.switchScene(restoreScene);
            }
        }
    }

    private void updateFiles() {

        File f = new File(WorldSave.getSaveDir());
        f.mkdirs();
        File[] names = f.listFiles();
        if (names == null)
            throw new RuntimeException("Can not find save directory");
        previews.clear();
        recovered.clear();
        for (File name : names) {
            if (WorldSave.isSafeFile(name.getName())) {
                int slot = WorldSave.filenameToSlot(name.getName());
                WorldSaveHeader worldSaveHeader = WorldSave.readHeader(name);
                if (worldSaveHeader == null) {
                    // Round 861: a .sav cut off by an app killed mid-save (1.24) used to hide its slot; it shows the
                    // .old's game, marked, and loads it through round 858's fallback.
                    worldSaveHeader = WorldSave.readCutOffHeader(slot);
                    if (worldSaveHeader != null)
                        recovered.add(slot);
                }
                if (worldSaveHeader != null)
                    previews.put(slot, worldSaveHeader);
            }
        }
        refreshSaveSlots();
    }

    private String getSplitHeaderName(WorldSaveHeader worldSaveHeader, boolean getLocation) {
        String noMapData = "[RED]No Map Data!";
        if (worldSaveHeader.name.contains(Character.toString(ASCII_179))) {
            String[] split = TextUtil.split(worldSaveHeader.name, ASCII_179);
            if (getLocation) // unicode symbols with \\uFFxx blackout the stage using TextraTypist 2.x.x
                return split.length > 1 ? split[1].replaceAll("\uFF0A", "• ") : noMapData;
            else
                return split[0];
        }
        return getLocation ? noMapData : worldSaveHeader.name;
    }

    public enum Modes {
        Save,
        Load,
        NewGamePlus
    }

    public void setMode(Modes mode) {
        switch (mode) {
            case Save:
                header.setText(Forge.getLocalizer().getMessage("lblSaveGame"));
                saveLoadButton.setText(Forge.getLocalizer().getMessage("lblSave"));
                break;
            case Load:
                header.setText(Forge.getLocalizer().getMessage("lblLoadGame"));
                saveLoadButton.setText(Forge.getLocalizer().getMessage("lblLoad"));
                break;
            case NewGamePlus:
                header.setText(Forge.getLocalizer().getMessage("lblNewGame") + "+");
                saveLoadButton.setText(Forge.getLocalizer().getMessage("lblStart"));
                break;
        }
        autoSave.actor.setDisabled(mode == Modes.Save);
        quickSave.actor.setDisabled(mode == Modes.Save);
        this.mode = mode;
    }

    /**
     * Round 140 (S1-1). WorldSave.load() no longer regenerates the world behind the player's back;
     * it refuses and records why. This is where the player finds out.
     */
    private void showLoadFailedDialog() {
        String reason = WorldSave.getLastLoadError();
        WorldSave.clearLastLoadError();
        showDialog(createGenericDialog("Could not load that save", "This save could not be opened"
                        + (reason == null ? "." : ":\n\n" + reason)
                        + "\n\nThe file on disk has NOT been changed. Try another slot, and please report this "
                        + "with your forge.log so it can be looked at.",
                Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null));
    }

    @Override
    public void enter() {
        unselectActors();
        // Round 140 (code review S2-6): a plane data file that exists but does not parse leaves
        // every feature flag and every balance number at its default, which looks like a very
        // strange but playable game rather than a broken install. Report it the first time the
        // player reaches a menu that can start or load a game.
        String dataError = Config.instance().getFatalDataError();
        if (dataError != null && !dataError.equals(shownDataError)) {
            shownDataError = dataError;
            showDialog(createGenericDialog("Plane data failed to load", dataError
                            + "\n\nThe game is running with every plane feature and balance value at its built-in "
                            + "default. Reinstall or restore that file before starting a new game.",
                    Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null));
        }
        updateFiles();
        select(lastSelectedSlot);
        scrollPane.setScrollY(0);
        scrollPane.updateVisualScroll();
        autoSave.actor.setText(Forge.getLocalizer().getMessage("lblAutoSave"));
        quickSave.actor.setText(Forge.getLocalizer().getMessage("lblQuickSave"));
        // Round 837: the top-right difficulty box is never shown. It pre-selected the LOADED game's difficulty, and from a
        // fresh launch (no game loaded) fell back to Normal; New Game+ now picks in askNewGamePlusCarry's dialog, from
        // the chosen slot's own difficulty.
        if (difficulty != null)
            difficulty.setVisible(false);
        performTouch(scrollPane); //can use mouse wheel if available to scroll
        super.enter();
    }

    public void showMessage(String title, String message) {
        showDialog(createGenericDialog(title, message,
            Forge.getLocalizer().getMessage("lblOK"),
            Forge.getLocalizer().getMessage("lblCancel"),
            () -> {
                Forge.switchScene(StartScene.instance());
                removeDialog();
            }, this::removeDialog));
    }

    public String getSaveFileSuffix() {
        String difficulty;
        switch (AdventurePlayer.current().getDifficulty().name) {
            case "easy":
            case "Easy":
                difficulty = "[%99][CYAN]• [WHITE]";
                break;
            case "normal":
            case "Normal":
                difficulty = "[%99][GREEN]• [WHITE]";
                break;
            case "hard":
            case "Hard":
                difficulty = "[%99][GOLD]• [WHITE]";
                break;
            case "insane":
            case "Insane":
                difficulty = "[%99][RED]• [WHITE]";
                break;
            default:
                difficulty = "[%99][WHITE]";
                break;
        }
        // Round 838: a third segment, the difficulty in CustomDifficulty.displayName's words ("Insane", "Custom (Hard)"),
        // for the slot buttons and New Game+ (slotDifficulty). A build before 838 reads only the first two.
        String words = forge.adventure.util.CustomDifficulty.displayName(AdventurePlayer.current());
        return ASCII_179 + difficulty + GameScene.instance().getAdventurePlayerLocation(true, true)
                + ("?".equals(words) || words.indexOf(ASCII_179) >= 0 ? "" : ASCII_179 + words);
    }
}
