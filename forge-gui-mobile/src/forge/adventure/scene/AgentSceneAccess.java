package forge.adventure.scene;

import com.badlogic.gdx.scenes.scene2d.Stage;

/**
 * Round 161 (agent bridge): the scene package's protected stages, exposed to
 * {@code forge.adventure.agent} through one mod-added file instead of edits to the stock scenes.
 */
public final class AgentSceneAccess {
    private AgentSceneAccess() {}

    /** The scene2d stage a scene draws its UI on: a UIScene's own stage, or a HudScene's game stage. */
    public static Stage stageOf(Scene scene) {
        if (scene instanceof UIScene)
            return ((UIScene) scene).stage;
        if (scene instanceof HudScene)
            return ((HudScene) scene).stage;
        return null;
    }

    /** What a RewardScene is showing: "Shop", "Loot", "QuestReward", "RewardChoice", ... (its package-private type). */
    public static String rewardSceneType(RewardScene scene) {
        return scene.type == null ? "unknown" : scene.type.name();
    }

    /** Starts a new game from whatever the New Game screen currently shows (its defaults unless the agent clicked).
     *  Round 317: {@code race} (a race's name, as the selector lists it) and {@code gender} ("male"/"female") are set
     *  first when given - the screen picks both at random, and testing a race needs that race. */
    public static boolean startNewGame(String race, String gender) {
        NewGameScene scene = NewGameScene.instance();
        if (forge.Forge.getCurrentScene() != scene)
            forge.Forge.switchScene(scene);
        if (race != null && !race.isEmpty() && !scene.selectRaceForAgent(race))
            return false;
        if (gender != null && !gender.isEmpty())
            scene.selectGenderForAgent(gender.toLowerCase().startsWith("f"));
        return scene.start();
    }
}
