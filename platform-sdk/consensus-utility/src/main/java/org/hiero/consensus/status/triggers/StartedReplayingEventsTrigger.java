// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.triggers;

/**
 * A trigger fired when the platform starts replaying events from the preconsensus event stream.
 */
public record StartedReplayingEventsTrigger() implements StatusMachineTrigger {}
