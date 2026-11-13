package forge.ai;

import java.util.List;

import org.testng.annotations.Test;

import forge.ai.simulation.GameCopier;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;

/**
 * TFR round 1024: ComputerUtilCombat.predictTeamPumpOfAttacker counts an activated team pump (Thunderscape Master's
 * "{G}{G}, {T}: creatures you control get +2/+2") only while it can be paid, and its canPayCost answer is cached per
 * thread. The cache must never hand an answer to a different mana state or to another game: tapping a land leaves the
 * game's timestamp unchanged, and a Flat Monte Carlo copy keeps the original's timestamp and ability ids.
 */
public class TeamPumpPredictionTest extends AITest {

    private static int pump(final Card attacker) {
        return ComputerUtilCombat.predictTeamPumpOfAttacker(attacker, null, true);
    }

    private static Card find(final Player p, final String name) {
        for (final Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    @Test
    public void theTeamPumpFollowsTheManaAndEachGameCopy() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        addCard("Thunderscape Master", p).setSickness(false);
        final List<Card> forests = addCards("Forest", 2, p);
        final Card bear = addCard("Grizzly Bears", p);
        bear.setSickness(false);
        game.getAction().checkStateEffects(true);

        assertEquals("{G}{G} open: +2", 2, pump(bear));
        assertEquals("asked again (a cache hit): +2", 2, pump(bear));

        // the same game, turn and step, and the timestamp does not move: only the tapped Forest tells
        final long timestamp = game.getTimestamp();
        forests.get(0).setTapped(true);
        assertEquals("tapping a land leaves the timestamp alone", timestamp, game.getTimestamp());
        assertEquals("one Forest tapped: no +2", 0, pump(bear));
        forests.get(0).setTapped(false);
        assertEquals("untapped again: +2", 2, pump(bear));

        // mana already in the pool pays too
        forests.get(0).setTapped(true);
        forests.get(1).setTapped(true);
        assertEquals("both Forests tapped: no +2", 0, pump(bear));
        p.getManaPool().addMana(new Mana((byte) ManaAtom.GREEN, forests.get(0), null, p));
        p.getManaPool().addMana(new Mana((byte) ManaAtom.GREEN, forests.get(1), null, p));
        assertEquals("{G}{G} floating: +2", 2, pump(bear));
        p.getManaPool().resetPool();
        forests.get(0).setTapped(false);
        forests.get(1).setTapped(false);
        assertEquals("back to two open Forests: +2", 2, pump(bear));

        // a game copy: same timestamp, same ability ids, its own mana
        final Game copy = new GameCopier(game).makeCopy();
        final Player cp = copy.getPlayers().get(0);
        final Card copyBear = find(cp, "Grizzly Bears");
        assertNotNull(copyBear);
        for (final Card c : cp.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals("Forest")) {
                c.setTapped(true);
            }
        }
        assertEquals("the copy's Forests are tapped: no +2 there", 0, pump(copyBear));
        assertEquals("the real game still has {G}{G}: +2", 2, pump(bear));
        assertEquals("and the copy still has none", 0, pump(copyBear));

        // the other way round: the real game taps out, the copy untaps
        forests.get(0).setTapped(true);
        forests.get(1).setTapped(true);
        for (final Card c : cp.getCardsIn(ZoneType.Battlefield)) {
            c.setTapped(false);
        }
        assertEquals("the real game tapped out: no +2", 0, pump(bear));
        assertEquals("the copy untapped: +2", 2, pump(copyBear));
    }
}
