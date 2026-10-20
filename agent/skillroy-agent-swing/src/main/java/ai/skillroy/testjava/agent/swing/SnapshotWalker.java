/*
 * Copyright 2026 Mike Mills.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.skillroy.testjava.agent.swing;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the Node tree of {@code swing.snapshot} in pre-order with two limits: {@code maxDepth} levels below each root
 * and {@code maxNodes} nodes in total (roots included). Hitting either sets {@link #truncated()}. Children that are
 * not on screen (not showing, or collapsed: no effective visible area, Phase 8, phase8-contracts.md 3.3) are left out
 * unless {@code includeHidden}. A node's {@code children} key is present only when it has included children.
 *
 * <p>Must be used on the EDT. One instance per snapshot.
 */
final class SnapshotWalker {

    private final ComponentView view;
    private final int maxDepth;
    private final int maxNodes;
    private final boolean includeHidden;
    private int count;
    private boolean truncated;

    SnapshotWalker(ComponentView view, int maxDepth, int maxNodes, boolean includeHidden) {
        this.view = view;
        this.maxDepth = maxDepth;
        this.maxNodes = maxNodes;
        this.includeHidden = includeHidden;
    }

    /** The node for a root and its subtree, or null if the node limit was already reached. */
    Map<String, Object> walk(Component root) {
        return walk(root, 0);
    }

    boolean truncated() {
        return truncated;
    }

    private Map<String, Object> walk(Component c, int depth) {
        if (count >= maxNodes) {
            truncated = true;
            return null;
        }
        count++;
        Map<String, Object> node = view.node(c);
        if (c instanceof Container) {
            List<Object> children = new ArrayList<Object>();
            for (Component child : ((Container) c).getComponents()) {
                if (!includeHidden && !view.onScreen(child)) {
                    continue;
                }
                if (depth >= maxDepth) {
                    truncated = true;
                    break;
                }
                Map<String, Object> childNode = walk(child, depth + 1);
                if (childNode == null) {
                    break;
                }
                children.add(childNode);
            }
            if (!children.isEmpty()) {
                node.put("children", children);
            }
        }
        return node;
    }
}
