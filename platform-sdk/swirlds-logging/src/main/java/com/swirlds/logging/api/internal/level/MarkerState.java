// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.level;

/**
 * Enumeration representing marker decision.
 * <p>
 * This enumeration defines different options for specifying the state of a marker. It allows you to explicitly indicate whether
 * a marker is enabled, disabled, or should inherit its state from the package configuration.
 * <p>
 * The {@code ENABLED} option signifies that the marker is explicitly enabled.
 * The {@code DISABLED} option indicates that the marker is explicitly disabled.
 * The {@code UNDEFINED} option implies that the marker state should be inherited from the package configuration.
 */
public enum MarkerState {
    ENABLED,
    DISABLED,
    UNDEFINED;
}
