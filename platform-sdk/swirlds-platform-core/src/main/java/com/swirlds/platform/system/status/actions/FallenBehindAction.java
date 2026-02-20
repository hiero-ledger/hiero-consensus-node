// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.status.actions;

import org.hiero.consensus.model.status.PlatformStatusAction;

/**
 * An action that is triggered when the platform falls behind.
 */
public record FallenBehindAction() implements PlatformStatusAction {}
