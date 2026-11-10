package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Round 1006 (the user: "Yes, fix it", World Standings' Reputation info dialog). Dialog.show() packs the window to its
 * content with no cap, and every scene is laid out on a 480x270 (270x480 portrait) stage, so a long body pushed the
 * window past the top of the screen and cut off its title and first line. At 1280x720 the Reputation table and the
 * Balance Sheet both ran over.
 * <p>
 * Call {@link #fitToStage(Dialog)} right AFTER the dialog is shown. It measures the shown window against its own
 * stage, and only when the window is taller than the screen does it move the content rows into a ScrollPane just tall
 * enough to bring the whole window back on screen. A dialog that already fits is untouched. Running after show (not
 * before, as EconomyBuildings.makeContentScrollable does) means the real stage size is known in either orientation, and
 * the scene's keyboard selection, gathered at show, never sees the pane.
 */
public final class DialogFit {
    /** Space kept free above and below the window, in stage units. */
    private static final float MARGIN = 6f;
    /** The pane never shrinks below this, however tall the buttons are. */
    private static final float MIN_PANE = 48f;

    private DialogFit() {}

    public static void fitToStage(Dialog dialog) {
        Stage stage = dialog.getStage();
        if (stage == null)
            return;
        dialog.pack();
        float room = stage.getHeight() - 2 * MARGIN;
        float tall = dialog.getPrefHeight();
        if (tall <= room)
            return;
        Table content = dialog.getContentTable();
        content.validate();
        float cap = Math.max(MIN_PANE, content.getPrefHeight() - (tall - room));

        // The rows keep their laid-out widths, pads, spacing, spans and row breaks; only their parent changes.
        Table body = new Table();
        List<Cell<?>> cells = new ArrayList<>();
        for (Cell<?> cell : content.getCells())
            cells.add(cell);
        int rows = 0;
        for (Cell<?> cell : cells) {
            Actor actor = cell.getActor();
            if (actor == null) {
                if (cell.isEndRow())
                    body.row();
                continue;
            }
            float width = cell.getActorWidth() > 0 ? cell.getActorWidth() : actor.getWidth();
            Cell<Actor> moved = body.add(actor).width(width)
                    .pad(cell.getPadTop(), cell.getPadLeft(), cell.getPadBottom(), cell.getPadRight())
                    .space(cell.getSpaceTop(), cell.getSpaceLeft(), cell.getSpaceBottom(), cell.getSpaceRight());
            if (cell.getAlign() != null)
                moved.align(cell.getAlign());
            if (cell.getColspan() != null && cell.getColspan() > 1)
                moved.colspan(cell.getColspan());
            if (cell.isEndRow()) {
                body.row();
                rows++;
            }
        }
        content.clear();
        ScrollPane pane = new ScrollPane(body, scrollStyle());
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        pane.setOverscroll(false, false);
        body.pack();
        ScrollPane.ScrollPaneStyle style = pane.getStyle();
        float bar = style.vScrollKnob == null ? 0f : Math.max(style.vScrollKnob.getMinWidth(),
                style.vScroll == null ? 0f : style.vScroll.getMinWidth());
        content.add(pane).width(body.getPrefWidth() + bar + 4f).height(cap).row();

        dialog.pack();
        dialog.setPosition(Math.round((stage.getWidth() - dialog.getWidth()) / 2f),
                Math.round((stage.getHeight() - dialog.getHeight()) / 2f));

        // The mouse wheel goes to the scroll-focus actor, and the scenes hand that to the DIALOG (UIScene.showDialog,
        // Dialog.show, and again whenever a dialog stacked above this one closes). Pass it on to the pane now, and
        // each time the dialog takes it back - the same hand-off round 160 gave EconomyBuildings' scroll dialogs.
        stage.setScrollFocus(pane);
        dialog.addListener(new FocusListener() {
            @Override
            public void scrollFocusChanged(FocusEvent event, Actor actor, boolean focused) {
                if (!focused || event.getTarget() != dialog)
                    return;
                if (!pane.isDescendantOf(dialog)) {
                    dialog.removeListener(this); // a reused dialog (MapStage's) refilled since
                    return;
                }
                Gdx.app.postRunnable(() -> {
                    if (dialog.getStage() != null && dialog.getStage().getScrollFocus() == dialog)
                        dialog.getStage().setScrollFocus(pane);
                });
            }
        });
        System.out.println("[TFR-Dialog] fit to the screen: " + rows + " row(s) into a " + (int) cap
                + "px scroll pane (window " + (int) tall + "px, screen " + (int) stage.getHeight() + "px)");
    }

    /** The skin's "nobg" pane draws no scroll bar, so nothing would say the text goes on. This one adds the
     *  vertical slider's track and knob from the same skin; without them it falls back to plain "nobg". */
    private static ScrollPane.ScrollPaneStyle scrollStyle() {
        ScrollPane.ScrollPaneStyle style = new ScrollPane.ScrollPaneStyle(
                Controls.getSkin().get("nobg", ScrollPane.ScrollPaneStyle.class));
        try {
            style.vScrollKnob = Controls.getSkin().getDrawable("scroll2");
            style.vScroll = Controls.getSkin().getDrawable("thinwindow");
        } catch (RuntimeException e) {
            System.err.println("[TFR-Dialog] no scroll bar art in the skin: " + e.getMessage());
            style.vScrollKnob = null;
            style.vScroll = null;
        }
        return style;
    }
}
