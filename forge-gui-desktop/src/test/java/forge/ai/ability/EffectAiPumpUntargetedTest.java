package forge.ai.ability;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertTrue;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.SpellApiToAi;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * EffectAi's AILogic$ Pump with no targets (round 1037).
 *
 * Giant Koi's "This creature can't be blocked this turn" is an untargeted Effect (Defined$ Self) with AILogic$ Pump.
 * The Pump branch of EffectAi.checkApiLogic asked CardUtil.getValidCardsToTarget for its options, which dereferences
 * the target restrictions the ability doesn't have: a NullPointerException that made the AI pass its whole priority
 * (a player's 1.28 log, 8 throws in one duel). The untargeted path now judges the defined cards instead.
 */
public class EffectAiPumpUntargetedTest extends AITest {

    private static final String KOI = "Giant Koi";

    private static final class Fixture {
        final Game game;
        final Player ai;
        final Player opp;
        final Card koi;
        final SpellAbility sa;

        Fixture(Game game, Player ai, Player opp, Card koi, SpellAbility sa) {
            this.game = game;
            this.ai = ai;
            this.opp = opp;
            this.koi = koi;
            this.sa = sa;
        }
    }

    /** A Giant Koi that can attack, three Islands to waterbend with, a Grizzly Bears to block it; the AI's main 1. */
    private Fixture fixture() {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);

        final Card koi = addCard(KOI, ai);
        koi.setSickness(false);
        addCards("Island", 3, ai);
        addCard("Grizzly Bears", opp).setSickness(false);
        for (final Player p : game.getPlayers()) {
            fillLibrary(p, 20);
        }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = null;
        for (final SpellAbility s : koi.getSpellAbilities()) {
            if (s.getApi() == ApiType.Effect) {
                sa = s;
            }
        }
        assertNotNull("Giant Koi's Effect ability", sa);
        assertFalse("the ability has no targets, the case that threw", sa.usesTargeting());
        sa.setActivatingPlayer(ai);
        return new Fixture(game, ai, opp, koi, sa);
    }

    private static boolean willPlay(final Fixture f) {
        return SpellApiToAi.Converter.get(f.sa).canPlayWithSubs(f.ai, f.sa).willingToPlay();
    }

    @Test
    public void untargetedPumpIsPlayedInMainOneForACreatureThatCanAttack() {
        final Fixture f = fixture();
        // threw a NullPointerException in CardUtil.getValidCardsToTarget before round 1037
        assertTrue("makes the Koi unblockable before it attacks", willPlay(f));
        assertEquals("an untargeted ability gets no targets", 0, f.sa.getTargets().size());
    }

    @Test
    public void untargetedPumpIsPlayedForAnAttackingCreatureBeforeBlockers() {
        final Fixture f = fixture();
        f.game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, f.ai);
        final Combat combat = new Combat(f.ai);
        combat.addAttacker(f.koi, f.opp);
        f.game.getPhaseHandler().setCombat(combat);
        f.koi.setTapped(true);
        assertTrue("still worth it once the Koi is attacking", willPlay(f));
    }

    @Test
    public void notOnTheOpponentsTurn() {
        final Fixture f = fixture();
        f.game.getPhaseHandler().devModeSet(PhaseType.MAIN1, f.opp);
        assertFalse(willPlay(f));
    }

    @Test
    public void notForACreatureThatCannotAttack() {
        final Fixture f = fixture();
        f.koi.setSickness(true);
        assertFalse("a summoning-sick Koi gains nothing from it this turn", willPlay(f));
    }

    @Test
    public void notTwiceInOneTurn() {
        final Fixture f = fixture();
        AbilityUtils.resolve(f.sa);
        assertTrue("harness: the effect is in the command zone", f.ai.getCardsIn(ZoneType.Command).size() > 0);
        assertFalse("the Koi is unblockable already", willPlay(f));
    }

    @Test
    public void targetedPumpStillPicksItsTarget() {
        final Fixture f = fixture();
        final Card dodge = addCardToZone("Artful Dodge", f.ai, ZoneType.Hand);
        final SpellAbility dodgeSa = dodge.getFirstSpellAbility();
        dodgeSa.setActivatingPlayer(f.ai);
        assertTrue("harness: Artful Dodge targets", dodgeSa.usesTargeting());

        final boolean play = SpellApiToAi.Converter.get(dodgeSa).canPlayWithSubs(f.ai, dodgeSa).willingToPlay();

        assertTrue(play);
        assertEquals("the AI's own Koi is the target", f.koi, dodgeSa.getTargets().getFirstTargetedCard());
    }
}
