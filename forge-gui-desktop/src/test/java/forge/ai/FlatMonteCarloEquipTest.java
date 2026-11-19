package forge.ai;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.eventbus.Subscribe;

import forge.ai.simulation.EquipGuard;
import forge.ai.simulation.FlatMonteCarloPicker;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * TFR round 1045 (ported from TFR 2's FlatMonteCarloEquipTest, 2026-11-18; the TFR 2 user: "'Grafted Wargear' was like
 * triggering 10+ times each turn"): Flat Monte Carlo's discovery aimed a free Equip at the AI's best creature, the one
 * already wearing it, so the rollouts compared a no-op with the other plays, it could win, and the next decision offered
 * it again: 25 activations a turn until TFR's loop breaker barred it. An Equip aimed at the creature it already equips
 * is never offered now. {@code -Dtfr.equipZeroOld=true} runs the red check.
 */
public class FlatMonteCarloEquipTest extends AITest {
    private static final int LONG_TIMEOUT_MS = 180_000;

    private Game game;
    private Player ai, opp;

    private void setUp() {
        game = initAndCreateGame();
        game.AI_FLAT_MONTE_CARLO = true;
        game.AI_TIMEOUT = 10;
        ai = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        ai.setTeam(0);
        opp.setTeam(1);
    }

    private SpellAbility equipOf(final Card c) {
        for (final SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isActivatedAbility() && !sa.isManaAbility() && sa.isEquip()) {
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        throw new IllegalStateException("no equip on " + c);
    }

    private Card wornBy(final String equipment, final Card wearer) {
        final Card e = addCardToZone(equipment, ai, ZoneType.Battlefield);
        e.attachToEntity(wearer, null);
        game.getAction().checkStateEffects(true);
        return e;
    }

    private void fillLibraries() {
        for (int i = 0; i < 12; i++) {
            addCardToZone("Plains", ai, ZoneType.Library);
            addCardToZone("Mountain", opp, ZoneType.Library);
        }
    }

    @Test
    public void discoveryNeverAimsTheEquipAtItsOwnWearer() {
        setUp();
        final Card giant = addCardToZone("Hill Giant", ai, ZoneType.Battlefield);
        addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        final Card wargear = wornBy("Grafted Wargear", giant);
        final SpellAbility equip = equipOf(wargear);
        final GameObject aim = FlatMonteCarloPicker.loyaltyTarget(ai, equip);
        AssertJUnit.assertNotSame("the Giant already wears it", giant, aim);
        AssertJUnit.assertEquals("already-attached", EquipGuard.idle(ai, equip, giant));
    }

    @Test
    public void anEquipWithOnlyItsWearerToAimAtIsNotDiscovered() {
        setUp();
        final Card giant = addCardToZone("Hill Giant", ai, ZoneType.Battlefield);
        final SpellAbility equip = equipOf(wornBy("Shuko", giant));
        AssertJUnit.assertFalse("the Giant is the only creature, and it wears the Shuko",
                FlatMonteCarloPicker.isDiscoverable(equip));
    }

    @Test
    public void theWearerIsNoTargetVariant() {
        setUp();
        final Card giant = addCardToZone("Hill Giant", ai, ZoneType.Battlefield);
        final Card bears = addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        addCardToZone("Silvercoat Lion", ai, ZoneType.Battlefield);
        final SpellAbility equip = equipOf(wornBy("Shuko", giant));
        equip.resetTargets();
        equip.getTargets().add(bears);
        final List<GameObject> variants = FlatMonteCarloPicker.alternativeTargets(ai, equip, 5);
        AssertJUnit.assertFalse("the Giant wears it already: " + variants, variants.contains(giant));
    }

    @Test
    public void anEquipStaysOffAnUnequippedCreatureOfItsOwn() {
        setUp();
        final Card bears = addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        final SpellAbility equip = equipOf(addCardToZone("Shuko", ai, ZoneType.Battlefield));
        AssertJUnit.assertNull("nothing wears it yet", EquipGuard.idle(ai, equip, bears));
    }

    /**
     * The loop's seed: when the heuristics' favorite is a play the rollouts reject (here Pyroclasm, which kills the AI's
     * own Bears and nothing of the opponent's), the rollouts want to do nothing, and on the AI's own turn holding is not
     * a candidate. The old discovery offered the Equip onto its own wearer, a no-op that won as a disguised pass, did not
     * end the priority, and came back at the next decision: 25 times a turn in the user's duel.
     */
    @Test(timeOut = LONG_TIMEOUT_MS)
    public void theNoOpEquipIsNoDisguisedPass() {
        setUp();
        final Card giant = addCardToZone("Hill Giant", ai, ZoneType.Battlefield);
        addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        addCards("Mountain", 3, ai);
        addCardToZone("Hill Giant", opp, ZoneType.Battlefield);
        fillLibraries();
        final SpellAbility equip = equipOf(wornBy("Grafted Wargear", giant));
        final Card pyro = addCardToZone("Pyroclasm", ai, ZoneType.Hand);
        final SpellAbility bad = pyro.getFirstSpellAbility();
        bad.setActivatingPlayer(ai);
        final AiController brain = ((PlayerControllerAi) ai.getController()).getAi();
        final FlatMonteCarloPicker.Result r = FlatMonteCarloPicker.choose(ai, List.of(bad), List.of(equip),
                FlatMonteCarloPicker.Config.fromProfile(brain, ai), null);
        final GameObject aim = r.target != null ? r.target
                : equip.getTargets().isEmpty() ? null : equip.getTargets().getFirstTargetedCard();
        System.out.println("[TFR-AiEquip] test: Pyroclasm against the equip: " + r + ", the equip aimed at " + aim);
        AssertJUnit.assertFalse("the Equip onto the Giant that wears it was chosen: " + r,
                r.choice == equip && giant.equals(aim));
    }

    /**
     * TFR round 1045: with the wearer barred, the next best aim is another creature, and a free Equip without a drawback
     * (Shuko) went back and forth between two creatures 12 times in a turn of round 1045's bench. A move stays the
     * rollouts' call once a turn, as stock AttachAi allows; after that the ability is not offered again.
     */
    @Test
    public void aMoveIsOfferedOnceATurn() {
        setUp();
        final Card giant = addCardToZone("Hill Giant", ai, ZoneType.Battlefield);
        final Card bears = addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        final Card shuko = wornBy("Shuko", giant);
        final SpellAbility equip = equipOf(shuko);
        AssertJUnit.assertNull("the first move of the turn is the rollouts' call", EquipGuard.idle(ai, equip, bears));
        shuko.addAbilityActivated(equip); // equipped once this turn
        AssertJUnit.assertEquals("moved-this-turn", EquipGuard.idle(ai, equip, bears));
        AssertJUnit.assertNull("nothing left to aim it at", FlatMonteCarloPicker.loyaltyTarget(ai, equip));
    }

    /** The ping-pong's seed: Pyroclasm against a Shuko that was equipped this turn, with a second creature to move it to. */
    @Test(timeOut = LONG_TIMEOUT_MS)
    public void aSecondMoveIsNoDisguisedPass() {
        setUp();
        final Card giant = addCardToZone("Hill Giant", ai, ZoneType.Battlefield);
        addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        addCards("Mountain", 3, ai);
        addCardToZone("Hill Giant", opp, ZoneType.Battlefield);
        fillLibraries();
        final Card shuko = wornBy("Shuko", giant);
        final SpellAbility equip = equipOf(shuko);
        shuko.addAbilityActivated(equip); // equipped once this turn
        final Card pyro = addCardToZone("Pyroclasm", ai, ZoneType.Hand);
        final SpellAbility bad = pyro.getFirstSpellAbility();
        bad.setActivatingPlayer(ai);
        final AiController brain = ((PlayerControllerAi) ai.getController()).getAi();
        final FlatMonteCarloPicker.Result r = FlatMonteCarloPicker.choose(ai, List.of(bad), List.of(equip),
                FlatMonteCarloPicker.Config.fromProfile(brain, ai), null);
        System.out.println("[TFR-AiEquip] test: Pyroclasm against a second Shuko move: " + r);
        AssertJUnit.assertNotSame("a second move this turn was chosen: " + r, equip, r.choice);
    }

    /** The AI's whole turn with the Equipment on its best creature: it must not keep re-equipping it. */
    private int equipsInATurn(final String equipment) {
        setUp();
        final Card giant = addCardToZone("Hill Giant", ai, ZoneType.Battlefield);
        addCardToZone("Grizzly Bears", ai, ZoneType.Battlefield);
        addCards("Plains", 3, ai);
        addCardToZone("Grizzly Bears", opp, ZoneType.Battlefield);
        fillLibraries();
        final Card worn = wornBy(equipment, giant);
        final AtomicInteger equips = new AtomicInteger();
        game.subscribeToEvents(new Object() {
            @Subscribe
            public void on(final GameEventSpellAbilityCast ev) {
                if (!ev.si().isTrigger() && ev.sa().getHostCard() != null
                        && ev.sa().getHostCard().getId() == worn.getId()) {
                    equips.incrementAndGet();
                }
            }
        });
        playUntilPhase(game, PhaseType.END_OF_TURN);
        System.out.println("[TFR-AiEquip] test: " + equipment + " equipped " + equips.get() + " time(s) in the AI's turn");
        return equips.get();
    }

    @Test(timeOut = LONG_TIMEOUT_MS)
    public void graftedWargearIsNotReequippedAllTurn() {
        AssertJUnit.assertTrue("at most one move", equipsInATurn("Grafted Wargear") <= 1);
    }

    @Test(timeOut = LONG_TIMEOUT_MS)
    public void shukoIsNotReequippedAllTurn() {
        AssertJUnit.assertTrue("at most one move", equipsInATurn("Shuko") <= 1);
    }
}
