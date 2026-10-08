package forge.adventure.util;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TypingLabel;
import forge.adventure.world.WorldSave;

/**
 * Round 493 - the HUD's Ascendance panel: "Asc 7" and a bar of the Power toward the next level, one row under the
 * wood/stone panel (GameHUD places it), built in code like ResourceDisplayActor so no shared hud*.json is forked.
 * Hidden for a character without Ascendance or with it switched off in Settings. It reads the state every frame (a few
 * int compares) instead of adding signals to AdventurePlayer. A "!" after the level means a reward is waiting to be chosen.
 */
public class AscendanceDisplayActor extends com.badlogic.gdx.scenes.scene2d.Group {
    private static final int PANEL_WIDTH = 72;
    private static final int PANEL_HEIGHT = 18;
    private static final float BAR_X = 38;
    private static final float BAR_WIDTH = 28;
    private static final float BAR_HEIGHT = 4;

    private final TypingLabel label;
    private final Image barFill;
    private int shownPower = -1;
    private int shownPending = -1;
    private boolean wasActive;

    public AscendanceDisplayActor() {
        Drawable panelBackground = Controls.getSkin().getDrawable("windowMain10Patch");
        Image background = new Image(panelBackground);
        background.setSize(PANEL_WIDTH, PANEL_HEIGHT);
        addActor(background);

        label = Controls.newTypingLabel("");
        label.setSize(BAR_X - 6, PANEL_HEIGHT);
        label.setPosition(6, 0);
        label.setAlignment(Align.left);
        addActor(label);

        float barY = (PANEL_HEIGHT - BAR_HEIGHT) / 2f;
        Image barBack = new Image(Controls.getSkin().getDrawable("white-pixel"));
        barBack.setColor(new Color(0.12f, 0.10f, 0.08f, 1f));
        barBack.setBounds(BAR_X, barY, BAR_WIDTH, BAR_HEIGHT);
        addActor(barBack);
        barFill = new Image(Controls.getSkin().getDrawable("white-pixel"));
        barFill.setColor(new Color(0.95f, 0.75f, 0.25f, 1f));
        barFill.setBounds(BAR_X, barY, 0, BAR_HEIGHT);
        addActor(barFill);

        setSize(PANEL_WIDTH, PANEL_HEIGHT);
        setVisible(false);
        // Round 494: a tap opens the waiting reward, or the status (AscendanceUI).
        addListener(new com.badlogic.gdx.scenes.scene2d.utils.ClickListener() {
            @Override
            public void clicked(com.badlogic.gdx.scenes.scene2d.InputEvent event, float x, float y) {
                AscendanceUI.openFromHud();
            }
        });
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        boolean active = WorldSave.getCurrentSave() != null && WorldSave.getCurrentSave().getPlayer() != null
                && Ascendance.isActive();
        if (active && !wasActive) // switched back on in Settings, or a save with it loaded: wear only what fits
            Ascendance.enforceMainLimit(WorldSave.getCurrentSave().getPlayer(), "Ascendance on");
        wasActive = active;
        setVisible(active);
        if (!active)
            return;
        int power = Ascendance.power();
        int pending = Ascendance.pendingChoices();
        if (power == shownPower && pending == shownPending)
            return;
        shownPower = power;
        shownPending = pending;
        int[] progress = Ascendance.progress();
        float share = progress[1] <= 0 ? 1f : Math.min(1f, progress[0] / (float) progress[1]);
        barFill.setWidth(BAR_WIDTH * share);
        label.restart("[%80]Asc " + Ascendance.level() + (pending > 0 ? "[GOLD]![]" : ""));
    }
}
