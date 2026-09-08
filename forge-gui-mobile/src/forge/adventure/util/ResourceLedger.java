package forge.adventure.util;

import forge.adventure.player.AdventurePlayer;
import forge.adventure.world.WorldSave;

/**
 * A recorded weekly tally of every resource that moves (round 148, user spec: the balance sheet
 * must show "All other Income - this is from stuff like quest rewards, loot, pickups, etc. - total
 * all incoming resources for the week tallied" and "All other expenses for the week, like
 * Buildings, Lost to re-rolls, duel lost, arena entry fees, item uses").
 * <p>
 * Round 147's sheet RECOMPUTED everything from the same helpers the weekly sweep pays from, which
 * works perfectly for the recurring lines - mines, interest, wages - because those are a rate, not
 * an event. It cannot work for the two the user asked for here: a quest reward or a lost duel
 * leaves no standing state behind to recompute from. Those have to be recorded as they happen.
 * <p>
 * <b>How the attribution works.</b> Rather than tagging twenty-odd call sites (and silently missing
 * every one added later), this reads an AMBIENT bucket that defaults to {@link Bucket#OTHER}. The
 * weekly sweep declares its own payouts with {@link #run}; everything else in the game - loot,
 * quest gold, shop purchases, re-rolls, arena fees - falls into "other" by simply not saying
 * anything, which is exactly what "everything else" means. A flow that is neither income nor
 * expense (a bank deposit, an exchange trade) declares {@link Bucket#IGNORED} so it does not
 * inflate both columns with the same gold twice.
 * <p>
 * Hooked into AdventurePlayer's four resource mutators, which every movement already funnels
 * through (addGold/giveGold route into takeGold, addShards into takeShards, and so on). The hook
 * records the ACTUAL delta rather than the requested amount, so takeGold's overspend clamp cannot
 * make the ledger disagree with the purse.
 * <p>
 * <b>Not Serializable and never written to the save as an object</b> - the {@link Book} is
 * persisted field by field, same standing rule as RoamingGuardData.
 */
public final class ResourceLedger {
    private ResourceLedger() {}

    public static final int GOLD = 0;
    public static final int SHARDS = 1;
    public static final int WOOD = 2;
    public static final int STONE = 3;
    public static final int RESOURCES = 4;

    /** Glyph per resource index, for the sheet. */
    public static final String[] GLYPH = {"[+Gold]", "[+Shards]", "[+Wood]", "[+Stone]"};

    public enum Bucket {
        MINES("Mines"),
        INTEREST("Bank interest"),
        GUARD_LOCAL("Local guards"),
        GUARD_ROAMING("Roaming guards"),
        OTHER("Everything else"),
        /** Moves that are transfers, not earnings: bank deposits/withdrawals and exchange trades.
         *  Counting them would show a 5,000 gold deposit as a 5,000 gold expense. */
        IGNORED(null);

        public final String label;
        Bucket(String label) { this.label = label; }
    }

    private static final Bucket[] TRACKED = {
            Bucket.MINES, Bucket.INTEREST, Bucket.GUARD_LOCAL, Bucket.GUARD_ROAMING, Bucket.OTHER};
    private static final int SLOTS = TRACKED.length;
    private static final int CELLS = SLOTS * RESOURCES;

    /** Two weeks of tallies - the one in progress and the last completed one, which is what the
     *  player actually wants to compare against. Owned by AdventurePlayer so it loads and resets
     *  with the save; this class only ever reads and writes it. */
    public static final class Book {
        private int week = -1;
        private final int[] thisIn = new int[CELLS];
        private final int[] thisOut = new int[CELLS];
        private final int[] lastIn = new int[CELLS];
        private final int[] lastOut = new int[CELLS];
        private boolean hasLast;
    }

    // ------------------------------------------------------------------ ambient bucket

    /** Per-thread, not a bare static. The weekly sweep runs on the render thread, but shards can be
     *  spent from the MATCH thread while a game is live (see DuelScene.chargeInGameManaShards) - a
     *  shared field would let a payout scope on one thread mis-file a spend on the other. A thread
     *  that never opens a scope sees OTHER, which is the right answer for all of them. */
    private static final ThreadLocal<Bucket> AMBIENT = ThreadLocal.withInitial(() -> Bucket.OTHER);

    /** Runs {@code body} with every resource movement inside it attributed to {@code bucket}.
     *  Restores the previous bucket in a finally so an exception mid-payout cannot leave the whole
     *  rest of the session mis-filed. */
    public static void run(Bucket bucket, Runnable body) {
        Bucket previous = enter(bucket);
        try {
            body.run();
        } finally {
            exit(previous);
        }
    }

    /** The same scope, opened by hand for the payout loops that mutate their own locals and so
     *  cannot be wrapped in a lambda. Always paired with {@link #exit} in a finally. */
    public static Bucket enter(Bucket bucket) {
        Bucket previous = AMBIENT.get();
        AMBIENT.set(bucket);
        return previous;
    }

    public static void exit(Bucket previous) {
        AMBIENT.set(previous);
    }

    // ------------------------------------------------------------------ recording

    /** Called from AdventurePlayer's mutators with the actual signed change: positive is income. */
    public static void moved(int resource, int delta) {
        post(AMBIENT.get(), resource, delta);
    }

    /** For movements that never reach the player's purse and so are invisible to {@link #moved} -
     *  a gold mine depositing straight into a town bank, interest accruing there, a guard's wage
     *  drawn from that bank. Real income and real expense; just not in the player's pocket. */
    public static void record(Bucket bucket, int resource, int delta) {
        post(bucket, resource, delta);
    }

    /** As {@link #record}, but files under whichever scope is open - for a bank-side movement whose
     *  meaning depends on its caller (a wage drawn from a town bank is a local OR roaming guard
     *  expense depending on which pass is running). */
    public static void recordAmbient(int resource, int delta) {
        post(AMBIENT.get(), resource, delta);
    }

    private static void post(Bucket bucket, int resource, int delta) {
        if (delta == 0 || bucket == Bucket.IGNORED || resource < 0 || resource >= RESOURCES)
            return;
        Book book = book();
        if (book == null)
            return; // before a game exists (menu, save load) there is nothing to bill to
        roll(book, currentWeek());
        int cell = slotOf(bucket) * RESOURCES + resource;
        if (delta > 0)
            book.thisIn[cell] += delta;
        else
            book.thisOut[cell] += -delta;
    }

    private static int slotOf(Bucket bucket) {
        for (int i = 0; i < SLOTS; i++)
            if (TRACKED[i] == bucket)
                return i;
        return SLOTS - 1; // OTHER
    }

    /** Rolls the week over lazily, so a resource that moves outside the day tick still lands in the
     *  right week and no call-ordering contract is needed anywhere. */
    private static void roll(Book book, int week) {
        if (week < 0 || book.week == week)
            return;
        if (book.week < 0) {
            book.week = week; // first movement of a new game
            return;
        }
        if (week == book.week + 1) {
            System.arraycopy(book.thisIn, 0, book.lastIn, 0, CELLS);
            System.arraycopy(book.thisOut, 0, book.lastOut, 0, CELLS);
            book.hasLast = true;
        } else {
            // Jumped more than a week (or backwards): the week immediately before this one saw no
            // recorded activity at all, so presenting a stale tally as "last week" would be a lie.
            java.util.Arrays.fill(book.lastIn, 0);
            java.util.Arrays.fill(book.lastOut, 0);
            book.hasLast = week > book.week;
        }
        java.util.Arrays.fill(book.thisIn, 0);
        java.util.Arrays.fill(book.thisOut, 0);
        book.week = week;
    }

    /** Called from the day sweep so the roll happens on schedule even in a week where the player
     *  neither earned nor spent anything. */
    public static void onDaysPassed(int newDayCount) {
        Book book = book();
        if (book != null)
            roll(book, newDayCount / 7);
    }

    // ------------------------------------------------------------------ reading

    public static int income(boolean lastWeek, Bucket bucket, int resource) {
        Book book = book();
        if (book == null)
            return 0;
        int cell = slotOf(bucket) * RESOURCES + resource;
        return lastWeek ? book.lastIn[cell] : book.thisIn[cell];
    }

    public static int expense(boolean lastWeek, Bucket bucket, int resource) {
        Book book = book();
        if (book == null)
            return 0;
        int cell = slotOf(bucket) * RESOURCES + resource;
        return lastWeek ? book.lastOut[cell] : book.thisOut[cell];
    }

    public static int totalIncome(boolean lastWeek, int resource) {
        int sum = 0;
        for (Bucket bucket : TRACKED)
            sum += income(lastWeek, bucket, resource);
        return sum;
    }

    public static int totalExpense(boolean lastWeek, int resource) {
        int sum = 0;
        for (Bucket bucket : TRACKED)
            sum += expense(lastWeek, bucket, resource);
        return sum;
    }

    /** False until a full week has actually elapsed and rolled - the sheet hides the Last Week
     *  view rather than showing a page of zeroes that looks like a bug. */
    public static boolean hasLastWeek() {
        Book book = book();
        return book != null && book.hasLast;
    }

    /** The in-game day this week started on, for the sheet's heading. */
    public static int weekStartDay(boolean lastWeek) {
        Book book = book();
        if (book == null || book.week < 0)
            return 0;
        return (lastWeek ? book.week - 1 : book.week) * 7;
    }

    // ------------------------------------------------------------------ plumbing

    private static Book book() {
        try {
            AdventurePlayer player = AdventurePlayer.current();
            return player == null ? null : player.getLedger();
        } catch (RuntimeException e) {
            return null; // no world yet
        }
    }

    private static int currentWeek() {
        try {
            WorldSave save = WorldSave.getCurrentSave();
            if (save == null || save.getWorld() == null)
                return -1;
            return save.getWorld().getCurrentDay() / 7;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ persistence

    public static void reset(Book book) {
        book.week = -1;
        book.hasLast = false;
        java.util.Arrays.fill(book.thisIn, 0);
        java.util.Arrays.fill(book.thisOut, 0);
        java.util.Arrays.fill(book.lastIn, 0);
        java.util.Arrays.fill(book.lastOut, 0);
    }

    /** Four comma-joined rows of ints rather than stored arrays: a String cannot change shape when
     *  a bucket is added later, and it stays readable in a save dump. */
    public static void save(SaveFileData data, Book book) {
        data.store("ledgerWeek", book.week);
        data.store("ledgerHasLast", book.hasLast);
        data.store("ledgerThisIn", join(book.thisIn));
        data.store("ledgerThisOut", join(book.thisOut));
        data.store("ledgerLastIn", join(book.lastIn));
        data.store("ledgerLastOut", join(book.lastOut));
    }

    public static void load(SaveFileData data, Book book) {
        reset(book);
        if (!data.containsKey("ledgerWeek"))
            return; // save predates the ledger - it starts tallying from the next movement
        book.week = data.readInt("ledgerWeek");
        book.hasLast = data.containsKey("ledgerHasLast") && data.readBool("ledgerHasLast");
        split(data, "ledgerThisIn", book.thisIn);
        split(data, "ledgerThisOut", book.thisOut);
        split(data, "ledgerLastIn", book.lastIn);
        split(data, "ledgerLastOut", book.lastOut);
    }

    private static String join(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0)
                sb.append(',');
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private static void split(SaveFileData data, String key, int[] into) {
        if (!data.containsKey(key))
            return;
        String[] parts = data.readString(key).split(",");
        for (int i = 0; i < into.length && i < parts.length; i++) {
            try {
                into[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
                into[i] = 0;
            }
        }
    }
}
