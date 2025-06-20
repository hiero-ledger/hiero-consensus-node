// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.component.framework.component.InputWireLabel;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Queue;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * Collects signatures for signed states. This class ensures that all the non-ancient states that are not fully signed
 * (as long as they are not too old) are kept so that signatures can be collected for it. This class returns states once
 * they are either:
 * <ul>
 *     <li>fully signed</li>
 *     <li>too old</li>
 * </ul>
 */
public interface StateSignatureCollector {

    /**
     * Add a state. It could be a complete state, in which case it will be returned immediately.
     *
     * @param reservedSignedState the signed state to add
     * @return a list of signed states that are now complete or too old, or null if there are none
     */
    @InputWireLabel("hashed states")
    @Nullable
    List<ReservedSignedState> addReservedState(@NonNull ReservedSignedState reservedSignedState);

    /**
     * Handle preconsensus state signatures.
     *
     * @param transactions the signature transactions to handle
     * @return a list of signed states that are now complete or too old, or null if there are none
     */
    @InputWireLabel("preconsensus state signatures")
    @Nullable
    List<ReservedSignedState> handlePreconsensusSignatures(
            @NonNull Queue<ScopedSystemTransaction<StateSignatureTransaction>> transactions);

    /**
     * Handle postconsensus state signatures.
     *
     * @param transactions the signature transactions to handle
     * @return a list of signed states that are now complete or too old, or null if there are none
     */
    @InputWireLabel("post consensus state signatures")
    @Nullable
    List<ReservedSignedState> handlePostconsensusSignatures(
            @NonNull Queue<ScopedSystemTransaction<StateSignatureTransaction>> transactions);

    /**
     * Clear the internal state of this collector.
     *
     * @param ignored ignored trigger object
     */
    @InputWireLabel("clear")
    void clear(@NonNull Object ignored);
}
