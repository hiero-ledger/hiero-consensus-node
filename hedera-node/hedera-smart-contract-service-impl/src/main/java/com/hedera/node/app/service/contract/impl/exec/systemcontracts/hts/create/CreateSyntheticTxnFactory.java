// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenSupplyType;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody.Builder;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenCreateWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenCreateWrapper.FixedFeeWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenCreateWrapper.FractionalFeeWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenCreateWrapper.RoyaltyFeeWrapper;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenKeyUtils;
import com.hedera.node.app.service.contract.impl.exec.utils.TokenTransactionBodyWrapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.inject.Singleton;

/**
 * A factory for creating a {@link TokenCreateTransactionBody.Builder}.
 */
@Singleton
public class CreateSyntheticTxnFactory {

    private CreateSyntheticTxnFactory() {
        // Singleton constructor
    }

    /**
     * @param tokenCreateWrapper the wrapper of token create transaction
     * @return the body of the token create transaction
     */
    @NonNull
    public static TokenTransactionBodyWrapper createToken(
            @NonNull final TokenCreateWrapper tokenCreateWrapper, final boolean withMetadata) {
        final var txnBodyBuilder = TokenCreateTransactionBody.newBuilder();
        txnBodyBuilder
                .name(tokenCreateWrapper.getName())
                .symbol(tokenCreateWrapper.getSymbol())
                .decimals(tokenCreateWrapper.getDecimals())
                .tokenType(tokenCreateWrapper.isFungible() ? TokenType.FUNGIBLE_COMMON : TokenType.NON_FUNGIBLE_UNIQUE)
                .supplyType(tokenCreateWrapper.isSupplyTypeFinite() ? TokenSupplyType.FINITE : TokenSupplyType.INFINITE)
                .maxSupply(tokenCreateWrapper.getMaxSupply())
                .initialSupply(tokenCreateWrapper.getInitSupply())
                .freezeDefault(tokenCreateWrapper.isFreezeDefault())
                .memo(tokenCreateWrapper.getMemo());

        // checks for treasury
        if (tokenCreateWrapper.getTreasury() != null) {
            txnBodyBuilder.treasury(tokenCreateWrapper.getTreasury());
        }

        // Set keys if they exist
        final var preemptingStatus =
                TokenKeyUtils.addTokenKeys(tokenCreateWrapper.getTokenKeys(), withMetadata, txnBodyBuilder);

        // Set expiry details
        setExpiry(tokenCreateWrapper, txnBodyBuilder);

        // Add custom fees
        addCustomFees(tokenCreateWrapper, txnBodyBuilder);

        if (withMetadata) {
            setMetadata(tokenCreateWrapper, txnBodyBuilder);
        }

        return new TokenTransactionBodyWrapper(
                TransactionBody.newBuilder().tokenCreation(txnBodyBuilder).build(), preemptingStatus);
    }

    private static void setExpiry(
            @NonNull final TokenCreateWrapper tokenCreateWrapper, @NonNull final Builder txnBodyBuilder) {
        if (tokenCreateWrapper.getExpiry().second() != 0) {
            txnBodyBuilder.expiry(Timestamp.newBuilder()
                    .seconds(tokenCreateWrapper.getExpiry().second())
                    .build());
        }
        if (tokenCreateWrapper.getExpiry().autoRenewAccount() != null) {
            txnBodyBuilder.autoRenewAccount(tokenCreateWrapper.getExpiry().autoRenewAccount());
        }
        if (tokenCreateWrapper.getExpiry().autoRenewPeriod() != null) {
            txnBodyBuilder.autoRenewPeriod(tokenCreateWrapper.getExpiry().autoRenewPeriod());
        }
    }

    private static void setMetadata(
            @NonNull final TokenCreateWrapper tokenCreateWrapper, final Builder txnBodyBuilder) {
        if (tokenCreateWrapper.getMetadata() != null) {
            txnBodyBuilder.metadata(tokenCreateWrapper.getMetadata());
        }
    }

    private static void addCustomFees(
            @NonNull final TokenCreateWrapper tokenCreateWrapper, @NonNull final Builder txnBodyBuilder) {
        final var fractionalFees =
                tokenCreateWrapper.getFractionalFees().stream().map(FractionalFeeWrapper::asGrpc);
        final var fixedFees = tokenCreateWrapper.getFixedFees().stream().map(FixedFeeWrapper::asGrpc);
        final var royaltyFees = tokenCreateWrapper.getRoyaltyFees().stream().map(RoyaltyFeeWrapper::asGrpc);

        var allFees = Stream.of(fixedFees, fractionalFees, royaltyFees)
                .flatMap(Function.identity())
                .toList();

        if (!allFees.isEmpty()) {
            txnBodyBuilder.customFees(allFees);
        }
    }
}
