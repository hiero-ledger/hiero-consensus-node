// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.concurrent.CompletableFuture;

/**
 * A simple record to hold the starting state info when node start at Genesis, Restart or Reconnect.
 * This is needed because the stateHash is needed to construct {@link com.hedera.hapi.block.stream.BlockProof}
 * for first round after node started.
 * @param hashFuture resolves to the hash of the initial state
 * @param roundNum the round number of the initial state
 */
public record InitialStateHash(CompletableFuture<Bytes> hashFuture, long roundNum) {}
