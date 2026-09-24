"""plan.py - round 322: chest guards to Apprentice, legend-named guards renamed (plan only; apply.py writes).

User: "Swap the chest guards to Apprentice and rename the legend guards. Make sure the Legends are not supposed to be
in those dungeons before removing them."

usage: python -B plan.py <repo root> --provenance provenance.json [--out-dir DIR]

  <repo root>     REQUIRED, read only. Maps, enemies.json, points_of_interest.json and the card database are read from it.
  --provenance    the output of dev-tools/guard_patrols/provenance.py for the same repo (which round placed each enemy).

What it selects (from the CURRENT maps, nothing cached):
  chest    every enemy placed by round 286b's chest-guard pass (add_booster_guards --loot treasure --rank 0) whose tier
           is above Apprentice by the game's own rule: EnemyData.tierRank(tier) > 0, i.e. tier Uncommon (Adept), Rare
           (Master) or Mythic (Archmage); tier comes from world/enemies.json, first entry by name (WorldData.getEnemy),
           default "Common". Refused if the placement is a booster's registered guard on any difficulty (the runtime
           matching MapStage.assignLootGuards() replayed per difficulty) - booster guards stay Adept+ (round 287).
  legend   every enemy placed by a guard tool (rounds 279, 284, 286b, 287) whose catalog entry is a unique character:
           `legend` flag, `boss` flag, or a deck under decks/legends/ (Forge's named-legend commander decks - the same
           entries carry spawnRate 0, i.e. SpawnTierWeighting.isExempt()). Names are cross-checked against Forge's
           cardsfolder (legendary / planeswalker card names).
The new name for each comes from the curated PICKS table below (reason included) and is VALIDATED here: it must be in
enemies.json by exact name, have a deck file, not be a boss / legend / spawnRate-0 special / decks/legends/ commander /
story-tagged / a legendary card's name, have a sprite, be Include=Y in config tables/enemies.csv, and be Apprentice for
a chest guard; Adept+ for a booster guard. Every selected placement must be a plain tool clone: no dialog, defeatDialog,
effect, reward, displayNameOverride, questStageID or spawnCondition, and no deleteMapObject / activateMapObject /
battleWithActorID in its map naming its id. A placement with a patrol route must keep its flying flag (round 319
validated the route for a walker or a flier).
"""
import argparse
import collections
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import unicodedata

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import common as C

TOOL_ROUNDS = ("r279", "r284", "r286b", "r287")
ROUND_WHAT = {"r279": "round 279's booster-guard pass (add_booster_guards.py, Adept+)",
              "r284": "round 284's Basilica guard",
              "r286b": "round 286b's chest-guard pass (add_booster_guards.py --loot treasure)",
              "r287": "round 287's loot-guard pass (add_loot_guards.py)"}
DIFFS = ("Easy", "Normal", "Hard", "Insane")

# ------------------------------------------------------------------------------------------------ the picks
# (map, old name) -> (new name, why). One line per group; every placement of that name in that map gets the pick.
PICKS = {
    # ---- chest guards above Apprentice (round 286b) ------------------------------------------------------------
    ("aerie/aerie_4.tmx", "Owl"): ("Duskfeather Owlet", "an owlet for the aerie's Owls - Apprentice bird, flies like the Owl"),
    ("barbariancamp/barbariancamp_bandit.tmx", "Bandit Scoundrel"): ("Cutpurse", "the camp's bandits have no Apprentice rank; a cutpurse is the petty-thief step below a scoundrel"),
    ("barbariancamp/barbariancamp_goblin_3.tmx", "Goblin Stalker"): ("Goblin Skulker", "the Apprentice sneak-goblin next to the camp's Goblin Stalkers"),
    ("barbariancamp/barbariancamp_kobold_mine.tmx", "Kobold Pyromancer"): ("Kobold Shaman", "the mine's kobolds: Kobold Shaman is the Apprentice kobold caster"),
    ("barbariancamp/kor_encampment.tmx", "Kor Warrior"): ("Kor Duelist", "an Apprentice Kor fighter for the Kor encampment"),
    ("cave/Ancient_Opal_Cavern.tmx", "Nephilim Epochal"): ("Eye", "the cavern holds only its Nephilim; no Apprentice of its kind exists, so the same alien aberration as the Eldrazi prisons"),
    ("cave/Eldrazi_Prison_Azlask.tmx", "Azlask"): ("Eldrazi Scion", "EYE_ELDRAZI"),
    ("cave/Eldrazi_Prison_Emrakul.tmx", "Emrakul, the Aeons Torn"): ("Eldrazi Scion", "EYE_ELDRAZI"),
    ("cave/Eldrazi_Prison_Kozilek.tmx", "Kozilek, Butcher of Truth"): ("Eldrazi Scion", "EYE_ELDRAZI"),
    ("cave/Eldrazi_Prison_Ulalek.tmx", "Ulalek"): ("Eldrazi Scion", "EYE_ELDRAZI"),
    ("cave/Eldrazi_Prison_Ulamog.tmx", "Ulamog"): ("Eldrazi Scion", "EYE_ELDRAZI"),
    ("cave/Eldrazi_Prison_Zhulodok.tmx", "Zhulodok"): ("Eldrazi Scion", "EYE_ELDRAZI"),
    ("cave/cave_bandit.tmx", "Bandit Scoundrel"): ("Cutpurse", "the cave's bandits have no Apprentice rank; a cutpurse is the petty-thief step below a scoundrel"),
    ("cave/cave_black_04.tmx", "Talonborn Harpy"): ("Harpy", "the plain Harpy - same creature, Apprentice, flies"),
    ("cave/cave_blue_05.tmx", "Angel Overseer"): ("Geist", "no Apprentice angel exists; a flying blue spirit for the blue cave"),
    ("cave/cave_blue_06.tmx", "Galewrapped Specter"): ("Geist", "a specter is a spirit: the Apprentice blue Geist"),
    ("cave/cave_blue_09.tmx", "Umbral Dragon"): ("Duskwing Whelp", "a dragon's whelp for the Umbral (shadow) Dragon - Apprentice, flies"),
    ("cave/cave_blue_13.tmx", "Void Dragon"): ("Duskwing Whelp", "a dragon's whelp for the Void Dragon - Apprentice, flies"),
    ("cave/cave_eldrazi.tmx", "Eldrazi"): ("Eldrazi Scion", "EYE_ELDRAZI"),
    ("cave/cave_multilevel/cave_16BR2D.tmx", "Master Blue Wizard"): ("Apprentice Blue Wizard", "the wizard ladder one-for-one: Apprentice Blue Wizard"),
    ("cave/cave_multilevel/cave_16BR2U1.tmx", "Cave Spider"): ("Giant Spider", "the Apprentice spider"),
    ("cave/cave_multilevel/cave_16BR3.tmx", "Undead Goblin"): ("Zombie", "the Apprentice undead (Walking Zombie)"),
    ("cave/cave_multilevel/cave_16BR3U.tmx", "Unholy Knight"): ("False Knight", "the Apprentice unholy knight"),
    ("cave/cave_multilevel/cave_16D.tmx", "Snake"): ("Viper", "the Apprentice snake"),
    ("cave/cave_multilevel_3/cave_21D.tmx", "Troll"): ("Brawny Ogre", "no Apprentice troll exists; an ogre, kin to the cave's own Mountain Ogre"),
    ("cave/cave_orc.tmx", "Orc Warrior"): ("Grimtusk Bowman", "the Orc Warrior is an orc archer; Grimtusk Bowman is the Apprentice one"),
    ("cave/cave_red_12.tmx", "Adept Red Wizard"): ("Pyromancer", "'Apprentice Red Wizard' is Adept in the data; Pyromancer is the Apprentice red fire-wizard"),
    ("cave/cave_zombie.tmx", "Undead Goblin"): ("Zombie", "the zombie cave's own creature (Walking Zombie), Apprentice"),
    ("demontower/portal_2G.tmx", "Torturer"): ("Immersturm Demon", "the Demon Tower's own authored Apprentice (Immersturm Demon stands on its lower floors)"),
    ("evilgrove/evilgrove_2_blackgolem.tmx", "Black Golem"): ("Golem", "the Apprentice golem (Rusted Golem); walks like the Black Golem, so its round-319 patrol stays valid"),
    ("fort/fort_blue_4_clouds.tmx", "Cloud Guardian"): ("Apprentice Blue Wizard", "the sky fort's Master Blue Wizard line at Apprentice; flies like the Cloud Guardian"),
    ("fort/fort_colorless_2_wizards.tmx", "Adept Black Wizard"): ("Apprentice Black Wizard", "the wizard ladder one-for-one: Apprentice Black Wizard"),
    ("fort/fort_colorless_4_ooze.tmx", "Necrogoyf"): ("Gazestalk Ooze", "the ooze fort's theme (its authored Ooze is UG): an Apprentice UG ooze"),
    ("fort/fort_colorless_7_multilevel4.tmx", "Eldrazi Devastator"): ("Eldrazi Scion", "EYE_ELDRAZI"),
    ("fort/fort_green_3_forestcastle.tmx", "Adept Green Wizard"): ("Dawnhart Witch", "'Apprentice Green Wizard' is Adept in the data; Dawnhart Witch is an Apprentice green human caster"),
    ("graveyard_crypt/crypt.tmx", "Adept Black Wizard"): ("Apprentice Black Wizard", "the wizard ladder one-for-one: Apprentice Black Wizard"),
    ("grove/grove_1_bears.tmx", "Bear"): ("Timber Wolf", "no Apprentice bear exists; a forest predator of the same grove"),
    ("grove/grove_4_gorilla.tmx", "Gorilla"): ("Bristlehide Boar", "no Apprentice ape exists (the grove's Monkey is Master); an RG beast like the Gorilla"),
    ("grove/grove_9_eldrazi.tmx", "Eldrazi"): ("Eldrazi Scion", "EYE_ELDRAZI"),
    ("magetower/magetower_10_crawlspace.tmx", "Archmage"): ("Apprentice Scribe", "a mage tower's apprentice; a blue walker like the tower's Adept Blue Wizard and Illusionist"),
    ("magetower/magetower_12_lichsmirror.tmx", "Lich"): ("Apprentice Black Wizard", "the Apprentice necromancer (Wizard, Necromancer tags) for the Lich's tower"),
    ("magetower/magetower_5_greenhouse.tmx", "Plant"): ("Wandering Treefolk", "the greenhouse's own kind (it has Treefolk and Plants): an Apprentice treefolk tagged Plant"),
    ("main_story/castles/black_castle.tmx", "Demon"): ("Pointed Demonspawn", "the Apprentice mono-black demon"),
    ("main_story/castles/blue_castle.tmx", "Master Blue Wizard"): ("Apprentice Blue Wizard", "the wizard ladder one-for-one: Apprentice Blue Wizard"),
    ("main_story/castles/white_castle.tmx", "Master White Wizard"): ("Avacynian Preacher", "'Apprentice White Wizard' is Adept in the data; Avacynian Preacher is the Apprentice white holy caster"),
    ("main_story/temple_of_liliana/forest.tmx", "Golgari Elf"): ("Elf", "the Apprentice elf"),
    ("main_story/templeofchandra.tmx", "Phoenix"): ("Fire Elemental", "a flying fire creature for Chandra's temple of elementals"),
    ("main_story_defend/waste_town_abandoned.tmx", "Demon"): ("Pointed Demonspawn", "the Apprentice mono-black demon for the demon-held town"),
    ("main_story_explore/dig_site_1.tmx", "Blue Prototype"): ("Construct", "the Apprentice construct (Rustic Construct, blue-red)"),
    ("main_story_explore/shard_mines.tmx", "Pirate 3"): ("Brinebone Buccaneer", "the only living Apprentice pirate ('Pirate Captain 2', shown as 'Pirate Captain') carries the Captain tag that quest 45's 'Defeat the mine captain' accepts in these very mines (round 323) - it would stand in for the quest's captain; Brinebone Buccaneer is a pirate-tagged Apprentice without it"),
    ("maze/maze_2.tmx", "Minotaur Warcaller"): ("Minotaur", "the Apprentice minotaur (Minotaur Warrior); walks, so #77's round-319 patrol stays valid"),
    ("merfolkpool/Idyllic_Beachfront.tmx", "Grandmother Goby"): ("Merfolk Soldier", "an Apprentice merfolk in Grandmother Goby's own sprite"),
    ("merfolkpool/merfolkpool_1.tmx", "Merfolk Fighter"): ("Merfolk Soldier", "the Apprentice merfolk"),
    ("merfolkpool/merfolkpool_5.tmx", "Pirate"): ("Brinebone Buccaneer", "no Apprentice living pirate exists; a pirate-tagged Apprentice"),
    ("minibosses/kiora_island.tmx", "Merfolk Elite"): ("Merfolk Soldier", "the Apprentice merfolk"),
    ("minibosses/xira.tmx", "Wasp"): ("Pilfer Beetle", "an Apprentice insect for Xira's insect lair (the only flying one, Scarab, has 21 life - an outlier for Apprentice)"),
    ("monastery/monastery_1.tmx", "Human guard"): ("Lantern Ward Spearman", "an Apprentice human guard (Soldier) for the monastery"),
    ("phyrexia/phyrexian_g1.tmx", "Copper Host Infector"): ("Gitaxian Underling", "the only Apprentice Phyrexian (no green one exists)"),
    ("skep/sliverqueen.tmx", "Sliver Queen"): ("Triplate Crawler", "no Apprentice sliver exists; an armored insect-swarm creature, the closest Apprentice to a sliver brood"),
    ("skullcave/skullcave_3.tmx", "Abyssal Baron"): ("Pointed Demonspawn", "the Apprentice mono-black demon for the Abyssal Barons' cave"),
    ("tibalt/tibalt_f0.tmx", "Fire Dragon"): ("Cinderwing Whelp", "a red dragon's whelp beside the floor's Fire Dragon - Apprentice, flies"),
    ("tibalt/tibalt_f1.tmx", "Horror of Tibalt"): ("Devil", "Tibalt's minions are devils: the Apprentice Devil"),
    # ---- legend-named guards (booster guards unless noted; Adept+ kept) ------------------------------------------
    ("grove/An-Havva_Inn.tmx", "Joven and Chandler"): ("Bandit Slingshot", "Joven and Chandler are human rogues drawn with the bandit-slingshot sprite: Bandit Slingshot is the ordinary Master-tier rogue in that same sprite"),
    ("grove/Squirrel_Farm.tmx", "Chatterfang"): ("Squirrel", "Chatterfang is a Squirrel: the ordinary Master-tier BG Squirrel, same sprite"),
    ("grove/Squirrel_Farm.tmx", "Mysterious Mage"): ("Nutcache Scrapper", "chest guard: an Apprentice nut-hoarding critter for the Squirrel Farm"),
    ("evilgrove/Gitrog_Bog_1.tmx", "Agatha"): ("Witch", "Agatha is a witch: the ordinary Witch (Accursed Witch) in Agatha's own sprite; Adept (no ordinary Master witch exists), booster floor kept"),
    ("evilgrove/Gitrog_Bog_2.tmx", "The Gitrog Monster"): ("Froghemoth", "the Gitrog Monster is a Frog Horror: Froghemoth is the ordinary Master-tier Frog Horror (its sprite is the bog's Uurg)"),
    ("barbariancamp/Ashlings_Domain.tmx", "Grub"): ("Goblin Chief", "Grub is a goblin matriarch (RB): Goblin Chief is the ordinary Master-tier BR goblin leader"),
    ("barbariancamp/Prismari_Classroom.tmx", "Veyran"): ("Efreet", "Veyran is an Efreet wizard (UR): the ordinary Adept Efreet, same colors"),
    ("barbariancamp/Tarnation_1.tmx", "Mysterious Mage"): ("Cutpurse", "chest guard: an Apprentice rogue for Tarnation's outlaws"),
    ("cave/Planeswalker_Dueling_Club.tmx", "Zo-Zu the Punisher"): ("Goblin Fighter", "chest guard: Zo-Zu is a goblin warrior - the Apprentice Goblin Fighter"),
    ("cave/Valors_Reach_Arena.tmx", "Regna and Krav"): ("Spiked Ravager", "Regna and Krav's own sprite on the ordinary Adept demon (Krav is a demon)"),
    ("cave/Valors_Reach_Arena.tmx", "Gwafa Hazid"): ("Rogue", "Gwafa Hazid is a human rogue; placed as a chest guard but the game makes it booster 102's guard, so the ordinary ADEPT Rogue (booster floor)"),
    ("evilgrove/Court_of_Paliano.tmx", "Grenzo"): ("Goblin Stalker", "Grenzo is a BR goblin rogue: Goblin Stalker is the ordinary Adept BR goblin"),
    ("evilgrove/Eclipsed_Elven_Court.tmx", "Morcant"): ("High Elf", "Morcant is a BG elf: High Elf is the ordinary Master-tier BG elf"),
    ("evilgrove/Silverquill_Classroom.tmx", "Felisa"): ("Bloodveil Countess", "Felisa is a WB vampire: Bloodveil Countess is the ordinary Master-tier white-black vampire"),
    ("fort/Lorehold_Classroom.tmx", "Alibou"): ("Marblehewn Golem", "Alibou is a golem (RW): Marblehewn Golem is the ordinary Master-tier white stone golem"),
    ("fort/Omenport.tmx", "Baron Bertram"): ("Vampire Lord", "Baron Bertram is a vampire noble: Vampire Lord is the ordinary Adept vampire lord"),
    ("grove/Witherbloom_Classroom.tmx", "Gyome"): ("Swamp Troll", "Gyome is a BG troll: Swamp Troll is the ordinary Master-tier BG troll, same sprite"),
    ("merfolkpool/Quandrix_Classroom.tmx", "Esix"): ("Djinn", "Esix is a flying elemental (UG): the ordinary Adept Djinn, a flying blue elemental"),
    ("tibalt/tibalt_f4.tmx", "Tibalt"): ("Devil of Tibalt", "Tibalt's own devil, the ordinary Master-tier red devil of this fortress"),
}
EYE_ELDRAZI = ("an Eldrazi prison / Eldrazi cave guard: the Apprentice Eldrazi Scion added in round 324 for this "
               "(the user's pick over the Adept Eldrazi and the Eye)")

CHILD_OF = {}   # filled at run time: rel -> [parent rels] via `teleport` properties


def sha1(path):
    return hashlib.sha1(open(path, "rb").read()).hexdigest()


def norm(s):
    return unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode("ascii").lower().strip()


def legendary_names(repo):
    """Legendary / planeswalker card names from forge-gui/res/cardsfolder, normalized, plus their comma-less forms."""
    out = set()
    cf = os.path.join(repo, "forge-gui", "res", "cardsfolder")
    for d, _s, files in os.walk(cf):
        for f in files:
            if not f.endswith(".txt"):
                continue
            name = types = None
            try:
                for line in open(os.path.join(d, f), encoding="utf-8", errors="replace"):
                    if line.startswith("Name:") and name is None:
                        name = line[5:].strip()
                    elif line.startswith("Types:") and types is None:
                        types = line[6:].split()
                    if name and types:
                        break
            except OSError:
                continue
            if name and types and ("Legendary" in types or "Planeswalker" in types):
                out.add(norm(name))
    return out


def include_flags(repo):
    path = os.path.join(C.plane_dir(repo), "config tables", "enemies.csv")
    out = {}
    import csv
    with open(path, encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh):
            out[row.get("Name")] = (row.get("Include") or "Y").strip().upper()
    return out


def sprite_exists(repo, sprite):
    for base in (C.plane_dir(repo), os.path.join(os.path.dirname(C.plane_dir(repo)), "common")):
        if os.path.exists(os.path.join(base, *sprite.split("/"))):
            return True
    return False


def is_unique_character(e):
    dk = (e.get("deck") or [""])[0]
    why = []
    if e.get("legend"):
        why.append("legend flag")
    if e.get("boss"):
        why.append("boss flag")
    if dk.startswith("decks/legends/"):
        why.append("a decks/legends/ commander")
    return why


def validate_pick(repo, cat, legends, include, new, want):
    """None if `new` is a fit replacement of the wanted tier band, else the reason it is not."""
    e = cat.by_name.get(new)                      # EXACT name only - never a nameOverride match
    if not e:
        return "not in enemies.json by exact name"
    dk = (e.get("deck") or [""])[0]
    if not dk or not C.deck_exists(repo, dk):
        return "no deck file"
    if e.get("boss") or e.get("legend"):
        return "boss/legend"
    if (e.get("spawnRate") or 0) <= 0:
        return "spawnRate 0 special"
    if dk.startswith("decks/legends/"):
        return "a decks/legends/ commander"
    if set(t for t in (e.get("questTags") or []) if t) & C.STORY_TAGS:
        return "story tag"
    base = re.sub(r"\s*\(.*\)$", "", new)
    if norm(base) in legends or norm(e.get("nameOverride")) in legends or "," in new:
        return "a legendary card's name"
    if e.get("copyPlayerDeck") or e.get("nextEnemy"):
        return "special deck mechanics"
    if not sprite_exists(repo, e.get("sprite") or ""):
        return "sprite missing"
    if include.get(new, "Y") != "Y":
        return "Include=N in enemies.csv (would be dropped from the dungeon)"
    r = C.tier_rank(e.get("tier") or "Common")
    if want == "apprentice" and r != 0:
        return "not Apprentice (%s)" % C.RANK_NAME[r]
    if want == "adept+" and r < 1:
        return "below Adept"
    return None


def roles_of(m):
    """{enemy id: {difficulty: (loot id, tier, tiles)}} - MapStage.assignLootGuards() replayed per difficulty."""
    roles = {}
    for diff in DIFFS:
        loot = [(o, t) for (o, t) in m.loot(spawned_only=False) if m.spawns(o, diff)]
        pri = {"booster": 2, "treasure": 1, "other": 0}
        order = sorted(range(len(loot)), key=lambda i: -pri[loot[i][1]])
        mobs = [e for e in m.enemies() if m.spawns(e, diff) and not (e.props.get("dialog") or "").strip()]
        taken = set()
        for i in order:
            o, tier = loot[i]
            best, bd = None, None
            for e in mobs:
                if e.id in taken:
                    continue
                d = ((e.x - o.x) ** 2 + (e.y - o.y) ** 2) ** 0.5
                if d <= 3 * m.tw and (bd is None or d < bd):
                    best, bd = e, d
            if best is not None:
                taken.add(best.id)
                roles.setdefault(best.id, {})[diff] = (o.id, tier, round(bd / m.tw, 2))
    return roles


def script_refs(text, oid):
    hits = []
    for key in ("deleteMapObject", "activateMapObject", "battleWithActorID"):
        if re.search(r'(?:&quot;|")%s(?:&quot;|")\s*:\s*%d\b' % (key, oid), text):
            hits.append(key)
    return hits


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("repo", help="repo root (REQUIRED, read only)")
    ap.add_argument("--provenance", required=True)
    ap.add_argument("--out-dir", default=HERE)
    a = ap.parse_args()
    repo = os.path.abspath(a.repo)
    T = C.load_tfrmaps(repo)
    cat = C.Catalog(repo)
    if cat.duplicate_names:
        print("note: enemies.json repeats %s - the game takes the first" % cat.duplicate_names)
    prov = json.load(open(a.provenance, encoding="utf-8"))
    legends = legendary_names(repo)
    include = include_flags(repo)
    mroot = C.maps_dir(repo)
    head = subprocess.run(["git", "-C", repo, "rev-parse", "--short=11", "HEAD"], capture_output=True, text=True).stdout.strip()

    # POIs and the teleport graph, for the "belongs here" evidence
    pois = json.load(open(os.path.join(C.plane_dir(repo), "world", "points_of_interest.json"), encoding="utf-8"))
    poi_of = collections.defaultdict(list)
    for p in pois:
        mp = (p.get("map") or "").replace("\\", "/")
        k = mp.find("maps/map/")
        if k >= 0:
            poi_of[mp[k + len("maps/map/"):]].append(p)
    maps = {}
    for path in T.all_maps(mroot):
        m = T.TMap(path, mroot)
        maps[m.rel] = m
        for o in m.objects:
            tp = (o.props.get("teleport") or "").replace("\\", "/")
            k = tp.find("maps/map/")
            if k >= 0:
                CHILD_OF.setdefault(tp[k + len("maps/map/"):], []).append(m.rel)

    def poi_chain(rel, depth=0, seen=None):
        seen = seen or set()
        if rel in seen or depth > 8:
            return []
        seen.add(rel)
        if poi_of.get(rel):
            return [(rel, p) for p in poi_of[rel]]
        out = []
        for parent in sorted(set(CHILD_OF.get(rel, []))):
            out += poi_chain(parent, depth + 1, seen)
        return out

    changes, refused, notes = [], [], []
    by_map = collections.defaultdict(list)
    for rel, m in sorted(maps.items()):
        text = open(m.path, encoding="utf-8", errors="replace").read()
        roles = None
        for e in m.enemies():
            name = (e.props.get("enemy") or "").strip()
            first = prov.get("%s#%d" % (rel, e.id), {}).get("first", "?")
            if first not in TOOL_ROUNDS:
                continue
            d = cat.get(name)
            if not d:
                refused.append((rel, e.id, name, "enemy not in enemies.json"))
                continue
            rank = C.tier_rank(d.get("tier") or "Common")
            unique = is_unique_character(d)
            is_chest = first == "r286b" and rank > 0
            if not (is_chest or unique):
                if norm(name) in legends and d.get("spawnRate", 1) and (d.get("deck") or [""])[0].startswith("decks/standard/"):
                    notes.append("%s #%d %s: shares a legendary card's name but is an ordinary enemy (standard deck, "
                                  "spawnRate %s) - not a legend" % (rel, e.id, name, d.get("spawnRate")))
                continue
            if roles is None:
                roles = roles_of(m)
            role = roles.get(e.id, {})
            booster_on = [k for k, v in role.items() if v[1] == "booster"]
            # the tier band this placement needs
            if is_chest:
                if booster_on:
                    refused.append((rel, e.id, name, "a booster's registered guard on %s - booster guards stay Adept+"
                                    % "/".join(booster_on)))
                    continue
                want = "apprentice"
            else:
                placed_chest = first in ("r286b", "r287") and (e.own.get("threatRange") or "") == "20"
                want = "apprentice" if (placed_chest and not booster_on) else "adept+"
            # the placement itself must be a plain clone
            bad = [k for k in ("dialog", "defeatDialog", "effect", "reward", "displayNameOverride", "questStageID",
                               "spawnCondition", "deckOverride") if (e.props.get(k) or "").strip()]
            bad += script_refs(text, e.id)
            if (not e.visible) or str(e.props.get("hidden", "")).lower() == "true" or \
                    str(e.props.get("inactive", "")).lower() == "true":
                bad.append("hidden/inactive")
            if bad:
                refused.append((rel, e.id, name, "not a plain clone: " + ", ".join(bad)))
                continue
            pick = PICKS.get((rel, name))
            if not pick:
                refused.append((rel, e.id, name, "no pick in PICKS for (%s, %s)" % (rel, name)))
                continue
            new, why = pick
            if why == "EYE_ELDRAZI":
                why = EYE_ELDRAZI
            err = validate_pick(repo, cat, legends, include, new, want)
            if err:
                refused.append((rel, e.id, name, "pick %r rejected: %s" % (new, err)))
                continue
            nd = cat.by_name[new]
            fly_old = str(e.own.get("flying", d.get("flying") or False)).lower() == "true" if "flying" in e.own \
                else bool(d.get("flying"))
            fly_new = bool(nd.get("flying"))
            route = (e.props.get("waypoints") or "").strip()
            if route and fly_old != fly_new and "flying" not in e.own:
                refused.append((rel, e.id, name, "has a patrol route (%s) validated for a %s; %r is a %s"
                                % (route, "flier" if fly_old else "walker", new, "flier" if fly_new else "walker")))
                continue
            rec = {
                "map": rel, "id": e.id, "x": e.x, "y": e.y, "tile": [int(e.x // 16), int(e.y // 16) - 1],
                "old": name, "new": new,
                "tier_before": C.RANK_NAME[rank], "tier_after": C.RANK_NAME[C.tier_rank(nd.get("tier") or "Common")],
                "tier_field_before": d.get("tier"), "tier_field_after": nd.get("tier"),
                "life_before": d.get("life"), "life_after": nd.get("life"),
                "flying_before": fly_old, "flying_after": fly_new, "route": route or None,
                "placed_by": first, "threatRange": e.own.get("threatRange"),
                "kind": "+".join(k for k, on in (("chest", is_chest), ("legend", bool(unique))) if on),
                "unique_because": unique, "role": {k: list(v) for k, v in role.items()}, "want": want, "why": why,
            }
            if unique:
                auth = [x for x in m.enemies() if (x.props.get("enemy") or "").strip() == name
                        and prov.get("%s#%d" % (rel, x.id), {}).get("first") == "r256"]
                marks = []
                for x in auth:
                    mk = []
                    for k, lab in (("effect", "crowned"), ("defeatDialog", "defeatDialog"), ("reward", "own reward"),
                                   ("dialog", "dialog NPC"), ("displayNameOverride", "named")):
                        if (x.props.get(k) or "").strip():
                            mk.append(lab)
                    on = "".join(k[0] for k in DIFFS if m.spawns(x, k))
                    marks.append({"id": x.id, "spawns_on": on, "marks": mk})
                others = [x.id for x in m.enemies() if (x.props.get("enemy") or "").strip() == name and x.id != e.id
                          and prov.get("%s#%d" % (rel, x.id), {}).get("first") in TOOL_ROUNDS]
                chain = poi_chain(rel)
                dialog_mentions = 0
                for x in m.objects:
                    for k in ("dialog", "defeatDialog"):
                        v = x.props.get(k) or ""
                        if v and name.split(",")[0].split(" ")[0].lower() in v.lower() and x.id != e.id:
                            dialog_mentions += 1
                rec["belongs"] = {
                    "verdict": "yes - authored here" if auth else "no - not authored in this map",
                    "authored": marks, "other_tool_copies": others,
                    "poi": [{"via": via, "name": p.get("displayName") or p.get("name"), "type": p.get("type"),
                             "tags": p.get("questTags")} for via, p in chain],
                    "catalog": {"spawnRate": d.get("spawnRate"), "life": d.get("life"), "speed": d.get("speed"),
                                "rewards": len(d.get("rewards") or []), "deck": (d.get("deck") or [""])[0]},
                    "dialogs_naming_it_in_map": dialog_mentions,
                    "copy_is_plain": True, "script_refs": [],
                }
            changes.append(rec)
            by_map[rel].append(rec)

    # found on the way (reported, not changed): boosters whose registered guard on Hard is an Apprentice
    appr_boosters = []
    planned = {(c["map"], c["id"]) for c in changes}
    for rel, m in sorted(maps.items()):
        if not any(t == "booster" for _o, t in m.loot(spawned_only=False)):
            continue
        for eid, rr in roles_of(m).items():
            h = rr.get("Hard")
            if not h or h[1] != "booster":
                continue
            name = (m.by_id[eid].props.get("enemy") or "").strip()
            if C.tier_rank((cat.get(name) or {}).get("tier") or "Common") == 0:
                appr_boosters.append({"map": rel, "id": eid, "enemy": name, "booster": h[0],
                                      "placed_by": prov.get("%s#%d" % (rel, eid), {}).get("first", "?"),
                                      "fixed_by_this_plan": (rel, eid) in planned})

    plan = {"generated": datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "repo": repo, "repo_head": head,
            "apprentice_booster_guards": appr_boosters,
            "rule": "tier = EnemyData.tierRank(enemies.json tier): Common 0 Apprentice, Uncommon 1 Adept, Rare 2 Master, "
                    "Mythic 3 Archmage",
            "maps": {rel: {"sha1": sha1(maps[rel].path), "changes": [{k: c[k] for k in ("id", "x", "y", "old", "new")}
                                                                     for c in sorted(v, key=lambda c: c["id"])]}
                     for rel, v in sorted(by_map.items())},
            "changes": changes, "refused": [list(r) for r in refused], "notes": notes}
    os.makedirs(a.out_dir, exist_ok=True)
    json.dump(plan, open(os.path.join(a.out_dir, "plan.json"), "w", encoding="utf-8"), indent=1)
    write_md(plan, maps, cat, os.path.join(a.out_dir, "plan.md"))
    n_chest = sum(1 for c in changes if "chest" in c["kind"])
    n_leg = sum(1 for c in changes if "legend" in c["kind"])
    print("%d change(s) in %d map(s): %d chest guard(s) to Apprentice, %d legend-named guard(s) renamed "
          "(%d are both); %d refused" % (len(changes), len(by_map), n_chest, n_leg,
                                          sum(1 for c in changes if c["kind"] == "chest+legend"), len(refused)))
    for r in refused:
        print("REFUSED %s #%d %s: %s" % r)
    return 1 if refused else 0


# ------------------------------------------------------------------------------------------------ plan.md
def write_md(plan, maps, cat, path):
    ch = plan["changes"]
    chest = [c for c in ch if "chest" in c["kind"]]
    leg = [c for c in ch if "legend" in c["kind"]]
    kept = collections.OrderedDict()
    for c in leg:
        for a_ in c["belongs"]["authored"]:
            kept[(c["map"], a_["id"])] = (c["old"], a_)
    L = []
    w = L.append
    w("# Round 322 plan: chest guards to Apprentice, legend-named guards renamed")
    w("")
    w("Generated %s from `%s` (HEAD `%s`). **Nothing here has been applied to the repo.** `apply.py <repo root>` writes "
      "it; it changes only the `enemy` value of the objects listed below." % (plan["generated"], plan["repo"],
                                                                             plan["repo_head"]))
    w("")
    w("## The short version")
    w("")
    w("* **%d changes in %d maps**, each the `enemy` property of one guard placed by a guard tool (rounds 279/286b/287) - "
      "position, threatRange, patrol route and every other property stay as they are. Nothing is removed or moved."
      % (len(ch), len(plan["maps"])))
    n_hard = sum(1 for c in chest if (c["role"].get("Hard") or [0, ""])[1] == "treasure")
    w("* **Chest guards: %d swapped to Apprentice.** Every enemy round 286b placed as a chest guard whose tier is above "
      "Apprentice (%s). %d of them are a chest's registered guard on Hard (round 319 counted these: 75); the other %d "
      "guard chests authored with `spawn.Hard=false` - they are those chests' registered guards on Easy, Normal and "
      "Insane. None is a booster's guard on any difficulty." % (
          len(chest), ", ".join("%d %s" % (n, t) for t, n in sorted(collections.Counter(c["tier_before"] for c in chest).items(),
                                                                  key=lambda kv: -kv[1])), n_hard, len(chest) - n_hard))
    w("* **Legend-named guards: %d renamed**, %d of them also in the chest list above. Every one is a guard-tool COPY "
      "of a character the map's author placed in the same map - so **the legends do belong in those dungeons, and all "
      "%d authored placements stay exactly as they are**; only the extra copies get an ordinary name. None of the copies "
      "carries anything of its own: no crown/effect, dialog, defeatDialog, reward or script reference - a bare "
      "`enemy` + `threatRange`." % (len(leg), sum(1 for c in leg if "chest" in c["kind"]), len(kept)))
    w("* **Tier after the change:** chest guards all Apprentice; legend copies that are booster guards keep Adept or "
      "better (round 279/287's booster rule), the tier of the legend where an ordinary creature of the same kind exists.")
    w("")
    w("## How the tier is computed (exactly as the game does)")
    w("")
    w("`EnemyData.tier` from `world/enemies.json` (default `Common`), looked up the way `WorldData.getEnemy()` does (first "
      "entry by `name`, then by display name). `EnemyData.tierRank()` / `tierDisplayName()`: `Uncommon` = 1 Adept, "
      "`Rare` = 2 Master, `Mythic` = 3 Archmage, anything else = 0 Apprentice. \"Above Apprentice\" = rank > 0. "
      "(`enemies.json` has no duplicate names today, so first/last-wins lookups agree.) Which round placed an enemy comes "
      "from `dev-tools/guard_patrols/provenance.py` (git history: present at round 256 = hand-authored).")
    w("")
    w("## 1. Legend-named guards - do the legends belong in those dungeons?")
    w("")
    w("A \"legend\" here is a catalog entry with the `legend` or `boss` flag or a deck under `decks/legends/` (Forge's "
      "named-legend commander decks; every such entry the tools copied also has spawnRate 0, i.e. "
      "`SpawnTierWeighting.isExempt()`); every name was also checked against Forge's card database (legendary cards). "
      "For each map: the authored placements of the same legend (kept), the POI the map belongs to, and what the copy is.")
    w("")
    groups = collections.OrderedDict()
    for c in sorted(leg, key=lambda c: (c["map"], c["old"], c["id"])):
        groups.setdefault((c["map"], c["old"]), []).append(c)
    w("| map (POI) | legend | authored, KEPT | guard-tool copies, renamed | new name (tier) | evidence it belongs / what the copy is |")
    w("|---|---|---|---|---|---|")
    for (rel, old), cs in groups.items():
        b = cs[0]["belongs"]
        poi = "; ".join("%s%s [%s%s]" % (p["name"], " via " + p["via"] if p["via"] != rel else "", p["type"],
                                         (", " + "/".join(p["tags"] or [])) if p["tags"] else "") for p in b["poi"]) or "?"
        auth = ", ".join("#%d (%s%s)" % (x["id"], x["spawns_on"], (": " + ", ".join(x["marks"])) if x["marks"] else "")
                         for x in b["authored"])
        copies = ", ".join("#%d %s" % (c["id"], c["placed_by"]) for c in cs)
        new = "%s (%s)" % (cs[0]["new"], cs[0]["tier_after"])
        cat_ = b["catalog"]
        rounds = sorted({c["placed_by"] for c in cs})
        ev = ("the map's author placed %s here (%d placement%s%s); the POI is %s. The %s added by %s next to "
              "loot, bare (enemy + threatRange only)" % (
                  old, len(b["authored"]), "s" if len(b["authored"]) != 1 else "",
                  ", one per difficulty pair" if len(b["authored"]) == 2 and len({x["spawns_on"] for x in b["authored"]}) == 2 else "",
                  poi, "copy was" if len(cs) == 1 else "copies were",
                  " and ".join(ROUND_WHAT.get(r, r) for r in rounds)))
        if cat_["life"] == 1 and cat_["speed"] == 0:
            ev += "; **%s is a dialog NPC's stand-in entry (life 1, speed 0, no rewards, the 'Mystery List' deck of 99 " \
                  "Wastes)** - the copy is a guard that cannot move and dies to one point of damage" % old
        if any("boss" in u for c in cs for u in c["unique_because"]):
            ev += "; %s has the **boss** flag, so beating a copy counts as beating the boss (MapStage.getReward -> " \
                  "DungeonRotation.onLairBossDefeated, the boss's reward list)" % old
        w("| %s (%s) | %s | %s | %s | %s | %s. %s |" % (rel, poi, old, auth or "-", copies, new, ev, cs[0]["why"]))
    w("")
    w("**Kept because they belong (authored, untouched): %d placements.** Quests in `quests.json` target enemies by "
      "TAG, never by name, and these legend entries carry no quest tags - so no quest targets any copy; the authored "
      "placements keep their crowns, dialogs, map-flag counters (`advanceMapFlag` in their defeatDialogs) and rewards." % len(kept))
    w("")
    w("## 2. Chest guards above Apprentice -> Apprentice")
    w("")
    w("No map in this list has a hand-authored Apprentice (that is why round 286b fell back to the map's commonest "
      "enemy of any rank - the bug round 287 fixed for later runs). The only Apprentices in them are round 287's "
      "fallback Skeletons, a placeholder, so the pick is the Apprentice that fits the map's own theme: the same "
      "creature's lower rank where one exists (wizard ladder, whelp for dragon, owlet for owl), else the map's kind and "
      "colors; typical Apprentice strength (life 10-17; the median Apprentice has 12).")
    w("")
    w("| map | object | tile | old enemy (tier) | new enemy (tier) | life | why |")
    w("|---|---|---|---|---|---|---|")
    for c in sorted(chest, key=lambda c: (c["map"], c["id"])):
        role = c["role"].get("Hard")
        w("| %s | #%d | (%d,%d) | %s (%s) | %s (%s) | %s -> %s | %s%s%s |" % (
            c["map"], c["id"], c["tile"][0], c["tile"][1], c["old"], c["tier_before"], c["new"], c["tier_after"],
            c["life_before"], c["life_after"], c["why"],
            "" if (role and role[1] == "treasure") else
            " (its chest has spawn.Hard=false; it is that chest's guard on %s)" % "/".join(
                k for k in DIFFS if (c["role"].get(k) or [0, ""])[1] == "treasure"),
            "; keeps its patrol %s" % c["route"] if c["route"] else ""))
    w("")
    w("## 3. Legend-named guards -> ordinary enemies (all %d)" % len(leg))
    w("")
    w("| map | object | old (tier) | new (tier) | guard of (Hard) | why |")
    w("|---|---|---|---|---|---|")
    for c in sorted(leg, key=lambda c: (c["map"], c["id"])):
        role = c["role"].get("Hard")
        w("| %s | #%d | %s (%s) | %s (%s) | %s | %s |" % (
            c["map"], c["id"], c["old"], c["tier_before"], c["new"], c["tier_after"],
            "%s %d" % (role[1], role[0]) if role else "-", c["why"]))
    w("")
    w("## Judgment calls (flip any of them by editing PICKS in plan.py and re-running)")
    w("")
    w("* **Eldrazi prisons, cave_eldrazi, grove_9_eldrazi, fort_colorless_7, Ancient Opal Cavern -> Eye.** enemies.json "
      "has no Apprentice Eldrazi (no Scion/Spawn/Drone; the plain `Eldrazi` is Adept, the Devastator and Floater "
      "Master). Eye is a typical Apprentice aberration (life 11) but it FLIES where the Eldrazi walked. Alternatives: "
      "Unraveling Crawler (walks, pale crawler, but life 20), Flesh Abomination (looks most like Eldrazi flesh, but life "
      "32 - the strongest Apprentice in the game); or accept the Adept `Eldrazi` in these 10 places; or add an "
      "Apprentice Eldrazi Scion to enemies.json (new content).")
    w("* **Sliver Queen's lair -> Triplate Crawler:** no Apprentice sliver exists (the weakest are Adept).")
    w("* **Gwafa Hazid #192 (Valor's Reach Arena) -> Rogue, ADEPT.** Placed by round 287 as a chest guard (threatRange "
      "20), but the game's matching makes it booster 102's registered guard on every difficulty, so the booster rule "
      "(Adept+) is applied. For Apprentice instead: Cutpurse.")
    w("* **Agatha -> Witch is one tier down** (Master -> Adept): the ordinary witch in Agatha's own sprite; no ordinary "
      "Master-tier witch exists. Booster floor kept.")
    w("* **Pirates (shard_mines, merfolkpool_5) -> Brinebone Buccaneer**, an undead pirate: the only living Apprentice "
      "pirate (`Pirate Captain 2`, shown as \"Pirate Captain\") carries the `Captain` tag that quest 45's \"Defeat the "
      "mine captain\" accepts inside the shard mines (round 323's fix) - two of them there would each complete the "
      "quest in place of the mines' own captain (#25). No new name in this plan carries `Captain`.")
    w("* Flying changes (fliers go straight, over walls, when they chase): walker -> flier for the Eye picks, Galewrapped "
      "Specter -> Geist, Veyran -> Efreet, Baron Bertram -> Vampire Lord, Regna and Krav -> Spiked Ravager; flier -> "
      "walker for Necrogoyf -> Gazestalk Ooze, Wasp -> Pilfer Beetle, Felisa -> Bloodveil Countess. The two placements "
      "with round-319 patrol routes (evilgrove_2_blackgolem #97, maze_2 #77) keep walkers, so their validated routes "
      "stay valid.")
    w("")
    w("## Not changed")
    w("")
    w("* Booster guards (round 279/287 made them Adept+ on purpose) and every hand-authored placement.")
    w("* **Round 284's Orthodoxy Angel** (phyrexian_w1 #86, Adept) guards the Basilica's two chests - the one chest "
      "guard above Apprentice that round 286b did not place: you asked for it by hand in round 284, and no white "
      "Phyrexian Apprentice exists. Left as is.")
    if plan["refused"]:
        w("* Refused by the planner: " + "; ".join("%s #%s %s: %s" % tuple(r) for r in plan["refused"]))
    for n in plan["notes"][:20]:
        w("* " + n)
    w("")
    w("## Found on the way (not changed - your call)")
    w("")
    ab = plan.get("apprentice_booster_guards") or []
    tool_ab = [x for x in ab if x["placed_by"] in TOOL_ROUNDS and not x["fixed_by_this_plan"]]
    n_fixed = len([x for x in ab if x["fixed_by_this_plan"]])
    w("* **%d boosters are guarded by an Apprentice in the game's own matching (Hard)**, against round 279/287's "
      "\"boosters Adept+\": `assignLootGuards()` gives each booster the NEAREST free enemy, so an Apprentice standing "
      "closer to a booster than the booster's own Adept guard takes it. %d of those Apprentices are guard-tool "
      "chest guards (e.g. %s), %d are hand-authored. This plan fixes %d (Gwafa Hazid #192 -> Rogue), leaving %d. Not "
      "touched here - you said to leave booster guards alone - but it is why a booster can still be guarded by a "
      "Skeleton (Tibalt's lair: booster 167's guard is round 287's Skeleton #180)." % (
          len(ab), len([x for x in ab if x["placed_by"] in TOOL_ROUNDS]),
          ", ".join("%s #%d %s" % (x["map"].split("/")[-1], x["id"], x["enemy"]) for x in tool_ab[:4]),
          len([x for x in ab if x["placed_by"] not in TOOL_ROUNDS]), n_fixed, len(ab) - n_fixed))
    w("* **Why the tools copied NPCs:** `booster_guards.objects_of()` reads `dialog` from a property's `value` "
      "attribute only; a multi-line dialog is element text, so dialog NPCs looked like fighters and became the "
      "\"commonest enemy\" the tools cloned - Mysterious Mage x3, Zo-Zu, Gwafa Hazid (fixed here), and harmless "
      "same-name cases (skep_outer's 4 Archers, black_castle's 2 Demons, green_castle's 2 Dinosaurs are real enemy "
      "entries). Worth fixing in the tool before it is run again.")
    w("* Round 279's `upgrade_booster_guards.py` also RENAMED 41 hand-authored guards (Apprentice -> Adept+); none of "
      "those new names is a legend or a spawnRate-0 special, so the legend list above is complete.")
    w("")
    w("## Save games")
    w("")
    w("A dungeon the player has already entered keeps the names it rolled on the first visit (round 201's fixed roster, "
      "`PointOfInterestChanges.dungeonRoster`, keyed by object id): the stored name wins over the map. So on an existing "
      "save the new names appear in dungeons not yet entered, and in rotating dungeons once they rotate "
      "(`DungeonRotation.hidePoi` clears the roster). The legend dungeons are Story POIs, which never rotate - a Story "
      "dungeon already visited keeps its copies on that save until its roster is cleared (a save edit) or a new game.")
    w("")
    w("## apply.py")
    w("")
    w("`python apply.py <repo root>` (the root is required, no default; `--dry-run` checks without writing). It refuses "
      "a map whose SHA-1 changed since this plan, checks each object is still the planned enemy at the planned "
      "position, edits only the `value` of its `<property name=\"enemy\">`, keeps every other byte (line endings, BOM), "
      "and re-parses the result: every object and property must be identical except the planned names.")
    res = os.path.join(os.path.dirname(path), "checks", "results.md")
    if os.path.exists(res):
        w("")
        L.extend(open(res, encoding="utf-8").read().rstrip("\n").split("\n"))
    open(path, "w", encoding="utf-8", newline="\n").write("\n".join(L) + "\n")


if __name__ == "__main__":
    sys.exit(main())
