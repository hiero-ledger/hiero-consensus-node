// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.triggers;

/**
 * A trigger fired when the platform falls behind.
 */
public record FallenBehindTrigger() implements StatusMachineTrigger {}
