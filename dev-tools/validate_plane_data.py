#!/usr/bin/env python3
"""Plane data/asset validator (added 2026-09-05, round 123 code review, docs/review/2026-09-05-code-review.md 3.6).

Usage (from the repo root):
    python dev-tools/validate_plane_data.py "forge-gui/res/adventure/The Forsaken Realms" report.txt dev-tools/validate_plane_data_stage_fields.txt
Run it before every packaging round; it takes a few seconds and prints one section per problem category.

Parses every JSON/atlas/tmx under the plane, checks keys against the Java loader classes
(libGDX Json throws on unknown keys except quests.json), and cross-checks references:
POI -> atlas region / map file, enemies -> atlas / deck / items, shops -> atlas / items,
biomes -> enemies / POIs / atlases, quests -> items / enemy tags / POI tags, tmx -> tsx /
templates / enemies / shops / rewards / dialogs, atlas -> png.
Writes a report to the path given as argv[2] (default: stdout only).
"""
import json, os, re, sys, xml.etree.ElementTree as ET
from collections import defaultdict, Counter

PLANE = sys.argv[1] if len(sys.argv) > 1 else r"F:\FORGE\C--Users-vicwaver-MTG-Forge\forge-gui\res\adventure\The Forsaken Realms"
COMMON = os.path.normpath(os.path.join(PLANE, "..", "common"))
REPORT = sys.argv[2] if len(sys.argv) > 2 else None

issues = defaultdict(list)   # category -> list of strings
counts = Counter()

def issue(cat, msg):
    issues[cat].append(msg)
    counts[cat] += 1

# ---------------------------------------------------------------- lenient JSON
def strip_comments(text):
    out, i, n = [], 0, len(text)
    in_str = False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1]); i += 2; continue
            if c == '"':
                in_str = False
            i += 1; continue
        if c == '"':
            in_str = True; out.append(c); i += 1; continue
        if text.startswith("//", i):
            j = text.find("\n", i); i = n if j < 0 else j; continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2); i = n if j < 0 else j + 2; continue
        out.append(c); i += 1
    return "".join(out)

def quote_keys(text):
    # libGDX "minimal" JSON allows unquoted keys: {name: "x"} -> {"name": "x"} (outside strings only)
    out, i, n, in_str = [], 0, len(text), False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1]); i += 2; continue
            if c == '"':
                in_str = False
            i += 1; continue
        if c == '"':
            in_str = True; out.append(c); i += 1; continue
        m = re.match(r"([A-Za-z_][A-Za-z0-9_]*)(\s*:)", text[i:])
        if m and (not out or out[-1].strip() == "" or out[-1] in "{,"):
            out.append('"' + m.group(1) + '"' + m.group(2)); i += m.end(); continue
        out.append(c); i += 1
    return "".join(out)

def loads_lenient(raw):
    try:
        return json.loads(raw, strict=False), "strict"
    except Exception:
        pass
    cleaned = re.sub(r",(\s*[}\]])", r"\1", strip_comments(raw))
    try:
        return json.loads(cleaned, strict=False), "lenient"
    except Exception:
        pass
    quoted = quote_keys(cleaned)
    try:
        return json.loads(quoted, strict=False), "minimal"
    except Exception:
        pass
    # libGDX JsonReader also accepts a newline as a separator between members/elements
    fixed = re.sub(r'([\]\}"0-9]|true|false|null)[ \t]*\r?\n(\s*)(?=["\{\[])', r'\1,\n\2', quoted)
    return json.loads(fixed, strict=False), "newline-separated"

def load_json(path):
    raw = open(path, "r", encoding="utf-8-sig").read()
    try:
        return loads_lenient(raw)
    except Exception as e2:
        issue("json-parse", "%s: %s" % (rel(path), e2))
        return None, "fail"

def rel(p):
    try:
        return os.path.relpath(p, PLANE).replace("\\", "/")
    except ValueError:
        return p

# ---------------------------------------------------------------- loader field sets
F = {}
F["ConfigData"] = set("""screenWidth screenHeight skin font fontColor minDeckSize maxNumberOfDecks playerBaseSpeed colorIds colorIdNames
 starterEditions starterEditionNames starterDecksByEdition difficulties legalCards restrictedCards restrictedEditions restrictedBlocks
 restrictedTokens allowedEditions vintageOnlyEditions restrictedEvents allowedEvents allowedJumpstart defaultBasicLandSet enableGeneticAI
 chaosDeckFormat usePriceListPrices fogOfWarEnabled warTownAssaultEnabled ringGiftStart dayNightCycleEnabled townReconstructionEnabled
 territoryControlEnabled colorReputationEnabled resourceSpawnsEnabled dungeonRotationEnabled sideQuestTimerEnabled resourceLootVarietyEnabled
 armoryRarityGatingEnabled spawnDuplicateLimitEnabled editionProgressionEnabled armoryGuardsEnabled shopTypeRerollEnabled arenaUpgradesEnabled
 contentFilterTablesEnabled showEnemyTierInName raceEditions shopBlueprintsEnabled raceShops startingColorShopSuffixes blueprintShardCostCommon
 blueprintShardCostUncommon blueprintShardCostRare modVersion welcomePopupText welcomePopupLink engineBuildVersion modVersionDate
 weightedSpawnTiersEnabled functioningNeutralTownsEnabled disableGeneticDeckOverrides""".split())
F["TuningData"] = set("""dayLengthSeconds capitolExpansionTilesPerDay townExpansionDaysPerTile aiCastleExpansionTilesPerDay maxTerritoryRadius
 townMaxTerritoryRadius townProtectedRadiusCap speedUpMultiplier playerTerritorySpeedBonus aiTerritoryHappySpeedBonus aiTerritoryPartnerSpeedBonus
 aiTerritoryUnhappySpeedPenalty aiTerritoryWarSpeedPenalty mineWeeklyGoldPayout mineWeeklyWoodPayout mineWeeklyStonePayout mineWeeklyShardPayout
 anteRerollBaseShardCost anteRerollEscalationRate anteBuyBackMultiplier anteBuyBackMinCommon anteBuyBackMinUncommon anteBuyBackMinRare
 anteBuyBackMinMythic maxResourceSpawns aiShopPriceMultiplier playerShopPriceMultiplier sideQuestDays baseAttackingMagesPerColor
 researchThresholdFraction researchDays researchShardCost aiTownGuardDaysPerLevel aiTownAssaultCooldownDays townDefenderLifeFactorByDifficulty
 townAssaultReputationPenalty townCaptureReputationPenalty starTownsLossCount starTownExclusionRadiusTiles townMinSpacingTiles
 ringCityTownExclusionTiles ringShopPriceMultiplier ringShopRestockMultiplier ringCityPullFactor initialTownRoadSkipFraction townMaxRoadLinks
 ringTownTargetCooldownDays ringTownTargetWeightBonus aiTownGuardDefenseEnabled aiGuardTwoLandPowerFactor innTournamentRerollShardCost
 capitolTargetCooldownDays functioningNeutralTownCount maxSameEnemyNearby sameEnemyNearbyRadius sameEnemySpawnRerolls
 torchPulseMultiplier torchPulseSeconds torchPulseMaxRadiusTiles dungeonLootedDespawnFactor""".split())
F["PointOfInterestData"] = set("name type count spriteAtlas sprite map radiusFactor offsetX offsetY active questTags questFlagsToActivate displayName".split())
F["EnemyData"] = set("""name nameOverride sprite deck copyPlayerDeck ai boss flying randomizeDeck spawnRate difficulty tier speed scale life rewards
 equipment colors nextEnemy teamNumber questTags lifetime gamesPerMatch bossInsult bossIntro noAnte""".split())
F["ShopData"] = set("name description restockPrice spriteAtlas sprite unlimited rewards overlaySprite".split())
F["BiomeData"] = set("""startPointX startPointY noiseWeight distWeight name tilesetAtlas tilesetName terrain width height color collision invertHeight
 spriteNames enemies pointsOfInterest structures""".split())
F["WorldData"] = set("width height playerStartPosX playerStartPosY noiseZoomBiome tileSize miniMapTileSize roadTileset biomesSprites maxRoadDistance biomesNames".split())
F["DifficultyData"] = set("""name startingLife startingShards startingMoney startingWood startingStone enemyLifeFactor startingDifficulty spawnRank
 sellFactor goldLoss lifeLoss shardSellRatio rewardMaxFactor startItems starterDecks constructedStarterDecks pileDecks commanderDecks""".split())
F["EffectData"] = set("""name lifeModifier changeStartCards startBattleWithCard startBattleWithCardTapped startBattleWithCardInCommandZone colorView
 moveSpeed goldModifier cardRewardBonus extraManaShards visionRadiusMultiplier opponent""".split())
F["ItemData"] = set("""name equipmentSlot effect description iconName questItem excludeFromGeneralSale cost rarity usableOnWorldMap usableInPoi
 isCracked isEquipped longID commandOnUse shardsNeeded dialogOnUse""".split())
F["RewardData"] = set("""type probability count addMaxCount cardName itemName itemNames itemRarity editions colors startDate endDate rarity subTypes
 cardTypes superTypes manaCosts keyWords colorType cardText matchAllSubTypes matchAllColors cardUnion deckNeeds rotation cardPack sourceDeck minDate""".split())
F["DialogData"] = set("action condition name locname text loctext options isDisabled pinLastOption voiceFile".split())
F["ActionData"] = set("""key val removeItem addItem addLife addGold addShards addWood grantRingGift addStone deleteMapObject activateMapObject
 battleWithActorID giveBlessing setColorIdentity advanceCharacterFlag advanceQuestFlag advanceMapFlag setEffect setCharacterFlag setQuestFlag
 setMapFlag grantRewards grantRewardsChoice issueQuest addMapReputation POIReference addColorReputationColor addColorReputationAmount runCommand
 refreshShopRewardsTrigger pinShopType triggerDungeonClear""".split())
F["ConditionData"] = set("""key op val item actorID hasBlessing hasGold hasShards hasMapReputation hasLife colorIdentity checkCharacterFlag
 checkQuestFlag checkMapFlag getCharacterFlag getQuestFlag getMapFlag not""".split())
F["QuestFlag"] = set("key val".split())
F["AdventureQuestData"] = set("""isTemplate name description synopsis offerDialog prologue epilogue failureDialog declinedDialog reward
 rewardDescription stages questSourceTags giverColor requiredColorStatus questEnemyTags questPOITags storyQuest isTracked autoTrack sourceID
 id offerProbability""".split())
F["AdventureQuestStage"] = None   # filled from argv[3] if given (long list); otherwise skipped
F["ArmoryRarityData"] = set("venueBrackets".split())
F["WeekBracket"] = set("weekMin weekMax common uncommon rare mythic".split())
F["SpawnTierWeightData"] = set("weekBrackets territoryDeltas".split())
F["TierDelta"] = set("common uncommon rare mythic".split())
F["RaceEditionData"] = set("race editions".split())
F["RaceShopData"] = set("race shops".split())
F["BiomeStructureData"] = set("""name color collision N x y randomPosition structureAtlasPath sourcePath maskPath periodicInput height width ground
 symmetry periodicOutput mappingInfo""".split())
F["BiomeTerrainData"] = set("spriteName min max resolution".split())
F["BiomeSpriteData"] = set("name startArea endArea density resolution layer atlas".split())
if len(sys.argv) > 3 and os.path.exists(sys.argv[3]):
    F["AdventureQuestStage"] = set(open(sys.argv[3]).read().split())

def check_keys(obj, cls, where, strict=True):
    fields = F.get(cls)
    if fields is None or not isinstance(obj, dict):
        return
    for k in obj.keys():
        if k not in fields:
            issue("unknown-key" if strict else "unknown-key-ignored", "%s: key '%s' not a field of %s" % (where, k, cls))

def check_reward(rd, where):
    if not isinstance(rd, dict):
        issue("shape", "%s: RewardData is not an object" % where); return
    check_keys(rd, "RewardData", where)
    t = rd.get("type")
    if t is not None and t not in REWARD_TYPES:
        issue("reward-type", "%s: unknown reward type '%s'" % (where, t))
    for nm in ([rd.get("itemName")] if rd.get("itemName") else []) + list(rd.get("itemNames") or []):
        if nm not in ITEM_NAMES:
            issue("ref-item", "%s: item '%s' not in items.json" % (where, nm))
    for sub in rd.get("cardUnion") or []:
        check_reward(sub, where + ".cardUnion")
    for sub in rd.get("rotation") or []:
        check_reward(sub, where + ".rotation")
    if rd.get("sourceDeck"):
        if not resolve_deck(rd["sourceDeck"]):
            issue("ref-deck", "%s: sourceDeck '%s' not found" % (where, rd["sourceDeck"]))

def check_effect(ef, where):
    if not isinstance(ef, dict):
        return
    check_keys(ef, "EffectData", where)
    if ef.get("opponent"):
        check_effect(ef["opponent"], where + ".opponent")

def check_dialog(d, where, depth=0):
    if isinstance(d, list):
        for i, sub in enumerate(d):
            check_dialog(sub, "%s[%d]" % (where, i), depth)
        return
    if not isinstance(d, dict):
        issue("shape", "%s: DialogData is not an object" % where); return
    check_keys(d, "DialogData", where)
    for i, a in enumerate(d.get("action") or []):
        w = "%s.action[%d]" % (where, i)
        check_keys(a, "ActionData", w)
        for k in ("removeItem", "addItem"):
            if a.get(k) and a[k] not in ITEM_NAMES:
                issue("ref-item", "%s: %s '%s' not in items.json" % (w, k, a[k]))
        for k in ("setCharacterFlag", "setQuestFlag", "setMapFlag"):
            if a.get(k) is not None:
                check_keys(a[k], "QuestFlag", w + "." + k)
        for k in ("grantRewards", "grantRewardsChoice"):
            for j, r in enumerate(a.get(k) or []):
                check_reward(r, "%s.%s[%d]" % (w, k, j))
        if a.get("issueQuest") not in (None, ""):
            q = str(a["issueQuest"])
            if q not in QUEST_IDS:
                issue("ref-quest", "%s: issueQuest '%s' not a quest id" % (w, q))
        if a.get("giveBlessing"):
            check_effect(a["giveBlessing"], w + ".giveBlessing")
        if a.get("setEffect"):
            check_effect(a["setEffect"], w + ".setEffect")
        if a.get("runCommand"):
            cmd = a["runCommand"].split()[0] if a["runCommand"].strip() else ""
            if cmd not in CONSOLE_ROOTS:
                issue("ref-command", "%s: runCommand root '%s' is not a console command" % (w, cmd))
    for i, c in enumerate(d.get("condition") or []):
        w = "%s.condition[%d]" % (where, i)
        check_keys(c, "ConditionData", w)
        if c.get("item") and c["item"] not in ITEM_NAMES:
            issue("ref-item", "%s: condition item '%s' not in items.json" % (w, c["item"]))
    for i, o in enumerate(d.get("options") or []):
        check_dialog(o, "%s.options[%d]" % (where, i), depth + 1)

REWARD_TYPES = set("card randomCard item cardPackShop landSketchbookShop cardPack deckCard gold life mana shards stone wood Union".split())
CONSOLE_ROOTS = set("""teleport spawn give set leave debug clearnosell sanitize fullHeal listPOI count setColorID resetQuests resetMapQuests
 dumpEnemyDeckColors dumpEnemyDeckList dumpEnemyColorIdentity heal getShards remove hide fly sprint crack edition fog defeat reset torch""".split())

# ---------------------------------------------------------------- atlases
def parse_atlas(path):
    """returns (pages:list[str], regions:set[str])"""
    pages, regions = [], set()
    lines = open(path, "r", encoding="utf-8-sig").read().splitlines()
    i = 0
    expect_page = True
    while i < len(lines):
        ln = lines[i]
        if ln.strip() == "":
            expect_page = True; i += 1; continue
        if expect_page and not ln.startswith((" ", "\t")):
            pages.append(ln.strip()); expect_page = False; i += 1
            # skip page properties (indented or "key: value" lines until a region name)
            while i < len(lines) and lines[i].strip() != "" and (":" in lines[i]) and not lines[i].startswith((" ", "\t")) \
                    and lines[i].split(":")[0].strip() in ("size", "format", "filter", "repeat", "pma", "scale"):
                i += 1
            continue
        if not ln.startswith((" ", "\t")):
            regions.add(ln.strip())
        i += 1
    return pages, regions

ATLAS = {}   # rel path (from plane or common, as written in data) -> regions
ATLAS_FILES = {}
def resolve_file(p):
    """resolve a data path the way Config.getFile does: plane first, then common."""
    if p is None:
        return None
    p = p.replace("\\", "/")
    cands = [os.path.join(PLANE, p), os.path.join(COMMON, p)]
    if p.startswith("../"):
        cands = [os.path.normpath(os.path.join(COMMON, p)), os.path.normpath(os.path.join(PLANE, p))] + cands
    for c in cands:
        if os.path.isfile(c):
            return c
    return None

def atlas_regions(p):
    f = resolve_file(p)
    if f is None:
        return None
    if f not in ATLAS:
        try:
            pages, regions = parse_atlas(f)
        except Exception as e:
            issue("atlas-parse", "%s: %s" % (rel(f), e)); ATLAS[f] = set(); return ATLAS[f]
        for pg in pages:
            if not os.path.isfile(os.path.join(os.path.dirname(f), pg)):
                issue("atlas-page-missing", "%s: page image '%s' missing" % (rel(f), pg))
        ATLAS[f] = regions
    return ATLAS[f]

def check_region(atlas_path, region, where, optional=False):
    if not region:
        if not optional:
            issue("atlas-region", "%s: empty region name" % where)
        return
    regs = atlas_regions(atlas_path)
    if regs is None:
        issue("ref-atlas", "%s: atlas '%s' not found" % (where, atlas_path)); return
    if region not in regs:
        issue("atlas-region", "%s: region '%s' not in %s" % (where, region, atlas_path))

def resolve_deck(p):
    p = p.replace("\\", "/")
    for base in (PLANE, COMMON):
        for cand in (os.path.join(base, p), os.path.join(base, "decks", p), os.path.join(base, p + ".dck")):
            if os.path.isfile(cand):
                return cand
    # upstream res/decks? (forge-gui/res/adventure/common/decks)
    return None

# ---------------------------------------------------------------- load core lists
items, mode = load_json(os.path.join(PLANE, "world", "items.json"))
ITEM_NAMES = set()
for it in items or []:
    ITEM_NAMES.add(it.get("name"))
quests, _ = load_json(os.path.join(PLANE, "world", "quests.json"))
QUEST_IDS = set(str(q.get("id")) for q in (quests or []))
enemies, _ = load_json(os.path.join(PLANE, "world", "enemies.json"))
ENEMY_NAMES = set(e.get("name") for e in (enemies or []))
ENEMY_TAGS = set()
for e in enemies or []:
    for t in e.get("questTags") or []:
        ENEMY_TAGS.add(t)
pois, _ = load_json(os.path.join(PLANE, "world", "points_of_interest.json"))
POI_NAMES = [p.get("name") for p in (pois or [])]
POI_TAGS = set()
for p in pois or []:
    for t in p.get("questTags") or []:
        POI_TAGS.add(t)
shops, _ = load_json(os.path.join(PLANE, "world", "shops.json"))
SHOP_NAMES = set(s.get("name") for s in (shops or []))

# ---------------------------------------------------------------- items
for i, it in enumerate(items or []):
    w = "items.json[%d]%s" % (i, "(" + str(it.get("name")) + ")")
    check_keys(it, "ItemData", w)
    if it.get("effect"):
        check_effect(it["effect"], w + ".effect")
    if it.get("dialogOnUse"):
        check_dialog(it["dialogOnUse"], w + ".dialogOnUse")
    if it.get("rarity") not in (None, "Common", "Uncommon", "Rare", "Mythic"):
        issue("enum", "%s: rarity '%s'" % (w, it.get("rarity")))
    if it.get("iconName"):
        found = any(it["iconName"] in (atlas_regions(a) or set()) for a in ("sprites/items.atlas",))
        if not found:
            issue("atlas-region", "%s: iconName '%s' not in sprites/items.atlas" % (w, it["iconName"]))
    if it.get("commandOnUse"):
        root = it["commandOnUse"].split()[0]
        if root not in CONSOLE_ROOTS:
            issue("ref-command", "%s: commandOnUse root '%s' unknown" % (w, root))
dup = [n for n, c in Counter(ITEM_NAMES if False else [it.get("name") for it in items or []]).items() if c > 1]
for n in dup:
    issue("duplicate", "items.json: duplicate item name '%s'" % n)

# ---------------------------------------------------------------- enemies
TIERS = {"Common", "Uncommon", "Rare", "Mythic"}
def check_enemy(e, w, nested=False):
    check_keys(e, "EnemyData", w)
    if e.get("sprite"):
        if resolve_file(e["sprite"]) is None:
            issue("ref-atlas", "%s: sprite atlas '%s' missing" % (w, e["sprite"]))
        else:
            atlas_regions(e["sprite"])
    decks = e.get("deck") or []
    if not decks and not e.get("copyPlayerDeck") and not nested:
        issue("enemy-no-deck", "%s: no deck and not copyPlayerDeck" % w)
    for d in decks:
        if not resolve_deck(d):
            issue("ref-deck", "%s: deck '%s' not found" % (w, d))
    for it in e.get("equipment") or []:
        if it not in ITEM_NAMES:
            issue("ref-item", "%s: equipment '%s' not in items.json" % (w, it))
    for j, r in enumerate(e.get("rewards") or []):
        check_reward(r, "%s.rewards[%d]" % (w, j))
    if e.get("tier") is not None and e["tier"] not in TIERS:
        issue("enum", "%s: tier '%s'" % (w, e["tier"]))
    col = e.get("colors")
    if col is not None and any(ch not in "WUBRGC" for ch in col):
        issue("enum", "%s: colors '%s'" % (w, col))
    if e.get("nextEnemy"):
        check_enemy(e["nextEnemy"], w + ".nextEnemy", nested=True)
for i, e in enumerate(enemies or []):
    check_enemy(e, "enemies.json[%d](%s)" % (i, e.get("name")))
for n, c in Counter(e.get("name") for e in enemies or []).items():
    if c > 1:
        issue("duplicate", "enemies.json: duplicate enemy name '%s' x%d" % (n, c))

# ---------------------------------------------------------------- POIs
POI_TYPES = {"town", "capital", "dungeon", "cave", "castle", "spawn", "arena", "shop", "sanctuary", "inn", "waypoint", "dungeon_castle"}
for i, p in enumerate(pois or []):
    w = "points_of_interest.json[%d](%s)" % (i, p.get("name"))
    check_keys(p, "PointOfInterestData", w)
    if p.get("spriteAtlas"):
        check_region(p["spriteAtlas"], p.get("sprite"), w, optional=False)
    if p.get("map"):
        if resolve_file(p["map"]) is None:
            issue("ref-map", "%s: map '%s' not found" % (w, p["map"]))
    if p.get("type") and p["type"] not in POI_TYPES:
        issue("enum", "%s: type '%s' (not in the known set; informational)" % (w, p["type"]))
    for fl in p.get("questFlagsToActivate") or []:
        check_keys(fl, "QuestFlag", w + ".questFlagsToActivate")
for n, c in Counter(POI_NAMES).items():
    if c > 1:
        issue("duplicate", "points_of_interest.json: duplicate POI name '%s' x%d" % (n, c))

# ---------------------------------------------------------------- shops
for i, s in enumerate(shops or []):
    w = "shops.json[%d](%s)" % (i, s.get("name"))
    check_keys(s, "ShopData", w)
    if s.get("spriteAtlas"):
        check_region(s["spriteAtlas"], s.get("sprite"), w)
        if s.get("overlaySprite"):
            check_region(s["spriteAtlas"], s["overlaySprite"], w + ".overlaySprite")
    for j, r in enumerate(s.get("rewards") or []):
        check_reward(r, "%s.rewards[%d]" % (w, j))
for n, c in Counter(s.get("name") for s in shops or []).items():
    if c > 1:
        issue("duplicate", "shops.json: duplicate shop name '%s' x%d" % (n, c))

# ---------------------------------------------------------------- quests
COLORS = {"white", "blue", "black", "red", "green"}
STATUSES = {"PARTNER", "HAPPY", "NEUTRAL", "UNHAPPY", "WAR"}
for i, q in enumerate(quests or []):
    w = "quests.json[%d](id=%s %s)" % (i, q.get("id"), q.get("name"))
    check_keys(q, "AdventureQuestData", w, strict=False)
    for k in ("offerDialog", "prologue", "epilogue", "failureDialog", "declinedDialog"):
        if q.get(k):
            check_dialog(q[k], w + "." + k)
    if q.get("reward"):
        check_reward(q["reward"], w + ".reward")
    if q.get("giverColor") and q["giverColor"] not in COLORS:
        issue("enum", "%s: giverColor '%s'" % (w, q["giverColor"]))
    if q.get("requiredColorStatus") and q["requiredColorStatus"].upper() not in STATUSES:
        issue("enum", "%s: requiredColorStatus '%s'" % (w, q["requiredColorStatus"]))
    ids = set()
    for j, st in enumerate(q.get("stages") or []):
        sw = "%s.stages[%d]" % (w, j)
        check_keys(st, "AdventureQuestStage", sw, strict=False)
        if st.get("id") in ids:
            issue("duplicate", "%s: duplicate stage id %s" % (sw, st.get("id")))
        ids.add(st.get("id"))
        for t in st.get("enemyTags") or []:
            if t not in ENEMY_TAGS:
                issue("ref-enemy-tag", "%s: enemyTags '%s' matches no enemy questTag" % (sw, t))
        for t in st.get("POITags") or []:
            if t not in POI_TAGS and t not in POI_NAMES:
                issue("ref-poi-tag", "%s: POITags '%s' matches no POI questTag/name" % (sw, t))
        for nm in (st.get("itemNames") or []) + (st.get("equipNames") or []):
            if nm not in ITEM_NAMES:
                issue("ref-item", "%s: item '%s' not in items.json" % (sw, nm))
        for pre in st.get("prerequisiteIDs") or []:
            if pre not in [s2.get("id") for s2 in q.get("stages") or []]:
                issue("ref-stage", "%s: prerequisite stage %s not in quest" % (sw, pre))
        for k in ("prologue", "epilogue", "failureDialog"):
            if st.get(k):
                check_dialog(st[k], sw + "." + k)
for n, c in Counter(str(q.get("id")) for q in quests or []).items():
    if c > 1:
        issue("duplicate", "quests.json: duplicate quest id %s x%d" % (n, c))

# ---------------------------------------------------------------- world + biomes
world, _ = load_json(os.path.join(PLANE, "world", "world.json"))
if world:
    check_keys(world, "WorldData", "world.json")
    if world.get("roadTileset"):
        check_keys(world["roadTileset"], "BiomeData", "world.json.roadTileset")
    if world.get("biomesSprites") and resolve_file(world["biomesSprites"]) is None:
        issue("ref-file", "world.json: biomesSprites '%s' missing" % world["biomesSprites"])
    for bn in world.get("biomesNames") or []:
        if resolve_file(bn) is None:
            issue("ref-file", "world.json: biome file '%s' missing" % bn)
bdir = os.path.join(PLANE, "world", "biomes")
for fn in sorted(os.listdir(bdir)) if os.path.isdir(bdir) else []:
    if not fn.endswith(".json"):
        continue
    b, _ = load_json(os.path.join(bdir, fn))
    if not b:
        continue
    w = "biomes/" + fn
    check_keys(b, "BiomeData", w)
    if b.get("tilesetAtlas"):
        regs = atlas_regions(b["tilesetAtlas"])
        if regs is None:
            issue("ref-atlas", "%s: tilesetAtlas '%s' missing" % (w, b["tilesetAtlas"]))
    for j, t in enumerate(b.get("terrain") or []):
        check_keys(t, "BiomeTerrainData", "%s.terrain[%d]" % (w, j))
    for en in b.get("enemies") or []:
        if en not in ENEMY_NAMES:
            issue("ref-enemy", "%s: enemy '%s' not in enemies.json" % (w, en))
    for pn in b.get("pointsOfInterest") or []:
        if pn not in POI_NAMES:
            issue("ref-poi", "%s: POI '%s' not in points_of_interest.json" % (w, pn))
    for j, s in enumerate(b.get("structures") or []):
        sw = "%s.structures[%d]" % (w, j)
        check_keys(s, "BiomeStructureData", sw)
        for k in ("structureAtlasPath", "sourcePath", "maskPath"):
            if s.get(k) and resolve_file(s[k]) is None:
                issue("ref-file", "%s: %s '%s' missing" % (sw, k, s[k]))

# map sprites
ms, _ = load_json(os.path.join(PLANE, "world", "sprites", "map_sprites.json"))
if isinstance(ms, list):
    for j, s in enumerate(ms):
        check_keys(s, "BiomeSpriteData", "map_sprites.json[%d]" % j)
        if s.get("atlas") and resolve_file(s["atlas"]) is None:
            issue("ref-atlas", "map_sprites.json[%d]: atlas '%s' missing" % (j, s["atlas"]))

# ---------------------------------------------------------------- config + tables
cfg, _ = load_json(os.path.join(PLANE, "config.json"))
if cfg:
    check_keys(cfg, "ConfigData", "config.json")
    for j, d in enumerate(cfg.get("difficulties") or []):
        dw = "config.json.difficulties[%d](%s)" % (j, d.get("name"))
        check_keys(d, "DifficultyData", dw)
        for it in d.get("startItems") or []:
            if it not in ITEM_NAMES:
                issue("ref-item", "%s: startItem '%s' not in items.json" % (dw, it))
        for k in ("starterDecks", "constructedStarterDecks", "pileDecks", "commanderDecks"):
            for color, path in (d.get(k) or {}).items():
                if not resolve_deck(path):
                    issue("ref-deck", "%s.%s[%s]: '%s' not found" % (dw, k, color, path))
    for j, r in enumerate(cfg.get("raceEditions") or []):
        check_keys(r, "RaceEditionData", "config.json.raceEditions[%d]" % j)
    for j, r in enumerate(cfg.get("raceShops") or []):
        check_keys(r, "RaceShopData", "config.json.raceShops[%d]" % j)
        for sname in r.get("shops") or []:
            if sname not in SHOP_NAMES:
                issue("ref-shop", "config.json.raceShops[%d]: shop '%s' not in shops.json" % (j, sname))
    for ed, decks in (cfg.get("starterDecksByEdition") or {}).items():
        for color, path in (decks or {}).items():
            if not resolve_deck(path):
                issue("ref-deck", "config.json.starterDecksByEdition[%s][%s]: '%s' not found" % (ed, color, path))
    if cfg.get("legalCards"):
        check_reward(cfg["legalCards"], "config.json.legalCards")
    for k in ("restrictedCards", "restrictedEditions", "allowedEditions", "colorIds", "starterEditions"):
        v = cfg.get(k)
        if isinstance(v, list):
            dups = [x for x, c in Counter(v).items() if c > 1]
            if dups:
                issue("duplicate", "config.json.%s: duplicates %s" % (k, dups[:10]))
tun, _ = load_json(os.path.join(PLANE, "config tables", "settings.json"))
if tun:
    check_keys(tun, "TuningData", "config tables/settings.json")
arm, _ = load_json(os.path.join(PLANE, "config tables", "armory_rarity.json"))
if arm:
    check_keys(arm, "ArmoryRarityData", "armory_rarity.json")
    for venue, brs in (arm.get("venueBrackets") or {}).items():
        for j, br in enumerate(brs or []):
            check_keys(br, "WeekBracket", "armory_rarity.json.%s[%d]" % (venue, j))
stw, _ = load_json(os.path.join(PLANE, "config tables", "spawn_tier_weighting.json"))
if stw:
    check_keys(stw, "SpawnTierWeightData", "spawn_tier_weighting.json")
    for j, br in enumerate(stw.get("weekBrackets") or []):
        check_keys(br, "WeekBracket", "spawn_tier_weighting.json.weekBrackets[%d]" % j)
    for terr, td in (stw.get("territoryDeltas") or {}).items():
        check_keys(td, "TierDelta", "spawn_tier_weighting.json.territoryDeltas[%s]" % terr)
rc, _ = load_json(os.path.join(PLANE, "config tables", "restricted_cards.json"))
if rc:
    for k in rc.keys():
        if k != "restrictedCards":
            issue("unknown-key", "restricted_cards.json: key '%s'" % k)
for fn in sorted(os.listdir(os.path.join(PLANE, "ui"))):
    if fn.endswith(".json"):
        load_json(os.path.join(PLANE, "ui", fn))

# ---------------------------------------------------------------- atlases: every atlas page exists
for root, dirs, files in os.walk(PLANE):
    for fn in files:
        if fn.endswith(".atlas"):
            atlas_regions(os.path.relpath(os.path.join(root, fn), PLANE))
counts["atlases-parsed"] = len(ATLAS)

# ---------------------------------------------------------------- tmx maps
def tmx_props(elem):
    props = {}
    pe = elem.find("properties")
    if pe is not None:
        for p in pe.findall("property"):
            props[p.get("name")] = p.get("value") if p.get("value") is not None else (p.text or "")
    return props

tmx_count = 0
TMX_OBJ_TYPES = Counter()
# roots: every POI map, plus the Ring City per-color layouts TileMapScene.resolveMapPath() synthesizes
roots = []
for p in pois or []:
    if p.get("map"):
        f = resolve_file(p["map"])
        if f: roots.append(f)
for color in ("white", "blue", "black", "red", "green"):
    for pre in ("", "player_"):
        f = resolve_file("../The Forsaken Realms/maps/map/towns/ring_city_%s%s.tmx" % (pre, color))
        if f: roots.append(f)
reachable, queue = set(), list(dict.fromkeys(roots))
parsed = {}
while queue:
    path = queue.pop()
    key = os.path.normcase(os.path.normpath(path))
    if key in reachable:
        continue
    reachable.add(key)
    try:
        tree = ET.parse(path)
    except Exception as e:
        issue("tmx-parse", "%s: %s" % (rel(path), e)); continue
    parsed[key] = (path, tree)
    for og in tree.getroot().findall("objectgroup"):
        for o in og.findall("object"):
            tp = tmx_props(o).get("teleport")
            if tp and tp.strip():
                f = resolve_file(tp.strip())
                if f:
                    queue.append(f)
                else:
                    issue("ref-map", "%s#%s: teleport target '%s' missing" % (rel(path), o.get("id"), tp))
counts["tmx-reachable"] = len(reachable)
# dead map files inside the plane (never reachable from a POI / teleport)
plane_maps = set()
for root, dirs, files in os.walk(os.path.join(PLANE, "maps")):
    for fn in files:
        if fn.endswith(".tmx"):
            plane_maps.add(os.path.normcase(os.path.normpath(os.path.join(root, fn))))
dead = sorted(m for m in plane_maps if m not in reachable)
counts["tmx-unreachable-in-plane"] = len(dead)
for m in dead:
    issue("tmx-unreachable", rel(m))
for key, (path, tree) in parsed.items():
            tmx_count += 1
            r = tree.getroot()
            here = os.path.dirname(path)
            for ts in r.findall("tileset"):
                src = ts.get("source")
                if src and not os.path.isfile(os.path.normpath(os.path.join(here, src))):
                    issue("ref-tsx", "%s: tileset '%s' missing" % (rel(path), src))
                img = ts.find("image")
                if img is not None and img.get("source") and not os.path.isfile(os.path.normpath(os.path.join(here, img.get("source")))):
                    issue("ref-tsx-image", "%s: tileset image '%s' missing" % (rel(path), img.get("source")))
            for og in r.findall("objectgroup"):
                for o in og.findall("object"):
                    tpl = o.get("template")
                    if tpl and not os.path.isfile(os.path.normpath(os.path.join(here, tpl))):
                        issue("ref-template", "%s: template '%s' missing" % (rel(path), tpl))
                    props = tmx_props(o)
                    otype = o.get("type") or o.get("class") or props.get("type")
                    if otype:
                        TMX_OBJ_TYPES[otype] += 1
                    en = props.get("enemy")
                    if en and en not in ENEMY_NAMES:
                        issue("ref-enemy", "%s#%s: enemy '%s' not in enemies.json" % (rel(path), o.get("id"), en))
                    for k in ("commonShopList", "uncommonShopList", "rareShopList", "mythicShopList", "shopList", "rotation"):
                        v = props.get(k)
                        if v:
                            for sname in [x.strip() for x in v.split(",") if x.strip()]:
                                if sname not in SHOP_NAMES:
                                    issue("ref-shop", "%s#%s: %s '%s' not in shops.json" % (rel(path), o.get("id"), k, sname))
                    for k in ("reward",):
                        v = props.get(k)
                        if v and v.strip():
                            try:
                                rj, _m = loads_lenient(v)
                            except Exception as e:
                                issue("tmx-json", "%s#%s: %s does not parse: %s" % (rel(path), o.get("id"), k, e)); continue
                            for j, rd in enumerate(rj if isinstance(rj, list) else [rj]):
                                check_reward(rd, "%s#%s.reward[%d]" % (rel(path), o.get("id"), j))
                    for k in ("dialog", "defeatDialog"):
                        v = props.get(k)
                        if v and v.strip():
                            try:
                                dj, _m = loads_lenient(v)
                            except Exception as e:
                                issue("tmx-json", "%s#%s: %s does not parse: %s" % (rel(path), o.get("id"), k, e)); continue
                            check_dialog(dj, "%s#%s.%s" % (rel(path), o.get("id"), k))
                    v = props.get("effect")
                    if v and v.strip():
                        try:
                            check_effect(loads_lenient(v)[0], "%s#%s.effect" % (rel(path), o.get("id")))
                        except Exception as e:
                            issue("tmx-json", "%s#%s: effect does not parse: %s" % (rel(path), o.get("id"), e))
                    ar = props.get("arena")
                    if ar and ar.strip():
                        try:
                            aj, _m = loads_lenient(ar)
                            check_keys(aj, "ArenaData", "%s#%s.arena" % (rel(path), o.get("id")))
                            for en2 in aj.get("enemyPool") or []:
                                if en2 not in ENEMY_NAMES:
                                    issue("ref-enemy", "%s#%s: arena enemy '%s' unknown" % (rel(path), o.get("id"), en2))
                        except Exception as e:
                            issue("tmx-json", "%s#%s: arena does not parse: %s" % (rel(path), o.get("id"), e))
counts["tmx-parsed"] = tmx_count
F["ArenaData"] = set("enemyPool rounds entryFee rewards".split())

# ---------------------------------------------------------------- decks referenced by enemies: quick sanity
deck_refs = set()
for e in enemies or []:
    for d in e.get("deck") or []:
        deck_refs.add(d)
counts["enemy-deck-refs"] = len(deck_refs)
empty_decks = 0
for d in sorted(deck_refs):
    f = resolve_deck(d)
    if not f:
        continue
    if f.lower().endswith(".json"):
        try:
            loads_lenient(open(f, "r", encoding="utf-8-sig").read())
        except Exception as e:
            issue("deck-json-parse", "%s: %s" % (d, e))
        continue
    txt = open(f, "r", encoding="utf-8", errors="replace").read()
    if "[Main]" in txt or "[main]" in txt:
        body = txt.split("[Main]" if "[Main]" in txt else "[main]", 1)[1]
        body = body.split("[", 1)[0]
        if not any(ln.strip() and not ln.strip().startswith(("#", ";")) for ln in body.splitlines()):
            empty_decks += 1
            issue("deck-empty-main", "%s: [Main] section empty" % d)
    else:
        issue("deck-no-main", "%s: no [Main] section" % d)

# ---------------------------------------------------------------- report
lines = []
lines.append("Data validation for: %s" % PLANE)
lines.append("counts: " + ", ".join("%s=%d" % kv for kv in sorted(counts.items())))
lines.append("items=%d enemies=%d pois=%d shops=%d quests=%d" % (len(items or []), len(enemies or []), len(pois or []), len(shops or []), len(quests or [])))
lines.append("tmx object types: " + ", ".join("%s=%d" % kv for kv in TMX_OBJ_TYPES.most_common()))
for cat in sorted(issues):
    lst = issues[cat]
    lines.append("")
    lines.append("== %s (%d) ==" % (cat, len(lst)))
    for m in lst[:60]:
        lines.append("  " + m)
    if len(lst) > 60:
        lines.append("  ... %d more" % (len(lst) - 60))
out = "\n".join(lines)
print(out)
if REPORT:
    with open(REPORT, "w", encoding="utf-8") as fh:
        fh.write(out + "\n")
