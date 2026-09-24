"""Stock-text audit - the base game's "captured planeswalkers" premise, left over in TFR's adapted story.

Usage:  python apply_stock_text_audit.py <repo_root> [--sets stock,quests] [--check]

Companion to apply_rol_text_audit.py (the Realm of Legends pass). This one fixes the lines that
still came from stock Forge Adventure's own story after TFR reframed it around the Guardian, the
Warden, the Five and the Seals: the temple mages' ALL-CAPS "YOU HAVE FREED THE LEGENDARY
PLANESWALKER ... FROM CAPTIVITY", the castle bosses' "does not hold any prisoners", quest 52's
"Rescue the X Captive" stages, the Warden's stock "Guardians' spell" line, a debug greeter left
in the plains town ("I am a big gate. Greetings.") and the plains capital's placeholder steward.
Every replacement is an exact-substring edit on the raw file text (XML-escaped for .tmx), checked
to match exactly the expected number of times; idempotent on a patched tree. --check reports only.
"""
import sys, os, json, argparse, xml.etree.ElementTree as ET

ap = argparse.ArgumentParser()
ap.add_argument("root")
ap.add_argument("--sets", default="stock,quests")
ap.add_argument("--check", action="store_true")
args = ap.parse_args()
ROOT = args.root
PLANE = os.path.join(ROOT, "forge-gui", "res", "adventure", "The Forsaken Realms")
MAPS = os.path.join(PLANE, "maps", "map")
assert os.path.isdir(MAPS), f"not a TFR repo root: {ROOT}"


def esc(s):  # Tiled's element-text escaping for the characters these texts use
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


# JSON-escaped double quotes inside the dialog strings, as TFR's own Warden lines use them
Q = '\\"'

def seal(boss, gem, extra_first="", extra_again=""):
    """The five castle bosses: the Seal answers, and the stock 'no prisoners' sentence goes."""
    return [
        (f"As you land your final blow against {boss}, you feel a significant pulse of mana.{extra_first}",
         f"As you land your final blow against {boss}, you feel a significant pulse of mana - the {gem} Seal, answering its Guardian at last."),
        (f"As you land your final blow against {boss}, you feel another pulse of mana.{extra_again}",
         f"As you land your final blow against {boss}, you feel another pulse of mana - the {gem} Seal, answering its Guardian at last."),
    ]

NO_PRISONERS_FIRST = " But with the immediate threat removed, you can now see clearly that the locked room at the north end of the chamber does not hold any prisoners."
NO_PRISONERS_AGAIN = " But just as before, the locked room behind your fallen foe does not hold any prisoners."

def temple(name, who, where, portals=False, hello_suffix=""):
    """The Warden replaces the stock ALL-CAPS mage at a temple. Each tuple: (old, new, expected count).
    hello_suffix: Liliana's greeting line ends in a period, Chandra's does not."""
    pairs = [
        (f"HELLO AGAIN PLANESWALKER {{var=player_name}}{hello_suffix}",
         f"The Warden stands just inside the temple, far from his fire at Orazca. {Q}You made good time, Guardian.{Q}", 1),
        (f"Yeah, yeah, defeat {name}. Got it.", f"{Q}I remember. {name}. Let's finish it.{Q}", 1),
        ("Why am I here?", f"{Q}Why are you here, Warden?{Q}", 1),
        (f"YOU HAVE FREED THE LEGENDARY PLANESWALKER {name.upper()} FROM CAPTIVITY...",
         f"{Q}Because the Five did not stop at you. {name} came to this realm long before the Night of Chains, {who}, and they chained her mind as they chained your power. What waits {where} wears her face and none of her will.{Q}", 1),
        ("Okay, yeah, I remember this now.", f"{Q}You told me. Let's finish it.{Q}", 2),
        ("I did, just like you asked.", f"{Q}Then she is not my enemy.{Q}", 1),
        ("...BUT HER MIND IS STILL IMPRISONED.",
         f"{Q}She is not - but the chain holds until it is cut, and nothing gentler than a defeat reaches her. Beat her down to where the binding shows, and I can do the rest.{Q}", 1),
        ("So... what now?", f"{Q}And after?{Q}", 1),
        (f"DEFEAT {name.upper()} AND I CAN HELP HER RECOVER, SO THAT SHE MAY AID YOU IN BATTLES YET TO COME. TAKE HEED, {{var=player_name}}, THIS WILL BE A DIFFICULT TASK.",
         f"{Q}Defeat her and I can bring her back to herself. A walker who owes you her mind may stand with you in battles yet to come. Take heed, {{var=player_name}} - she will not go down easily.{Q}", 1),
        ("Thank you for the warning.", f"{Q}Understood.{Q}", 1),
    ]
    if portals:
        pairs.append(("THE PORTALS BEHIND ME LEAD TO PARTS OF HER REALM. ONCE ACTIVATED, YOU MAY USE THEM TO TRAVEL MORE QUICKLY.",
                      f"{Q}The portals behind me lead into the parts of her domain. Once you have opened them, they will carry you there quickly.{Q}", 1))
    return pairs

WARDEN_STOCK = (f"{Q}{{var=player_name}}! I feel the Guardians' spell growing weaker! Defeat them all and you may save us yet.{Q}",
                f"{Q}{{var=player_name}}! I feel the Seals stirring - every throne you break gives a piece of you back. Break them all, Guardian.{Q}")

STOCK = {
 "main_story/castles/black_castle_f1.tmx": seal("Griselbrand", "jet"),
 "main_story/castles/green_castle_f1.tmx": seal("Ghalta", "emerald"),
 "main_story/castles/red_castle_f1.tmx": [(o, n, 2) for o, n in seal("Lathliss", "ruby")],   # two Lathliss objects
 "main_story/castles/blue_castle_f1.tmx": seal("Lorthos", "sapphire", NO_PRISONERS_FIRST, NO_PRISONERS_AGAIN),
 "main_story/castles/white_castle_f1.tmx": seal("Akroma", "pearl", NO_PRISONERS_FIRST, NO_PRISONERS_AGAIN),
 "main_story/templeofchandra.tmx": temple("Chandra", "a walker of fire", "below"),
 "main_story/temple_of_liliana/keep.tmx": temple("Liliana", "a walker who bargained with death", "within", portals=True, hello_suffix="."),
 "towns/orazca.tmx": [WARDEN_STOCK],
 "towns/player_capital.tmx": [WARDEN_STOCK],
 "towns/plains_town.tmx": [("I am a big gate. Greetings.",
                            "Welcome, traveler. Keep to the roads out there - the Five's wizards walk them now.")],
 "main_story/plains_capital.tmx": [("Hello. There is nothing for you to do here...\\nFor now.",
                                    "A steward bars the inner door. 'The Lord's court is closed to outsiders. The market is not - spend, then go.'")],
}

QUESTS = {  # world/quests.json - strict JSON, plain strings
 "world/quests.json": [
   ("Rescue the Black Captive", "Take the Jet Seal"),
   ("Free the wizard being held captive inside the Black Castle.", "Storm the Black Castle and take the jet Seal from its throne."),
   ("Rescue the Blue Captive", "Take the Sapphire Seal"),
   ("Free the wizard being held captive inside the Blue Castle.", "Storm the Blue Castle and take the sapphire Seal from its throne."),
   ("Rescue the Green Captive", "Take the Emerald Seal"),
   ("Free the wizard being held captive inside the Green Castle.", "Storm the Green Castle and take the emerald Seal from its throne."),
   ("Rescue the Red Captive", "Take the Ruby Seal"),
   ("Free the wizard being held captive inside the Red Castle.", "Storm the Red Castle and take the ruby Seal from its throne."),
   ("Rescue the White Captive", "Take the Pearl Seal"),
   ("Free the wizard being held captive inside the White Castle.", "Storm the White Castle and take the pearl Seal from its throne."),
 ],
}


def dialog_failures(path):
    """Which dialog properties of a map fail to parse (lenient JSON)."""
    bad = set()
    for obj in ET.parse(path).iter("object"):
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


def apply(path, pairs, escape):
    """Apply (old, new[, count]) pairs to one file; returns (changed, problems)."""
    text = open(path, encoding="utf-8", newline="").read()
    new, problems = text, []
    for pair in pairs:
        old, rep, want = (pair + (1,))[:3]
        o, r = (esc(old), esc(rep)) if escape else (old, rep)
        n = new.count(o)
        if n == 0 and r in new:
            continue  # already applied
        if n != want:
            problems.append(f"{os.path.relpath(path, PLANE)}: expected {want} match(es), found {n}: {old[:70]!r}")
            continue
        new = new.replace(o, r)
    return new if new != text else None, problems


chosen = [s.strip() for s in args.sets.split(",") if s.strip()]
changed, problems = [], []
if "stock" in chosen:
    for rel, pairs in sorted(STOCK.items()):
        path = os.path.join(MAPS, rel)
        before = dialog_failures(path)
        new, probs = apply(path, pairs, escape=True)
        problems += probs
        if new is not None and not probs:
            if not args.check:
                open(path, "w", encoding="utf-8", newline="").write(new)
                after = dialog_failures(path)
                if after - before:
                    problems.append(f"{rel}: NEW dialog parse failures {after - before}")
            changed.append(rel)
if "quests" in chosen:
    for rel, pairs in QUESTS.items():
        path = os.path.join(PLANE, rel)
        new, probs = apply(path, pairs, escape=False)
        problems += probs
        if new is not None and not probs:
            if not args.check:
                open(path, "w", encoding="utf-8", newline="").write(new)
                json.load(open(path, encoding="utf-8"))  # must still be strict JSON
            changed.append(rel)

print(("CHECK ONLY - " if args.check else "") + f"sets={chosen} files touched={len(changed)}")
for c in changed:
    print("  ", c)
if problems:
    print("PROBLEMS:")
    for p in problems:
        print("  ", p)
    sys.exit(1)

left = []
for dp, dn, fn in os.walk(MAPS):
    for f in fn:
        if not f.endswith(".tmx") or f in ("debug_map.tmx", "naktamun.tmx"):
            continue
        p = os.path.join(dp, f)
        for obj in ET.parse(p).iter("object"):
            props = obj.find("properties")
            if props is None:
                continue
            for pr in props.findall("property"):
                if pr.get("name") in ("dialog", "defeatDialog"):
                    raw = (pr.get("value") if pr.get("value") is not None else (pr.text or "")).lower()
                    for tok in ("from captivity", "still imprisoned", "hold any prisoners", "guardians' spell", "big gate. greetings", "nothing for you to do here"):
                        if tok in raw:
                            left.append(f"{os.path.relpath(p, MAPS)} obj {obj.get('id')}: {tok}")
qt = open(os.path.join(PLANE, "world", "quests.json"), encoding="utf-8").read()
for tok in ("Rescue the", "held captive"):
    if tok in qt:
        left.append(f"quests.json: {tok}")
print("leftovers:", left if left else "none")
