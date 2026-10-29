package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Timer;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.adventure.data.DifficultyData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.CustomDifficulty;
import forge.adventure.util.CustomDifficulty.Knob;
import forge.adventure.world.WorldSave;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Round 823 (custom difficulty, part 2; the user's "Yes, build it" on notes/r823-mock.png): the Advanced panel the
 * new-game screen opens. Every knob of {@code config tables/custom_difficulty.json}, under tabs (All + the file's
 * categories, each with its change count), a search box (name, tooltip, group, category), Basic/Expert and "Changed
 * only". A row shows the knob's name, the preset's own value in grey, a -/+ stepper (long press repeats) or a check box,
 * and a reset arrow once changed; the tapped row's tooltip shows in the help line at the bottom. "Based on" picks the
 * preset the values are measured against (the new-game screen's own difficulty box follows it).
 * <p>
 * The panel edits a copy; Done hands it to {@link CustomDifficulty#setPending} (the next new game takes it) and the preset
 * back to the new-game screen. A value stepped back to the preset's own is no change. Landscape layout
 * {@code ui/custom_difficulty.json}. Round 824: portrait ({@code ui/custom_difficulty_portrait.json}) puts the tabs in a
 * select box beside "Based on", the rows full width with touch-sized (24) steppers, a three-line help line, and the
 * buttons across the bottom. {@code [TFR-Custom] panel ...} lines; console
 * {@code custom panel} lists what the panel shows, {@code custom panel search TEXT} types into its search box.
 * Round 826: Settings' Difficulty row opens it on the running save ({@link #openMidGame}). Round 827: Share code -
 * Copy / Paste code through the clipboard ({@link CustomDifficulty#shareCode} / {@link CustomDifficulty#readCode}).
 * Round 839: New Game+'s dialog opens it on the chosen slot's settings ({@link #openNewGamePlus}).
 */
public class CustomDifficultyScene extends UIScene {
    private static CustomDifficultyScene object;

    public static CustomDifficultyScene instance() {
        if (object == null)
            object = new CustomDifficultyScene();
        return object;
    }

    private static final String ALL = "All";
    private static final Color CHANGED_TINT = new Color(0.36f, 0.31f, 0.18f, 0.55f);
    private static Texture white;

    private final LinkedHashMap<String, String> work = new LinkedHashMap<>();
    private IntConsumer onDone;
    private String tab = ALL;
    private boolean expert, changedOnly;

    private final Table content;
    private final Table tabs = new Table();
    private final Table rows = new Table();
    private final ScrollPane rowScroll;
    private final SelectBox<String> preset;
    private final TextField search;
    private final TextraButton basicButton, expertButton;
    private final CheckBox changedBox;
    private final TextraLabel countLabel, help;
    private Timer.Task repeat;
    /** Round 824: portrait's tab box (null in landscape) and the tab each of its items is; a refill re-lists it. */
    private final SelectBox<String> tabBox;
    private final List<String> tabBoxTabs = new ArrayList<>();
    private boolean fillingTabBox;
    private final boolean landscape = Forge.isLandscapeMode();
    /** Steppers and reset: 14 in landscape, 24 (a finger) in portrait. */
    private final float button = Forge.isLandscapeMode() ? 14 : 24;
    private final Map<String, TextraLabel> valueLabels = new java.util.HashMap<>();
    /** Round 826: opened from Settings on the running save - the preset fixed, new-game-only rows locked, Done applies. */
    private boolean midGame;
    private AdventurePlayer player;
    private final Table presetSlot = new Table();
    private final TextraLabel basedOn, inPlay;
    /** Round 839: opened from New Game+'s dialog - every row open (a new world); Done hands the copy back to it. */
    private boolean newGamePlus;
    private java.util.function.Consumer<Map<String, String>> onDoneSettings;
    private final TextraLabel title;

    private CustomDifficultyScene() {
        super(Forge.isLandscapeMode() ? "ui/custom_difficulty.json" : "ui/custom_difficulty_portrait.json");
        content = ui.findActor("content");
        content.top().left();

        // header: the title, the preset, how many are changed
        Table header = new Table();
        countLabel = Controls.newTextraLabel("");
        title = Controls.newTextraLabel("[%120]Custom Difficulty");
        header.add(title).left().padRight(12);
        Table pick = header;
        if (!landscape) {
            header.row();
            header.add(countLabel).left().padBottom(4).row();
            pick = new Table();
            header.add(pick).left().row();
        }
        basedOn = Controls.newTextraLabel("[%85][LIGHT_GRAY]Based on");
        inPlay = Controls.newTextraLabel("");
        pick.add(basedOn).padRight(4);
        @SuppressWarnings("unchecked")
        SelectBox<String> box = Controls.newComboBox();
        preset = box;
        Array<String> names = new Array<>();
        for (DifficultyData d : Config.instance().getConfigData().difficulties)
            names.add(d.name);
        preset.setItems(names);
        preset.setName("preset");
        preset.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                refill();
            }
        });
        showPresetBox(true);
        if (landscape) {
            header.add(presetSlot).height(16).padRight(8);
            header.add(countLabel).left().expandX();
            tabBox = null;
        } else {
            pick.add(presetSlot).height(20).padRight(6);
            @SuppressWarnings("unchecked")
            SelectBox<String> tb = Controls.newComboBox();
            tabBox = tb;
            tabBox.setName("tabBox");
            tabBox.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    int i = tabBox.getSelectedIndex();
                    if (fillingTabBox || i < 0 || i >= tabBoxTabs.size())
                        return;
                    tab = tabBoxTabs.get(i);
                    search.setText("");
                    refill();
                }
            });
            pick.add(tabBox).width(124).height(20).left();
        }
        content.add(header).left().growX().padBottom(3).row();

        // filter bar: search, Basic / Expert, Changed only
        Table bar = new Table();
        search = Controls.newTextField("");
        search.setMessageText("Search name or tooltip...");
        search.setName("search");
        search.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                refill();
            }
        });
        if (landscape) {
            bar.add(search).width(120).height(15).padRight(8);
        } else {
            bar.add(search).width(246).height(20).colspan(4).left().padBottom(4).row();
        }
        basicButton = Controls.newTextButton("[%80]Basic", () -> setLevel(false));
        basicButton.setName("level:basic");
        expertButton = Controls.newTextButton("[%80]Expert", () -> setLevel(true));
        expertButton.setName("level:expert");
        bar.add(basicButton).width(landscape ? 34 : 44).height(landscape ? 15 : 20);
        bar.add(expertButton).width(landscape ? 38 : 48).height(landscape ? 15 : 20).padRight(10);
        changedBox = Controls.newCheckBox("");
        changedBox.setName("changedOnly");
        changedBox.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                changedOnly = changedBox.isChecked();
                refill();
            }
        });
        bar.add(changedBox).padRight(3);
        bar.add(Controls.newTextraLabel("[%85]Changed only")).left().expandX();
        content.add(bar).left().growX().padBottom(4).row();

        // body: tabs on the left, the rows on the right
        Table body = new Table();
        tabs.top();
        if (landscape)
            body.add(tabs).top().width(84).padRight(4);
        rows.top().left();
        rowScroll = new ScrollPane(rows, Controls.getSkin());
        rowScroll.setFadeScrollBars(false);
        rowScroll.setScrollingDisabled(true, false);
        body.add(rowScroll).grow();
        content.add(body).grow().row();

        help = Controls.newTextraLabel("");
        help.setWrap(true);
        content.add(help).growX().height(landscape ? 20 : 32).padTop(2).left();

        ui.onButtonPress("done", this::done);
        ui.onButtonPress("resetAll", this::resetAll);
        ui.onButtonPress("share", this::openShare);
    }

    /** NewGameScene's Advanced button: the panel on this preset and the pending overrides; onDone gets the preset back. */
    public void open(int presetIndex, Map<String, String> current, IntConsumer onDone) {
        this.onDone = onDone;
        midGame = false;
        newGamePlus = false;
        onDoneSettings = null;
        player = null;
        title.setText("[%120]Custom Difficulty");
        showPresetBox(true);
        work.clear();
        if (current != null)
            work.putAll(current);
        work.putAll(CustomDifficulty.pendingRules()); // round 825: the rules ride along (seeded from the profile)
        preset.setSelectedIndex(Math.max(0, Math.min(presetIndex, preset.getItems().size - 1)));
        help.setText("[%85][LIGHT_GRAY]Tap a setting's name to read what it does.");
        System.out.println("[TFR-Custom] panel opened on " + presetName() + " with " + work.size() + " change(s), "
                + CustomDifficulty.knobs().size() + " knob(s)");
        Forge.switchScene(this);
    }

    /**
     * Round 826 (the user's "Yes, build it" on notes/r826-mock.png): Settings' Difficulty row - the panel on the running
     * save. "Based on" is the save's preset, fixed; new-game-only knobs show their value, locked; Done applies each
     * difference through {@link CustomDifficulty#change} (a {@code [TFR-Custom] changed ... on day N} line each) and
     * the rules through {@link CustomDifficulty#setRule}. An older save's rules show the profile's (what it plays by).
     */
    public void openMidGame(AdventurePlayer p) {
        if (p == null || p.getDifficulty() == null)
            return;
        onDone = null;
        midGame = true;
        newGamePlus = false;
        onDoneSettings = null;
        title.setText("[%120]Custom Difficulty");
        player = p;
        int i = Math.max(0, preset.getItems().indexOf(p.getDifficulty().name, false));
        preset.setSelectedIndex(i);
        work.clear();
        work.putAll(p.getCustomDifficulty());
        for (Knob k : CustomDifficulty.knobs().values()) {
            String field = ruleField(k);
            if (field != null && !p.hasRulesPerSave()) {
                work.remove(k.id);
                if (CustomDifficulty.profileRule(field) != Boolean.parseBoolean(CustomDifficulty.presetRaw(k, presetName())))
                    work.put(k.id, String.valueOf(CustomDifficulty.profileRule(field)));
            }
        }
        int day = WorldSave.getCurrentSave() == null || WorldSave.getCurrentSave().getWorld() == null ? 0
                : WorldSave.getCurrentSave().getWorld().getCurrentDay();
        inPlay.setText("[%85][LIGHT_GRAY]" + p.getDifficulty().name + " - in play, day " + day);
        showPresetBox(false);
        help.setText("[%85][LIGHT_GRAY]Mid-game: only settings that can change during a run. New-game-only ones are locked.");
        System.out.println("[TFR-Custom] panel opened mid-game on day " + day + ": " + CustomDifficulty.displayName(p)
                + ", " + CustomDifficulty.count(p) + " change(s)"
                + (CustomDifficulty.rules(p).isEmpty() ? "" : ", rules " + CustomDifficulty.rules(p))
                + (p.hasRulesPerSave() ? "" : " (rules from the profile - an older save)"));
        Forge.switchScene(this);
    }

    /**
     * Round 839: New Game+'s Advanced... - the panel on the chosen slot's settings (read from its file, the slot not yet
     * loaded) and the dialog's difficulty. Every row is open: New Game+ makes a new world. A save that does not keep its
     * own rules shows the profile's, as New Game+ would seed them. Done hands the copy back (the dialog keeps it until
     * Start) and the preset to the dialog's difficulty box; nothing changes on disk.
     */
    public void openNewGamePlus(int presetIndex, CustomDifficulty.SavePeek peek, Map<String, String> edited,
                                IntConsumer onPreset, java.util.function.Consumer<Map<String, String>> onSettings) {
        onDone = onPreset;
        onDoneSettings = onSettings;
        midGame = false;
        newGamePlus = true;
        player = null;
        title.setText("[%120]New Game+");
        showPresetBox(true);
        preset.setSelectedIndex(Math.max(0, Math.min(presetIndex, preset.getItems().size - 1)));
        work.clear();
        if (edited != null) {
            work.putAll(edited);
        } else if (peek != null) {
            work.putAll(peek.overrides);
            if (!peek.rulesPerSave) // as resetForNewGamePlus would seed them (CustomDifficulty.onNewGamePlus)
                for (Knob k : CustomDifficulty.knobs().values()) {
                    String field = ruleField(k);
                    if (field != null && CustomDifficulty.profileRule(field) != Boolean.parseBoolean(CustomDifficulty.presetRaw(k, presetName())))
                        work.put(k.id, String.valueOf(CustomDifficulty.profileRule(field)));
                }
        }
        help.setText("[%85][LIGHT_GRAY]New Game+: these settings start the new run. Done takes them back to the New Game+ dialog.");
        System.out.println("[TFR-Custom] panel opened for New Game+ on " + presetName() + " with "
                + CustomDifficulty.countChanges(work) + " change(s)" + (edited != null ? " (edited before)" : "")
                + ": " + CustomDifficulty.encode(work));
        Forge.switchScene(this);
    }

    /** The preset box (a new game) or the save's fixed preset (mid-game) beside "Based on". */
    private void showPresetBox(boolean box) {
        presetSlot.clearChildren();
        if (box)
            presetSlot.add(preset).width(62).height(landscape ? 16 : 20);
        else
            presetSlot.add(inPlay).left();
        basedOn.setText(box ? "[%85][LIGHT_GRAY]Based on" : ""); // mid-game the fixed text says it all
        basedOn.invalidateHierarchy();
    }

    private static String ruleField(Knob k) {
        return CustomDifficulty.ruleField(k);
    }

    /** Mid-game, a new-game-only knob is shown but cannot change. */
    private boolean locked(Knob k) {
        return midGame && k.isNewGameOnly();
    }

    /** Mid-game Done: every difference from the running save, applied as it is now. */
    private void applyMidGame() {
        AdventurePlayer p = player;
        if (p == null || p != AdventurePlayer.current()) {
            System.out.println("[TFR-Custom] panel done mid-game: the save changed while the panel was open - nothing applied");
            return;
        }
        String source = "Advanced, mid-game";
        int applied = 0;
        for (Knob k : CustomDifficulty.knobs().values()) {
            if (k.isNewGameOnly())
                continue;
            String field = ruleField(k);
            String want = work.get(k.id);
            if (field != null) {
                boolean now = want != null ? Boolean.parseBoolean(want) : Boolean.parseBoolean(CustomDifficulty.presetRaw(k, presetName()));
                Boolean saved = CustomDifficulty.rule(field);
                boolean was = saved != null ? saved : CustomDifficulty.profileRule(field);
                if (now != was && CustomDifficulty.setRule(p, field, now, source)) {
                    applied++;
                    if ("reloadJabOptOut".equals(field))
                        forge.adventure.util.ReloadJab.onSettingChanged(!now);
                }
                continue;
            }
            String have = p.getCustomDifficulty().get(k.id);
            if (want == null ? have == null : have != null && CustomDifficulty.same(k, want, have))
                continue;
            String error = CustomDifficulty.change(p, k.id, want, source);
            if (error != null)
                System.out.println("[TFR-Custom] panel done mid-game: " + k.id + " not applied - " + error);
            else
                applied++;
        }
        System.out.println("[TFR-Custom] panel done mid-game: " + applied + " change(s) applied, now "
                + CustomDifficulty.displayName(p) + " with " + CustomDifficulty.count(p) + " override(s)"
                + (CustomDifficulty.rules(p).isEmpty() ? "" : ", rules " + CustomDifficulty.rules(p)));
    }

    @Override
    public void enter() {
        super.enter();
        refill();
        stage.setScrollFocus(rowScroll);
    }

    private String presetName() {
        return preset.getSelected();
    }

    private boolean done() {
        cancelRepeat();
        if (midGame) {
            applyMidGame();
            midGame = false;
            player = null;
            Forge.switchToLast();
            return true;
        }
        if (newGamePlus) { // round 839: back to the dialog, which keeps the copy until Start
            newGamePlus = false;
            System.out.println("[TFR-Custom] panel done for New Game+: " + presetName() + ", "
                    + CustomDifficulty.countChanges(work) + " change(s): " + CustomDifficulty.encode(work));
            java.util.function.Consumer<Map<String, String>> settings = onDoneSettings;
            IntConsumer pick = onDone;
            onDoneSettings = null;
            if (settings != null)
                settings.accept(new LinkedHashMap<>(work));
            if (pick != null)
                pick.accept(preset.getSelectedIndex());
            Forge.switchToLast();
            return true;
        }
        CustomDifficulty.setPending(work);
        int changes = CustomDifficulty.countChanges(work);
        System.out.println("[TFR-Custom] panel done: " + (changes == 0 ? presetName() + " (no changes)"
                : "Custom (" + presetName() + "), " + changes + " change(s)")
                + (work.isEmpty() ? "" : ": " + CustomDifficulty.encode(work)));
        if (onDone != null)
            onDone.accept(preset.getSelectedIndex());
        Forge.switchToLast();
        return true;
    }

    @Override
    public boolean back() {
        return done();
    }

    // ---- share codes (round 827) ------------------------------------------------------------------------------

    private TextraLabel shareText;
    /** What the dialog says now (its markup), for the console. */
    private String shareShown = "";
    /** The last paste's report, kept on the dialog until the next Copy or paste. */
    private String shareReport;

    private boolean openShare() {
        cancelRepeat();
        shareReport = null;
        Dialog d = new Dialog("", Controls.getSkin());
        shareText = Controls.newTextraLabel("");
        shareText.setWrap(true);
        d.getContentTable().add(shareText).width(Math.min(landscape ? 380f : 240f, stage.getWidth() - 40f)).pad(4).row();
        TextraButton copy = Controls.newTextButton("Copy", this::copyCode);
        copy.setName("share:copy");
        TextraButton paste = Controls.newTextButton("Paste code", this::pasteCode);
        paste.setName("share:paste");
        TextraButton close = Controls.newTextButton("Close", this::removeDialog);
        close.setName("share:close");
        float w = landscape ? 70 : 74, h = landscape ? 18 : 26;
        d.getButtonTable().add(copy).width(w).height(h).pad(3);
        d.getButtonTable().add(paste).width(w).height(h).pad(3);
        d.getButtonTable().add(close).width(w).height(h).pad(3);
        updateShareText();
        showDialog(d);
        return true;
    }

    private String myCode() {
        return CustomDifficulty.shareCode(presetName(), work);
    }

    private void updateShareText() {
        if (shareText == null)
            return;
        int n = CustomDifficulty.countChanges(work);
        StringBuilder sb = new StringBuilder("[%120][GOLD]Share code[%][WHITE]\n");
        if (shareReport != null) {
            sb.append(shareReport);
        } else {
            sb.append("Your setup: ").append(n == 0 ? presetName() + ", no changes" : "Custom (" + presetName() + "), " + n + " changed")
                    .append("\n[GOLD]").append(myCode()).append("[WHITE]\n[%85][LIGHT_GRAY]Copy puts it on the clipboard - paste it on "
                            + "Discord. Your Rules (Notoriety, leveling, reloads) are your own and stay out of the code.\n"
                            + "Paste code reads a code from the clipboard into this panel.");
        }
        setShareText(sb.toString());
    }

    private void setShareText(String text) {
        shareShown = text;
        shareText.setText(text);
        shareText.invalidateHierarchy();
        if (!dialogs.isEmpty()) {
            Dialog d = dialogs.peek();
            d.pack();
            d.setPosition(Math.round((stage.getWidth() - d.getWidth()) / 2), Math.round((stage.getHeight() - d.getHeight()) / 2));
        }
    }

    private void copyCode() {
        String code = myCode();
        Forge.getClipboard().setContents(code);
        shareReport = null;
        System.out.println("[TFR-Custom] share: copied " + code + " (" + CustomDifficulty.countChanges(work) + " change(s) on "
                + presetName() + (midGame ? ", mid-game" : "") + ")");
        updateShareText();
        setShareText(shareShown + "\n[%85][GREEN]Copied.");
    }

    private void pasteCode() {
        String text = Forge.getClipboard().hasContents() ? Forge.getClipboard().getContents() : "";
        shareReport = applyCode(text);
        updateShareText();
    }

    /** Loads a code into the panel's copy; returns the dialog's report. Nothing is kept until Done. */
    private String applyCode(String text) {
        CustomDifficulty.Pasted p = CustomDifficulty.readCode(text);
        String shortCode = text == null ? "" : text.trim();
        if (shortCode.length() > 40)
            shortCode = shortCode.substring(0, 40) + "...";
        if (p.error != null) {
            System.out.println("[TFR-Custom] share: paste refused - " + p.error);
            return "[RED]Not loaded:[WHITE] " + p.error + ".\n[%85][LIGHT_GRAY]Copy a code starting with "
                    + CustomDifficulty.CODE_PREFIX + " and press Paste code again.";
        }
        StringBuilder r = new StringBuilder();
        String madeOn = p.preset;
        boolean knownPreset = preset.getItems().indexOf(madeOn, false) >= 0;
        List<String> locked = new ArrayList<>();
        if (midGame) {
            r.append("Pasted mid-game (").append(presetName()).append("): ").append(shortCode).append('\n');
        } else {
            r.append("Pasted: ").append(shortCode).append('\n');
            if (knownPreset && !madeOn.equals(presetName()))
                preset.setSelectedIndex(preset.getItems().indexOf(madeOn, false)); // the header follows the code
        }
        // the panel's difficulty changes give way to the code's; the rules (and mid-game the locked rows) stay
        for (java.util.Iterator<String> it = work.keySet().iterator(); it.hasNext(); ) {
            Knob k = CustomDifficulty.knobs().get(it.next());
            if (k != null && !CustomDifficulty.isRule(k) && !locked(k))
                it.remove();
        }
        int loaded = 0;
        for (Map.Entry<String, String> e : p.values.entrySet()) {
            Knob k = CustomDifficulty.knobs().get(e.getKey());
            if (locked(k)) {
                locked.add(k.label);
                continue;
            }
            if (!CustomDifficulty.same(k, e.getValue(), CustomDifficulty.presetRaw(k, presetName())))
                work.put(k.id, e.getValue());
            loaded++;
        }
        r.append("[GREEN]Loaded ").append(loaded).append(loaded == 1 ? " setting" : " settings")
                .append(midGame ? " over " + presetName() + (madeOn.equals(presetName()) ? "" : " (the code was made on " + madeOn + ")")
                        : " on " + presetName() + (knownPreset ? "" : " (the code's " + madeOn + " is not a difficulty here)"))
                .append("[WHITE]");
        if (!p.clamped.isEmpty())
            r.append("\n").append(p.clamped.size()).append(" clamped: ").append(String.join(", ", p.clamped));
        if (!p.dropped.isEmpty())
            r.append("\n").append(p.dropped.size()).append(" dropped: ").append(String.join(", ", p.dropped));
        if (!locked.isEmpty())
            r.append("\n").append(locked.size()).append(" locked: ").append(String.join(", ", locked)).append(" (new game only)");
        r.append("\n[%85][LIGHT_GRAY]").append(midGame ? "Nothing changes until you press Done." : "Nothing is kept until you press Done.");
        System.out.println("[TFR-Custom] share: pasted " + text.trim() + " - made on " + madeOn + ", loaded " + loaded
                + " on " + presetName() + (midGame ? " (mid-game)" : "") + ", clamped " + p.clamped.size() + ", dropped "
                + p.dropped.size() + ", locked " + locked.size() + "; panel now " + CustomDifficulty.encode(work));
        refill();
        return r.toString();
    }

    private boolean resetAll() {
        if (midGame) { // round 826: the new-game-only rows are locked, so they keep the save's values
            int n = 0;
            for (java.util.Iterator<String> it = work.keySet().iterator(); it.hasNext(); ) {
                Knob k = CustomDifficulty.knobs().get(it.next());
                if (k == null || !k.isNewGameOnly()) {
                    it.remove();
                    n++;
                }
            }
            if (n > 0)
                System.out.println("[TFR-Custom] panel: reset all mid-game (" + n + " change(s); locked rows kept)");
            refill();
            return true;
        }
        if (!work.isEmpty())
            System.out.println("[TFR-Custom] panel: reset all (" + work.size() + " change(s))");
        work.clear();
        refill();
        return true;
    }

    private void setLevel(boolean expertLevel) {
        expert = expertLevel;
        refill();
    }

    /** The value a row shows: the change, else the preset's own. */
    private String valueOf(Knob k) {
        String v = work.get(k.id);
        return v != null ? v : CustomDifficulty.presetRaw(k, presetName());
    }

    /** Sets a knob in the copy; the preset's own value removes the change. */
    private void put(Knob k, String value) {
        String before = valueOf(k);
        if (CustomDifficulty.same(k, value, CustomDifficulty.presetRaw(k, presetName())))
            work.remove(k.id);
        else
            work.put(k.id, value);
        if (!CustomDifficulty.same(k, before, value))
            System.out.println("[TFR-Custom] panel: " + k.id + " " + CustomDifficulty.display(k, before) + " -> "
                    + CustomDifficulty.display(k, value) + (work.containsKey(k.id) ? "" : " (the preset's)"));
    }

    private List<Knob> visible() {
        String q = search.getText();
        boolean searching = q != null && !q.trim().isEmpty();
        List<Knob> out = new ArrayList<>();
        for (Knob k : CustomDifficulty.knobs().values()) {
            if (!searching && !ALL.equals(tab) && !tab.equals(k.category))
                continue;
            if (!expert && !"basic".equals(k.level))
                continue;
            if (changedOnly && !work.containsKey(k.id))
                continue;
            if (!CustomDifficulty.matches(k, q))
                continue;
            out.add(k);
        }
        if (!searching && ALL.equals(tab)) // the All tab runs in tab order, as the tabs list them
            out.sort(java.util.Comparator.comparingInt(k -> CustomDifficulty.categories().indexOf(k.category)));
        return out;
    }

    /** Rebuilds the tabs, the rows and the count - after every change; the list keeps its scroll position. */
    private void refill() {
        float scroll = rowScroll.getScrollY();
        Map<String, Knob> all = CustomDifficulty.knobs();

        updateCount();
        basicButton.setColor(expert ? Color.WHITE : Color.GOLD);
        expertButton.setColor(expert ? Color.GOLD : Color.WHITE);

        tabs.clearChildren();
        List<String> names = new ArrayList<>();
        names.add(ALL);
        names.addAll(CustomDifficulty.categories());
        boolean searching = search.getText() != null && !search.getText().trim().isEmpty();
        for (String name : names) {
            int n = 0;
            for (String id : work.keySet()) {
                Knob k = all.get(id);
                if (k != null && (ALL.equals(name) ? !CustomDifficulty.isRule(k) : name.equals(k.category)))
                    n++;
            }
            boolean on = !searching && name.equals(tab);
            TextraButton b = Controls.newTextButton("[%80]" + (on ? "[GOLD]" : "") + name + (n > 0 ? "  [GOLD]" + n : ""), () -> {
                tab = name;
                search.setText("");
                refill();
            });
            b.setName("tab:" + name);
            tabs.add(b).width(84).height(17).padBottom(2).row();
        }
        if (tabBox != null) {
            Array<String> items = new Array<>();
            tabBoxTabs.clear();
            for (String name : names) {
                int n = 0;
                for (String id : work.keySet()) {
                    Knob k = all.get(id);
                    if (k != null && (ALL.equals(name) ? !CustomDifficulty.isRule(k) : name.equals(k.category)))
                        n++;
                }
                items.add(name + (n > 0 ? "  (" + n + ")" : ""));
                tabBoxTabs.add(name);
            }
            fillingTabBox = true;
            tabBox.setItems(items);
            tabBox.setSelectedIndex(Math.max(0, tabBoxTabs.indexOf(tab)));
            fillingTabBox = false;
        }

        rows.clearChildren();
        valueLabels.clear();
        String last = null;
        List<Knob> shown = visible();
        for (Knob k : shown) {
            String heading = searching || ALL.equals(tab)
                    ? k.category + (k.group.isEmpty() ? "" : " - " + k.group) : k.group;
            if (!heading.equals(last)) {
                rows.add(Controls.newTextraLabel("[%80][GOLD]" + heading)).left().padTop(last == null ? 0 : 4).row();
                last = heading;
            }
            rows.add(row(k)).growX().padBottom(landscape ? 1 : 3).row();
        }
        if (shown.isEmpty())
            rows.add(Controls.newTextraLabel("[%85][LIGHT_GRAY]" + (changedOnly ? "No changed settings here." : "Nothing matches."))).left().row();
        rows.add().expandY();

        rowScroll.layout();
        rowScroll.setScrollY(scroll);
        rowScroll.updateVisualScroll();
    }

    private void updateCount() {
        int changed = CustomDifficulty.countChanges(work); // round 825: the rules are not difficulty changes
        countLabel.setText(changed == 0 ? "[%85][LIGHT_GRAY]No changes - " + (midGame ? "playing as " : "plays as ") + presetName()
                : "[%85][GOLD]" + changed + " changed - \"Custom (" + presetName() + ")\"");
    }

    private Table row(Knob k) {
        boolean changed = work.containsKey(k.id);
        String value = valueOf(k);
        Table r = new Table();
        r.left();
        if (changed)
            r.setBackground(tint());

        Table name = new Table();
        TextraLabel label = Controls.newTextraLabel("[%90]" + k.label + ("expert".equals(k.level) ? " [%70][LIGHT_GRAY](Expert)" : ""));
        label.setWrap(true);
        name.add(label).width(landscape ? 158 : 98).left().row();
        String sub = presetName() + ": " + CustomDifficulty.display(k, CustomDifficulty.presetRaw(k, presetName()))
                + (locked(k) ? "" : k.isNewGameOnly() ? " - new game only" : "");
        name.add(Controls.newTextraLabel("[%70][LIGHT_GRAY]" + sub)).left();
        name.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.enabled);
        name.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                showHelp(k);
            }
        });
        r.add(name).left().width(landscape ? 160 : 100).padLeft(3).expandX();

        if (locked(k)) {
            TextraLabel shown = Controls.newTextraLabel("[%85][LIGHT_GRAY]" + CustomDifficulty.display(k, value) + "   locked");
            shown.setAlignment(Align.center);
            shown.setName("locked:" + k.id);
            r.add(shown).width(landscape ? 82 : 90).center();
        } else if ("bool".equals(k.type)) {
            CheckBox box = Controls.newCheckBox("");
            box.setName("check:" + k.id);
            box.setChecked(Boolean.parseBoolean(value));
            box.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    put(k, String.valueOf(box.isChecked()));
                    showHelp(k);
                    refill();
                }
            });
            r.add(box).width(landscape ? 82 : 90).height(button).center();
        } else {
            r.add(stepper(k, -1)).width(button).height(button);
            TextraLabel shown = Controls.newTextraLabel("[%90]" + (changed ? "[GOLD]" : "") + CustomDifficulty.display(k, value));
            shown.setAlignment(Align.center);
            valueLabels.put(k.id, shown);
            r.add(shown).width(landscape ? 50 : 38).center();
            r.add(stepper(k, 1)).width(button).height(button);
        }
        if (changed && !locked(k)) {
            TextraButton reset = Controls.newTextButton("[%80]<", () -> {
                put(k, CustomDifficulty.presetRaw(k, presetName()));
                refill();
            });
            reset.setName("reset:" + k.id);
            r.add(reset).width(button).height(button).padLeft(landscape ? 6 : 4).padRight(3);
        } else {
            r.add().width(button + (landscape ? 9 : 7));
        }
        return r;
    }

    /** A - or + button: one step on the touch, then every 0.08 s while held (after 0.45 s). A refill rebuilds the
     *  button under the finger, so the steps update the row in place and the list is rebuilt when the touch ends. */
    private TextraButton stepper(Knob k, int dir) {
        TextraButton b = Controls.newTextButton(dir < 0 ? "[%90]-" : "[%90]+");
        b.setName((dir < 0 ? "minus:" : "plus:") + k.id);
        b.addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                cancelRepeat();
                stepOnce(k, dir);
                repeat = Timer.schedule(new Timer.Task() {
                    @Override
                    public void run() { // libGDX's Timer posts to the render thread
                        stepOnce(k, dir);
                    }
                }, 0.45f, 0.08f);
                return true;
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                cancelRepeat();
                refill();
            }
        });
        return b;
    }

    private void stepOnce(Knob k, int dir) {
        String next = CustomDifficulty.step(k, valueOf(k), dir);
        if (CustomDifficulty.same(k, next, valueOf(k))) {
            cancelRepeat(); // at the end of the range
            return;
        }
        put(k, next);
        showHelp(k);
        // in place while the button is held (a refill would take the button away from under the finger); the row's
        // tint, reset arrow, tab counts and filters catch up on the touch's end
        TextraLabel shown = valueLabels.get(k.id);
        if (shown != null)
            shown.setText("[%90][GOLD]" + CustomDifficulty.display(k, valueOf(k)));
        updateCount();
    }

    private void cancelRepeat() {
        if (repeat != null) {
            repeat.cancel();
            repeat = null;
        }
    }

    private void showHelp(Knob k) {
        help.setText("[%85][GOLD]" + k.label + ":[WHITE] " + k.tooltip
                + (locked(k) ? " [LIGHT_GRAY](New game only.)"
                : midGame ? " [LIGHT_GRAY](On Done.)"
                : newGamePlus ? " [LIGHT_GRAY](For the new run.)"
                : k.isNewGameOnly() ? " [LIGHT_GRAY](New game only.)" : " [LIGHT_GRAY](Can be changed later in Settings.)"));
    }

    private static com.badlogic.gdx.scenes.scene2d.utils.Drawable tint() {
        if (white == null) {
            Pixmap p = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            p.setColor(Color.WHITE);
            p.fill();
            white = new Texture(p);
            p.dispose();
        }
        return new TextureRegionDrawable(new TextureRegion(white)).tint(CHANGED_TINT); // a SpriteDrawable
    }

    // ---- console (round 823 testing) ----------------------------------------------------------------------------

    /** custom panel: what the panel shows now; custom panel search TEXT: types TEXT into its search box. */
    public static String console(String[] s) {
        if (object == null)
            return "The panel has not been opened this launch.";
        if (s.length >= 2 && "code".equals(s[1])) {
            // round 827: the agent bridge cannot read or fill the clipboard - "custom panel code" prints this panel's
            // code; "custom panel code paste TEXT" loads TEXT as Paste code would (the dialog open). It does not go
            // through the clipboard: read straight back after a set, the PC clipboard came back empty (agent-827 test;
            // a code set outside the game and pasted with the button read fine)
            if (s.length >= 4 && "paste".equals(s[2])) {
                StringBuilder t = new StringBuilder();
                for (int i = 3; i < s.length; i++)
                    t.append(i > 3 ? " " : "").append(s[i]);
                if (object.shareText == null || object.dialogs.isEmpty())
                    object.openShare();
                object.shareReport = object.applyCode(t.toString());
                object.updateShareText();
                return object.shareShown.replaceAll("\\[[^\\]]*\\]", "");
            }
            return object.myCode();
        }
        if (s.length >= 3 && "search".equals(s[1])) {
            StringBuilder q = new StringBuilder();
            for (int i = 2; i < s.length; i++)
                q.append(i > 2 ? " " : "").append(s[i]);
            object.search.setText(q.toString());
            object.refill();
        } else if (s.length == 2 && "search".equals(s[1])) {
            object.search.setText("");
            object.refill();
        } else if (s.length >= 3 && "tab".equals(s[1])) {
            // round 824: portrait's tab box is a SelectBox, which the agent bridge cannot open - picked here, so its own
            // change listener runs; landscape takes the tab directly
            StringBuilder name = new StringBuilder();
            for (int i = 2; i < s.length; i++)
                name.append(i > 2 ? " " : "").append(s[i]);
            if (object.tabBox != null) {
                int i = object.tabBoxTabs.indexOf(name.toString());
                if (i < 0)
                    return "No tab " + name + " (" + object.tabBoxTabs + ")";
                object.tabBox.setSelectedIndex(i);
            } else {
                object.tab = name.toString();
                object.refill();
            }
        }
        StringBuilder sb = new StringBuilder("Panel on " + object.presetName() + (object.midGame ? " (mid-game)" : "") + ", tab " + object.tab
                + (object.search.getText().isEmpty() ? "" : ", search \"" + object.search.getText() + "\"")
                + (object.expert ? ", Expert" : ", Basic") + (object.changedOnly ? ", changed only" : "") + ": "
                + CustomDifficulty.encode(object.work));
        for (Knob k : object.visible())
            sb.append("\n ").append(k.category).append(" / ").append(k.id).append(' ')
                    .append(CustomDifficulty.display(k, object.valueOf(k)))
                    .append(object.work.containsKey(k.id) ? " (changed)" : "")
                    .append(object.locked(k) ? " (locked)" : "");
        return sb.toString();
    }
}
