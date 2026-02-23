// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.model;

/**
 * Represents a segment of the in-memory hash store assigned for parallel reading during validation.
 *
 * @param startPath the starting path (inclusive)
 * @param endPath the ending path (exclusive)
 */
public record MemoryReadSegment(long startPath, long endPath) {}
