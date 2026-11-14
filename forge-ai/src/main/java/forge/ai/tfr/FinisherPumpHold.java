package forge.ai.tfr;

import java.util.ArrayList;
import java.util.List;

import forge.ai.ComputerUtilCombat;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * TFR round 1025, rule {@value #ID} (round 1023's study, F5): keep the pump spell that is next turn's kill.
 *
 * <p>Forge's pump AI judges each pump on its own. On the opponent's turn it pumps its blocker to save it or to kill the
 * attacker, whatever the pump is for in the deck. The study's infect deck (5 poison on the opponent, its infect
 * creatures home, the opponent's attackers about to stay tapped) blocked a 2/2 with Overgrown Battlement (0/4) and cast
 * Groundswell and then Primal Bellow on the wall: two cards for a 2/2, and the poison kill they made next turn was
 * gone.</p>
 *
 * <p>The review: the AI chose a pump spell from hand on its blocker, on the opponent's turn, with the stack empty, and
 * neither the blocker nor its attackers have lifelink or trample (so the pump changes no life total now). If the AI's
 * next attack is lethal with this pump kept and not with it spent, the AI holds it and passes priority instead. "Lethal"
 * is counted against every way the opponent can block next turn: its creatures that are untapped now block (the
 * attackers stay tapped through the AI's turn), each on the AI's biggest attacker of the kind it stops, and after the
 * blocks the AI puts every pump it can pay for (one land a mana) on one unblocked creature, for poison (infect) or
 * damage. A blocker that dies without the pump is not counted as an attacker when the pump is kept. Evasion is not
 * counted (fewer kills seen, never more), and landfall pumps count their present size.</p>
 *
 * <p>No stock line: {@link TfrPlayerControllerAi#chooseSpellAbilityToPlay()} is the hook. Puzzles: P15 (holds the
 * finisher and wins next turn), P15c (no kill next turn: pumps as Forge does).</p>
 */
final class FinisherPumpHold {
    static final String ID = "finisher-pump-hold";

    private FinisherPumpHold() {
    }

    /** True when the seat should hold the chosen pump and pass priority instead. */
    static boolean hold(final Player ai, final List<SpellAbility> chosen) {
        if (chosen == null || chosen.isEmpty() || !TfrAiRules.isOn(ID, ai)) {
            return false;
        }
        final Game game = ai.getGame();
        final Combat combat = game.getCombat();
        if (game.getPhaseHandler().isPlayerTurn(ai) || combat == null || !game.getStack().isEmpty()
                || !combat.isPlayerAttacked(ai)) {
            return false;
        }
        for (final SpellAbility sa : chosen) {
            final Card host = sa.getHostCard();
            if (sa.getApi() != ApiType.Pump || !sa.isSpell() || host == null || !host.isInZone(ZoneType.Hand)
                    || !sa.usesTargeting()) {
                continue;
            }
            final Card target = sa.getTargetCard();
            if (target == null || target.getController() != ai || !combat.isBlocking(target)
                    || target.hasKeyword(Keyword.LIFELINK)
                    || combat.getAttackersBlockedBy(target).stream().anyMatch(a -> a.hasKeyword(Keyword.TRAMPLE) || a.hasKeyword(Keyword.LIFELINK))) {
                continue;
            }
            final int bonus = bonus(sa);
            if (bonus <= 0) {
                continue;
            }
            final List<int[]> others = new ArrayList<>(); // {bonus, cmc} of the other pump spells in hand
            for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
                if (c == host) {
                    continue;
                }
                for (final SpellAbility o : c.getSpells()) {
                    if (o.getApi() == ApiType.Pump && o.usesTargeting() && bonus(o) > 0) {
                        others.add(new int[] {bonus(o), c.getCMC()});
                        break;
                    }
                }
            }
            final Player opp = combat.getAttackingPlayer();
            final boolean blockerDies = ComputerUtilCombat.blockerWouldBeDestroyed(ai, target, combat);
            final List<int[]> kept = new ArrayList<>(others);
            kept.add(new int[] {bonus, host.getCMC()});
            final String keepLine = lethal(ai, opp, kept, blockerDies ? target : null);
            if (keepLine == null || lethal(ai, opp, others, null) != null) {
                continue;
            }
            TfrAiRules.fired(ID, "turn " + game.getPhaseHandler().getTurn() + " " + ai.getName() + " casts " + host.getName()
                    + " (+" + bonus + ") on blocker " + target.getName(), "holds it for next turn: " + keepLine);
            return true;
        }
        return false;
    }

    /** A pump spell's power bonus now (its NumAtt; X counted at its present value), or 0. */
    static int bonus(final SpellAbility sa) {
        final String att = sa.getParam("NumAtt");
        if (att == null || !att.startsWith("+")) {
            return 0;
        }
        try {
            return Math.max(0, AbilityUtils.calculateAmount(sa.getHostCard(), att.substring(1), sa));
        } catch (final RuntimeException e) {
            return 0;
        }
    }

    /**
     * Whether the AI's next attack kills {@code opp} whatever the opponent blocks, with these pumps ({bonus, cmc}) put on
     * one unblocked attacker. Returns a short description of the kill, or null.
     */
    static String lethal(final Player ai, final Player opp, final List<int[]> pumps, final Card notAttacking) {
        final List<Integer> infect = new ArrayList<>();
        final List<Integer> plain = new ArrayList<>();
        for (final Card c : ai.getCreaturesInPlay()) {
            if (c == notAttacking || !ComputerUtilCombat.canAttackNextTurn(c, opp)) {
                continue;
            }
            final int p = Math.max(0, c.getNetCombatDamage());
            (c.hasKeyword(Keyword.INFECT) ? infect : plain).add(p);
        }
        infect.sort((a, b) -> b - a);
        plain.sort((a, b) -> b - a);
        int blockers = 0;
        for (final Card c : opp.getCreaturesInPlay()) {
            if (c.isUntapped()) {
                blockers++;
            }
        }
        // the pumps the AI can pay for next turn (every land untaps), biggest first
        final List<int[]> sorted = new ArrayList<>(pumps);
        sorted.sort((a, b) -> b[0] - a[0]);
        int mana = ai.getLandsInPlay().size();
        int pumpTotal = 0;
        for (final int[] p : sorted) {
            if (p[1] <= mana) {
                mana -= p[1];
                pumpTotal += p[0];
            }
        }
        final int poison = opp.getPoisonCounters();
        final int life = opp.getLife();
        // every split of the blockers: k on the infect attackers, the rest on the others
        for (int k = 0; k <= blockers; k++) {
            final int poisonLeft = sumAfter(infect, k);
            final int damageLeft = sumAfter(plain, blockers - k);
            final boolean infectFree = infect.size() > k;
            final boolean plainFree = plain.size() > blockers - k;
            final boolean byPoison = infectFree && poison + poisonLeft + pumpTotal >= 10;
            final boolean byDamage = plainFree && opp.canLoseLife() && damageLeft + pumpTotal >= life;
            if (!byPoison && !byDamage) {
                return null;
            }
        }
        return (infect.isEmpty() ? "" : "infect " + infect + " ") + (plain.isEmpty() ? "" : "others " + plain + " ")
                + "against " + blockers + " untapped blocker(s), pumps +" + pumpTotal + ", opponent at " + life + " life and "
                + poison + " poison";
    }

    private static int sumAfter(final List<Integer> desc, final int skip) {
        int s = 0;
        for (int i = skip; i < desc.size(); i++) {
            s += desc.get(i);
        }
        return s;
    }
}
