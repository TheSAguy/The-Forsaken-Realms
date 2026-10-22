package forge.adventure.stage;

public interface IAfterMatch {
    void setWinner(boolean winner, boolean isArena);

    /**
     * Round 708: a drawn duel - nothing won or lost (DuelScene skips the ante, the record and the streaks). The world map
     * and the maps handle it themselves; a scene without its own handling (the Arena bracket, an Inn tournament, which
     * both need a winner to go on) still scores it as a loss, as before.
     */
    default void setDrawn(boolean isArena) {
        System.out.println("[TFR-Draw] " + getClass().getSimpleName() + " has no draw handling - scored as a loss");
        setWinner(false, isArena);
    }
}
