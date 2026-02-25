// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.report;

import org.hiero.base.crypto.Hash;

/**
 * The running hash of the event stream at the end of a round
 *
 * @param round
 * 		the round in question
 * @param runningHash
 * 		the hash
 */
public record RoundRunningHash(long round, Hash runningHash) {}
