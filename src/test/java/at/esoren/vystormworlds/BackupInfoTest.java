package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code /worlds info} names the backups of a world and their size, so an admin sees what fills the disk. */
class BackupInfoTest {
    @TempDir Path folder;

    @Test
    void countsOnlyThisWorldsBackupsAndAddsUpTheirSize() throws Exception {
        Path a = Files.createDirectories(folder.resolve("farm-regenerated-2026-11-01_040000-1111").resolve("region"));
        Files.write(a.resolve("r.0.0.mca"), new byte[3000]);
        Path b = Files.createDirectories(folder.resolve("farm-regenerated-2026-11-02_040000-2222"));
        Files.write(b.resolve("level.dat"), new byte[1000]);
        Path c = Files.createDirectories(folder.resolve("farm-deleted-2026-11-03_040000-3333").resolve("region"));
        Files.write(c.resolve("r.0.0.mca"), new byte[2 * 1024 * 1024]);
        Files.write(Files.createDirectories(folder.resolve("farmland-regenerated-2026-11-01_040000-1111")).resolve("x"), new byte[500]);
        Files.write(Files.createDirectories(folder.resolve("other-deleted-2026-11-01_040000-1111")).resolve("x"), new byte[500]);

        WorldResets.Backups backups = WorldResets.backups(folder, "farm");
        assertEquals(2, backups.regenerated());
        assertEquals(1, backups.deleted());
        assertEquals(3, backups.count());
        assertEquals(4000 + 2 * 1024 * 1024, backups.bytes());
        assertEquals("2.0 MB", backups.size());
    }

    @Test
    void noFolderMeansNoBackups() {
        assertEquals(0, WorldResets.backups(folder.resolve("missing"), "farm").count());
        assertEquals("1 KB", new WorldResets.Backups(1, 0, 10).size());
        assertEquals("1.5 GB", new WorldResets.Backups(1, 0, 1536L * 1024 * 1024).size());
    }

    @Test
    void backupLineExistsInAllSevenLanguagesWithTheSamePlaceholders() {
        for (String lang : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            String line = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + lang + ".yml")).getString("info-backups", "");
            for (String placeholder : List.of("{regenerated}", "{deleted}", "{keep}", "{size}"))
                assertTrue(line.contains(placeholder), lang + " info-backups lacks " + placeholder + ": " + line);
        }
    }
}
