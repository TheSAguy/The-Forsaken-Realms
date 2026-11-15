package forge.ai;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * How big a hidden combat trick the AI should assume a player can cast right now (TFR round 1035, F1/F13 of the
 * Hard/Insane study).
 * <p>
 * Combat prediction only counts what is on the battlefield, so a block the AI calls "safe" (the blocker survives) dies to
 * a single pump spell, and an attack that "can't be killed" (a first striker into an even blocker) loses its creature the
 * same way. A player who has cards in hand and untapped mana may hold such a trick. The allowance is the +N/+N the AI
 * assumes: 2 for any open mana, 3 when green is open (Giant Growth and its kin), one more once the player has shown a
 * creature trick (an instant in the graveyard that pumps, puts counters on or protects a creature), or that trick's own
 * size when larger. 0 when the player has no cards in hand or no untapped mana.
 * <p>
 * Opt-in by AI profile: {@link AiProps#TRICK_ALLOWANCE_ON_BLOCK} and {@link AiProps#TRICK_ALLOWANCE_ON_ATTACK}.
 */
public final class CombatTrickAllowance {
    private CombatTrickAllowance() {
    }

    /**
     * Per thread, 8 direct-mapped slots of (key, stamp, allowance + 1). The key is the game and the player; the stamp
     * hashes what the answer depends on: the turn, the step, the game's timestamp, the hand and graveyard sizes, the
     * floating mana, and every mana source's id and tapped state (tapping a land doesn't move the timestamp). Only
     * numbers are kept, never a game or a card. Game copies have their own game id.
     */
    private static final ThreadLocal<long[]> CACHE = ThreadLocal.withInitial(() -> new long[24]);

    public static int of(final Player trickster) {
        if (trickster == null || trickster.getCardsIn(ZoneType.Hand).isEmpty()) {
            return 0;
        }
        final long key = ((long) trickster.getGame().getId() << 32) | (trickster.getId() & 0xffffffffL);
        long stamp = trickster.getGame().getTimestamp();
        stamp = stamp * 31 + trickster.getGame().getPhaseHandler().getTurn();
        stamp = stamp * 31 + String.valueOf(trickster.getGame().getPhaseHandler().getPhase()).hashCode();
        stamp = stamp * 31 + trickster.getCardsIn(ZoneType.Hand).size();
        stamp = stamp * 31 + trickster.getCardsIn(ZoneType.Graveyard).size();
        stamp = stamp * 31 + trickster.getManaPool().totalMana();
        for (final Card c : trickster.getCardsIn(ZoneType.Battlefield)) {
            if (!c.getManaAbilities().isEmpty()) {
                stamp = stamp * 31 + (c.getId() * 2L + (c.isTapped() ? 1 : 0));
            }
        }
        final long[] cache = CACHE.get();
        final int slot = (int) ((key ^ (key >>> 29)) & 7) * 3;
        if (cache[slot] == key && cache[slot + 1] == stamp && cache[slot + 2] > 0) {
            return (int) cache[slot + 2] - 1;
        }
        final int allowance = compute(trickster);
        cache[slot] = key;
        cache[slot + 1] = stamp;
        cache[slot + 2] = allowance + 1;
        return allowance;
    }

    private static int compute(final Player trickster) {
        if (ComputerUtilMana.getAvailableManaEstimate(trickster) <= 0) {
            return 0;
        }
        int allowance = canProduceUntapped(trickster, "G") ? 3 : 2;
        int seen = -1;
        for (final Card c : trickster.getCardsIn(ZoneType.Graveyard)) {
            if (!c.isInstant() && !c.hasKeyword(forge.game.keyword.Keyword.FLASH)) {
                continue;
            }
            for (final SpellAbility sa : c.getSpells()) {
                final int size = trickSize(sa);
                if (size > seen) {
                    seen = size;
                }
            }
        }
        if (seen >= 0) {
            allowance = Math.max(allowance + 1, seen);
        }
        return allowance;
    }

    /**
     * TFR round 1036: the +N power of the biggest team pump this player has shown (an instant or flash in the graveyard
     * whose spell gives the player's own or attacking creatures +N/+X: Inspired Charge, Trumpet Blast), when they still
     * have cards in hand and the mana to cast it again; 0 otherwise. A single-target trick adds its +N to one creature,
     * a team pump to every attacker, so the block half's danger term counts this one on every unblocked attacker.
     * On-board team pumps (Boast, activated PumpAll) need no allowance: the danger checks already count them through
     * {@link ComputerUtilCombat#predictTeamPumpOfAttacker} (round 1024).
     */
    public static int shownTeamPump(final Player trickster) {
        if (trickster == null || trickster.getCardsIn(ZoneType.Hand).isEmpty()) {
            return 0;
        }
        int best = 0;
        int cost = 0;
        for (final Card c : trickster.getCardsIn(ZoneType.Graveyard)) {
            if (!c.isInstant() && !c.hasKeyword(forge.game.keyword.Keyword.FLASH)) {
                continue;
            }
            for (final SpellAbility sa : c.getSpells()) {
                final int size = teamPumpSize(sa);
                if (size > best) {
                    best = size;
                    cost = c.getCMC();
                }
            }
        }
        if (best <= 0 || ComputerUtilMana.getAvailableManaEstimate(trickster) < Math.max(1, cost)) {
            return 0;
        }
        return best;
    }

    /** The +N power of a team pump spell for the caster's own or attacking creatures, or 0. */
    private static int teamPumpSize(final SpellAbility sa) {
        if (sa.getApi() != ApiType.PumpAll || sa.usesTargeting() || sa.isCurse()) {
            return 0;
        }
        final String valid = sa.getParamOrDefault("ValidCards", "");
        if (!valid.startsWith("Creature") || !(valid.contains("YouCtrl") || valid.contains("attacking"))) {
            return 0;
        }
        final String att = sa.getParamOrDefault("NumAtt", "");
        if (!att.startsWith("+")) {
            return 0;
        }
        try {
            return Integer.parseInt(att.substring(1).trim());
        } catch (final NumberFormatException e) {
            return 0; // +X: unknown size
        }
    }

    /** The +N of a creature trick (0 for protection or a counter), or -1 when the spell is no creature trick. */
    private static int trickSize(final SpellAbility sa) {
        if (!sa.usesTargeting() || !sa.getParamOrDefault("ValidTgts", "").startsWith("Creature")) {
            return -1;
        }
        final ApiType api = sa.getApi();
        if (api == ApiType.Pump) {
            final String def = sa.getParamOrDefault("NumDef", "");
            if (def.startsWith("+")) {
                try {
                    return Integer.parseInt(def.substring(1).trim());
                } catch (final NumberFormatException e) {
                    return 0; // +X: a trick of unknown size
                }
            }
            return sa.hasParam("KW") ? 0 : -1;
        }
        if (api == ApiType.PutCounter && "P1P1".equals(sa.getParam("CounterType"))) {
            return 0;
        }
        if (api == ApiType.Protection) {
            return 0;
        }
        return -1;
    }

    private static boolean canProduceUntapped(final Player p, final String color) {
        for (final Card src : p.getCardsIn(ZoneType.Battlefield)) {
            if (src.isTapped()) {
                continue;
            }
            for (final SpellAbility ma : src.getManaAbilities()) {
                ma.setActivatingPlayer(p);
                if (ma.canProduce(color) && ma.canPlay()) {
                    return true;
                }
            }
        }
        return false;
    }
}
