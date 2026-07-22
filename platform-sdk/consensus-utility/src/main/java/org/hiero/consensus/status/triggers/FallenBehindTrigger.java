// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.triggers;

/**
 * A trigger to indicate that the platform fell behind.
 */
public record FallenBehindTrigger() implements StatusMachineTrigger {}
