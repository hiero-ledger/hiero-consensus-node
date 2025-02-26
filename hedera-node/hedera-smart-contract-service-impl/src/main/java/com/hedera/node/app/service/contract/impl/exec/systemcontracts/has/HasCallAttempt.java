// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AddressIdConverter;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.SystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;

/**
 * Manages the call attempted by a {@link Bytes} payload received by the {@link HasSystemContract}.
 * Translates a valid attempt into an appropriate {@link AbstractCall} subclass, giving the {@link Call}
 * everything it will need to execute.
 */
public class HasCallAttempt extends AbstractCallAttempt<HasCallAttempt> {
    /** Selector for redirectForAccount(address,bytes) method. */
    public static final Function REDIRECT_FOR_ACCOUNT = new Function("redirectForAccount(address,bytes)");

    @Nullable
    private final Account redirectAccount;

    @NonNull
    private final SignatureVerifier signatureVerifier;

    // too many parameters
    @SuppressWarnings("java:S107")
    public HasCallAttempt(
            @NonNull final ContractID contractID,
            @NonNull final Bytes input,
            @NonNull final Address senderAddress,
            final boolean onlyDelegatableContractKeysActive,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final Configuration configuration,
            @NonNull final AddressIdConverter addressIdConverter,
            @NonNull final VerificationStrategies verificationStrategies,
            @NonNull final SignatureVerifier signatureVerifier,
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final List<CallTranslator<HasCallAttempt>> callTranslators,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            final boolean isStaticCall) {
        super(
                contractID,
                input,
                senderAddress,
                senderAddress,
                onlyDelegatableContractKeysActive,
                enhancement,
                configuration,
                addressIdConverter,
                verificationStrategies,
                gasCalculator,
                callTranslators,
                isStaticCall,
                systemContractMethodRegistry,
                REDIRECT_FOR_ACCOUNT);
        if (isRedirect()) {
            this.redirectAccount = linkedAccount(requireNonNull(redirectAddress));
        } else {
            this.redirectAccount = null;
        }
        this.signatureVerifier = requireNonNull(signatureVerifier);
    }

    @Override
    protected SystemContract systemContractKind() {
        return SystemContractMethod.SystemContract.HAS;
    }

    @Override
    protected HasCallAttempt self() {
        return this;
    }

    /**
     * Returns whether this is a account redirect.
     *
     * @return whether this is a account redirect
     * @throws IllegalStateException if this is not a valid call
     */
    public boolean isAccountRedirect() {
        return isRedirect();
    }

    /**
     * Returns the account that is the target of this redirect, if it existed.
     *
     * @return the account that is the target of this redirect, or null if it didn't exist
     * @throws IllegalStateException if this is not an account redirect
     */
    public @Nullable Account redirectAccount() {
        if (!isRedirect()) {
            throw new IllegalStateException("Not an account redirect");
        }
        return redirectAccount;
    }

    /**
     * Returns the id of the account that is the target of this redirect, if it existed.
     *
     * @return the id of the account that is the target of this redirect, or null if it didn't exist
     * @throws IllegalStateException if this is not an account redirect
     */
    public @Nullable AccountID redirectAccountId() {
        if (!isRedirect()) {
            throw new IllegalStateException("Not a account redirect");
        }
        return redirectAccount == null ? null : redirectAccount.accountId();
    }

    /**
     * Returns the account at the given Besu address, if it exists.
     *
     * @param accountAddress the Besu address of the account to look up
     * @return the account that is the target of this redirect, or null if it didn't exist
     */
    public @Nullable Account linkedAccount(@NonNull final Address accountAddress) {
        requireNonNull(accountAddress);
        if (isLongZero(enhancement().nativeOperations().entityIdFactory(), accountAddress)) {
            return enhancement.nativeOperations().getAccount(numberOfLongZero(accountAddress.toArray()));
        } else {
            final var addressNum = enhancement
                    .nativeOperations()
                    .resolveAlias(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(accountAddress.toArray()));
            return enhancement.nativeOperations().getAccount(addressNum);
        }
    }

    public @NonNull SignatureVerifier signatureVerifier() {
        return signatureVerifier;
    }
}
