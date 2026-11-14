package forge.adventure.util;

import forge.adventure.data.HeroListData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.world.WorldSave;

import java.util.ArrayList;
import java.util.List;

/**
 * Round 879 (follow-up; the user: "Might be cool if the character randomly chooses a new look from all the existing
 * races", then "maybe after a win they change after a loss, they starting form"): the Doppelganger's borrowed look.
 * A won duel puts on another race's look (never the true form, never the look it already wears); a lost duel brings
 * the true form back; a new game and a New Game+ start in it. Cosmetic only: the map sprite and its size follow the
 * look (AdventurePlayer.spriteName/spriteScale), while the race, its sets, the portrait and every text that names the
 * race stay the Doppelganger's. The look is saved ("doppelLook", -1 = true form), so a reload keeps it. No message to
 * the player (the user: "No message, will be too many messages") - the Status page says "Doppelganger (as Kor)", and
 * forge.log gets a [TFR-Doppel] line per change.
 */
public final class Doppelganger {
    private Doppelganger() {
    }

    /** Mixed into the pick so it never lines up with another seed drawn from the same world seed and count. */
    private static final long LOOK_SALT = 0x446F7070656CL; // "Doppel"

    /** Whether a duel moves the look: those the win/loss record counts (overworld, dungeon, Arena), less a guard's fight -
     *  not a draw, an Inn tournament or Deck Tester (a fixed-deck enemy). DuelScene reads it before its end runnable,
     *  because guardDeck is cleared for the next duel before that runs. */
    public static boolean counts(boolean drawn, boolean guardFight, boolean innEvent, boolean deckTester) {
        return !drawn && !guardFight && !innEvent && !deckTester;
    }

    /** The Status page: heads the blessing box with "Doppelganger (as Kor)" or "(true form)" for a Doppelganger - the
     *  page's stat rows sit at fixed places with no room for another line. Rewrites what the page just set, at the page's
     *  own text size (round 903). */
    public static void headBlessing(com.github.tommyettinger.textra.TextraLabel label, AdventurePlayer p, String size, String body) {
        String head = describe(p);
        if (head == null || label == null)
            return;
        // Round 1031: body is the sheet's "Active effects" text (upstream's, the 11.13 merge), which names the blessing
        label.setText(size + "[BLACK]" + head + "\n" + body);
    }

    /**
     * The player's own duel ended with a result (DuelScene.afterGameEnd, right after recordStatistics, on the GL thread);
     * {@code counts} is {@link #counts}.
     * <p>
     * The one place the rule lives. The alternatives the user weighed swap only this method's body: "every duel" is
     * {@code winLook(p)} whatever the result; "every 2-4 days" reads the day instead of the duel (stretch k lasts
     * 2 + mix(seed, k) % 3 days, its look mix(seed, k, salt)) and needs no saved field.
     */
    public static void onDuelEnded(boolean counts, boolean won) {
        AdventurePlayer p = AdventurePlayer.current();
        if (!counts || p == null || !EditionProgression.isAnySetRace(p.getHeroRace()))
            return;
        int before = p.getDoppelLook();
        int after = won ? winLook(p) : -1;
        p.setDoppelLook(after);
        System.out.println("[TFR-Doppel] " + (won ? "win" : "loss") + ", duel " + duelCount(p) + ", seed " + worldSeed()
                + ": " + lookName(p, before) + " -> " + lookName(p, after));
    }

    /** A won duel's look: any race but the true form and the look worn now, picked by the world seed and the duel count
     *  (the win/loss record, this duel included), so replaying a duel after a reload gives the same look. */
    static int winLook(AdventurePlayer p) {
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; HeroListData.getRawRaceName(i) != null; i++)
            if (i != p.getHeroRace() && i != p.getDoppelLook())
                pool.add(i);
        if (pool.isEmpty())
            return -1;
        long h = ResourceSpawns.mixSeed(ResourceSpawns.mixSeed(worldSeed() + duelCount(p)) ^ LOOK_SALT);
        return pool.get((int) Math.floorMod(h, (long) pool.size()));
    }

    private static int duelCount(AdventurePlayer p) {
        return p.getStatistic().totalWins() + p.getStatistic().totalLoss();
    }

    private static long worldSeed() {
        WorldSave save = WorldSave.getCurrentSave();
        return save == null || save.getWorld() == null ? 0L : save.getWorld().getSeed();
    }

    private static String lookName(AdventurePlayer p, int look) {
        return look < 0 ? "true form" : HeroListData.instance().getRaces().get(look);
    }

    /** The Status page's race line: "Doppelganger (as Kor)" or "Doppelganger (true form)"; null for other races. */
    public static String describe(AdventurePlayer p) {
        if (p == null || !EditionProgression.isAnySetRace(p.getHeroRace()))
            return null;
        int look = p.getDoppelLook();
        return HeroListData.instance().getRaces().get(p.getHeroRace()) + (look < 0 ? " (true form)" : " (as " + lookName(p, look) + ")");
    }
}
