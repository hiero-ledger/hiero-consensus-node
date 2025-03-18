// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.common;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hyperledger.besu.datatypes.Address;

public record CallAttemptOptions<T extends AbstractCallAttempt<T>>(
        @NonNull ContractID contractID,
        @NonNull Address senderAddress,
        @NonNull Address authorizingAddress,
        boolean onlyDelegatableContractKeysActive,
        @NonNull HederaWorldUpdater.Enhancement enhancement,
        @NonNull Configuration configuration,
        @NonNull AddressIdConverter addressIdConverter,
        @NonNull VerificationStrategies verificationStrategies,
        @NonNull SystemContractGasCalculator gasCalculator,
        @NonNull List<CallTranslator<T>> callTranslators,
        @NonNull SystemContractMethodRegistry systemContractMethodRegistry,
        boolean isStaticCall) {

    /**
     * @param contractID the target system contract ID
     * @param senderAddress the address of the sender of this call
     * @param authorizingAddress the contract whose keys are to be activated
     * @param onlyDelegatableContractKeysActive whether the strategy should require a delegatable contract id key
     * @param enhancement the enhancement to get the native operations to look up the contract's number
     * @param configuration the configuration for this call
     * @param addressIdConverter the address ID converter for this call
     * @param verificationStrategies the verification strategies for this call
     * @param gasCalculator the system contract gas calculator for this call
     * @param callTranslators the call translators for this call
     * @param isStaticCall whether this is a static call
     * @param systemContractMethodRegistry a registry for all the system contract methods - their names, selectors, and signatures.
     */
    public CallAttemptOptions(
            @NonNull final ContractID contractID,
            @NonNull final Address senderAddress,
            @NonNull final Address authorizingAddress,
            final boolean onlyDelegatableContractKeysActive,
            @NonNull final Enhancement enhancement,
            @NonNull final Configuration configuration,
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final VerificationStrategies verificationStrategies,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final List<CallTranslator<T>> callTranslators,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            final boolean isStaticCall) {
        this.contractID = requireNonNull(contractID);
        this.senderAddress = requireNonNull(senderAddress);
        this.authorizingAddress = requireNonNull(authorizingAddress);
        this.onlyDelegatableContractKeysActive = onlyDelegatableContractKeysActive;
        this.enhancement = requireNonNull(enhancement);
        this.configuration = requireNonNull(configuration);
        this.addressIdConverter = requireNonNull(addressIdConverter);
        this.verificationStrategies = requireNonNull(verificationStrategies);
        this.gasCalculator = requireNonNull(gasCalculator);
        this.callTranslators = requireNonNull(callTranslators);
        this.systemContractMethodRegistry = requireNonNull(systemContractMethodRegistry);
        this.isStaticCall = isStaticCall;
    }
}
