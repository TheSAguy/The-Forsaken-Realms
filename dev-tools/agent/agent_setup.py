#!/usr/bin/env python
"""
agent_setup.py - seed the AGENT game's isolated profile (MOD_SCOPE #117).

The agent game runs with APPDATA=F:\\FORGE\\TFR-Agent\\profile (see agent_launch.cmd), so Forge keeps its saves,
preferences and forge.log in F:\\FORGE\\TFR-Agent\\profile\\ForsakenRealms and never touches the player's own
%APPDATA%\\ForsakenRealms. A fresh profile would start full-screen at the player's 4K mode with sound on, so this
seeds two files once:

  adventure/settings.json          a 1280x720 WINDOW, the plane selected
  preferences/forge.preferences    the player's own preferences copied, with full screen, sounds and music OFF

Existing seeded files are left alone unless --force. Card art stays shared with the player's game (it lives under
%LOCALAPPDATA%\\Forge\\Cache, which the launcher does not redirect).

usage: python dev-tools/agent/agent_setup.py [--force] [--root F:\\FORGE\\TFR-Agent]
"""
import argparse, json, os, sys

ap = argparse.ArgumentParser()
ap.add_argument("--root", default=r"F:\FORGE\TFR-Agent")
ap.add_argument("--force", action="store_true")
a = ap.parse_args()

game = os.path.join(a.root, "The Forsaken Realms")
if not os.path.isfile(os.path.join(game, "PACKAGE_OK.txt")):
    sys.exit("No playable game folder at %s (PACKAGE_OK.txt missing) - sync it from the live folder first." % game)
data = os.path.join(a.root, "profile", "ForsakenRealms")
os.makedirs(os.path.join(data, "adventure"), exist_ok=True)
os.makedirs(os.path.join(data, "preferences"), exist_ok=True)

settings = os.path.join(data, "adventure", "settings.json")
if a.force or not os.path.exists(settings):
    with open(settings, "w", encoding="utf-8", newline="\n") as f:
        json.dump({
            "width": 1280, "height": 720, "plane": "The Forsaken Realms", "fullScreen": False, "videomode": "720p",
            "rewardCardAdj": 1, "cardTooltipAdj": 1, "rewardCardAdjLandscape": 1, "cardTooltipAdjLandscape": 1,
            "preferEraMatchedTokenArt": True, "simulateInnTournamentAIMatches": True,
        }, f, indent=0)
    print("seeded", settings)
else:
    print("kept", settings)

prefs = os.path.join(data, "preferences", "forge.preferences")
OVERRIDES = {"UI_FULLSCREEN_MODE": "false", "UI_ENABLE_SOUNDS": "false", "UI_ENABLE_MUSIC": "false",
             "UI_VOL_SOUNDS": "0", "UI_VOL_MUSIC": "0", "UI_LANDSCAPE_MODE": "true"}
if a.force or not os.path.exists(prefs):
    src = os.path.join(os.environ.get("APPDATA", ""), "ForsakenRealms", "preferences", "forge.preferences")
    lines = []
    if os.path.exists(src):
        with open(src, encoding="utf-8", errors="replace") as f:
            lines = [ln.rstrip("\r\n") for ln in f]
    seen = set()
    out = []
    for ln in lines:
        key = ln.split("=", 1)[0] if "=" in ln else None
        if key in OVERRIDES:
            out.append("%s=%s" % (key, OVERRIDES[key]))
            seen.add(key)
        else:
            out.append(ln)
    for key, val in OVERRIDES.items():
        if key not in seen:
            out.append("%s=%s" % (key, val))
    with open(prefs, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    print("seeded", prefs, "(from %s)" % (src if lines else "defaults"))
else:
    print("kept", prefs)
