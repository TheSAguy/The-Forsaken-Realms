package forge.adventure.util;

import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.utils.Timer;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.data.DifficultyData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.scene.GameScene;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;

import java.util.Properties;

/**
 * Round 779 (the user: "When someone has a win-streak of 50+, let's give them a hint to go to the next difficulty level.
 * Add the hint for loss also that you recommended."; the 2026-10-27 log review: one Hard player lost 11 times in a row to
 * one foe and reloaded each time, another Normal player was at 138 wins in a row). Two one-time hints, each a Notices line
 * plus one short dialog on the world map once the player is back there and nothing else is open:
 * <ul>
 * <li><b>Loss</b>: the 5th loss to the same foe ("Kurbis has beaten you 5 times") suggests the next difficulty down. Not
 * on the lowest difficulty. Losses count from the first one this round sees, reloaded ones included - that is the
 * player it is for.</li>
 * <li><b>Win</b>: 50 wins in a row suggests the next difficulty up. Not on the highest. The streak is the end of the
 * saved win-ratio record (AdventurePlayer.recentDuels: the duels notoriety counts, not the Arena or an Inn event), so a
 * player already past 50 when they load this version sees it on that load.</li>
 * </ul>
 * A save's difficulty can only be picked for a new game or New Game+ (NewGameScene, SaveLoadScene's New Game+ picker;
 * Settings has none), so both say so. Each hint shows once per world - its marks live in ReloadJab's store, outside the
 * save, keyed by the world's seed: a reload cannot undo them (the loss count in a save would go back down with the very
 * reload it counts), and no save changes. Once per world, not once per foe: the advice is about the difficulty, and a
 * player who reloads every loss would otherwise hear it for every boss. The store remembers the difficulty the marks
 * were made at; a different one (New Game+ keeps the seed) clears them, so a player who moved up hears the win hint
 * again at the new level. On a plane without the reload jab
 * (reloadJabEnabled) neither hint exists. Grep forge.log for [TFR-Hint].
 */
public final class DifficultyHints {
    private DifficultyHints() {
    }

    /** Losses to one foe before the loss hint. */
    static final int LOSSES = 5;
    /** Wins in a row before the win hint. */
    static final int WINS = 50;
    private static final String INK = "[#2b7a2b]";
    /** The watcher's poll, and how long the world map must stay quiet before a hint shows. */
    private static final float POLL_SECONDS = 0.5f;
    private static final float QUIET_SECONDS = 1.5f;

    private static Timer.Task task;
    private static float quietFor;
    /** What the watcher waits to show, read from the store when it is armed: the foe of a due loss hint, the win hint. */
    private static long watchedSeed;
    private static String dueLossFoe;
    private static boolean winShown;
    private static boolean loggedNoHarder;
    private static boolean loggedWinDue;

    /** ReloadJab.onDuelLost: one more loss to this foe; the 5th makes the loss hint due. */
    static void onDuelLost(String enemyName) {
        String foe = enemyName == null || enemyName.isEmpty() ? "?" : enemyName;
        String easier = neighbor(-1);
        if (easier == null) {
            System.out.println("[TFR-Hint] lost to " + foe + " - no easier difficulty than " + difficultyName()
                    + ", no loss hint");
            return;
        }
        long seed = ReloadJab.seed();
        int[] n = {0};
        ReloadJab.updateStore(p -> {
            if (p.getProperty("hint.lossShown." + seed) != null || p.getProperty("hint.lossDue." + seed) != null)
                return; // shown or waiting: nothing more to count
            String key = "hint.losses." + seed + "." + foe;
            n[0] = intOf(p.getProperty(key)) + 1;
            p.setProperty(key, String.valueOf(n[0]));
            if (n[0] >= LOSSES)
                p.setProperty("hint.lossDue." + seed, foe);
        });
        if (n[0] == 0)
            return;
        System.out.println("[TFR-Hint] lost to " + foe + " - " + n[0] + " of " + LOSSES + (n[0] >= LOSSES
                ? ", the loss hint (" + difficultyName() + " -> " + easier + ") is due on the world map" : ""));
        if (n[0] >= LOSSES)
            watch("the 5th loss");
    }

    /**
     * ReloadJab: a duel ended or a save loaded - reads this world's marks and arms the watcher. Its first tick decides
     * whether anything is due: at a duel's end the win is not on the record yet (afterGameEnd records it after this).
     */
    static void watch(String why) {
        try {
            long seed = ReloadJab.seed();
            Properties p = ReloadJab.readStore();
            String difficulty = difficultyName();
            if (!difficulty.equals("?") && !difficulty.equals(p.getProperty("hint.difficulty." + seed, difficulty))) {
                // The main thread's addition: a new difficulty (New Game+ keeps the world's seed) starts both hints over.
                ReloadJab.updateStore(s -> s.keySet().removeIf(k -> k.toString().startsWith("hint.")
                        && (k.toString().endsWith("." + seed) || k.toString().contains("." + seed + "."))));
                System.out.println("[TFR-Hint] difficulty " + p.getProperty("hint.difficulty." + seed) + " -> " + difficulty
                        + " - both hints start over (world " + seed + ")");
                p = ReloadJab.readStore();
            }
            if (!difficulty.equals("?") && p.getProperty("hint.difficulty." + seed) == null) {
                ReloadJab.updateStore(s -> s.setProperty("hint.difficulty." + seed, difficulty));
                p = ReloadJab.readStore();
            }
            if (seed != watchedSeed)
                loggedWinDue = false;
            watchedSeed = seed;
            dueLossFoe = neighbor(-1) == null ? null : p.getProperty("hint.lossDue." + seed);
            winShown = p.getProperty("hint.winShown." + seed) != null;
            quietFor = 0f;
            if (task != null && task.isScheduled())
                return;
            task = Timer.schedule(new Timer.Task() {
                @Override
                public void run() {
                    tick();
                }
            }, POLL_SECONDS, POLL_SECONDS);
        } catch (Exception e) {
            System.err.println("[TFR-Hint] watch (" + why + ") failed: " + e);
        }
    }

    /** The render thread, every POLL_SECONDS while a hint is due: shows it once the world map has stayed quiet. */
    private static void tick() {
        try {
            boolean win = dueLossFoe == null && winDue();
            if (ReloadJab.seed() != watchedSeed || (dueLossFoe == null && !win)) {
                stopWatching(); // another world loaded (its load arms again), or nothing to show
                return;
            }
            if (win && !loggedWinDue) {
                loggedWinDue = true;
                System.out.println("[TFR-Hint] " + winsInARow(Current.player().recentDuels()) + " wins in a row - the win"
                        + " hint (" + difficultyName() + " -> " + neighbor(1) + ") is due on the world map");
            }
            boolean onWorldMap = Forge.getCurrentScene() instanceof GameScene && !MapStage.getInstance().isInMap();
            quietFor = onWorldMap && isCalm() ? quietFor + POLL_SECONDS : 0f;
            if (quietFor < QUIET_SECONDS)
                return;
            quietFor = 0f;
            if (dueLossFoe != null)
                showLoss(dueLossFoe);
            else
                showWin();
        } catch (Exception e) {
            System.err.println("[TFR-Hint] tick failed: " + e);
            stopWatching();
        }
    }

    private static void stopWatching() {
        if (task != null)
            task.cancel();
        task = null;
    }

    private static boolean winDue() {
        if (winShown)
            return false;
        AdventurePlayer player = Current.player();
        if (player == null || winsInARow(player.recentDuels()) < WINS)
            return false;
        if (neighbor(1) == null) {
            if (!loggedNoHarder)
                System.out.println("[TFR-Hint] " + WINS + "+ wins in a row on " + difficultyName()
                        + " - no harder difficulty, no win hint");
            loggedNoHarder = true;
            return false;
        }
        return true;
    }

    /** The W's at the end of a win-ratio record ('W'/'L', oldest first). */
    static int winsInARow(String record) {
        int n = 0;
        for (int i = record == null ? -1 : record.length() - 1; i >= 0 && record.charAt(i) == 'W'; i--)
            n++;
        return n;
    }

    private static void showLoss(String foe) {
        long seed = watchedSeed;
        ReloadJab.updateStore(p -> {
            p.remove("hint.lossDue." + seed);
            p.setProperty("hint.lossShown." + seed, foe);
            p.keySet().removeIf(k -> k.toString().startsWith("hint.losses." + seed + ".")); // nothing reads them now
        });
        dueLossFoe = null;
        String easier = neighbor(-1);
        System.out.println("[TFR-Hint] shown: loss hint - " + foe + " beat the player " + LOSSES + " times ("
                + difficultyName() + " -> " + easier + ", world " + seed + ")");
        show("A Word of Advice", foe + " has beaten you " + LOSSES + " times. " + easier
                + " is always an option: pick it on your next game or New Game+.");
    }

    private static void showWin() {
        long seed = watchedSeed;
        ReloadJab.updateStore(p -> p.setProperty("hint.winShown." + seed, "true"));
        winShown = true;
        String harder = neighbor(1);
        System.out.println("[TFR-Hint] shown: win hint - " + winsInARow(Current.player().recentDuels())
                + " wins in a row (" + difficultyName() + " -> " + harder + ", world " + seed + ")");
        show("Ready for More?", WINS + " wins in a row! If the duels feel easy, try " + harder
                + " on your next game or New Game+.");
    }

    /** The Notices line and the world map's dialog, in round 756's style, with one button. */
    private static void show(String title, String text) {
        GameHUD.getInstance().logNotification(INK + text, true);
        WorldStage stage = WorldStage.getInstance();
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        TypingLabel heading = Controls.newTypingLabel("[%120]" + title);
        heading.skipToTheEnd();
        dialog.getContentTable().add(heading).padBottom(6f).row();
        TypingLabel label = Controls.newTypingLabel(text);
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();
        dialog.getButtonTable().add(Controls.newTextButton("Got it", stage::hideDialog)).width(240f).row();
        dialog.setKeepWithinStage(true);
        stage.showDialog();
    }

    /** Nothing else on screen and the player standing still (SupportPrompt's test). */
    private static boolean isCalm() {
        WorldStage world = WorldStage.getInstance();
        return !Forge.advFreezePlayerControls && Forge.getTransitionScreen() == null
                && !world.isDialogOnlyInput() && !MapStage.getInstance().isDialogOnlyInput()
                && (world.getPlayerSprite() == null || !world.getPlayerSprite().isMoving());
    }

    /** The difficulty {@code step} away from this game's in the plane's list (-1 easier, +1 harder), or null. */
    private static String neighbor(int step) {
        DifficultyData[] all = Config.instance().getConfigData().difficulties;
        AdventurePlayer player = Current.player();
        DifficultyData current = player == null ? null : player.getDifficulty();
        if (all == null || current == null || current.name == null)
            return null;
        for (int i = 0; i < all.length; i++)
            if (current.name.equals(all[i].name)) {
                int j = i + step;
                return j >= 0 && j < all.length ? all[j].name : null;
            }
        return null;
    }

    private static String difficultyName() {
        AdventurePlayer player = Current.player();
        return player == null || player.getDifficulty() == null ? "?" : player.getDifficulty().name;
    }

    private static int intOf(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Console "hint": the marks for this world. */
    public static String info() {
        long seed = ReloadJab.seed();
        Properties p = ReloadJab.readStore();
        StringBuilder losses = new StringBuilder();
        for (String k : p.stringPropertyNames())
            if (k.startsWith("hint.losses." + seed + "."))
                losses.append(losses.length() == 0 ? "" : ", ").append(k.substring(("hint.losses." + seed + ".").length()))
                        .append(' ').append(p.getProperty(k));
        AdventurePlayer player = Current.player();
        return "Hints (world " + seed + ", " + difficultyName() + "): loss " + (p.getProperty("hint.lossShown." + seed) != null
                ? "shown (" + p.getProperty("hint.lossShown." + seed) + ")" : p.getProperty("hint.lossDue." + seed) != null
                ? "due (" + p.getProperty("hint.lossDue." + seed) + ")" : "counting [" + losses + "]") + "; win "
                + (p.getProperty("hint.winShown." + seed) != null ? "shown" : "not shown") + ", "
                + (player == null ? 0 : winsInARow(player.recentDuels())) + " in a row of " + WINS;
    }
}
