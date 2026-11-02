package forge.game;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.card.CardView;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableProperty;
import forge.trackable.Tracker;

/**
 * Round 919: the match screens read a card's attachments on the render thread (VField.tryStackCard, the card panels,
 * the arrows) while the game thread attaches and detaches cards in the view's own collection. A stream over that
 * collection threw ConcurrentModificationException, which closes the game; 10x and 50x spectating make it likelier.
 * The race is run for real for about 2 seconds.
 */
public class GameEntityViewTest {

    @Test
    public void testAttachmentsReadWhileTheGameThreadChangesThem() throws InterruptedException {
        final Tracker tracker = new Tracker();
        final CardView host = new CardView(1, tracker);
        final CardView[] auras = new CardView[4];
        final TrackableCollection<CardView> attached = new TrackableCollection<>();
        for (int i = 0; i < auras.length; i++) {
            auras[i] = new CardView(100 + i, tracker);
            attached.add(auras[i]);
        }
        host.set(TrackableProperty.AttachedCards, attached);

        final AtomicBoolean stop = new AtomicBoolean();
        final Thread gameThread = new Thread(() -> {
            int i = 0;
            while (!stop.get()) {
                final CardView aura = auras[i++ % auras.length];
                attached.remove(aura); // TrackableObject.removeCard
                attached.add(aura); // TrackableObject.addCard
            }
        }, "game");
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        gameThread.start();
        try {
            final long end = System.nanoTime() + 2_000_000_000L;
            while (System.nanoTime() < end && failure.get() == null) {
                try {
                    host.hasCardAttachments(); // VField.tryStackCard
                    for (final CardView aura : host.getAttachedCards()) { // TargetingOverlay
                        aura.getId();
                    }
                    for (final CardView aura : host.getAllAttachedCards()) { // VCardDisplayArea.updateCard
                        aura.getId();
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
            throw new AssertionError("reading the attachments failed: " + failure.get(), failure.get());
        }
    }

    @Test
    public void testNoAttachmentsAllocateNothing() {
        final CardView card = new CardView(1, new Tracker());
        // never set: a card with nothing attached has no collection at all
        Assert.assertSame(card.getAllAttachedCards(), List.of());
        Assert.assertSame(card.getAttachedCards(), List.of());
        // set and emptied: isEmpty answers before anything is copied
        card.set(TrackableProperty.AttachedCards, new TrackableCollection<CardView>());
        Assert.assertSame(card.getAllAttachedCards(), List.of());
        Assert.assertSame(card.getAttachedCards(), List.of());
        Assert.assertFalse(card.hasCardAttachments());
    }

    @Test
    public void testAttachmentsAreACopy() {
        final Tracker tracker = new Tracker();
        final CardView host = new CardView(1, tracker);
        final CardView aura = new CardView(2, tracker);
        final TrackableCollection<CardView> attached = new TrackableCollection<>(aura);
        host.set(TrackableProperty.AttachedCards, attached);

        final List<CardView> read = host.getAllAttachedCards();
        attached.add(new CardView(3, tracker));
        Assert.assertEquals(read, List.of(aura));
        Assert.assertEquals(host.getAllAttachedCards().size(), 2);
        Assert.assertTrue(host.hasCardAttachments());
    }

    @Test
    public void testPhasedOutAttachmentsAreLeftOutOfTheShownList() {
        final Tracker tracker = new Tracker();
        final CardView host = new CardView(1, tracker);
        final CardView shown = new CardView(2, tracker);
        final CardView phased = new CardView(3, tracker);
        phased.set(TrackableProperty.PhasedOut, true);
        final TrackableCollection<CardView> attached = new TrackableCollection<>();
        attached.add(shown);
        attached.add(phased);
        host.set(TrackableProperty.AttachedCards, attached);

        Assert.assertEquals(host.getAttachedCards(), List.of(shown));
        Assert.assertEquals(host.getAllAttachedCards(), List.of(shown, phased));
    }
}
