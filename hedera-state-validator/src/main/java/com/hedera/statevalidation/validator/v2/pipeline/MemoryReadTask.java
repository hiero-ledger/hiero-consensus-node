// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.v2.pipeline;

/**
 * Represents a task for reading a range of paths from in-memory hash storage.
 *
 * @param startPath the starting path (inclusive)
 * @param endPath the ending path (exclusive)
 */
public record MemoryReadTask(long startPath, long endPath) {}
