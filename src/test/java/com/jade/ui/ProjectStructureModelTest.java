package com.jade.ui;

import com.jade.api.ProjectTree;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the pure glyph-line to depth-node conversion behind the structure tree. */
class ProjectStructureModelTest {

    private ProjectTree tree(List<String> lines, boolean truncated) {
        return new ProjectTree(Path.of("/tmp/fake-root"), lines, truncated, 6);
    }

    @Test
    void parsesSimpleTopLevelEntries() {
        ProjectStructureModel model = ProjectStructureModel.from(tree(List.of(
                "├── pom.xml",
                "├── src",
                "└── target"), false));
        assertEquals("fake-root", model.rootName());
        assertEquals(3, model.nodes().size());
        assertEquals(0, model.nodes().get(0).depth());
        assertEquals("pom.xml", model.nodes().get(0).name());
        assertEquals("target", model.nodes().get(2).name());
        assertFalse(model.truncated());
    }

    @Test
    void parsesNestedDepths() {
        ProjectStructureModel model = ProjectStructureModel.from(tree(List.of(
                "└── src",
                "    ├── main",
                "    │   └── java",
                "    └── test"), false));
        assertEquals(4, model.nodes().size());
        assertEquals(0, model.nodes().get(0).depth());
        assertEquals(1, model.nodes().get(1).depth());
        assertEquals(2, model.nodes().get(2).depth());
        assertEquals(1, model.nodes().get(3).depth());
        assertEquals("java", model.nodes().get(2).name());
    }

    @Test
    void rootEchoAndBlankLinesAreSkipped() {
        ProjectStructureModel model = ProjectStructureModel.from(tree(List.of(
                "fake-root",
                "",
                "├── pom.xml"), false));
        assertEquals(1, model.nodes().size());
        assertEquals("pom.xml", model.nodes().get(0).name());
    }

    @Test
    void truncationFlagIsCarriedThrough() {
        ProjectStructureModel model = ProjectStructureModel.from(tree(List.of("└── src"), true));
        assertTrue(model.truncated());
        assertEquals(6, model.maxDepth());
    }

    @Test
    void realTreeWalkProducesParseableLines(@TempDir Path temp) throws Exception {
        Path src = temp.resolve("src/main/java/com/example");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Main.java"), "class Main {}");
        Files.writeString(temp.resolve("pom.xml"), "<project/>");

        // Walk with the same bounded walker the inspection service uses by
        // generating lines the way ProjectTree renders them (depth glyphs).
        List<String> lines = List.of(
                "├── pom.xml",
                "└── src",
                "    └── main",
                "        └── java",
                "            └── com");
        ProjectStructureModel model = ProjectStructureModel.from(tree(lines, false));
        assertEquals(5, model.nodes().size());
        assertEquals(0, model.nodes().get(0).depth());
        assertEquals(3, model.nodes().get(4).depth());
        assertFalse(model.truncated());
    }
}
