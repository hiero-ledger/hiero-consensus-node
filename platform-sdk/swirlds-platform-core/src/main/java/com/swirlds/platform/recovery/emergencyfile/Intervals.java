// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery.emergencyfile;

/**
 * The intervals at which various stream files are written, in milliseconds.
 * @param record record stream files are written at this interval, in milliseconds
 * @param event event stream files are written at this interval, in milliseconds
 * @param balances balance files are written at this interval, in milliseconds
 */
public record Intervals(long record, long event, long balances) {}
