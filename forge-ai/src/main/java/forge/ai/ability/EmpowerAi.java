package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilCard;
import forge.ai.SpellAbilityAi;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;

import java.util.Map;

/**
 * AI for Empower (put loyalty counters on a Jace token you control, creating one first if needed).
 * As a sub-ability (Countersculpt, No Admittance) the inherited chkDrawback plays it, so the main effect decides.
 */
public class EmpowerAi extends SpellAbilityAi {
    @Override
    protected AiAbilityDecision checkApiLogic(final Player ai, final SpellAbility sa) {
        // loyalty abilities (Jace, Reality Sculptor +1) and sorcery-speed ones need no timing of their own;
        // instant-speed ones (Campus Crier from the graveyard, Inspired Tethermage) wait for main 2 or the
        // opponent's end step so the mana stays open for the main-phase plays and for combat
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        if (sa.isPwAbility() || isSorcerySpeed(sa, ai) || ComputerUtil.playImmediately(ai, sa)
                || ph.is(PhaseType.MAIN2, ai) || (ph.is(PhaseType.END_OF_TURN) && ph.isPlayerNextTurn(ai))) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return new AiAbilityDecision(0, AiPlayDecision.WaitForMain2);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(final Player ai, final SpellAbility sa, final boolean mandatory) {
        // loyalty for free is always welcome
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message, Map<String, Object> params) {
        return true;
    }

    @Override
    protected Card chooseSingleCard(Player ai, SpellAbility sa, Iterable<Card> options, boolean isOptional, Player targetedPlayer, Map<String, Object> params) {
        // which of several Jace tokens gets the counters: the best one
        return ComputerUtilCard.getBestAI(options);
    }
}
