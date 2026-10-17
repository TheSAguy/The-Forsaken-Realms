/**
 * Prototype C slider panel. Plain data. Terrain sliders are prototype A's; the rest drive the set-territory model.
 * Every length is expressed for a 700-tile map and scaled by sc = size / 700 inside the generator.
 */
final class Settings {
    String name = "Balanced (default)";
    String key = "balanced";
    long seed = 2026;
    int size = 700;               // map is size x size tiles
    // ---- terrain (prototype A) ----
    int continents = 3;           // 1..6
    float islands = 0.45f;        // 0..1
    float water = 0.55f;          // 0.30..0.80 exact ocean share (sea level is a percentile)
    float rivers = 0.5f;          // 0..1
    float age = 0.5f;             // 0..1 young .. old
    float mountains = 0.5f;       // 0..1
    float temperature = 0f;       // -1..1
    float latitudeSpan = 0.85f;   // 0..1
    float moisture = 0.5f;        // 0..1
    // ---- set territories ----
    boolean foldReprints = false; // true: Alchemy -> parent, plane remasters -> parent, generic masters ride on core sets (189 -> 146)
    float coreWeight = 2.0f;      // area weight of a core-set territory (others 1.0)
    float coreFrac = 0.55f;       // themed core blob share of each territory (0.5-0.6)
    float cohesion = 0.5f;        // 0 = sets scattered at random (climate-informed) .. 1 = a plane's sets clustered in one realm
    boolean ubIsles = true;       // Universes Beyond / space planes get their own moated isles (prototype B)
    // ---- AI start layer ----
    int aiCount = 5;              // 2..8 AI capitals, each on ONE core-set territory
    // ---- player ----
    boolean playerArea = true;    // (a) reserve a wild 6th area of core size; false = (b) carve-out, nothing reserved
    // ---- harbors and ferries ----
    float harbors = 0.5f;         // 0..1 extra docks beyond the one-per-landmass minimum
    float ferryExtras = 1.0f;     // 0..1 share of eligible Urquhart shortcut lanes kept

    Settings copy() {
        Settings o = new Settings();
        o.name = name; o.key = key; o.seed = seed; o.size = size; o.continents = continents; o.islands = islands; o.water = water;
        o.rivers = rivers; o.age = age; o.mountains = mountains; o.temperature = temperature; o.latitudeSpan = latitudeSpan;
        o.moisture = moisture; o.foldReprints = foldReprints; o.coreWeight = coreWeight; o.coreFrac = coreFrac;
        o.cohesion = cohesion; o.ubIsles = ubIsles; o.aiCount = aiCount; o.playerArea = playerArea; o.harbors = harbors;
        o.ferryExtras = ferryExtras;
        return o;
    }

    static Settings preset(String key) {
        Settings s = new Settings(); s.key = key;
        switch (key) {
            case "pangaea":
                s.name = "Pangaea"; s.continents = 1; s.islands = 0.2f; s.water = 0.45f; s.age = 0.3f;
                s.mountains = 0.7f; s.rivers = 0.45f; s.moisture = 0.45f; s.seed = 7; break;
            case "archipelago":
                s.name = "Archipelago"; s.continents = 6; s.islands = 1f; s.water = 0.67f; s.age = 0.8f;
                s.mountains = 0.35f; s.rivers = 0.45f; s.moisture = 0.6f; s.seed = 31; break;
            default: break;
        }
        return s;
    }

    String sliders() {
        return String.format(java.util.Locale.US,
            "size=%d seed=%d continents=%d islands=%.2f water=%.2f rivers=%.2f age=%.2f mountains=%.2f temperature=%.2f latitudeSpan=%.2f moisture=%.2f "
            + "foldReprints=%b coreWeight=%.1f coreFrac=%.2f cohesion=%.2f ubIsles=%b aiCount=%d playerArea=%b harbors=%.2f ferryExtras=%.2f",
            size, seed, continents, islands, water, rivers, age, mountains, temperature, latitudeSpan, moisture,
            foldReprints, coreWeight, coreFrac, cohesion, ubIsles, aiCount, playerArea, harbors, ferryExtras);
    }
}
