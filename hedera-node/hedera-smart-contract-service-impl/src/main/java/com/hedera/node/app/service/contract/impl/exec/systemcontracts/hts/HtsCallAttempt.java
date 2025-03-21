// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZeroAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAttemptOptions;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.SystemContract;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;

/**
 * Manages the call attempted by a {@link Bytes} payload received by the {@link HtsSystemContract}. Translates a valid
 * attempt into an appropriate {@link Call} subclass, giving the {@link Call} everything it will need to execute.
 */
public class HtsCallAttempt extends AbstractCallAttempt<HtsCallAttempt> {
    /** Selector for redirectForToken(address,bytes) method. */
    public static final Function REDIRECT_FOR_TOKEN = new Function("redirectForToken(address,bytes)");

    // The id address of the account authorizing the call, in the sense
    // that (1) a dispatch should omit the key of this account from the
    // set of required signing keys; and (2) the verification strategy
    // for this call should use this authorizing address. We only need
    // this because we will still have two contracts on the qualified
    // delegates list, so it is possible the authorizing account can be
    // different from the EVM sender address
    private final AccountID authorizingId;

    @Nullable
    private final Token redirectToken;

    public HtsCallAttempt(@NonNull final Bytes input, @NonNull final CallAttemptOptions<HtsCallAttempt> options) {
        super(input, options, REDIRECT_FOR_TOKEN);
        if (isRedirect()) {
            this.redirectToken = linkedToken(redirectAddress);
        } else {
            redirectToken = null;
        }
        this.authorizingId = (options.authorizingAddress() != senderAddress())
                ? addressIdConverter().convertSender(options.authorizingAddress())
                : senderId;
    }

    @Override
    protected SystemContract systemContractKind() {
        return SystemContractMethod.SystemContract.HTS;
    }

    @Override
    protected HtsCallAttempt self() {
        return this;
    }

    /**
     * Returns the token that is the target of this redirect, if it existed.
     *
     * @return the token that is the target of this redirect, or null if it didn't exist
     * @throws IllegalStateException if this is not a token redirect
     */
    public @Nullable Token redirectToken() {
        if (!isRedirect()) {
            throw new IllegalStateException("Not a token redirect");
        }
        return redirectToken;
    }

    /**
     * Returns the id of the token that is the target of this redirect, if it existed.
     *
     * @return the id of the token that is the target of this redirect, or null if it didn't exist
     * @throws IllegalStateException if this is not a token redirect
     */
    public @Nullable TokenID redirectTokenId() {
        if (!isRedirect()) {
            throw new IllegalStateException("Not a token redirect");
        }
        return redirectToken == null ? null : redirectToken.tokenId();
    }

    /**
     * Returns the type of the token that is the target of this redirect, if it existed.
     *
     * @return the type of the token that is the target of this redirect, or null if it didn't exist
     * @throws IllegalStateException if this is not a token redirect
     */
    public @Nullable TokenType redirectTokenType() {
        if (!isRedirect()) {
            throw new IllegalStateException("Not a token redirect");
        }
        return redirectToken == null ? null : redirectToken.tokenType();
    }

    /**
     * Returns the token at the given Besu address, if it exists.
     *
     * @param tokenAddress the Besu address of the token to look up
     * @return the token that is the target of this redirect, or null if it didn't exist
     */
    public @Nullable Token linkedToken(@NonNull final Address tokenAddress) {
        requireNonNull(tokenAddress);
        return linkedToken(tokenAddress.toArray());
    }

    /**
     * Returns the token at the given EVM address, if it exists.
     *
     * @param evmAddress the headlong address of the token to look up
     * @return the token that is the target of this redirect, or null if it didn't exist
     */
    public @Nullable Token linkedToken(@NonNull final byte[] evmAddress) {
        requireNonNull(evmAddress);
        if (isLongZeroAddress(enhancement().nativeOperations().entityIdFactory(), evmAddress)) {
            return enhancement()
                    .nativeOperations()
                    .getToken(nativeOperations().entityIdFactory().newTokenId(numberOfLongZero(evmAddress)));
        } else {
            // No point in looking up a token that can't exist
            return null;
        }
    }

    /**
     * Returns the ID of the sender of this call in the EVM frame.
     *
     * @return the ID of the sender of this call in the EVM frame
     */
    public AccountID authorizingId() {
        return authorizingId;
    }
}
