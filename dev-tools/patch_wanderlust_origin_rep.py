#!/usr/bin/env python3
"""Round 186: Wanderlust pays reputation at the SENDING town as well as the receiving one.

User: "for quests that send you to deliver a message across the map, currently you get the
reputation boost from the arriving town. Let's make it that you get rep boost from both sending
and receiving destinations."

The quest
---------
"Wanderlust" (id 2, isTemplate) is the plane's delivery quest - description "Make a delivery to
a distant location", one Travel stage to a Town. A mage in the origin town hands you a letter;
the epilogue plays in the DESTINATION town and grants reputation there with an empty
POIReference, which MapDialog reads as "the POI the player is standing in".

Its epilogue has three terminal branches, and all three deliver the letter:

    +1  "I guess I should have asked for the reward first."  (she slams the window)
    -1  (Break down the door)                                (a scene in the street)
    +2  You take the coins and place the letter in the bucket.

The change
----------
Each of those three actions gains a SIBLING action granting reputation at the origin, addressed
with the new $(poi_source) token (AdventureQuestData.SOURCE_POI_TOKEN), which resolves to the
quest's sourceID - the POI whose NPC handed the quest out. MapDialog executes every entry in an
option's action array, so both grants land.

Flat +2 at the origin on all three branches, deliberately: the sender paid for a letter to
arrive, and in all three branches it arrives. How gracefully the recipient was handled is a
matter for the recipient's own town, which is what the destination grant already reflects - and
in the +1 branch the recipient slams the window regardless of what the player does, so there is
nothing there to punish the sender for.

Writes quests.json.bak once. The file round-trips byte-for-byte through json with tab indent, so
the diff is confined to the lines actually added.
"""

import json
import os
import shutil
import sys

QUESTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "forge-gui", "res",
                      "adventure", "The Forsaken Realms", "world", "quests.json")

QUEST_NAME = "Wanderlust"
SOURCE_TOKEN = "$(poi_source)"
ORIGIN_REPUTATION = 2


def origin_action():
    """A sibling action shaped exactly like the ones already in this file."""
    return {
        "removeItem": "",
        "setColorIdentity": "",
        "advanceQuestFlag": "",
        "advanceMapFlag": "",
        "setQuestFlag": {"key": ""},
        "setMapFlag": {"key": ""},
        "issueQuest": "",
        "addMapReputation": ORIGIN_REPUTATION,
        "POIReference": SOURCE_TOKEN,
    }


# The file's own convention is to spell a reputation change out in the option's name or text,
# e.g. "(+1 Local Reputation. Complete Quest)". A silent second grant would break that, and the
# player would never learn the sending town pays too. The origin is described rather than named:
# $(poi_source) resolves to a raw POI id and only inside a POIReference, and there is no cheap
# id -> PointOfInterest lookup to get a display name from here.
ANNOTATIONS = [
    ("name", "(+1 Local Reputation. Complete Quest)",
             "(+1 Local Reputation, +2 Sender's Town. Complete Quest)"),
    ("text", "(-1 Local Reputation)", "(-1 Local Reputation, +2 Sender's Town)"),
    ("text", "(+2 Local Reputation)", "(+2 Local Reputation, +2 Sender's Town)"),
]


def annotate(node, report):
    """Spell the origin grant out wherever the local one is already spelled out."""
    if not isinstance(node, dict):
        return
    for field, old, new in ANNOTATIONS:
        value = node.get(field)
        if isinstance(value, str) and old in value and new not in value:
            node[field] = value.replace(old, new)
            report.append(("text", new, None))
    for option in (node.get("options") or []):
        annotate(option, report)


def patch_dialog(node, report):
    """Append an origin grant beside every local reputation grant in this dialog tree."""
    if not isinstance(node, dict):
        return
    actions = node.get("action")
    if isinstance(actions, list):
        # Already patched? Then leave it alone - this script must be safe to re-run.
        if any(isinstance(a, dict) and a.get("POIReference") == SOURCE_TOKEN for a in actions):
            report.append(("skip", node.get("name", "")[:60], None))
        else:
            local = [a for a in actions
                     if isinstance(a, dict) and a.get("addMapReputation")
                     and not a.get("POIReference")]
            if local:
                amounts = [a["addMapReputation"] for a in local]
                actions.append(origin_action())
                report.append(("add", node.get("name", "")[:60], amounts))
    for option in (node.get("options") or []):
        patch_dialog(option, report)


def main():
    path = os.path.normpath(QUESTS)
    raw = open(path, encoding="utf-8").read()
    quests = json.loads(raw)

    # Guard the formatting assumption before trusting a rewrite of a 420 KB data file.
    if json.dumps(quests, indent="\t", ensure_ascii=False) + "\n" != raw:
        print("ABORT: quests.json does not round-trip byte-for-byte with tab indent; "
              "rewriting it would reformat the whole file.")
        return 1

    target = [q for q in quests if q.get("name") == QUEST_NAME]
    if len(target) != 1:
        print("ABORT: expected exactly one quest named %r, found %d" % (QUEST_NAME, len(target)))
        return 1
    quest = target[0]
    if not quest.get("epilogue"):
        print("ABORT: %s has no epilogue to patch" % QUEST_NAME)
        return 1

    report = []
    patch_dialog(quest["epilogue"], report)
    annotate(quest["epilogue"], report)

    added = [r for r in report if r[0] in ("add", "text")]
    skipped = [r for r in report if r[0] == "skip"]
    for kind, name, amounts in report:
        if kind == "add":
            print("  + origin %+d beside local %s  <- %s"
                  % (ORIGIN_REPUTATION, amounts, name or "(unnamed option)"))
        elif kind == "text":
            print("  ~ label now reads %s" % name)
        else:
            print("  = already patched: %s" % (name or "(unnamed option)"))
    if not added:
        print("nothing to do (%d already patched)" % len(skipped))
        return 0

    backup = path + ".bak"
    if not os.path.exists(backup):
        shutil.copy2(path, backup)
        print("backup -> %s" % os.path.basename(backup))
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(json.dumps(quests, indent="\t", ensure_ascii=False) + "\n")
    print("patched %d branch(es) of %s" % (len(added), QUEST_NAME))
    return 0


if __name__ == "__main__":
    sys.exit(main())
