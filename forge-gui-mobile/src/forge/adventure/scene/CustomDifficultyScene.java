package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
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
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.CustomDifficulty;
import forge.adventure.util.CustomDifficulty.Knob;

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
 * {@code ui/custom_difficulty.json}; portrait is round 824. {@code [TFR-Custom] panel ...} lines; console
 * {@code custom panel} lists what the panel shows, {@code custom panel search TEXT} types into its search box.
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
    private final Map<String, TextraLabel> valueLabels = new java.util.HashMap<>();

    private CustomDifficultyScene() {
        super("ui/custom_difficulty.json");
        content = ui.findActor("content");
        content.top().left();

        // header: the title, the preset, how many are changed
        Table header = new Table();
        header.add(Controls.newTextraLabel("[%120]Custom Difficulty")).left().padRight(12);
        header.add(Controls.newTextraLabel("[%85][LIGHT_GRAY]Based on")).padRight(4);
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
        header.add(preset).width(62).height(16).padRight(8);
        countLabel = Controls.newTextraLabel("");
        header.add(countLabel).left().expandX();
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
        bar.add(search).width(120).height(15).padRight(8);
        basicButton = Controls.newTextButton("[%80]Basic", () -> setLevel(false));
        basicButton.setName("level:basic");
        expertButton = Controls.newTextButton("[%80]Expert", () -> setLevel(true));
        expertButton.setName("level:expert");
        bar.add(basicButton).width(34).height(15);
        bar.add(expertButton).width(38).height(15).padRight(10);
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
        body.add(tabs).top().width(84).padRight(4);
        rows.top().left();
        rowScroll = new ScrollPane(rows, Controls.getSkin());
        rowScroll.setFadeScrollBars(false);
        rowScroll.setScrollingDisabled(true, false);
        body.add(rowScroll).grow();
        content.add(body).grow().row();

        help = Controls.newTextraLabel("");
        help.setWrap(true);
        content.add(help).growX().height(20).padTop(2).left();

        ui.onButtonPress("done", this::done);
        ui.onButtonPress("resetAll", this::resetAll);
        TextraButton share = ui.findActor("share");
        if (share != null)
            share.setDisabled(true); // round 827
    }

    /** NewGameScene's Advanced button: the panel on this preset and the pending overrides; onDone gets the preset back. */
    public void open(int presetIndex, Map<String, String> current, IntConsumer onDone) {
        this.onDone = onDone;
        work.clear();
        if (current != null)
            work.putAll(current);
        preset.setSelectedIndex(Math.max(0, Math.min(presetIndex, preset.getItems().size - 1)));
        help.setText("[%85][LIGHT_GRAY]Tap a setting's name to read what it does.");
        System.out.println("[TFR-Custom] panel opened on " + presetName() + " with " + work.size() + " change(s), "
                + CustomDifficulty.knobs().size() + " knob(s)");
        Forge.switchScene(this);
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
        CustomDifficulty.setPending(work);
        System.out.println("[TFR-Custom] panel done: " + (work.isEmpty() ? presetName() + " (no changes)"
                : "Custom (" + presetName() + "), " + work.size() + " change(s): " + CustomDifficulty.encode(work)));
        if (onDone != null)
            onDone.accept(preset.getSelectedIndex());
        Forge.switchToLast();
        return true;
    }

    @Override
    public boolean back() {
        return done();
    }

    private boolean resetAll() {
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
                if (k != null && (ALL.equals(name) || name.equals(k.category)))
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
            rows.add(row(k)).growX().padBottom(1).row();
        }
        if (shown.isEmpty())
            rows.add(Controls.newTextraLabel("[%85][LIGHT_GRAY]" + (changedOnly ? "No changed settings here." : "Nothing matches."))).left().row();
        rows.add().expandY();

        rowScroll.layout();
        rowScroll.setScrollY(scroll);
        rowScroll.updateVisualScroll();
    }

    private void updateCount() {
        int changed = work.size();
        countLabel.setText(changed == 0 ? "[%85][LIGHT_GRAY]No changes - plays as " + presetName()
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
        name.add(label).width(158).left().row();
        String sub = presetName() + ": " + CustomDifficulty.display(k, CustomDifficulty.presetRaw(k, presetName()))
                + (k.isNewGameOnly() ? " - new game only" : "");
        name.add(Controls.newTextraLabel("[%70][LIGHT_GRAY]" + sub)).left();
        name.setTouchable(com.badlogic.gdx.scenes.scene2d.Touchable.enabled);
        name.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                showHelp(k);
            }
        });
        r.add(name).left().width(160).padLeft(3).expandX();

        if ("bool".equals(k.type)) {
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
            r.add(box).width(82).center();
        } else {
            r.add(stepper(k, -1)).width(14).height(14);
            TextraLabel shown = Controls.newTextraLabel("[%90]" + (changed ? "[GOLD]" : "") + CustomDifficulty.display(k, value));
            shown.setAlignment(Align.center);
            valueLabels.put(k.id, shown);
            r.add(shown).width(50).center();
            r.add(stepper(k, 1)).width(14).height(14);
        }
        if (changed) {
            TextraButton reset = Controls.newTextButton("[%80]<", () -> {
                put(k, CustomDifficulty.presetRaw(k, presetName()));
                refill();
            });
            reset.setName("reset:" + k.id);
            r.add(reset).width(14).height(14).padLeft(6).padRight(3);
        } else {
            r.add().width(23);
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
                + (k.isNewGameOnly() ? " [LIGHT_GRAY](New game only.)" : " [LIGHT_GRAY](Can be changed later in Settings.)"));
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
        if (s.length >= 3 && "search".equals(s[1])) {
            StringBuilder q = new StringBuilder();
            for (int i = 2; i < s.length; i++)
                q.append(i > 2 ? " " : "").append(s[i]);
            object.search.setText(q.toString());
            object.refill();
        } else if (s.length == 2 && "search".equals(s[1])) {
            object.search.setText("");
            object.refill();
        }
        StringBuilder sb = new StringBuilder("Panel on " + object.presetName() + ", tab " + object.tab
                + (object.search.getText().isEmpty() ? "" : ", search \"" + object.search.getText() + "\"")
                + (object.expert ? ", Expert" : ", Basic") + (object.changedOnly ? ", changed only" : "") + ": "
                + CustomDifficulty.encode(object.work));
        for (Knob k : object.visible())
            sb.append("\n ").append(k.category).append(" / ").append(k.id).append(' ')
                    .append(CustomDifficulty.display(k, object.valueOf(k)))
                    .append(object.work.containsKey(k.id) ? " (changed)" : "");
        return sb.toString();
    }
}
