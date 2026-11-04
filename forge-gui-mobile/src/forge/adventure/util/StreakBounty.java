package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.EnemyData;
import forge.adventure.data.TuningData;
import forge.adventure.player.AdventurePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Round 943: the Bounty (a Reddit player: "extra rewards if you take those enemies with these advantages"; the user: "It's
 * harder but also more rewarding. Will add."; numbers after the inflation check, notes/r943-bounty-inflation-check.md).
 * A won duel against an enemy that started with bonus cards pays more gold, shards, wood and stone, sized by what it
 * brought:
 * <ul>
 *   <li>a Notoriety Wall by size (0/1, 1/2, 2/4, 3/6 = TuningData.bountyPointsWall), each 45+/50+ flying Wall
 *   (bountyPointsBonusWall), the Notoriety deck card where its gate is met (bountyPointsDeckCard - the Wrath's own card
 *   below the gate is not one), each streak Wastes (bountyPointsStreakCard) and each win-ratio Clue/Treasure
 *   (bountyPointsHelpToken). The Archmage's own Wastes (round 390) is not a streak card and scores nothing.</li>
 *   <li>Bounty = points x the difficulty's share per point (bountySharePerPoint{D}), capped (bountyCap), in whole
 *   percent. A fight against several enemies pays its largest single seat's.</li>
 *   <li>The Temple's duels (WrathHunt's): no Arena, Inn tournament, guard's fight, fixed-deck enemy or Deck Tester.
 *   Disable Notoriety removes the Walls and the deck card, so their points; the streak and the help tokens still score.</li>
 * </ul>
 * DuelScene scores each seat as it is set up (scoreSeat, "[TFR-Bounty] marked"), marks the win when the duel ends
 * (onDuelEnded), and the loot sites raise the loot before it shows (applyBounty: the old -> new amounts and a HUD notice).
 * The Alt plates (TfrPlates) and the Realm Journal show it ahead (percentFor). No save state: a mark lives one duel.
 */
public final class StreakBounty {
    private StreakBounty() {
    }

    /** The best seat of the duel being set up: its percent, its name and what it brought. */
    private static int bestPercent, bestPoints;
    private static String bestWho, bestParts;
    /** The won duel whose loot is still to come. */
    private static EnemySprite pending;
    private static int pendingPercent;
    private static String pendingWho, pendingParts;

    private static TuningData tuning() {
        return Config.instance().getTuningData();
    }

    private static String difficulty() {
        AdventurePlayer p = Current.player();
        return p == null || p.getDifficulty() == null || p.getDifficulty().name == null ? "" : p.getDifficulty().name;
    }

    /** One seat's points and the words for them ("reach 3/6 + 2 Wastes + Treasure"). */
    public static final class Score {
        public int points;
        public final List<String> parts = new ArrayList<>();

        void add(int pts, String what) {
            if (pts <= 0)
                return;
            points += pts;
            parts.add(what);
        }

        public String describe() {
            return parts.isEmpty() ? "nothing" : String.join(" + ", parts);
        }
    }

    /** The points a Wall token script is worth: a bonus Wall, else its size in its rank's list. */
    static int wallPoints(TuningData t, String script) {
        if (script == null)
            return 0;
        if (t.notorietyBonusWalls != null)
            for (String b : t.notorietyBonusWalls)
                if (script.equals(b))
                    return Math.max(0, t.bountyPointsBonusWall);
        int[] table = t.bountyPointsWall;
        if (table == null || table.length == 0)
            return 0;
        int per = Math.max(1, t.notorietyLevelsPerWall);
        for (int rank = 0; rank < 4; rank++) {
            String[] list = t.notorietyWallsForRank(rank);
            if (list == null)
                continue;
            for (int i = 0; i < list.length; i++)
                if (script.equals(list[i]))
                    return Math.max(0, table[Math.min(i % per, table.length - 1)]);
        }
        return 0;
    }

    /** A Wall's words in the log and the plate's tooltip: "reach 3/6", "flying 3/6 (45+)". */
    private static String wallWords(TuningData t, String script) {
        boolean bonus = false;
        if (t.notorietyBonusWalls != null)
            for (int i = 0; i < t.notorietyBonusWalls.length; i++)
                if (script.equals(t.notorietyBonusWalls[i])) {
                    bonus = true;
                    break;
                }
        String kind = script.contains("flying") ? "flying " : script.contains("reach") ? "reach " : "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("_(\\d+)_(\\d+)$").matcher(script);
        String size = m.find() ? m.group(1) + "/" + m.group(2) : script;
        return kind + size + (bonus ? " (bonus)" : "");
    }

    /** What a seat that starts with these cards is worth. walls: notorietyWallsFor; streakCards: winStreakCards'. */
    public static Score score(List<String> walls, boolean deckCard, int streakCards, int helpTokens) {
        Score s = new Score();
        TuningData t = tuning();
        if (t == null)
            return s;
        if (walls != null)
            for (String w : walls)
                s.add(wallPoints(t, w), wallWords(t, w));
        if (deckCard)
            s.add(Math.max(0, t.bountyPointsDeckCard), "deck card");
        if (streakCards > 0)
            s.add(streakCards * Math.max(0, t.bountyPointsStreakCard), (streakCards > 1 ? streakCards + " " : "") + "Wastes");
        if (helpTokens > 0)
            s.add(helpTokens * Math.max(0, t.bountyPointsHelpToken), helpTokens > 1 ? helpTokens + " help tokens" : "help token");
        return s;
    }

    /** The share per point on this save's difficulty (0 = off). */
    public static float share() {
        TuningData t = tuning();
        return t == null ? 0f : t.bountySharePerPointFor(difficulty());
    }

    /** points x share, capped, in whole percent (the amount the loot is raised by, and the number the player reads). */
    public static int percent(int points) {
        TuningData t = tuning();
        float share = share();
        if (t == null || points <= 0 || share <= 0f)
            return 0;
        float raw = points * share * 100f;
        int pct = (int) Math.floor(raw + 0.5f + 1e-3f);
        if (t.bountyCap > 0f)
            pct = Math.min(pct, (int) Math.floor(t.bountyCap * 100f + 1e-3f));
        return Math.max(0, pct);
    }

    private static String pointWords(int points) {
        return points + (points == 1 ? " point" : " points");
    }

    /** The share in the log's words: "1.5%". */
    private static String shareWords() {
        float s = share() * 100f;
        return (Math.abs(s - Math.round(s)) < 1e-3f ? String.valueOf(Math.round(s)) : String.format(Locale.ROOT, "%.1f", s)) + "%";
    }

    private static String capWords() {
        TuningData t = tuning();
        return t == null || t.bountyCap <= 0f ? "no cap" : "cap " + Math.round(t.bountyCap * 100f) + "%";
    }

    /**
     * Is the Notoriety deck card's gate met right now (NotorietyDeckCard.gate)? The Wrath's own card, given below the
     * gate, is the Wrath's and not Notoriety's - it scores only here.
     */
    public static boolean deckCardGateMet() {
        TuningData t = tuning();
        AdventurePlayer p = Current.player();
        if (t == null || p == null || AdventurePlayer.notorietyDisabled())
            return false;
        NotorietyDeckCard.Gate g = NotorietyDeckCard.gate(t, p);
        return g.wins > 0 && p.notorietyStreak() >= g.wins;
    }

    /** A seat's score as its next duel would set it up (the plates, the Journal): same rules as DuelScene's seats. */
    public static Score scoreFor(EnemyData seat) {
        AdventurePlayer p = Current.player();
        if (seat == null || p == null)
            return new Score();
        return score(p.notorietyWallsFor(seat), deckCardGateMet(), p.winStreakCards(seat.getName()).length,
                seat.fixedDeck == null ? SpawnTierWeighting.winRatioEnemyTokens().length : 0);
    }

    /**
     * The plates and the Journal: the bounty a win over this enemy would pay now - its largest seat's, 0 when none, or for
     * a fixed-deck enemy (no bounty there).
     */
    public static int percentFor(EnemyData head) {
        if (head == null || head.fixedDeck != null || share() <= 0f)
            return 0;
        int best = 0;
        int guard = 0;
        for (EnemyData seat = head; seat != null && guard < 8; seat = seat.nextEnemy, guard++)
            best = Math.max(best, percent(scoreFor(seat).points));
        return best;
    }

    // ------------------------------------------------------------------------------------------------- the duel

    /** DuelScene.initDuels, before the seats: forget the last set-up's best seat. */
    public static void beginDuel() {
        bestPercent = bestPoints = 0;
        bestWho = bestParts = null;
    }

    /**
     * DuelScene, each enemy seat as it is set up, in the duels a bounty can be paid for (not the Arena, an Inn match, a
     * guard's fight, a fixed-deck enemy or Deck Tester). Logs "[TFR-Bounty] marked" for a seat worth anything.
     */
    public static void scoreSeat(EnemyData seat, List<String> walls, boolean deckCard, int streakCards, int helpTokens) {
        if (seat == null || share() <= 0f)
            return;
        Score s = score(walls, deckCard, streakCards, helpTokens);
        if (s.points <= 0)
            return;
        int pct = percent(s.points);
        String who = seat.getName() + " (" + EnemyData.tierDisplayName(seat.tier) + ", " + difficulty() + ")";
        System.out.println("[TFR-Bounty] marked: " + who + ": " + s.describe() + " = " + pointWords(s.points) + " x "
                + shareWords() + " = +" + pct + "% (" + capWords() + ")"
                + (pct > bestPercent || bestWho == null ? "" : " - the best seat's bounty stands (+" + bestPercent + "%)"));
        if (pct > bestPercent || bestWho == null) {
            bestPercent = pct;
            bestPoints = s.points;
            bestWho = who;
            bestParts = s.describe();
        }
    }

    /**
     * DuelScene.afterGameEnd: {@code counted} is the enemy when a bounty duel was won (WrathHunt's duels), else null -
     * which also drops a mark no loot screen used.
     */
    public static void onDuelEnded(EnemySprite counted) {
        if (pending != null && pending != counted)
            System.out.println("[TFR-Bounty] dropped - the bounty on " + pending.getName() + " reached no loot screen");
        pending = null;
        if (bestWho == null || bestPercent <= 0) {
            beginDuel();
            return;
        }
        if (counted == null || counted.getData() == null)
            System.out.println("[TFR-Bounty] " + bestWho + ": +" + bestPercent + "% not paid - the duel was not won");
        else {
            pending = counted;
            pendingPercent = bestPercent;
            pendingWho = bestWho;
            pendingParts = bestParts + " = " + pointWords(bestPoints) + " x " + shareWords() + " = +" + bestPercent + "% ("
                    + capWords() + ")";
        }
        beginDuel();
    }

    /** WorldStage.setWinner / MapStage.getReward: the won duel's loot, before it shows - raises its gold and resources. */
    public static void applyBounty(Array<Reward> loot, EnemySprite mob) {
        if (pending == null)
            return;
        if (pending != mob || loot == null) {
            System.out.println("[TFR-Bounty] dropped - the loot was " + (mob == null ? "nobody's" : mob.getName() + "'s")
                    + ", the bounty " + pending.getName() + "'s");
            pending = null;
            return;
        }
        pending = null;
        float share = pendingPercent / 100f;
        StringBuilder changes = new StringBuilder();
        int[] before = new int[4], after = new int[4];
        Reward.Type[] types = {Reward.Type.Gold, Reward.Type.Shards, Reward.Type.Wood, Reward.Type.Stone};
        for (int i = 0; i < loot.size; i++) {
            Reward r = loot.get(i);
            if (r == null || r.getCount() <= 0)
                continue;
            for (int k = 0; k < types.length; k++) {
                if (r.getType() != types[k])
                    continue;
                int raised = raised(r.getCount(), share);
                before[k] += r.getCount();
                after[k] += raised;
                if (k == 0)
                    loot.set(i, r.isCardFallbackGold() ? Reward.cardFallbackGold(raised) : new Reward(raised));
                else
                    loot.set(i, new Reward(types[k], raised));
            }
        }
        for (int k = 0; k < types.length; k++)
            if (before[k] > 0)
                changes.append(changes.length() == 0 ? "" : ", ").append(types[k].name().toLowerCase(Locale.ROOT))
                        .append(' ').append(before[k]).append(" -> ").append(after[k]);
        System.out.println("[TFR-Bounty] " + pendingWho + ": " + pendingParts + " - "
                + (changes.length() == 0 ? "no gold or resources in this loot" : changes));
        if (changes.length() > 0)
            // icons only, no color tags: GameHUD draws the coin in its own colors on the paper (round 367's rule)
            forge.adventure.stage.GameHUD.getInstance().addNotification("Bounty! " + EnemyData.getDisplayName(mob.getName())
                    + " came armed: +" + pendingPercent + "% [+Gold] and resources.");
    }

    /** count x (1 + share), rounded up - WrathHunt.raised's rule, so a small amount gains one at least. */
    static int raised(int count, float share) {
        return (int) Math.ceil(count * (1f + share) - 0.0001f);
    }

    /** WorldStage.clearCache: a load or a new game starts with no mark. */
    public static void resetSessionState() {
        beginDuel();
        pending = null;
    }
}
