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
        dialog.getButtonTable().add(Controls.newTextButton("Close", stage::hideDialog)).width(width() / 2f).row();
        dialog.setKeepWithinStage(true);
        stage.showDialog();
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
