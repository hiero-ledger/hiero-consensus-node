// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.logging;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;

/**
 * Utility class for determining the color of node ID text
 */
public class NodeIdColorizer {
    /**
     * Hidden constructor
     */
    private NodeIdColorizer() {}

    /**
     * Only define 24 colors, each separated by minimum of 15 degrees on the color wheel
     * <p>
     * Further specification isn't very helpful, since less than 15 degrees is too close to be easily distinguished
     */
    public static final List<String> nodeIdColors = List.of(
            "#6FD154", // 0°
            "#54AED1", // 90°
            "#B654D1", // 180°
            "#D17854", // 270°
            "#54D197", // 45°
            "#5854D1", // 135°
            "#D1548E", // 225°
            "#CDD154", // 315°
            "#54D158", // 15°
            "#548ED1", // 105°
            "#D154CD", // 195°
            "#D19754", // 285°
            "#54D1B6", // 60°
            "#7754D1", // 150°
            "#D1546F", // 240°
            "#AED154", // 330°
            "#54D178", // 30°
            "#546ED1", // 120°
            "#D154AD", // 210°
            "#D1B754", // 300°
            "#54CDD1", // 75°
            "#9654D1", // 165°,
            "#D15854", // 255°
            "#8ED154" // 345°
            );

    /**
     * Get the color for a node id
     * <p>
     * If the node ID cannot be used an index for the color list, then null is returned
     *
     * @param nodeId the node id
     * @return the color, or null if no color is defined for the node id
     */
    @Nullable
    public static String getNodeIdColor(@NonNull final NodeId nodeId) {
        if (nodeId.id() < nodeIdColors.size()) {
            return nodeIdColors.get((int) nodeId.id());
        } else {
            return null;
        }
    }
}
