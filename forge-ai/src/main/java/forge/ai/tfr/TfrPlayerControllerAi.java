package forge.ai.tfr;

import java.util.List;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * TFR round 1018: the AI controller of a TFR seat. Forge decides first; the TFR AI rules ({@link TfrAiRules}) then
 * review the decision. Overrides only what a rule reviews, and always calls Forge's own decision first.
 */
public class TfrPlayerControllerAi extends PlayerControllerAi {
    private boolean announced;

    public TfrPlayerControllerAi(final Game game, final Player p, final LobbyPlayer lp) {
        super(game, p, lp);
    }

    /** The rules-version line, once per duel, on the seat's first decision in the real game. */
    private void announce() {
        if (!announced && !getGame().isSimulationCopy()) {
            announced = true;
            System.out.println(TfrAiRules.duelStartLine(getPlayer()));
        }
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        announce();
        return super.chooseSpellAbilityToPlay();
    }

    @Override
    public void declareAttackers(final Player attacker, final Combat combat) {
        announce();
        super.declareAttackers(attacker, combat);
        TfrAiRules.reviewAttack(attacker, combat);
    }
}
