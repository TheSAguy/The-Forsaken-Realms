"""Round 407: add the castle champions to the five AI castle maps.

usage: python add_champions.py <castles folder> [--write]

Each castle gets two enemy objects patrolling the sand ring around its keep (the corridors every castle shares):
  A - every difficulty, starts at the ring's top-left corner and walks it clockwise
  B - Insane only (spawn.Easy/Normal/Hard false), starts at the bottom-right corner, same direction - always opposite A
Both carry castleChampion=<color>; MapStage swaps the authored stand-in for a random Archmage of that color on the
first visit (util/CastleChampions). Text insertion, so the rest of each file keeps its exact formatting. Dry run by
default; a map that already holds a castleChampion object is left alone.
"""
import argparse
import re
import sys
from pathlib import Path

THREAT = 80    # px - notices the player 5 tiles off
PURSUE = 192   # px - gives up 12 tiles off

# corner waypoint ids per castle: top-left, top-right, bottom-right, bottom-left of the ring
CASTLES = {
    "white": {"ring": (79, 80, 77, 78), "standins": ("Aegis Paladin", "The Ivory Sovereign")},
    "blue":  {"ring": (82, 81, 78, 76), "standins": ("Archmage of the Tides", "Sirena, Tide-Singer")},
    "black": {"ring": (80, 73, 74, 76), "standins": ("High Vampire", "The Reaper")},
    "red":   {"ring": (84, 81, 79, 77), "standins": ("Crimson Minotaur Lord", "Goblin Warlord")},
    "green": {"ring": (74, 76, 75, 79), "standins": ("Troll Warlord", "Kapre of the Old Balete")},
}


def obj_xy(text, oid):
    m = re.search(r'<object id="%d"[^>]*\bx="([-\d.]+)" y="([-\d.]+)"' % oid, text)
    if not m:
        sys.exit(f"waypoint {oid} not found")
    return m.group(1), m.group(2)


def champion(oid, template, color, standin, x, y, route, insane_only):
    lines = [f'  <object id="{oid}" template="{template}" x="{x}" y="{y}">',
             '   <properties>',
             f'    <property name="castleChampion" value="{color}"/>',
             f'    <property name="enemy" value="{standin}"/>',
             f'    <property name="pursueRange" type="int" value="{PURSUE}"/>']
    if insane_only:
        lines += ['    <property name="spawn.Easy" type="bool" value="false"/>',
                  '    <property name="spawn.Hard" type="bool" value="false"/>',
                  '    <property name="spawn.Normal" type="bool" value="false"/>']
    lines += [f'    <property name="threatRange" type="int" value="{THREAT}"/>',
              f'    <property name="waypoints" value="{route}"/>',
              '   </properties>',
              '  </object>']
    return "\n".join(lines) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("castles", help="maps/map/main_story/castles folder (required - no default)")
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args()
    root = Path(args.castles)
    for color, spec in CASTLES.items():
        path = root / f"{color}_castle.tmx"
        text = path.read_text(encoding="utf-8")
        if 'name="castleChampion"' in text:
            print(f"{path.name}: already has champions - skipped")
            continue
        nxt = int(re.search(r'nextobjectid="(\d+)"', text).group(1))
        template = re.search(r'template="([^"]*obj/enemy\.tx)"', text).group(1)
        tl, tr, br, bl = spec["ring"]
        a_xy, b_xy = obj_xy(text, tl), obj_xy(text, br)
        block = champion(nxt, template, color, spec["standins"][0], *a_xy, f"{tr},{br},{bl},{tl}", False)
        block += champion(nxt + 1, template, color, spec["standins"][1], *b_xy, f"{bl},{tl},{tr},{br}", True)
        # into the object group that holds the castle's enemies, just before it closes
        first_enemy = text.index(template)
        close = text.index("</objectgroup>", first_enemy)
        line_start = text.rfind("\n", 0, close) + 1
        if "\r\n" in text:
            block = block.replace("\n", "\r\n")
        new = text[:line_start] + block + text[line_start:]
        new = new.replace(f'nextobjectid="{nxt}"', f'nextobjectid="{nxt + 2}"', 1)
        print(f"{path.name}: champion #{nxt} at {a_xy} route {tr},{br},{bl},{tl}; Insane #{nxt + 1} at {b_xy} route {bl},{tl},{tr},{br}")
        if args.write:
            path.write_text(new, encoding="utf-8", newline="")
    if not args.write:
        print("dry run - pass --write to save")


if __name__ == "__main__":
    main()
