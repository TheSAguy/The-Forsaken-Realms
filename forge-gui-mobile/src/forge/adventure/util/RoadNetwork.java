package forge.adventure.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.apache.commons.lang3.tuple.Pair;

import com.badlogic.gdx.math.Vector2;

import forge.adventure.data.PointOfInterestData;
import forge.adventure.data.TuningData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.World;

/**
 * Round 351 - one set of rules for every road on the world map. The user, 2026-09-26: "It looks like there are some
 * double roads in the center." - "I also got a double road after restoring a ruin town." - "After building cap, still
 * seeing a lot of old roads under new road." - "The road pathing logic seems wrong here. Red is longer than the
 * alternative green. The green circle road should have been upgraded and the red should not be there."
 * <p>
 * Every road is the road pass's staircase from one town's anchor (PointOfInterest.getTilePosition) to another's. What
 * went wrong, and the rule for each:
 * <ul>
 * <li>The walk from A to B is a different staircase from the walk from B to A. The star's spokes named pairs the
 *     nearest-neighbor pass already had, and captures linked pairs world-gen had drawn from the other end - two
 *     staircases side by side. Every walk now starts at the same end ({@link #canonicalFirst}), and world-gen draws a
 *     pair once ({@link #uniqueCanonicalPairs}).</li>
 * <li>The star - Orazca (the Capitol once raised), the campfire beside it and the five Ring Cities - is a wheel: the
 *     hub, its spokes, the rim and the campfire's road to the hub (rounds 347-348). The nearest-neighbor pass also
 *     linked the campfire and the Ring Cities to each other, and captures routed across the middle, so the pentagram
 *     round 348 had taken out came back. No road joins two star towns off the wheel, and nothing reaches the hub or
 *     the campfire from outside ({@link Star#allowsHop}).</li>
 * <li>The Capitol's roads were routed per town, from the Capitol, over every town with cost = distance squared and
 *     blind to the roads already there: they ran beside old roads instead of on them, and each town got its own route
 *     even when a road to the last one was a step away. Now the network grows from the Capitol one town at a time,
 *     the cheapest to reach from anything already on it first, and a hop along an existing road costs
 *     {@link #EXISTING_ROAD_DISCOUNT} of a new one - the network upgrades the roads that are there. Captures' old roads
 *     route with the same discount.</li>
 * <li>An upgraded pair's other staircase, and old road squeezed against a player road, is lifted; an old road between
 *     two of the player's own places is upgraded too.</li>
 * </ul>
 * A save from before this round is normalized once on load ({@link #migrateOnLoad}): the star's clutter and every
 * doubled pair go, and a standing Capitol's network is laid again - its player road turned back to old road first so
 * the new network can upgrade it, and a hop of the old network the new one no longer uses is lifted when the new
 * network already joins its ends about as directly (the user's "the red should not be there"); otherwise it stays as
 * an old road. Grep forge.log for [TFR-Roads].
 */
public final class RoadNetwork {
    /** Bumped when the rules change and every save should be normalized again (World.roadsNormalized). */
    public static final int VERSION = 4; // 2: roads past a town chained on both sides lifted (round 351b); 3: corner joints joined (round 365); 4: one road into the star per town (round 392)
    /** A hop along a road that is already there costs this share of a new one (cost = distance squared). */
    public static final double EXISTING_ROAD_DISCOUNT = 0.35;
    /** A pair of towns is joined by road when this share of either staircase between them is road. */
    static final float EDGE_COVERAGE = 0.95f;
    /** Pairs farther apart than this (tiles) are never looked at as a road - world-gen links reach about 62. */
    static final int MAX_EDGE_TILES = 90;
    /** An abandoned hop of an older player network goes when the new network joins its ends within this detour. */
    static final double ABANDONED_DETOUR = 1.6;

    static final int HUB = 1, CAMP = 2, RING = 3;

    private RoadNetwork() {
    }

    // ------------------------------------------------------------------ places

    public static boolean isTownOrCapital(PointOfInterest poi) {
        String type = poi == null || poi.getData() == null ? null : poi.getData().type;
        return "town".equals(type) || "capital".equals(type);
    }

    /**
     * The towns and capitals roads run between - hidden ones left out (the campfire beside Orazca goes inactive after
     * the start; a road may not end at a place the player cannot see, nor route through one).
     */
    public static List<PointOfInterest> towns(World world) {
        List<PointOfInterest> out = new ArrayList<>();
        for (PointOfInterest poi : world.getAllPointOfInterest())
            if (isTownOrCapital(poi) && poi.getActive())
                out.add(poi);
        return out;
    }

    /** A town's part in the star by its data name - HUB, CAMP, RING - or 0. */
    static int starRole(PointOfInterest poi) {
        PointOfInterestData d = poi == null ? null : poi.getData();
        if (d == null || d.name == null)
            return 0;
        if (TownRestoration.ORAZCA_POI_NAME.equals(d.name) || TownRestoration.CAPITOL_POI_NAME.equals(d.name))
            return HUB;
        if ("Spawn".equals(d.name))
            return CAMP;
        return d.name.contains(" Town Center") ? RING : 0;
    }

    /**
     * World-gen's nearest-neighbor and rescue passes: may these two towns be linked? Not two star towns - the star's own
     * pass lays the wheel - and nothing from outside into the hub or the campfire.
     */
    public static boolean worldGenLinkAllowed(PointOfInterest a, PointOfInterest b) {
        int ra = starRole(a), rb = starRole(b);
        if (ra != 0 && rb != 0)
            return false;
        int r = ra != 0 ? ra : rb;
        return r == 0 || r == RING;
    }

    /**
     * Round 351b (the user: "There were two roads, one leading from Shiv (Red Ring City) to two nearby towns. I think it
     * should have been 1 to one town and from that town to the next"): is a road from a to b direct - no town it may also
     * link to lies between them, nearer to both than they are to each other (the lune of a relative-neighborhood graph)?
     * A road past such a town runs beside the chain through it. World-gen distances (positions, pixels).
     */
    public static boolean worldGenDirect(List<PointOfInterest> towns, PointOfInterest a, PointOfInterest b) {
        float dab = a.getPosition().dst(b.getPosition());
        for (PointOfInterest w : towns) {
            if (w == a || w == b || !worldGenLinkAllowed(a, w) || !worldGenLinkAllowed(w, b))
                continue;
            if (Math.max(a.getPosition().dst(w.getPosition()), w.getPosition().dst(b.getPosition())) < dab)
                return false;
        }
        return true;
    }

    /** The star as it stands: the hub (or the campfire when a plane has no Orazca), the campfire, the rim in order. */
    public static final class Star {
        final PointOfInterest hub, camp;
        final List<PointOfInterest> rim = new ArrayList<>();

        public Star(Collection<PointOfInterest> towns) {
            PointOfInterest h = null, c = null;
            for (PointOfInterest t : towns) {
                int role = starRole(t);
                if (role == HUB && h == null)
                    h = t;
                else if (role == CAMP && c == null)
                    c = t;
                else if (role == RING)
                    rim.add(t);
            }
            hub = h != null ? h : c;
            camp = c;
            if (hub != null) {
                final float hx = hub.getPosition().x, hy = hub.getPosition().y;
                rim.sort(java.util.Comparator.comparingDouble(t -> Math.atan2(t.getPosition().y - hy, t.getPosition().x - hx)));
            }
        }

        int role(PointOfInterest p) {
            if (p == null)
                return 0;
            if (p == hub)
                return HUB;
            if (p == camp)
                return CAMP;
            return starRole(p) == RING ? RING : 0;
        }

        /**
         * May a road run straight between these two towns? Off the star, always; into it only to a Ring City - and
         * since round 392 only to the outside town's NEAREST Ring City (one road into the star per town); between two
         * star towns only along the wheel - the hub to a Ring City or the campfire, a Ring City to its rim neighbors.
         */
        public boolean allowsHop(PointOfInterest a, PointOfInterest b) {
            int ra = role(a), rb = role(b);
            if (ra == 0 && rb == 0)
                return true;
            if (ra == 0 || rb == 0) {
                PointOfInterest outside = ra == 0 ? a : b, inside = ra == 0 ? b : a;
                return (ra == 0 ? rb : ra) == RING && nearestRing(outside) == inside;
            }
            if (ra == HUB || rb == HUB)
                return (ra == HUB ? rb : ra) != HUB;
            if (ra == RING && rb == RING)
                return rimNeighbors(a, b);
            return false;
        }

        private final Map<PointOfInterest, PointOfInterest> nearestRing = new IdentityHashMap<>();

        /** Round 392: the Ring City nearest this town (positions) - the one road it may have into the star. */
        PointOfInterest nearestRing(PointOfInterest t) {
            return nearestRing.computeIfAbsent(t, k -> RoadNetwork.nearestRing(k, rim));
        }

        boolean rimNeighbors(PointOfInterest a, PointOfInterest b) {
            int i = rim.indexOf(a), j = rim.indexOf(b), n = rim.size();
            if (i < 0 || j < 0)
                return false;
            return n < 3 || (i + 1) % n == j || (j + 1) % n == i;
        }
    }

    // ------------------------------------------------------------------ one road into the star (round 392)

    /** The Ring City of these nearest t, by position; null when there is none. */
    static PointOfInterest nearestRing(PointOfInterest t, Collection<PointOfInterest> rings) {
        PointOfInterest best = null;
        float bd = Float.MAX_VALUE;
        for (PointOfInterest r : rings) {
            float d = t.getPosition().dst(r.getPosition());
            if (d < bd) {
                bd = d;
                best = r;
            }
        }
        return best;
    }

    /** The outside end of a road between a town outside the star and a Ring City; null for any other road. */
    static PointOfInterest outsideEnd(PointOfInterest a, PointOfInterest b) {
        int ra = starRole(a), rb = starRole(b);
        if (ra == 0 && rb == RING)
            return a;
        if (rb == 0 && ra == RING)
            return b;
        return null;
    }

    /**
     * Round 392 (the user, of a new world's centre: "there seems to be a lot of roads here. Seems only the one spot";
     * then "go ahead with the one-road-per-town fix"): a town outside the star keeps ONE road into it, to the nearest
     * Ring City it has a road to. worldGenDirect() never counts a Ring City as the town between two others when the far
     * end is a Ring City too (two star towns may not link), so a town between two Ring Cities was linked to both - two
     * roads side by side into a wheel that already joins them. World-gen's pairs, filtered in place; returns how many
     * went.
     */
    public static int oneRoadIntoStar(List<Pair<PointOfInterest, PointOfInterest>> pairs) {
        Map<PointOfInterest, Pair<PointOfInterest, PointOfInterest>> keep = new IdentityHashMap<>();
        for (Pair<PointOfInterest, PointOfInterest> p : pairs) {
            PointOfInterest out = outsideEnd(p.getLeft(), p.getRight());
            if (out == null)
                continue;
            Pair<PointOfInterest, PointOfInterest> cur = keep.get(out);
            if (cur == null || ringDistance(out, p) < ringDistance(out, cur))
                keep.put(out, p);
        }
        int before = pairs.size();
        pairs.removeIf(p -> {
            PointOfInterest out = outsideEnd(p.getLeft(), p.getRight());
            return out != null && keep.get(out) != p;
        });
        return before - pairs.size();
    }

    private static float ringDistance(PointOfInterest out, Pair<PointOfInterest, PointOfInterest> p) {
        PointOfInterest ring = p.getLeft() == out ? p.getRight() : p.getLeft();
        return out.getPosition().dst(ring.getPosition());
    }

    /** Round 392: the roads into the star an outside town has beyond the one to its nearest Ring City (rule 4). */
    static List<Edge> extraStarLinks(List<Edge> edges) {
        Map<PointOfInterest, Edge> keep = new IdentityHashMap<>();
        for (Edge e : edges) {
            PointOfInterest out = outsideEnd(e.a, e.b);
            if (out == null)
                continue;
            Edge cur = keep.get(out);
            if (cur == null || out.getPosition().dst((e.a == out ? e.b : e.a).getPosition())
                    < out.getPosition().dst((cur.a == out ? cur.b : cur.a).getPosition()))
                keep.put(out, e);
        }
        List<Edge> extra = new ArrayList<>();
        for (Edge e : edges) {
            PointOfInterest out = outsideEnd(e.a, e.b);
            if (out != null && keep.get(out) != e)
                extra.add(e);
        }
        return extra;
    }

    /** Rule 4 (round 392): a save's extra roads into the star lifted - old road only, each town's own square kept. */
    static int liftExtraStarLinks(World world, Set<Long> touched) {
        List<PointOfInterest> towns = towns(world);
        List<Edge> edges = detectEdges(world, towns, null);
        List<Edge> extra = extraStarLinks(edges);
        edges.removeAll(extra);
        Set<Long> keep = groundTiles(edges);
        Set<Long> prot = protectedTiles(world, towns);
        int tiles = 0;
        StringBuilder names = new StringBuilder();
        for (Edge e : extra) {
            for (long c : e.canon)
                tiles += liftOld(world, c, prot, keep, touched);
            for (long c : e.reverse)
                tiles += liftOld(world, c, prot, keep, touched);
            names.append(names.length() == 0 ? "" : ", ").append(e.a.getDisplayName()).append(" - ").append(e.b.getDisplayName());
        }
        System.out.println("[TFR-Roads] rule 4: " + extra.size() + " extra road(s) into the star lifted (" + tiles
                + " tile(s)) - each town outside it keeps one, to its nearest Ring City" + (extra.isEmpty() ? "" : ": " + names));
        return tiles;
    }

    // ------------------------------------------------------------------ geometry

    static long key(int x, int rawY) {
        return (long) x << 32 | (rawY & 0xffffffffL);
    }

    static int keyX(long key) {
        return (int) (key >> 32);
    }

    static int keyY(long key) {
        return (int) key;
    }

    static int[] anchor(World world, PointOfInterest poi) {
        Vector2 t = poi.getTilePosition(world.getTileSize());
        return new int[]{(int) t.x, (int) t.y};
    }

    /** Is (ax, ay) the end a road between the two starts from? The smaller x, then the smaller y. */
    public static boolean canonicalFirst(int ax, int ay, int bx, int by) {
        return ax < bx || (ax == bx && ay <= by);
    }

    /** World-gen's town links, each pair once, each walked from its canonical end - first-named order kept. */
    public static List<Pair<PointOfInterest, PointOfInterest>> uniqueCanonicalPairs(
            List<Pair<PointOfInterest, PointOfInterest>> pairs, int tileSize) {
        Map<String, Pair<PointOfInterest, PointOfInterest>> out = new LinkedHashMap<>();
        for (Pair<PointOfInterest, PointOfInterest> p : pairs) {
            PointOfInterest a = p.getKey(), b = p.getValue();
            if (a == null || b == null || a == b)
                continue;
            Vector2 ta = a.getTilePosition(tileSize), tb = b.getTilePosition(tileSize);
            if (!canonicalFirst((int) ta.x, (int) ta.y, (int) tb.x, (int) tb.y)) {
                PointOfInterest swap = a;
                a = b;
                b = swap;
            }
            out.putIfAbsent(System.identityHashCode(a) + "|" + System.identityHashCode(b) + "|" + a.getID() + "|" + b.getID(),
                    Pair.of(a, b));
        }
        return new ArrayList<>(out.values());
    }

    /** layRoad()'s walk from (x0, y0) to (x1, y1) in world tiles, as raw keys; tiles off the map skipped as it skips. */
    static long[] walk(World world, int x0, int y0, int x1, int y1) {
        int width = world.getWidthInTiles(), height = world.getHeightInTiles();
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        long[] out = new long[Math.min(dx + dy + 1, 1000)];
        int n = 0;
        for (int i = 0; i < 1000; i++) {
            if (!(x0 < 0 || y0 <= 0 || x0 >= width || y0 > height))
                out[n++] = key(x0, height - y0);
            if (x0 == x1 && y0 == y1)
                break;
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            } else if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
        return Arrays.copyOf(out, n);
    }

    /** The staircase a road between the two lays now (from the canonical end), or the other one. */
    static long[] walkBetween(World world, PointOfInterest a, PointOfInterest b, boolean canonical) {
        int[] pa = anchor(world, a), pb = anchor(world, b);
        boolean aFirst = canonicalFirst(pa[0], pa[1], pb[0], pb[1]) == canonical;
        return aFirst ? walk(world, pa[0], pa[1], pb[0], pb[1]) : walk(world, pb[0], pb[1], pa[0], pa[1]);
    }

    static float coverage(World world, long[] cells) {
        if (cells.length == 0)
            return 0f;
        int hit = 0;
        for (long c : cells)
            if (world.roadKindRaw(keyX(c), keyY(c)) > 0)
                hit++;
        return hit / (float) cells.length;
    }

    static double tileDistance(World world, PointOfInterest a, PointOfInterest b) {
        int[] pa = anchor(world, a), pb = anchor(world, b);
        return Math.hypot(pa[0] - pb[0], pa[1] - pb[1]);
    }

    /**
     * Round 383 (QA: "an old road can stop one tile short of a restored town"). A town's anchor column is the middle of
     * its sprite (PointOfInterest.getTilePosition), and the sprite's width changes with the town: world-gen lays every
     * road, THEN TerritoryControl.neutralizeAfterGeneration turns each Forest Town (32 px) outside the green keep into a
     * Waste Town (48 px), and every capture swaps a town's data the same way. The anchor moves a column and the road on
     * the ground still ends at the old one - 32 of the 254 roads in the user's world, the ruins a player restores among
     * them. Walked from today's anchors those roads are below EDGE_COVERAGE, so no rule here saw them: never kept, and
     * the lift along a new road's other staircase cut them beside the town (an offline replay of the user's save: a
     * restore's hop into a former Forest ruin cut another road within five tiles of it in 116 of 1,876 cases, any town
     * 211 of 3,778 - none after this round). The anchor columns a town's sprite widths give.
     */
    static final int[] TOWN_SPRITE_WIDTHS = {32, 48, 64};

    static int[] anchorColumns(World world, PointOfInterest poi) {
        int ts = world.getTileSize();
        int[] out = new int[TOWN_SPRITE_WIDTHS.length + 1];
        int n = 0;
        out[n++] = anchor(world, poi)[0];
        for (int w : TOWN_SPRITE_WIDTHS) {
            int x = (int) ((poi.getPosition().x + w / 2f) / ts);
            boolean seen = false;
            for (int i = 0; i < n; i++)
                seen |= out[i] == x;
            if (!seen)
                out[n++] = x;
        }
        return Arrays.copyOf(out, n);
    }

    /**
     * Round 383: the two staircases between a and b as the road on the ground was walked - {canonical, other}. Today's
     * anchors when either of their staircases is at least EDGE_COVERAGE road, else the anchor columns (anchorColumns)
     * whose staircases are best covered: a road laid before a town changed its sprite.
     */
    static long[][] groundWalks(World world, PointOfInterest a, PointOfInterest b) {
        int[] pa = anchor(world, a), pb = anchor(world, b);
        long[][] best = walkPair(world, pa[0], pa[1], pb[0], pb[1]);
        float bestCover = Math.max(coverage(world, best[0]), coverage(world, best[1]));
        if (bestCover >= EDGE_COVERAGE)
            return best;
        for (int xa : anchorColumns(world, a)) {
            for (int xb : anchorColumns(world, b)) {
                if (xa == pa[0] && xb == pb[0])
                    continue;
                long[][] w = walkPair(world, xa, pa[1], xb, pb[1]);
                float cover = Math.max(coverage(world, w[0]), coverage(world, w[1]));
                if (cover > bestCover) {
                    best = w;
                    bestCover = cover;
                }
            }
        }
        return best;
    }

    /** {the walk from the canonical end, the other} between two anchor tiles. */
    static long[][] walkPair(World world, int xa, int ya, int xb, int yb) {
        boolean aFirst = canonicalFirst(xa, ya, xb, yb);
        long[] fromA = walk(world, xa, ya, xb, yb), fromB = walk(world, xb, yb, xa, ya);
        return aFirst ? new long[][]{fromA, fromB} : new long[][]{fromB, fromA};
    }

    /**
     * Does a road already run between these two towns - either staircase at least EDGE_COVERAGE road? Round 383 left
     * this on today's anchors: it prices TerritoryControl's old-road routes, and an old road laid along a pair is
     * walked from today's anchors without lifting anything - routing it onto a road laid to an older anchor would draw
     * a second staircase a column beside it.
     */
    public static boolean roadJoins(World world, PointOfInterest a, PointOfInterest b) {
        if (a == null || b == null || a == b || tileDistance(world, a, b) > MAX_EDGE_TILES)
            return false;
        return coverage(world, walkBetween(world, a, b, true)) >= EDGE_COVERAGE
                || coverage(world, walkBetween(world, a, b, false)) >= EDGE_COVERAGE;
    }

    /** The cost of one hop for the routers (distance squared), cheaper along a road already there. */
    public static double hopCost(World world, PointOfInterest a, PointOfInterest b, boolean joined) {
        return a.getPosition().dst2(b.getPosition()) * (joined ? EXISTING_ROAD_DISCOUNT : 1.0);
    }

    // ------------------------------------------------------------------ the roads as pairs of towns

    static final class Edge {
        final PointOfInterest a, b;
        final long[] canon, reverse, drawn, ground;
        final float kc, kr;
        float formerShare;

        Edge(PointOfInterest a, PointOfInterest b, long[] canon, long[] reverse, float kc, float kr, long[] ground) {
            this.a = a;
            this.b = b;
            this.canon = canon;
            this.reverse = reverse;
            this.kc = kc;
            this.kr = kr;
            // Round 383: the staircase more of which is road. A pair laid from its other end before round 351 can cover
            // its canonical staircase 95% too, and taking that one protected the wrong tiles - a lift could then take a
            // step of the real road (the likely source of round 365's corner joint by Silent Crossing).
            this.drawn = kc >= EDGE_COVERAGE && kc >= kr ? canon : reverse;
            this.ground = ground;
        }

        boolean both() {
            return kc >= EDGE_COVERAGE && kr >= EDGE_COVERAGE;
        }

        boolean joins(PointOfInterest p, PointOfInterest q) {
            return (a == p && b == q) || (a == q && b == p);
        }
    }

    /** Every pair of towns within MAX_EDGE_TILES that a road joins; formerShare = the share of its tiles in `former`. */
    static List<Edge> detectEdges(World world, List<PointOfInterest> towns, Set<Long> former) {
        List<Edge> out = new ArrayList<>();
        int n = towns.size();
        int[][] anchors = new int[n][];
        for (int i = 0; i < n; i++)
            anchors[i] = anchor(world, towns.get(i));
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (Math.hypot(anchors[i][0] - anchors[j][0], anchors[i][1] - anchors[j][1]) > MAX_EDGE_TILES)
                    continue;
                PointOfInterest a = towns.get(i), b = towns.get(j);
                long[][] w = groundWalks(world, a, b); // round 383: as walked, the anchor a sprite change moved or not
                long[] canon = w[0], reverse = w[1];
                float kc = coverage(world, canon), kr = coverage(world, reverse);
                if (Math.max(kc, kr) < EDGE_COVERAGE)
                    continue;
                Edge e = new Edge(a, b, canon, reverse, kc, kr, roadCells(world, canon, reverse));
                if (former != null && e.drawn.length > 0) {
                    int f = 0;
                    for (long c : e.drawn)
                        if (former.contains(c))
                            f++;
                    e.formerShare = f / (float) e.drawn.length;
                }
                out.add(e);
            }
        }
        return out;
    }

    static Edge findEdge(List<Edge> edges, PointOfInterest p, PointOfInterest q) {
        for (Edge e : edges)
            if (e.joins(p, q))
                return e;
        return null;
    }

    static Set<Long> drawnTiles(Collection<Edge> edges) {
        Set<Long> out = new HashSet<>();
        for (Edge e : edges)
            for (long c : e.drawn)
                out.add(c);
        return out;
    }

    /** Round 383: the cells of either staircase that are road - an edge's road as it lies on the ground. */
    static long[] roadCells(World world, long[] canon, long[] reverse) {
        Set<Long> out = new LinkedHashSet<>();
        for (long[] cells : new long[][]{canon, reverse})
            for (long c : cells)
                if (world.roadKindRaw(keyX(c), keyY(c)) > 0)
                    out.add(c);
        long[] arr = new long[out.size()];
        int i = 0;
        for (long c : out)
            arr[i++] = c;
        return arr;
    }

    /**
     * Round 383: what a lift along another road leaves alone - every road tile of these edges' staircases, not just the
     * one drawnTiles() takes for theirs. A lift that took a step of a road's real staircase left it a corner short
     * (round 365) or, across a straight run, a tile short.
     */
    static Set<Long> groundTiles(Collection<Edge> edges) {
        Set<Long> out = new HashSet<>();
        for (Edge e : edges)
            for (long c : e.ground)
                out.add(c);
        return out;
    }

    /**
     * Old road never lifted: each town's own square - the 3x3 world-gen stamps at a road's first town (raw rows
     * height - y - 2 .. height - y, one row above the walk's). A wider guard kept the pieces of lifted lines that ran
     * through it, and they showed as loose bits of road beside the Capitol.
     */
    static Set<Long> protectedTiles(World world, List<PointOfInterest> towns) {
        Set<Long> out = new HashSet<>();
        int h = world.getHeightInTiles();
        for (PointOfInterest t : towns) {
            int[] a = anchor(world, t);
            for (int dx = -1; dx <= 1; dx++)
                for (int rawY = h - a[1] - 2; rawY <= h - a[1]; rawY++)
                    out.add(key(a[0] + dx, rawY));
        }
        return out;
    }

    /** Each town's anchor tile, where its roads end - all the squeeze rule leaves alone. */
    static Set<Long> anchorTiles(World world, List<PointOfInterest> towns) {
        Set<Long> out = new HashSet<>();
        int h = world.getHeightInTiles();
        for (PointOfInterest t : towns) {
            int[] a = anchor(world, t);
            out.add(key(a[0], h - a[1]));
        }
        return out;
    }

    /** Lifts one old road tile - never a player road, a protected tile or a kept one. Returns 1 when it went. */
    static int liftOld(World world, long c, Set<Long> prot, Set<Long> keep, Set<Long> touched) {
        if (prot.contains(c) || (keep != null && keep.contains(c)))
            return 0;
        if (world.roadKindRaw(keyX(c), keyY(c)) != World.ROAD_OLD)
            return 0;
        return world.setRoadKindRaw(keyX(c), keyY(c), World.ROAD_NONE, touched) ? 1 : 0;
    }

    /** Does the edge run past another town (a chain through it, not a road of its own)? */
    static boolean throughTown(World world, Edge e, List<PointOfInterest> towns) {
        int h = world.getHeightInTiles();
        Set<Long> anchors = new HashSet<>();
        for (PointOfInterest t : towns) {
            if (t == e.a || t == e.b)
                continue;
            int[] a = anchor(world, t);
            anchors.add(key(a[0], h - a[1]));
        }
        for (long c : e.drawn)
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    if (anchors.contains(key(keyX(c) + dx, keyY(c) + dy)))
                        return true;
        return false;
    }

    // ------------------------------------------------------------------ old roads

    /** The star down to its wheel and every pair drawn from both ends down to one staircase. Old road only. */
    static void normalizeOldRoads(World world, List<PointOfInterest> towns, Star star, Set<Long> prot, Set<Long> touched) {
        List<Edge> edges = detectEdges(world, towns, null);
        List<Edge> clutter = new ArrayList<>();
        for (Edge e : edges)
            if (star.role(e.a) != 0 && star.role(e.b) != 0 && !star.allowsHop(e.a, e.b))
                clutter.add(e);
        edges.removeAll(clutter);
        List<Edge> shortcuts = shortcuts(world, towns, star, edges);
        edges.removeAll(shortcuts);
        Set<Long> kept = drawnTiles(edges);
        int clutterTiles = 0, shortcutTiles = 0;
        for (Edge e : clutter) {
            for (long c : e.canon)
                clutterTiles += liftOld(world, c, prot, kept, touched);
            for (long c : e.reverse)
                clutterTiles += liftOld(world, c, prot, kept, touched);
        }
        for (Edge e : shortcuts) {
            for (long c : e.canon)
                shortcutTiles += liftOld(world, c, prot, kept, touched);
            for (long c : e.reverse)
                shortcutTiles += liftOld(world, c, prot, kept, touched);
        }
        int inner = sweepWheel(world, star, prot, kept, touched);
        int doubledPairs = 0, doubledTiles = 0;
        for (Edge e : edges) {
            if (!e.both())
                continue;
            doubledPairs++;
            Set<Long> canon = new HashSet<>();
            for (long c : e.canon)
                canon.add(c);
            for (long c : e.reverse)
                if (!canon.contains(c))
                    doubledTiles += liftOld(world, c, prot, kept, touched);
        }
        int campLink = 0;
        if (star.hub != null && star.camp != null && star.hub != star.camp && findEdge(edges, star.hub, star.camp) == null
                && !world.roadLineCrossesBarrier(star.hub, star.camp))
            campLink = world.buildRoad(Arrays.asList(star.hub, star.camp), null);
        System.out.println("[TFR-Roads] old roads: " + edges.size() + " town pair(s) joined; the wheel - " + clutter.size()
                + " line(s) off it lifted (" + clutterTiles + " tile(s)) + " + inner + " stray tile(s) inside the rim"
                + (campLink > 0 ? ", the campfire's road to the hub laid (" + campLink + " tile(s))" : "") + "; "
                + doubledPairs + " pair(s) drawn from both ends down to one staircase (" + doubledTiles + " tile(s)); "
                + shortcuts.size() + " road(s) past a town already chained on both sides lifted (" + shortcutTiles + " tile(s))");
    }

    /**
     * Round 351b (the user: "There were two roads, one leading from Shiv (Red Ring City) to two nearby towns. I think it
     * should have been 1 to one town and from that town to the next. The road with the red arrow should not have
     * existed."): the roads that pass a town w lying between their ends - nearer to both than they are to each other -
     * while roads already join w to both ends. The chain through w carries them. Each lifted road's chain is shorter at
     * every step, so nothing is cut off. The star's own pairs are the wheel's business.
     */
    static List<Edge> shortcuts(World world, List<PointOfInterest> towns, Star star, List<Edge> edges) {
        Map<PointOfInterest, int[]> anchors = new IdentityHashMap<>();
        for (PointOfInterest t : towns)
            anchors.put(t, anchor(world, t));
        Set<Long> joined = new HashSet<>();
        Map<PointOfInterest, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < towns.size(); i++)
            index.put(towns.get(i), i);
        for (Edge e : edges)
            joined.add(pairKey(index.get(e.a), index.get(e.b)));
        List<Edge> out = new ArrayList<>();
        for (Edge e : edges) {
            if (star.role(e.a) != 0 && star.role(e.b) != 0)
                continue;
            double d = dist(anchors.get(e.a), anchors.get(e.b));
            int ia = index.get(e.a), ib = index.get(e.b);
            for (int iw = 0; iw < towns.size(); iw++) {
                if (iw == ia || iw == ib)
                    continue;
                int[] w = anchors.get(towns.get(iw));
                if (Math.max(dist(anchors.get(e.a), w), dist(w, anchors.get(e.b))) < d
                        && joined.contains(pairKey(ia, iw)) && joined.contains(pairKey(iw, ib))) {
                    out.add(e);
                    break;
                }
            }
        }
        return out;
    }

    static double dist(int[] a, int[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    /** Old road inside the rim that no kept line draws. */
    static int sweepWheel(World world, Star star, Set<Long> prot, Set<Long> keep, Set<Long> touched) {
        int n = star.rim.size();
        if (n < 3)
            return 0;
        int h = world.getHeightInTiles();
        double[] px = new double[n], py = new double[n];
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int[] a = anchor(world, star.rim.get(i));
            px[i] = a[0];
            py[i] = h - a[1];
            x0 = Math.min(x0, a[0]);
            x1 = Math.max(x1, a[0]);
            y0 = Math.min(y0, h - a[1]);
            y1 = Math.max(y1, h - a[1]);
        }
        int lifted = 0;
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                if (world.roadKindRaw(x, y) == World.ROAD_OLD && insidePolygon(px, py, x, y))
                    lifted += liftOld(world, key(x, y), prot, keep, touched);
        return lifted;
    }

    static boolean insidePolygon(double[] px, double[] py, double x, double y) {
        boolean in = false;
        for (int i = 0, j = px.length - 1; i < px.length; j = i++)
            if ((py[i] > y) != (py[j] > y) && x < (px[j] - px[i]) * (y - py[i]) / (py[j] - py[i]) + px[i])
                in = !in;
        return in;
    }

    // ------------------------------------------------------------------ the player's network

    /**
     * Grows the network toward the targets - each time the target cheapest to reach from anything already on it -
     * adding the hops it takes. Returns the targets it could not reach.
     */
    static List<PointOfInterest> grow(World world, List<PointOfInterest> towns, Star star, List<Edge> edges,
                                      Set<PointOfInterest> network, Collection<PointOfInterest> targets,
                                      List<PointOfInterest[]> hops, String why) {
        int n = towns.size();
        Map<PointOfInterest, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < n; i++)
            index.put(towns.get(i), i);
        Set<Long> joined = new HashSet<>();
        for (Edge e : edges) {
            Integer i = index.get(e.a), j = index.get(e.b);
            if (i != null && j != null)
                joined.add(pairKey(i, j));
        }
        Set<PointOfInterest> remaining = new LinkedHashSet<>(targets);
        remaining.removeAll(network);
        while (!remaining.isEmpty()) {
            double[] best = new double[n];
            int[] prev = new int[n];
            boolean[] done = new boolean[n];
            Arrays.fill(best, Double.MAX_VALUE);
            Arrays.fill(prev, -1);
            for (PointOfInterest p : network) {
                Integer i = index.get(p);
                if (i != null)
                    best[i] = 0;
            }
            int reached = -1;
            for (int iter = 0; iter < n; iter++) {
                int u = -1;
                double uBest = Double.MAX_VALUE;
                for (int i = 0; i < n; i++)
                    if (!done[i] && best[i] < uBest) {
                        uBest = best[i];
                        u = i;
                    }
                if (u < 0)
                    break;
                done[u] = true;
                if (remaining.contains(towns.get(u))) {
                    reached = u;
                    break;
                }
                PointOfInterest pu = towns.get(u);
                for (int v = 0; v < n; v++) {
                    if (done[v])
                        continue;
                    PointOfInterest pv = towns.get(v);
                    if (!star.allowsHop(pu, pv) || world.roadLineCrossesBarrier(pu, pv))
                        continue;
                    double cost = best[u] + hopCost(world, pu, pv, joined.contains(pairKey(u, v)));
                    if (cost < best[v]) {
                        best[v] = cost;
                        prev[v] = u;
                    }
                }
            }
            if (reached < 0) {
                StringBuilder names = new StringBuilder();
                for (PointOfInterest p : remaining)
                    names.append(names.length() == 0 ? "" : ", ").append(p.getDisplayName());
                System.out.println("[TFR-Roads] player road (" + why + "): no route to " + names
                        + " (the barrier, or a town off the graph)");
                return new ArrayList<>(remaining);
            }
            List<PointOfInterest> path = new ArrayList<>();
            for (int i = reached; i >= 0; i = prev[i])
                path.add(towns.get(i));
            java.util.Collections.reverse(path);
            StringBuilder route = new StringBuilder();
            for (int i = 0; i < path.size(); i++) {
                route.append(i == 0 ? "" : " -> ").append(path.get(i).getDisplayName());
                if (i > 0)
                    hops.add(new PointOfInterest[]{path.get(i - 1), path.get(i)});
            }
            network.addAll(path);
            remaining.remove(towns.get(reached));
            System.out.println("[TFR-Roads] player road (" + why + "): " + route);
        }
        return new ArrayList<>();
    }

    static long pairKey(int i, int j) {
        return i < j ? (long) i << 32 | j : (long) j << 32 | i;
    }

    static boolean hasHop(List<PointOfInterest[]> hops, PointOfInterest a, PointOfInterest b) {
        for (PointOfInterest[] h : hops)
            if ((h[0] == a && h[1] == b) || (h[0] == b && h[1] == a))
                return true;
        return false;
    }

    /**
     * Lays the hops as player road, each on its canonical staircase, then lifts what the upgrade leaves beside it: the
     * pair's old road on either staircase, unless another road lies there (round 383: its road as it lies, not the
     * staircase taken for it). Returns {tiles laid, old tiles paved over, tiles lifted}.
     */
    static int[] layHops(World world, List<PointOfInterest[]> hops, List<Edge> edges, Set<Long> prot, Set<Long> touched) {
        int laid = 0, paved = 0, lifted = 0;
        Set<Long> playerDrawn = new HashSet<>();
        for (PointOfInterest[] hop : hops) {
            if (world.roadLineCrossesBarrier(hop[0], hop[1]))
                continue;
            for (long c : walkBetween(world, hop[0], hop[1], true)) {
                playerDrawn.add(c);
                int before = world.roadKindRaw(keyX(c), keyY(c));
                if (world.setRoadKindRaw(keyX(c), keyY(c), World.ROAD_PLAYER, touched)) {
                    laid++;
                    if (before == World.ROAD_OLD)
                        paved++;
                }
            }
        }
        for (PointOfInterest[] hop : hops) {
            Edge own = findEdge(edges, hop[0], hop[1]);
            List<Edge> others = new ArrayList<>();
            for (Edge e : edges)
                if (e != own && !hasHop(hops, e.a, e.b))
                    others.add(e);
            // Round 383: the pair's own old road as it lies (both staircases, walked from the anchors it was laid to),
            // and nothing else. The blind lift along today's other staircase took whatever old road crossed it - a
            // road no rule saw, ending at a town whose sprite changed, cut a tile or a corner from the town. Old road
            // left squeezed beside the new road still goes, in thinBesidePlayerRoad, while its neighbors stay joined.
            Set<Long> keep = groundTiles(others);
            keep.addAll(playerDrawn);
            if (own != null)
                for (long c : own.ground)
                    lifted += liftOld(world, c, prot, keep, touched);
        }
        return new int[]{laid, paved, lifted};
    }

    /** The upgrade rule for the player's own places: every old road between two of them joins the network. */
    static int addHeldPairs(World world, List<Edge> edges, Set<PointOfInterest> places, List<PointOfInterest> towns,
                            List<PointOfInterest[]> hops, PointOfInterest onlyTouching) {
        int added = 0;
        for (Edge e : edges) {
            if (!places.contains(e.a) || !places.contains(e.b) || e.formerShare > 0.5f || hasHop(hops, e.a, e.b))
                continue;
            if (onlyTouching != null && e.a != onlyTouching && e.b != onlyTouching)
                continue;
            if (throughTown(world, e, towns))
                continue;
            hops.add(new PointOfInterest[]{e.a, e.b});
            added++;
        }
        return added;
    }

    /**
     * Old road squeezed against a player road - a tile filling a 2x2 block of road with it - goes when its neighbors stay
     * joined without it (a town's square beside the road included; `keep` is the towns' anchor tiles).
     */
    static int thinBesidePlayerRoad(World world, Set<Long> keep, Set<Long> touched) {
        int w = world.getWidthInTiles(), h = world.getHeightInTiles();
        int lifted = 0;
        boolean changed = true;
        for (int pass = 0; changed && pass < 16; pass++) {
            changed = false;
            for (int x = 1; x < w - 1; x++) {
                for (int y = 1; y < h - 1; y++) {
                    if (world.roadKindRaw(x, y) != World.ROAD_PLAYER)
                        continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            int qx = x + dx, qy = y + dy;
                            if (world.roadKindRaw(qx, qy) != World.ROAD_OLD || keep.contains(key(qx, qy)))
                                continue;
                            if (fillsBlock(world, qx, qy) && staysJoined(world, qx, qy)
                                    && world.setRoadKindRaw(qx, qy, World.ROAD_NONE, touched)) {
                                lifted++;
                                changed = true;
                            }
                        }
                    }
                }
            }
        }
        return lifted;
    }

    static boolean road(World world, int x, int y) {
        return world.roadKindRaw(x, y) > 0;
    }

    static boolean fillsBlock(World world, int x, int y) {
        for (int dx = -1; dx <= 1; dx += 2)
            for (int dy = -1; dy <= 1; dy += 2)
                if (road(world, x + dx, y + dy) && road(world, x, y + dy) && road(world, x + dx, y))
                    return true;
        return false;
    }

    /** Without (x, y), are its road neighbors (4-way) still joined to each other inside its 3x3? */
    /**
     * Round 370: the player-road tiles within three tiles of `place`'s plaza that are not the plaza itself come up -
     * a row laid too low, a larger size of an earlier round - one at a time while the road around each stays joined
     * without it (so a road running into the plaza keeps every tile it needs), until none goes. The plaza and the
     * place's road anchor stay. Returns the tiles lifted.
     */
    static int trimAroundPlaza(World world, PointOfInterest place, int size, Set<Long> touched) {
        if (world == null || place == null || size <= 0)
            return 0;
        int h = world.getHeightInTiles();
        int[] o = world.plazaOrigin(place, size);
        Set<Long> keep = new HashSet<>();
        int rows = size - World.plazaTopCut(size); // round 370b: the rows the plaza actually has
        for (int tx = o[0]; tx < o[0] + size; tx++)
            for (int ty = o[1]; ty < o[1] + rows; ty++)
                keep.add(key(tx, h - ty - 1));
        int[] a = anchor(world, place);
        keep.add(key(a[0], h - a[1]));
        int lifted = 0;
        boolean changed = true;
        for (int pass = 0; changed && pass < 16; pass++) {
            changed = false;
            for (int tx = o[0] - 3; tx < o[0] + size + 3; tx++) {
                for (int ty = o[1] - 3; ty < o[1] + size + 3; ty++) {
                    int rawY = h - ty - 1;
                    if (keep.contains(key(tx, rawY)) || world.roadKindRaw(tx, rawY) != World.ROAD_PLAYER)
                        continue;
                    if (staysJoined(world, tx, rawY) && world.setRoadKindRaw(tx, rawY, World.ROAD_NONE, touched)) {
                        lifted++;
                        changed = true;
                    }
                }
            }
        }
        return lifted;
    }

    static boolean staysJoined(World world, int x, int y) {
        int[][] four = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        List<int[]> ends = new ArrayList<>();
        for (int[] d : four)
            if (road(world, x + d[0], y + d[1]))
                ends.add(new int[]{x + d[0], y + d[1]});
        if (ends.size() < 2)
            return true;
        Set<Long> seen = new HashSet<>();
        List<int[]> stack = new ArrayList<>();
        stack.add(ends.get(0));
        seen.add(key(ends.get(0)[0], ends.get(0)[1]));
        while (!stack.isEmpty()) {
            int[] c = stack.remove(stack.size() - 1);
            for (int[] d : four) {
                int nx = c[0] + d[0], ny = c[1] + d[1];
                if (Math.abs(nx - x) > 1 || Math.abs(ny - y) > 1 || (nx == x && ny == y) || !road(world, nx, ny))
                    continue;
                if (seen.add(key(nx, ny)))
                    stack.add(new int[]{nx, ny});
            }
        }
        for (int[] e : ends)
            if (!seen.contains(key(e[0], e[1])))
                return false;
        return true;
    }

    /** Distance between two towns along the hops (tiles), or infinity. */
    static double networkDistance(World world, List<PointOfInterest[]> hops, PointOfInterest from, PointOfInterest to) {
        Map<PointOfInterest, Double> best = new IdentityHashMap<>();
        best.put(from, 0.0);
        List<PointOfInterest> open = new ArrayList<>();
        open.add(from);
        while (!open.isEmpty()) {
            PointOfInterest u = open.get(0);
            for (PointOfInterest p : open)
                if (best.get(p) < best.get(u))
                    u = p;
            open.remove(u);
            if (u == to)
                return best.get(u);
            for (PointOfInterest[] h : hops) {
                PointOfInterest v = h[0] == u ? h[1] : h[1] == u ? h[0] : null;
                if (v == null)
                    continue;
                double d = best.get(u) + tileDistance(world, u, v);
                Double old = best.get(v);
                if (old == null || d < old) {
                    best.put(v, d);
                    if (!open.contains(v))
                        open.add(v);
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    /** A save migrating: the old network's hops the new one left - lifted when the new network joins their ends. */
    static int liftAbandoned(World world, List<Edge> edges, List<PointOfInterest[]> hops, Set<PointOfInterest> network,
                             Set<Long> former, Set<Long> prot, Set<Long> touched) {
        List<Edge> original = new ArrayList<>();
        for (Edge e : edges)
            if (e.formerShare <= 0.5f)
                original.add(e);
        Set<Long> keep = drawnTiles(original);
        int lifted = 0;
        for (Edge e : edges) {
            if (e.formerShare <= 0.5f || hasHop(hops, e.a, e.b))
                continue;
            double direct = tileDistance(world, e.a, e.b);
            double via = network.contains(e.a) && network.contains(e.b) ? networkDistance(world, hops, e.a, e.b)
                    : Double.POSITIVE_INFINITY;
            boolean redundant = via <= ABANDONED_DETOUR * direct;
            System.out.println("[TFR-Roads] the old network's " + e.a.getDisplayName() + " - " + e.b.getDisplayName()
                    + ": " + (redundant ? String.format("lifted - the new network joins them in %.0f tile(s) vs %.0f", via, direct)
                    : "kept as an old road"));
            if (!redundant)
                continue;
            for (long c : e.drawn)
                if (former.contains(c))
                    lifted += liftOld(world, c, prot, keep, touched);
        }
        return lifted;
    }

    /** The towns the player road already reaches: the Capitol and each town with player road at its anchor. */
    static Set<PointOfInterest> onNetwork(World world, List<PointOfInterest> towns, PointOfInterest capitol) {
        Set<PointOfInterest> out = new LinkedHashSet<>();
        out.add(capitol);
        int h = world.getHeightInTiles();
        for (PointOfInterest t : towns) {
            int[] a = anchor(world, t);
            search:
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    if (world.roadKindRaw(a[0] + dx, h - a[1] + dy) == World.ROAD_PLAYER) {
                        out.add(t);
                        break search;
                    }
        }
        return out;
    }

    /**
     * The Capitol's network laid fresh - the Capitol raised, or a save migrating (`former` = the tiles that were
     * player road before; null otherwise).
     */
    public static void rebuildPlayerNetwork(World world, BiConsumer<Integer, Integer> onTileRepainted, String why,
                                            Set<Long> former) {
        PointOfInterest capitol = TownRestoration.findCapitol();
        if (world == null || capitol == null)
            return;
        long t0 = System.nanoTime();
        List<PointOfInterest> towns = towns(world);
        Star star = new Star(towns);
        Set<Long> prot = protectedTiles(world, towns);
        Set<Long> touched = new HashSet<>();
        List<PointOfInterest> held = PlayerRoads.heldTowns(world, capitol);
        List<Edge> edges = detectEdges(world, towns, former);
        Set<PointOfInterest> network = new LinkedHashSet<>();
        network.add(capitol);
        List<PointOfInterest[]> hops = new ArrayList<>();
        grow(world, towns, star, edges, network, held, hops, why);
        Set<PointOfInterest> places = new HashSet<>(held);
        places.add(capitol);
        int heldPairs = addHeldPairs(world, edges, places, towns, hops, null);
        int[] laid = layHops(world, hops, edges, prot, touched);
        TuningData tuning = Config.instance().getTuningData();
        int plaza = world.stampPlayerRoadPatch(capitol, tuning.playerRoadPlazaCapitol, onTileRepainted);
        for (PointOfInterest town : held)
            plaza += world.stampPlayerRoadPatch(town, tuning.playerRoadPlazaTown, onTileRepainted);
        int abandoned = former == null ? 0 : liftAbandoned(world, edges, hops, network, former, prot, touched);
        int thinned = thinBesidePlayerRoad(world, anchorTiles(world, towns), touched);
        joinCorners(world, touched); // round 365
        world.repaintRoadTiles(touched, onTileRepainted);
        world.setPlayerRoadsBuilt(PlayerRoads.NETWORK_VERSION);
        System.out.println("[TFR-Roads] player road network (" + why + "): " + held.size() + " held town(s) from "
                + capitol.getDisplayName() + " - " + hops.size() + " hop(s) (" + heldPairs + " old road(s) between held places"
                + " upgraded as well), " + laid[0] + " tile(s) laid, " + laid[1] + " old tile(s) paved over, " + plaza
                + " plaza tile(s); lifted " + laid[2] + " beside the upgrades, " + abandoned + " of the old network, "
                + thinned + " squeezed against it - " + (System.nanoTime() - t0) / 1_000_000 + " ms");
    }

    /** A town restored or captured while the Capitol stands: joined to the nearest part of the network. */
    public static boolean connectPlayerTown(World world, PointOfInterest town, String why) {
        PointOfInterest capitol = TownRestoration.findCapitol();
        if (world == null || capitol == null || town == null)
            return false;
        if (town == capitol)
            return true;
        long t0 = System.nanoTime();
        List<PointOfInterest> towns = towns(world);
        Star star = new Star(towns);
        Set<Long> prot = protectedTiles(world, towns);
        Set<Long> touched = new HashSet<>();
        List<Edge> edges = detectEdges(world, towns, null);
        Set<PointOfInterest> network = onNetwork(world, towns, capitol);
        List<PointOfInterest[]> hops = new ArrayList<>();
        List<PointOfInterest> single = new ArrayList<>();
        single.add(town);
        grow(world, towns, star, edges, network, single, hops, why);
        Set<PointOfInterest> places = new HashSet<>(PlayerRoads.heldTowns(world, capitol));
        places.add(capitol);
        places.add(town);
        int heldPairs = addHeldPairs(world, edges, places, towns, hops, town);
        int[] laid = layHops(world, hops, edges, prot, touched);
        BiConsumer<Integer, Integer> repaint = liveRepaint();
        int plaza = world.stampPlayerRoadPatch(town, Config.instance().getTuningData().playerRoadPlazaTown, repaint);
        int thinned = thinBesidePlayerRoad(world, anchorTiles(world, towns), touched);
        joinCorners(world, touched); // round 365
        world.repaintRoadTiles(touched, repaint);
        System.out.println("[TFR-Roads] player road (" + why + "): " + town.getDisplayName() + " joined - " + hops.size()
                + " hop(s) (" + heldPairs + " old road(s) to held places upgraded as well), " + laid[0] + " tile(s) laid, "
                + laid[1] + " old tile(s) paved over, " + plaza + " plaza tile(s); lifted " + laid[2] + " beside the"
                + " upgrades, " + thinned + " squeezed against it - " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        return true;
    }

    /**
     * Round 365 (QA: "an old road can stop one tile short of a restored town"). A road draws by its four straight
     * neighbours, so two road tiles that touch only at a corner read as a break - the user's day-16 world had five,
     * each 2-6 tiles from a place, Silent Crossing's road one step short of its square. A lift that took a staircase's
     * corner tile (liftOld along a reverse walk crossing the road) leaves exactly that. Every such joint gets one corner
     * filled - old road, or player road when both ends are player road - never on water or the barrier. Runs after each
     * network change and on world generation; saves get it once on load (VERSION 3, this pass only).
     */
    public static int joinCorners(World world, Set<Long> touched) {
        if (world == null)
            return 0;
        int w = world.getWidthInTiles(), h = world.getHeightInTiles();
        int joined = 0;
        for (int x = 0; x < w - 1; x++) {
            for (int y = 0; y < h - 1; y++) {
                joined += joinCorner(world, x, y, x + 1, y + 1, x + 1, y, x, y + 1, touched);
                joined += joinCorner(world, x + 1, y, x, y + 1, x, y, x + 1, y + 1, touched);
            }
        }
        return joined;
    }

    private static int joinCorner(World world, int ax, int ay, int bx, int by, int c1x, int c1y, int c2x, int c2y,
                                  Set<Long> touched) {
        int a = world.roadKindRaw(ax, ay), b = world.roadKindRaw(bx, by);
        if (a <= 0 || b <= 0 || world.roadKindRaw(c1x, c1y) != World.ROAD_NONE || world.roadKindRaw(c2x, c2y) != World.ROAD_NONE)
            return 0;
        int kind = a == World.ROAD_PLAYER && b == World.ROAD_PLAYER ? World.ROAD_PLAYER : World.ROAD_OLD;
        int cx = c1x, cy = c1y;
        if (!world.canJoinRoadRaw(cx, cy)) {
            cx = c2x;
            cy = c2y;
            if (!world.canJoinRoadRaw(cx, cy))
                return 0;
        }
        if (!world.setRoadKindRaw(cx, cy, kind, touched))
            return 0;
        System.out.println("[TFR-Roads] corner joint at raw (" + ax + "," + ay + ")-(" + bx + "," + by + ") joined at ("
                + cx + "," + cy + ")");
        return 1;
    }

    static BiConsumer<Integer, Integer> liveRepaint() {
        WorldStage stage = WorldStage.getInstance();
        return stage == null ? null : stage::refreshBackgroundTile;
    }

    /** Once per save laid before these rules: the old roads normalized, a standing Capitol's network laid again. */
    public static void migrateOnLoad(World world) {
        if (world == null || world.getRoadsNormalized() >= VERSION)
            return;
        long t0 = System.nanoTime();
        if (world.getRoadsNormalized() < 2)
            normalizeAndRebuild(world);
        // Round 365: rule 3 is the corner-joint pass alone - a rule-2 save must NOT rerun the normalization above,
        // which would turn the player network back to old road and route it again against today's map.
        int joined = world.getRoadsNormalized() < 3 ? joinCorners(world, new HashSet<>()) : 0;
        // Round 392: rule 4 alone for a rule-3 save - one road into the star per outside town.
        liftExtraStarLinks(world, new HashSet<>());
        world.setRoadsNormalized(VERSION);
        System.out.println("[TFR-Roads] roads normalized to rule " + VERSION + " (" + joined + " corner joint(s) joined) in "
                + (System.nanoTime() - t0) / 1_000_000 + " ms");
    }

    /** Rules 1-2 (round 351/351b): the old roads normalized, a standing Capitol's network laid again. */
    private static void normalizeAndRebuild(World world) {
        PointOfInterest capitol = TownRestoration.findCapitol();
        Set<Long> former = null;
        if (capitol != null) {
            former = new HashSet<>();
            int w = world.getWidthInTiles(), h = world.getHeightInTiles();
            for (int x = 0; x < w; x++)
                for (int y = 0; y < h; y++)
                    if (world.roadKindRaw(x, y) == World.ROAD_PLAYER && world.setRoadKindRaw(x, y, World.ROAD_OLD, null))
                        former.add(key(x, y));
            System.out.println("[TFR-Roads] migrating: the old player network's " + former.size()
                    + " tile(s) turned back to old road for the new network to upgrade");
        }
        List<PointOfInterest> towns = towns(world);
        StringBuilder hidden = new StringBuilder();
        for (PointOfInterest poi : world.getAllPointOfInterest())
            if (isTownOrCapital(poi) && !poi.getActive())
                hidden.append(hidden.length() == 0 ? "" : ", ").append(poi.getDisplayName());
        System.out.println("[TFR-Roads] migrating: " + towns.size() + " town(s) on the map"
                + (hidden.length() == 0 ? "" : "; hidden, so no road ends there: " + hidden));
        normalizeOldRoads(world, towns, new Star(towns), protectedTiles(world, towns), new HashSet<>());
        if (capitol != null)
            rebuildPlayerNetwork(world, null, "a save from before round 351", former);
    }

    /** The old-road router's hop rule for TerritoryControl: the wheel, the barrier, and the discount. */
    public static final class HopCosts {
        final World world;
        final Star star;
        final Map<Long, Boolean> joined = new HashMap<>();
        final List<PointOfInterest> nodes;

        public HopCosts(World world, List<PointOfInterest> nodes) {
            this.world = world;
            this.nodes = nodes;
            this.star = new Star(nodes);
        }

        /** May a road run straight from node u to node v (the wheel)? */
        public boolean allows(int u, int v) {
            return star.allowsHop(nodes.get(u), nodes.get(v));
        }

        /** The cost of the hop from node u to node v - distance squared, cheaper along a road already there. */
        public double cost(int u, int v) {
            PointOfInterest a = nodes.get(u), b = nodes.get(v);
            Boolean j = joined.get(pairKey(u, v));
            if (j == null) {
                j = roadJoins(world, a, b);
                joined.put(pairKey(u, v), j);
            }
            return hopCost(world, a, b, j);
        }
    }
}
