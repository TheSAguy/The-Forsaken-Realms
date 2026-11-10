package forge.adventure.util;

import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.World;

import java.util.List;

/**
 * Round 1005 (a player on Discord: "Im getting to the point where things are 'under attack' but I dont really know what
 * that means or how to interact with it"; the user: a pop-up "when your first town is under attack. Tells you exactly
 * what's going on. What you need to do"). "The War Begins": five short pages - what is happening, how to defend, the
 * Roaming Guards, what the colors want, how the run ends - shown once, the first time a mage marches on one of the
 * player's towns. A save from before the round that already holds a town sees it once on load, its first page
 * reworded. World Standings' "The War" button (was Expansion) opens it again. The texts are tips.json's war.* keys,
 * their numbers filled from the running game (custom difficulty included). The Capitol's Archmage surge is left out
 * on purpose (round 267: a hidden mechanic). [TFR-WarIntro].
 */
public final class WarIntro {
    private WarIntro() {
    }

    public static final int PAGES = 5;

    /** Why the pop-up shows now, or NONE. */
    public enum Reason { NONE, FIRST_ATTACK, OLD_SAVE }

    /**
     * The whole decision, pure for the unit test.
     * @param shown       the world's warIntroShown flag
     * @param oldSave     the save was written before round 1005 (no warIntroShown key) and not yet checked this load
     * @param townsHeld   the player's towns, the Capitol included
     * @param underAttack the player's towns a mage is marching on now
     */
    public static Reason decide(boolean shown, boolean oldSave, int townsHeld, int underAttack) {
        if (shown)
            return Reason.NONE;
        if (underAttack > 0)
            return Reason.FIRST_ATTACK;
        if (oldSave && townsHeld > 0)
            return Reason.OLD_SAVE;
        return Reason.NONE;
    }

    /** The saved key. */
    public static final String KEY = "warIntroShown";

    /** What a save says: {shown, oldSave}. A save without the key is from before the round: not shown, old. */
    public static boolean[] readState(SaveFileData data) {
        if (data == null || !data.containsKey(KEY))
            return new boolean[]{false, true};
        return new boolean[]{data.readBool(KEY), false};
    }

    // ------------------------------------------------------------------ the world-map check

    private static int frame;

    /** WorldStage.onActing, every frame past its dialog and pause guards; looks twice a second. */
    public static void check(World world) {
        if (world == null || world.isWarIntroShown() || !Config.instance().getConfigData().territoryControlEnabled)
            return;
        if (++frame % 30 != 0)
            return;
        List<PointOfInterest> underAttack = TerritoryControl.playerTownsUnderAttack();
        boolean oldSave = world.isWarIntroOldSave();
        int held = oldSave ? TownRestoration.countPlayerTowns() : 0;
        Reason reason = decide(false, oldSave, held, underAttack.size());
        if (oldSave && reason == Reason.NONE) {
            world.setWarIntroOldSave(false); // an old save with no town yet waits for its first real attack
            System.out.println("[TFR-WarIntro] save from before 1.29 holds no town - waiting for the first attack");
            return;
        }
        if (reason == Reason.NONE)
            return;
        world.setWarIntroShown(true);
        world.setWarIntroOldSave(false);
        String[] attack = firstAttack(underAttack);
        if (reason == Reason.FIRST_ATTACK)
            System.out.println("[TFR-WarIntro] shown (first attack: " + attack[0] + " -> " + attack[1] + ")");
        else
            System.out.println("[TFR-WarIntro] shown on load (save from before 1.29, " + held + " town(s) held)");
        WorldStage.getInstance().showWarIntro(pages(reason == Reason.FIRST_ATTACK ? attack : null));
    }

    /** {color, town} of the first mage marching on one of the player's towns, or {"?", "?"}. */
    private static String[] firstAttack(List<PointOfInterest> underAttack) {
        if (underAttack.isEmpty())
            return new String[]{"?", "?"};
        PointOfInterest town = underAttack.get(0);
        String color = "?";
        for (EnemySprite mage : WorldStage.getInstance().getTerritoryMages())
            if (mage.territoryTarget == town && mage.territoryColor != null) {
                color = Character.toUpperCase(mage.territoryColor.charAt(0)) + mage.territoryColor.substring(1);
                break;
            }
        return new String[]{color, town.getDisplayName()};
    }

    // ------------------------------------------------------------------ the pages

    /** {title, body} per page. attack = {color, town} for the first-attack opening, null for the general one. */
    public static String[][] pages(String[] attack) {
        int lose = Config.instance().getTuningData().starTownsLossCount;
        if (lose <= 0)
            lose = 3;
        String[][] out = new String[PAGES][];
        out[0] = new String[]{text("war.title1"), attack != null
                ? text("war.page1", "color", attack[0], "town", attack[1]) : text("war.page1Later")};
        out[1] = new String[]{text("war.title2"), text("war.page2", "wage", EconomyBuildings.guardWeeklyGoldCost("Common"))};
        out[2] = new String[]{text("war.title3"), text(TownRestoration.capitolExists() ? "war.page3Capitol" : "war.page3",
                "towns", TownRestoration.CAPITOL_TOWNS_REQUIRED, "guards", RoamingGuards.maxGuards(),
                "rest", RoamingGuards.recoveryDaysNow())};
        out[3] = new String[]{text("war.title4"), text("war.page4")};
        out[4] = new String[]{text("war.title5"), text("war.page5", "lose", lose, "warn", Math.max(1, lose - 1))};
        return out;
    }

    /** A war.* text, {name}s filled; the dialog's own colors (not a hover tip's ink). */
    static String text(String key, Object... kv) {
        String raw = TfrTips.data().getString(key, null);
        if (raw == null) {
            System.err.println("[TFR-WarIntro] tips.json has no " + key);
            return "";
        }
        for (int i = 0; i + 1 < kv.length; i += 2)
            raw = raw.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        return TfrTips.fill(raw);
    }

    /** Page i of pages into a dialog: its title and count, its text, Back / Next / Understood. */
    public static void fill(Dialog dialog, String[][] pages, int i, java.util.function.IntConsumer goTo, Runnable close) {
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        float width = Forge.isLandscapeMode() ? 330f : 240f;
        TypingLabel title = Controls.newTypingLabel("[GOLD]" + pages[i][0] + "[]  [GRAY](" + (i + 1) + "/" + pages.length + ")[]");
        title.skipToTheEnd();
        dialog.getContentTable().add(title).width(width).padBottom(4f).row();
        TypingLabel body = Controls.newTypingLabel(pages[i][1]);
        body.setWrap(true);
        body.skipToTheEnd();
        dialog.getContentTable().add(body).width(width).row();
        float half = (width - 6f) / 2f;
        if (i > 0)
            dialog.getButtonTable().add(Controls.newTextButton("Back", () -> goTo.accept(i - 1))).width(half).padRight(6f);
        if (i < pages.length - 1)
            dialog.getButtonTable().add(Controls.newTextButton("Next", () -> goTo.accept(i + 1))).width(i > 0 ? half : width);
        else
            dialog.getButtonTable().add(Controls.newTextButton("Understood", close)).width(half);
    }

    // ------------------------------------------------------------------ read it again

    /** World Standings' "The War" button: the same pages, opened by the player's request. */
    public static void reopen(forge.adventure.scene.UIScene scene) {
        List<PointOfInterest> underAttack = TerritoryControl.playerTownsUnderAttack();
        String[][] pages = pages(underAttack.isEmpty() ? null : firstAttack(underAttack));
        System.out.println("[TFR-WarIntro] reopened from World Standings");
        showInScene(scene, pages, 0);
    }

    private static void showInScene(forge.adventure.scene.UIScene scene, String[][] pages, int i) {
        Dialog dialog = new Dialog("", Controls.getSkin());
        fill(dialog, pages, i, next -> {
            scene.removeDialog();
            showInScene(scene, pages, next);
        }, scene::removeDialog);
        scene.showDialog(dialog);
    }

    // ------------------------------------------------------------------ console (cheats)

    /** war [show | reset | old] - test hooks: show the pages, clear the flag, or play a save from before 1.29. */
    public static String console(String[] s) {
        World world = forge.adventure.world.WorldSave.getCurrentSave().getWorld();
        String what = s.length > 0 ? s[0] : "";
        switch (what) {
            case "show":
                WorldStage.getInstance().showWarIntro(pages(null));
                return "War pages shown";
            case "reset":
                world.setWarIntroShown(false);
                world.setWarIntroOldSave(false);
                System.out.println("[TFR-WarIntro] console: flag cleared - the next attack on a player town shows it");
                return "War intro flag cleared";
            case "old":
                world.setWarIntroShown(false);
                world.setWarIntroOldSave(true);
                System.out.println("[TFR-WarIntro] console: playing a save from before 1.29");
                return "War intro: old-save check armed";
            case "attack": { // war attack <color>: an Apprentice from that color's castle at the player's first town
                String color = s.length > 1 ? s[1].toLowerCase() : "blue";
                PointOfInterest town = null;
                for (PointOfInterest poi : world.getAllPointOfInterest())
                    if (TownRestoration.isTownRestored(forge.adventure.world.WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID()))) {
                        town = poi;
                        break;
                    }
                PointOfInterest castle = TerritoryControl.findCastle(world, color);
                forge.adventure.data.EnemyData data = forge.adventure.data.WorldData.getEnemy(
                        forge.adventure.data.EnemyData.tierDisplayName("Common") + " " + Character.toUpperCase(color.charAt(0)) + color.substring(1) + " Wizard");
                if (town == null || castle == null || data == null)
                    return "war attack: " + (town == null ? "you hold no town" : castle == null ? "no " + color + " castle" : "no " + color + " Apprentice");
                EnemySprite mage = new EnemySprite(data);
                mage.territoryTarget = town;
                mage.territoryColor = color;
                WorldStage.getInstance().spawnAt(mage, new com.badlogic.gdx.math.Vector2(castle.getPosition()));
                System.out.println("[TFR-WarIntro] console: " + color + " Apprentice sent at " + town.getDisplayName());
                return color + " mage sent at " + town.getDisplayName();
            }
            default:
                return "war show | reset | old | attack <color>  (shown=" + world.isWarIntroShown() + ", oldSave=" + world.isWarIntroOldSave() + ")";
        }
    }
}
