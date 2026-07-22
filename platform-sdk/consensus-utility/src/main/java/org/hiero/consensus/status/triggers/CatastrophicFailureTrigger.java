// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.triggers;

/**
 * A trigger to indicate that the platform experiences a catastrophic failure.
 */
public record CatastrophicFailureTrigger() implements StatusMachineTrigger {}
