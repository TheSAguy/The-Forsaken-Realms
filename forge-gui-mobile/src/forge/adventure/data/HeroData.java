package forge.adventure.data;

/**
 * Data class that will be used to read Json configuration files
 * BiomeData
 * contains the information possible hero sprite
 */
public class HeroData {
    public String name;
    public String female;
    public String male;
    public String femaleAvatar;
    public String maleAvatar;
    /** Round 591: how big the race's sheets draw - 1 for the stock 16 px heroes; a sheet with more pixels (the Pixelate
     *  Phyrexian, 64 px art) gives the factor that brings its body to hero size. See CharacterSprite.setHeroScale. */
    public float scale = 1f;
}
