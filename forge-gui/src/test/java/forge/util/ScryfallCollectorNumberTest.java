package forge.util;

import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TFR round 977 (notes/alchemy-art-404.md): Scryfall no longer lists Alchemy rebalanced ("A-") printings, so an A-
 * card is fetched as its base print, the same set and number with "A-" and the letter suffix dropped.
 */
public class ScryfallCollectorNumberTest {

    @Test
    public void rebalancedNumberWithLetterMapsToBaseNumber() {
        assertThat(ImageUtil.scryfallCollectorNumber("A-150e")).isEqualTo("150");
    }

    @Test
    public void rebalancedNumberMapsToBaseNumber() {
        assertThat(ImageUtil.scryfallCollectorNumber("A-85")).isEqualTo("85");
    }

    @Test
    public void ordinaryNumbersAreUnchanged() {
        assertThat(ImageUtil.scryfallCollectorNumber("150")).isEqualTo("150");
        assertThat(ImageUtil.scryfallCollectorNumber("407s")).isEqualTo("407s");
        assertThat(ImageUtil.scryfallCollectorNumber("A")).isEqualTo("A");
    }

    @Test
    public void funnyAndPlanechaseNumbersAreUnchanged() {
        assertThat(ImageUtil.scryfallCollectorNumber("F12")).isEqualTo("F12");
        assertThat(ImageUtil.scryfallCollectorNumber("OHOP7")).isEqualTo("OHOP7");
    }
}
