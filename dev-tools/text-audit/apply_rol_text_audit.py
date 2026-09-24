"""Realm of Legends text audit - apply the wording changes to a checkout of the TFR repo.

Usage:  python apply_rol_text_audit.py <repo_root> [--sets rol,typos,shandalar] [--check]

Edits the dialog text of the dungeons imported from the Realm of Legends plane so it fits
The Forsaken Realms' own story (the Guardian, the Warden, the Five, the Seals), fixes typos
in those dialogs, and removes the leftover "Shandalar" mentions in stock-derived maps.
Every replacement is an exact-substring edit on the raw .tmx text (XML-escaped), checked to
match exactly the expected number of times; the script refuses to write anything otherwise.
--check only reports what would change.
"""
import sys, os, re, json, argparse, xml.etree.ElementTree as ET

ap = argparse.ArgumentParser()
ap.add_argument("root")
ap.add_argument("--sets", default="rol,typos,shandalar")
ap.add_argument("--check", action="store_true")
args = ap.parse_args()
ROOT = args.root
PLANE = os.path.join(ROOT, "forge-gui", "res", "adventure", "The Forsaken Realms")
MAPS = os.path.join(PLANE, "maps", "map")
assert os.path.isdir(MAPS), f"not a TFR repo root: {ROOT}"


def esc(s):  # Tiled's element-text escaping for the characters these texts use
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


# --- the manasight stone (a Realm of Legends start item the TFR player never has) ---
MS_A = ("Your manasight stone glows, sensing the power of the legends here.",
        "Your spark stirs, sensing the power gathered here.")
MS_B = ("Your manasight stone glows brightly, sensing a truly mighty legend in this place.",
        "Your spark stirs, sensing something truly mighty in this place.")
MS_C = ("Your manasight stone blazes with power, near-blindingly bright.",
        "Your spark blazes in answer, brighter than it has since you woke.")

ROL = {
 "barbariancamp/Ashlings_Domain.tmx": [
   ("A twisting purple eclipsed sky twists ahead, a remnant of Lorwyn/Shadowmoor it was drawn from.",
    "A twisting purple eclipse hangs in the sky ahead - a piece of far Shadowmoor, torn loose and stranded here with everything beneath it."),
   MS_A],
 "cave/Planeswalker_Dueling_Club.tmx": [
   MS_A,
   ("'A group of planeswalkers have come here to hone their dueling skills with the hopes of escaping this forsaken realm. I'm not a planeswalker myself, I just like watching people fight! If you defeat them all, I've got a fine reward waiting for you!'",
    "'A group of planeswalkers, stranded here like the rest of us, hone their dueling skills in the hope of one day walking out of this forsaken realm. I'm not a planeswalker myself, I just like watching people fight! If you defeat them all, I've got a fine reward waiting for you!'")],
 "evilgrove/Eclipsed_Elven_Court.tmx": [
   ("A strange realm stretches out ahead", "A strange country stretches out ahead"),
   MS_A],
 "barbariancamp/Tarnation_1.tmx": [MS_B],
 "evilgrove/Church_of_Valgavoth_1.tmx": [MS_B],
 "evilgrove/Gitrog_Bog_1.tmx": [MS_B],
 "fort/Kenriths_Court.tmx": [MS_B],
 "grove/Squirrel_Farm.tmx": [MS_B],
 "merfolkpool/Wizard_Palace_1.tmx": [MS_B],
 "cave/Ancient_Opal_Cavern.tmx": [
   MS_C,
   ("The threat here is not necessary for your quest, but guards a powerful spell not available elsewhere.",
    "Whatever sleeps here has no part in your war with the Five - but it guards a spell found nowhere else in the realm.")],
 "cave/Eldrazi_Prison_0.tmx": [
   MS_C,
   ("You are confident that this portal is the one you've been looking for throughout your entire quest - a way to leave this realm and return to your home.",
    "Nothing about it was built to keep anyone out. It was built to keep something in, and its wards are older than the Night of Chains - older than anything you remember. Whatever waits beyond has been waiting far longer than you slept.")],
 "evilgrove/Court_of_Paliano.tmx": [
   ("I was close to overthrowing her when we all got dragged here. And now I've been stuck under some frustrating pacifism enchantment, not able to do anything but watch like a caged bird as she plots to seize power in this world just like in our home.",
    "I was close to overthrowing her when this realm swallowed the lot of us. And now I've been stuck under some frustrating pacifism enchantment, able to do nothing but watch like a caged bird while she plots to seize power here just as she did at home.")],
 "fort/Peaceful_Clearing.tmx": [
   ("Despite the calm attitude of the place, there is a lingering sense of danger behind it all, as you can tell the curse of violence still clings, even in a place like this, driving the beings ahead to fight if you confront them.",
    "Despite the calm of the place, there is a lingering sense of danger behind it all. Even here, the realm's old hunger for battle clings to everyone in it, and the beings ahead will fight if you confront them.")],
 "merfolkpool/Idyllic_Beachfront.tmx": [
   ("A group of merfolk and other beachgoers gather about, relaxing for a brief moment before the curse of violence grips them once more.",
    "A group of merfolk and other beachgoers gather about, relaxing while they can. The Forsaken Realms do not let anyone rest for long.")],
 "fort/Three_Tree_City.tmx": [
   ("Its a shame they're all ready to fight anyway from the curse of violence in this world, but",
    "It's a shame this realm has them all so ready to fight anyway, but")],
}

TYPOS = {
 "cave/Planeswalker_Dueling_Club.tmx": [("furhter observation", "further observation")],
 "evilgrove/Gitrog_Bog_1.tmx": [("several frog wearing clothes", "several frogs wearing clothes")],
 "evilgrove/Gitrog_Bog_2.tmx": [("a single magical lock afixed to it", "a single magical lock affixed to it")],
 "fort/Kenriths_Court.tmx": [("The architecture is remniscent of", "The architecture is reminiscent of")],
 "grove/Squirrel_Farm.tmx": [("A farm spreads out before. The oddest", "A farm spreads out before you. The oddest")],
 "fort/Three_Tree_City.tmx": [("Not some flightless bird! Its ridiculous.", "Not some flightless bird! It's ridiculous.")],
 "barbariancamp/Prismari_Classroom.tmx": [("The classrom ahead", "The classroom ahead")],
 "evilgrove/Silverquill_Classroom.tmx": [("The inhabitants of the classrom ahead", "The inhabitants of the classroom ahead")],
 "fort/Lorehold_Classroom.tmx": [("The classrom ahead", "The classroom ahead")],
 "grove/Witherbloom_Classroom.tmx": [("The classrom ahead", "The classroom ahead")],
 "merfolkpool/Quandrix_Classroom.tmx": [("The classrom ahead", "The classroom ahead"), ("divided  by zero", "divided by zero")],
 "fort/Omenport.tmx": [("A huge manor stis on the corner hill", "A huge manor sits on the corner hill")],
 "cave/Valors_Reach_Arena.tmx": [("Its PIR AND", "It's PIR AND"), ("its REGNA AND KRAV", "it's REGNA AND KRAV"),
                                 ("its the two-fer with the lure", "it's the two-fer with the lure"), ("its GROOOOOTHAMAAAAAA", "it's GROOOOOTHAMAAAAAA")],
}

SHANDALAR = {
 "aerie/aerie_0.tmx": [
   ("unmatched compared to those of other sellers in Shandalar!", "unmatched compared to those of other sellers in the realm!"),
   ("breeders of the various bird species of Shandalar.", "breeders of the various bird species of the realm."),
   ("scattered around the various biomes of Shandalar.", "scattered around the realm's lands.")],
 "lair/ancient_diamond_mine.tmx": [("remnants of Shandalar's earliest age", "remnants of the realm's earliest age")],
 "main_story_explore/library_of_varsil_2.tmx": [
   ("difficulties in Shandalar communities", "difficulties in the realm's communities"),
   ("a type of Elf that you've seen nowhere on Shandalar.", "a type of Elf that you've seen nowhere in the realm.")],
 "main_story_explore/library_of_varsil_3.tmx": [
   ("'Shandalaar's Most Burnable Cities'", "'The Realm's Most Burnable Cities'"),
   ("dwarfs the largest cities of Shandalar.", "dwarfs the largest cities of the realm.")],
 "minibosses/slime_hive.tmx": [("The inscription reads: The Shandalar sewer system is currently under maintenance.",
                                "The inscription reads: This sewer system is currently under maintenance.")],
 "main_story/temple_of_liliana/town.tmx": [("set loose upon Shandalar!!!", "set loose upon the realm!!!")],
 "skep/skep_outer.tmx": [
   ("an army large enough to conquer the whole of Shandalar.", "an army large enough to conquer the whole realm."),
   ("hive queen of the slivers of Shandalar,", "hive queen of the slivers of the realm,"),
   ("The people of Shandalar shouldn't fear the Slivers", "The people of the realm shouldn't fear the Slivers"),
   ("will never be a danger to the people of Shandalar.", "will never be a danger to the people of the realm."),
   ("raiding parties into the various towns of Shandalar.", "raiding parties into the various towns of the realm."),
   ("Outside of the thanks of the people of Shandalar for dealing", "Outside of the thanks of the people of the realm for dealing")],
}
SHANDALAR_JSON = {  # world/items.json - a plain description string, no escaping
 "world/items.json": [("sketches of various landscapes across Shandalar", "sketches of various landscapes across the realm")],
}


# --- Hall of the Unifier: Jodah's speech and the ending, rewritten for TFR ---
def chain(nodes, last_name, last_text, final_name):
    """Build the nested (Continue) dialog chain the map uses."""
    tail = {"name": last_name, "text": last_text,
            "options": [{"name": final_name, "action": [{"deleteMapObject": -1}]}]}
    for name, text in reversed(nodes[1:]):
        tail = {"name": name, "text": text, "options": [tail]}
    return [{"text": nodes[0][1], "options": [tail]}]


JODAH_INTRO = chain([
  (None, "The mysterious mage stands before you - the same hooded figure who has sold you spells in the realm's far corners. He lowers his hood.\n\n'You know my face, Guardian, even if you never asked my name. I have been watching you since the Ring cracked your prison. I imagine you have questions - why I helped you, and what I am doing at the bottom of your realm.'"),
  ("So, care to explain?", "'I am Jodah, once called the Unifier. I am not of this realm. I came here long before your Night of Chains, chasing a rumor of doors beneath the world that were never meant to open - and I found them. This prison is one of them. I have kept its wards fed for longer than your wardens kept your vigil.'"),
  ("(Continue)", "'The titans you fought were never the worst of it. They are what the door lets you see. Its locks are older than your Seals, and when the Five came to bind you they had learned their craft from somewhere. Draw your own conclusions. I drew mine long ago.'"),
  ("(Continue)", "'I hid the keys to this hall behind the mightiest of the realm's stranded legends because I needed an answer: is the one who wakes strong enough to hold this door, or only strong enough to open it? Every fight you have won down here was a question. This is the last of them.'"),
  ("(Continue)", "'There are a few final gifts here, the last of my rare spells. A small token - you have earned far more, but it is all I have left to give.'"),
  ("(Continue)", "'And here is my confession. A door works on its keeper as surely as the keeper works on the door. What waits behind it whispers, and I have listened for far too long. I have been holding back the urge to crush you since you walked in, and I can feel that restraint slipping.'"),
  ("(Continue)", "'So, Guardian. Before you go any further, you go through me. Call it the last lock.'"),
 ], "It's a duel.", "'Good. I am rooting for you, though it may not seem like it. Win, and the door is yours - to keep shut, or to open. Choose better than I did.'",
 "You ready yourself for the final battle")

JODAH_DEFEAT = [{
  "text": "After a battle of unrivaled intensity, Jodah finally falls. A smile crosses his face as his blood pools on the stone. 'Excellent... well done, {var=player_name}. The door is yours now. Keep it better than I did.' With that he collapses, dead despite his immortality.",
  "options": [{"name": "(Continue)",
    "text": "The Hall falls silent. Beyond the mirror, nothing stirs - for now.\n\n[RED]Developer's note:[] You have beaten the hardest fight in the Forsaken Realms. The realm fights on - territory, research, restoration and the Five continue - and New Game+ waits on the Load Game screen whenever you want to carry your collection into a fresh run.",
    "options": [{"name": "(Continue)", "action": [{"deleteMapObject": -1}]}]}]}]

MIRROR = [{"text": "The mirror ahead ripples and shimmers. Whatever it once opened onto, it now shows only the way back out.",
           "options": [{"name": "(Continue)", "action": [{"deleteMapObject": -1}]}]}]


def dialog_json(data):
    s = json.dumps(data, indent=2, ensure_ascii=False)
    return s.replace("\\n", "\n")   # the map's own style: literal newlines inside strings


HALL = "cave/Hall_of_the_Unifier.tmx"
HALL_PROPS = [  # (object id, property, new content)
  ("144", "dialog", dialog_json(JODAH_INTRO)),
  ("69", "defeatDialog", dialog_json(JODAH_DEFEAT)),
  ("153", "defeatDialog", dialog_json(JODAH_DEFEAT)),
  ("148", "dialog", dialog_json(MIRROR)),
]


def replace_property(text, obj_id, prop, new_inner):
    m = re.search(r'<object id="%s"[^>]*>' % obj_id, text)
    assert m, f"object {obj_id} not found"
    start = text.index('<property name="%s">' % prop, m.end())
    inner_start = start + len('<property name="%s">' % prop)
    end = text.index("</property>", inner_start)
    return text[:inner_start] + esc(new_inner) + text[end:]


def dialog_failures(path):
    """Which dialog properties of a map fail to parse (lenient JSON)."""
    bad = set()
    tree = ET.parse(path)
    for obj in tree.iter("object"):
        props = obj.find("properties")
        if props is None:
            continue
        for pr in props.findall("property"):
            if pr.get("name") in ("dialog", "defeatDialog"):
                raw = pr.get("value") if pr.get("value") is not None else (pr.text or "")
                try:
                    json.loads(raw.strip(), strict=False)
                except Exception:
                    bad.add((obj.get("id"), pr.get("name")))
    return bad


sets = {"rol": ROL, "typos": TYPOS, "shandalar": SHANDALAR}
chosen = [s.strip() for s in args.sets.split(",") if s.strip()]
problems, changed = [], []

# merge the chosen sets per file
work = {}
for s in chosen:
    for rel, pairs in sets[s].items():
        work.setdefault(rel, []).extend(pairs)

for rel, pairs in sorted(work.items()):
    path = os.path.join(MAPS, rel)
    before = dialog_failures(path)
    text = open(path, encoding="utf-8", newline="").read()
    new = text
    for old, rep in pairs:
        n = new.count(esc(old))
        if n == 0 and esc(rep) in new:
            continue  # already applied (re-run on a patched tree)
        if n != 1:
            problems.append(f"{rel}: expected 1 match, found {n}: {old[:70]!r}")
            continue
        new = new.replace(esc(old), esc(rep))
    if new != text and not problems:
        if not args.check:
            open(path, "w", encoding="utf-8", newline="").write(new)
            after = dialog_failures(path)
            if after - before:
                problems.append(f"{rel}: NEW dialog parse failures {after - before}")
        changed.append(rel)

if "rol" in chosen:
    path = os.path.join(MAPS, HALL)
    before = dialog_failures(path)
    text = open(path, encoding="utf-8", newline="").read()
    new = text
    for oid, prop, content in HALL_PROPS:
        new = replace_property(new, oid, prop, content)
    if "\r\n" in text:  # keep the file's own line endings inside the inserted blocks
        new = new.replace("\r\n", "\n").replace("\n", "\r\n")
    if not args.check:
        open(path, "w", encoding="utf-8", newline="").write(new)
        after = dialog_failures(path)
        if after - before:
            problems.append(f"{HALL}: NEW dialog parse failures {after - before}")
    changed.append(HALL)

if "shandalar" in chosen:
    for rel, pairs in SHANDALAR_JSON.items():
        path = os.path.join(PLANE, rel)
        text = open(path, encoding="utf-8", newline="").read()
        new = text
        for old, rep in pairs:
            n = new.count(old)
            if n == 0 and rep in new:
                continue  # already applied
            if n != 1:
                problems.append(f"{rel}: expected 1 match, found {n}: {old[:70]!r}")
                continue
            new = new.replace(old, rep)
        if new != text and not args.check:
            open(path, "w", encoding="utf-8", newline="").write(new)
            json.load(open(path, encoding="utf-8"))  # must still be valid JSON
        changed.append(rel)

print(("CHECK ONLY - " if args.check else "") + f"sets={chosen} files touched={len(changed)}")
for c in changed:
    print("  ", c)
if problems:
    print("PROBLEMS:")
    for p in problems:
        print("  ", p)
    sys.exit(1)

# leftovers in dialog text across the whole plane
left = []
for dp, dn, fn in os.walk(MAPS):
    for f in fn:
        if not f.endswith(".tmx") or f == "debug_map.tmx":
            continue
        p = os.path.join(dp, f)
        for obj in ET.parse(p).iter("object"):
            props = obj.find("properties")
            if props is None:
                continue
            for pr in props.findall("property"):
                if pr.get("name") in ("dialog", "defeatDialog"):
                    raw = pr.get("value") if pr.get("value") is not None else (pr.text or "")
                    for tok in ("manasight", "shandala", "curse of violence", "mirror gallery", "return to your home", "classrom"):
                        if tok in raw.lower():
                            left.append(f"{os.path.relpath(p, MAPS)} obj {obj.get('id')}: {tok}")
print("leftovers in dialog text:", left if left else "none")
