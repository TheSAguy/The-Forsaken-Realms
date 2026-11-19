package forge.adventure.util;

import forge.adventure.data.TuningData;
import forge.adventure.player.AdventurePlayer;

/**
 * TFR round 1046: the week-1 ante grace. The new-player study (notes\newplayer-1118.md) found that new players who left
 * in week 1 had lost or conceded an early duel and lost a starter card to ante before they knew how a duel works. In
 * the world's first {@code anteGraceWeeks{D}} weeks (settings.json; TFR: Easy 1, Normal 1, Hard 0, Insane 0; the Custom
 * difficulty knob "Weeks without ante") a duel does not play for ante: DuelScene turns the game's ante rule off, so no
 * ante card is chosen, the Re-roll prompt never comes, and nothing is won or lost.
 *
 * <p>The week is the [TFR-EarlyLife] week count (SpawnTierWeighting.currentWeek: days 1-7 are week 1), read from the
 * world's day at each duel - so a save already past its grace is unaffected and a save loaded mid-week-1 keeps the grace
 * for its remaining days, with nothing stored. New Game+ never gets it (a returning player, like [TFR-EarlyLife]).
 * The player's own Play for Ante switch still wins: off is off in every week.
 */
public final class AnteGrace {
    private AnteGrace() {
    }

    /** The first day a duel plays for ante again after {@code graceWeeks} weeks of grace (week 1 = days 1-7). */
    public static int anteFromDay(int graceWeeks) {
        return Math.max(0, graceWeeks) * 7 + 1;
    }

    /** True when a duel on {@code day} of the world is inside {@code graceWeeks} weeks of grace. */
    public static boolean inGrace(int graceWeeks, int day, boolean newGamePlus) {
        if (graceWeeks <= 0 || newGamePlus || day <= 0)
            return false;
        return day < anteFromDay(graceWeeks);
    }

    /**
     * True when this duel skips ante for the grace; logs {@code [TFR-AnteGrace] week N: no ante (until day D)} when it
     * does. Call once per duel, only for a duel that would otherwise play for ante.
     */
    public static boolean skipsAnte(String enemyName) {
        try {
            TuningData tuning = forge.adventure.util.Config.instance().getTuningData();
            AdventurePlayer player = AdventurePlayer.current();
            if (tuning == null || player == null || player.getDifficulty() == null || Current.world() == null)
                return false;
            String difficulty = player.getDifficulty().name;
            int weeks = tuning.anteGraceWeeksFor(difficulty);
            int day = Current.world().getCurrentDay();
            boolean ngPlus = player.getCharacterFlag("newGamePlus") > 0;
            if (!inGrace(weeks, day, ngPlus))
                return false;
            System.out.println("[TFR-AnteGrace] week " + SpawnTierWeighting.currentWeek(Current.world())
                    + ": no ante (until day " + anteFromDay(weeks) + ") - day " + day + ", " + difficulty
                    + ", vs " + enemyName);
            return true;
        } catch (RuntimeException e) {
            System.out.println("[TFR-AnteGrace] check failed, ante as set: " + e);
            return false;
        }
    }
}
