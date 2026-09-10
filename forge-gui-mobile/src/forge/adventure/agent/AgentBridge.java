package forge.adventure.agent;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Round 161 (MOD_SCOPE #117): the agent bridge - a loopback HTTP server that lets something outside
 * the process (Claude in a Claude Code session, or a scripted soak-test driver) SEE the game state
 * and SEND the player's actions, one command at a time, at the pace of a turn-based game.
 * <p>
 * Off unless {@code TFR_AGENT_PORT} (environment) or {@code -Dtfr.agent.port} is set, so players
 * never see it. {@code TFR_AGENT_CHEATS=1} additionally allows console commands and the fog-free
 * state. Nothing here is persisted; the save format is untouched.
 * <p>
 * Threading: the HTTP handlers run on their own thread and never touch scene2d. Every read of game
 * state and every action is marshalled onto the render thread with {@link Gdx#app}.postRunnable and
 * awaited with a bounded timeout. Design: docs/design/2026-09-09-agent-play.md.
 */
public final class AgentBridge {
    private static volatile boolean startAttempted;
    private static volatile AgentBridge instance;

    private final int port;
    private final boolean cheats;
    private HttpServer server;
    volatile boolean autoBattle = true;
    private final Deque<String> notifications = new ArrayDeque<>();
    private final AgentObserver observer = new AgentObserver(this);
    private final WalkController walker = new WalkController(this);
    private final AgentActions actions = new AgentActions(this, observer, walker);
    private volatile CompletableFuture<String> pendingCapture;
    private volatile String pendingCaptureFile;
    long frame;
    private final Deque<String> log = new ArrayDeque<>();

    private AgentBridge(int port, boolean cheats) {
        this.port = port;
        this.cheats = cheats;
    }

    // ------------------------------------------------------------------ hooks (called from stock code)

    /** Called on the first render frame. Cheap after the first call. */
    public static void startIfConfigured() {
        if (startAttempted)
            return;
        startAttempted = true;
        String p = System.getProperty("tfr.agent.port");
        if (p == null || p.isBlank())
            p = System.getenv("TFR_AGENT_PORT");
        if (p == null || p.isBlank())
            return;
        boolean cheats = "1".equals(System.getProperty("tfr.agent.cheats", System.getenv("TFR_AGENT_CHEATS")));
        try {
            AgentBridge b = new AgentBridge(Integer.parseInt(p.trim()), cheats);
            b.start();
            instance = b;
            // A bridge-launched game is an Adventure session: the splash's Classic/Adventure prompt
            // would otherwise sit waiting for a mouse click nobody is there to give. This process only;
            // the saved preference (FPref.UI_SELECTOR_MODE) is untouched.
            forge.Forge.selector = "Adventure";
        } catch (Exception e) {
            System.err.println("[TFR-Agent] bridge NOT started: " + e);
        }
    }

    /** Called at the end of every rendered frame: the only moment the frame buffer is complete. */
    public static void afterRender() {
        AgentBridge b = instance;
        if (b == null)
            return;
        b.frame++;
        try {
            b.walker.frame();
        } catch (Throwable t) {
            b.log("[TFR-Agent] walker.frame failed: " + t);
        }
        CompletableFuture<String> capture = b.pendingCapture;
        if (capture != null) {
            b.pendingCapture = null;
            try {
                int w = Gdx.graphics.getBackBufferWidth(), h = Gdx.graphics.getBackBufferHeight();
                Pixmap pixmap = Pixmap.createFromFrameBuffer(0, 0, w, h);
                FileHandle fh = Gdx.files.absolute(b.pendingCaptureFile);
                PixmapIO.writePNG(fh, pixmap, -1, true);
                pixmap.dispose();
                capture.complete(fh.path());
            } catch (Throwable t) {
                capture.completeExceptionally(t);
            }
        }
    }

    /** The HUD's notifications, so "your guard broke the attack" reaches the agent. */
    public static void noteNotification(String text) {
        AgentBridge b = instance;
        if (b == null || text == null)
            return;
        synchronized (b.notifications) {
            b.notifications.addLast(Jsons.plain(text));
            while (b.notifications.size() > 40)
                b.notifications.removeFirst();
        }
    }

    /** True when the bridge is up and auto-battle is on: the AI pilots the PLAYER's seat in every duel
     *  (equipment, ante, rewards and statistics stay the player's - unlike aiControlsPlayerSide). */
    public static boolean aiPilotsPlayer() {
        AgentBridge b = instance;
        return b != null && b.autoBattle;
    }

    public static boolean isActive() {
        return instance != null;
    }

    // ------------------------------------------------------------------ server

    private void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/state", ex -> handle(ex, () -> onGl(() -> observer.snapshot(cheats && flag(ex, "cheat")), 10)));
        server.createContext("/wait", ex -> handle(ex, () -> waitIdle(ex)));
        server.createContext("/screenshot", ex -> handle(ex, () -> screenshot(param(ex, "file"))));
        server.createContext("/cmd", ex -> handle(ex, () -> command(body(ex))));
        server.createContext("/", ex -> handle(ex, () -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("endpoints", List.of("GET /state[?cheat=1]", "GET /wait?timeout=30", "GET /screenshot[?file=path]", "POST /cmd {cmd:...}"));
            m.put("cheats", cheats);
            return m;
        }));
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tfr-agent-bridge");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        System.out.println("[TFR-Agent] bridge listening on http://127.0.0.1:" + port + (cheats ? " (cheats allowed)" : ""));
    }

    private interface Producer {
        Object produce() throws Exception;
    }

    private void handle(HttpExchange ex, Producer producer) throws IOException {
        int status = 200;
        String json;
        try {
            json = Jsons.write(producer.produce());
        } catch (Throwable t) {
            status = 500;
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            StringBuilder trace = new StringBuilder();
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            for (int i = 0; i < Math.min(6, cause.getStackTrace().length); i++)
                trace.append(cause.getStackTrace()[i]).append("; ");
            err.put("trace", trace.toString());
            json = Jsons.write(err);
            log("[TFR-Agent] " + ex.getRequestURI() + " failed: " + t + " @ " + trace);
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String param(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null)
            return null;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            if (k.equals(name))
                return i < 0 ? "" : java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private static boolean flag(HttpExchange ex, String name) {
        String v = param(ex, name);
        return v != null && !v.equals("0") && !v.equalsIgnoreCase("false");
    }

    // ------------------------------------------------------------------ render-thread marshalling

    /** Run on the render thread and wait for the result (bounded). */
    <T> T onGl(Callable<T> call, int timeoutSeconds) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        Gdx.app.postRunnable(() -> {
            try {
                f.complete(call.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            return f.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
        }
    }

    // ------------------------------------------------------------------ endpoints

    private Object waitIdle(HttpExchange ex) throws Exception {
        String t = param(ex, "timeout");
        int timeout = t == null ? 30 : Integer.parseInt(t);
        long deadline = System.currentTimeMillis() + timeout * 1000L;
        boolean idle = false;
        while (System.currentTimeMillis() < deadline) {
            idle = onGl(observer::isIdle, 10);
            if (idle)
                break;
            Thread.sleep(100);
        }
        Map<String, Object> state = onGl(() -> observer.snapshot(cheats && flag(ex, "cheat")), 10);
        state.put("idle", idle);
        return state;
    }

    private Object screenshot(String file) throws Exception {
        String path = file != null && !file.isBlank() ? file
                : System.getProperty("java.io.tmpdir") + java.io.File.separator + "tfr-agent-" + System.currentTimeMillis() + ".png";
        CompletableFuture<String> f = new CompletableFuture<>();
        pendingCaptureFile = path;
        pendingCapture = f;
        Gdx.graphics.requestRendering();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("file", f.get(10, TimeUnit.SECONDS));
        return m;
    }

    private Object command(String body) throws Exception {
        JsonValue cmd;
        try {
            cmd = new JsonReader().parse(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", "body is not JSON: " + e.getMessage());
            return m;
        }
        String name = cmd.getString("cmd", "");
        int timeout = cmd.getInt("timeout", 120);
        if ("console".equals(name) && !cheats) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", "console commands need TFR_AGENT_CHEATS=1");
            return m;
        }
        CompletableFuture<Map<String, Object>> result = onGl(() -> actions.execute(name, cmd), 20);
        try {
            Map<String, Object> m = result.get(timeout, TimeUnit.SECONDS);
            m.put("cmd", name);
            return m;
        } catch (java.util.concurrent.TimeoutException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("cmd", name);
            m.put("pending", true);
            m.put("message", "still running after " + timeout + "s - poll /wait");
            return m;
        }
    }

    // ------------------------------------------------------------------ shared state for the observer

    /** Bridge-internal log: printed, and kept for /state.agent.log because forge.log is locked while the game runs. */
    void log(String line) {
        System.out.println(line);
        synchronized (log) {
            log.addLast(java.time.LocalTime.now().toString().substring(0, 8) + " " + line);
            while (log.size() > 60)
                log.removeFirst();
        }
    }

    List<String> recentLog() {
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    boolean cheatsAllowed() {
        return cheats;
    }

    List<String> drainNotifications() {
        synchronized (notifications) {
            List<String> out = new ArrayList<>(notifications);
            notifications.clear();
            return out;
        }
    }

    WalkController walker() {
        return walker;
    }
}
