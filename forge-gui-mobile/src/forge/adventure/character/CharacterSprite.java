package forge.adventure.character;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.utils.Array;
import forge.adventure.data.DialogData;
import forge.adventure.stage.SpriteGroup;
import forge.adventure.util.Config;

import java.util.HashMap;

/**
 * CharacterSprite base class for animated sprites on the map
 */

public class CharacterSprite extends MapActor {
    private static final float DEFAULT_ANIMATION_FRAME_DURATION = 0.2f;
    private static final float MAX_DEATH_ANIMATION_DURATION = 3f;
    private static final float MAX_ACTION_ANIMATION_DURATION = 5f;
    private final HashMap<AnimationTypes, HashMap<AnimationDirections, Animation<TextureRegion>>> animations = new HashMap<>();
    float timer;
    private Animation<TextureRegion> currentAnimation = null;
    private AnimationTypes currentAnimationType = AnimationTypes.Idle;
    private AnimationDirections currentAnimationDir = AnimationDirections.None;
    private final Array<Sprite> avatar = new Array<>();
    public boolean hidden = false;
    public boolean inactive = false;
    private String atlasPath;
    private float wakeTimer = 0.0f;
    public DialogData.ConditionData[] spawnConditions = new DialogData.ConditionData[0]; //List of conditions for the sprite to spawn.
    /** Round 160: a rank whose size cue this sprite carries even though it is not an EnemySprite -
     *  the roaming guards, drawn on the hero atlas, so a Master guard and the Master mage it races
     *  read as the same rank. Null = no cue (the player, dialog actors, rewards). */
    private String tierCue = null;

    public void setTierCue(String tier) {
        tierCue = tier;
    }

    /** Round 578: where the creature is inside its frame - the atlas's optional `footprint: x, y, w, h` field on its
     *  Idle frame (dev-tools/sprite_footprints.py writes it): the body's first column and width, and the empty rows
     *  under its lowest drawn pixel, in atlas px. A remake's frame is as wide as its widest pose (an attack lunge),
     *  and the game anchored the FRAME at the position, so the body drew a tile or more right of / above where the
     *  map put it and the whole frame was the duel box. -1 = no footprint: the frame is the actor, as in stock. */
    private float footprintX0 = -1f;
    private float footprintWidth = 0f;
    private float footprintLift = 0f;
    private static final String FOOTPRINT_FIELD = "footprint";
    /** One tile at rank cue 1.0, and the most a side of a body narrower than its rank's tile is padded by - a stock
     *  16 px humanoid's frame has about that around its body. Mirrored in dev-tools/sprite_footprints.py. */
    private static final float FOOTPRINT_TILE = 16f;
    private static final float FOOTPRINT_MARGIN = 3f;

    /** Round 591: a hero sheet drawn below its own size (heroes.json "scale", the Pixelate Phyrexian's 64 px art at
     *  0.375) - 1 = drawn as authored, as every stock 16 px hero is. */
    private float heroScale = 1f;
    /** Round 777: the [TFR-Hero] lines already printed this launch. */
    private static final java.util.Set<String> heroLinesLogged = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /** Round 591: the player and the roaming guards on a hero sheet with a heroes.json "scale". The frame draws at that
     *  scale (times a guard's rank cue) with its body - the footprint - centred on the actor, but the actor itself
     *  stays the 16 px box every stock hero frame gave it (a guard's: times its cue), so collisions, doorways, map
     *  entries and the camera behave exactly as before. Call after setTierCue. */
    public void setHeroScale(float scale) {
        heroScale = scale > 0f ? scale : 1f;
        if (heroScale != 1f) {
            float side = heroBox();
            setSize(side, side);
            String line = "[TFR-Hero] " + atlasPath + " drawn at scale " + heroScale + " on a " + side + " px box"
                    + (hasFootprint() ? "" : " (no footprint: the frame is centred)");
            if (heroLinesLogged.add(line)) // round 777: once per launch, not four times per load
                System.out.println(line);
        }
    }

    private float heroBox() {
        return FOOTPRINT_TILE * (tierCue == null ? 1f
                : Config.instance().getTuningData().tierSizeMultiplier(tierCue, FOOTPRINT_TILE, false));
    }

    private static final Color batchColor = new Color();
    private static final Vector2 moveAngle = new Vector2();
    public CharacterSprite(int id, String path) {
        super(id);
        collisionHeight = 0.4f;
        atlasPath = path;
        load(path);
    }

    public CharacterSprite(String path) {
        this(0, path);
    }

    @Override
    void updateBoundingRect() {//We want a slimmer box for the player entity so it can navigate terrain without getting stuck.
        boundingRect.set(getX() + 4, getY(), getWidth() - 6, getHeight() * collisionHeight);
    }

    protected void load(String path) {
        if (path == null || path.isEmpty()) return;
        animations.clear();
        for (AnimationTypes stand : AnimationTypes.values()) {
            if (stand == AnimationTypes.Avatar) {
                avatar.addAll(Config.instance().getAnimatedSprites(path, stand.toString()));
                continue;
            }
            HashMap<AnimationDirections, Animation<TextureRegion>> dirs = new HashMap<>();
            for (AnimationDirections dir : AnimationDirections.values()) {

                Array<Sprite> anim;
                if (dir == AnimationDirections.None)
                    anim = Config.instance().getAnimatedSprites(path, stand.toString());
                else
                    anim = Config.instance().getAnimatedSprites(path, stand.toString() + dir.toString());

                if (anim.size != 0) {
                    float frameDuration = DEFAULT_ANIMATION_FRAME_DURATION;
                    if (stand == AnimationTypes.Death) {
                        frameDuration = Math.min(frameDuration, MAX_DEATH_ANIMATION_DURATION / anim.size);
                    }
                    dirs.put(dir, new Animation<>(frameDuration, anim));
                    if (getWidth() == 0.0)//init size onload
                    {
                        setWidth(anim.first().getWidth());
                        setHeight(anim.first().getHeight());
                    }
                }
            }
            animations.put(stand, dirs);
        }


        for (AnimationTypes stand : AnimationTypes.values()) {
            if (stand == AnimationTypes.Avatar) {
                continue;
            }
            HashMap<AnimationDirections, Animation<TextureRegion>> dirs = animations.get(stand);

            if (!dirs.containsKey(AnimationDirections.None) && dirs.containsKey(AnimationDirections.Right)) {
                dirs.put(AnimationDirections.None, (dirs.get(AnimationDirections.Right)));
            }
            if (!dirs.containsKey(AnimationDirections.Right) && dirs.containsKey(AnimationDirections.None)) {
                dirs.put(AnimationDirections.Right, (dirs.get(AnimationDirections.None)));
            }
            if (!dirs.containsKey(AnimationDirections.Left) && dirs.containsKey(AnimationDirections.Right)) {
                dirs.put(AnimationDirections.Left, FlipAnimation(dirs.get(AnimationDirections.Right)));
            }
            if (dirs.containsKey(AnimationDirections.Left) && !dirs.containsKey(AnimationDirections.Right)) {
                dirs.put(AnimationDirections.Right, FlipAnimation(dirs.get(AnimationDirections.Left)));
            }
            if (!dirs.containsKey(AnimationDirections.LeftUp) && dirs.containsKey(AnimationDirections.Left)) {
                dirs.put(AnimationDirections.LeftUp, dirs.get(AnimationDirections.Left));
            }
            if (!dirs.containsKey(AnimationDirections.LeftDown) && dirs.containsKey(AnimationDirections.Left)) {
                dirs.put(AnimationDirections.LeftDown, dirs.get(AnimationDirections.Left));
            }
            if (!dirs.containsKey(AnimationDirections.RightDown) && dirs.containsKey(AnimationDirections.Right)) {
                dirs.put(AnimationDirections.RightDown, dirs.get(AnimationDirections.Right));
            }
            if (!dirs.containsKey(AnimationDirections.RightUp) && dirs.containsKey(AnimationDirections.Right)) {
                dirs.put(AnimationDirections.RightUp, dirs.get(AnimationDirections.Right));
            }
            if (!dirs.containsKey(AnimationDirections.Up) && dirs.containsKey(AnimationDirections.Right)) {
                dirs.put(AnimationDirections.Up, dirs.get(AnimationDirections.Right));
            }
            if (!dirs.containsKey(AnimationDirections.Down) && dirs.containsKey(AnimationDirections.Left)) {
                dirs.put(AnimationDirections.Down, dirs.get(AnimationDirections.Left));
            }
        }

        readFootprint(path);
        setAnimation(AnimationTypes.Idle);
        setDirection(AnimationDirections.Right);
    }

    /** Round 578 - see footprintX0. The field sits on the Idle frame findRegion() returns ("IdleRight" in an atlas
     *  with no plain side view); every other frame is taken to hold the body where that one does, which is what
     *  anchoring them all at getX()/getY() already assumed. A field that does not fit its frame is ignored. */
    private void readFootprint(String path) {
        footprintX0 = -1f;
        TextureAtlas atlas = Config.instance().getAtlas(path);
        TextureAtlas.AtlasRegion ref = atlas == null ? null : atlas.findRegion("Idle");
        if (ref == null && atlas != null)
            ref = atlas.findRegion("IdleRight");
        int[] fp = ref == null ? null : ref.findValue(FOOTPRINT_FIELD);
        if (fp == null || fp.length < 4 || fp[0] < 0 || fp[1] < 0 || fp[2] <= 0 || fp[3] <= 0
                || fp[0] + fp[2] > ref.getRegionWidth() || fp[1] + fp[3] > ref.getRegionHeight()) {
            if (fp != null)
                System.out.println("[TFR-Footprint] " + path + ": footprint " + java.util.Arrays.toString(fp)
                        + " does not fit its " + ref.getRegionWidth() + "x" + ref.getRegionHeight() + " frame - ignored");
            return;
        }
        footprintX0 = fp[0];
        footprintWidth = fp[2];
        footprintLift = ref.getRegionHeight() - (fp[1] + fp[3]);
    }

    public boolean hasFootprint() {
        return footprintX0 >= 0f;
    }

    /** Round 578: a walker's feet go on its position; a flier (EnemyData.flying) keeps the hover it was drawn with. */
    private boolean footprintOnGround() {
        if (this instanceof EnemySprite) {
            forge.adventure.data.EnemyData data = ((EnemySprite) this).getData();
            return data == null || !data.flying;
        }
        return true;
    }

    /** Round 578: the actor's box for a sprite with a footprint, given its frame's drawn height and the draw scale:
     *  the body's columns, never narrower than min(one tile of its rank, body + 2 margins), from the feet to the
     *  frame's top. draw() offsets the frame to match; EnemySprite's constructor sizes its first box with it. */
    protected void sizeToFootprint(float frameHeight, float scale, String tier) {
        float body = footprintWidth * scale;
        float tile = FOOTPRINT_TILE * (tier == null ? 1f : Config.instance().getTuningData().tierScale(tier));
        setWidth(Math.max(body, Math.min(tile, body + 2f * FOOTPRINT_MARGIN)));
        setHeight(frameHeight - (footprintOnGround() ? footprintLift * scale : 0f));
    }

    static public Animation<TextureRegion> FlipAnimation(Animation<TextureRegion> anim) {
        TextureRegion[] texReg = anim.getKeyFrames();
        Array<TextureRegion> newReg = new Array<>();
        for (TextureRegion reg : texReg) {
            TextureRegion cpy = new TextureRegion(reg);
            cpy.flip(true, false);
            newReg.add(cpy);
        }
        return new Animation<>(anim.getFrameDuration(), newReg);
    }

    public void setAnimation(AnimationTypes type) {
        Animation<TextureRegion> animation = getAnimation(type, currentAnimationDir);
        if (animation == null) {
            return;
        }

        if (currentAnimationType != type || currentAnimation != animation || isOneShotAnimation(type)) {
            currentAnimationType = type;
            currentAnimation = animation;
            if (isOneShotAnimation(type)) {
                timer = 0.0f;
            }
        }
    }

    /**
     * Returns the capped duration of an action animation in the sprite's current direction.
     * Uses the supplied fallback when the atlas does not define that animation.
     */
    public float getActionAnimationDuration(AnimationTypes type, float fallbackDuration) {
        Animation<TextureRegion> animation = getAnimation(type, currentAnimationDir);
        float duration = animation == null ? fallbackDuration : animation.getAnimationDuration();
        return Math.min(duration, MAX_ACTION_ANIMATION_DURATION);
    }

    private Animation<TextureRegion> getAnimation(AnimationTypes type, AnimationDirections direction) {
        HashMap<AnimationDirections, Animation<TextureRegion>> dirs = animations.get(type);
        if (dirs == null || dirs.isEmpty()) {
            return null;
        }

        Animation<TextureRegion> animation = dirs.get(direction);
        return animation == null ? dirs.get(AnimationDirections.Right) : animation;
    }

    private boolean isOneShotAnimation(AnimationTypes type) {
        return type == AnimationTypes.Attack
                || type == AnimationTypes.Death
                || type == AnimationTypes.Hit;
    }

    private void updateAnimation() {
        Animation<TextureRegion> animation = getAnimation(currentAnimationType, currentAnimationDir);
        if (animation == null) {
            animation = getAnimation(AnimationTypes.Idle, currentAnimationDir);
        }
        if (animation != null) {
            currentAnimation = animation;
        }
    }

    public void setDirection(AnimationDirections dir) {
        if (currentAnimationDir != dir) {
            currentAnimationDir = dir;
            updateAnimation();
        }
    }


    @Override
    protected void positionChanged() {
        Actor parent = getParent();
        if (parent instanceof SpriteGroup) {
            ((SpriteGroup) parent).UpdateActorZ(this);
        }
        super.positionChanged();
    }

    @Override
    public void moveBy(float x, float y) {
        moveBy(x, y, 0.0f);
    }

    public void moveBy(float x, float y, float delta) {
        if (inactive) {
            return;
        }

        if (hidden) {
            if (animations.containsKey(AnimationTypes.Wake)) {
                //Todo: Need another check here if we want objects revealed by activateMapObject to play wake animation
                setAnimation(AnimationTypes.Wake);
                wakeTimer = 0.0f;
                hidden = false;
            } else return;
        }

        if (currentAnimationType == AnimationTypes.Wake && wakeTimer <= currentAnimation.getAnimationDuration()) {
            wakeTimer += delta;
            return;
        }

        super.moveBy(x, y);
        if (x == 0 && y == 0) {
            return;
        }

        moveAngle.set(x, y);
        float degree = moveAngle.angleDeg();

        if (!hidden)
            setAnimation(AnimationTypes.Walk);
        if (degree < 22.5f)
            setDirection(AnimationDirections.Right);
        else if (degree < 22.5f + 45f)
            setDirection(AnimationDirections.RightUp);
        else if (degree < 22.5f + 45f * 2f)
            setDirection(AnimationDirections.Up);
        else if (degree < 22.5f + 45f * 3f)
            setDirection(AnimationDirections.LeftUp);
        else if (degree < 22.5f + 45f * 4f)
            setDirection(AnimationDirections.Left);
        else if (degree < 22.5f + 45f * 5f)
            setDirection(AnimationDirections.LeftDown);
        else if (degree < 22.5f + 45f * 6f)
            setDirection(AnimationDirections.Down);
        else if (degree < 22.5f + 45f * 7f)
            setDirection(AnimationDirections.RightDown);
        else
            setDirection(AnimationDirections.Right);
    }

    public Vector2 pos() {
        // The movement system requires an independent instance copy to prevent
        // player and NPC coordinates from cross-contaminating each other..
        return new Vector2(getX(), getY());
    }


    @Override
    public void act(float delta) {
        timer += delta;
        super.act(delta);

    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        if (currentAnimation == null || hidden || inactive) {
            return;
        }
        super.draw(batch, parentAlpha);
        beforeDraw(batch, parentAlpha);

        TextureRegion currentFrame;
        if (currentAnimationType.equals(AnimationTypes.Wake)) {
            currentFrame = currentAnimation.getKeyFrame(wakeTimer, false);
        } else {
            currentFrame = currentAnimation.getKeyFrame(timer, !isOneShotAnimation(currentAnimationType));
        }

        // Round 159: rendered size is atlasSize x scale x tier cue. `scale` is the artist's
        // per-creature intent (a ladybug is deliberately half a tile); the tier cue is the separate
        // Apprentice->Archmage size signal, config-driven and 1.0-neutral. Round 160 anchored that
        // cue to ONE TILE (TuningData.tierSizeMultiplier: a 16px sprite still renders at exactly
        // tierScale x 16, but a 96px boss now grows 4px rather than 24) and gave the roaming
        // guards the same cue through setTierCue().
        float scale = 1f;
        String tier = tierCue;
        // Round 261: a boss or a hand-placed set piece is authored large on purpose and keeps its native frame;
        // everything else is held to the ceiling in tierSizeMultiplier. A sprite with no EnemyData behind it (a
        // roaming guard given a cue through setTierCue) is ordinary, so it is capped like any other.
        boolean keepNativeSize = false;
        if (this instanceof EnemySprite) {
            forge.adventure.data.EnemyData enemyData = ((EnemySprite) this).getData();
            if (enemyData != null) {
                scale = enemyData.scale;
                tier = enemyData.tier;
                keepNativeSize = enemyData.boss || enemyData.keepSize;
            }
        }
        if (heroScale != 1f) // round 591: a guard's cue is the one its stock 16 px hero frame got
            scale = heroScale * heroBox() / FOOTPRINT_TILE;
        else if (tier != null)
            scale *= Config.instance().getTuningData().tierSizeMultiplier(tier,
                    currentFrame.getRegionHeight() * scale, keepNativeSize);

        float frameWidth = currentFrame.getRegionWidth() * scale;
        float frameHeight = currentFrame.getRegionHeight() * scale;
        float drawX = getX();
        float drawY = getY();
        if (this instanceof EnemySprite && ((EnemySprite) this).disguisedAsLoot()) { // round 452: a Mimic is a chest's size
            frameWidth = EnemySprite.LOOT_DISGUISE_PIXELS;
            frameHeight = EnemySprite.LOOT_DISGUISE_PIXELS;
            setHeight(frameHeight);
            setWidth(frameWidth);
        } else if (heroScale != 1f) {
            // Round 591 (setHeroScale): the box stays the stock hero's; the body sits centred on it, feet on its floor
            float box = heroBox();
            setWidth(box);
            setHeight(box);
            if (hasFootprint()) {
                float bodyLeft = currentFrame.isFlipX()
                        ? currentFrame.getRegionWidth() - footprintX0 - footprintWidth : footprintX0;
                drawX += (box - footprintWidth * scale) / 2f - bodyLeft * scale;
                drawY -= footprintLift * scale;
            } else {
                drawX += (box - frameWidth) / 2f;
            }
        } else if (hasFootprint()) {
            // Round 578: the box is the creature (sizeToFootprint) and the frame is drawn so the body sits in it - a
            // mirrored frame (FlipAnimation) has the body at the other side. The rest of the frame (a lunge, a tail
            // swept back) draws outside the box.
            sizeToFootprint(frameHeight, scale, tier);
            float bodyLeft = currentFrame.isFlipX()
                    ? currentFrame.getRegionWidth() - footprintX0 - footprintWidth : footprintX0;
            drawX += (getWidth() - footprintWidth * scale) / 2f - bodyLeft * scale;
            if (footprintOnGround())
                drawY -= footprintLift * scale;
        } else {
            setHeight(frameHeight);
            setWidth(frameWidth);
        }

        Color originalBatchColor = batch.getColor();
        batchColor.set(originalBatchColor.r, originalBatchColor.g, originalBatchColor.b, originalBatchColor.a);

        batch.setColor(getColor());
        drawnFrame = currentFrame; // round 719: where the art went, for the info plate (TfrPlates)
        drawnX = drawX;
        drawnY = drawY;
        drawnW = frameWidth;
        drawnH = frameHeight;
        batch.draw(currentFrame, drawX, drawY, frameWidth, frameHeight);
        batch.setColor(batchColor);

        super.draw(batch, parentAlpha);
    }



    /** Round 719: the frame drawn last and where (world px) - the art can sit outside the actor's box (a footprint). */
    public TextureRegion drawnFrame;
    public float drawnX, drawnY, drawnW, drawnH;

    public Sprite getAvatar() {
        if (avatar == null || avatar.isEmpty())
            return null;
        return avatar.first();
    }

    public String getAtlasPath() {
        return atlasPath;
    }

    /** How many Avatar frames this sprite's atlas actually carries. 491 of the 493 enemy
     *  atlases have exactly one; goblin_group.atlas has three (one per pack member) and
     *  umber_hulk.atlas has zero. Callers sizing per-seat portraits for a multi-opponent duel
     *  need this - see DuelScene's per-seat loop. */
    public int getAvatarCount() {
        return avatar == null ? 0 : avatar.size;
    }

    /**
     * The Avatar frame for seat {@code index} of a multi-opponent duel, clamped to the last
     * frame the atlas actually has, or null when the atlas has no Avatar region at all.
     * <p>
     * This used to be a bare {@code avatar.get(index)}, which threw IndexOutOfBoundsException
     * for any chained ({@code EnemyData.nextEnemy}) fight whose atlas has a single Avatar frame
     * - i.e. every enemy except Goblin Pack. That crash is the reason the 1-vs-N support has
     * only ever been exercised once (2026-09-01 research, STAR_TOWNS_RESEARCH.md). Clamping
     * means extra seats share the last portrait instead of crashing; a purpose-built pack atlas
     * with N Avatar frames still gets distinct faces per seat.
     * <p>
     * The null case is not theoretical: umber_hulk.atlas ships with zero Avatar regions, and a
     * clamp alone would underflow to index -1 there.
     */
    public Sprite getAvatar(int index) {
        if (avatar == null || avatar.isEmpty())
            return null;
        return avatar.get(Math.min(index, avatar.size - 1));
    }

    public enum AnimationTypes {
        Idle,
        Walk,
        Death,
        Attack,
        Hit,
        Avatar,
        Hidden,
        Wake
    }

    public enum AnimationDirections {
        None,
        Right,
        RightDown,
        Down,
        LeftDown,
        Left,
        LeftUp,
        Up,
        RightUp
    }

}
