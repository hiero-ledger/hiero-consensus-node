// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.eventhandling;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.platform.components.transaction.system.ScopedSystemTransaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Queue;

/**
 * The result of the {@link com.swirlds.platform.eventhandling.TransactionHandler} handling a round.
 * <p>
 * Contains:
 * <ul>
 * <li>a wrapper object with a reserved, unhashed state for the round just handled and an estimated hash computation
 * complexity, or null if the round is aligned with the end of a block, or null otherwise, </li>
 * <lI>a queue of system transactions contained in the round</lI>
 * </ul>
 *
 * @param stateWithHashComplexity the state may null, if the round is not aligned with a block boundary
 * @param systemTransactions      the system transactions that reached consensus in the round
 */
public record TransactionHandlerResult(
        @Nullable StateWithHashComplexity stateWithHashComplexity,
        @NonNull Queue<ScopedSystemTransaction<StateSignatureTransaction>> systemTransactions) {}
