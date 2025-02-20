// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.status.actions;

/**
 * An action that is triggered when the platform starts replaying events from the preconsensus event stream.
 */
public record StartedReplayingEventsAction() implements PlatformStatusAction {}
