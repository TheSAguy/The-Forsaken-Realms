import java.util.*;

/**
 * Differential test for round 193's short-circuit in World.claimWastelandRing().
 *
 * The optimization replaces "compute the exact minimum pull over every rival, then decide" with
 * "stop at the first source that decides it". Both forms are reproduced here verbatim and run
 * against randomized source sets and tiles; any disagreement on the CLAIM/SKIP outcome is a bug.
 *
 * Also reports how many rival comparisons each form performs, which is the whole point of the
 * change.
 */
public class ClaimEquiv {

    // rival layout, exactly as buildPullSources/claimWastelandRing pack it:
    //   [0]=x  [1]=y  [2]=weightSq  [3]=protectRadiusSq  [4]=ownerOrdinal
    // mySources layout: [0]=x [1]=y [2]=weight [3]=protectRadius

    static long oldComparisons = 0, newComparisons = 0;

    static float myPull(List<float[]> mySources, int wx, int wy) {
        float myPullSq = Float.MAX_VALUE;
        for (float[] s : mySources) {
            float sdx = wx - s[0], sdy = wy - s[1];
            float pull = (sdx * sdx + sdy * sdy) * s[2] * s[2];
            if (pull < myPullSq) myPullSq = pull;
        }
        return myPullSq;
    }

    /** The pre-round-193 form. */
    static boolean oldClaims(List<float[]> mySources, List<float[]> rivalFlat,
                             int wx, int wy, boolean isWasteland, int ownerOrdinal) {
        float myPullSq = myPull(mySources, wx, wy);
        boolean hardProtected = false;
        float ownerPullSq = Float.MAX_VALUE, bestRivalPullSq = Float.MAX_VALUE;
        for (float[] rival : rivalFlat) {
            float rdx = wx - rival[0], rdy = wy - rival[1];
            float rDistSq = rdx * rdx + rdy * rdy;
            oldComparisons++;
            if (rival[3] > 0 && rDistSq <= rival[3]) { hardProtected = true; break; }
            float pull = rDistSq * rival[2];
            if (pull < bestRivalPullSq) bestRivalPullSq = pull;
            if ((int) rival[4] == ownerOrdinal && pull < ownerPullSq) ownerPullSq = pull;
        }
        if (hardProtected) return false;
        if (isWasteland) {
            if (bestRivalPullSq < myPullSq) return false;
        } else {
            if (myPullSq >= ownerPullSq) return false;
        }
        return true;
    }

    /** The round-193 short-circuit form. */
    static boolean newClaims(List<float[]> mySources, List<float[]> rivalFlat,
                             int wx, int wy, boolean isWasteland, int ownerOrdinal) {
        float myPullSq = myPull(mySources, wx, wy);
        boolean loses = false;
        for (float[] rival : rivalFlat) {
            float rdx = wx - rival[0], rdy = wy - rival[1];
            float rDistSq = rdx * rdx + rdy * rdy;
            newComparisons++;
            if (rival[3] > 0 && rDistSq <= rival[3]) { loses = true; break; }
            float pull = rDistSq * rival[2];
            if (isWasteland) {
                if (pull < myPullSq) { loses = true; break; }
            } else if ((int) rival[4] == ownerOrdinal && pull <= myPullSq) {
                loses = true; break;
            }
        }
        return !loses;
    }

    public static void main(String[] args) {
        Random r = new Random(20260913L);
        int cases = 0, mismatches = 0, claimedOld = 0, claimedNew = 0;

        for (int trial = 0; trial < 4000; trial++) {
            // A world's worth of sources: 6 owners, up to ~300 sources total, mixed weights, and a
            // minority carrying a protection radius (castle keeps / town inner halves).
            int owners = 6;
            int sourceCount = 1 + r.nextInt(60);
            List<float[]> rivalFlat = new ArrayList<>();
            for (int i = 0; i < sourceCount; i++) {
                float x = r.nextInt(500), y = r.nextInt(500);
                float w = 0.5f + r.nextFloat() * 2f;
                // protection on ~20% of sources, and deliberately sometimes large
                float protect = r.nextInt(5) == 0 ? (float) Math.pow(2 + r.nextInt(30), 2) : 0f;
                rivalFlat.add(new float[]{x, y, w * w, protect, r.nextInt(owners)});
            }
            List<float[]> mySources = new ArrayList<>();
            int mine = 1 + r.nextInt(8);
            for (int i = 0; i < mine; i++)
                mySources.add(new float[]{r.nextInt(500), r.nextInt(500), 0.5f + r.nextFloat() * 2f, 0f});

            for (int t = 0; t < 40; t++) {
                int wx = r.nextInt(500), wy = r.nextInt(500);
                boolean isWasteland = r.nextBoolean();
                // -1 exercises the "owner has no sources in rivalFlat" case the original left at
                // Float.MAX_VALUE (and therefore claimed).
                int ownerOrdinal = isWasteland ? -1 : (r.nextInt(10) == 0 ? -1 : r.nextInt(owners));

                boolean a = oldClaims(mySources, rivalFlat, wx, wy, isWasteland, ownerOrdinal);
                boolean b = newClaims(mySources, rivalFlat, wx, wy, isWasteland, ownerOrdinal);
                cases++;
                if (a) claimedOld++;
                if (b) claimedNew++;
                if (a != b) {
                    mismatches++;
                    if (mismatches <= 5)
                        System.out.println("MISMATCH tile(" + wx + "," + wy + ") wasteland=" + isWasteland
                                + " ownerOrdinal=" + ownerOrdinal + " old=" + a + " new=" + b);
                }
            }
        }

        System.out.println("cases            : " + cases);
        System.out.println("claimed (old/new): " + claimedOld + " / " + claimedNew);
        System.out.println("MISMATCHES       : " + mismatches);
        System.out.println("rival comparisons: old=" + oldComparisons + "  new=" + newComparisons
                + "  (" + String.format("%.1f", 100.0 * newComparisons / oldComparisons) + "% of old)");
        System.out.println(mismatches == 0 ? "EQUIVALENT" : "*** NOT EQUIVALENT ***");
    }
}
