package forge.trackable;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.card.CardView;

/**
 * TFR round 997 (notes/logs-1108.md P3-10): a duel with start-in-play cards died at its start with a
 * ConcurrentModificationException in Tracker.unfreeze - a change was queued while the queued changes were being applied.
 * Here a nested freeze, queue and unfreeze runs from inside the replay (the first change's object lookup); the old loop
 * threw on its next step.
 */
public class TrackerTest {
    @Test
    public void testAChangeQueuedDuringTheReplayIsApplied() {
        final CardView[] late = new CardView[1];
        final boolean[] armed = {false};
        final Tracker tracker = new Tracker() {
            @Override
            public <T> void putObj(TrackableTypes.TrackableType<T> type, Integer id, T val) {
                super.putObj(type, id, val);
                if (armed[0]) {
                    armed[0] = false;
                    freeze();
                    addDelayedPropChange(late[0], TrackableProperty.Name, "Late");
                    unfreeze();
                }
            }
        };
        final CardView host = new CardView(1, tracker);
        final CardView origin = new CardView(2, tracker);
        final CardView other = new CardView(3, tracker);
        late[0] = new CardView(4, tracker);

        tracker.freeze();
        tracker.addDelayedPropChange(host, TrackableProperty.CloneOrigin, origin);
        tracker.addDelayedPropChange(other, TrackableProperty.Name, "Other");
        armed[0] = true;
        tracker.unfreeze();

        Assert.assertFalse(tracker.isFrozen());
        Assert.assertSame(host.getCloneOrigin(), origin);
        Assert.assertEquals(other.getName(), "Other");
        Assert.assertEquals(late[0].getName(), "Late");
        Assert.assertTrue(tracker.getDelayedPropsFor(late[0]).isEmpty());
    }

    @Test
    public void testQueuedChangesWaitForTheLastUnfreeze() {
        final Tracker tracker = new Tracker();
        final CardView card = new CardView(1, tracker);
        tracker.freeze();
        tracker.freeze();
        tracker.addDelayedPropChange(card, TrackableProperty.Name, "Queued");
        tracker.unfreeze();
        Assert.assertNotEquals(card.getName(), "Queued");
        tracker.unfreeze();
        Assert.assertEquals(card.getName(), "Queued");
    }
}
