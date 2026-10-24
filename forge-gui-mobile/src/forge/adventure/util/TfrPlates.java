package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.EnemyData;
import forge.adventure.player.PlayerStatistic;
import forge.adventure.scene.DuelScene;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Round 719: enemy info plates, TFR 2's Alt plates brought to TFR 1 (the user, 2026-10-23: "If you press 'Alt' it shows
 * the monsters level ... If you have battle them before, then it will also show his deck colors and life total. If
 * you've battled a Apprentice, then for the same creatures' level ups ... it will show the level and deck colors. but
 * not life"; design.md approved with every recommendation).
 * <ul>
 *   <li>While either Alt is held, or the controller's right stick is held in (R3), or the HUD's Units toggle is on
 *   (touch), every enemy in sight - overworld, dungeon, town - wears a plate over its head on a dark plate: its rank
 *   ("Adept", "Adept Boss") in its rank's color, a gold star for a legend, then what the player knows of it.</li>
 *   <li>Knowledge (PlayerStatistic): this exact rank battled (ranksBattled, "Name|Tier") - colors and the life the duel
 *   would start with right now (DuelScene.enemyStartingLife), orange when that is above its difficulty life; another
 *   rank of its family battled (EnemyTiers' families share a name, the wizard families a name after the rank word) -
 *   colors only; nothing - a dim "?". An older save's win/loss record (by name) counts as the family, and as the exact
 *   rank for an enemy that has only one rank.</li>
 *   <li>Manasight (hasColorView) shows the colors even on an unknown enemy, as its dots beside the sprite do.</li>
 *   <li>A Bronze Coin icon on an enemy that holds the player's coin (owesCoinRansom).</li>
 *   <li>No plate on a Mimic or Fog Trap (EnemySprite.disguisedAsLoot) or The Warden. The player's own roaming guards
 *   are CharacterSprites, never EnemySprites, so they wear none.</li>
 *   <li>The plates are queued while the sprites draw and drawn after them all (SpriteGroup), so no sprite covers one;
 *   a plate that would overlap one already placed is lifted above it, at most twice.</li>
 * </ul>
 * Logs [TFR-Plates] as they show (with the counts) and hide, and each rank learned (PlayerStatistic).
 * ConfigData.enemyInfoPlatesEnabled turns it on; the agent's "plates on|off" console command is the toggle.
 */
public final class TfrPlates {
    private TfrPlates() {
    }

    /** The HUD's Units toggle (touch), or the console's "plates": shown until toggled off. */
    private static boolean toggled;
    /** The controller's right stick held in (HudScene: buttons arrive as key codes). */
    private static boolean stickHeld;
    private static boolean wasShown, countNext, loggedShown, frameShown, frameColorView;
    private static int exact, family, unknown, lifted;
    /** A mana pip's side and the gap between parts, in world px (the text is [%55]: about 5 px). */
    private static final float PIP = 4f, GAP = 1.2f, ICON = 5f;
    /** Where a [%55] label's letters sit in its height (measured in the agent game: the lower ~40% is line gap): the
     *  plate is drawn round that band, not round the whole label. */
    private static final float BAND_LO = 0.42f, BAND_HI = 0.9f;
    /** The ranks' colors: TFR 2's Apprentice, Adept and Master (Tfr2Plates), and gold for an Archmage. */
    private static final String[] RANK_COLOR = {"a8b49a", "7ab6d8", "c07ad8", "e8b84a"};
    private static final String[] RANK_WORDS = {"Apprentice ", "Adept ", "Master ", "Archmage "};

    private static final List<Queued> queue = new ArrayList<>();
    private static final List<float[]> placed = new ArrayList<>();
    /** Family names the player has battled, rebuilt when the records change. */
    private static final Set<String> knownFamilies = new HashSet<>();
    private static Object knownRecord, knownRanks;
    private static int knownSize = -1;

    public static boolean enabled() {
        return Config.instance().getConfigData().enemyInfoPlatesEnabled;
    }

    /** The plates show: Alt or R3 held, or the toggle on. */
    public static boolean shown() {
        return enabled() && (toggled || stickHeld || Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT)
                || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT));
    }

    public static void toggle() {
        toggled = !toggled;
        System.out.println("[TFR-Plates] the Units toggle: " + (toggled ? "on" : "off"));
    }

    public static boolean toggledOn() {
        return toggled;
    }

    /** HudScene's key events: the right stick pressed in or let go. Any other key is not ours. */
    public static void stickKey(int keycode, boolean down) {
        if (keycode == Input.Keys.BUTTON_THUMBR && stickHeld != down) {
            stickHeld = down;
            System.out.println("[TFR-Plates] R3 " + (down ? "held" : "released"));
        }
    }

    /** HudScene.leave: a release that never arrives (a duel took over) leaves nothing showing. */
    public static void releaseStick() {
        stickHeld = false;
    }

    /** SpriteGroup, before its sprites draw. */
    public static void beginFrame() {
        queue.clear();
        frameShown = shown();
        frameColorView = frameShown && Current.player() != null && Current.player().hasColorView();
    }

    /** SpriteGroup, after its sprites drew: every queued plate, and the show/hide log. */
    public static void endFrame(Batch batch, float parentAlpha) {
        placed.clear();
        for (Queued q : queue)
            q.plate.draw(batch, parentAlpha, q.cx, q.foot);
        // Round 723 (log review 2026-10-24, P3): the edge first, and "hidden" only after a "shown". A view with no enemy
        // in sight printed a lone "hidden" and left countNext set, so the next view printed "shown" on its first frame
        // (the stale flag, checked before the edge) and again on its second.
        if (frameShown != wasShown) {
            if (frameShown) {
                countNext = true;
            } else {
                if (loggedShown)
                    System.out.println("[TFR-Plates] hidden");
                countNext = loggedShown = false;
            }
            wasShown = frameShown;
        }
        if (countNext && !queue.isEmpty()) {
            System.out.println("[TFR-Plates] shown: " + queue.size() + " plate(s) - " + exact + " battled, " + family
                    + " family only, " + unknown + " unknown, " + lifted + " lifted" + (frameColorView ? " (Manasight)" : ""));
            countNext = false;
            loggedShown = true;
        }
        exact = family = unknown = lifted = 0;
        queue.clear();
    }

    /** EnemySprite.draw: this enemy's plate, centered on cx with its foot at foot, drawn after every sprite. */
    public static void queue(EnemySprite sprite, Plate plate, float cx, float foot) {
        if (!frameShown || sprite.getData() == null || Current.player() == null)
            return;
        plate.update(sprite);
        Queued q = new Queued();
        q.plate = plate;
        q.cx = cx;
        q.foot = foot;
        queue.add(q);
    }

    private static final class Queued {
        Plate plate;
        float cx, foot;
    }

    /** Opaque bounds of a measured frame, as fractions of it: left, right, top (from the frame's top edge). */
    private static final java.util.Map<String, float[]> artBounds = new java.util.HashMap<>();

    /**
     * Where the plate goes: {center x, top} of the sprite's VISIBLE art in world px. The art is drawn from the frame
     * CharacterSprite drew last (drawnFrame/X/Y/W/H), which can sit off the actor's box (a footprint, round 578), and a
     * frame carries empty rows and columns round the creature. Measured once per atlas from the first frame seen: the
     * png is read again into a Pixmap, its opaque pixels bounded, the Pixmap freed. Without it, the actor's box.
     */
    public static float[] artBox(EnemySprite sprite) {
        TextureRegion f = sprite.drawnFrame;
        float boxCx = sprite.getX() + sprite.getWidth() / 2f, boxTop = sprite.getY() + sprite.getHeight();
        if (f == null || sprite.drawnW <= 0 || sprite.drawnH <= 0)
            return new float[]{boxCx, boxTop};
        String key = sprite.getAtlasPath() + "@" + f.getRegionWidth() + "x" + f.getRegionHeight();
        float[] b = artBounds.get(key);
        if (b == null) {
            b = measure(f);
            artBounds.put(key, b);
            System.out.println("[TFR-Plates] art of " + sprite.getData().getName() + " (" + key + "): left " + b[0]
                    + ", right " + b[1] + ", top " + b[2]);
        }
        float left = f.isFlipX() ? 1f - b[1] : b[0], right = f.isFlipX() ? 1f - b[0] : b[1];
        return new float[]{sprite.drawnX + sprite.drawnW * (left + right) / 2f, sprite.drawnY + sprite.drawnH * (1f - b[2])};
    }

    private static float[] measure(TextureRegion f) {
        com.badlogic.gdx.graphics.Pixmap pm = null;
        boolean dispose = false;
        try {
            com.badlogic.gdx.graphics.TextureData data = f.getTexture().getTextureData();
            if (!data.isPrepared())
                data.prepare();
            pm = data.consumePixmap();
            dispose = data.disposePixmap();
            // a mirrored region (CharacterSprite.FlipAnimation) has u > u2: read the texture from its left edge
            int w = f.getRegionWidth(), h = f.getRegionHeight();
            int x0 = Math.round(Math.min(f.getU(), f.getU2()) * pm.getWidth());
            int y0 = Math.round(Math.min(f.getV(), f.getV2()) * pm.getHeight());
            int minX = w, maxX = -1, minY = h;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    if ((pm.getPixel(x0 + x, y0 + y) & 0xff) > 24) {  // RGBA8888: the low byte is alpha
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                    }
            if (maxX < 0)
                return new float[]{0f, 1f, 0f};
            return new float[]{minX / (float) w, (maxX + 1) / (float) w, minY / (float) h};
        } catch (Exception e) {
            System.out.println("[TFR-Plates] could not measure a frame (" + e + ") - the frame's own box");
            return new float[]{0f, 1f, 0f};
        } finally {
            if (pm != null && dispose)
                pm.dispose();
        }
    }

    /** A name without its rank word: "Adept Red Wizard" and "Master Red Wizard" are one family, as "Bear" is. */
    static String familyName(String name) {
        if (name == null)
            return "";
        for (String w : RANK_WORDS)
            if (name.startsWith(w) && name.length() > w.length())
                return name.substring(w.length());
        return name;
    }

    /** 2 this exact rank battled, 1 its family, 0 nothing. */
    static int knowledge(EnemySprite sprite) {
        EnemyData d = sprite.getData();
        PlayerStatistic stats = Current.player().getStatistic();
        if (stats.hasBattledRank(d.getName(), d.tier))
            return 2;
        Map<String, ?> record = stats.getWinLossRecord();
        // An older save knows names only: one with a single rank was this rank.
        if (!EnemyTiers.isFamily(d) && (record.containsKey(d.getName()) || record.containsKey(sprite.getName())))
            return 2;
        Set<String> families = knownFamilies(stats);
        return families.contains(familyName(d.getName())) || families.contains(familyName(sprite.getName())) ? 1 : 0;
    }

    private static Set<String> knownFamilies(PlayerStatistic stats) {
        Map<String, ?> record = stats.getWinLossRecord();
        Set<String> ranks = stats.getRanksBattled();
        int size = record.size() + ranks.size();
        if (record != knownRecord || ranks != knownRanks || size != knownSize) {
            knownFamilies.clear();
            for (String name : record.keySet())
                knownFamilies.add(familyName(name));
            for (String key : ranks) {
                int bar = key.lastIndexOf('|');
                knownFamilies.add(familyName(bar < 0 ? key : key.substring(0, bar)));
            }
            knownRecord = record;
            knownRanks = ranks;
            knownSize = size;
        }
        return knownFamilies;
    }

    /** One enemy's plate (a field on its EnemySprite): its labels, made again only when what they say changes. */
    public static final class Plate {
        private TextraLabel rank, life;
        private String key = "", colors = "";
        private boolean unknownColors, legend, coin, showLife;
        private int lifeNow = -1;
        private long lifeAt;

        void update(EnemySprite sprite) {
            EnemyData d = sprite.getData();
            int known = knowledge(sprite);
            if (known == 2)
                exact++;
            else if (known == 1)
                family++;
            else
                unknown++;
            boolean seeColors = known > 0 || frameColorView;
            unknownColors = !seeColors;
            colors = seeColors && d.colors != null ? d.colors.toUpperCase(Locale.ROOT).replaceAll("[^WUBRGC]", "") : "";
            legend = LegendSpawns.isMember(d);
            coin = Current.player().owesCoinRansom(sprite.getName());
            showLife = known == 2;
            boolean buffed = false;
            if (showLife) {
                long now = System.currentTimeMillis();
                if (lifeNow < 0 || now - lifeAt > 250) {  // night falls, it walks onto other land: a few times a second
                    lifeNow = DuelScene.enemyStartingLife(d, sprite, false, false, false, false, false);
                    lifeAt = now;
                }
                buffed = lifeNow > Math.round(d.life * Current.player().getDifficulty().enemyLifeFactor);
            }
            int r = Math.max(0, Math.min(3, EnemyData.tierRank(d.tier)));
            String k = r + "/" + d.boss + "/" + showLife + "/" + lifeNow + "/" + buffed;
            if (k.equals(key))
                return;
            key = k;
            if (rank == null) {
                rank = Controls.newTextraLabel("");
                life = Controls.newTextraLabel("");
            }
            String word = EnemyData.tierDisplayName(d.tier) + (d.boss ? " Boss" : "");
            rank.setText("[%55][#" + RANK_COLOR[r] + "]" + word.toUpperCase(Locale.ROOT));
            rank.pack();
            life.setText("[%55][+Life][#" + (buffed ? "e67e22" : "e9e1cc") + "]" + lifeNow);
            life.pack();
        }

        /** Centered on cx, its foot at foot - lifted above any plate already placed this frame that it would overlap. */
        void draw(Batch batch, float parentAlpha, float cx, float foot) {
            if (rank == null)
                return;
            float rw = rank.getWidth(), th = rank.getHeight();
            float starW = legend ? ICON : 0f;
            // the last pip's slot less its spacing, so the right padding matches the left
            float colorsW = unknownColors ? PIP : colors.isEmpty() ? 0f : colors.length() * PIP - 0.6f;
            float lifeW = showLife ? GAP + life.getWidth() : 0f;
            float coinW = coin ? GAP + ICON : 0f;
            float w = rw + starW + (colorsW > 0 ? GAP + colorsW : 0f) + lifeW + coinW;
            // y is the labels' origin; the band of letters [lo, hi] above it is what the plate frames
            float lo = th * BAND_LO, hi = th * BAND_HI, mid = (lo + hi) / 2f;
            float x = cx - w / 2f, y = foot + 1f - lo;
            for (int tries = 0; tries < 2; tries++) {
                float[] hit = null;
                for (float[] p : placed)
                    if (x - 1.5f < p[2] && x + w + 1.5f > p[0] && y + lo - 1f < p[3] && y + hi + 0.8f > p[1]) {
                        hit = p;
                        break;
                    }
                if (hit == null)
                    break;
                y = hit[3] + 1.5f - lo;
                lifted++;
            }
            placed.add(new float[]{x - 1.5f, y + lo - 1f, x + w + 1.5f, y + hi + 0.8f});
            Color was = batch.getColor().cpy();
            batch.setColor(0.08f, 0.07f, 0.06f, 0.72f * parentAlpha);
            batch.draw(Forge.getAssets().getWhiteTexture(), x - 1.5f, y + lo - 1f, w + 3f, hi - lo + 1.8f);
            batch.setColor(1f, 1f, 1f, parentAlpha);
            rank.setPosition(x, y);
            rank.draw(batch, parentAlpha);
            float px = x + rw;
            TextureAtlas items = Config.instance().getAtlas(Paths.ITEMS_ATLAS);
            if (legend) {
                TextureRegion star = items.findRegion("Star");
                if (star != null)
                    batch.draw(star, px + 0.5f, y + mid - (ICON - 1f) / 2f, ICON - 1f, ICON - 1f);
                px += starW;
            }
            if (colorsW > 0) {
                px += GAP;
                if (unknownColors) {
                    // a dim "?": two strokes and a dot, so no third label is needed
                    batch.setColor(0.55f, 0.53f, 0.49f, parentAlpha);
                    float qx = px + 0.8f, qy = y + mid - 2f;
                    batch.draw(Forge.getAssets().getWhiteTexture(), qx, qy + 3.2f, 2.2f, 0.7f);
                    batch.draw(Forge.getAssets().getWhiteTexture(), qx + 1.8f, qy + 2.0f, 0.7f, 1.5f);
                    batch.draw(Forge.getAssets().getWhiteTexture(), qx + 1.1f, qy + 1.3f, 0.7f, 0.9f);
                    batch.draw(Forge.getAssets().getWhiteTexture(), qx + 1.1f, qy, 0.7f, 0.7f);
                    batch.setColor(1f, 1f, 1f, parentAlpha);
                } else {
                    TextureAtlas pips = Config.instance().getAtlas(Paths.PIXELMANA_ATLAS);
                    for (int i = 0; i < colors.length(); i++) {
                        TextureAtlas.AtlasRegion pip = pips.findRegion("pm" + colors.charAt(i));
                        if (pip != null)
                            batch.draw(pip, px + i * PIP, y + mid - (PIP - 0.6f) / 2f, PIP - 0.6f, PIP - 0.6f);
                    }
                }
                px += colorsW;
            }
            if (showLife) {
                life.setPosition(px + GAP, y);
                life.draw(batch, parentAlpha);
                px += lifeW;
            }
            if (coin) {
                TextureRegion c = items.findRegion("BronzeChallengeCoin");
                if (c != null)
                    batch.draw(c, px + GAP, y + mid - (ICON - 1f) / 2f, ICON - 1f, ICON - 1f);
            }
            batch.setColor(was);  // the HUD shares the batch: no color left behind (GameHUD.draw, round 329)
        }
    }
}
