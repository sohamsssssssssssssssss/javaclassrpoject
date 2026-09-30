package com.jade.ui;

import com.jade.api.ProjectTree;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure model behind the Project tab's structure tree. It converts the bounded
 * glyph lines already collected by {@link ProjectTree} (e.g. {@code ├── src},
 * {@code │   └── main}) into depth-annotated node entries that a JavaFX
 * {@code TreeView} can nest. It never touches the filesystem: the tree it
 * describes is exactly the one the inspection service walked, with the same
 * truncation truthfulness.
 */
public final class ProjectStructureModel {

    /** One display entry: nesting depth (root's children are depth 0) and its name. */
    public record Node(int depth, String name) {
        public Node {
            Objects.requireNonNull(name, "name");
        }
    }

    private final String rootName;
    private final List<Node> nodes;
    private final boolean truncated;
    private final int maxDepth;

    private ProjectStructureModel(String rootName, List<Node> nodes, boolean truncated, int maxDepth) {
        this.rootName = rootName;
        this.nodes = List.copyOf(nodes);
        this.truncated = truncated;
        this.maxDepth = maxDepth;
    }

    /**
     * Builds the model from the bounded tree. Lines are the exact strings
     * produced by {@link ProjectTree#lines()}; shapes that do not carry a
     * connector glyph (for example a blank line or the root echo) are skipped
     * rather than guessed at. Each nesting level consumes exactly four
     * characters ("│   " / "    " for ancestors plus "├── "/"└── " for the
     * entry itself), so depth is simply the number of consumed levels.
     */
    public static ProjectStructureModel from(ProjectTree tree) {
        Objects.requireNonNull(tree, "tree");
        Path root = tree.root();
        String rootName = root == null ? "project" : root.getFileName().toString();

        List<Node> nodes = new ArrayList<>();
        for (String line : tree.lines()) {
            if (line == null || line.isBlank()) {
                continue;
            }
            int index = 0;
            while (line.startsWith("│   ", index) || line.startsWith("    ", index)) {
                index += 4;
            }
            if (line.startsWith("├── ", index) || line.startsWith("└── ", index)) {
                String name = line.substring(index + 4).strip();
                if (!name.isEmpty()) {
                    nodes.add(new Node(index / 4, name));
                }
            }
            // Anything else (root echo, unknown shape) is skipped honestly.
        }
        return new ProjectStructureModel(rootName, nodes, tree.truncated(), tree.maxDepth());
    }

    public String rootName() {
        return rootName;
    }

    public List<Node> nodes() {
        return nodes;
    }

    public boolean truncated() {
        return truncated;
    }

    public int maxDepth() {
        return maxDepth;
    }
}
