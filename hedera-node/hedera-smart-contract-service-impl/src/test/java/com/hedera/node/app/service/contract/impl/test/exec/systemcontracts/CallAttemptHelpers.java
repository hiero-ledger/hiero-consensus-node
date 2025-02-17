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

package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract.HAS_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HssSystemContract.HSS_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_167_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.NON_SYSTEM_LONG_ZERO_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_BESU_ADDRESS;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.test.TestHelpers;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.swirlds.config.api.Configuration;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;

/**
 * Helper utility class to generate {@link HtsCallAttempt} object in different scenarios
 */
public final class CallAttemptHelpers {

    public static HtsCallAttempt prepareHtsAttemptWithSelector(
            final SystemContractMethod systemContractMethod,
            final CallTranslator<HtsCallAttempt> translator,
            final HederaWorldUpdater.Enhancement enhancement,
            final AddressIdConverter addressIdConverter,
            final VerificationStrategies verificationStrategies,
            final SystemContractGasCalculator gasCalculator,
            final SystemContractMethodRegistry systemContractMethodRegistry) {
        final var input = Bytes.wrap(systemContractMethod.selector());

        return new HtsCallAttempt(
                HTS_167_CONTRACT_ID,
                input,
                OWNER_BESU_ADDRESS,
                OWNER_BESU_ADDRESS,
                false,
                enhancement,
                DEFAULT_CONFIG,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                List.of(translator),
                systemContractMethodRegistry,
                false);
    }

    public static HtsCallAttempt prepareHtsAttemptWithSelectorWithContractID(
            final ContractID contractID,
            final SystemContractMethod systemContractMethod,
            final CallTranslator<HtsCallAttempt> translator,
            final HederaWorldUpdater.Enhancement enhancement,
            final AddressIdConverter addressIdConverter,
            final VerificationStrategies verificationStrategies,
            final SystemContractGasCalculator gasCalculator,
            final SystemContractMethodRegistry systemContractMethodRegistry) {
        final var input = Bytes.wrap(systemContractMethod.selector());

        return new HtsCallAttempt(
                contractID,
                input,
                OWNER_BESU_ADDRESS,
                OWNER_BESU_ADDRESS,
                false,
                enhancement,
                DEFAULT_CONFIG,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                List.of(translator),
                systemContractMethodRegistry,
                false);
    }

    public static HtsCallAttempt prepareHtsAttemptWithSelectorForRedirect(
            final SystemContractMethod systemContractMethod,
            final CallTranslator<HtsCallAttempt> translator,
            final HederaWorldUpdater.Enhancement enhancement,
            final AddressIdConverter addressIdConverter,
            final VerificationStrategies verificationStrategies,
            final SystemContractGasCalculator gasCalculator,
            final SystemContractMethodRegistry systemContractMethodRegistry) {
        final var input = TestHelpers.bytesForRedirect(systemContractMethod.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS);

        return new HtsCallAttempt(
                HTS_167_CONTRACT_ID,
                input,
                OWNER_BESU_ADDRESS,
                OWNER_BESU_ADDRESS,
                false,
                enhancement,
                DEFAULT_CONFIG,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                List.of(translator),
                systemContractMethodRegistry,
                false);
    }

    public static HtsCallAttempt prepareHtsAttemptWithSelectorForRedirectWithConfig(
            final SystemContractMethod systemContractMethod,
            final CallTranslator<HtsCallAttempt> translator,
            final HederaWorldUpdater.Enhancement enhancement,
            final AddressIdConverter addressIdConverter,
            final VerificationStrategies verificationStrategies,
            final SystemContractGasCalculator gasCalculator,
            final SystemContractMethodRegistry systemContractMethodRegistry,
            final Configuration config) {
        final var input = TestHelpers.bytesForRedirect(systemContractMethod.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS);

        return new HtsCallAttempt(
                HTS_167_CONTRACT_ID,
                input,
                OWNER_BESU_ADDRESS,
                OWNER_BESU_ADDRESS,
                false,
                enhancement,
                config,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                List.of(translator),
                systemContractMethodRegistry,
                false);
    }

    public static HtsCallAttempt prepareHtsAttemptWithSelectorAndCustomConfig(
            final SystemContractMethod systemContractMethod,
            final CallTranslator<HtsCallAttempt> translator,
            final HederaWorldUpdater.Enhancement enhancement,
            final AddressIdConverter addressIdConverter,
            final VerificationStrategies verificationStrategies,
            final SystemContractGasCalculator gasCalculator,
            final SystemContractMethodRegistry systemContractMethodRegistry,
            final Configuration config) {
        final var input = Bytes.wrap(systemContractMethod.selector());

        return new HtsCallAttempt(
                HTS_167_CONTRACT_ID,
                input,
                OWNER_BESU_ADDRESS,
                OWNER_BESU_ADDRESS,
                false,
                enhancement,
                config,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                List.of(translator),
                systemContractMethodRegistry,
                false);
    }

    /**
     * @param systemContractMethod the selector to match against (as a `SystemContractMethod`)
     * @param translator the translator for this specific call attempt
     * @param enhancement the enhancement that is used
     * @param addressIdConverter the address ID converter for this call
     * @param verificationStrategies the verification strategy currently used
     * @param signatureVerifier a function that verifies a signature
     * @param gasCalculator the gas calculator used for the system contract
     * @return the call attempt
     */
    public static HasCallAttempt prepareHasAttemptWithSelector(
            final SystemContractMethod systemContractMethod,
            final CallTranslator<HasCallAttempt> translator,
            final HederaWorldUpdater.Enhancement enhancement,
            final AddressIdConverter addressIdConverter,
            final VerificationStrategies verificationStrategies,
            final SignatureVerifier signatureVerifier,
            final SystemContractGasCalculator gasCalculator,
            final SystemContractMethodRegistry systemContractMethodRegistry) {
        return prepareHasAttemptWithSelectorAndCustomConfig(
                systemContractMethod,
                translator,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                DEFAULT_CONFIG);
    }

    /**
     * @param systemContractMethod the selector to match against (as a `SystemContractMethod`)
     * @param translator the translator for this specific call attempt
     * @param enhancement the enhancement that is used
     * @param addressIdConverter the address ID converter for this call
     * @param verificationStrategies the verification strategy currently used
     * @param signatureVerifier a function that verifies a signature
     * @param gasCalculator the gas calculator used for the system contract
     * @param config the configuration being used
     * @return the call attempt
     */
    public static HasCallAttempt prepareHasAttemptWithSelectorAndCustomConfig(
            final SystemContractMethod systemContractMethod,
            final CallTranslator<HasCallAttempt> translator,
            final HederaWorldUpdater.Enhancement enhancement,
            final AddressIdConverter addressIdConverter,
            final VerificationStrategies verificationStrategies,
            final SignatureVerifier signatureVerifier,
            final SystemContractGasCalculator gasCalculator,
            final SystemContractMethodRegistry systemContractMethodRegistry,
            final Configuration config) {
        return prepareHasAttemptWithSelectorAndInputAndCustomConfig(
                systemContractMethod,
                TestHelpers.bytesForRedirectAccount(systemContractMethod.selector(), NON_SYSTEM_LONG_ZERO_ADDRESS),
                translator,
                enhancement,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                systemContractMethodRegistry,
                config);
    }

    /**
     * @param input the input in bytes
     * @param translator the translator for this specific call attempt
     * @param enhancement the enhancement that is used
     * @param addressIdConverter the address ID converter for this call
     * @param verificationStrategies the verification strategy currently used
     * @param signatureVerifier a function that verifies a signature
     * @param gasCalculator the gas calculator used for the system contract
     * @param config the configuration being used
     * @return the call attempt
     */
    public static HasCallAttempt prepareHasAttemptWithSelectorAndInputAndCustomConfig(
            final Bytes input,
            final CallTranslator<HasCallAttempt> translator,
            final HederaWorldUpdater.Enhancement enhancement,
            final AddressIdConverter addressIdConverter,
            final VerificationStrategies verificationStrategies,
            final SignatureVerifier signatureVerifier,
            final SystemContractGasCalculator gasCalculator,
            final SystemContractMethodRegistry systemContractMethodRegistry,
            final Configuration config) {
        return new HasCallAttempt(
                HAS_CONTRACT_ID,
                input,
                OWNER_BESU_ADDRESS,
                false,
                enhancement,
                config,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                List.of(translator),
                systemContractMethodRegistry,
                false);
    }

    public static HasCallAttempt prepareHasAttemptWithSelectorAndInputAndCustomConfig(
            final SystemContractMethod systemContractMethod,
            final Bytes input,
            final CallTranslator<HasCallAttempt> translator,
            final HederaWorldUpdater.Enhancement enhancement,
            final AddressIdConverter addressIdConverter,
            final VerificationStrategies verificationStrategies,
            final SignatureVerifier signatureVerifier,
            final SystemContractGasCalculator gasCalculator,
            final SystemContractMethodRegistry systemContractMethodRegistry,
            final Configuration config) {
        return new HasCallAttempt(
                HAS_CONTRACT_ID,
                input,
                OWNER_BESU_ADDRESS,
                false,
                enhancement,
                config,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                List.of(translator),
                systemContractMethodRegistry,
                false);
    }

    /**
     * @param systemContractMethod the selector to match against (as a `SystemContractMethod`)
     * @param translator the translator for this specific call attempt
     * @param enhancement the enhancement that is used
     * @param addressIdConverter the address ID converter for this call
     * @param verificationStrategies the verification strategy currently used
     * @param gasCalculator the gas calculator used for the system contract
     * @param config the current configuration that is used
     * @return the call attempt
     */
    public static HssCallAttempt prepareHssAttemptWithSelectorAndCustomConfig(
            final SystemContractMethod systemContractMethod,
            final CallTranslator<HssCallAttempt> translator,
            final HederaWorldUpdater.Enhancement enhancement,
            final AddressIdConverter addressIdConverter,
            final VerificationStrategies verificationStrategies,
            final SignatureVerifier signatureVerifier,
            final SystemContractGasCalculator gasCalculator,
            final SystemContractMethodRegistry systemContractMethodRegistry,
            final Configuration config) {
        final var input = Bytes.wrap(systemContractMethod.selector());

        return new HssCallAttempt(
                HSS_CONTRACT_ID,
                input,
                OWNER_BESU_ADDRESS,
                false,
                enhancement,
                config,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                List.of(translator),
                systemContractMethodRegistry,
                false);
    }

    public static HssCallAttempt prepareHssAttemptWithSelectorAndCustomConfig(
            final SystemContractMethod systemContractMethod,
            final CallTranslator<HssCallAttempt> translator,
            final HederaWorldUpdater.Enhancement enhancement,
            final AddressIdConverter addressIdConverter,
            final VerificationStrategies verificationStrategies,
            final SystemContractGasCalculator gasCalculator,
            final SignatureVerifier signatureVerifier,
            final SystemContractMethodRegistry systemContractMethodRegistry,
            final Configuration config) {
        final var input = Bytes.wrap(systemContractMethod.selector());

        return new HssCallAttempt(
                HSS_CONTRACT_ID,
                input,
                OWNER_BESU_ADDRESS,
                false,
                enhancement,
                config,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                List.of(translator),
                systemContractMethodRegistry,
                false);
    }

    public static HssCallAttempt prepareHssAttemptWithBytesAndCustomConfig(
            final Bytes input,
            final CallTranslator<HssCallAttempt> translator,
            final HederaWorldUpdater.Enhancement enhancement,
            final AddressIdConverter addressIdConverter,
            final VerificationStrategies verificationStrategies,
            final SignatureVerifier signatureVerifier,
            final SystemContractGasCalculator gasCalculator,
            final SystemContractMethodRegistry systemContractMethodRegistry,
            final Configuration config) {

        return new HssCallAttempt(
                HSS_CONTRACT_ID,
                input,
                OWNER_BESU_ADDRESS,
                false,
                enhancement,
                config,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                List.of(translator),
                systemContractMethodRegistry,
                false);
    }

    public static HssCallAttempt prepareHssAttemptWithBytesAndCustomConfigAndDelegatableContractKeys(
            final Bytes input,
            final CallTranslator<HssCallAttempt> translator,
            final HederaWorldUpdater.Enhancement enhancement,
            final AddressIdConverter addressIdConverter,
            final VerificationStrategies verificationStrategies,
            final SignatureVerifier signatureVerifier,
            final SystemContractGasCalculator gasCalculator,
            final SystemContractMethodRegistry systemContractMethodRegistry,
            final Configuration config) {

        return new HssCallAttempt(
                HSS_CONTRACT_ID,
                input,
                OWNER_BESU_ADDRESS,
                true,
                enhancement,
                config,
                addressIdConverter,
                verificationStrategies,
                signatureVerifier,
                gasCalculator,
                List.of(translator),
                systemContractMethodRegistry,
                false);
    }
}
