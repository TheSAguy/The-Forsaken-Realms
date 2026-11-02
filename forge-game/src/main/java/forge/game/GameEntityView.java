package forge.game;

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.Multiset;

import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableObject;
import forge.trackable.TrackableProperty;
import forge.trackable.Tracker;

public abstract class GameEntityView extends TrackableObject {
    private static final long serialVersionUID = -5129089945124455670L;

    public static GameEntityView get(GameEntity e) {
        return e == null ? null : e.getView();
    }

    public static TrackableCollection<GameEntityView> getEntityCollection(Iterable<? extends GameEntity> entities) {
        if (entities == null) {
            return null;
        }
        TrackableCollection<GameEntityView> collection = new TrackableCollection<>();
        for (GameEntity e : entities) {
            collection.add(e.getView());
        }
        return collection;
    }

    public static <T extends GameEntity, V extends GameEntityView> GameEntityViewMap<T, V> getMap(Iterable<T> spabs) {
        GameEntityViewMap<T, V> gameViewCache = new GameEntityViewMap<T, V>();
        gameViewCache.putAll(spabs);
        return gameViewCache;
    }

    protected GameEntityView(final int id0, final Tracker tracker) {
        super(id0, tracker);
    }

    @Override
    public String toString() {
        return getName();
    }

    public String getName() {
        return get(TrackableProperty.Name);
    }
    protected void updateName(GameEntity e) {
        set(TrackableProperty.Name, e.getName());
    }

    public int getPreventNextDamage() {
        return get(TrackableProperty.PreventNextDamage);
    }
    public void updatePreventNextDamage(GameEntity e) {
        set(TrackableProperty.PreventNextDamage, e.getPreventNextDamageTotalShields());
    }

    public List<CardView> getAttachedCards() {
        final List<CardView> cards = getAllAttachedCards();
        if (!cards.isEmpty()) {
            cards.removeIf(CardView::isPhasedOut);
        }
        return cards;
    }
    public boolean hasCardAttachments() {
        return !getAttachedCards().isEmpty();
    }
    /**
     * TFR round 919: a copy, never the live collection. The match screens call this on the render thread while the game
     * thread changes the collection in place, and a stream over it threw ConcurrentModificationException, which closes
     * the game. toArray has no fail-fast check; a copy taken mid-change can hold an empty slot, which is left out. No
     * attachments allocate nothing: this runs per card per frame.
     */
    public List<CardView> getAllAttachedCards() {
        final TrackableCollection<CardView> live = get(TrackableProperty.AttachedCards);
        if (live == null || live.isEmpty()) {
            return List.of();
        }
        final Object[] slots = live.toArray();
        final List<CardView> cards = new ArrayList<>(slots.length);
        for (final Object c : slots) {
            if (c != null) {
                cards.add((CardView) c);
            }
        }
        return cards;
    }
    public boolean hasAnyCardAttachments() {
        return getAllAttachedCards().isEmpty();
    }

    public Multiset<CounterType> getCounters() {
        return get(TrackableProperty.Counters);
    }
    public int getCounters(CounterType counterType) {
        final Multiset<CounterType> counters = getCounters();
        if (counters != null) {
            return counters.count(counterType);
        }
        return 0;
    }
    public boolean hasSameCounters(GameEntityView other) {
        Multiset<CounterType> counters = getCounters();
        if (counters == null) {
            return other.getCounters() == null;
        }
        return counters.equals(other.getCounters());
    }

    public void updateCounters(GameEntity e) {
        set(TrackableProperty.Counters, e.getCounters());
        flagAsChanged(TrackableProperty.Counters);
    }
}
