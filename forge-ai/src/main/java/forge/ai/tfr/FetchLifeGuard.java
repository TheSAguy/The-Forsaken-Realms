package forge.ai.tfr;

import forge.ai.ComputerUtilCombat;
import forge.ai.ComputerUtilMana;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPayLife;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * TFR round 1034, rule {@value #ID} (round 1029's study, F10): don't pay life for a fetchland into the opponent's
 * on-board damage.
 *
 * <p>{@code ChangeZoneAi.willPayCosts} pays any life cost that leaves the AI at 4 or more. In the study (Hard, H4 turn
 * 14) the Orc Brute at 6 life cracked Bloodstained Mire for a Triome that entered tapped, with nothing to cast, and went
 * to 5: exactly the opponent's Gurmag Angler. The fetched land did nothing that turn; the life point decided the
 * race.</p>
 *
 * <p>The review: a land's hidden ChangeZone ability (a fetch) with a pay-life cost. The AI keeps the land when its life
 * after paying is at most the damage the opponents' creatures would deal unblocked next turn (every creature without
 * defender, at its present power), unless the land is needed now: it is the AI's own main phase, the stack is empty and
 * the AI holds a spell it can't pay for now that costs at most one more than its available mana. Evasion and blocks are
 * not counted (the threat is what the board shows); a land fetched at the opponent's end step is never "needed".</p>
 *
 * <p>Hook: one line in {@code ChangeZoneAi.willPayCosts} (the rule's stock line, in {@code CORE_ENGINE_CHANGES.md}).
 * Puzzle: P20 (keeps the Mire at 6 life against a 5/5).</p>
 */
final class FetchLifeGuard {
    static final String ID = "fetch-life-guard";

    private FetchLifeGuard() {
    }

    /** False when the rule keeps the land: the life payment walks into the opponent's on-board damage. */
    static boolean allow(final Player ai, final Cost cost, final Card source, final SpellAbility sa) {
        if (cost == null || source == null || !source.isLand() || !TfrAiRules.isOn(ID, ai) || ai.cantLoseForZeroOrLessLife()) {
            return true;
        }
        int pay = 0;
        for (final CostPart part : cost.getCostParts()) {
            if (part instanceof CostPayLife payLife) {
                pay += payLife.getAbilityAmount(sa);
            }
        }
        if (pay <= 0) {
            return true;
        }
        final int threat = boardThreat(ai);
        final int lifeAfter = ai.getLife() - pay;
        if (lifeAfter > threat) {
            return true;
        }
        if (neededNow(ai)) {
            return true;
        }
        TfrAiRules.firedOnce(ID, ai, ID + ":" + source.getId(), source + " pays " + pay + " life (" + ai.getLife() + " -> "
                + lifeAfter + ") into " + threat + " on-board damage", "keeps the land");
        return false;
    }

    /** What the opponents' creatures deal unblocked next turn, at their present power (defenders left out). */
    static int boardThreat(final Player ai) {
        int threat = 0;
        for (final Player opp : ai.getOpponents()) {
            for (final Card c : opp.getCreaturesInPlay()) {
                if (c.hasKeyword(Keyword.DEFENDER) || c.hasKeyword("CARDNAME can't attack.")
                        || c.hasKeyword("CARDNAME can't attack or block.")) {
                    continue;
                }
                threat += ComputerUtilCombat.damageIfUnblocked(c, ai, null, false);
            }
        }
        return threat;
    }

    /** The AI's own main phase, stack empty, and a spell in hand that one more mana (or the fetched color) would cast. */
    private static boolean neededNow(final Player ai) {
        final Game game = ai.getGame();
        if (!game.getPhaseHandler().isPlayerTurn(ai) || !game.getPhaseHandler().getPhase().isMain() || !game.getStack().isEmpty()) {
            return false;
        }
        final int available = ComputerUtilMana.getAvailableManaEstimate(ai, false);
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (c.isLand() || c.getCMC() > available + 1) {
                continue;
            }
            for (final SpellAbility s : c.getSpells()) {
                if (!ComputerUtilMana.canPayManaCost(s, ai, 0, false)) {
                    return true;
                }
            }
        }
        return false;
    }
}
