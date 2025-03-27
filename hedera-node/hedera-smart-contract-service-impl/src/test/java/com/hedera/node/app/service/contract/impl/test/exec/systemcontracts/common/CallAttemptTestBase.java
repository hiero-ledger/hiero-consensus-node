// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract.HAS_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HssSystemContract.HSS_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_167_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.EIP_1014_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_BESU_ADDRESS;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAttemptOptions;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.hiero.consensus.model.utility.CommonUtils;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.mockito.Mock;

/**
 * The base test class for all unit tests in Smart Contract Service that is using AbstractCallAttempt.
 */
public class CallAttemptTestBase extends CallTestBase {

    // properties for CallAttempt
    @Mock
    protected AddressIdConverter addressIdConverter;

    @Mock
    protected VerificationStrategies verificationStrategies;

    @Mock
    protected SignatureVerifier signatureVerifier;

    @Mock
    protected Enhancement enhancement;

    protected final SystemContractMethodRegistry systemContractMethodRegistry = new SystemContractMethodRegistry();

    @Mock
    protected MessageFrame frame;

    // -------------------------------------- HTS --------------------------------------
    protected HtsCallAttempt createHtsCallAttempt(
            @NonNull final SystemContractMethod method, @NonNull final CallTranslator<HtsCallAttempt> translator) {
        return createHtsCallAttempt(
                HTS_167_CONTRACT_ID,
                Bytes.wrap(method.selector()),
                OWNER_BESU_ADDRESS,
                OWNER_BESU_ADDRESS,
                false,
                DEFAULT_CONFIG,
                List.of(translator));
    }

    protected HtsCallAttempt createHtsCallAttemptForRedirect(
            @NonNull final SystemContractMethod method, @NonNull final CallTranslator<HtsCallAttempt> translator) {
        return createHtsCallAttempt(
                HTS_167_CONTRACT_ID,
                TestHelpers.bytesForRedirect(method.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                OWNER_BESU_ADDRESS,
                OWNER_BESU_ADDRESS,
                false,
                DEFAULT_CONFIG,
                List.of(translator));
    }

    protected HtsCallAttempt createHtsCallAttempt(
            @NonNull final ContractID contractID,
            @NonNull final SystemContractMethod method,
            @NonNull final CallTranslator<HtsCallAttempt> translator) {
        return createHtsCallAttempt(
                contractID,
                Bytes.wrap(method.selector()),
                OWNER_BESU_ADDRESS,
                OWNER_BESU_ADDRESS,
                false,
                DEFAULT_CONFIG,
                List.of(translator));
    }

    protected HtsCallAttempt createHtsCallAttempt(
            @NonNull final SystemContractMethod method,
            @NonNull final Configuration configuration,
            @NonNull final CallTranslator<HtsCallAttempt> translator) {
        return createHtsCallAttempt(
                HTS_167_CONTRACT_ID,
                Bytes.wrap(method.selector()),
                OWNER_BESU_ADDRESS,
                OWNER_BESU_ADDRESS,
                false,
                configuration,
                List.of(translator));
    }

    protected HtsCallAttempt createHtsCallAttemptForRedirect(
            @NonNull final SystemContractMethod method,
            @NonNull final Configuration configuration,
            @NonNull final CallTranslator<HtsCallAttempt> translator) {
        return createHtsCallAttempt(
                HTS_167_CONTRACT_ID,
                TestHelpers.bytesForRedirect(method.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                OWNER_BESU_ADDRESS,
                OWNER_BESU_ADDRESS,
                false,
                configuration,
                List.of(translator));
    }

    protected HtsCallAttempt createHtsCallAttempt(
            @NonNull final String method, @NonNull final List<CallTranslator<HtsCallAttempt>> callTranslators) {
        return createHtsCallAttempt(
                HTS_167_CONTRACT_ID,
                Bytes.wrap(CommonUtils.unhex(method)),
                EIP_1014_ADDRESS,
                NON_SYSTEM_LONG_ZERO_ADDRESS,
                true,
                DEFAULT_CONFIG,
                callTranslators);
    }

    protected HtsCallAttempt createHtsCallAttempt(
            @NonNull final Bytes input,
            final boolean onlyDelegatableContractKeysActive,
            @NonNull final List<CallTranslator<HtsCallAttempt>> callTranslators) {
        return createHtsCallAttempt(
                HTS_167_CONTRACT_ID,
                input,
                OWNER_BESU_ADDRESS,
                OWNER_BESU_ADDRESS,
                onlyDelegatableContractKeysActive,
                DEFAULT_CONFIG,
                callTranslators);
    }

    protected HtsCallAttempt createHtsCallAttempt(
            @NonNull final ContractID contractID,
            @NonNull final Bytes input,
            @NonNull final Address senderAddress,
            @NonNull final Address authorizingAddress,
            final boolean onlyDelegatableContractKeysActive,
            @NonNull final Configuration configuration,
            @NonNull final List<CallTranslator<HtsCallAttempt>> callTranslators) {
        return new HtsCallAttempt(
                input,
                new CallAttemptOptions<>(
                        contractID,
                        senderAddress,
                        authorizingAddress,
                        onlyDelegatableContractKeysActive,
                        mockEnhancement(),
                        configuration,
                        addressIdConverter,
                        verificationStrategies,
                        gasCalculator,
                        callTranslators,
                        systemContractMethodRegistry,
                        false));
    }

    // -------------------------------------- HAS --------------------------------------
    protected HasCallAttempt createHasCallAttempt(
            @NonNull final Bytes input, @NonNull final CallTranslator<HasCallAttempt> translator) {
        return createHasCallAttempt(input, DEFAULT_CONFIG, List.of(translator));
    }

    protected HasCallAttempt createHasCallAttempt(
            @NonNull final Bytes input,
            @NonNull final Configuration configuration,
            @NonNull final CallTranslator<HasCallAttempt> translator) {
        return createHasCallAttempt(input, configuration, List.of(translator));
    }

    protected HasCallAttempt createHasCallAttempt(
            @NonNull final Bytes input, @NonNull final List<CallTranslator<HasCallAttempt>> callTranslators) {
        return createHasCallAttempt(input, DEFAULT_CONFIG, callTranslators);
    }

    protected HasCallAttempt createHasCallAttempt(
            @NonNull final Bytes input,
            @NonNull final Configuration configuration,
            @NonNull final List<CallTranslator<HasCallAttempt>> callTranslators) {
        return new HasCallAttempt(
                input,
                new CallAttemptOptions<>(
                        HAS_CONTRACT_ID,
                        OWNER_BESU_ADDRESS,
                        OWNER_BESU_ADDRESS,
                        false,
                        mockEnhancement(),
                        configuration,
                        addressIdConverter,
                        verificationStrategies,
                        gasCalculator,
                        callTranslators,
                        systemContractMethodRegistry,
                        false),
                signatureVerifier);
    }

    // -------------------------------------- HSS --------------------------------------
    protected HssCallAttempt createHssCallAttempt(
            @NonNull final SystemContractMethod method, @NonNull final CallTranslator<HssCallAttempt> translator) {
        return createHssCallAttempt(
                Bytes.wrap(method.selector()),
                OWNER_BESU_ADDRESS,
                false,
                TestHelpers.DEFAULT_CONFIG,
                List.of(translator));
    }

    protected HssCallAttempt createHssCallAttempt(
            @NonNull final Bytes input,
            final boolean onlyDelegatableContractKeysActive,
            @NonNull final Configuration configuration,
            @NonNull final List<CallTranslator<HssCallAttempt>> callTranslators) {
        return createHssCallAttempt(
                input, OWNER_BESU_ADDRESS, onlyDelegatableContractKeysActive, configuration, callTranslators);
    }

    protected HssCallAttempt createHssCallAttempt(
            @NonNull final Bytes input,
            @NonNull final Address senderAddress,
            final boolean onlyDelegatableContractKeysActive,
            @NonNull final Configuration configuration,
            @NonNull final List<CallTranslator<HssCallAttempt>> callTranslators) {
        return new HssCallAttempt(
                input,
                new CallAttemptOptions<>(
                        HSS_CONTRACT_ID,
                        senderAddress,
                        senderAddress,
                        onlyDelegatableContractKeysActive,
                        mockEnhancement(),
                        configuration,
                        addressIdConverter,
                        verificationStrategies,
                        gasCalculator,
                        callTranslators,
                        systemContractMethodRegistry,
                        false),
                signatureVerifier);
    }
}
