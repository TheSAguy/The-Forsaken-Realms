#!/usr/bin/env python
"""
tfr_agent.py - client for the in-game agent bridge (round 161, docs/design/2026-09-09-agent-play.md).

Start the AGENT game (round 175 - isolated from the player's own game: its own folder F:\\FORGE\\TFR-Agent and
its own APPDATA profile, so it never touches the player's saves, settings or log):
    dev-tools\\agent\\agent_launch.cmd [cheats]      (see the tfr-play skill for starting it from Claude Code)
    powershell -File dev-tools\\agent\\agent_stop.ps1   stops it (only the TFR-Agent javaw, never the player's game)

Then, from anywhere:
    python tfr_agent.py boot                      # wait for the bridge and a scene that takes commands
    python tfr_agent.py settle                    # back to an idle map: duel, win/lose view, reward screen, dialogs
    python tfr_agent.py dialogs [choose=TEXT]     # walk single-option dialogs; take the option containing TEXT
    python tfr_agent.py state                     # the observation (add --cheat for the fog-free view)
    python tfr_agent.py state --brief             # scene, player, nearby pois/enemies/actors, dialog, ui
    python tfr_agent.py wait [--timeout 30]       # block until the game is idle, then print the brief state
    python tfr_agent.py shot [file.png]           # screenshot -> path
    python tfr_agent.py cmd goto poi="Waste Town Tribal"
    python tfr_agent.py cmd goto actor=12
    python tfr_agent.py cmd goto tile=120,88
    python tfr_agent.py cmd explore dir=NE tiles=10
    python tfr_agent.py cmd wait days=2
    python tfr_agent.py cmd click id=d1            # a dialog option / UI button id from the last state
    python tfr_agent.py cmd click text="Done"
    python tfr_agent.py cmd leave | back | stop | key key=ESCAPE
    python tfr_agent.py cmd equip item="Torch" | unequip item=... | use item=... | sell item=...
    python tfr_agent.py cmd deck op=list | op=select index=1 | op=add card="Swamp" count=2 | op=remove card=...
    python tfr_agent.py cmd buy index=0
    python tfr_agent.py cmd save slot=4 name="agent" | load slot=4 | newgame
    python tfr_agent.py cmd fasttime on=true | autobattle on=false
    python tfr_agent.py cmd console text="give gold 100"   (needs TFR_AGENT_CHEATS=1)

Values: numbers and true/false are typed automatically; a,b lists become arrays; anything else is a string.
Every command blocks until it finishes (a walk, a wait) or --timeout seconds pass.
"""
import json, sys, time, urllib.request, urllib.parse

PORT = 8765


def call(method, path, body=None, timeout=200):
    url = "http://127.0.0.1:%d%s" % (PORT, path)
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        # the bridge answers 500 with {"ok": false, "error": ..., "trace": ...} - show it, do not raise
        body = e.read().decode("utf-8", "replace")
        try:
            return json.loads(body)
        except ValueError:
            return {"ok": False, "error": "HTTP %d" % e.code, "body": body[:2000]}


def typed(v):
    if v.lower() in ("true", "false"):
        return v.lower() == "true"
    try:
        return int(v)
    except ValueError:
        pass
    try:
        return float(v)
    except ValueError:
        pass
    if "," in v and all(p.strip().lstrip("-").isdigit() for p in v.split(",")):
        return [int(p) for p in v.split(",")]
    return v


def brief(s):
    out = {"scene": s.get("scene"), "idle": s.get("idle"), "frozen": s.get("frozen"), "transition": s.get("transition")}
    p = s.get("player")
    if p:
        out["player"] = {k: p.get(k) for k in ("name", "day", "gold", "shards", "life", "maxLife", "location", "tile")}
        out["player"]["deck"] = (p.get("selectedDeck") or {}).get("name")
        out["items"] = [("*" if i.get("equipped") else "") + i["name"] for i in p.get("items", [])]
    w = s.get("world")
    if w:
        out["pois"] = [(x["name"], x.get("type"), x.get("color"), x["distance"], x["bearing"]) for x in w.get("pois", [])[:15]]
        out["enemies"] = [(e["name"], e["distance"], e["bearing"], e.get("attacking")) for e in w.get("enemies", [])]
    m = s.get("map")
    if m:
        out["map"] = m.get("name")
        out["actors"] = [(a["id"], a["kind"], a.get("label"), a["distance"]) for a in m.get("actors", [])]
    if s.get("dialog"):
        out["dialog"] = s["dialog"]
    if s.get("ui"):
        out["ui"] = [(u["id"], u.get("text") or u.get("name") or u.get("class")) for u in s["ui"]]
    if s.get("shop"):
        out["shop"] = s["shop"]["items"]
    q = s.get("quests")
    if q:
        out["quests"] = [x["name"] for x in q]
    a = s.get("agent") or {}
    if a.get("notifications"):
        out["notifications"] = a["notifications"]
    if a.get("busy"):
        out["busy"] = a.get("action")
    return out


def say(*parts):
    print(time.strftime("%H:%M:%S ") + " ".join(str(p) for p in parts), flush=True)


def walk_dialogs(choose=None, rounds=40):
    """Click through single-option dialogs (advancing typing text). At a real choice: take the option whose
    text contains `choose` when given, else stop and return the state with the dialog still open."""
    s = call("GET", "/state")
    for _ in range(rounds):
        d = s.get("dialog")
        if not d:
            return s
        opts = d.get("options") or []
        if not opts:
            if d.get("typing"):
                call("POST", "/cmd", {"cmd": "advance"})
            time.sleep(1.2)
            s = call("GET", "/state")
            continue
        pick = opts[0]
        if len(opts) > 1:
            if choose is None:
                return s
            pick = next((o for o in opts if choose.lower() in (o.get("text") or "").lower()), None)
            if pick is None:
                return s
            choose = None  # a choice is taken once; later dialogs stop at their own choices
        say("dialog ->", (pick.get("text") or "")[:70])
        call("POST", "/cmd", {"cmd": "click", "id": pick["id"]})
        time.sleep(2.0)
        s = call("GET", "/state")
    return s


def boot(timeout=180):
    """None if the bridge never answers; else the first state whose scene takes commands."""
    end = time.time() + timeout
    while time.time() < end:
        try:
            call("GET", "/", timeout=3)
            break
        except Exception:
            time.sleep(2)
    else:
        return None
    s = {}
    while time.time() < end:
        s = call("GET", "/state")
        if s.get("scene") in ("StartScene", "NewGameScene", "GameScene", "TileMapScene"):
            return s
        time.sleep(2)
    return s


SETTLE_BUTTONS = ("back to adventure", "quit match", "quit", "ok", "continue", "done", "close")


def settle(rounds=40, wait_each=60):
    """Back to an idle GameScene/TileMapScene: sit through a duel (the AI pilots the seat), tap the win/lose
    view's button, press Done on a reward screen, walk single-option dialogs. Returns at a real choice."""
    s = {}
    for i in range(rounds):
        s = call("GET", "/wait?timeout=%d" % wait_each, timeout=wait_each + 15)
        scene = s.get("scene")
        prompts = [b for b in (s.get("forgeUi") or []) if b.get("prompt")]
        if prompts:
            texts = [(b.get("text") or "").lower() for b in prompts]
            # A LOST ante (Use Bronze Coin / Buy Back) is a real decision - never answer it with "OK". The first
            # watched session's settle pressed OK there and gave the card away with three coins in the pack.
            if any("bronze coin" in t or t.startswith("buy back") for t in texts):
                say("ante lost - a real choice, not settled:", [b.get("text") for b in prompts])
                return s
            pick = next((b for b in prompts if (b.get("text") or "").lower() in SETTLE_BUTTONS), None)
            if pick is None:
                say("forge prompt with unknown buttons", [b.get("text") for b in prompts], "- waiting")
                time.sleep(3)
                continue
            say("forge prompt", [b.get("text") for b in prompts], "-> tapping", pick["text"])
            call("POST", "/cmd", {"cmd": "click", "id": pick["id"]})
            time.sleep(2.5)
            continue
        if scene == "DuelScene":
            say("duel in progress, life", (s.get("player") or {}).get("life"))
            continue
        if scene == "RewardScene":
            ui = s.get("ui") or []
            say("reward screen:", [u.get("text") or u.get("name") for u in ui])
            done = next((u for u in ui if (u.get("text") or "").lower() in ("done", "back")), None)
            call("POST", "/cmd", {"cmd": "click", "id": done["id"]} if done else {"cmd": "back"})
            time.sleep(2.5)
            continue
        d = s.get("dialog")
        if d:
            opts = d.get("options") or []
            if len(opts) > 1:
                return s  # a real choice - the caller decides
            s = walk_dialogs()
            if s.get("dialog"):
                return s
            continue
        if s.get("idle") and scene in ("GameScene", "TileMapScene"):
            return s
    return s


def main(argv):
    global PORT
    args = [x for x in argv if not x.startswith("--port=")]
    for x in argv:
        if x.startswith("--port="):
            PORT = int(x.split("=", 1)[1])
    if not args:
        print(__doc__)
        return 2
    verb, rest = args[0], args[1:]
    if verb == "state":
        s = call("GET", "/state" + ("?cheat=1" if "--cheat" in rest else ""))
        print(json.dumps(brief(s) if "--brief" in rest else s, indent=1))
    elif verb == "wait":
        t = 30
        for x in rest:
            if x.startswith("--timeout"):
                t = int(x.split("=", 1)[1]) if "=" in x else int(rest[rest.index(x) + 1])
        s = call("GET", "/wait?timeout=%d" % t, timeout=t + 15)
        print(json.dumps(brief(s), indent=1))
    elif verb == "dialogs":
        # Walk through dialogs that offer exactly one option (story text, "(Continue)", "(Begin)"),
        # advancing typing text; stop and print the options when a real choice comes up - unless
        # choose=TEXT names the option to take (first option whose text contains TEXT).
        choose = next((x.split("=", 1)[1] for x in rest if x.startswith("choose=")), None)
        s = walk_dialogs(choose)
        d = s.get("dialog")
        print(json.dumps(d, indent=1) if d else "no dialog open; scene: %s" % s.get("scene"))
    elif verb == "boot":
        # Round 175: wait for a freshly launched game's bridge, then for a scene that takes commands.
        t = next((int(x.split("=", 1)[1]) for x in rest if x.startswith("--timeout=")), 180)
        s = boot(t)
        if s is None:
            print("the bridge did not answer within %d s - is the agent game running?" % t)
            return 1
        print(json.dumps(brief(s), indent=1))
    elif verb == "settle":
        # Round 175 (the round-161 e2e driver's settle()): back to an idle map scene - sit a duel out,
        # tap the win/lose view's button, Done on a reward screen, walk single-option dialogs. Stops at
        # a real choice and prints it.
        s = settle()
        print(json.dumps(brief(s), indent=1))
    elif verb == "shot":
        q = "?file=" + urllib.parse.quote(rest[0]) if rest else ""
        print(json.dumps(call("GET", "/screenshot" + q)))
    elif verb == "cmd":
        if not rest:
            print("cmd needs a command name")
            return 2
        body = {"cmd": rest[0]}
        for kv in rest[1:]:
            if "=" in kv:
                k, v = kv.split("=", 1)
                body[k] = typed(v)
        r = call("POST", "/cmd", body)
        print(json.dumps(r, indent=1))
        if r.get("ok") is False:
            return 1
    else:
        print("unknown verb", verb)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
