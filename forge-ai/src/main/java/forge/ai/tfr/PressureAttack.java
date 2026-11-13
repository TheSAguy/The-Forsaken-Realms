package forge.ai.tfr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.ai.ComputerUtilCombat;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.player.Player;

/**
 * TFR round 1021, rule {@value #ID} (the board stall of the eldrazi_scion against crocodile bench games): Forge declared
 * no attack, but the AI has more attackers than the opponent has blockers, and chip damage at that rate kills the
 * opponent within a few turns while the swing back cannot punish it.
 *
 * <p>Why Forge declines (diagnosis in {@code notes/r1021/report.md}): {@code AiAttackController.shouldAttack} judges
 * each attacker alone, and below aggression 5 ("all out") an attacker that one blocker can kill and that kills nothing
 * never attacks - every token on a token board. Aggression 5 (everything attacks) needs the attritional count to kill
 * the opponent outright, every blocked attacker dying each round and no blocker ever dying, and the AI to be ahead on
 * the life-to-damage ratio; a token side facing bigger creatures has neither, and it would leave no blocker home anyway.
 * There is no level between: no "send the surplus, keep the chump blockers home". Flat Monte Carlo, which looks one
 * turn ahead, agrees with holding: the first round of chip damage only loses creatures.</p>
 *
 * <p>The count (one opponent, no tricks):</p>
 * <ul>
 *   <li>S is every creature that can attack with power over 0, minus the h weakest kept home;</li>
 *   <li>the damage that gets through is S's damage past the opponent's best blocks (each untapped creature blocks one
 *   attacker, or more if it can; the heaviest blockable set is blocked), minus the life every lifelink blocker gains;</li>
 *   <li>reach: that net chip, repeated, kills the opponent within {@code rounds} turns (params, default 3);</li>
 *   <li>the swing back: every opposing creature attacks next turn into what stayed home (the creatures not sent, and S's
 *   vigilance creatures), each block a chump on the heaviest attacker; the AI's life after it stays at
 *   {@code minLifeAfter} or more (default 1) and its poison short of lethal;</li>
 *   <li>the race: that swing back deals at most the net chip more than the swing back after no attack would;</li>
 *   <li>the smallest h that passes all of them is sent; none passes, no change.</li>
 * </ul>
 * It acts only when Forge declared no attack ({@code onlyWhenNoAttack}, default true), never against a defender with an
 * attack or block trigger (puzzle P4), never in game copies. Puzzle: {@code P6_eldrazi_pressure}.
 */
final class PressureAttack {
    static final String ID = "pressure-attack";

    private PressureAttack() {
    }

    /** One plan: what is sent, and the count behind it. */
    static final class Plan {
        final List<Card> send;
        final int keptHome;
        final int through;
        final int lifelink;
        final int turnsToKill;
        final int swingBack;
        final int swingBackIfHeld;

        Plan(final List<Card> send, final int keptHome, final int through, final int lifelink, final int turnsToKill,
             final int swingBack, final int swingBackIfHeld) {
            this.send = send;
            this.keptHome = keptHome;
            this.through = through;
            this.lifelink = lifelink;
            this.turnsToKill = turnsToKill;
            this.swingBack = swingBack;
            this.swingBackIfHeld = swingBackIfHeld;
        }
    }

    static void review(final Player ai, final Combat combat) {
        if (combat == null || !TfrAiRules.isOn(ID, ai)) {
            return;
        }
        final TfrAiRules.Entry entry = TfrAiRules.entry(ID);
        final boolean onlyWhenNoAttack = entry == null || entry.boolParam("onlyWhenNoAttack", true);
        if (onlyWhenNoAttack && !combat.getAttackers().isEmpty()) {
            return;
        }
        final Player d = soleOpponent(ai);
        if (d == null || d.cantLose() || !d.canLoseLife() || d.cantLoseForZeroOrLessLife()) {
            return;
        }
        for (final Card c : combat.getAttackers()) {
            if (!ai.equals(c.getController())) {
                return;
            }
        }
        final int rounds = Math.max(1, entry == null ? 3 : entry.intParam("rounds", 3));
        final int minLifeAfter = Math.max(1, entry == null ? 1 : entry.intParam("minLifeAfter", 1));
        final Plan plan = plan(ai, d, combat, rounds, minLifeAfter);
        if (plan == null) {
            return;
        }
        final Game game = ai.getGame();
        final String head = "turn " + game.getPhaseHandler().getTurn() + " " + ai.getName() + " attacks with "
                + combat.getAttackers().size();
        final Card trap = AlphaStrikeCount.combatTrigger(d);
        if (trap != null) {
            System.out.println(TfrAiRules.TAG + " " + ID + " stays out: " + head + ", " + trap.getName()
                    + " has a combat trigger that acts before damage");
            return;
        }
        final String after = "pressure with " + plan.send.size() + " (" + power(plan.send) + " power, " + plan.keptHome
                + " kept home): " + plan.through + " through past " + blockers(d).size() + " blockers"
                + (plan.lifelink > 0 ? " (-" + plan.lifelink + " lifelink)" : "") + ", kills in " + plan.turnsToKill
                + " at this rate; opponent life " + d.getLife() + ", own life " + ai.getLife() + ", swing back "
                + plan.swingBack + " (" + plan.swingBackIfHeld + " if held)";
        if (entry != null && entry.boolParam("shadow", false)) {
            TfrAiRules.shadow(ID, head, after);
            return;
        }
        final Map<Card, GameEntity> before = new LinkedHashMap<>();
        for (final Card c : combat.getAttackers()) {
            before.put(c, combat.getDefenderByAttacker(c));
        }
        for (final Card c : plan.send) {
            if (!combat.isAttacking(c)) {
                combat.addAttacker(c, d);
            }
        }
        if (!CombatUtil.validateAttackers(combat)) {
            combat.clearAttackers();
            for (final Map.Entry<Card, GameEntity> e : before.entrySet()) {
                combat.addAttacker(e.getKey(), e.getValue());
            }
            System.out.println(TfrAiRules.TAG + " " + ID + " not applied: the pressure attack was not a legal declaration");
            return;
        }
        TfrAiRules.fired(ID, head, after);
    }

    /** The plan with the fewest creatures kept home that passes the count, or null. */
    static Plan plan(final Player ai, final Player d, final Combat combat, final int rounds, final int minLifeAfter) {
        final Game game = ai.getGame();
        final List<Card> can = new ArrayList<>();
        for (final Card c : ai.getCreaturesInPlay()) {
            if (!combat.isAttacking(c) && CombatUtil.canAttack(c, d) && CombatUtil.getAttackCost(game, c, d) == null
                    && ComputerUtilCombat.damageIfUnblocked(c, d, combat, true) > 0) {
                can.add(c);
            }
        }
        final List<Card> bl = blockers(d);
        if (can.size() <= capacity(bl, can.size())) {
            return null; // no surplus: every attacker can be blocked
        }
        // strongest first, so the weakest are kept home first
        can.sort((x, y) -> Integer.compare(ComputerUtilCombat.damageIfUnblocked(y, d, combat, true),
                ComputerUtilCombat.damageIfUnblocked(x, d, combat, true)));
        int lifelink = 0;
        for (final Card b : bl) {
            if (b.hasKeyword(Keyword.LIFELINK) || canGiveItselfLifelink(b)) {
                lifelink += Math.max(0, b.getNetCombatDamage()); // counted as if every lifelink creature blocks
            }
        }
        final List<Card> theirs = new ArrayList<>();
        for (final Card c : d.getCreaturesInPlay()) {
            if (!c.hasKeyword(Keyword.DEFENDER) && c.getNetCombatDamage() > 0) {
                theirs.add(c);
            }
        }
        final int[] held = swingBack(ai, theirs, home(ai, combat, List.of()));
        for (int h = 0; h < can.size(); h++) {
            final List<Card> send = new ArrayList<>(can.subList(0, can.size() - h));
            if (send.size() <= capacity(bl, send.size())) {
                return null; // keeping more home only shrinks the surplus
            }
            final int[] dmg = new int[send.size()];
            for (int i = 0; i < dmg.length; i++) {
                dmg[i] = Math.max(0, ComputerUtilCombat.damageIfUnblocked(send.get(i), d, combat, true));
            }
            final boolean[] blocked = blockedSet(dmg, send, bl, new int[dmg.length]);
            int through = 0;
            for (int i = 0; i < dmg.length; i++) {
                if (!blocked[i]) {
                    through += dmg[i];
                }
            }
            final int net = through - lifelink;
            if (net <= 0) {
                return null; // keeping more home only lowers the damage, against the same lifelink
            }
            final int turns = (d.getLife() + net - 1) / net;
            if (turns > rounds) {
                return null; // keeping more home only lowers the damage
            }
            final int[] back = swingBack(ai, theirs, home(ai, combat, send));
            final boolean lifeOk = !ai.canLoseLife() || ai.cantLoseForZeroOrLessLife() || ai.getLife() - back[0] >= minLifeAfter;
            final boolean poisonOk = !ai.canReceiveCounters(CounterEnumType.POISON)
                    || ai.getPoisonCounters() + back[1] < ai.getGame().getRules().getPoisonCountersToLose();
            final boolean raceOk = back[0] - held[0] <= net && back[1] <= held[1];
            if (turns == 1 || lifeOk && poisonOk && raceOk) {
                // a kill this turn needs no swing-back check: there is no swing back
                return new Plan(send, h, through, lifelink, turns, back[0], held[0]);
            }
        }
        return null;
    }

    /**
     * A creature with an activated ability that gives itself lifelink (Stonefare Crocodile's "{2}{B}: gains lifelink";
     * P6 found it: the crocodiles paid it while blocking and the chip damage became a life gain). Counted whatever the
     * mana, so the count errs towards staying out.
     */
    static boolean canGiveItselfLifelink(final Card c) {
        for (final forge.game.spellability.SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isActivatedAbility() && sa.getApi() == forge.game.ability.ApiType.Pump && sa.hasParam("KW")
                    && sa.getParam("KW").contains("Lifelink")
                    && (!sa.hasParam("Defined") || "Self".equals(sa.getParam("Defined")))
                    && !sa.usesTargeting()) {
                return true;
            }
        }
        return false;
    }

    /** What stays home to block the swing back: untapped creatures not sent (vigilance creatures block either way). */
    private static List<Card> home(final Player ai, final Combat combat, final List<Card> send) {
        final List<Card> home = new ArrayList<>();
        for (final Card c : ai.getCreaturesInPlay()) {
            final boolean sent = (send.contains(c) || combat.isAttacking(c)) && !c.hasKeyword(Keyword.VIGILANCE);
            if (!sent && CombatUtil.canBlock(c, true)) {
                home.add(c);
            }
        }
        return home;
    }

    /** {damage, poison} of an all-out attack by {@code theirs} into {@code home}, each block a chump on the heaviest. */
    static int[] swingBack(final Player ai, final List<Card> theirs, final List<Card> home) {
        final int n = theirs.size();
        final int[] dmg = new int[n];
        final int[] psn = new int[n];
        final int[] weight = new int[n];
        for (int i = 0; i < n; i++) {
            final Card a = theirs.get(i);
            final int base = Math.max(0, a.getNetCombatDamage()) * (a.hasDoubleStrike() ? 2 : 1);
            if (a.hasKeyword(Keyword.INFECT)) {
                psn[i] = base;
            } else {
                dmg[i] = base;
                if (a.hasKeyword(Keyword.TOXIC) || a.hasKeyword(Keyword.POISONOUS)) {
                    psn[i] = 1;
                }
            }
            weight[i] = dmg[i] + 10 * psn[i];
        }
        final boolean[] blocked = blockedSet(weight, theirs, home, new int[n]);
        int d = 0;
        int p = 0;
        for (int i = 0; i < n; i++) {
            if (!blocked[i]) {
                d += dmg[i];
                p += psn[i];
            }
        }
        return new int[] {d, p};
    }

    static List<Card> blockers(final Player d) {
        final List<Card> out = new ArrayList<>();
        for (final Card b : d.getCreaturesInPlay()) {
            if (CombatUtil.canBlock(b)) {
                out.add(b);
            }
        }
        return out;
    }

    /** How many attackers these blockers can block at most (an upper bound: legality per attacker aside). */
    private static int capacity(final List<Card> bl, final int n) {
        int cap = 0;
        for (final Card b : bl) {
            cap += b.canBlockAny() ? n : 1 + Math.max(0, b.canBlockAdditional());
        }
        return cap;
    }

    /**
     * The heaviest set of attackers the blockers can block (a maximum-weight matching; blockable sets form a
     * transversal matroid, so heaviest-first with augmenting paths is exact). {@code usedBy[i]} gets the index of the
     * blocker that takes attacker i.
     */
    static boolean[] blockedSet(final int[] w, final List<Card> attackers, final List<Card> bl, final int[] usedBy) {
        final int n = w.length;
        final int m = bl.size();
        final boolean[][] can = new boolean[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                can[i][j] = CombatUtil.canBlock(attackers.get(i), bl.get(j), true);
            }
        }
        final int[][] slot = new int[m][];
        for (int j = 0; j < m; j++) {
            final Card b = bl.get(j);
            slot[j] = new int[Math.min(n, b.canBlockAny() ? n : 1 + Math.max(0, b.canBlockAdditional()))];
            Arrays.fill(slot[j], -1);
        }
        final Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (x, y) -> Integer.compare(w[y], w[x]));
        for (final int a : order) {
            if (w[a] <= 0) {
                break;
            }
            augment(a, can, slot, new boolean[m]);
        }
        final boolean[] blocked = new boolean[n];
        Arrays.fill(usedBy, -1);
        for (int j = 0; j < m; j++) {
            for (final int a : slot[j]) {
                if (a >= 0) {
                    blocked[a] = true;
                    usedBy[a] = j;
                }
            }
        }
        return blocked;
    }

    private static boolean augment(final int a, final boolean[][] can, final int[][] slot, final boolean[] seen) {
        for (int j = 0; j < slot.length; j++) {
            if (!can[a][j] || seen[j]) {
                continue;
            }
            seen[j] = true;
            for (int k = 0; k < slot[j].length; k++) {
                if (slot[j][k] < 0) {
                    slot[j][k] = a;
                    return true;
                }
            }
            for (int k = 0; k < slot[j].length; k++) {
                if (augment(slot[j][k], can, slot, seen)) {
                    slot[j][k] = a;
                    return true;
                }
            }
        }
        return false;
    }

    private static Player soleOpponent(final Player ai) {
        Player only = null;
        for (final Player o : ai.getOpponents()) {
            if (o.isInGame()) {
                if (only != null) {
                    return null;
                }
                only = o;
            }
        }
        return only;
    }

    private static int power(final List<Card> cs) {
        int p = 0;
        for (final Card c : cs) {
            p += Math.max(0, c.getNetCombatDamage());
        }
        return p;
    }
}
