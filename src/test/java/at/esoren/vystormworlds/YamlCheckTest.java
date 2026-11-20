package at.esoren.vystormworlds;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlCheckTest {
    @TempDir Path dir;

    @Test void givenAMissingOrValidFile_whenChecked_thenThereIsNoProblem() throws Exception {
        assertNull(YamlCheck.problem(dir.resolve("missing.yml").toFile()));
        Path valid = Files.writeString(dir.resolve("valid.yml"), "a: 1\nb:\n  c: true\n");
        assertNull(YamlCheck.problem(valid.toFile()));
    }

    @Test void givenAYamlSyntaxError_whenChecked_thenTheProblemNamesLineAndColumn() throws Exception {
        Path broken = Files.writeString(dir.resolve("config.yml"), "plots:\n  enabled: true\nthis line has no colon\nother: 2\n");
        String problem = YamlCheck.problem(broken.toFile());
        assertNotNull(problem);
        assertEquals("line 4, column 1: could not find expected ':'", problem);
    }

    @Test void givenABrokenFile_whenBackedUp_thenTheCopyHoldsTheSameBytes() throws Exception {
        Path broken = Files.writeString(dir.resolve("alerts.yml"), "a: [\n");
        File copy = YamlCheck.backup(broken.toFile(), null);
        assertTrue(copy.getName().matches("alerts\\.yml\\.broken-\\d{8}-\\d{6}"), copy.getName());
        assertArrayEquals(Files.readAllBytes(broken), Files.readAllBytes(copy.toPath()));
    }
}
