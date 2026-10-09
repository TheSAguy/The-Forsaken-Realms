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

/**
 * Round 494 - Ascendance's dialogs, opened by tapping the HUD's "Asc" panel (AscendanceDisplayActor): the pick-1-of-3
 * reward while one waits (one after another until none is left, or "Not now"; round 518: a "Re-roll" once a level, for
 * shards), else the status - level, title, Power,
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
            withIcon(button, Ascendance.iconRegion(id)); // round 522
            dialog.getButtonTable().add(button).width(width()).colspan(2).padTop(2f).row();
        }
        // Round 518 (the user: "Let's add a Skill Re-roll. Can only do it once per level"): a new offer for shards, beside
        // "Not now"; greyed when this level's re-roll is used or the shards are short.
        int cost = Ascendance.rerollCost();
        boolean open = Ascendance.canReroll();
        TextraButton reroll = Controls.newTextButton(open ? "Re-roll " + cost + " [+Shards]" : "Re-rolled", () -> {
            stage.hideDialog();
            Ascendance.reroll();
            Gdx.app.postRunnable(AscendanceUI::openChoice);
        });
        reroll.setDisabled(!open || WorldSave.getCurrentSave().getPlayer().getShards() < cost);
        dialog.getButtonTable().add(reroll).width(width() / 2f).padTop(4f).padRight(4f);
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
        addRow(dialog, "[%85]" + Ascendance.ICON + " Power " + progress[0] + " / " + progress[1] + " to the next level"); // round 517
        int next = Ascendance.nextMainSlotLevel();
        addRow(dialog, "[%85]" + Ascendance.mainItemsLabel(player) + (next > 0 ? " - one more at Ascendance " + next : ""));
        int nextCompanion = Ascendance.nextCompanionLevel(); // round 502
        addRow(dialog, "[%85]" + Ascendance.companionsLabel(player)
                + (nextCompanion > 0 ? " - one more at Ascendance " + nextCompanion : ""));
        // Round 513 (the user: "Not sure why 'Spoilsman' is showing here. We can remove since it's on the Level sheet"):
        // the lasting picks' line is gone - the Level sheet lists every pick by the level it came at.
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
            // Round 522: a lasting pick's icon at the rank it reached; other lines carry their own icon in the text.
            com.badlogic.gdx.scenes.scene2d.ui.Image icon = icon(Ascendance.historyIconRegion(what));
            if (icon != null)
                rows.add(icon).size(SHEET_ICON).top().padRight(3f);
            else
                rows.add().width(SHEET_ICON).padRight(3f);
            rows.add(text).width(width() - 30f - SHEET_ICON - 3f).left().padBottom(2f).row();
        }
        if (Ascendance.level() < 1)
            rows.add(Controls.newTypingLabel("[%85]Nothing yet - the first level comes with the first wins.")).width(width())
                    .colspan(3).row();
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

    /** Round 522: the reward icon's box on a choice button, and on a Level-sheet line. */
    private static final float CHOICE_ICON = 26f;
    private static final float SHEET_ICON = 12f;
    private static boolean iconAtlasMissing;

    /** Round 522 (the user: "find Icons for all the Level upgrades we have ... each upgrade to kinda look like the first,
     *  but each time more impressive"): the reward's icon left of its text - Heroes III's Basic/Advanced/Expert skill
     *  pictures, medieval skill icons with silver and gold rims, the game's own resource icons - and an empty cell as wide
     *  on the right, so the text stays centered on the button. */
    private static void withIcon(TextraButton button, String region) {
        com.badlogic.gdx.scenes.scene2d.ui.Image image = icon(region);
        if (image == null)
            return;
        com.github.tommyettinger.textra.TextraLabel label = button.getTextraLabel();
        button.clearChildren();
        button.add(image).size(CHOICE_ICON).pad(2f, 4f, 2f, 2f);
        button.add(label).expandX().fillX();
        button.add().width(CHOICE_ICON + 6f);
    }

    /** Round 522: an icon of Ascendance.ICON_ATLAS, or null. A lasting rank past the drawn ones (a config that raised
     *  maxPicks) shows the highest drawn. */
    private static com.badlogic.gdx.scenes.scene2d.ui.Image icon(String region) {
        if (region == null || iconAtlasMissing)
            return null;
        try {
            com.badlogic.gdx.graphics.g2d.Sprite sprite = Config.instance().getAtlasSprite(Ascendance.ICON_ATLAS, region);
            int bar = region.lastIndexOf('_');
            if (sprite == null && bar > 0) {
                int rank;
                try {
                    rank = Integer.parseInt(region.substring(bar + 1));
                } catch (NumberFormatException e) {
                    rank = 0;
                }
                for (rank--; sprite == null && rank >= 1; rank--)
                    sprite = Config.instance().getAtlasSprite(Ascendance.ICON_ATLAS, region.substring(0, bar + 1) + rank);
            }
            if (sprite == null) {
                System.out.println("[TFR-Ascend] no icon '" + region + "' in " + Ascendance.ICON_ATLAS);
                return null;
            }
            com.badlogic.gdx.scenes.scene2d.ui.Image image = new com.badlogic.gdx.scenes.scene2d.ui.Image(
                    new com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable(sprite));
            image.setScaling(com.badlogic.gdx.utils.Scaling.fit);
            return image;
        } catch (RuntimeException e) { // the atlas itself missing (an older plane folder): no icons, once
            iconAtlasMissing = true;
            System.out.println("[TFR-Ascend] reward icons unavailable (" + Ascendance.ICON_ATLAS + "): " + e.getMessage());
            return null;
        }
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
