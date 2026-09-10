#!/usr/bin/env python
"""
tfr_agent.py - client for the in-game agent bridge (round 161, docs/design/2026-09-09-agent-play.md).

Start the game with the bridge on (PowerShell, from the live folder):
    $env:TFR_AGENT_PORT = "8765"; $env:TFR_AGENT_CHEATS = "1"; & ".\\The Forsaken Realms.cmd"

Then, from anywhere:
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
import json, sys, urllib.request, urllib.parse

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
        # advancing typing text; stop and print the options when a real choice comes up.
        import time
        for _ in range(30):
            s = call("GET", "/state")
            d = s.get("dialog")
            if not d:
                print("no dialog open;", "scene:", s.get("scene"))
                return 0
            opts = d.get("options") or []
            if not opts:
                if d.get("typing"):
                    call("POST", "/cmd", {"cmd": "advance"})
                time.sleep(1.5)
                continue
            if len(opts) == 1:
                print("->", opts[0]["text"])
                call("POST", "/cmd", {"cmd": "click", "id": opts[0]["id"]})
                time.sleep(2.5)
                continue
            print(json.dumps(d, indent=1))
            return 0
        print("gave up after 30 rounds")
        return 1
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
