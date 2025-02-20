// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test.fixtures.map.benchmark;

/**
 * A method that constructs an account.
 */
@FunctionalInterface
public interface AccountFactory<A extends BenchmarkAccount> {
    A buildAccount(long balance, byte[] data);
}
