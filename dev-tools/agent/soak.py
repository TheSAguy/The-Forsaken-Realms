"""soak.py - drive the agent game for hours and write down everything that looks wrong (round 275).

The `tfr-play` loop is read state -> decide ONE action -> settle, which is right for judging a feature and far too
slow for a soak: a five-hour stress test is thousands of actions. This is the same loop with a fixed policy,
running unattended, keeping a compact journal and a tally of every failure mode it meets.

The policy plays like a careful player rather than optimally, because the point is coverage, not score:

  in a dungeon/town map   fight the nearest enemy, then collect the nearest reward, then leave
  on the world map       visit the nearest point of interest not visited recently; every few legs, pass a day
  always                 settle after each action; on a real choice, take the first option and record it

Passing days is deliberate and is where most of the value is: a day tick runs territory expansion, mage dispatch,
dungeon rotation (which forces a full minimap re-bake) and the quest clocks - the systems rounds 247-275 touched.

usage: python dev-tools/agent/soak.py --minutes 300 [--journal FILE]
"""
import argparse
import collections
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
CLIENT = os.path.join(HERE, "tfr_agent.py")


def call(*args, timeout=600):
    """Run the agent client and return its parsed JSON, or {'ok': False, 'message': ...}."""
    try:
        p = subprocess.run([sys.executable, CLIENT] + list(args), capture_output=True, text=True,
                           timeout=timeout, cwd=os.path.join(HERE, "..", ".."))
    except subprocess.TimeoutExpired:
        return {"ok": False, "message": "client timeout after %ds" % timeout, "_timeout": True}
    out = (p.stdout or "").strip()
    if not out:
        return {"ok": False, "message": "no output (rc=%d) %s" % (p.returncode, (p.stderr or "")[:200])}
    try:
        return json.loads(out)
    except ValueError:
        start = out.find("{")
        if start >= 0:
            try:
                return json.loads(out[start:])
            except ValueError:
                pass
        return {"ok": False, "message": "unparsable: " + out[:200]}


def state():
    return call("state", "--brief", timeout=180)


def cmd(name, **kw):
    return call("cmd", name, *["%s=%s" % (k, v) for k, v in kw.items()])


def settle():
    return call("settle", timeout=900)


class Journal:
    def __init__(self, path):
        self.path = path
        self.fh = open(path, "a", encoding="utf-8", newline="\n")
        self.counts = collections.Counter()
        self.problems = []

    def say(self, kind, text):
        self.counts[kind] += 1
        line = "%s  %-14s %s" % (time.strftime("%H:%M:%S"), kind, text)
        self.fh.write(line + "\n")
        self.fh.flush()
        print(line, flush=True)

    def problem(self, text):
        self.problems.append("%s  %s" % (time.strftime("%H:%M:%S"), text))
        self.say("PROBLEM", text)


# settle() deliberately stops at a REAL choice and prints it - the skill says so - so something has to
# choose. The first long run livelocked here for twenty-five minutes: the player lost a duel, Forge put up
# OK / "Use Bronze Coin" / "Buy Back (500 gold)", and the driver answered every settle with another settle.
#
# The policy is "keep playing, spend nothing": take the option that just acknowledges, never one that burns
# the save's gold, shards or items. A soak that pays 500 gold per death would be measuring the wallet.
PREFERRED = ("ok", "done", "continue", "back to adventure", "close", "leave", "no", "decline", "cancel")
SPENDS = ("gold", "shard", "coin", "buy", "use ", "pay", "purchase", "spend")


def choose(options):
    """-> (id, text) for the option to take, or None. options are [id, text, ...] rows."""
    rows = []
    for o in options or []:
        if isinstance(o, dict):
            rows.append((o.get("id"), str(o.get("text") or "")))
        elif isinstance(o, (list, tuple)) and len(o) >= 2:
            rows.append((o[0], str(o[1] or "")))
    if not rows:
        return None
    free = [r for r in rows if not any(w in r[1].lower() for w in SPENDS)]
    pool = free or rows
    for want in PREFERRED:
        for r in pool:
            if r[1].strip().lower() == want:
                return r
    for want in PREFERRED:
        for r in pool:
            if want in r[1].strip().lower():
                return r
    return pool[0]


def nearest(actors, kind, skip=()):
    best = None
    for a in actors or []:
        if len(a) < 4 or a[1] != kind or a[3] is None:
            continue
        if a[0] in skip:
            continue
        if best is None or a[3] < best[3]:
            best = a
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=float, default=300)
    ap.add_argument("--journal", default=os.path.join(HERE, "soak_journal.txt"))
    args = ap.parse_args()

    j = Journal(args.journal)
    deadline = time.time() + args.minutes * 60
    j.say("start", "soak for %.0f minutes, journal %s" % (args.minutes, args.journal))

    last_day = None
    last_life = None
    visited = collections.deque(maxlen=6)
    legs_since_day = 0
    consecutive_failures = 0
    peak_gold = 0
    stuck_enemies = {}
    giveups = set()
    unknown_scenes = {}
    # Nothing above can prove the loop is making progress, and the first run proved it can fail to: the
    # state was byte-identical for twenty-five minutes while the driver span. This is the backstop.
    last_fingerprint = None
    same_count = 0
    walk_failures = 0
    escapes = 0
    duels = 0
    days_passed = 0

    while time.time() < deadline:
        s = state()
        if not s or "player" not in s:
            consecutive_failures += 1
            # An all-null state means the bridge answered but the game is not there: either mid-transition,
            # or GONE. The bridge's HttpServer dispatcher thread is not a daemon, so an exited game leaves a
            # JVM that keeps answering - the difference is that a transition clears within a second or two.
            if all(s.get(k) is None for k in ("scene", "idle", "frozen", "transition")):
                j.problem("empty state - the game has exited or is mid-transition (try %d of 3)"
                          % consecutive_failures)
            else:
                j.problem("state unreadable: %s" % str(s)[:200])
            if consecutive_failures >= 3:
                j.problem("the game is gone - stopping rather than hammering a dead bridge")
                break
            time.sleep(5)
            continue
        consecutive_failures = 0
        p = s["player"]
        scene = s.get("scene")

        fingerprint = (scene, p.get("day"), p.get("life"), p.get("gold"), p.get("location"),
                       len(s.get("actors") or []))
        if fingerprint == last_fingerprint:
            same_count += 1
            if same_count in (12, 40):
                j.problem("no change in %d iterations: %s  forgeUi=%s dialog=%s"
                          % (same_count, fingerprint, str(s.get("forgeUi"))[:160], str(s.get("dialog"))[:160]))
            if same_count >= 40:
                j.problem("livelocked - stopping so the session can be looked at rather than spun on")
                break
        else:
            same_count = 0
            last_fingerprint = fingerprint

        if last_day is not None and p["day"] != last_day:
            days_passed += p["day"] - last_day
            j.say("day", "day %s -> %s (life %s/%s, gold %s)"
                  % (last_day, p["day"], p["life"], p["maxLife"], p["gold"]))
        if last_life is not None and p["life"] < last_life:
            j.say("hurt", "life %s -> %s" % (last_life, p["life"]))
        if p["life"] <= 0:
            j.problem("life reached %s - the player is dead" % p["life"])
        last_day, last_life = p["day"], p["life"]
        peak_gold = max(peak_gold, p.get("gold") or 0)

        # A DuelScene belongs to settle(), never to back(). Learned the hard way on the first soak run: the
        # driver met a duel already in progress, fell through to its "unexpected scene" branch and pressed
        # back - which QUIT THE GAME. The JVM then hung in shutdown (see the bridge note below), leaving a
        # process that still answered HTTP with an all-null state, so it read as a freeze rather than an exit.
        if scene in ("DuelScene", "RewardScene", "InfoTextScene") or s.get("dialog") or s.get("forgeUi"):
            r = settle()
            if not r.get("ok", True):
                j.say("settle", str(r.get("message"))[:160])
            # Did settle actually clear it? If the same prompt is still up, it is a real choice and settle
            # will never take it - decide, once, and say what was decided.
            after = state()
            if after.get("forgeUi") or after.get("dialog"):
                pick = choose(after.get("forgeUi") or after.get("dialog"))
                if pick and pick[0]:
                    j.say("choice", "%r from %s" % (pick[1], [str(o[1] if isinstance(o, (list, tuple)) else
                                                              o.get("text")) for o in
                                                             (after.get("forgeUi") or after.get("dialog"))][:6]))
                    cmd("click", id=pick[0])
                else:
                    j.problem("a prompt is up that cannot be answered: %s"
                              % str(after.get("forgeUi") or after.get("dialog"))[:200])
                    cmd("key", key="ESCAPE")
            continue

        # ---------------------------------------------------------------- inside a map
        if scene in ("TileMapScene",):
            actors = s.get("actors") or []
            # Some "enemies" are not fights. The Warden in Orazca is an enemy-TYPE object carrying a
            # dialog, so walking into him talks and leaves him standing there - and the driver picked him
            # again on every visit, giving up three times per visit, all night. Give-ups are remembered per
            # (map, actor) for the whole run, not per visit.
            here = p.get("location") or "?"
            skip = {aid for (loc, aid) in giveups if loc == here}
            enemy = nearest(actors, "enemy", skip)
            reward = nearest(actors, "reward", skip)
            if enemy:
                r = cmd("goto", actor=enemy[0])
                duels += 1
                if not r.get("ok"):
                    msg = str(r.get("message"))
                    j.say("map", "could not reach enemy %s: %s" % (enemy[0], msg[:110]))
                    # "no path" is the PLANNER saying it is impossible - the round-275 finding. "stuck near"
                    # is the WALKER giving up after four replans, a known limitation of long legs and not
                    # evidence about the map, so it is retried rather than reported.
                    if "no path" in msg:
                        j.problem("unreachable enemy %s (%s) in %s"
                                  % (enemy[0], enemy[2], p.get("location")))
                        cmd("leave")
                    else:
                        key = (here, enemy[0])
                        stuck_enemies[key] = stuck_enemies.get(key, 0) + 1
                        if stuck_enemies[key] >= 3:
                            j.say("skip", "giving up on %s (%s) in %s for the rest of the run - three failed "
                                          "approaches; a dialog NPC or a spot the walker cannot reach"
                                  % (enemy[0], enemy[2], here))
                            giveups.add(key)
                settle()
                continue
            if reward:
                cmd("goto", actor=reward[0])
                settle()
                continue
            j.say("map", "%s cleared - leaving" % p.get("location"))
            cmd("leave")
            settle()
            continue

        # ---------------------------------------------------------------- world map
        if scene == "GameScene":
            if legs_since_day >= 3:
                legs_since_day = 0
                r = cmd("wait", days=1)
                if not r.get("ok"):
                    j.say("wait", str(r.get("message"))[:160])
                settle()
                continue
            # Stranded? Every route failing in a row means the player is standing INSIDE a point of
            # interest's footprint. The walker exempts the POI it stands on from the ENTRY check (round 175)
            # but not from the planner's obstacle set, so there is no legal first step and `goto`, `explore`
            # and even `wait` (which has to step clear first) all answer "no path". A death respawn can drop
            # the player there, which is how the third soak run lost half an hour on Shimmering Crossing:
            # player rect [8210,5816 10x6] inside poiRect [8189,5796 48x48].
            #
            # The game's own way out is the Homeward rune, which teleports and does not path. It has to be
            # EQUIPPED to be used, exactly as in the HUD.
            if walk_failures >= 3:
                walk_failures = 0
                j.problem("stranded - every route failed; the player is probably standing inside a POI "
                          "footprint (see the [TFR-Agent] walk start line). Using the Homeward rune")
                escapes += 1
                # The rune is worth ONE try per strand, because its own destination is a POI. Measured:
                # it reports "Teleported outside Orazca((5600,5600))" and Orazca's footprint covers that
                # spot, so the second strand arrived NINE SECONDS after the first escape - left alone the
                # driver would have spent the night teleporting into the same trap. After that, reload the
                # checkpoint: a load puts the player back where they stood when it was taken, which is by
                # definition somewhere the planner had already walked to.
                if escapes <= 1:
                    cmd("equip", item='Homeward rune')
                    r = cmd("use", item='Homeward rune')
                    j.say("escape", "Homeward rune: %s" % str(r.get("message"))[:120])
                else:
                    j.say("escape", "stranded again after a rune - reloading the checkpoint (slot 5)")
                    r = cmd("load", slot=5)
                    if not r.get("ok"):
                        j.problem("could not reload the checkpoint either: %s" % str(r.get("message"))[:140])
                        break
                    escapes = 0
                settle()
                continue
            target = None
            for row in s.get("pois") or []:
                name = row[0]
                if name in visited:
                    continue
                target = row
                break
            if target is None:
                legs_since_day = 99
                continue
            visited.append(target[0])
            r = cmd("goto", poi=target[0])
            legs_since_day += 1
            if not r.get("ok"):
                msg = str(r.get("message"))
                j.say("walk", "%s (%s tiles %s): %s" % (target[0], target[3], target[4], msg[:110]))
                # try one leg in the target's own direction, the documented recovery
                e = cmd("explore", dir=target[4], tiles=8)
                # Both failing is the stranded signature - a planner that cannot leave the start node.
                walk_failures = walk_failures + 1 if not e.get("ok") else 0
            else:
                walk_failures = 0
            settle()
            continue

        # ---------------------------------------------------------------- anything else
        # Anything else: settle FIRST (it knows the win/lose views, reward screens and single-option
        # dialogs) and only fall back to back() if settle cannot move it. back() on the wrong scene is how
        # the first run ended the game.
        j.say("scene", "unexpected scene %s - settling" % scene)
        r = settle()
        if not r.get("ok", True):
            j.say("scene", "settle could not move %s: %s" % (scene, str(r.get("message"))[:110]))
            unknown_scenes[scene] = unknown_scenes.get(scene, 0) + 1
            if unknown_scenes[scene] >= 3:
                j.problem("stuck in scene %s - settle cannot leave it" % scene)
                break

    j.say("end", "duels attempted %d, days passed %d, peak gold %d" % (duels, days_passed, peak_gold))
    j.say("end", "counts: %s" % dict(j.counts))
    if j.problems:
        j.say("end", "%d PROBLEM(s):" % len(j.problems))
        for t in j.problems:
            j.fh.write("    " + t + "\n")
            print("    " + t, flush=True)
    j.fh.close()


if __name__ == "__main__":
    main()
