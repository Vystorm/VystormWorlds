package at.esoren.vystormworlds;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FeaturesTest {
    private static final String[] SWITCHES = {Features.RESETS, Features.BORDER_EVENTS, Features.TEMPLATES, Features.BUILD_HEIGHT};

    @Test
    void givenPackagedConfig_whenRead_thenEveryFeatureSwitchIsABooleanAndOn() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            config.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (String key : SWITCHES) {
            assertTrue(key.endsWith(".enabled"), key);
            assertTrue(config.isBoolean(key), key + " missing in config.yml");
            assertTrue(Features.on(config, key), key);
        }
    }

    @Test
    void givenOlderConfigWithoutSwitches_whenAsked_thenEveryFeatureStaysOn() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("reset.keep-backups", 3);
        for (String key : SWITCHES) assertTrue(Features.on(config, key), key);
    }

    @Test
    void givenResetsOff_whenScheduleIsChanged_thenBlockedButSameValueAndOtherPropertiesPass() {
        YamlConfiguration config = new YamlConfiguration();
        config.set(Features.RESETS, false);
        WorldSettings w = new WorldSettings("farm");
        assertFalse(Features.on(config, Features.RESETS));
        assertTrue(Features.blocks(config, w, "reset", "daily 04:00"));
        assertTrue(Features.blocks(config, w, "reset-seed", "random"));
        assertFalse(Features.blocks(config, w, "reset", "off"), "saving the unchanged value is not a change");
        assertFalse(Features.blocks(config, w, "reset-seed", "same"));
        assertFalse(Features.blocks(config, w, "border-event", "daily 20:00"), "border events have their own switch");
        assertFalse(Features.blocks(config, w, "pvp", "false"));
        assertEquals("off", w.reset, "checking never changes the world");
    }

    @Test
    void givenBorderEventsOff_whenEventScheduleIsChanged_thenBlockedButBorderSizeIsNot() {
        YamlConfiguration config = new YamlConfiguration();
        config.set(Features.BORDER_EVENTS, false);
        WorldSettings w = new WorldSettings("arena");
        assertTrue(Features.blocks(config, w, "border-event", "daily 20:00"));
        assertFalse(Features.blocks(config, w, "border-event", "off"));
        assertFalse(Features.blocks(config, w, "border", "2000"));
        assertFalse(Features.blocks(config, w, "reset", "daily 04:00"));
    }

    @Test
    void givenSwitchTurnedBackOn_whenAsked_thenChangesAreAllowedAgain() {
        YamlConfiguration config = new YamlConfiguration();
        config.set(Features.RESETS, false);
        WorldSettings w = new WorldSettings("farm");
        assertTrue(Features.blocks(config, w, "reset", "daily 04:00"));
        config.set(Features.RESETS, true);
        assertFalse(Features.blocks(config, w, "reset", "daily 04:00"));
    }
}
