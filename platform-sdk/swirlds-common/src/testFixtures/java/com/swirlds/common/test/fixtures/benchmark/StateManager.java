// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.benchmark;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.utility.AutoCloseableWrapper;

/**
 * Get a state to be used in an operation.
 *
 * @param <S>
 * 		the type of the state
 */
public interface StateManager<S extends MerkleNode> {

    AutoCloseableWrapper<S> getState();
}
