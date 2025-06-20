// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.common;

import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
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
import java.util.Optional;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;

/**
 * Base class for HTS and HAS system contract call attempts.
 * @param <T> the type of the abstract call attempt
 */
public abstract class AbstractCallAttempt<T extends AbstractCallAttempt<T>> {

    protected final CallAttemptOptions<T> options;
    // The id of the sender in the EVM frame
    protected final AccountID senderId;
    protected final Bytes input;
    protected final byte[] selector;
    // If non-null, the address of a non-contract entity (e.g., account or token) whose
    // "bytecode" redirects all calls to a system contract address, and was determined
    // to be the redirecting entity for this call attempt
    protected @Nullable final Address redirectAddress;

    /**
     * @param input the input in bytes
     * @param options the AbstractCallAttempt parameters and options
     * @param redirectFunction the redirect function
     */
    public AbstractCallAttempt(
            // we are keeping the 'input' out of the 'options' for not duplicate and keep close to related params
            @NonNull final Bytes input,
            @NonNull final CallAttemptOptions<T> options,
            @NonNull final Function redirectFunction) {
        requireNonNull(input);
        requireNonNull(redirectFunction);
        this.options = requireNonNull(options);
        this.senderId = options.addressIdConverter().convertSender(options.senderAddress());

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
        return options.verificationStrategies()
                .activatingOnlyContractKeysFor(
                        options.authorizingAddress(),
                        options.onlyDelegatableContractKeysActive(),
                        options.enhancement().nativeOperations());
    }

    /**
     * Returns the updater enhancement this call was attempted within.
     *
     * @return the updater enhancement this call was attempted within
     */
    public @NonNull HederaWorldUpdater.Enhancement enhancement() {
        return options.enhancement();
    }

    /**
     * Returns the system contract gas calculator for this call.
     *
     * @return the system contract gas calculator for this call
     */
    public @NonNull SystemContractGasCalculator systemContractGasCalculator() {
        return options.gasCalculator();
    }

    /**
     * Returns the native operations this call was attempted within.
     *
     * @return the native operations this call was attempted within
     */
    public @NonNull HederaNativeOperations nativeOperations() {
        return options.enhancement().nativeOperations();
    }

    /**
     * Tries to translate this call attempt into a {@link Call} from the given sender address.
     *
     * @return the executable call, or null if this attempt can't be translated to one
     */
    public @Nullable Call asExecutableCall() {
        final var self = self();
        for (final var translator : options.callTranslators()) {
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
        return options.senderAddress();
    }

    /**
     * Returns the address ID converter for this call.
     *
     * @return the address ID converter for this call
     */
    public AddressIdConverter addressIdConverter() {
        return options.addressIdConverter();
    }

    /**
     * Returns the configuration for this call.
     *
     * @return the configuration for this call
     */
    public Configuration configuration() {
        return options.configuration();
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
        return options.isStaticCall();
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
            if (Arrays.equals(method.selector(), this.selector()) && method.hasSupportedAddress(options.contractID())) {
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
        return options.onlyDelegatableContractKeysActive();
    }

    /**
     * Returns the system contract method registry for this call.
     *
     * @return the system contract method registry for this call
     */
    public SystemContractMethodRegistry getSystemContractMethodRegistry() {
        return options.systemContractMethodRegistry();
    }

    /**
     * Returns the target system contract ID of this call.
     *
     * @return the target system contract ID of this call
     */
    public ContractID systemContractID() {
        return options.contractID();
    }
}
