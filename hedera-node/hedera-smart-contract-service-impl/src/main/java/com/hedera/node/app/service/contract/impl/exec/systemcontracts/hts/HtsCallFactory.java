// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CallType.QUALIFIED_DELEGATE;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.configOf;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.proxyUpdaterFor;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.systemContractGasCalculatorOf;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAddressChecks;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAttemptOptions;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallFactory;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.CallType;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Factory to create a new {@link HtsCallAttempt} for a given input and message frame.
 */
@Singleton
public class HtsCallFactory implements CallFactory<HtsCallAttempt> {
    private final SyntheticIds syntheticIds;
    private final CallAddressChecks addressChecks;
    private final VerificationStrategies verificationStrategies;
    private final List<CallTranslator<HtsCallAttempt>> callTranslators;
    private final SystemContractMethodRegistry systemContractMethodRegistry;

    @Inject
    public HtsCallFactory(
            @NonNull final SyntheticIds syntheticIds,
            @NonNull final CallAddressChecks addressChecks,
            @NonNull final VerificationStrategies verificationStrategies,
            @NonNull @Named("HtsTranslators") final List<CallTranslator<HtsCallAttempt>> callTranslators,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry) {
        this.syntheticIds = requireNonNull(syntheticIds);
        this.addressChecks = requireNonNull(addressChecks);
        this.verificationStrategies = requireNonNull(verificationStrategies);
        this.callTranslators = requireNonNull(callTranslators);
        this.systemContractMethodRegistry = requireNonNull(systemContractMethodRegistry);
    }

    /**
     * Creates a new {@link HtsCallAttempt} for the given input and message frame.
     *
     * @param input    the input
     * @param frame    the message frame
     * @param callType the call type
     * @return the new attempt
     * @throws RuntimeException if the call cannot be created
     */
    @Override
    public @NonNull HtsCallAttempt createCallAttemptFrom(
            @NonNull ContractID contractID,
            @NonNull final Bytes input,
            @NonNull final CallType callType,
            @NonNull final MessageFrame frame) {
        requireNonNull(input);
        requireNonNull(frame);
        final var enhancement = proxyUpdaterFor(frame).enhancement();
        return new HtsCallAttempt(
                input,
                new CallAttemptOptions<>(
                        contractID,
                        frame.getSenderAddress(),
                        // We only need to distinguish between the EVM sender id and the
                        // "authorizing id" for qualified delegate calls; and even then, only
                        // for classic transfers. In that specific case, the qualified delegate
                        // contracts need to use their own address as the authorizing id in order
                        // to have signatures waived correctly during preHandle() for the
                        // dispatched CryptoTransfer.
                        // As an example, following transaction show that a qualified delegate can delegate
                        // call directForToken function in the hts system contract address.  No similar
                        // transaction could be found for making a delegate to a classic transfer function.
                        // https://hashscan.io/mainnet/transaction/1722925453.690690655
                        callType == QUALIFIED_DELEGATE ? frame.getRecipientAddress() : frame.getSenderAddress(),
                        addressChecks.hasParentDelegateCall(frame),
                        enhancement,
                        configOf(frame),
                        syntheticIds.converterFor(enhancement.nativeOperations()),
                        verificationStrategies,
                        systemContractGasCalculatorOf(frame),
                        callTranslators,
                        systemContractMethodRegistry,
                        frame.isStatic()));
    }
}
