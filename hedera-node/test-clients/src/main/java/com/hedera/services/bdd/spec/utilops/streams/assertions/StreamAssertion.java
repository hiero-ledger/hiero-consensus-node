// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams.assertions;

/**
 * Common marker interface for stream assertions. Subtypes handle either record stream items
 * ({@link RecordStreamAssertion}) or block stream items ({@link BlockStreamAssertion}).
 *
 * <p>Use with the {@code streamMust*} verbs in
 * {@link com.hedera.services.bdd.spec.utilops.UtilVerbs} which dynamically route
 * to the correct stream based on the active {@code blockStream.streamMode}.
 */
public interface StreamAssertion {}
