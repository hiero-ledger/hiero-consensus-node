// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.diagram;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Describes a manual link between two components. Useful for adding information to the diagram that is not captured by
 * the wiring framework
 *
 * @param source the source scheduler
 * @param label  the label on the edge
 * @param target the target scheduler
 */
public record ModelManualLink(@NonNull String source, @NonNull String label, @NonNull String target) {}
