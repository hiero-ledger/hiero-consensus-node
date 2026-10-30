// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.sei;

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
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.SyntheticIds;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Factory to create a new {@link SeiVerifierCallAttempt} for a given input and message frame.
 */
@Singleton
public class SeiVerifierCallFactory implements CallFactory<SeiVerifierCallAttempt> {
    private final SyntheticIds syntheticIds;
    private final CallAddressChecks addressChecks;
    private final VerificationStrategies verificationStrategies;
    private final List<CallTranslator<SeiVerifierCallAttempt>> callTranslators;
    private final SystemContractMethodRegistry systemContractMethodRegistry;

    @Inject
    public SeiVerifierCallFactory(
            @NonNull final SyntheticIds syntheticIds,
            @NonNull final CallAddressChecks addressChecks,
            @NonNull final VerificationStrategies verificationStrategies,
            @NonNull @Named("SeiVerifierTranslators")
                    final List<CallTranslator<SeiVerifierCallAttempt>> callTranslators,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry) {
        this.syntheticIds = requireNonNull(syntheticIds);
        this.addressChecks = requireNonNull(addressChecks);
        this.verificationStrategies = requireNonNull(verificationStrategies);
        this.callTranslators = requireNonNull(callTranslators);
        this.systemContractMethodRegistry = requireNonNull(systemContractMethodRegistry);
    }

    @Override
    public @NonNull SeiVerifierCallAttempt createCallAttemptFrom(
            @NonNull final ContractID contractID,
            @NonNull final Bytes input,
            @NonNull final FrameUtils.CallType callType,
            @NonNull final MessageFrame frame) {
        requireNonNull(input);
        requireNonNull(frame);
        final var enhancement = proxyUpdaterFor(frame).enhancement();
        return new SeiVerifierCallAttempt(
                input,
                new CallAttemptOptions<>(
                        contractID,
                        frame.getSenderAddress(),
                        frame.getRecipientAddress(),
                        frame.getSenderAddress(),
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
