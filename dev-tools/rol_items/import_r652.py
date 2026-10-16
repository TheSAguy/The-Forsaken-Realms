r"""import_r652.py - TFR round 652: Nest Warden's Armor returns from Realm of Legends.

The user (2026-10-16): "add Nest Warden's Armor as a land armor like the others, with a shop source you pick." A
recheck after round 648 found it was the one removed Realm of Legends body item with no Commander tie (Nesting
Grounds is a plain land); the 2026-08-10 Item Economy round had dropped it together with the Commander robes.

  Nest Warden's Armor  Body  Nesting Grounds  Uncommon  Red Equipment shop
  - the land-armor rule (Nomad / Cephalid / Cabal / Centaur / Barbarian Armor, Reliquary Robes): the land starts in
    play for one card fewer in the opening hand. Realm of Legends also gave +2 life; dropped, like the others.
  - Red Equipment: a colorless land fits any shop, and red's was the one list round 648 left as it was.
  - its icon, NestWarden'sArmor, is still in items.atlas.

usage: python import_r652.py <repo root>      (idempotent; uses import_r648's writers)"""
import os
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import import_r648 as base  # noqa: E402

base.ITEMS = [
    ("Nest Warden's Armor", "Taps for C. 1, T: move a counter from one of your permanents onto another, as a sorcery.",
     "Body", "NestWarden'sArmor", None, {"changeStartCards": -1, "startBattleWithCard": ["Nesting Grounds|C20"]},
     "Uncommon"),
]
base.SHOPS = {"RedEquipment": ["Nest Warden's Armor"]}


def main(argv):
    if len(argv) < 2 or argv[1].startswith('-'):
        raise SystemExit(__doc__)
    plane = os.path.join(argv[1], 'forge-gui', 'res', 'adventure', 'The Forsaken Realms')
    print('items.json: %d added' % base.write_items(plane))
    print('shops.json: %d entries added' % base.write_shops(plane))
    print('items.csv: %d rows added' % base.write_csv(plane))


if __name__ == '__main__':
    main(sys.argv)
