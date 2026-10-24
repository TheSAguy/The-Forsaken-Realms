r"""import_r731.py - TFR round 731: the deeper floors of three dungeons, so their stairs stop throwing the player out.

Two players on Discord (v1.23, 2026-10-24): "At the Tarnation dungeon ... when you try to enter the hideout you get
teleported out of the dungeon" (Nova), and the Wizard Palace's door "immediate boots to overworld" (gnomedoom).

The cause is the 2026-08-10 item-economy import: it copied only the FIRST floor of Tarnation, the Wizard Palace and
the Church of Valgavoth from Realm of Legends, and cleared each one's stairs (`teleport` = "") so they would not load
a map that was not there. EntryActor.onPlayerCollide() treats an empty `teleport` as the dungeon's exit, so the door
that leads deeper sent the player to the world map instead.

THE MAPS (rol_common.import_maps; repointed to this plane, the treasure template too):
  barbariancamp/Tarnation_2.tmx        the Hellspur sanctum: Akul (Easy/Normal; Laughing Jasper Flint on Hard),
                                       Calamity, OTJ boosters, BIG and OTP chests
  evilgrove/Church_of_Valgavoth_2.tmx  the Master of Keys, the Mindskinner, Rendmaw, three captives that open the
                                       blood barrier, Valgavoth; DSK boosters, a DSC chest
  merfolkpool/Wizard_Palace_2-4.tmx    Azami, Jalira, Vadrik, the Archimandrite; Kess, Pol Jamaar, Inalla, Mairsil and
                                       a Tome of the Infinite scroll; Zur in the void
  Every enemy is already in this plane's enemies.json, every reward is a card or a booster: no item, no quest, no
  Commander, no promise. The three captive dialogs get the comma their JSON is missing.
THE STAIRS: each first floor's door to the next floor gets its target back (Tarnation_1 #108, Church_of_Valgavoth_1
  #89, Wizard_Palace_1 #118).

The fourth cleared door, Hall_of_the_Unifier #150 (to Realm of Legends' 11-map Mirror Gallery), stays an exit: its
dialog already says the mirror "now shows only the way back out".

usage: python import_r731.py <repo root>          (idempotent: maps already there and doors already set are left)"""
import os
import re
import sys

sys.dont_write_bytecode = True
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rol_common as rc  # noqa: E402

ROUND = 731

MAPS = [('barbariancamp', 'Tarnation_2.tmx'), ('evilgrove', 'Church_of_Valgavoth_2.tmx'),
        ('merfolkpool', 'Wizard_Palace_2.tmx'), ('merfolkpool', 'Wizard_Palace_3.tmx'),
        ('merfolkpool', 'Wizard_Palace_4.tmx')]

Q = '&quot;'
CARD_SWAPS = {
    # Forge's name is "The Key to the Vault" (Akul's effect, both difficulty copies)
    'Tarnation_2.tmx': [(Q + 'Key to the Vault|OTJ' + Q, Q + 'The Key to the Vault|OTJ' + Q, 2)],
    # the captives' "Put them out of their misery" option runs into its "options" with no comma (3 dialogs)
    'Church_of_Valgavoth_2.tmx': [('Put them out of their misery' + Q + ' \r\n',
                                   'Put them out of their misery' + Q + ', \r\n', 3),
                                  (Q + 'Found footage' + Q, Q + 'Found Footage' + Q, 1),
                                  (Q + 'nowhere to run|DSK' + Q, Q + 'Nowhere to Run|DSK' + Q, 2)],
    # the scroll's Tome of the Infinite is digital-only (J21, blocked unless the player allows those sets): the
    # palace's other signature card instead, in paper
    'Wizard_Palace_3.tmx': [(Q + 'cardName' + Q + ': ' + Q + 'Tome of the Infinite' + Q + ', \r\n' + Q + 'editions'
                             + Q + ': [ ' + Q + 'J21' + Q + ' ]',
                             Q + 'cardName' + Q + ': ' + Q + 'Wizard Class' + Q + ', \r\n' + Q + 'editions'
                             + Q + ': [ ' + Q + 'AFR' + Q + ' ]', 1)],
}
# Wizard_Palace_1 has started every duel with Tome of the Infinite since 2026-08-10 (gnomedoom's log shows it in play);
# the deeper floors keep the same palace effect
BATTLE_CARDS_OK = ('Tome of the Infinite',)

# (first floor, door object id, the floor it leads to)
STAIRS = [('barbariancamp/Tarnation_1.tmx', 108, 'barbariancamp/Tarnation_2.tmx'),
          ('evilgrove/Church_of_Valgavoth_1.tmx', 89, 'evilgrove/Church_of_Valgavoth_2.tmx'),
          ('merfolkpool/Wizard_Palace_1.tmx', 118, 'merfolkpool/Wizard_Palace_2.tmx')]


def reconnect(c):
    for src, oid, target in STAIRS:
        path = os.path.join(c.plane, 'maps', 'map', src)
        raw = rc.rw(path)
        m = re.search(r'<object id="%d"[^>]*>.*?</object>' % oid, raw, re.S)
        assert m, '%s: no object %d' % (src, oid)
        block = m.group(0)
        want = '<property name="teleport" value="../%s/maps/map/%s"/>' % (rc.TFR_NAME, target)
        if want in block:
            c.report['stairs'].append('%s #%d (already set)' % (src, oid))
            continue
        assert block.count('<property name="teleport" value=""/>') == 1, '%s #%d: no cleared teleport' % (src, oid)
        raw = raw[:m.start()] + block.replace('<property name="teleport" value=""/>', want) + raw[m.end():]
        rc.ww(path, raw)
        c.report['stairs'].append('%s #%d -> %s' % (src, oid, target))


def main(argv):
    c = rc.Ctx(argv[1])
    rc.import_maps(c, MAPS, {}, CARD_SWAPS, battle_cards_ok=BATTLE_CARDS_OK)
    reconnect(c)
    rc.print_report(c)


if __name__ == '__main__':
    main(sys.argv)
