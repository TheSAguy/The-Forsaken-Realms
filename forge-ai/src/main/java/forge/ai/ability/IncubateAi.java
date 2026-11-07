package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilCard;
import forge.ai.SpellAbilityAi;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostSacrifice;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;

import java.util.Map;

/**
 * AI for Incubate (create an Incubator token with N +1/+1 counters that transforms into an N/N Phyrexian for {2}).
 * As a sub-ability (Sunfall, Excise the Imperfect) the inherited chkDrawback plays it, so the main effect decides.
 */
public class IncubateAi extends SpellAbilityAi {
    @Override
    protected AiAbilityDecision checkApiLogic(final Player ai, final SpellAbility sa) {
        final int amount = AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParamOrDefault("Amount", "1"), sa);
        if (amount < 1) {
            return new AiAbilityDecision(0, AiPlayDecision.DoesntImpactGame);
        }
        // sorcery-speed ones (Glistening Dawn, Ichor Drinker) can only be played when they make sense anyway;
        // instant-speed ones (Eyes of Gitaxias, Compleated Huntmaster) wait for main 2 or the opponent's end step
        // so the mana stays open for the main-phase plays and for combat
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        if (isSorcerySpeed(sa, ai) || ComputerUtil.playImmediately(ai, sa)
                || ph.is(PhaseType.MAIN2, ai) || (ph.is(PhaseType.END_OF_TURN) && ph.isPlayerNextTurn(ai))) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return new AiAbilityDecision(0, AiPlayDecision.WaitForMain2);
    }

    @Override
    protected boolean willPayCosts(final Player ai, final SpellAbility sa, final Cost cost, final Card source) {
        for (final CostPart part : cost.getCostParts()) {
            if (part instanceof CostSacrifice sac && !sac.payCostFromSource()) {
                // Compleated Huntmaster: sacrifice only something worth less than the token it makes
                if (!sacrificeIsWorthIt(ai, sa, sac)) {
                    return false;
                }
                // the stock sacrifice check refuses fodder unless it carries SacMe, so skip it for this part
                final Cost rest = cost.copy();
                rest.getCostParts().removeIf(p -> p instanceof CostSacrifice s && !s.payCostFromSource());
                return super.willPayCosts(ai, sa, rest, source);
            }
        }
        return super.willPayCosts(ai, sa, cost, source);
    }

    private static boolean sacrificeIsWorthIt(final Player ai, final SpellAbility sa, final CostSacrifice sac) {
        final Card host = sa.getHostCard();
        final int sacAmount = AbilityUtils.calculateAmount(host, sac.getAmount(), sa);
        // the same cards the cost payment will pick
        final CardCollection choice = ComputerUtil.chooseSacrificeType(ai, sac.getType(), sa, null, false, sacAmount, null);
        if (choice == null || choice.size() < sacAmount) {
            return false;
        }
        final int amount = AbilityUtils.calculateAmount(host, sa.getParamOrDefault("Amount", "1"), sa);
        // what CreatureEvaluator gives an N/N token without abilities
        final int tokenValue = 80 + amount * 15 + amount * 10;
        int sacValue = 0;
        for (final Card c : choice) {
            if (c.isCreature()) {
                sacValue += ComputerUtilCard.evaluateCreature(c);
            } else if (!c.isToken() && !c.hasSVar("SacMe")) {
                // a nontoken artifact that is not a creature: only the cheap ones
                sacValue += c.getCMC() <= 1 ? 50 : 1000;
            }
        }
        return sacValue < tokenValue;
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(final Player ai, final SpellAbility sa, final boolean mandatory) {
        // a free extra body is always welcome
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message, Map<String, Object> params) {
        return true;
    }
}
