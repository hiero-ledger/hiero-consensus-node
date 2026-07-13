// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.actions;

/**
 * An action that is triggered when the platform starts replaying events from the preconsensus event stream.
 */
public record StartedReplayingEventsAction() implements PlatformStatusAction {}
