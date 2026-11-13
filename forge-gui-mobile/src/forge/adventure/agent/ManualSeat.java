package forge.adventure.agent;

import com.badlogic.gdx.utils.JsonValue;
import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Round 1023: the MANUAL seat - the agent (Claude) plays the player's side of a duel decision by decision, against
 * Forge's AI, to find what a human exploits. Training and diagnosis only, never live play.
 * <p>
 * Off unless the bridge is up AND a client sent {@code duelmode mode=manual}: then every duel the bridge's auto-battle
 * would pilot gets a {@link ManualSeatController} instead of the AI. That controller is Forge's AI seat with the
 * player's decisions (priority, attacks, blocks, mulligan, discard, sacrifice, scry, choices, numbers, confirms) routed
 * here: the GAME thread publishes a prompt with a state snapshot ({@link DuelView}) and waits; a bridge verb
 * ({@code play}, {@code pass}, {@code attack}, {@code block}, {@code choose}, {@code keep}, {@code mulligan},
 * {@code concede}) hands it a decision and waits for the next prompt. Everything not routed (mana payment, trigger
 * targets, damage assignment, rare choices) stays the AI's. The AI's own turns run at the duel's 50x speed meanwhile.
 * <p>
 * A prompt nobody answers for {@link #fallbackMinutes} minutes goes to the AI, so a forgotten duel cannot hang the
 * game forever. {@code [TFR-ManualSeat]} lines log every prompt and answer.
 */
final class ManualSeat {
    private final AgentBridge bridge;
    private final Object lock = new Object();

    // ---- configuration (bridge thread writes, game thread reads)
    volatile boolean manual;
    /** The one duel the AI finishes (`duelmode autopilot=true`); the next duel waits for the bridge again. */
    private volatile Game autopilotGame;
    volatile boolean stopAll;
    volatile int fallbackMinutes = 30;
    /** Empty-stack priority stops are skipped until this turn number (a "pass until=turn"). */
    private volatile int skipStopsUntilTurn;

    // ---- the duel being played (game thread)
    private volatile ManualSeatController controller;
    private final int[] logMark = {0};
    private Set<String> seen = DuelView.newSeen();
    private int decisions, autoPasses;
    private long duelStart;

    // ---- the handoff (guarded by lock)
    private Map<String, Object> pending;
    private long seq;
    private JsonValue decision;
    private boolean resultOk = true;
    private final List<String> result = new ArrayList<>();

    ManualSeat(AgentBridge bridge) {
        this.bridge = bridge;
    }

    // ------------------------------------------------------------------ the seat (DuelScene through AgentBridge)

    /** The lobby player of a manual duel; its in-game controller waits for the bridge. */
    LobbyPlayer lobbyPlayer(LobbyPlayer ai) {
        Lobby lp = new Lobby(ai.getName(), this);
        if (ai instanceof LobbyPlayerAi a)
            lp.setAiProfile(a.getAiProfile());
        return lp;
    }

    static final class Lobby extends LobbyPlayerAi {
        private final ManualSeat seat;

        Lobby(String name, ManualSeat seat) {
            super(name, null);
            this.seat = seat;
        }

        @Override
        public Player createIngamePlayer(Game game, int id) {
            Player p = super.createIngamePlayer(game, id);
            // Game copies (the AI's rollouts) reuse this lobby player; their controller never waits (see manual()).
            p.dangerouslySetController(new ManualSeatController(game, p, this, seat));
            return p;
        }
    }

    /** The first manual decision of a real (not copied) game binds its controller: a new duel starts fresh. */
    void bind(ManualSeatController c) {
        if (controller == c)
            return;
        controller = c;
        logMark[0] = 0;
        seen = DuelView.newSeen();
        decisions = 0;
        autoPasses = 0;
        duelStart = System.currentTimeMillis();
        skipStopsUntilTurn = 0;
        bridge.log("[TFR-ManualSeat] duel bound: " + c.player().getName() + " vs "
                + c.player().getOpponents().get(0).getName() + " (manual seat; AI turns at the duel's speed)");
    }

    boolean waitsFor(ManualSeatController c) {
        Game g = c.player().getGame();
        return manual && g != autopilotGame && !g.isSimulationCopy();
    }

    // ------------------------------------------------------------------ priority stops

    /** Whether an empty-or-not stack priority with plays available is worth a decision. */
    boolean stopsAt(Player me) {
        Game game = me.getGame();
        PhaseHandler ph = game.getPhaseHandler();
        if (!game.getStack().isEmpty()) {
            SpellAbility top = game.getStack().peekAbility();
            // our own spell or ability on top: let it resolve (a human taps "OK"), unless every stop is wanted
            return stopAll || top == null || top.getActivatingPlayer() != me;
        }
        if (stopAll)
            return true;
        if (skipStopsUntilTurn > 0 && ph.getTurn() < skipStopsUntilTurn)
            return false;
        PhaseType phase = ph.getPhase();
        if (ph.isPlayerTurn(me))
            return phase == PhaseType.MAIN1 || phase == PhaseType.MAIN2 || phase == PhaseType.COMBAT_DECLARE_BLOCKERS;
        if (phase == PhaseType.COMBAT_DECLARE_ATTACKERS || phase == PhaseType.COMBAT_DECLARE_BLOCKERS)
            return ph.getCombat() != null && !ph.getCombat().getAttackers().isEmpty(); // no stop when nothing attacks
        return phase == PhaseType.END_OF_TURN;
    }

    void noteAutoPass() {
        autoPasses++;
    }

    void passOptions(JsonValue d, Player me) {
        String until = d.getString("until", "");
        if ("turn".equalsIgnoreCase(until) || "next".equalsIgnoreCase(until))
            skipStopsUntilTurn = me.getGame().getPhaseHandler().getTurn() + 1;
    }

    // ------------------------------------------------------------------ game thread: ask and answer

    /**
     * Publishes a prompt and waits for the bridge's decision. Returns null when the AI should decide instead: autopilot,
     * or nobody answered within {@link #fallbackMinutes}.
     */
    JsonValue ask(ManualSeatController c, String kind, String title, Map<String, Object> extra) {
        bind(c);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", kind);
        p.put("title", title);
        if (extra != null)
            p.putAll(extra);
        p.put("state", DuelView.state(c.player(), logMark, seen));
        long deadline = System.currentTimeMillis() + fallbackMinutes * 60_000L;
        synchronized (lock) {
            pending = p;
            seq++;
            decision = null;
            lock.notifyAll();
            try {
                while (decision == null) {
                    if (!manual || c.player().getGame() == autopilotGame) {
                        pending = null;
                        return null;
                    }
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        bridge.log("[TFR-ManualSeat] no answer to the " + kind + " prompt in " + fallbackMinutes + " min - the AI decides");
                        pending = null;
                        return null;
                    }
                    lock.wait(Math.min(left, 1000));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pending = null;
                return null;
            }
            JsonValue d = decision;
            decision = null;
            pending = null;
            decisions++;
            resultOk = true;
            result.clear();
            bridge.log("[TFR-ManualSeat] " + kind + " <- " + d.toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.minimal));
            return d;
        }
    }

    /** The decision was not usable; the caller asks again (the bridge returns this message with the same prompt). */
    void reject(String why) {
        synchronized (lock) {
            resultOk = false;
            result.add(why);
        }
        bridge.log("[TFR-ManualSeat] rejected: " + why);
    }

    /** What the decision did (a cast that went on the stack or failed, an attack declared...). */
    void note(boolean ok, String what) {
        synchronized (lock) {
            if (!ok)
                resultOk = false;
            result.add(what);
        }
        bridge.log("[TFR-ManualSeat] " + (ok ? "" : "FAILED ") + what);
    }

    void concede(Player me) {
        note(true, "conceded");
        me.concede();
        me.getGame().getAction().checkGameOverCondition();
    }

    // ------------------------------------------------------------------ bridge thread

    static final Set<String> VERBS = Set.of("play", "pass", "attack", "block", "choose", "keep", "mulligan", "concede", "order");

    /** GET /duel and the `duel` command: the waiting prompt (with its state), or what the duel is doing. */
    Map<String, Object> read() {
        synchronized (lock) {
            Map<String, Object> m = status();
            if (pending != null)
                m.put("prompt", pending);
            return m;
        }
    }

    private Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        ManualSeatController c = controller;
        boolean onAutopilot = c != null && c.player().getGame() == autopilotGame;
        m.put("mode", manual ? (onAutopilot ? "manual (autopilot: the AI finishes this duel)" : "manual") : "auto");
        if (c == null) {
            m.put("duel", "none yet");
            return m;
        }
        Game g = c.player().getGame();
        if (!manual)
            m.put("duel", "the last manual duel (auto mode now)");
        m.put("decisions", decisions);
        m.put("autoPasses", autoPasses);
        m.put("minutes", Math.round((System.currentTimeMillis() - duelStart) / 6000.0) / 10.0);
        if (g.isGameOver()) {
            m.put("gameOver", true);
            GameOutcome o = g.getOutcome();
            m.put("winner", o == null || o.getWinningLobbyPlayer() == null ? "draw" : o.getWinningLobbyPlayer().getName());
            m.put("turns", o == null ? g.getPhaseHandler().getTurn() : o.getLastTurnNumber());
            m.put("next", "the duel is over - `tfr_agent.py settle` takes the win/lose screen and the rewards");
        } else {
            m.put("waiting", pending != null ? "for you" : "the game is running (the AI's turn or a resolving stack)");
        }
        return m;
    }

    Map<String, Object> config(JsonValue a) {
        String mode = a.getString("mode", "");
        if ("manual".equalsIgnoreCase(mode)) {
            manual = true;
            bridge.autoBattle = true; // the manual seat is a kind of auto-battle seat: the player's fight, an AI shell
        } else if ("auto".equalsIgnoreCase(mode)) {
            manual = false;
        }
        if (a.has("stops"))
            stopAll = "all".equalsIgnoreCase(a.getString("stops", ""));
        if (a.has("fallback"))
            fallbackMinutes = Math.max(1, a.getInt("fallback", 30));
        if (a.getBoolean("autopilot", false)) {
            ManualSeatController c = controller;
            autopilotGame = c == null ? null : c.player().getGame(); // only the duel in progress; the next one is manual
        }
        synchronized (lock) {
            lock.notifyAll(); // a waiting prompt sees autopilot / auto at once
        }
        Map<String, Object> m = status();
        m.put("stops", stopAll ? "all" : "default (own main phases and after blocks; the opponent's attacks, blocks and end step; any stack you can answer)");
        m.put("fallbackMinutes", fallbackMinutes);
        bridge.log("[TFR-ManualSeat] duelmode " + m.get("mode") + ", stops " + (stopAll ? "all" : "default") + ", fallback " + fallbackMinutes + " min");
        return m;
    }

    /** A decision verb: hand it to the waiting game thread, then wait for its next prompt, the game's end or the timeout. */
    Map<String, Object> submit(String verb, JsonValue cmd, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        synchronized (lock) {
            if (pending == null) {
                Map<String, Object> m = status();
                m.put("ok", false);
                m.put("error", "no decision is waiting" + (controller == null ? " (no manual duel has started; `duelmode mode=manual`, then start a fight)" : ""));
                return m;
            }
            // a verb meant for another prompt (a blind `pass` landing on the attack prompt) is refused, not consumed
            String expect = cmd.getString("expect", "");
            if (!expect.isEmpty() && !expect.equalsIgnoreCase(String.valueOf(pending.get("kind")))) {
                Map<String, Object> m = status();
                m.put("ok", false);
                m.put("error", verb + " expected a " + expect + " prompt; the waiting prompt is " + pending.get("kind") + " - nothing was sent");
                m.put("prompt", pending);
                return m;
            }
            // turn=N (round 1023 follow-up): two priority prompts look alike, so a chained `pass` meant for turn 7's main phase 2
            // once passed turn 9's main phase 1. With turn= the verb is refused on any other turn.
            if (cmd.has("turn") && pending.get("state") instanceof Map<?, ?> st
                    && !String.valueOf(st.get("turn")).equals(cmd.getString("turn", ""))) {
                Map<String, Object> m = status();
                m.put("ok", false);
                m.put("error", verb + " was meant for turn " + cmd.getString("turn", "") + "; the waiting prompt is on turn " + st.get("turn") + " - nothing was sent");
                m.put("prompt", pending);
                return m;
            }
            long s = seq;
            JsonValue d = cmd; // the request body: {cmd: verb, ...}
            decision = d;
            lock.notifyAll();
            while (seq == s) {
                ManualSeatController c = controller;
                if (c != null && c.player().getGame().isGameOver())
                    break;
                long left = deadline - System.currentTimeMillis();
                if (left <= 0)
                    break;
                lock.wait(Math.min(left, 200));
            }
            Map<String, Object> m = status();
            m.put("ok", resultOk);
            m.put("result", new ArrayList<>(result));
            if (seq != s && pending != null)
                m.put("prompt", pending);
            else if (!m.containsKey("gameOver"))
                m.put("pending", "no new prompt within " + timeoutSeconds + " s - read `duel` again");
            return m;
        }
    }
}
