package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * AI for an effect that needs no decision of its own when it rides on another ability: the parent ability's AI
 * decides whether to play, and this part just resolves. On its own (a spell or ability whose main effect it is) the
 * AI still never plays it, exactly as {@link CannotPlayAi}.
 * <p>
 * TFR round 1022 (pattern 7, abilities with no AI): SpellApiToAi had no entry for ChangeText, so it fell back to
 * CannotPlayAi, whose chkDrawback says no, and SpellAbilityAi.chkDrawbackWithSubs then vetoed the whole spell. New
 * Blood (gain control of target creature, then change its text) sat in hand in four TFR legend decks for that reason
 * alone (puzzle P8_new_blood).
 */
public class DrawbackOnlyAi extends CannotPlayAi {
    @Override
    public AiAbilityDecision chkDrawback(final Player aiPlayer, final SpellAbility sa) {
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }
}
