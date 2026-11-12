package forge.ai.tfr;

import java.util.Set;

import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * TFR round 1018: the AI seat that plays by the TFR AI rules ({@link TfrAiRules}). Forge's {@link LobbyPlayerAi} makes
 * the player and its controller as it always does; this class then swaps in a {@link TfrPlayerControllerAi} with the
 * same simulation option. So whatever upstream adds to {@code createIngamePlayer} (profile rotation, ...) still runs.
 * Game copies reuse this lobby player ({@code GameCopier.clonePlayer}), so a copy's AI seat is a TFR seat too; the
 * rules themselves stay out of copies ({@link TfrAiRules#isOn}).
 */
public class TfrLobbyPlayerAi extends LobbyPlayerAi {

    public TfrLobbyPlayerAi(final String name, final Set<AIOption> options) {
        super(name, options);
    }

    @Override
    public Player createIngamePlayer(final Game game, final int id) {
        final Player ai = super.createIngamePlayer(game, id);
        ai.dangerouslySetController(wrap(ai.getController(), ai));
        return ai;
    }

    @Override
    public PlayerController createMindSlaveController(final Player master, final Player slave) {
        return wrap(super.createMindSlaveController(master, slave), slave);
    }

    private PlayerController wrap(final PlayerController made, final Player p) {
        if (!(made instanceof PlayerControllerAi stock) || made instanceof TfrPlayerControllerAi) {
            return made;
        }
        final TfrPlayerControllerAi tfr = new TfrPlayerControllerAi(p.getGame(), p, this);
        final AIOption sim = stock.getAi().usesFullSimulation() ? AIOption.USE_FULL_SIMULATION
                : stock.getAi().usesHybridSimulation() ? AIOption.USE_HYBRID_SIMULATION : null;
        tfr.getAi().setUseSimulation(sim);
        return tfr;
    }
}
