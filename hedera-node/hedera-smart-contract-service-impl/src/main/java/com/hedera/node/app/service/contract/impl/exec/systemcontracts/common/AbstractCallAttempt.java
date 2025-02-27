// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.common;

import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.SystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.BufferUnderflowException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;

/**
 * Base class for HTS and HAS system contract call attempts.
 * @param <T> the type of the abstract call attempt
 */
public abstract class AbstractCallAttempt<T extends AbstractCallAttempt<T>> {
    private final byte[] selector;
    protected final ContractID contractID;
    protected Bytes input;
    private final Address authorizingAddress;
    // The id of the sender in the EVM frame
    protected final AccountID senderId;
    private final Address senderAddress;
    private final boolean onlyDelegatableContractKeysActive;
    protected final HederaWorldUpdater.Enhancement enhancement;
    private final Configuration configuration;
    private final AddressIdConverter addressIdConverter;
    private final VerificationStrategies verificationStrategies;
    private final SystemContractGasCalculator gasCalculator;
    private final List<CallTranslator<T>> callTranslators;
    private final SystemContractMethodRegistry systemContractMethodRegistry;
    private final boolean isStaticCall;

    // If non-null, the address of a non-contract entity (e.g., account or token) whose
    // "bytecode" redirects all calls to a system contract address, and was determined
    // to be the redirecting entity for this call attempt
    protected @Nullable final Address redirectAddress;

    /**
     * @param contractID the target system contract ID
     * @param input the input in bytes
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
     * @param redirectFunction the redirect function
     */
    // too many parameters
    @SuppressWarnings("java:S107")
    public AbstractCallAttempt(
            @NonNull final ContractID contractID,
            @NonNull final Bytes input,
            @NonNull final Address senderAddress,
            @NonNull final Address authorizingAddress,
            final boolean onlyDelegatableContractKeysActive,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final Configuration configuration,
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final VerificationStrategies verificationStrategies,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final List<CallTranslator<T>> callTranslators,
            final boolean isStaticCall,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final com.esaulpaugh.headlong.abi.Function redirectFunction) {
        this.contractID = requireNonNull(contractID);
        requireNonNull(input);
        requireNonNull(redirectFunction);
        this.callTranslators = requireNonNull(callTranslators);
        this.gasCalculator = requireNonNull(gasCalculator);
        this.senderAddress = requireNonNull(senderAddress);
        this.authorizingAddress = requireNonNull(authorizingAddress);
        this.configuration = requireNonNull(configuration);
        this.addressIdConverter = requireNonNull(addressIdConverter);
        this.enhancement = requireNonNull(enhancement);
        this.verificationStrategies = requireNonNull(verificationStrategies);
        this.onlyDelegatableContractKeysActive = onlyDelegatableContractKeysActive;
        this.systemContractMethodRegistry = requireNonNull(systemContractMethodRegistry);

        if (isRedirectSelector(redirectFunction.selector(), input.toArrayUnsafe())) {
            Tuple abiCall = null;
            try {
                // First try to decode the redirect with standard ABI encoding using a 32-byte address
                abiCall = redirectFunction.decodeCall(input.toArrayUnsafe());
            } catch (IllegalArgumentException | BufferUnderflowException | IndexOutOfBoundsException ignore) {
                // Otherwise use the "packed" encoding with a 20-byte address
            }
            if (abiCall != null) {
                this.redirectAddress = Address.fromHexString(abiCall.get(0).toString());
                this.input = Bytes.wrap((byte[]) abiCall.get(1));
            } else {
                this.redirectAddress = Address.wrap(input.slice(4, 20));
                this.input = input.slice(24);
            }
        } else {
            this.redirectAddress = null;
            this.input = input;
        }

        this.selector = this.input.slice(0, 4).toArrayUnsafe();
        this.senderId = addressIdConverter.convertSender(senderAddress);
        this.isStaticCall = isStaticCall;
    }

    protected abstract SystemContract systemContractKind();

    protected abstract T self();

    /**
     * Returns the default verification strategy for this call (i.e., the strategy that treats only
     * contract id and delegatable contract id keys as active when they match the call's sender address).
     *
     * @return the default verification strategy for this call
     */
    public @NonNull VerificationStrategy defaultVerificationStrategy() {
        return verificationStrategies.activatingOnlyContractKeysFor(
                authorizingAddress, onlyDelegatableContractKeysActive, enhancement.nativeOperations());
    }

    /**
     * Returns the updater enhancement this call was attempted within.
     *
     * @return the updater enhancement this call was attempted within
     */
    public @NonNull HederaWorldUpdater.Enhancement enhancement() {
        return enhancement;
    }

    /**
     * Returns the system contract gas calculator for this call.
     *
     * @return the system contract gas calculator for this call
     */
    public @NonNull SystemContractGasCalculator systemContractGasCalculator() {
        return gasCalculator;
    }

    /**
     * Returns the native operations this call was attempted within.
     *
     * @return the native operations this call was attempted within
     */
    public @NonNull HederaNativeOperations nativeOperations() {
        return enhancement.nativeOperations();
    }

    /**
     * Tries to translate this call attempt into a {@link Call} from the given sender address.
     *
     * @return the executable call, or null if this attempt can't be translated to one
     */
    public @Nullable Call asExecutableCall() {
        final var self = self();
        for (final var translator : callTranslators) {
            final var call = translator.translateCallAttempt(self);
            if (call != null) {
                return call;
            }
        }
        return null;
    }

    /**
     * Returns the ID of the sender of this call.
     *
     * @return the ID of the sender of this call
     */
    public @NonNull AccountID senderId() {
        return senderId;
    }

    /**
     * Returns the address of the sender of this call.
     *
     * @return the address of the sender of this call
     */
    public @NonNull Address senderAddress() {
        return senderAddress;
    }

    /**
     * Returns the address ID converter for this call.
     *
     * @return the address ID converter for this call
     */
    public AddressIdConverter addressIdConverter() {
        return addressIdConverter;
    }

    /**
     * Returns the configuration for this call.
     *
     * @return the configuration for this call
     */
    public Configuration configuration() {
        return configuration;
    }

    /**
     * Returns the selector of this call.
     *
     * @return the selector of this call
     * @throws IllegalStateException if this is not a valid call
     */
    public byte[] selector() {
        return selector;
    }

    /**
     * Returns the input of this call.
     *
     * @return the input of this call
     * @throws IllegalStateException if this is not a valid call
     */
    public Bytes input() {
        return input;
    }

    /**
     * Returns the raw byte array input of this call.
     *
     * @return the raw input of this call
     * @throws IllegalStateException if this is not a valid call
     */
    public byte[] inputBytes() {
        return input.toArrayUnsafe();
    }

    /**
     * @return whether the current call attempt is a static call
     */
    public boolean isStaticCall() {
        return isStaticCall;
    }

    /**
     * @return whether the current call attempt is redirected to a system contract address
     */
    public boolean isRedirect() {
        return redirectAddress != null;
    }

    /**
     * Returns whether this call attempt is a selector for any of the given functions.
     * @param functions selectors to match against
     * @return boolean result
     */
    public boolean isSelector(@NonNull final Function... functions) {
        for (final var function : functions) {
            if (Arrays.equals(function.selector(), this.selector())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether this call attempt is a selector for any of the given functions.
     * @param methods selectors to match against
     * @return boolean result
     */
    public boolean isSelector(@NonNull final SystemContractMethod... methods) {
        return isMethod(methods).isPresent();
    }

    public @NonNull Optional<SystemContractMethod> isMethod(@NonNull final SystemContractMethod... methods) {
        for (final var method : methods) {
            if (Arrays.equals(method.selector(), this.selector()) && method.hasSupportedAddress(contractID)) {
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns whether this call attempt is a selector for any of the given functions.
     * @param configEnabled whether the config is enabled
     * @param methods selectors to match against
     * @return boolean result
     */
    public boolean isSelectorIfConfigEnabled(
            final boolean configEnabled, @NonNull final SystemContractMethod... methods) {
        return configEnabled && isSelector(methods);
    }

    /**
     * Returns whether this call attempt is a selector for any of the given functions.
     * @param functionSelector bytes of the function selector
     * @param input input bytes
     * @return true if the function selector at the start of the input bytes
     */
    private boolean isRedirectSelector(@NonNull final byte[] functionSelector, @NonNull final byte[] input) {
        return Arrays.equals(input, 0, functionSelector.length, functionSelector, 0, functionSelector.length);
    }

    /**
     * Returns whether only delegate contract keys are active.
     *
     * @return true if only delegate contract keys are active
     */
    public boolean isOnlyDelegatableContractKeysActive() {
        return onlyDelegatableContractKeysActive;
    }

    /**
     * Returns the system contract method registry for this call.
     *
     * @return the system contract method registry for this call
     */
    public SystemContractMethodRegistry getSystemContractMethodRegistry() {
        return systemContractMethodRegistry;
    }

    /**
     * Returns the target system contract ID of this call.
     *
     * @return the target system contract ID of this call
     */
    public ContractID systemContractID() {
        return contractID;
    }
}
