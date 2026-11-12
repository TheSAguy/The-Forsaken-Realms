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
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.player.Player;

/**
 * TFR round 1018, rule {@value #ID} (audit pattern 3, the big-board stall): after Forge declared its attack, if
 * attacking the opponent with every creature that can attack gets at least the opponent's life (or the poison they
 * have left) past every possible block, attack with everything.
 *
 * <p>The count is conservative and ignores tricks:</p>
 * <ul>
 *   <li>the blocks are the opponent's best: a maximum matching that blocks the most damage (each untapped creature that
 *   can block takes one attacker it can legally block, or more if it can block more), not a greedy guess;</li>
 *   <li>a blocked attacker deals the player nothing (trample is not counted), and menace counts as blockable by one;</li>
 *   <li>damage is {@code ComputerUtilCombat.damageIfUnblocked} without the attackers' own pump abilities, so prevention
 *   and damage doublers on the board count;</li>
 *   <li>an opponent's blocker that already has lifelink adds its damage to the life to beat;</li>
 *   <li>a creature with an attack cost (Propaganda and the like) is left out; one opponent only; never in game copies.</li>
 * </ul>
 * Puzzle: {@code res/ai/tfr-puzzles/P3_eldrazi_stall.pzl} (audit run max-default g021, turn 50).
 */
final class AlphaStrikeCount {
    static final String ID = "alpha-strike-count";

    private AlphaStrikeCount() {
    }

    static void review(final Player attacker, final Combat combat) {
        if (combat == null || !TfrAiRules.isOn(ID, attacker)) {
            return;
        }
        final Game game = attacker.getGame();
        final List<Player> opps = new ArrayList<>();
        for (final Player o : attacker.getOpponents()) {
            if (o.isInGame()) {
                opps.add(o);
            }
        }
        if (opps.size() != 1) {
            return; // multiplayer: whom to swing at is a different decision
        }
        final Player d = opps.get(0);
        if (!d.canLoseLife() && !d.canReceiveCounters(forge.game.card.CounterEnumType.POISON) || d.cantLose()) {
            return;
        }
        for (final Card c : combat.getAttackers()) {
            if (!attacker.equals(c.getController())) {
                return; // a teammate's attack is not ours to rewrite
            }
        }

        final List<Card> all = new ArrayList<>();
        for (final Card c : attacker.getCreaturesInPlay()) {
            if (CombatUtil.canAttack(c, d) && CombatUtil.getAttackCost(game, c, d) == null) {
                all.add(c);
            }
        }
        final List<Card> blockers = new ArrayList<>();
        int lifelink = 0;
        for (final Card b : d.getCreaturesInPlay()) {
            if (CombatUtil.canBlock(b)) {
                blockers.add(b);
                if (b.hasKeyword(Keyword.LIFELINK)) {
                    lifelink += Math.max(0, b.getNetCombatDamage());
                }
            }
        }
        final int life = d.canLoseLife() && !d.cantLoseForZeroOrLessLife() ? d.getLife() + lifelink : Integer.MAX_VALUE;
        final int poisonLeft = d.canReceiveCounters(forge.game.card.CounterEnumType.POISON)
                ? game.getRules().getPoisonCountersToLose() - d.getPoisonCounters() : Integer.MAX_VALUE;

        final List<Card> current = new ArrayList<>();
        for (final Card c : combat.getAttackers()) {
            if (d.equals(combat.getDefenderByAttacker(c))) {
                current.add(c);
            }
        }
        final Count now = count(current, blockers, d, combat);
        if (now.lethal(life, poisonLeft)) {
            return; // Forge's attack already gets there
        }
        final Count allIn = count(all, blockers, d, combat);
        if (!allIn.lethal(life, poisonLeft)) {
            return;
        }
        // The count only sees combat damage. A defender's attack or block trigger acts BEFORE it (bench g043: Giggling
        // Skitterspike's block trigger dealt 6 to an attacker at 6 life), so the rule stays out whenever one is there.
        final Card trap = combatTrigger(d);
        if (trap != null) {
            System.out.println(TfrAiRules.TAG + " " + ID + " stays out: turn " + game.getPhaseHandler().getTurn() + " "
                    + attacker.getName() + " all-in would be lethal by the count, but " + trap.getName()
                    + " has a combat trigger that acts before damage");
            return;
        }

        final TfrAiRules.Entry entry = TfrAiRules.entry(ID);
        if (entry != null && entry.boolParam("shadow", false)) {
            // shadow mode (rules file): log what the rule would change, change nothing - evidence for log reviews
            TfrAiRules.shadow(ID,
                    "turn " + game.getPhaseHandler().getTurn() + " " + attacker.getName() + " attacks with " + current.size()
                            + " (gets " + now.damage + " dmg/" + now.poison + " poison through)",
                    "all " + all.size() + " attackers: at least " + allIn.damage + " dmg/" + allIn.poison + " poison past "
                            + blockers.size() + " blockers, opponent life " + d.getLife()
                            + (lifelink > 0 ? " (+" + lifelink + " lifelink)" : "") + ", poison " + d.getPoisonCounters());
            return;
        }
        final Map<Card, GameEntity> before = new LinkedHashMap<>();
        for (final Card c : combat.getAttackers()) {
            before.put(c, combat.getDefenderByAttacker(c));
        }
        for (final Card c : all) {
            if (combat.isAttacking(c)) {
                if (d.equals(combat.getDefenderByAttacker(c))) {
                    continue;
                }
                combat.removeFromCombat(c); // it was swinging at a planeswalker or a battle: the player is the target now
            }
            combat.addAttacker(c, d);
        }
        if (!CombatUtil.validateAttackers(combat)) {
            combat.clearAttackers();
            for (final Map.Entry<Card, GameEntity> e : before.entrySet()) {
                combat.addAttacker(e.getKey(), e.getValue());
            }
            System.out.println(TfrAiRules.TAG + " " + ID + " not applied: the all-out attack was not a legal declaration");
            return;
        }
        TfrAiRules.fired(ID,
                "turn " + game.getPhaseHandler().getTurn() + " " + attacker.getName() + " attacks with " + before.size()
                        + " (gets " + now.damage + " dmg/" + now.poison + " poison through)",
                "all " + combat.getAttackers().size() + " attackers: at least " + allIn.damage + " dmg/" + allIn.poison
                        + " poison past " + blockers.size() + " blockers, opponent life " + d.getLife()
                        + (lifelink > 0 ? " (+" + lifelink + " lifelink)" : "") + ", poison " + d.getPoisonCounters());
    }

    /** What gets through the opponent's best blocks: damage and poison, each counted against its own best blocks. */
    static final class Count {
        final int damage;
        final int poison;

        Count(final int damage, final int poison) {
            this.damage = damage;
            this.poison = poison;
        }

        boolean lethal(final int life, final int poisonLeft) {
            return damage >= life || poison >= poisonLeft;
        }
    }

    /** Combat trigger modes that resolve before combat damage. */
    private static final java.util.EnumSet<forge.game.trigger.TriggerType> BEFORE_DAMAGE = java.util.EnumSet.of(
            forge.game.trigger.TriggerType.Attacks, forge.game.trigger.TriggerType.AttackersDeclared,
            forge.game.trigger.TriggerType.AttackersDeclaredOneTarget, forge.game.trigger.TriggerType.AttackerBlocked,
            forge.game.trigger.TriggerType.AttackerBlockedOnce, forge.game.trigger.TriggerType.AttackerBlockedByCreature,
            forge.game.trigger.TriggerType.AttackerUnblocked, forge.game.trigger.TriggerType.AttackerUnblockedOnce,
            forge.game.trigger.TriggerType.Blocks, forge.game.trigger.TriggerType.BlockersDeclared);

    /** A permanent of the defender's with an attack or block trigger, or null. */
    static Card combatTrigger(final Player d) {
        for (final Card c : d.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
            for (final forge.game.trigger.Trigger t : c.getTriggers()) {
                if (BEFORE_DAMAGE.contains(t.getMode())) {
                    return c;
                }
            }
        }
        return null;
    }

    static Count count(final List<Card> attackers, final List<Card> blockers, final Player d, final Combat combat) {
        final int n = attackers.size();
        final int[] dmg = new int[n];
        final int[] psn = new int[n];
        for (int i = 0; i < n; i++) {
            final Card a = attackers.get(i);
            dmg[i] = Math.max(0, ComputerUtilCombat.damageIfUnblocked(a, d, combat, true));
            psn[i] = Math.max(0, ComputerUtilCombat.poisonIfUnblocked(a, d));
        }
        final boolean[][] can = new boolean[n][blockers.size()];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < blockers.size(); j++) {
                can[i][j] = CombatUtil.canBlock(attackers.get(i), blockers.get(j));
            }
        }
        final int[] cap = new int[blockers.size()];
        for (int j = 0; j < cap.length; j++) {
            final Card b = blockers.get(j);
            cap[j] = b.canBlockAny() ? n : 1 + Math.max(0, b.canBlockAdditional());
        }
        return new Count(through(dmg, can, cap), through(psn, can, cap));
    }

    /**
     * The weight that gets past the blocks that stop the most weight. Each blocker j stops up to cap[j] attackers it
     * can block. The blockable sets form a transversal matroid, so taking attackers heaviest first and keeping each one
     * that still fits a matching (an augmenting path) gives the heaviest blockable set exactly.
     */
    static int through(final int[] w, final boolean[][] can, final int[] cap) {
        final int n = w.length;
        final int m = cap.length;
        final Integer[] order = new Integer[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            order[i] = i;
            total += w[i];
        }
        Arrays.sort(order, (x, y) -> Integer.compare(w[y], w[x]));
        // slots: blocker j has cap[j] slots; slotOwner[j][k] = attacker in that slot or -1
        final int[][] slot = new int[m][];
        for (int j = 0; j < m; j++) {
            slot[j] = new int[Math.min(cap[j], n)];
            Arrays.fill(slot[j], -1);
        }
        int blocked = 0;
        for (final int a : order) {
            if (w[a] <= 0) {
                break;
            }
            if (augment(a, can, slot, new boolean[m])) {
                blocked += w[a];
            }
        }
        return total - blocked;
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
}
