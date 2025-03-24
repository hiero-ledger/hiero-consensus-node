// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.eventhandling;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Queue;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

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
 * @param stateWithHashComplexity a wrapper objects with a signed state and an estimate of its hash complexity, or null
 *                                if no signed state was created for this round
 * @param systemTransactions      any system transactions that reached consensus in the round
 */
public record TransactionHandlerResult(
        @Nullable StateWithHashComplexity stateWithHashComplexity,
        @NonNull Queue<ScopedSystemTransaction<StateSignatureTransaction>> systemTransactions) {}
