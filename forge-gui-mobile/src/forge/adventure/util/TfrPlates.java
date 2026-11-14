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
import forge.adventure.data.AdventureQuestData;
import forge.adventure.data.AdventureQuestStage;
import forge.adventure.data.EnemyData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.player.PlayerStatistic;
import forge.adventure.scene.DuelScene;
import forge.adventure.scene.TileMapScene;
import forge.adventure.stage.MapStage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Round 719: enemy info plates, Alt plates ported from another project of the user's (the user, 2026-10-23: "If you press 'Alt' it shows
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
 *   <li>Round 866: "RAIDER - TOWN" above a raider's plate. Round 894: "QUEST - NAME" above an enemy whose kill would count
 *   toward an active Defeat or Hunt stage (readHunts); a raider keeps its RAIDER line, and the Wrath has its own plate.</li>
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
    private static int exact, family, unknown, lifted, raiders, questTargets, bounties;
    /** A mana pip's side and the gap between parts, in world px (the text is [%55]: about 5 px). */
    private static final float PIP = 4f, GAP = 1.2f, ICON = 5f;
    /** Where a [%55] label's letters sit in its height (measured in the agent game: the lower ~40% is line gap): the
     *  plate is drawn round that band, not round the whole label. */
    private static final float BAND_LO = 0.42f, BAND_HI = 0.9f;
    /** The ranks' colors: the original plates' Apprentice, Adept and Master, and gold for an Archmage. */
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
        placeQueue.clear();
        wrathQueue.clear();
        frameShown = shown();
        frameColorView = frameShown && Current.player() != null && Current.player().hasColorView();
        if (frameShown)
            readHunts();
    }

    /** Round 894: the active stages an enemy can count toward, each with its quest's name. */
    private static final List<Hunt> hunts = new ArrayList<>();
    /** Round 894: moves each time hunts is read, so a Plate asks again - twice a second, as PlacePlate does. */
    private static int huntsRead;
    private static long huntsAt;

    private static final class Hunt {
        AdventureQuestStage stage;
        String quest;
    }

    /**
     * Round 894: the quest log's stages this place can count, read here so an enemy never walks the log. A kill's own
     * test (checkIfTargetLocation) says where the stage counts: a world-map stage has no mark in a dungeon. A stage with
     * a named target comes first, so it names the plate over a wide color hunt; a stage with no filter at all ("Win a
     * duel") is never a hunt - every enemy would wear it.
     */
    private static void readHunts() {
        long now = System.currentTimeMillis();
        if (now - huntsAt <= 500)
            return;
        huntsAt = now;
        huntsRead++;
        hunts.clear();
        if (Current.player() == null)
            return;
        boolean inMap = MapStage.getInstance().isInMap();
        PointOfInterest here = inMap ? TileMapScene.instance().rootPoint : null;
        if (inMap && here == null)
            return;
        int named = 0;
        for (AdventureQuestData quest : Current.player().getQuests()) {
            for (AdventureQuestStage stage : quest.stages) {
                if (stage == null || stage.getStatus() != AdventureQuestController.QuestStatus.ACTIVE
                        || (stage.objective != AdventureQuestController.ObjectiveTypes.Defeat
                        && stage.objective != AdventureQuestController.ObjectiveTypes.Hunt)
                        || !stage.checkIfTargetLocation(here))
                    continue;
                boolean target = stage.getTargetEnemyData() != null;
                boolean color = stage.enemyColorLetter != null && !stage.enemyColorLetter.isEmpty();
                boolean mage = stage.territoryMageColor != null && !stage.territoryMageColor.isEmpty();
                if (!target && !color && !mage && (stage.enemyTags == null || stage.enemyTags.isEmpty()))
                    continue;
                Hunt h = new Hunt();
                h.stage = stage;
                h.quest = quest.getName();
                if (target)
                    hunts.add(named++, h);
                else
                    hunts.add(h);
            }
        }
    }

    /** Round 894: the quest this enemy's kill would count toward, or null. A Hunt stage wants its one sprite. */
    private static String huntOf(EnemySprite sprite) {
        for (Hunt h : hunts)
            if (h.stage.objective == AdventureQuestController.ObjectiveTypes.Hunt ? h.stage.getTargetSprite() == sprite
                    : h.stage.checkIfTargetEnemy(sprite))
                return h.quest;
        return null;
    }

    /** SpriteGroup, after its sprites drew: every queued plate, and the show/hide log. */
    public static void endFrame(Batch batch, float parentAlpha) {
        placed.clear();
        for (PlaceQueued q : placeQueue) // round 791: the entrances first, so an enemy beside one is lifted over it
            q.plate.draw(batch, parentAlpha, q.cx, q.foot);
        placeQueue.clear();
        targets.clear();
        for (WrathQueued q : wrathQueue) { // round 814: the Wrath's plate, shown with or without Alt
            q.plate.draw(batch, parentAlpha, q.cx, q.foot);
            target(q.sprite);
        }
        wrathQueue.clear();
        for (Queued q : queue) {
            q.plate.draw(batch, parentAlpha, q.cx, q.foot);
            target(q.sprite);
        }
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
                    + " family only, " + unknown + " unknown, " + lifted + " lifted" + (raiders > 0 ? ", " + raiders
                    + " raider(s)" : "") + (questTargets > 0 ? ", " + questTargets + " quest target(s)" : "")
                    + (bounties > 0 ? ", " + bounties + " with a bounty" : "")
                    + (frameColorView ? " (Manasight)" : ""));
            countNext = false;
            loggedShown = true;
        }
        exact = family = unknown = lifted = raiders = questTargets = bounties = 0;
        queue.clear();
    }

    /** EnemySprite.draw: this enemy's plate, centered on cx with its foot at foot, drawn after every sprite. */
    public static void queue(EnemySprite sprite, Plate plate, float cx, float foot) {
        if (!frameShown || sprite.getData() == null || Current.player() == null)
            return;
        plate.update(sprite);
        Queued q = new Queued();
        q.sprite = sprite;
        q.plate = plate;
        q.cx = cx;
        q.foot = foot;
        queue.add(q);
    }

    private static final class Queued {
        EnemySprite sprite;
        Plate plate;
        float cx, foot;
    }

    /** Round 915: an enemy that wore a plate last frame, and that plate's box. */
    private static final class Target {
        EnemySprite sprite;
        float[] box;
    }

    /** Round 915: every plate drawn last frame. A plate is the test the click needs: in sight, no Mimic, no Warden. */
    private static final List<Target> targets = new ArrayList<>();

    /** endFrame, right after a plate drew: placeBox's newest entry is its box. */
    private static void target(EnemySprite sprite) {
        Target t = new Target();
        t.sprite = sprite;
        t.box = placed.isEmpty() ? null : placed.get(placed.size() - 1);
        targets.add(t);
    }

    /** Round 915: the enemy whose plate or art is at this stage point, or null. Plates draw on top, so a plate wins; of
     *  overlapping sprites, the one centered nearest. */
    public static EnemySprite enemyAt(float x, float y) {
        EnemySprite best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Target t : targets) {
            if (t.box != null && x >= t.box[0] && x <= t.box[2] && y >= t.box[1] && y <= t.box[3])
                return t.sprite;
            EnemySprite s = t.sprite;
            boolean drawn = s.drawnW > 0 && s.drawnH > 0;
            float left = drawn ? s.drawnX : s.getX(), bottom = drawn ? s.drawnY : s.getY();
            float width = drawn ? s.drawnW : s.getWidth(), height = drawn ? s.drawnH : s.getHeight();
            if (x < left || x > left + width || y < bottom || y > bottom + height)
                continue;
            float distance = Math.abs(x - left - width / 2f) + Math.abs(y - bottom - height / 2f);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = s;
            }
        }
        return best;
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

    /** Round 791: the share of a place's art above its first opaque row, measured once per region - its days plate
     *  (PlacePlate) sits on the art, not on the transparent rows over it. */
    public static float artTopGap(TextureRegion f) {
        String key = "place@" + System.identityHashCode(f.getTexture()) + ":" + f.getRegionX() + "," + f.getRegionY() + ","
                + f.getRegionWidth() + "x" + f.getRegionHeight();
        float[] b = artBounds.get(key);
        if (b == null) {
            b = measure(f);
            artBounds.put(key, b);
        }
        return b[2];
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
        return knowledge(sprite.getData(), sprite.getName());
    }

    /** Round 897: the same answer with no sprite (RealmJournal). shownName: a sprite's override, else the enemy's name. */
    static int knowledge(EnemyData d, String shownName) {
        PlayerStatistic stats = Current.player().getStatistic();
        if (stats.hasBattledRank(d.getName(), d.tier))
            return 2;
        Map<String, ?> record = stats.getWinLossRecord();
        // An older save knows names only: one with a single rank was this rank.
        if (!EnemyTiers.isFamily(d) && (record.containsKey(d.getName()) || record.containsKey(shownName)))
            return 2;
        Set<String> families = knownFamilies(stats);
        return families.contains(familyName(d.getName())) || families.contains(familyName(shownName)) ? 1 : 0;
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
    /** Where a plate of width w goes: y, lifted above any plate already placed this frame that it would overlap (at most
     *  twice), and recorded. lo/hi: the band of letters above the labels' origin y. Round 791: shared with PlacePlate. */
    private static float placeBox(float x, float y, float w, float lo, float hi) {
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
        return y;
    }

    /** The plate's dark box round the band of letters; leaves the batch white. */
    private static void drawBox(Batch batch, float parentAlpha, float x, float y, float w, float lo, float hi) {
        batch.setColor(0.08f, 0.07f, 0.06f, 0.72f * parentAlpha);
        batch.draw(Forge.getAssets().getWhiteTexture(), x - 1.5f, y + lo - 1f, w + 3f, hi - lo + 1.8f);
        batch.setColor(1f, 1f, 1f, parentAlpha);
    }

    /** Round 791: the color of a fading place's number - gold, orange in its last FADE_SOON days. */
    private static final int FADE_SOON = 3;

    private static String fadeColor(int days) {
        return days <= FADE_SOON ? "e67e22" : "e8b84a";
    }

    /**
     * Round 791: the corner line GameHUD shows inside a place while the plates show - DungeonRotation.fadeDays() in the
     * plates' words and colors ("Fades in 12 days", the number gold, orange at 3 or fewer). null for no line.
     */
    public static String fadeLine(int fade) {
        String text = "[#e9e1cc]", dim = "[#a8a296]";
        switch (fade) {
            case DungeonRotation.FADE_NONE: return null;
            case DungeonRotation.FADE_STAYS: return dim + "Stays";
            case DungeonRotation.FADE_QUEST: return dim + "Quest target - stays";
            case DungeonRotation.FADE_WHEN_CLEARED: return dim + "Leaves when cleared";
            case DungeonRotation.FADE_ON_LEAVE: return "[#e67e22]Fades when you leave";
            default: return text + "Fades in [#" + fadeColor(fade) + "]" + fade + text + (fade == 1 ? " day" : " days");
        }
    }

    /** Round 791: PointOfInterestMapSprite.draw - a fading entrance's "12 DAYS" plate, centered on cx, its foot at foot. */
    public static void queuePlace(PointOfInterest poi, PlacePlate plate, float cx, float foot) {
        if (!frameShown || poi == null || !plate.update(poi))
            return;
        PlaceQueued q = new PlaceQueued();
        q.plate = plate;
        q.cx = cx;
        q.foot = foot;
        placeQueue.add(q);
    }

    private static final List<WrathQueued> wrathQueue = new ArrayList<>();

    private static final class WrathQueued {
        EnemySprite sprite;
        WrathPlate plate;
        float cx, foot;
    }

    /** Round 814: EnemySprite.draw - the Wrath hunter's plate, every frame; the days left join it while the plates show. */
    public static void queueWrath(EnemySprite sprite, WrathPlate plate, String color, String tier, int daysLeft, float cx, float foot) {
        plate.update(color, tier, frameShown ? daysLeft : -1);
        WrathQueued q = new WrathQueued();
        q.sprite = sprite;
        q.plate = plate;
        q.cx = cx;
        q.foot = foot;
        wrathQueue.add(q);
    }

    /**
     * Round 814: "WRATH OF RED - MASTER" over the Temple's Wrath hunter (util/WrathHunt), always shown - it was announced.
     * The color's word in the Temple chart's bar color, the rank in its plate color; with the plates shown (Alt, R3, the
     * Units toggle) the days left to the week's turn follow, gold, orange in the last FADE_SOON days (round 791's colors).
     */
    public static final class WrathPlate {
        private TextraLabel label;
        private String key = "";

        void update(String color, String tier, int daysLeft) {
            String k = color + "/" + tier + "/" + daysLeft;
            if (k.equals(key) && label != null)
                return;
            key = k;
            if (label == null)
                label = Controls.newTextraLabel("");
            int r = Math.max(0, Math.min(3, EnemyData.tierRank(tier)));
            String dim = "[#a8a296]";
            label.setText("[%55][#" + WRATH_COLOR.getOrDefault(color, "e05a3a") + "]WRATH OF " + color.toUpperCase(Locale.ROOT)
                    + dim + " - [#" + RANK_COLOR[r] + "]" + RANK_WORDS[r].trim().toUpperCase(Locale.ROOT)
                    + (daysLeft >= 1 ? dim + " - [#" + fadeColor(daysLeft) + "]" + daysLeft + (daysLeft == 1 ? " DAY" : " DAYS")
                    : ""));
            label.pack();
        }

        void draw(Batch batch, float parentAlpha, float cx, float foot) {
            if (label == null)
                return;
            float w = label.getWidth(), th = label.getHeight();
            float lo = th * BAND_LO, hi = th * BAND_HI;
            float x = cx - w / 2f, y = placeBox(x, foot + 1f - lo, w, lo, hi);
            Color was = batch.getColor().cpy();
            drawBox(batch, parentAlpha, x, y, w, lo, hi);
            label.setPosition(x, y);
            label.draw(batch, parentAlpha);
            batch.setColor(was);
        }
    }

    /** Round 814: the hunted color's word on its plate - the Temple chart's bar colors, red and white lightened to read. */
    private static final Map<String, String> WRATH_COLOR = new java.util.HashMap<>();

    static {
        WRATH_COLOR.put("white", "eee3b8");
        WRATH_COLOR.put("blue", "5c8fe0");
        WRATH_COLOR.put("black", "9a86a6");
        WRATH_COLOR.put("red", "e8543c");
        WRATH_COLOR.put("green", "4fb35f");
    }

    private static final class PlaceQueued {
        PlacePlate plate;
        float cx, foot;
    }

    private static final List<PlaceQueued> placeQueue = new ArrayList<>();

    /**
     * Round 791: a world-map entrance's days left while the plates show - only a place on a timer (a rotating dungeon or
     * cave with no quest on it; DungeonRotation.fadeDays() >= 1). Read twice a second, not every frame: the answer
     * changes once a day, and fadeDays() walks the quest log.
     */
    public static final class PlacePlate {
        private TextraLabel label;
        private int days = Integer.MIN_VALUE;
        private long readAt;

        boolean update(PointOfInterest poi) {
            long now = System.currentTimeMillis();
            if (days == Integer.MIN_VALUE || now - readAt > 500) {
                readAt = now;
                int fade = DungeonRotation.isRotatableData(poi.getData()) ? DungeonRotation.fadeDays(poi, false)
                        : DungeonRotation.FADE_NONE;
                if (fade != days) {
                    days = fade;
                    if (days >= 1) {
                        if (label == null)
                            label = Controls.newTextraLabel("");
                        label.setText("[%55][#" + fadeColor(days) + "]" + days + (days == 1 ? " DAY" : " DAYS"));
                        label.pack();
                    }
                }
            }
            return days >= 1 && label != null;
        }

        void draw(Batch batch, float parentAlpha, float cx, float foot) {
            float w = label.getWidth(), th = label.getHeight();
            float lo = th * BAND_LO, hi = th * BAND_HI;
            float x = cx - w / 2f, y = placeBox(x, foot + 1f - lo, w, lo, hi);
            Color was = batch.getColor().cpy();
            drawBox(batch, parentAlpha, x, y, w, lo, hi);
            label.setPosition(x, y);
            label.draw(batch, parentAlpha);
            batch.setColor(was);
        }
    }

    public static final class Plate {
        private TextraLabel rank, life, raider, bounty, mirror;
        private String key = "", colors = "";
        /** Round 866: the town this raider pillages (TownPillage.raiderTown), or null - its plate's RAIDER line. */
        private String raiderTown;
        /** Round 894: the quest this kill counts toward (huntOf), or null - its QUEST line; a raider keeps its own. */
        private String questName;
        private int huntsSeen;
        private boolean unknownColors, legend, coin, showLife, line;
        private int lifeNow = -1;
        private long lifeAt;
        /** Round 943: the Bounty a win would pay now (StreakBounty.percentFor), 0 for none; read twice a second. */
        private int bountyPct;
        private long bountyAt;
        /** Round 1030: the gear this enemy would copy now (MirrorGear.forSeat), "" for none; read with the Bounty. */
        private String mirrorItems = "";

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
            raiderTown = TownPillage.raiderTown(sprite);
            if (raiderTown != null)
                raiders++;
            if (huntsSeen != huntsRead) {
                huntsSeen = huntsRead;
                questName = huntOf(sprite);
            }
            String quest = raiderTown == null ? questName : null;
            if (quest != null)
                questTargets++;
            long nowB = System.currentTimeMillis();
            if (bountyAt == 0 || nowB - bountyAt > 500) {
                bountyPct = StreakBounty.percentFor(d);
                bountyAt = nowB;
                mirrorItems = MirrorGear.copiesNow(d) > 0 ? String.join(" + ", MirrorGear.forSeat(d, 0).items) : "";
            }
            if (bountyPct > 0)
                bounties++;
            int r = Math.max(0, Math.min(3, EnemyData.tierRank(d.tier)));
            String k = r + "/" + d.boss + "/" + showLife + "/" + lifeNow + "/" + buffed + "/" + raiderTown + "/" + quest
                    + "/" + bountyPct + "/" + mirrorItems;
            if (k.equals(key))
                return;
            key = k;
            if (rank == null) {
                rank = Controls.newTextraLabel("");
                life = Controls.newTextraLabel("");
                raider = Controls.newTextraLabel("");
                bounty = Controls.newTextraLabel("");
                mirror = Controls.newTextraLabel("");
            }
            if (!mirrorItems.isEmpty()) { // round 1030: the gear it copies, above the BOUNTY line
                mirror.setText("[%55][#9fc5e8]MIRRORS[#a8a296] - [#9fc5e8]" + mirrorItems.toUpperCase(Locale.ROOT));
                mirror.pack();
            }
            if (bountyPct > 0) { // round 943: the Bounty, in the RAIDER/QUEST line's slot, above it when both show; the
                // gold coin is drawn after it at the plate's icon size (a [+Gold] glyph keeps its full size at [%55])
                bounty.setText("[%55][#e8b84a]BOUNTY +" + bountyPct + "%");
                bounty.pack();
            }
            if (raiderTown != null) {
                raider.setText("[%55][#e67e22]RAIDER[#a8a296] - [#e67e22]" + raiderTown.toUpperCase(Locale.ROOT));
                raider.pack();
            } else if (quest != null) {
                raider.setText("[%55][#e8b84a]QUEST[#a8a296] - [#e8b84a]" + quest.toUpperCase(Locale.ROOT));
                raider.pack();
            }
            line = raiderTown != null || quest != null;
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
            // Round 866: a raider's RAIDER line (round 894: a hunted enemy's QUEST line) sits right above, so the two are
            // placed as one box. Round 943: the BOUNTY line above that (or in its place).
            float step = hi - lo + 2f;
            boolean showBounty = bountyPct > 0;
            boolean showMirror = !mirrorItems.isEmpty(); // round 1030: the MIRRORS line on top
            int lines = (line ? 1 : 0) + (showBounty ? 1 : 0) + (showMirror ? 1 : 0);
            float extra = lines * step, raw = line ? raider.getWidth() : 0f;
            float bwid = showBounty ? bounty.getWidth() + GAP + ICON : 0f;
            float mwid = showMirror ? mirror.getWidth() : 0f;
            float x = cx - w / 2f, bw = Math.max(Math.max(w, mwid), Math.max(raw, bwid));
            float y = placeBox(cx - bw / 2f, foot + 1f - lo, bw, lo, hi + extra);
            Color was = batch.getColor().cpy();
            drawBox(batch, parentAlpha, x, y, w, lo, hi);
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
            if (line) {
                float rx = cx - raw / 2f, ry = y + step;
                drawBox(batch, parentAlpha, rx, ry, raw, lo, hi);
                raider.setPosition(rx, ry);
                raider.draw(batch, parentAlpha);
            }
            if (showBounty) {
                float bx = cx - bwid / 2f, by = y + ((line ? 1 : 0) + 1) * step;
                drawBox(batch, parentAlpha, bx, by, bwid, lo, hi);
                bounty.setPosition(bx, by);
                bounty.draw(batch, parentAlpha);
                TextureRegion gold = items.findRegion("Gold");
                if (gold != null)
                    batch.draw(gold, bx + bounty.getWidth() + GAP, by + mid - (ICON - 1f) / 2f, ICON - 1f, ICON - 1f);
            }
            if (showMirror) {
                float mx = cx - mwid / 2f, my = y + lines * step;
                drawBox(batch, parentAlpha, mx, my, mwid, lo, hi);
                mirror.setPosition(mx, my);
                mirror.draw(batch, parentAlpha);
            }
            batch.setColor(was);  // the HUD shares the batch: no color left behind (GameHUD.draw, round 329)
        }
    }
}
