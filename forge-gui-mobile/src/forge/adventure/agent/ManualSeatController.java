package forge.adventure.agent;

import com.badlogic.gdx.utils.JsonValue;
import forge.LobbyPlayer;
import forge.ai.ComputerUtil;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.tuple.ImmutablePair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Round 1023: the player's seat in a MANUAL duel ({@link ManualSeat}). Forge's AI seat, with the player's decisions
 * asked of the bridge instead. In a game copy (the AI's rollouts), on autopilot or with manual mode off, every method
 * is the AI's, unchanged.
 */
final class ManualSeatController extends PlayerControllerAi {
    private final ManualSeat seat;
    /** The bridge's play decision while its spell is being cast (modes, optional costs and targets come from it). */
    private JsonValue casting;
    private SpellAbility castingSa;

    ManualSeatController(Game game, Player p, LobbyPlayer lp, ManualSeat seat) {
        super(game, p, lp);
        this.seat = seat;
    }

    Player player() {
        return player;
    }

    private boolean manual() {
        return seat.waitsFor(this);
    }

    private static boolean is(JsonValue d, String verb) {
        return verb.equals(d.getString("cmd", ""));
    }

    // ------------------------------------------------------------------ priority

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        if (!manual())
            return super.chooseSpellAbilityToPlay();
        List<DuelView.Play> plays = DuelView.legalPlays(player);
        if (plays.isEmpty() || !seat.stopsAt(player)) {
            seat.noteAutoPass();
            return null;
        }
        List<Object> listed = new ArrayList<>();
        for (DuelView.Play p : plays)
            listed.add(DuelView.playMap(p));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("plays", listed);
        extra.put("answer", "play n=<n> [targets=..] [x=..] [modes=..] | pass [until=turn] | concede");
        while (true) {
            JsonValue d = seat.ask(this, "priority", "You have priority", extra);
            if (d == null)
                return super.chooseSpellAbilityToPlay();
            if (is(d, "pass")) {
                seat.passOptions(d, player);
                seat.note(true, "passed");
                return null;
            }
            if (is(d, "concede")) {
                seat.concede(player);
                return null;
            }
            if (!is(d, "play")) {
                seat.reject("at priority: play, pass or concede");
                continue;
            }
            DuelView.Play play = pick(plays, d);
            if (play == null) {
                seat.reject("no legal play " + (d.has("n") ? "n=" + d.getString("n") : "id=" + d.getString("id", "?")) + " - see `plays`");
                continue;
            }
            SpellAbility sa = play.sa;
            if (sa.costHasManaX()) {
                if (!d.has("x")) {
                    seat.reject(play.label() + " has X in its cost: give x=");
                    continue;
                }
                sa.setXManaCostPaid(d.getInt("x"));
            }
            String err = sa.getApi() == ApiType.Charm ? checkModes(sa, d) : checkTargets(sa, d);
            if (err != null) {
                seat.reject(err);
                continue;
            }
            casting = d;
            castingSa = sa;
            return new ArrayList<>(List.of(sa));
        }
    }

    private static DuelView.Play pick(List<DuelView.Play> plays, JsonValue d) {
        if (d.has("n")) {
            int n = d.getInt("n", -1);
            return n >= 0 && n < plays.size() ? plays.get(n) : null;
        }
        if (d.has("id")) {
            int id = d.getInt("id", -1), k = d.getInt("ability", 0);
            for (DuelView.Play p : plays)
                if (p.host.getId() == id && k-- == 0)
                    return p;
        }
        return null;
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        if (castingSa == null || sa != castingSa || !manual())
            return super.playChosenSpellAbility(sa);
        JsonValue d = casting;
        String label = sa.getHostCard().getName();
        try {
            if (sa.isLandAbility()) {
                if (sa.canPlay()) {
                    sa.resolve();
                    seat.note(true, "played land " + label);
                } else {
                    seat.note(false, "could not play land " + label);
                }
                return true;
            }
            final String[] err = {null};
            boolean played = ComputerUtil.handlePlayingSpellAbility(player, sa, root -> err[0] = applyTargets(root, d));
            if (err[0] != null)
                seat.note(false, label + " not cast: " + err[0]);
            else
                seat.note(played, played ? (sa.isSpell() ? "cast " : "activated ") + label + " - on the stack" : label + " was not put on the stack (cost or targets failed)");
            return true; // like the AI: priority comes back either way
        } finally {
            casting = null;
            castingSa = null;
        }
    }

    // ---- targets: targets="12" | "12,opp" (one group) | "12;opp" (one group per targeting step, in order)

    private static List<List<String>> groups(JsonValue d) {
        List<List<String>> out = new ArrayList<>();
        if (d == null || !d.has("targets"))
            return out;
        JsonValue t = d.get("targets");
        if (t.isArray()) {
            List<String> g = new ArrayList<>();
            for (JsonValue v = t.child; v != null; v = v.next)
                g.add(v.asString());
            out.add(g);
            return out;
        }
        for (String grp : t.asString().split(";")) {
            List<String> g = new ArrayList<>();
            for (String s : grp.split(","))
                if (!s.isBlank())
                    g.add(s.trim());
            out.add(g);
        }
        return out;
    }

    static List<String> tokens(JsonValue d, String key) {
        List<String> out = new ArrayList<>();
        if (d == null || !d.has(key))
            return out;
        JsonValue t = d.get(key);
        if (t.isArray()) {
            for (JsonValue v = t.child; v != null; v = v.next)
                out.add(v.asString());
        } else {
            for (String s : t.asString().split(","))
                if (!s.isBlank())
                    out.add(s.trim());
        }
        return out;
    }

    /** A target token as a game object: "me", "opp", a card id, or a spell on the stack by its card's id. */
    private GameObject resolve(String tok, SpellAbility cur) {
        Game game = player.getGame();
        if ("me".equalsIgnoreCase(tok))
            return player;
        if ("opp".equalsIgnoreCase(tok))
            return player.getOpponents().isEmpty() ? null : player.getOpponents().get(0);
        int id;
        try {
            id = Integer.parseInt(tok);
        } catch (NumberFormatException e) {
            return null;
        }
        if (cur.getTargetRestrictions().getZone().contains(ZoneType.Stack)) {
            for (SpellAbilityStackInstance si : game.getStack()) {
                SpellAbility s = si.getSpellAbility();
                if (s != null && s.getHostCard().getId() == id)
                    return s;
            }
        }
        return game.findById(id);
    }

    private String checkTargets(SpellAbility sa, JsonValue d) {
        List<List<String>> gs = groups(d);
        int k = 0;
        for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
            if (!cur.usesTargeting())
                continue;
            List<String> g = k < gs.size() ? gs.get(k) : List.of();
            k++;
            int min = cur.getTargetRestrictions().getMinTargets(cur.getHostCard(), cur);
            int max = cur.getTargetRestrictions().getMaxTargets(cur.getHostCard(), cur);
            if (g.size() < min || g.size() > max)
                return "targeting step " + k + " (" + DuelView.shorten(cur.getDescription(), 60) + ") needs " + min + "-" + max + " targets, got " + g.size()
                        + "; candidates " + DuelView.candidates(cur);
            for (String tok : g) {
                GameObject o = resolve(tok, cur);
                if (o == null || !cur.canTarget(o))
                    return "'" + tok + "' is not a legal target for step " + k + "; candidates " + DuelView.candidates(cur);
            }
        }
        if (k < gs.size())
            return "this play has " + k + " targeting step(s), " + gs.size() + " target groups given";
        return null;
    }

    /** Called by the cast, after the spell is on the stack and its modes are chosen: sets the targets. */
    private String applyTargets(SpellAbility root, JsonValue d) {
        List<List<String>> gs = groups(d);
        int k = 0;
        for (SpellAbility cur = root; cur != null; cur = cur.getSubAbility()) {
            if (!cur.usesTargeting())
                continue;
            List<String> g = k < gs.size() ? gs.get(k) : List.of();
            k++;
            cur.resetTargets();
            for (String tok : g) {
                GameObject o = resolve(tok, cur);
                if (o == null || !cur.canTarget(o))
                    return "'" + tok + "' is not a legal target for step " + k;
                cur.getTargets().add(o);
            }
            if (!cur.isTargetNumberValid())
                return "step " + k + " has the wrong number of targets";
        }
        return null;
    }

    // ---- modes: modes="0,2" (indices into the play's `modes`)

    private String checkModes(SpellAbility sa, JsonValue d) {
        List<AbilitySub> opts = CharmEffect.makePossibleOptions(sa);
        List<String> ms = tokens(d, "modes");
        if (ms.isEmpty())
            return "a modal spell: give modes= (indices of the play's `modes`)";
        for (String m : ms) {
            try {
                int i = Integer.parseInt(m);
                if (i < 0 || i >= opts.size())
                    return "mode " + m + " out of range 0-" + (opts.size() - 1);
            } catch (NumberFormatException e) {
                return "modes are indices: " + m;
            }
        }
        return null;
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        if (!manual())
            return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        List<AbilitySub> all = CharmEffect.makePossibleOptions(sa);
        List<String> ms = casting != null ? tokens(casting, "modes") : List.of();
        if (ms.isEmpty()) {
            // a modal trigger or a mode chosen on resolution: ask
            List<Object> opts = new ArrayList<>();
            for (int i = 0; i < possible.size(); i++) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", i);
                o.put("text", DuelView.shorten(possible.get(i).getDescription(), 120));
                opts.add(o);
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("options", opts);
            extra.put("min", min);
            extra.put("max", num);
            extra.put("answer", "choose ids=<mode indices>");
            while (true) {
                JsonValue d = seat.ask(this, "modes", "Choose modes for " + sa.getHostCard().getName(), extra);
                if (d == null)
                    return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
                List<String> ids = tokens(d, "ids");
                List<AbilitySub> out = new ArrayList<>();
                try {
                    for (String s : ids)
                        out.add(possible.get(Integer.parseInt(s)));
                } catch (RuntimeException e) {
                    seat.reject("ids are mode indices 0-" + (possible.size() - 1));
                    continue;
                }
                if (out.size() < min || out.size() > num) {
                    seat.reject("choose " + min + "-" + num + " modes");
                    continue;
                }
                seat.note(true, "modes " + ids);
                return out;
            }
        }
        List<AbilitySub> out = new ArrayList<>();
        for (String m : ms) {
            AbilitySub want = all.get(Integer.parseInt(m));
            // the same mode object when offered; else match by description (a copy of the spell)
            AbilitySub got = possible.contains(want) ? want : null;
            if (got == null)
                for (AbilitySub p : possible)
                    if (p.getDescription().equals(want.getDescription()))
                        got = p;
            if (got != null)
                out.add(got);
        }
        if (out.size() < min || out.size() > num) {
            seat.note(false, "modes " + ms + " are not " + min + "-" + num + " legal modes; the AI chose");
            return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        }
        return out;
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen, List<OptionalCostValue> optionalCostValues) {
        if (!manual() || casting == null)
            return super.chooseOptionalCosts(chosen, optionalCostValues);
        List<OptionalCostValue> out = new ArrayList<>();
        for (String s : tokens(casting, "optional")) {
            try {
                out.add(optionalCostValues.get(Integer.parseInt(s)));
            } catch (RuntimeException e) {
                seat.note(false, "optional cost " + s + " ignored (0-" + (optionalCostValues.size() - 1) + ")");
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ combat

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        if (!manual()) {
            super.declareAttackers(attacker, combat);
            return;
        }
        List<Card> can = new ArrayList<>();
        for (Card c : player.getCreaturesInPlay())
            if (CombatUtil.canAttack(c))
                can.add(c);
        if (can.isEmpty())
            return;
        List<Object> defenders = new ArrayList<>();
        for (GameEntity e : combat.getDefenders())
            defenders.add(DuelView.entity(e, player) + (e instanceof Card c ? " (" + c.getName() + ")" : ""));
        List<Object> opts = new ArrayList<>();
        for (Card c : can)
            opts.add(DuelView.cardMap(c, true));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("attackers", opts);
        extra.put("defenders", defenders);
        extra.put("answer", "attack ids=<ids> [at=opp|<planeswalker id>] | attack pairs=\"id:opp,id:55\" | pass (no attack) | concede");
        while (true) {
            JsonValue d = seat.ask(this, "attack", "Declare attackers", extra);
            if (d == null) {
                super.declareAttackers(attacker, combat);
                return;
            }
            if (is(d, "concede")) {
                seat.concede(player);
                return;
            }
            if (is(d, "pass")) {
                combat.clearAttackers();
                seat.note(true, "no attack");
                return;
            }
            if (!is(d, "attack")) {
                seat.reject("at declare attackers: attack, pass or concede");
                continue;
            }
            combat.clearAttackers();
            String err = null;
            List<String> pairs = tokens(d, "pairs");
            if (pairs.isEmpty())
                for (String id : tokens(d, "ids"))
                    pairs.add(id + ":" + d.getString("at", "opp"));
            List<String> declared = new ArrayList<>();
            for (String pr : pairs) {
                String[] ab = pr.split(":");
                Card c = card(ab[0]);
                GameEntity def = defender(ab.length > 1 ? ab[1] : "opp", combat);
                if (c == null || !can.contains(c)) {
                    err = ab[0] + " cannot attack";
                    break;
                }
                if (def == null || !CombatUtil.canAttack(c, def)) {
                    err = c.getName() + " cannot attack " + (ab.length > 1 ? ab[1] : "opp");
                    break;
                }
                combat.addAttacker(c, def);
                declared.add(c.getName() + "#" + c.getId());
            }
            if (err == null && !CombatUtil.validateAttackers(combat))
                err = "that attack breaks an attack requirement or restriction (must attack, can't attack alone...)";
            if (err != null) {
                combat.clearAttackers();
                seat.reject(err);
                continue;
            }
            seat.note(true, declared.isEmpty() ? "no attack" : "attacking with " + declared);
            return;
        }
    }

    private GameEntity defender(String tok, Combat combat) {
        for (GameEntity e : combat.getDefenders()) {
            if (e instanceof Player p && ("opp".equalsIgnoreCase(tok) ? p != player : ("p" + p.getId()).equals(tok)))
                return e;
            if (e instanceof Card c && String.valueOf(c.getId()).equals(tok))
                return e;
        }
        return null;
    }

    private Card card(String tok) {
        try {
            return player.getGame().findById(Integer.parseInt(tok.trim()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        if (!manual()) {
            super.declareBlockers(defender, combat);
            return;
        }
        List<Card> attackers = new ArrayList<>(combat.getAttackers());
        List<Card> blockers = new ArrayList<>();
        for (Card c : defender.getCreaturesInPlay())
            if (CombatUtil.canBlock(c, combat))
                blockers.add(c);
        if (attackers.isEmpty() || blockers.isEmpty())
            return;
        List<Object> atk = new ArrayList<>();
        for (Card a : attackers) {
            Map<String, Object> m = DuelView.cardMap(a, true);
            GameEntity at = combat.getDefenderByAttacker(a);
            m.put("at", DuelView.entity(at, player));
            atk.add(m);
        }
        List<Object> blk = new ArrayList<>();
        for (Card b : blockers) {
            Map<String, Object> m = DuelView.cardMap(b, true);
            List<Integer> canBlock = new ArrayList<>();
            for (Card a : attackers)
                if (CombatUtil.canBlock(a, b, combat))
                    canBlock.add(a.getId());
            m.put("canBlock", canBlock);
            blk.add(m);
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("attackers", atk);
        extra.put("blockers", blk);
        extra.put("answer", "block pairs=\"blocker:attacker,blocker:attacker\" (two blockers on one attacker is fine) | pass (no blocks) | concede");
        while (true) {
            JsonValue d = seat.ask(this, "block", "Declare blockers", extra);
            if (d == null) {
                super.declareBlockers(defender, combat);
                return;
            }
            if (is(d, "concede")) {
                seat.concede(player);
                return;
            }
            if (is(d, "pass")) {
                seat.note(true, "no blocks");
                return;
            }
            if (!is(d, "block")) {
                seat.reject("at declare blockers: block, pass or concede");
                continue;
            }
            String err = null;
            List<String> done = new ArrayList<>();
            List<Card> placed = new ArrayList<>();
            for (String pr : tokens(d, "pairs")) {
                String[] ba = pr.split(":");
                Card b = card(ba[0]), a = ba.length > 1 ? card(ba[1]) : null;
                if (b == null || !blockers.contains(b) || a == null || !attackers.contains(a)) {
                    err = "bad pair " + pr + " (blocker:attacker ids)";
                    break;
                }
                if (!CombatUtil.canBlock(a, b, combat)) {
                    err = b.getName() + " cannot block " + a.getName();
                    break;
                }
                combat.addBlocker(a, b);
                placed.add(b);
                done.add(b.getName() + "#" + b.getId() + " blocks " + a.getName() + "#" + a.getId());
            }
            if (err == null) {
                String v = CombatUtil.validateBlocks(combat, defender);
                if (v != null)
                    err = "illegal blocks: " + v;
            }
            if (err != null) {
                for (Card b : placed)
                    combat.undoBlockingAssignment(b);
                seat.reject(err);
                continue;
            }
            seat.note(true, done.isEmpty() ? "no blocks" : String.join("; ", done));
            return;
        }
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        if (!manual() || blockers.size() < 2)
            return super.orderBlockers(attacker, blockers);
        List<Card> r = chooseFrom("order", "Damage order of the blockers of " + attacker.getName() + " (first takes damage first)",
                blockers, blockers.size(), blockers.size(), () -> super.orderBlockers(attacker, blockers));
        return new CardCollection(r);
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        if (!manual() || attackers.size() < 2)
            return super.orderAttackers(blocker, attackers);
        List<Card> r = chooseFrom("order", "Damage order of the attackers " + blocker.getName() + " blocks",
                attackers, attackers.size(), attackers.size(), () -> super.orderAttackers(blocker, attackers));
        return new CardCollection(r);
    }

    // ------------------------------------------------------------------ mulligan, discard, sacrifice, scry

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        if (!manual())
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("onThePlay", firstPlayer == player);
        extra.put("bottomIfKept", cardsToReturn);
        extra.put("answer", "keep | mulligan | concede");
        while (true) {
            JsonValue d = seat.ask(this, "mulligan", "Keep this hand?", extra);
            if (d == null)
                return super.mulliganKeepHand(firstPlayer, cardsToReturn);
            if (is(d, "keep") || (is(d, "choose") && d.getBoolean("yes", true))) {
                seat.note(true, "kept");
                return true;
            }
            if (is(d, "mulligan") || is(d, "choose")) {
                seat.note(true, "mulligan");
                return false;
            }
            if (is(d, "concede")) {
                seat.concede(player);
                return true;
            }
            seat.reject("keep or mulligan");
        }
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        if (!manual())
            return super.tuckCardsViaMulligan(hand, cardsToReturn);
        return new CardCollection(chooseFrom("bottom", "Put " + cardsToReturn + " card(s) on the bottom", hand, cardsToReturn, cardsToReturn,
                () -> new ArrayList<>(super.tuckCardsViaMulligan(hand, cardsToReturn))));
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) {
        if (!manual())
            return super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser);
        return new CardCollection(chooseFrom("discard", (p == player ? "Discard " : "Choose for " + p.getName() + " to discard ") + min + "-" + max
                + (sa == null ? "" : " (" + sa.getHostCard().getName() + ")"), validCards, min, max,
                () -> super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser)));
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        if (!manual())
            return super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
        CardCollectionView hand = player.getCardsIn(ZoneType.Hand);
        return new CardCollection(chooseFrom("discard", "Discard " + numDiscard + " to hand size", hand, numDiscard, numDiscard,
                () -> new ArrayList<>(super.chooseCardsToDiscardToMaximumHandSize(numDiscard))));
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        if (!manual())
            return super.choosePermanentsToSacrifice(sa, min, max, validTargets, message);
        return new CardCollection(chooseFrom("sacrifice", "Sacrifice " + min + "-" + max + (sa == null ? "" : " (" + sa.getHostCard().getName() + ")"),
                validTargets, min, max, () -> new ArrayList<>(super.choosePermanentsToSacrifice(sa, min, max, validTargets, message))));
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        if (!manual())
            return super.arrangeForScry(topN);
        List<Card> bottom = chooseFrom("scry", "Scry " + topN.size() + ": choose the cards to put on the BOTTOM (the rest stay on top in this order)",
                topN, 0, topN.size(), () -> new ArrayList<>(super.arrangeForScry(topN).getRight()));
        CardCollection top = new CardCollection(topN);
        top.removeAll(bottom);
        return ImmutablePair.of(top, new CardCollection(bottom));
    }

    // ------------------------------------------------------------------ choices made by effects

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        if (!manual())
            return super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
        return new CardCollection(chooseFrom("choose", title(title, sa), sourceList, isOptional ? 0 : min, max,
                () -> new ArrayList<>(super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params))));
    }

    /** A dig that takes up to one card of each kind (Gift of the Gargantuan: a creature and/or a land). Round 1023 follow-up. */
    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) {
        if (!manual())
            return super.chooseCardsForEffectMultiple(validMap, sa, title, isOptional);
        CardCollection chosen = new CardCollection();
        for (Map.Entry<String, CardCollection> e : validMap.entrySet()) {
            CardCollection left = new CardCollection(e.getValue());
            left.removeAll(chosen);
            if (left.isEmpty())
                continue;
            chosen.addAll(chooseFrom("choose", title(title, sa) + " - " + e.getKey() + " (up to one)", left, 0, 1,
                    () -> {
                        Card best = forge.ai.ComputerUtilCard.getBestAI(left);
                        return best == null ? new ArrayList<Card>() : new ArrayList<>(List.of(best));
                    }));
        }
        return chosen;
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player targetedPlayer, Map<String, Object> params) {
        if (!manual() || optionList.isEmpty())
            return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
        List<T> r = chooseFrom("choose", title(title, sa), optionList, isOptional ? 0 : 1, 1, () -> {
            T t = super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
            return t == null ? new ArrayList<>() : new ArrayList<>(List.of(t));
        });
        return r.isEmpty() ? null : r.get(0);
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title, Player targetedPlayer, Map<String, Object> params) {
        if (!manual() || optionList.isEmpty())
            return super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, targetedPlayer, params);
        return chooseFrom("choose", title(title, sa), optionList, min, max,
                () -> super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, targetedPlayer, params));
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow, Map<String, Object> params) {
        if (!manual())
            return super.confirmAction(sa, mode, message, options, cardToShow, params);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("message", DuelView.shorten(message, 300));
        if (options != null && !options.isEmpty())
            extra.put("options", options);
        if (sa != null)
            extra.put("source", sa.getHostCard().getName() + "#" + sa.getHostCard().getId());
        extra.put("answer", "choose yes=true | choose yes=false");
        while (true) {
            JsonValue d = seat.ask(this, "confirm", "Confirm", extra);
            if (d == null)
                return super.confirmAction(sa, mode, message, options, cardToShow, params);
            if (is(d, "concede")) {
                seat.concede(player);
                return false;
            }
            if (is(d, "choose") && d.has("yes")) {
                boolean y = d.getBoolean("yes");
                seat.note(true, (y ? "yes: " : "no: ") + DuelView.shorten(message, 80));
                return y;
            }
            seat.reject("answer with choose yes=true or yes=false");
        }
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        if (!manual() || min == max)
            return super.chooseNumber(sa, title, min, max);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("min", min);
        extra.put("max", max);
        extra.put("answer", "choose n=<number>");
        while (true) {
            JsonValue d = seat.ask(this, "number", title(title, sa), extra);
            if (d == null)
                return super.chooseNumber(sa, title, min, max);
            if (is(d, "concede")) {
                seat.concede(player);
                return min;
            }
            int n = d.getInt("n", Integer.MIN_VALUE);
            if (is(d, "choose") && n >= min && n <= max) {
                seat.note(true, "number " + n);
                return n;
            }
            seat.reject("choose n= between " + min + " and " + max);
        }
    }

    private static String title(String title, SpellAbility sa) {
        String t = title == null || title.isBlank() ? "Choose" : title;
        return sa == null ? t : t + " (" + sa.getHostCard().getName() + ")";
    }

    /**
     * One list choice: publishes the options (cards and players as tokens), takes `choose ids=...` with min..max of
     * them in the given order. `concede` concedes and lets the AI fill this last choice.
     */
    private <T extends GameObject> List<T> chooseFrom(String kind, String title, Collection<T> options, int min, int max, Supplier<List<T>> ai) {
        List<T> opts = new ArrayList<>(options);
        max = Math.min(max, opts.size());
        min = Math.min(min, max);
        List<Object> listed = new ArrayList<>();
        for (T o : opts)
            listed.add(DuelView.option(o, player));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("options", listed);
        extra.put("min", min);
        extra.put("max", max);
        extra.put("answer", "choose ids=<ids, in order>" + (min == 0 ? " (none: choose ids=)" : ""));
        while (true) {
            JsonValue d = seat.ask(this, kind, title, extra);
            if (d == null)
                return ai.get();
            if (is(d, "concede")) {
                seat.concede(player);
                return ai.get();
            }
            if (!is(d, "choose")) {
                seat.reject("answer with choose ids=...");
                continue;
            }
            List<T> out = new ArrayList<>();
            String err = null;
            for (String tok : tokens(d, "ids")) {
                T hit = null;
                for (T o : opts)
                    if (String.valueOf(DuelView.entity(o, player)).equalsIgnoreCase(tok))
                        hit = o;
                if (hit == null || out.contains(hit)) {
                    err = "'" + tok + "' is not one of the options (or given twice)";
                    break;
                }
                out.add(hit);
            }
            if (err == null && (out.size() < min || out.size() > max))
                err = "choose " + (min == max ? String.valueOf(min) : min + "-" + max) + ", got " + out.size();
            if (err != null) {
                seat.reject(err);
                continue;
            }
            List<String> names = new ArrayList<>();
            for (T o : out)
                names.add(o instanceof Card c ? c.getName() + "#" + c.getId() : String.valueOf(o));
            seat.note(true, kind + ": " + names);
            return out;
        }
    }
}
