// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import com.hedera.hapi.streams.SidecarType;
import com.hedera.node.app.service.contract.impl.bonneville.BonnevilleEVM;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCharging;
import com.hedera.node.app.service.contract.impl.exec.gas.GasCharges;
import com.hedera.node.app.service.contract.impl.exec.processors.CustomMessageCallProcessor;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameBuilder;
import com.hedera.node.app.service.contract.impl.exec.utils.OpsDurationCounter;
import com.hedera.node.app.service.contract.impl.hevm.*;
import com.hedera.node.app.service.contract.impl.utils.TODO;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;

// BESU imports
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.datatypes.Wei;


/**
 * Modeled after the Besu {@code MainnetTransactionProcessor}, so that all four HAPI
 * contract operations ({@code ContractCall}, {@code ContractCreate}, {@code EthereumTransaction},
 * {@code ContractCallLocal}) can reduce to a single code path.
 */
public class TransactionProcessorBEVM extends TransactionProcessor {
    //private final FrameBuilder frameBuilder;
    //private final FrameRunner frameRunner;
    //private final ContractCreationProcessor contractCreation;

    public TransactionProcessorBEVM(
            @NonNull FrameBuilder frameBuilder,
            @NonNull FrameRunner frameRunner,
            @NonNull CustomGasCharging gasCharging,
            @NonNull CustomMessageCallProcessor messageCall,
            @NonNull ContractCreationProcessor contractCreation,
            @NonNull FeatureFlags featureFlags,
            @NonNull CodeFactory codeFactory) {
        super(gasCharging, messageCall, featureFlags, codeFactory);
        //this.frameBuilder = requireNonNull(frameBuilder);
        //this.frameRunner = requireNonNull(frameRunner);
        //this.contractCreation = requireNonNull(contractCreation);
    }


    /**
     * Process the given transaction, returning the result of running it to completion
     * and committing to the given updater.
     *
     * @param transaction the transaction to process
     * @param updater the world updater to commit to
     * @param context the context to use
     * @param tracer the tracer to use
     * @param config the node configuration
     * @return the result of running the transaction to completion
     */
    public HederaEvmTransactionResult processTransaction(
            HederaEvmTransaction transaction,
            HederaWorldUpdater updater,
            HederaEvmContext context,
            ActionSidecarContentTracer tracer,
            Configuration config,
            OpsDurationCounter opsDurationCounter) {

        var parties = computeInvolvedPartiesOrAbort(transaction, updater, config);

        // If it is hook dispatch, skip gas charging because gas is pre-paid in cryptoTransfer already
        var gasCharges = transaction.hookOwnerAddress() != null
          ? GasCharges.NONE
          : gasCharging.chargeForGas(parties.sender(), parties.relayer(), context, updater, transaction);

        // Build a first MessageFrame
        var value = transaction.weiValue();
        var ledgerConfig = config.getConfigData( LedgerConfig.class);
        var nominalCoinbase = asLongZeroAddress(ledgerConfig.fundingAccount());
        var contextVariables = contextVariablesFrom(config, opsDurationCounter, context, transaction.hookOwnerAddress());

        MessageFrame.Builder bld = new MessageFrame.Builder()
          .worldUpdater( updater )
          .initialGas(transaction.gasAvailable(gasCharges.intrinsicGas()))
          .address   (parties.    receiverAddress())
          .contract  (parties.    receiverAddress())
          .originator(parties.sender().getAddress())
          .from      (parties.sender().getAddress())
          .gasPrice(Wei.of(context.gasPrice()))
          .blobGasPrice(Wei.ONE) // Per Hedera CANCUN adaptation
          .value        (value)
          .apparentValue(value)
          .blockValues(context.blockValuesOf(transaction.gasLimit()))
          .maxStackSize(BonnevilleEVM.MAX_STACK_SIZE)
          .isStatic(context.staticCall())
          .completer(unused -> {})
          .miningBeneficiary(nominalCoinbase)
          .blockHashLookup(context.blocks()::blockHashOf)
          .contextVariables(contextVariables)
          .code(codeFactory.createCode(transaction.evmPayload(), false))

        if( transaction.isCreate() ) {
          bld.type(MessageFrame.Type.CONTRACT_CREATION)
            .inputData( Bytes.EMPTY);



        } else {
          bld.type(MessageFrame.Type.MESSAGE_CALL)
            .inputData(transaction.evmPayload());
        }

        MessageFrame frame = bld.build();

        // TODO: will need a MessageFrame and a MessageFrame stack.
        // This runs the top-level code, without making a MessageFrame
        // and thus probably fails if one contract calls to another.

        // During the BESU TP, a FrameRunner spins over a stack of
        // MessageFrames and runs either the CustomMessageCallProcessor or
        // ContractCreationProcess .process(frame) call.

        final var recvAccount = updater.get(parties.receiverAddress());

        // Remove the interface at this level, so Bonneville only deals with concrete classes.
        // AbstractBytes is basically an array list for bytes, with a hashcode.
        byte[] codes = frame.getCodes().getBytes().toArrayUnsafe();

        // Hedera custom sidecar
        ContractsConfig cc = config.getConfigData(ContractsConfig.class);
        boolean isSidecarEnabled = cc.sidecars().contains(SidecarType.CONTRACT_STATE_CHANGE);

        BonnevilleEVM bevm = new BonnevilleEVM(recvAccount, codes, gasCharging.gasCalculator, gasCharges.intrinsicGas(), isSidecarEnabled );

        // Run the smart contract
        bevm.run();

        bevm.getSelfDestructs().forEach(updater::deleteAccount);


        // Maybe refund some of the charged fees before committing if not a hook dispatch
        // Note that for hook dispatch, gas is charged during cryptoTransfer and will not be refunded once
        // hook is executed
        if (transaction.hookOwnerAddress() == null) {
            gasCharging.maybeRefundGiven(
                    transaction.unusedGas(bevm.gasUsed()),
                    gasCharges.relayerAllowanceUsed(),
                    parties.sender(),
                    parties.relayer(),
                    context,
                    updater);
        }

        // Tries to commit and return the original result; returns a fees-only result on resource exhaustion
        return safeCommit(bevm.result(), transaction, updater, context, bevm.accessTracker());
    }

}
