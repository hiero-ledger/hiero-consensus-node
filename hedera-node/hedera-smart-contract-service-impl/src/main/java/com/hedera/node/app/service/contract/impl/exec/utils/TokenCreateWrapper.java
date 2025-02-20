// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import static com.hedera.node.app.hapi.utils.ValidationUtils.validateTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Fraction;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.FractionalFee;
import com.hedera.hapi.node.transaction.RoyaltyFee;
import com.hedera.node.app.hapi.utils.InvalidTransactionException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.List;

public class TokenCreateWrapper {
    private final boolean isFungible;
    private final String name;
    private final String symbol;
    private final AccountID treasury;
    private final boolean isSupplyTypeFinite;
    private final long initSupply;
    private final int decimals;
    private final long maxSupply;
    private final String memo;
    private final boolean isFreezeDefault;
    private final List<TokenKeyWrapper> tokenKeys;
    private final TokenExpiryWrapper expiry;

    private List<FixedFeeWrapper> fixedFees;
    private List<FractionalFeeWrapper> fractionalFees;
    private List<RoyaltyFeeWrapper> royaltyFees;
    private Bytes metadata;

    public TokenCreateWrapper(
            final boolean isFungible,
            final String tokenName,
            final String tokenSymbol,
            final AccountID tokenTreasury,
            final String memo,
            final Boolean isSupplyTypeFinite,
            final long initSupply,
            final int decimals,
            final long maxSupply,
            final Boolean isFreezeDefault,
            final List<TokenKeyWrapper> tokenKeys,
            final TokenExpiryWrapper tokenExpiry) {
        this.isFungible = isFungible;
        this.name = tokenName;
        this.symbol = tokenSymbol;
        this.treasury = tokenTreasury;
        this.memo = memo;
        this.isSupplyTypeFinite = isSupplyTypeFinite;
        this.initSupply = initSupply;
        this.decimals = decimals;
        this.maxSupply = maxSupply;
        this.isFreezeDefault = isFreezeDefault;
        this.tokenKeys = tokenKeys;
        this.expiry = tokenExpiry;
        this.fixedFees = List.of();
        this.fractionalFees = List.of();
        this.royaltyFees = List.of();
        this.metadata = Bytes.EMPTY;
    }

    public boolean isFungible() {
        return isFungible;
    }

    public String getName() {
        return name;
    }

    public String getSymbol() {
        return symbol;
    }

    public AccountID getTreasury() {
        return treasury;
    }

    public boolean isSupplyTypeFinite() {
        return isSupplyTypeFinite;
    }

    public long getInitSupply() {
        return initSupply;
    }

    public int getDecimals() {
        return decimals;
    }

    public long getMaxSupply() {
        return maxSupply;
    }

    public String getMemo() {
        return memo;
    }

    public boolean isFreezeDefault() {
        return isFreezeDefault;
    }

    public List<TokenKeyWrapper> getTokenKeys() {
        return tokenKeys;
    }

    public TokenExpiryWrapper getExpiry() {
        return expiry;
    }

    public Bytes getMetadata() {
        return metadata;
    }

    public List<FixedFeeWrapper> getFixedFees() {
        return fixedFees;
    }

    public List<FractionalFeeWrapper> getFractionalFees() {
        return fractionalFees;
    }

    public List<RoyaltyFeeWrapper> getRoyaltyFees() {
        return royaltyFees;
    }

    public void setMetadata(final Bytes metadata) {
        this.metadata = metadata;
    }

    public void setFixedFees(final List<FixedFeeWrapper> fixedFees) {
        this.fixedFees = fixedFees;
    }

    public void setFractionalFees(final List<FractionalFeeWrapper> fractionalFees) {
        this.fractionalFees = fractionalFees;
    }

    public void setRoyaltyFees(final List<RoyaltyFeeWrapper> royaltyFees) {
        this.royaltyFees = royaltyFees;
    }

    public void setAllInheritedKeysTo(final Key senderKey) {
        for (final var tokenKey : tokenKeys) {
            if (tokenKey.key().isShouldInheritAccountKeySet()) {
                tokenKey.key().setInheritedKey(senderKey);
            }
        }
    }

    public static final class FixedFeeWrapper {
        public enum FixedFeePayment {
            INVALID_PAYMENT,
            USE_HBAR,
            USE_CURRENTLY_CREATED_TOKEN,
            USE_EXISTING_FUNGIBLE_TOKEN
        }

        private final long amount;
        private final TokenID tokenID;
        private final boolean useHbarsForPayment;
        private final boolean useCurrentTokenForPayment;
        private final AccountID feeCollector;

        private final FixedFeeWrapper.FixedFeePayment fixedFeePayment;

        public FixedFeeWrapper(
                final long amount,
                final TokenID tokenID,
                final boolean useHbarsForPayment,
                final boolean useCurrentTokenForPayment,
                final AccountID feeCollector) {
            this.amount = amount;
            this.tokenID = tokenID;
            this.useHbarsForPayment = useHbarsForPayment;
            this.useCurrentTokenForPayment = useCurrentTokenForPayment;
            this.feeCollector = feeCollector;
            this.fixedFeePayment = setFixedFeePaymentType();
        }

        private FixedFeeWrapper.FixedFeePayment setFixedFeePaymentType() {
            if (tokenID != null) {
                return !useHbarsForPayment && !useCurrentTokenForPayment
                        ? FixedFeeWrapper.FixedFeePayment.USE_EXISTING_FUNGIBLE_TOKEN
                        : FixedFeeWrapper.FixedFeePayment.INVALID_PAYMENT;
            } else if (useCurrentTokenForPayment) {
                return !useHbarsForPayment
                        ? FixedFeeWrapper.FixedFeePayment.USE_CURRENTLY_CREATED_TOKEN
                        : FixedFeeWrapper.FixedFeePayment.INVALID_PAYMENT;
            } else {
                return useHbarsForPayment
                        ? FixedFeeWrapper.FixedFeePayment.USE_HBAR
                        : FixedFeeWrapper.FixedFeePayment.INVALID_PAYMENT;
            }
        }

        private FixedFee.Builder asBuilder() {
            return switch (fixedFeePayment) {
                case USE_HBAR -> FixedFee.newBuilder().amount(amount);
                case USE_EXISTING_FUNGIBLE_TOKEN -> FixedFee.newBuilder()
                        .amount(amount)
                        .denominatingTokenId(tokenID);
                case USE_CURRENTLY_CREATED_TOKEN -> FixedFee.newBuilder()
                        .amount(amount)
                        .denominatingTokenId(TokenID.newBuilder()
                                .shardNum(0L)
                                .realmNum(0L)
                                .tokenNum(0L)
                                .build());
                default -> throw new InvalidTransactionException(ResponseCodeEnum.FAIL_INVALID);
            };
        }

        public FixedFeeWrapper.FixedFeePayment getFixedFeePayment() {
            return this.fixedFeePayment;
        }

        public CustomFee asGrpc() {
            final var feeBuilder = CustomFee.newBuilder().fixedFee(asBuilder().build());
            if (feeCollector != null) {
                feeBuilder.feeCollectorAccountId(feeCollector);
            }
            return feeBuilder.build();
        }
    }

    public record FractionalFeeWrapper(
            long numerator,
            long denominator,
            long minimumAmount,
            long maximumAmount,
            boolean netOfTransfers,
            AccountID feeCollector) {
        public CustomFee asGrpc() {
            final var feeBuilder = CustomFee.newBuilder()
                    .fractionalFee(FractionalFee.newBuilder()
                            .fractionalAmount(Fraction.newBuilder()
                                    .numerator(numerator)
                                    .denominator(denominator)
                                    .build())
                            .minimumAmount(minimumAmount)
                            .maximumAmount(maximumAmount)
                            .netOfTransfers(netOfTransfers)
                            .build());
            if (feeCollector != null) {
                feeBuilder.feeCollectorAccountId(feeCollector);
            }
            return feeBuilder.build();
        }
    }

    public record RoyaltyFeeWrapper(
            long numerator, long denominator, FixedFeeWrapper fallbackFixedFee, AccountID feeCollector) {
        public CustomFee asGrpc() {
            final var royaltyFeeBuilder = RoyaltyFee.newBuilder()
                    .exchangeValueFraction(Fraction.newBuilder()
                            .numerator(numerator)
                            .denominator(denominator)
                            .build());
            if (fallbackFixedFee != null) {
                validateTrue(
                        fallbackFixedFee.getFixedFeePayment() != FixedFeeWrapper.FixedFeePayment.INVALID_PAYMENT,
                        ResponseCodeEnum.FAIL_INVALID);
                royaltyFeeBuilder.fallbackFee(fallbackFixedFee.asBuilder().build());
            }

            final var customFeeBuilder = CustomFee.newBuilder().royaltyFee(royaltyFeeBuilder.build());
            if (feeCollector != null) {
                customFeeBuilder.feeCollectorAccountId(feeCollector);
            }
            return customFeeBuilder.build();
        }
    }
}
