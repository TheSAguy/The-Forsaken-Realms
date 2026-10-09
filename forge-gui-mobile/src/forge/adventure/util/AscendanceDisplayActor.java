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
    /** The most the panel takes; it fits itself to the stage's right edge (fitTo). */
    private static final int PANEL_WIDTH = 72;
    /** Round 511 (the user: "I thought the yellow bar was a progress bar to the next level, but it does not match the
     *  current XP level, and when I reached level 2, it was still full"): the panel stood 72 wide at x 425 of a 480 HUD,
     *  so its right 17 units - where the bar's empty end lay - were off the screen and a half-filled bar looked full. Now
     *  "Asc 7" sits on top and the bar runs the panel's whole width underneath, empty at a level-up and full at the next. */
    private static final int PANEL_HEIGHT = 24;
    private static final float PAD = 6;
    private static final float BAR_Y = 5;
    private static final float BAR_HEIGHT = 4;
    private static final float LABEL_Y = 9;

    private final Image background;
    private final TypingLabel label;
    private final Image barBack;
    private final Image barFill;
    private final com.badlogic.gdx.math.Vector2 origin = new com.badlogic.gdx.math.Vector2();
    private float panelWidth = -1;
    private float share;
    private int shownPower = -1;
    private int shownPending = -1;
    private int shownLevel = -1;
    private boolean wasActive;

    public AscendanceDisplayActor() {
        Drawable panelBackground = Controls.getSkin().getDrawable("windowMain10Patch");
        background = new Image(panelBackground);
        addActor(background);

        label = Controls.newTypingLabel("");
        label.setAlignment(Align.left);
        addActor(label);

        barBack = new Image(Controls.getSkin().getDrawable("white-pixel"));
        barBack.setColor(new Color(0.12f, 0.10f, 0.08f, 1f));
        addActor(barBack);
        barFill = new Image(Controls.getSkin().getDrawable("white-pixel"));
        barFill.setColor(new Color(0.95f, 0.75f, 0.25f, 1f));
        addActor(barFill);

        fitTo(PANEL_WIDTH);
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
        forge.adventure.player.AdventurePlayer player = WorldSave.getCurrentSave() == null ? null
                : WorldSave.getCurrentSave().getPlayer();
        boolean active = player != null && Ascendance.isActive();
        if (active && !wasActive) // switched back on in Settings, or a save with it loaded: wear only what fits
            Ascendance.enforceMainLimit(player, "Ascendance on");
        // Round 502: the companion limit holds with or without Ascendance - checked when a save loads or a run starts
        // (AdventurePlayer.requestCompanionCheck) and when Settings switch Ascendance on or off.
        if (player != null && (player.takeCompanionCheck() || active != wasActive))
            Ascendance.enforceCompanionLimit(player, active ? "Ascendance" : "no Ascendance");
        if (active && player.ascendance().deferredLoaded) // round 505: saved while a quest's dialog held its Power
            Ascendance.payDeferred();
        if (player != null) // round 506: what a load changed (an item that moved slots came off)
            for (String notice : player.takeLoadNotices())
                forge.adventure.stage.GameHUD.getInstance().addNotification(notice);
        wasActive = active;
        setVisible(active);
        if (!active)
            return;
        if (getStage() != null) // round 511: never past the screen's right edge
            fitTo(getStage().getWidth() - localToStageCoordinates(origin.set(0, 0)).x - 1f);
        int power = Ascendance.power();
        int pending = Ascendance.pendingChoices();
        int level = Ascendance.level();
        if (power == shownPower && pending == shownPending && level == shownLevel)
            return;
        shownPower = power;
        shownPending = pending;
        shownLevel = level;
        int[] progress = Ascendance.progress(); // [Power into this level, Power this level needs]
        share = progress[1] <= 0 ? 0f : Math.max(0f, Math.min(1f, progress[0] / (float) progress[1]));
        barFill.setWidth(barWidth() * share);
        label.restart("[%80]Asc " + level + (pending > 0 ? "[GOLD]![]" : ""));
    }

    private float barWidth() {
        return Math.max(0f, panelWidth - 2 * PAD);
    }

    /** Round 511: lay the panel out {@code width} wide (at most PANEL_WIDTH): the label on top, the bar under it. */
    private void fitTo(float width) {
        float w = Math.max(36f, Math.min(PANEL_WIDTH, width));
        if (Math.abs(w - panelWidth) < 0.5f)
            return;
        panelWidth = w;
        background.setSize(w, PANEL_HEIGHT);
        label.setSize(w - 2 * PAD, PANEL_HEIGHT - LABEL_Y);
        label.setPosition(PAD, LABEL_Y);
        barBack.setBounds(PAD, BAR_Y, barWidth(), BAR_HEIGHT);
        barFill.setBounds(PAD, BAR_Y, barWidth() * share, BAR_HEIGHT);
        setSize(w, PANEL_HEIGHT);
    }
}
