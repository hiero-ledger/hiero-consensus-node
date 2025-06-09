// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZero;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HasSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAttemptOptions;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.SystemContract;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.config.data.HederaConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
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

    public HasCallAttempt(
            @NonNull final Bytes input,
            @NonNull final CallAttemptOptions<HasCallAttempt> options,
            @NonNull final SignatureVerifier signatureVerifier) {
        super(input, options, REDIRECT_FOR_ACCOUNT);
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
        if (isLongZero(accountAddress)) {
            return enhancement()
                    .nativeOperations()
                    .getAccount(nativeOperations()
                            .entityIdFactory()
                            .newAccountId(numberOfLongZero(accountAddress.toArray())));
        } else {
            final var config = configuration().getConfigData(HederaConfig.class);
            final var addressNum = enhancement()
                    .nativeOperations()
                    .resolveAlias(
                            config.shard(),
                            config.realm(),
                            com.hedera.pbj.runtime.io.buffer.Bytes.wrap(accountAddress.toArray()));
            return enhancement()
                    .nativeOperations()
                    .getAccount(nativeOperations().entityIdFactory().newAccountId(addressNum));
        }
    }

    public @NonNull SignatureVerifier signatureVerifier() {
        return signatureVerifier;
    }
}
