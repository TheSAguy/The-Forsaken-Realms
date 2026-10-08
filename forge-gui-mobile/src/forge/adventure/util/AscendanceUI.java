package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TypingLabel;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.stage.GameStage;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.WorldSave;

import java.util.List;
import java.util.Map;

/**
 * Round 494 - Ascendance's dialogs, opened by tapping the HUD's "Asc" panel (AscendanceDisplayActor): the pick-1-of-3
 * reward while one waits (one after another until none is left, or "Not now"), else the status - level, title, Power,
 * main items, the lasting rewards taken. Built on the stage's own dialog (the world map's or the town's), like
 * WorldStage's bonfire and gate dialogs, so Esc closes it (round 491) and the bridge sees its buttons.
 */
public final class AscendanceUI {
    private AscendanceUI() {
    }

    private static GameStage stage() {
        return MapStage.getInstance().isInMap() ? MapStage.getInstance() : WorldStage.getInstance();
    }

    private static float width() {
        return forge.Forge.isLandscapeMode() ? 250f : 230f;
    }

    /** The HUD panel's tap: the waiting reward if there is one, else the status. Ignored while another dialog is up. */
    public static void openFromHud() {
        if (!Ascendance.isActive() || stage().isDialogOnlyInput())
            return;
        if (Ascendance.pendingChoices() > 0)
            openChoice();
        else
            openStatus();
    }

    public static void openChoice() {
        List<String> offer = Ascendance.currentOffer();
        if (offer.isEmpty())
            return;
        GameStage stage = stage();
        Dialog dialog = prepare(stage);
        int level = Ascendance.offerLevel();
        int waiting = Ascendance.pendingChoices();
        addRow(dialog, "[GOLD]Ascendance " + level + "[] - choose one" + (waiting > 1 ? " [%80](" + waiting + " waiting)" : ""));
        for (String id : offer) {
            TextraButton button = Controls.newTextButton(Ascendance.describe(id, level), () -> {
                stage.hideDialog();
                Ascendance.choose(id);
                if (Ascendance.pendingChoices() > 0) // the next waiting level, as soon as this dialog has gone
                    Gdx.app.postRunnable(AscendanceUI::openChoice);
            });
            dialog.getButtonTable().add(button).width(width()).padTop(2f).row();
        }
        dialog.getButtonTable().add(Controls.newTextButton("Not now", stage::hideDialog)).width(width() / 2f).padTop(4f).row();
        dialog.setKeepWithinStage(true);
        stage.showDialog();
    }

    public static void openStatus() {
        GameStage stage = stage();
        Dialog dialog = prepare(stage);
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        int[] progress = Ascendance.progress();
        String title = Ascendance.title();
        addRow(dialog, "[GOLD]Ascendance " + Ascendance.level() + "[]" + (title.isEmpty() ? "" : " - " + title));
        addRow(dialog, "[%85]Power " + progress[0] + " / " + progress[1] + " to the next level");
        int next = Ascendance.nextMainSlotLevel();
        addRow(dialog, "[%85]" + Ascendance.mainItemsLabel(player) + (next > 0 ? " - one more at Ascendance " + next : ""));
        StringBuilder lasting = new StringBuilder();
        for (Map.Entry<String, Integer> pick : player.ascendance().picks.entrySet()) {
            if (lasting.length() > 0)
                lasting.append(", ");
            lasting.append(Ascendance.lastingName(pick.getKey())).append(" x").append(pick.getValue());
        }
        addRow(dialog, "[%85]" + (lasting.length() == 0 ? "No lasting rewards yet." : lasting.toString()));
        dialog.getButtonTable().add(Controls.newTextButton("Level sheet", () -> { // round 496
            stage.hideDialog();
            Gdx.app.postRunnable(AscendanceUI::openLevelSheet);
        })).width(width() / 2f).padRight(4f);
        dialog.getButtonTable().add(Controls.newTextButton("Close", stage::hideDialog)).width(width() / 2f).row();
        dialog.setKeepWithinStage(true);
        stage.showDialog();
    }

    /** Round 496 (the user: "We should add a Level sheet that shows what you picked on each level"), on the map. */
    public static void openLevelSheet() {
        GameStage stage = stage();
        if (stage.isDialogOnlyInput())
            return;
        Dialog dialog = prepare(stage);
        fillLevelSheet(dialog);
        dialog.getButtonTable().add(Controls.newTextButton("Close", stage::hideDialog)).width(width() / 2f).row();
        dialog.setKeepWithinStage(true);
        stage.showDialog();
    }

    /** Round 496: the same sheet over a menu scene (the character sheet's "Level sheet" button). */
    public static void openLevelSheet(forge.adventure.scene.UIScene scene) {
        Dialog dialog = new Dialog("", Controls.getSkin());
        fillLevelSheet(dialog);
        dialog.getButtonTable().add(Controls.newTextButton("Close", scene::removeDialog)).width(width() / 2f).row();
        dialog.setKeepWithinStage(true);
        scene.showDialog(dialog);
    }

    /** Level 2 to the level reached: what each gave - a pick, or a milestone's life, slot and title; a waiting level says
     *  so. Scrolls once it is longer than the screen. */
    private static void fillLevelSheet(Dialog dialog) {
        String title = Ascendance.title();
        addRow(dialog, "[GOLD]Level sheet[] - Ascendance " + Ascendance.level() + (title.isEmpty() ? "" : ", " + title));
        java.util.TreeMap<Integer, String> history = Ascendance.levelHistory();
        List<Integer> waiting = Ascendance.pendingLevelList();
        com.badlogic.gdx.scenes.scene2d.ui.Table rows = new com.badlogic.gdx.scenes.scene2d.ui.Table();
        for (int level = 1; level <= Ascendance.level(); level++) { // round 497: level 1 is a level-up now
            String what = history.get(level);
            if (what == null)
                what = waiting.contains(level) ? "[GOLD]a reward waits - tap the Asc panel[]" : "[%80](before the level sheet)";
            TypingLabel number = Controls.newTypingLabel("[%85]" + level);
            number.skipToTheEnd();
            TypingLabel text = Controls.newTypingLabel("[%85]" + what);
            text.setWrap(true);
            text.skipToTheEnd();
            rows.add(number).width(22f).top().left();
            rows.add(text).width(width() - 30f).left().padBottom(2f).row();
        }
        if (Ascendance.level() < 1)
            rows.add(Controls.newTypingLabel("[%85]Nothing yet - the first level comes with the first wins.")).width(width()).row();
        com.badlogic.gdx.scenes.scene2d.ui.ScrollPane pane = new com.badlogic.gdx.scenes.scene2d.ui.ScrollPane(rows);
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        rows.pack();
        float cap = forge.Forge.isLandscapeMode() ? 150f : 320f;
        dialog.getContentTable().add(pane).width(width() + 6f).height(Math.min(cap, rows.getPrefHeight() + 4f)).row();
        // The wheel scrolls the list, not the stage under it, once the dialog is up (UIScene.showDialog takes the focus).
        pane.addListener(new com.badlogic.gdx.scenes.scene2d.InputListener() {
            @Override
            public void enter(com.badlogic.gdx.scenes.scene2d.InputEvent event, float x, float y, int pointer,
                              com.badlogic.gdx.scenes.scene2d.Actor fromActor) {
                if (pointer == -1 && pane.getStage() != null)
                    pane.getStage().setScrollFocus(pane);
            }
        });
    }

    private static Dialog prepare(GameStage stage) {
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        return dialog;
    }

    private static void addRow(Dialog dialog, String text) {
        TypingLabel label = Controls.newTypingLabel(text);
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(width()).row();
    }
}
