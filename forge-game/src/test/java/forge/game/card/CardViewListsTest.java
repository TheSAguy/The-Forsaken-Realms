package forge.game.card;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.trackable.TrackableCollection;
import forge.trackable.TrackableProperty;
import forge.trackable.Tracker;
import forge.util.collect.FCollection;
import forge.util.collect.FCollectionView;

/**
 * Round 919: six card lists of a CardView are read on the render thread (VCardDisplayArea's linked-exile ghosts, the
 * card-detail text) while the game thread changes them in place. These reads are the ones the screens make: a loop, and
 * the detail text's append, whose toString walks the backing ArrayList and is fail-fast. About 2 seconds of real race.
 * Round 923: three more. Only the merged cards change in place today; the other two are replaced whole, so the race
 * below is stricter than the game for those, and holds if that ever changes.
 */
public class CardViewListsTest {

    private static final TrackableProperty[] LISTS = {TrackableProperty.UntilLeavesBattlefield,
            TrackableProperty.EncodedCards, TrackableProperty.GainControlTargets, TrackableProperty.ImprintedCards,
            TrackableProperty.ExiledCards, TrackableProperty.HauntedBy, TrackableProperty.MustBlockCards,
            TrackableProperty.MergedCardsCollection, TrackableProperty.ChosenCards};

    private static FCollectionView<?>[] readAll(final CardView card) {
        return new FCollectionView<?>[] {card.getUntilLeavesBattlefield(), card.getEncodedCards(),
                card.getGainControlTargets(), card.getImprintedCards(), card.getExiledCards(), card.getHauntedBy(),
                card.getMustBlockCards(), card.getMergedCardsCollection(), card.getChosenCards()};
    }

    @Test
    public void testCardListsReadWhileTheGameThreadChangesThem() throws InterruptedException {
        final Tracker tracker = new Tracker();
        final CardView host = new CardView(1, tracker);
        // a negative id makes CardView.toString the plain name (no zone or translation lookup)
        final CardView[] cards = new CardView[4];
        for (int i = 0; i < cards.length; i++) {
            cards[i] = new CardView(-100 - i, tracker, "card" + i);
        }
        @SuppressWarnings("unchecked")
        final TrackableCollection<CardView>[] lists = new TrackableCollection[LISTS.length];
        for (int k = 0; k < LISTS.length; k++) {
            lists[k] = new TrackableCollection<>();
            for (final CardView c : cards) {
                lists[k].add(c);
            }
            host.set(LISTS[k], lists[k]);
        }

        final AtomicBoolean stop = new AtomicBoolean();
        final Thread gameThread = new Thread(() -> {
            int i = 0;
            while (!stop.get()) {
                final CardView c = cards[i % cards.length];
                final TrackableCollection<CardView> list = lists[i++ % lists.length];
                list.remove(c); // TrackableObject.removeCard
                list.add(c); // TrackableObject.addCard
            }
        }, "game");
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<String> sink = new AtomicReference<>();
        gameThread.start();
        try {
            final long end = System.nanoTime() + 2_000_000_000L;
            while (System.nanoTime() < end && failure.get() == null) {
                try {
                    for (final FCollectionView<?> read : readAll(host)) {
                        for (final Object c : read) { // VCardDisplayArea.addLinkedExileGhosts
                            ((CardView) c).getId();
                        }
                    }
                    for (final FCollectionView<?> read : readAll(host)) {
                        sink.set(read.toString()); // CardDetailUtil's append
                    }
                } catch (final Throwable t) {
                    failure.set(t);
                }
            }
        } finally {
            stop.set(true);
            gameThread.join();
        }
        if (failure.get() != null) {
            throw new AssertionError("reading the card lists failed: " + failure.get(), failure.get());
        }
    }

    @Test
    public void testEmptyListsAllocateNothing() {
        final CardView card = new CardView(1, new Tracker());
        // never set: a card with none of these has no collection at all
        for (final FCollectionView<?> read : readAll(card)) {
            Assert.assertSame(read, FCollection.getEmpty());
        }
        // set and emptied: isEmpty answers before anything is copied
        for (final TrackableProperty key : LISTS) {
            card.set(key, new TrackableCollection<CardView>());
        }
        for (final FCollectionView<?> read : readAll(card)) {
            Assert.assertSame(read, FCollection.getEmpty());
        }
    }

    @Test
    public void testListsAreCopies() {
        final Tracker tracker = new Tracker();
        final CardView host = new CardView(1, tracker);
        final CardView first = new CardView(2, tracker);
        final CardView second = new CardView(3, tracker);
        final CardView third = new CardView(4, tracker);
        for (final TrackableProperty key : LISTS) {
            final TrackableCollection<CardView> live = new TrackableCollection<>();
            live.add(first);
            live.add(second);
            host.set(key, live);
        }

        final FCollectionView<?>[] before = readAll(host);
        for (final TrackableProperty key : LISTS) {
            final TrackableCollection<CardView> live = new TrackableCollection<>(first);
            live.add(second);
            live.add(third);
            host.set(key, live);
        }
        for (final FCollectionView<?> read : before) {
            Assert.assertEquals(read.size(), 2);
            Assert.assertEquals(read.get(0), first);
            Assert.assertEquals(read.get(1), second);
        }
        for (final FCollectionView<?> read : readAll(host)) {
            Assert.assertEquals(read.size(), 3);
            Assert.assertEquals(read.get(2), third);
        }
    }
}
