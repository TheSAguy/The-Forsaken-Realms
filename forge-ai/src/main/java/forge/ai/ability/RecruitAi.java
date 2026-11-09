package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtil;
import forge.ai.SpellAbilityAi;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.Map;

/**
 * AI for Recruit (draw a card, then discard a card; a nonland discard makes a 1/1 white Human Soldier).
 * TFR round 998 (logs-1109 P3-14): SpellApiToAi had no entry, so Recruit fell back to CannotPlayAi. Its mandatory
 * triggers and saga chapter still resolved, but the AI never cast a spell carrying it as a sub-ability (Sound the
 * Trumpets) and declined any optional one. The loot is always worth it unless the draw would deck the AI.
 */
public class RecruitAi extends SpellAbilityAi {
    /** The draw comes first: never from an empty library, which loses the game at the next state check. */
    private static boolean safeToDraw(final Player ai) {
        return ai.canDraw() && !ai.getCardsIn(ZoneType.Library).isEmpty();
    }

    @Override
    protected AiAbilityDecision checkApiLogic(final Player ai, final SpellAbility sa) {
        if (!safeToDraw(ai)) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
        // no stock card activates Recruit on its own; an instant-speed one would wait for main 2 or the
        // opponent's end step like Incubate and Empower, so the mana stays open for the main-phase plays
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        if (isSorcerySpeed(sa, ai) || ComputerUtil.playImmediately(ai, sa)
                || ph.is(PhaseType.MAIN2, ai) || (ph.is(PhaseType.END_OF_TURN) && ph.isPlayerNextTurn(ai))) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return new AiAbilityDecision(0, AiPlayDecision.WaitForMain2);
    }

    @Override
    public AiAbilityDecision chkDrawback(final Player ai, final SpellAbility sa) {
        // Sound the Trumpets: the counterspell decides; only a draw that would deck the AI holds it back
        return safeToDraw(ai) ? new AiAbilityDecision(100, AiPlayDecision.WillPlay)
                : new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(final Player ai, final SpellAbility sa, final boolean mandatory) {
        if (mandatory || safeToDraw(ai)) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
    }

    @Override
    public boolean confirmAction(Player player, SpellAbility sa, PlayerActionConfirmMode mode, String message, Map<String, Object> params) {
        return safeToDraw(player);
    }
}
