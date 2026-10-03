"""Round 411: write the 12 notoriety Wall token scripts into forge-gui/res/tokenscripts.

usage: python make_token_scripts.py <tokenscripts folder>   (required - no default)

tfr_wall_0_N / tfr_wall_reach_0_N / tfr_wall_flying_0_N for N = 1..4: colorless "Artifact Creature - Wall" tokens with
Defender (+ Reach / + Flying). The same names are the settings.json notorietyWalls* lists and the picture files
<name>.fullborder.png in adventure/common/custom_card_pics (dev-tools/wall_tokens/make_wall_cards.py).
"""
import sys
from pathlib import Path

KINDS = {"": [], "reach": ["Reach"], "flying": ["Flying"]}


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    if not out.is_dir():
        sys.exit(f"not a folder: {out}")
    for kind, extra in KINDS.items():
        for t in range(1, 5):
            name = f"tfr_wall_{kind + '_' if kind else ''}0_{t}"
            keywords = ["Defender"] + extra
            lines = ["Name:Wall Token", "ManaCost:no cost", "Types:Artifact Creature Wall", f"PT:0/{t}"]
            lines += [f"K:{k}" for k in keywords]
            lines.append("Oracle:" + ", ".join([keywords[0]] + [k.lower() for k in keywords[1:]]))
            (out / f"{name}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
            print(name, "|", ", ".join(keywords), f"0/{t}")


if __name__ == "__main__":
    main()
