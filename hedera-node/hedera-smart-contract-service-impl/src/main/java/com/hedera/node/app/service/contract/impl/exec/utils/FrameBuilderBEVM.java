// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

//import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
//import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ETHEREUM_TRANSACTION;
//import static com.hedera.hapi.streams.SidecarType.CONTRACT_ACTION;
//import static com.hedera.hapi.streams.SidecarType.CONTRACT_BYTECODE;
//import static com.hedera.hapi.streams.SidecarType.CONTRACT_STATE_CHANGE;
//import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.ACTION_SIDECARS_VALIDATION_VARIABLE;
//import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.ACTION_SIDECARS_VARIABLE;
//import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.BYTECODE_SIDECARS_VARIABLE;
//import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CONFIG_CONTEXT_VARIABLE;
//import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.HAPI_RECORD_BUILDER_CONTEXT_VARIABLE;
//import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.HOOK_OWNER_ADDRESS;
//import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.INVALID_ADDRESS_CONTEXT_VARIABLE;
//import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.OPS_DURATION_COUNTER;
//import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.PENDING_CREATION_BUILDER_CONTEXT_VARIABLE;
//import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.PROPAGATED_CALL_FAILURE_CONTEXT_VARIABLE;
//import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.SYSTEM_CONTRACT_GAS_CALCULATOR_CONTEXT_VARIABLE;
//import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.TINYBAR_VALUES_CONTEXT_VARIABLE;
//import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.TRACKER_CONTEXT_VARIABLE;
//import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asLongZeroAddress;
//import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
//
//import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmContext;
import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransaction;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.utils.TODO;
//import com.hedera.node.app.service.contract.impl.infra.StorageAccessTracker;
//import com.hedera.node.config.data.ContractsConfig;
//import com.hedera.node.config.data.LedgerConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
//import edu.umd.cs.findbugs.annotations.Nullable;
//import java.util.HashMap;
//import java.util.Map;
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
public class FrameBuilderBEVM extends FrameBuilder {

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
    public MessageFrame buildInitialFrameWith(
            @NonNull final HederaEvmTransaction transaction,
            @NonNull final HederaWorldUpdater worldUpdater,
            @NonNull final HederaEvmContext context,
            @NonNull final Configuration config,
            @NonNull final OpsDurationCounter opsDurationCounter,
            @NonNull final FeatureFlags featureFlags,
            @NonNull final Address from,
            @NonNull final Address to,
            final long intrinsicGas,
            @NonNull final CodeFactory codeFactory) {
      throw new TODO();
    }
}
