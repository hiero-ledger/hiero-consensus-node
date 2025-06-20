// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model.internal.analysis;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Uniquely describes an input wire within a wiring model.
 *
 * <p>
 * This object exists so that standard input wires don't have to implement equals and hash code.
 *
 * @param taskSchedulerName the name of the task scheduler the input wire is bound to
 * @param name              the name of the input wire
 */
public record InputWireDescriptor(@NonNull String taskSchedulerName, @NonNull String name) {}
