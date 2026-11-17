package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class ArrivalTitleTest {
    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }

    @Test
    void descriptionBecomesTheSubtitle() {
        WorldSettings farm = new WorldSettings("farm");
        farm.description = "Fields, <green>animals</green> and quiet";
        assertEquals("Fields, animals and quiet", plain(PlayerRules.subtitle(farm)));
    }

    @Test
    void noDescriptionNoSubtitle() {
        WorldSettings empty = new WorldSettings("void");
        empty.description = "  ";
        assertEquals(Component.empty(), PlayerRules.subtitle(empty));
    }

    @Test
    void theTitleUsesTheDisplayName() {
        WorldSettings farm = new WorldSettings("farm");
        farm.displayName = "<yellow>Farm World";
        assertEquals("Farm World", plain(Menus.title(farm)));
    }
}
