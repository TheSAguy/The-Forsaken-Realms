"""Round 411: write the notoriety Wall token scripts into forge-gui/res/tokenscripts.

usage: python make_token_scripts.py <tokenscripts folder>   (required - no default)

Colorless "Artifact Creature - Wall" tokens with Defender (+ Reach / + Flying), one per kind, wall and level. Round 429
(the user: "level 2, be 1/2, lvl 3: 2/4 and level 4: 3/6 ... at 25 consecutive wins, start over with a second wall"):
the levels are 0/1, 1/2, 2/4, 3/6, and the second wall has its own scripts (tfr_wall2_...) because its card prints a
different win count. Names: tfr_wall[2][_reach|_flying]_<power>_<toughness>. The same names are the settings.json
notorietyWalls* lists and the picture files <name>.fullborder.png in adventure/common/custom_card_pics
(dev-tools/wall_tokens/make_wall_cards.py).
"""
import sys
from pathlib import Path

KINDS = {"": [], "reach": ["Reach"], "flying": ["Flying"]}
LEVEL_PT = {1: (0, 1), 2: (1, 2), 3: (2, 4), 4: (3, 6)}   # round 429
WALLS = ("tfr_wall", "tfr_wall2")                          # round 429: the first wall, the second (from 25 wins)
# Round 445 (the user: "The flying wall level 4. Let's create 2 more copies of that, one ... 45+ and one 50+ win streak.
# ... give those to all enemy levels"): two bonus walls, a flying 3/6 each, for every rank - settings.json
# notorietyBonusWalls / notorietyBonusWallWins. (script name, the win count its card prints)
BONUS_WALLS = (("tfr_wall3_flying_3_6", 45), ("tfr_wall4_flying_3_6", 50))


def script_name(wall, kind, level):
    """Round 429: wall 0 or 1, kind "" / "reach" / "flying", level 1-4."""
    p, t = LEVEL_PT[level]
    return f"{WALLS[wall]}_{kind + '_' if kind else ''}{p}_{t}"


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    if not out.is_dir():
        sys.exit(f"not a folder: {out}")
    for wall in range(len(WALLS)):
        for kind, extra in KINDS.items():
            for level in range(1, 5):
                name = script_name(wall, kind, level)
                p, t = LEVEL_PT[level]
                keywords = ["Defender"] + extra
                lines = ["Name:Wall Token", "ManaCost:no cost", "Types:Artifact Creature Wall", f"PT:{p}/{t}"]
                lines += [f"K:{k}" for k in keywords]
                lines.append("Oracle:" + ", ".join([keywords[0]] + [k.lower() for k in keywords[1:]]))
                (out / f"{name}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
                print(name, "|", ", ".join(keywords), f"{p}/{t}")
    for name, _wins in BONUS_WALLS:   # round 445: the same token as the second wall's flying 3/6
        lines = ["Name:Wall Token", "ManaCost:no cost", "Types:Artifact Creature Wall", "PT:3/6",
                 "K:Defender", "K:Flying", "Oracle:Defender, flying"]
        (out / f"{name}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        print(name, "| Defender, Flying 3/6")


if __name__ == "__main__":
    main()
