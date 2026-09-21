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


def nearest(actors, kind):
    best = None
    for a in actors or []:
        if len(a) < 4 or a[1] != kind or a[3] is None:
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
    unknown_scenes = {}
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
            continue

        # ---------------------------------------------------------------- inside a map
        if scene in ("TileMapScene",):
            actors = s.get("actors") or []
            enemy = nearest(actors, "enemy")
            reward = nearest(actors, "reward")
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
                        stuck_enemies[enemy[0]] = stuck_enemies.get(enemy[0], 0) + 1
                        if stuck_enemies[enemy[0]] >= 3:
                            j.problem("walker gave up on enemy %s (%s) in %s three times"
                                      % (enemy[0], enemy[2], p.get("location")))
                            cmd("leave")
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
                cmd("explore", dir=target[4], tiles=8)
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
