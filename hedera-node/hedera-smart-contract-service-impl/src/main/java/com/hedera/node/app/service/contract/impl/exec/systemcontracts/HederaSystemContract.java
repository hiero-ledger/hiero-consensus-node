// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts;

import com.hedera.hapi.node.base.ContractID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
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
    /**
     * Computes a result, possibly suspending the frame until a child message finishes.
     * The completion receives the final result exactly once.
     */
    default void computeFully(
            @NonNull ContractID contractID,
            @NonNull Bytes input,
            @NonNull MessageFrame messageFrame,
            @NonNull Consumer<FullResult> completion) {
        completion.accept(computeFully(contractID, input, messageFrame));
    }
}
