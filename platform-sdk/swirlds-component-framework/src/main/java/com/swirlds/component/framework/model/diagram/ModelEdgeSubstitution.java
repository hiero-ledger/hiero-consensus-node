// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.diagram;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Describes an edge substitution. A substituted edge is not drawn on the diagram, and is instead noted using a label.
 * Useful for situations where a component is connected with a large number of other components (thus making the diagram
 * hard to read).
 *
 * @param source       the name of the scheduler that produces the output wire corresponding to the edge we are
 *                     attempting to substitute (NOT the group name, if grouped)
 * @param edge         the label on the edge(s) to be substituted
 * @param substitution the substitute label
 */
public record ModelEdgeSubstitution(@NonNull String source, @NonNull String edge, @NonNull String substitution) {}
