// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_167_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_16C_EVM_ADDRESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersTranslator.TRANSFER_NFT;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersTranslator.TRANSFER_NFTS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersTranslator.TRANSFER_TOKEN;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.ClassicTransfersTranslator.TRANSFER_TOKENS;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.isLongZeroAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.numberOfLongZero;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.scope.AccountAuthorizingVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAttemptOptions;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;

/**
 * Manages the call attempted by a {@link Bytes} payload received by the {@link HtsSystemContract}. Translates a valid
 * attempt into an appropriate {@link Call} subclass, giving the {@link Call} everything it will need to execute.
 */
public class HtsCallAttempt extends AbstractCallAttempt<HtsCallAttempt> {
    private static final Set<Address> HTS_ADDRESSES =
            Set.of(Address.fromHexString(HTS_167_EVM_ADDRESS), Address.fromHexString(HTS_16C_EVM_ADDRESS));

    public static final Function LEGACY_REDIRECT_FOR_TOKEN = new Function("redirectForToken(address,bytes)");

    // The account supplying authority for the classic transfer methods that support
    // qualified delegates. Primitive contract-key activation still uses this identity
    // for other methods, but their self-account authority belongs to the EVM sender.
    private final AccountID authorizingId;

    @Nullable
    private final Token redirectToken;

    public HtsCallAttempt(@NonNull final Bytes input, @NonNull final CallAttemptOptions<HtsCallAttempt> options) {
        super(input, options, HTS_ADDRESSES, LEGACY_REDIRECT_FOR_TOKEN);

        this.redirectToken = this.maybeRedirectAddress.map(this::linkedToken).orElse(null);

        this.authorizingId = (!options.authorizingAddress().equals(senderAddress()))
                ? addressIdConverter().convertSender(options.authorizingAddress())
                : senderId;
    }

    @Override
    protected HtsCallAttempt self() {
        return this;
    }

    @Override
    public @NonNull VerificationStrategy defaultVerificationStrategy() {
        final var strategy = super.defaultVerificationStrategy();
        if (strategy instanceof AccountAuthorizingVerificationStrategy scoped) {
            // Keep the qualified delegate's existing primitive-key activation rules, while
            // matching account authority to the method's actual caller semantics.
            final var accountAuthority =
                    isSelector(TRANSFER_TOKENS, TRANSFER_TOKEN, TRANSFER_NFTS, TRANSFER_NFT) ? authorizingId : senderId;
            return new AccountAuthorizingVerificationStrategy(accountAuthority, scoped.primitiveStrategy());
        }
        return strategy;
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
        return linkedToken(tokenAddress.getBytes().toArray());
    }

    /**
     * Returns the token at the given EVM address, if it exists.
     *
     * @param evmAddress the headlong address of the token to look up
     * @return the token that is the target of this redirect, or null if it didn't exist
     */
    public @Nullable Token linkedToken(@NonNull final byte[] evmAddress) {
        requireNonNull(evmAddress);
        if (isLongZeroAddress(evmAddress)) {
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
