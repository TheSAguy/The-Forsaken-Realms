package forge.adventure.util;

import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TypingLabel;
import forge.adventure.player.AdventurePlayer;

/**
 * HUD readout for Lumber/Stone (MOD_SCOPE.md #9 Expanded Resources). Icons are real art now, not
 * a placeholder: "[+Name]"-style inline markup (the technique Gold/Shards use, via
 * Controls.getTextraFont()'s registered items.atlas) turned out not to work for a second, newly-
 * added atlas - the icon tag was recognized but never resolved to a picture, root cause not fully
 * pinned down (suspected AssetManager-level Texture caching for items.png, not a font/atlas
 * config issue - Gold/Shards' own pre-existing icons in that same atlas kept working fine).
 * Rather than keep fighting that, icons here are rendered the same proven way
 * EconomyBuildings/TownRestoration's own custom art already is: a real Image backed by a
 * TextureRegion from a small dedicated atlas (Config.instance().getAtlasSprite()), cropped
 * directly from the same common/maps/tileset/buildings.png sheet the user identified (a resource-
 * pile icon row - orange for Lumber, dark grey for Stone) - see resource_icons.png/.atlas.
 * Background is the same "windowMain10Patch" stone-block panel every dialog/window already uses
 * (see TimeOfDayActor) - positioned immediately below hud.json's "money" actor in GameHUD, not
 * hud.json's own layout - forking that shared file per plane is a full-copy-not-merge risk, same
 * gotcha as config.json (see MOD_CHANGELOG.md).
 */
public class ResourceDisplayActor extends Group {

    private static final int PANEL_WIDTH = 72;
    private static final int PANEL_HEIGHT = 18;
    private static final int ICON_SIZE = 16;
    private static final int PADDING = 8;
    private static final String ICON_ATLAS = "maps/tileset/resource_icons.atlas";

    private final TypingLabel lumberLabel;
    private final TypingLabel stoneLabel;
    private final Image background;
    private final com.badlogic.gdx.math.Vector2 origin = new com.badlogic.gdx.math.Vector2();
    private float panelWidth = PANEL_WIDTH;
    private int lastLumber = Integer.MIN_VALUE;
    private int lastStone = Integer.MIN_VALUE;

    public ResourceDisplayActor() {
        Drawable panelBackground = Controls.getSkin().getDrawable("windowMain10Patch");
        background = new Image(panelBackground);
        background.setSize(PANEL_WIDTH, PANEL_HEIGHT * 2);
        addActor(background);

        float iconYInset = (PANEL_HEIGHT - ICON_SIZE) / 2f;
        addIcon("Lumber", PANEL_HEIGHT + iconYInset);
        addIcon("Stone", iconYInset);

        // 2026-08-08 tighten-up: consistent 6px icon-to-number gap (was 2, cramped against the
        // icon), and the number text no longer carries its own leading space (see refresh
        // methods) - one alignment mechanism instead of two stacking.
        float labelX = PADDING + ICON_SIZE + 6;
        float labelWidth = PANEL_WIDTH - labelX - PADDING;

        lumberLabel = Controls.newTypingLabel("");
        lumberLabel.setSize(labelWidth, PANEL_HEIGHT);
        lumberLabel.setPosition(labelX, PANEL_HEIGHT);
        lumberLabel.setAlignment(Align.left);
        addActor(lumberLabel);

        stoneLabel = Controls.newTypingLabel("");
        stoneLabel.setSize(labelWidth, PANEL_HEIGHT);
        stoneLabel.setPosition(labelX, 0);
        stoneLabel.setAlignment(Align.left);
        addActor(stoneLabel);

        setSize(PANEL_WIDTH, PANEL_HEIGHT * 2);

        AdventurePlayer.current().onWoodChange(this::refreshLumber);
        AdventurePlayer.current().onStoneChange(this::refreshStone);
        // The listeners above only fire on a *change* - without an initial call, the labels stay
        // blank until the player's first Lumber/Stone gain, which for most players is "never"
        // since nothing grants either yet except a Lumber Mill/Stone Mine's first daily tick.
        // Show the real (usually zero) starting value immediately instead.
        refreshLumber();
        refreshStone();
    }

    /** Round 511: 72 wide at x 425 of the 480-wide HUD ran 17 units off the screen (its right border unseen) - it fits
     *  itself to the stage's right edge now, like the Ascendance panel under it. */
    @Override
    public void act(float delta) {
        super.act(delta);
        if (getStage() == null)
            return;
        float w = Math.max(40f, Math.min(PANEL_WIDTH, getStage().getWidth() - localToStageCoordinates(origin.set(0, 0)).x - 1f));
        if (Math.abs(w - panelWidth) < 0.5f)
            return;
        panelWidth = w;
        background.setWidth(w);
        float labelWidth = Math.max(10f, w - (PADDING + ICON_SIZE + 6) - PADDING);
        lumberLabel.setWidth(labelWidth);
        stoneLabel.setWidth(labelWidth);
        setWidth(w);
    }

    private void addIcon(String regionName, float y) {
        TextureRegion region = Config.instance().getAtlasSprite(ICON_ATLAS, regionName);
        if (region == null)
            return;
        Image icon = new Image(new TextureRegionDrawable(region));
        icon.setSize(ICON_SIZE, ICON_SIZE);
        icon.setPosition(PADDING, y);
        addActor(icon);
    }

    private void refreshLumber() {
        int amount = AdventurePlayer.current().getWood();
        if (amount != lastLumber) {
            lastLumber = amount;
            lumberLabel.restart("[%95]{EMERGE}" + amount + "{ENDEMERGE}");
        }
    }

    private void refreshStone() {
        int amount = AdventurePlayer.current().getStone();
        if (amount != lastStone) {
            lastStone = amount;
            stoneLabel.restart("[%95]{EMERGE}" + amount + "{ENDEMERGE}");
        }
    }
}
