package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.world.World;
import forge.adventure.world.WorldHistory;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Round 626 - the History replay (Civilization 3's, the user's reference): the whole world on one screen as a political
 * map, played from the first recorded day to today - each color's land filling in, town dots changing hands, a legend with
 * every power's towns and share of the land, and a caption for the player's own milestones. Opened from the map screen's
 * History button. The record itself is WorldHistory's.
 * <p>
 * The map is two 1-pixel-a-tile textures (700 x 700 on this plane): the land, and the towns over it. Playing forward
 * repaints only the tiles a day changed; going back starts again from the first page (WorldHistory.Cursor).
 */
public class HistoryScene extends UIScene {
    private static HistoryScene object;
    /** Days a second at 1x, 2x and 4x. */
    private static final float[] SPEEDS = {3f, 6f, 12f};
    private static final String[] SPEED_LABELS = {"1x", "2x", "4x"};
    /** Land colors by WorldHistory owner code (sea, unclaimed, white, blue, black, red, green, you); a color's land is
     *  drawn a shade darker so its towns stand out. */
    private static final int[][] RGB = {{24, 36, 58}, {128, 118, 100}, {236, 228, 196}, {64, 128, 222}, {124, 86, 160},
            {214, 72, 58}, {72, 170, 82}, {240, 190, 48}};
    private static final int[] LEGEND = {WorldHistory.WHITE, WorldHistory.BLUE, WorldHistory.BLACK, WorldHistory.RED,
            WorldHistory.GREEN, WorldHistory.PLAYER, WorldHistory.WASTE};
    private static final float LAND_SHADE = 0.72f;
    private static final float BAR_WIDTH = 46f;

    private final Table mapArea, panel, controls;
    private Texture white;
    private Pixmap landPixmap, townPixmap;
    private Texture landTexture, townTexture;
    private WorldHistory history;
    private WorldHistory.Cursor cursor;
    private List<WorldHistory.Milestone> milestones;
    private int lastDay;
    private boolean playing, landDirty, sliderSync;
    private int speed = 1;
    private float carry;
    private TextraLabel dayLabel, captionDay, captionText;
    private final TextraLabel[] countLabels = new TextraLabel[8];
    private final TextraLabel[] shareLabels = new TextraLabel[8];
    private final Image[] bars = new Image[8];
    private Slider slider;
    private TextraButton playButton;
    private final TextraButton[] speedButtons = new TextraButton[SPEEDS.length];

    private HistoryScene() {
        super(Forge.isLandscapeMode() ? "ui/history.json" : "ui/history_portrait.json");
        mapArea = ui.findActor("historyMap");
        panel = ui.findActor("historyPanel");
        controls = ui.findActor("historyControls");
        ui.onButtonPress("return", this::close);
        Pixmap one = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        one.setColor(Color.WHITE);
        one.fill();
        white = new Texture(one);
        one.dispose();
        // A dark page over the last screen, under everything else.
        Image page = new Image(white);
        page.setColor(0.055f, 0.063f, 0.086f, 1f);
        page.setSize(Forge.isLandscapeMode() ? 480f : 270f, Forge.isLandscapeMode() ? 270f : 480f);
        page.setTouchable(Touchable.disabled);
        ui.addActorAt(1, page);
    }

    public static HistoryScene instance() {
        if (object == null)
            object = new HistoryScene();
        return object;
    }

    /** The map screen's History button (and the console's "history"). */
    public static void show() {
        World world = Current.world();
        if (world == null)
            return;
        world.recordHistory(world.getCurrentDay()); // today as it stands, so the replay ends where the game is
        WorldHistory h = world.getHistory();
        if (h == null)
            return;
        instance().open(h, Math.max(world.getCurrentDay(), h.lastDay()));
        if (Forge.getCurrentScene() != instance())
            Forge.switchScene(instance(), true);
    }

    private void open(WorldHistory h, int last) {
        history = h;
        lastDay = Math.max(last, h.startDay);
        cursor = h.cursor();
        milestones = h.milestones();
        if (landPixmap == null || landPixmap.getWidth() != h.width || landPixmap.getHeight() != h.height) {
            disposeMap();
            landPixmap = new Pixmap(h.width, h.height, Pixmap.Format.RGBA8888);
            townPixmap = new Pixmap(h.width, h.height, Pixmap.Format.RGBA8888);
            landTexture = new Texture(landPixmap);
            townTexture = new Texture(townPixmap);
            landTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            townTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        }
        paintAllLand();
        paintTowns();
        buildLayout();
        carry = 0;
        playing = lastDay > h.startDay;
        refreshButtons();
        refreshLabels();
        System.out.println("[TFR-History] replay opened: days " + h.startDay + "-" + lastDay + ", "
                + h.towns().size() + " towns, " + milestones.size() + " milestones");
    }

    // ---- the map ----

    private static int land(int code, int channel) {
        int v = RGB[code][channel];
        return code == WorldHistory.SEA || code == WorldHistory.WASTE ? v : (int) (v * LAND_SHADE);
    }

    private void putLand(ByteBuffer px, int index, int code) {
        int p = index * 4;
        px.put(p, (byte) land(code, 0));
        px.put(p + 1, (byte) land(code, 1));
        px.put(p + 2, (byte) land(code, 2));
        px.put(p + 3, (byte) 255);
    }

    private void paintAllLand() {
        ByteBuffer px = landPixmap.getPixels();
        for (int i = 0; i < cursor.tiles.length; i++)
            putLand(px, i, cursor.tiles[i]);
        landTexture.draw(landPixmap, 0, 0);
    }

    private void paintTowns() {
        townPixmap.setBlending(Pixmap.Blending.None);
        townPixmap.setColor(0, 0, 0, 0);
        townPixmap.fill();
        List<WorldHistory.Town> towns = history.towns();
        for (int i = 0; i < cursor.town.length && i < towns.size(); i++) {
            int code = cursor.town[i];
            if (code == WorldHistory.NONE)
                continue;
            int owner = code & 7;
            boolean capital = (code & WorldHistory.KIND_CAPITAL) != 0;
            WorldHistory.Town t = towns.get(i);
            int r = capital ? 5 : 3;
            townPixmap.setColor(16 / 255f, 16 / 255f, 20 / 255f, 1f);
            townPixmap.fillCircle(t.x, t.row, r + 1);
            if (owner == WorldHistory.WASTE)
                townPixmap.setColor(190 / 255f, 180 / 255f, 160 / 255f, 1f);
            else
                townPixmap.setColor(Math.min(255, RGB[owner][0] * 1.08f + 12) / 255f,
                        Math.min(255, RGB[owner][1] * 1.08f + 12) / 255f, Math.min(255, RGB[owner][2] * 1.08f + 12) / 255f, 1f);
            townPixmap.fillCircle(t.x, t.row, r);
            if (capital) {
                townPixmap.setColor(16 / 255f, 16 / 255f, 20 / 255f, 1f);
                townPixmap.fillCircle(t.x, t.row, 2);
            }
        }
        townTexture.draw(townPixmap, 0, 0);
    }

    /** Moves the replay to {@code target}: forward record by record, back from the first page. */
    private void stepTo(int target) {
        target = Math.max(history.startDay, Math.min(lastDay, target));
        boolean townsChanged;
        if (target < cursor.day) {
            cursor.reset();
            cursor.advanceTo(target, null);
            paintAllLand();
            townsChanged = true;
        } else {
            ByteBuffer px = landPixmap.getPixels();
            landDirty = false;
            townsChanged = cursor.advanceTo(target, idx -> {
                putLand(px, idx, cursor.tiles[idx]);
                landDirty = true;
            });
            if (landDirty)
                landTexture.draw(landPixmap, 0, 0);
        }
        if (townsChanged)
            paintTowns();
        refreshLabels();
    }

    // ---- the page ----

    private TextraLabel label(String text) {
        TextraLabel l = Controls.newTextraLabel(text);
        return l;
    }

    private Color color(int code) {
        return new Color(RGB[code][0] / 255f, RGB[code][1] / 255f, RGB[code][2] / 255f, 1f);
    }

    private void buildLayout() {
        boolean landscape = Forge.isLandscapeMode();
        float panelWidth = landscape ? 204f : 258f;
        float mapSide = Math.min(mapArea.getWidth(), mapArea.getHeight());

        mapArea.clear();
        mapArea.setBackground(new TextureRegionDrawable(white).tint(new Color(0.35f, 0.31f, 0.24f, 1f)));
        Stack map = new Stack();
        Image landImage = new Image(landTexture);
        Image townImage = new Image(townTexture);
        map.add(landImage);
        map.add(townImage);
        mapArea.add(map).size(mapSide - 4f, mapSide - 4f);

        panel.clear();
        panel.top().left();
        panel.add(label("[%100][#F0DCA0]History of the Realm")).left().colspan(5).row();
        dayLabel = label("");
        panel.add(dayLabel).left().colspan(5).padBottom(4).row();
        panel.add(label("[%70][#AAAAAA]Towns")).left().colspan(3);
        panel.add(label("[%70][#AAAAAA]Land")).left().colspan(2).row();
        for (int code : LEGEND) {
            Image swatch = new Image(white);
            swatch.setColor(color(code));
            panel.add(swatch).size(8f, 8f).padRight(4f);
            panel.add(label("[%80]" + WorldHistory.NAMES[code])).left().width(landscape ? 50f : 64f);
            countLabels[code] = label("");
            panel.add(countLabels[code]).right().width(22f).padRight(8f);
            Group barCell = new Group();
            barCell.setSize(BAR_WIDTH, 8f);
            bars[code] = new Image(white);
            bars[code].setColor(color(code));
            bars[code].setBounds(0, 2f, 1f, 5f);
            barCell.addActor(bars[code]);
            panel.add(barCell).size(BAR_WIDTH, 8f).padRight(4f);
            shareLabels[code] = label("");
            panel.add(shareLabels[code]).left().width(28f).row();
        }
        captionDay = label("");
        captionText = label("");
        captionText.setWrap(true);
        panel.add(captionDay).left().colspan(5).padTop(landscape ? 8f : 4f).row();
        panel.add(captionText).left().width(panelWidth).colspan(5).row();

        panel.add().expandY().colspan(5).row(); // the slider sits at the foot of the panel, over the buttons
        slider = Controls.newSlider(history.startDay, Math.max(lastDay, history.startDay + 1), 1, false);
        slider.setVisible(lastDay > history.startDay); // one recorded day: nothing to scrub
        slider.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (sliderSync)
                    return;
                setPlaying(false);
                stepTo(Math.round(slider.getValue()));
            }
        });
        panel.add(slider).width(panelWidth).colspan(5).padTop(landscape ? 8f : 4f).row();

        controls.clear();
        controls.left();
        controls.add(Controls.newTextButton("[%80]|<", () -> stepTo(history.startDay))).size(24f, 20f).padRight(3f);
        playButton = Controls.newTextButton("", this::togglePlay);
        controls.add(playButton).size(42f, 20f).padRight(3f);
        for (int i = 0; i < SPEEDS.length; i++) {
            final int s = i;
            speedButtons[i] = Controls.newTextButton("", () -> {
                speed = s;
                refreshButtons();
            });
            controls.add(speedButtons[i]).size(24f, 20f).padRight(3f);
        }
        refreshButtons();
    }

    private void togglePlay() {
        if (!playing && cursor.day >= lastDay)
            stepTo(history.startDay); // at the end, Play starts over
        setPlaying(!playing);
    }

    private void setPlaying(boolean on) {
        playing = on && lastDay > history.startDay;
        carry = 0;
        refreshButtons();
    }

    private void refreshButtons() {
        if (playButton != null)
            playButton.setText(playing ? "[%80]Pause" : "[%80]Play");
        for (int i = 0; i < speedButtons.length; i++)
            if (speedButtons[i] != null)
                speedButtons[i].setText((i == speed ? "[%80][#F0BE30]" : "[%80]") + SPEED_LABELS[i]);
    }

    private void refreshLabels() {
        dayLabel.setText("[%85]Day " + cursor.day + " of " + lastDay);
        int landTotal = 0;
        for (int c = 1; c < cursor.tileCounts.length; c++)
            landTotal += cursor.tileCounts[c];
        for (int code : LEGEND) {
            countLabels[code].setText("[%85][#F0DCA0]" + cursor.townCounts[code]);
            float share = landTotal == 0 ? 0f : cursor.tileCounts[code] / (float) landTotal;
            bars[code].setWidth(Math.max(1f, BAR_WIDTH * share));
            shareLabels[code].setText("[%70][#AAAAAA]" + Math.round(share * 100) + "%");
        }
        WorldHistory.Milestone latest = null;
        for (WorldHistory.Milestone m : milestones) {
            if (m.day > cursor.day)
                break;
            latest = m;
        }
        if (latest != null) {
            captionDay.setText("[%80][#F0BE30]Day " + latest.day);
            captionText.setText("[%85]" + latest.text);
        } else if (history.startDay > 1) {
            captionDay.setText("");
            captionText.setText("[%75][#AAAAAA]History is recorded from day " + history.startDay
                    + ", when this game first ran on a version with it.");
        } else {
            captionDay.setText("");
            captionText.setText("");
        }
        sliderSync = true;
        slider.setValue(cursor.day);
        sliderSync = false;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (!playing || cursor == null)
            return;
        carry += delta * SPEEDS[speed];
        int steps = (int) carry;
        if (steps <= 0)
            return;
        carry -= steps;
        stepTo(cursor.day + steps);
        if (cursor.day >= lastDay)
            setPlaying(false);
    }

    private void close() {
        setPlaying(false);
        back();
    }

    private void disposeMap() {
        if (landTexture != null)
            landTexture.dispose();
        if (townTexture != null)
            townTexture.dispose();
        if (landPixmap != null)
            landPixmap.dispose();
        if (townPixmap != null)
            townPixmap.dispose();
        landTexture = townTexture = null;
        landPixmap = townPixmap = null;
    }

    @Override
    public void dispose() {
        disposeMap();
        if (white != null)
            white.dispose();
        super.dispose();
    }
}
