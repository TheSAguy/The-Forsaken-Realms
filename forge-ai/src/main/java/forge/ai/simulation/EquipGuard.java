package forge.ai.simulation;

import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * TFR round 1045 (ported from TFR 2's equip-zero fix, 2026-11-18; the TFR 2 user: "'Grafted Wargear' was like
 * triggering 10+ times each turn"): an Equip (any attach ability of an attachment in play) aimed at the creature the
 * attachment is already on does nothing (rule 701.3b), yet Flat Monte Carlo's discovery aimed it there:
 * {@link FlatMonteCarloPicker#loyaltyTarget} picks the AI's best creature, and the wearer is the best because it wears
 * the Equipment. The rollouts saw a no-op as good as any other line, it could win as a disguised pass on the AI's own
 * main phase, and the next decision offered it again, until TFR's loop breaker barred it at 25 uses a turn
 * ({@code [TFR-LoopBreak]}). The heuristics ({@code AttachAi}) refuse it already, and upstream's
 * {@link ActivationGuard} calls the Equip idle when the wearer is its only legal target; this keeps discovery and its
 * target variants from aiming it at the wearer when another creature is there too, with the same exception for a
 * known combo ({@code attachPayoff}).
 *
 * <p>A move to another creature stays the rollouts' call once a turn. TFR 2 stopped at the wearer; TFR 1's bench (round
 * 1045) then saw the same disguised pass as a ping-pong: a free Shuko moved between two creatures 12 times in a turn. Stock
 * {@code AttachAi} moves an Equipment at most once a turn ("avoid randomly moving the equipment back and forth"); this
 * holds discovery to the same: no move off a creature of the AI's once the ability was activated this turn.
 *
 * <p>Logs {@code [TFR-AiFix] equip-wearer} and {@code [TFR-AiFix] equip-move} once a card a turn (never from a rollout).
 * {@code -Dtfr.equipZeroOld=true} turns it off (the red check).
 */
public final class EquipGuard {
    private EquipGuard() {
    }

    /** The old behavior, for the red check. */
    public static boolean off() {
        return Boolean.getBoolean("tfr.equipZeroOld");
    }

    /** The attachment an attach ability moves: its host, or the card its Object names. */
    static Card attachment(final SpellAbility sa) {
        if (sa == null || sa.getApi() != ApiType.Attach || sa.getHostCard() == null) {
            return null;
        }
        Card source = sa.getHostCard();
        if (sa.hasParam("Object")) {
            final CardCollection objs = AbilityUtils.getDefinedCards(source, sa.getParam("Object"), sa);
            if (objs.size() != 1) {
                return null;
            }
            source = objs.get(0);
        }
        return source.isAttachment() ? source : null;
    }

    /**
     * @return "already-attached" when the play attaches its Equipment (Aura, Fortification) to what it is already
     * attached to; "moved-this-turn" when it would move an attachment off a creature of the AI's again after the same
     * ability was activated this turn; else null
     */
    public static String idle(final Player ai, final SpellAbility sa, final GameObject target) {
        if (off() || !(target instanceof GameEntity)) {
            return null;
        }
        final SpellAbility ts = FlatMonteCarloPicker.singleTargetingAbility(sa);
        final SpellAbility node = ts == null ? sa : ts;
        final Card source = attachment(node);
        if (source == null || source.getAttachedTo() == null) {
            return null;
        }
        final GameEntity on = source.getAttachedTo();
        final boolean wearer = on.equals(target);
        // a move off a creature of the AI's after this ability was already activated this turn (round 1045's bench:
        // with the wearer barred, a free Shuko went back and forth between two creatures 12 times in one turn)
        final boolean again = !wearer && ai != null && on instanceof Card && ai.equals(((Card) on).getController())
                && node.isActivatedAbility() && node.getActivationsThisTurn() > 0;
        if (!wearer && !again) {
            return null;
        }
        if (ai != null && ActivationGuard.attachPayoff(ai, source)) {
            return null; // a known combo pays for every attachment: upstream's own exception (ActivationGuard)
        }
        if (wearer) {
            note(ai, "equip-wearer", source, source.getName() + " (" + source.getId() + ") is not offered to re-equip "
                    + target + ", which already wears it");
            return "already-attached";
        }
        note(ai, "equip-move", source, source.getName() + " (" + source.getId() + ") is not offered to move again from "
                + on + " to " + target + ": it was equipped this turn");
        return "moved-this-turn";
    }

    private static void note(final Player ai, final String kind, final Card source, final String what) {
        try {
            // once per game, turn and card, never inside a game copy (stockFix checks both)
            forge.ai.tfr.TfrAiRules.stockFix(ai, kind + ":" + source.getId(), what);
        } catch (final RuntimeException e) {
            // a log line only: never let it break a decision
        }
    }
}
