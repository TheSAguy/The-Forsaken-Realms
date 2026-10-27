package forge.adventure.character;

import com.badlogic.gdx.math.Vector2;
import forge.Forge;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.scene.Scene;
import forge.adventure.stage.GameStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;

/**
 * Class that will represent the player sprite on the map
 */
public class PlayerSprite extends CharacterSprite {
    private final float playerSpeed;
    private final Vector2 direction = new Vector2();
    private float playerSpeedModifier = 1f;
    private float playerSpeedEquipmentModifier = 1f;
    GameStage gameStage;

    private final Vector2 prevDirection = new Vector2();

    public PlayerSprite(GameStage gameStage) {
        super(AdventurePlayer.current().spriteName());
        applyHeroSize(); // round 591; round 677: at the level's size
        this.gameStage = gameStage;
        setOriginX(getWidth() / 2);
        Current.player().onPlayerChanged(PlayerSprite.this::updatePlayer);

        playerSpeed = Config.instance().getConfigData().playerBaseSpeed;

        //Attach signals here.
        Current.player().onBlessing(() -> playerSpeedEquipmentModifier = Current.player().equipmentSpeed());
        Current.player().onEquipmentChanged(() -> playerSpeedEquipmentModifier = Current.player().equipmentSpeed());
    }

    private void updatePlayer() {
        load(AdventurePlayer.current().spriteName());
        applyHeroSize(); // round 591: a loaded save may be another race
    }

    // Round 677: the Power and Ascendance state the hero was last sized at - a change (a level, a load, the console's
    // asc set, Ascendance switched off) resizes it in act().
    private int sizedAtPower = -1;
    private boolean sizedActive;
    private float sizedScale = -1f;

    /** Round 677: the race's heroes.json scale times the level's growth (Ascendance.heroSizeFactor). The roaming guards
     *  drawn on the player's sheet keep the plain scale - only the player grows. */
    /** Round 777: the last [TFR-HeroSize] line printed. */
    private static String lastHeroSizeLine;

    private void applyHeroSize() {
        applyHeroSize(true);
    }

    private void applyHeroSize(boolean always) {
        float factor = forge.adventure.util.Ascendance.heroSizeFactor();
        sizedAtPower = forge.adventure.util.Ascendance.power();
        sizedActive = forge.adventure.util.Ascendance.isActive();
        float scale = AdventurePlayer.current().spriteScale() * factor;
        if (!always && scale == sizedScale)
            return; // Power gained inside the same level
        sizedScale = scale;
        setHeroScale(scale);
        forge.adventure.data.AscendanceData d = forge.adventure.util.Ascendance.data();
        String line = "[TFR-HeroSize] " + (sizedActive ? "level " + forge.adventure.util.Ascendance.level()
                + ": body ~" + Math.round(d.heroSizeReference * factor * 10f) / 10f + " px" : "Ascendance off: today's size")
                + " (x" + factor + " on scale " + AdventurePlayer.current().spriteScale() + ")";
        if (!line.equals(lastHeroSizeLine)) { // round 777: only when it changes, not four times per load
            lastHeroSizeLine = line;
            System.out.println(line);
        }
    }

    public void LoadPos() {
        setPosition(AdventurePlayer.current().getWorldPosX(), AdventurePlayer.current().getWorldPosY());
    }

    public void storePos() {
        storePos(getX(), getY());
    }

    public void storePos(final float x, final float y) {
        AdventurePlayer.current().setWorldPosX(x);
        AdventurePlayer.current().setWorldPosY(y);
    }

    public Vector2 getMovementDirection() {
        return direction;
    }

    public void setMovementDirection(final Vector2 dir) {
        direction.set(dir);
    }

    public void setMoveModifier(float speed) {
        playerSpeedModifier = speed;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (forge.adventure.util.Ascendance.power() != sizedAtPower
                || forge.adventure.util.Ascendance.isActive() != sizedActive)
            applyHeroSize(false); // round 677
        if (Forge.advFreezePlayerControls)
            return;

        direction.setLength(playerSpeed * delta * playerSpeedModifier * playerSpeedEquipmentModifier);
        prevDirection.set(direction);
        Scene previousScene = forge.Forge.getCurrentScene();

        if(!direction.isZero()) {
            gameStage.prepareCollision(pos(), direction, boundingRect);
            direction.set(gameStage.adjustMovement(direction, boundingRect));
            moveBy(direction.x, direction.y);

            // If the player is blocked by an obstacle, and they haven't changed scenes,
            // they will keep trying to move in that direction
            if (previousScene == forge.Forge.getCurrentScene()) {
                direction.set(prevDirection);
            }
        }
    }

    public boolean isMoving() {
        return !direction.isZero();
    }

    public void stop() {
        direction.setZero();
        setAnimation(AnimationTypes.Idle);
    }

    public void setPosition(Vector2 oldPosition) {
        setPosition(oldPosition.x, oldPosition.y);
    }
}
