/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import com.hedera.hapi.node.base.ContractID;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.precompile.PrecompiledContract;

/**
 * Interface for Hedera system contracts, which differ from standard EVM precompiles
 * in that they are not always able to report their gas requirement until they have
 * <i>nearly</i> computed their result.
 *
 * <p>For example, a {@code tokenTransfer()} system contract will much use more gas
 * if it involves custom fees. But computing the custom fees requires doing much of
 * the work of the precompile itself.
 */
public interface HederaSystemContract extends PrecompiledContract {

    /**
     * Computes the result of this contract, and also returns the gas requirement.
     *
     * @param contractID the contractID of the called system contract
     * @param input the input to the contract
     * @param messageFrame the message frame
     * @return the result of the computation, and its gas requirement
     */
    default FullResult computeFully(
            @NonNull ContractID contractID, @NonNull Bytes input, @NonNull MessageFrame messageFrame) {
        return new FullResult(computePrecompile(input, messageFrame), gasRequirement(input), null);
    }
}
