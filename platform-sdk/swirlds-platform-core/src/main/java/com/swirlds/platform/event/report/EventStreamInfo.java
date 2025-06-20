// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.report;

import java.time.Instant;
import org.hiero.consensus.model.event.CesEvent;

/**
 * Information about an event stream.
 *
 * @param start
 * 		the timestamp at the start of the period reported
 * @param end
 * 		the timestamp at the end of the period reported
 * @param eventCount
 * 		the number of events in this period
 * @param applicationTransactionCount
 * 		the number of application transactions in this period
 * @param fileCount
 * 		the number of files in this period
 * @param byteCount
 * 		the byte count of
 * @param firstEvent
 * 		the first event in the time period
 * @param lastEvent
 * 		the last event in the time period
 * @param damagedFileCount
 * 		the number of damaged files
 */
public record EventStreamInfo(
        Instant start,
        Instant end,
        long roundCount,
        long eventCount,
        long applicationTransactionCount,
        long fileCount,
        long byteCount,
        CesEvent firstEvent,
        CesEvent lastEvent,
        long damagedFileCount) {}
