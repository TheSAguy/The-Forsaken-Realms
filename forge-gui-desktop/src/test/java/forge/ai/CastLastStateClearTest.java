package forge.ai;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * TFR round 958: a cast spell's castSA keeps an LKI copy of the battlefield and graveyard "as you cast it". Stock kept
 * those lists for the life of the card (and of any token the spell made), which ran a 118-permanent duel out of
 * memory. MagicStack.onNextTurn now empties them when the cast turn is over. These tests pin both halves: the
 * same-turn readers still see the cast-time board, and the lists are gone the next turn.
 * <p>
 * Each test removes the card the spell depends on after casting, so a cast-time snapshot and the live board give
 * different answers.
 */
public class CastLastStateClearTest extends AITest {

    private static void cast(final Game game, final Player ai, final Card card) {
        // the cast-time snapshot is the game's last state; a real game refreshes it after every resolution
        game.getAction().checkStateEffects(true);
        game.copyLastState();
        final SpellAbility sa = card.getSpellAbilities().get(0);
        sa.setActivatingPlayer(ai);
        // the AI's real cast path (addAndUnfreeze, which takes the snapshot); playStack's plain add() takes none
        AssertJUnit.assertTrue("the cast must go on the stack", ComputerUtil.handlePlayingSpellAbility(ai, sa, null));
    }

    private static boolean hasLastState(final SpellAbility castSA) {
        return castSA != null
                && (castSA.getLastStateBattlefield() != null && !castSA.getLastStateBattlefield().isEmpty()
                || castSA.getLastStateGraveyard() != null && !castSA.getLastStateGraveyard().isEmpty());
    }

    @Test
    public void oratorOfOjutaiStillSeesTheDragonItWasCastWith() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 10);
        fillLibrary(game.getPlayers().get(0), 10);
        addCards("Plains", 2, ai);
        final Card dragon = addCard("Shivan Dragon", ai);
        final Card orator = addCardToZone("Orator of Ojutai", ai, ZoneType.Hand);

        cast(game, ai, orator);
        // the Dragon leaves before Orator resolves: only the cast-time snapshot still has it
        game.getAction().moveToGraveyard(dragon, null);
        final int hand = ai.getCardsIn(ZoneType.Hand).size();
        // past the enter trigger, whose intervening "if" is checked again when it resolves
        playUntilPhase(game, PhaseType.END_OF_TURN);

        AssertJUnit.assertEquals("Orator draws: a Dragon was controlled as it was cast", hand + 1,
                ai.getCardsIn(ZoneType.Hand).size());
        final Card onBattlefield = findCardWithName(game, "Orator of Ojutai");
        AssertJUnit.assertNotNull(onBattlefield);
        AssertJUnit.assertTrue("this turn the snapshot is still there", hasLastState(onBattlefield.getCastSA()));

        playUntilNextTurn(game);
        AssertJUnit.assertFalse("the next turn the snapshot is let go", hasLastState(onBattlefield.getCastSA()));
    }

    @Test
    public void lifestreamsBlessingCountsTheCreatureItWasCastWith() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 20);
        fillLibrary(game.getPlayers().get(0), 10);
        addCards("Forest", 6, ai);
        final Card wurm = addCard("Craw Wurm", ai);
        final Card blessing = addCardToZone("Lifestream's Blessing", ai, ZoneType.Hand);

        cast(game, ai, blessing);
        // X is "the greatest power among creatures you controlled as you cast this spell": the live board has none
        game.getAction().moveToGraveyard(wurm, null);
        final int hand = ai.getCardsIn(ZoneType.Hand).size();
        playUntilStackClear(game);

        AssertJUnit.assertEquals("draws 6, Craw Wurm's power as it was cast", hand + 6,
                ai.getCardsIn(ZoneType.Hand).size());
    }

    @Test
    public void aTokenNoLongerKeepsItsSpellsSnapshot() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 10);
        fillLibrary(game.getPlayers().get(0), 10);
        addCards("Plains", 2, ai);
        addCards("Runeclaw Bear", 3, ai);
        final Card alarm = addCardToZone("Raise the Alarm", ai, ZoneType.Hand);

        cast(game, ai, alarm);
        playUntilStackClear(game);

        Card token = null;
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (c.isToken()) {
                token = c;
            }
        }
        AssertJUnit.assertNotNull("Raise the Alarm made its tokens", token);
        final SpellAbility spawn = token.getTokenSpawningAbility();
        AssertJUnit.assertNotNull(spawn);
        // the token reaches the spell's cast-time snapshot through the ability that made it
        final SpellAbility castSA = spawn.getHostCard().getCastSA();
        AssertJUnit.assertTrue("this turn the token still reaches the spell's snapshot", hasLastState(castSA));

        playUntilNextTurn(game);
        AssertJUnit.assertFalse("the next turn the token no longer keeps it", hasLastState(castSA));
    }
}
