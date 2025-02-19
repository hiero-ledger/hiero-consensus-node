/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.state.signed;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.components.transaction.system.ScopedSystemTransaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Queue;

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
