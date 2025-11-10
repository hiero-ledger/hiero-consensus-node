// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;
//
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.utils.TODO;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Singleton;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Infrastructure component that builds the initial {@link MessageFrame} instance for a transaction.
 * This includes tasks like,
 * <ol>
 *     <li>Putting the {@link Configuration} in the frame context variables.</li>
 *     <li>Setting the gas price and block values from the {@link HederaEvmContext}.</li>
 *     <li>Setting input data and code based on the message call type.</li>
 * </ol>
 */
@Singleton
public abstract class FrameBuilder {
    static final int MAX_STACK_SIZE = 1024;

    public static FrameBuilder make() {
        return System.getenv("UseBonnevilleEVM")==null
            ? new FrameBuilderBESU()
            : new FrameBuilderBEVM();
    }


    /**
     * Builds the initial {@link MessageFrame} instance for a transaction.
     *
     * @param transaction the transaction
     * @param worldUpdater the world updater for the transaction
     * @param context the Hedera EVM context (gas price, block values, etc.)
     * @param config the active Hedera configuration
     * @param featureFlags the feature flag currently used
     * @param from the sender of the transaction
     * @param to the recipient of the transaction
     * @param intrinsicGas the intrinsic gas cost, needed to calculate remaining gas
     * @param codeFactory the factory used to construct an instance of {@link org.hyperledger.besu.evm.Code}
     * *                    from raw bytecode.
     * @return the initial frame
     */
    public abstract MessageFrame buildInitialFrameWith(
            HederaEvmTransaction transaction,
            HederaWorldUpdater worldUpdater,
            HederaEvmContext context,
            Configuration config,
            OpsDurationCounter opsDurationCounter,
            FeatureFlags featureFlags,
            Address from,
            Address to,
            long intrinsicGas,
            CodeFactory codeFactory);

}
