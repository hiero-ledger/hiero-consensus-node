// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_TOKEN_TRANSFER_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_TOKEN_TRANSFER_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema.AIRDROPS_STATE_ID;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AirDropTransferType.NFT_AIRDROP;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AirDropTransferType.TOKEN_AIRDROP;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AirDropTransferType.TOKEN_AND_NFT_AIRDROP;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Fraction;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.PendingAirdropValue;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.hapi.node.token.TokenAirdropTransactionBody;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.FractionalFee;
import com.hedera.hapi.node.transaction.PendingAirdropRecord;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.FeeContextImpl;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableAirdropStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.TokenAirdropHandler;
import com.hedera.node.app.service.token.records.TokenAirdropStreamBuilder;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.TokensConfig;
import com.swirlds.config.api.Configuration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class TokenAirdropHandlerTest extends CryptoTransferHandlerTestBase {

    private static final int MAX_TOKEN_TRANSFERS = 10;

    @Mock
    private Configuration config;

    @Mock
    private TokensConfig tokensConfig;

    @Mock
    private FeeContextImpl feeContext;

    @Mock
    private TransactionBody transactionBody;

    @Mock
    private FeeCalculatorFactory feeCalculatorFactory;

    @Mock
    private FeeCalculator feeCalculator;

    @Mock
    private PureChecksContext pureChecksContext;

    @SuppressWarnings("DataFlowIssue")
    @Test
    void pureChecksNullArgThrows() {
        Assertions.assertThatThrownBy(() -> tokenAirdropHandler.pureChecks(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void pureChecksFungibleTokenTransfersHasMissingTokenId() {
        final var txn = newTokenAirdrop(TokenTransferList.newBuilder()
                .token((TokenID) null)
                .transfers(ACCT_4444_MINUS_5, ACCT_3333_PLUS_5)
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> tokenAirdropHandler.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TOKEN_ID));
    }

    @Test
    void pureChecksFungibleTokenTransfersHasMissingAccountId() {
        final var txn = newTokenAirdrop(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                // These are TOKEN fungible amount transfers, not HBAR amount transfers
                .transfers(
                        ACCT_3333_MINUS_10,
                        ACCT_4444_PLUS_10
                                .copyBuilder()
                                .accountID((AccountID) null)
                                .build())
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> tokenAirdropHandler.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID));
    }

    @Test
    void pureChecksFungibleTokenTransfersHasRepeatedAccountId() {
        final var txn = newTokenAirdrop(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                .transfers(
                        ACCT_4444_MINUS_5,
                        ACCT_4444_MINUS_5.copyBuilder().amount(5).build())
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> tokenAirdropHandler.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS));
    }

    @Test
    void pureChecksFungibleTokenTransfersHasNonZeroTokenSum() {
        final var txn = newTokenAirdrop(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                .transfers(
                        ACCT_3333_MINUS_10,
                        ACCT_4444_PLUS_10.copyBuilder().amount(5).build())
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> tokenAirdropHandler.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN));
    }

    @Test
    void pureChecksHasValidFungibleTokenTransfers() {
        final var txn = newTokenAirdrop(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                .transfers(ACCT_4444_MINUS_5, ACCT_3333_PLUS_5)
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatCode(() -> tokenAirdropHandler.pureChecks(pureChecksContext))
                .doesNotThrowAnyException();
    }

    @Test
    void pureChecksNonFungibleTokenTransfersHasMissingTokenId() {
        final var txn = newTokenAirdrop(TokenTransferList.newBuilder()
                .token((TokenID) null)
                // These are nft transfers, not hbar or fungible token transfers
                .nftTransfers(SERIAL_1_FROM_3333_TO_4444)
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> tokenAirdropHandler.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TOKEN_ID));
    }

    @Test
    void pureChecksNonFungibleTokenTransfersHasInvalidNftId() {
        final var txn = newTokenAirdrop(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                // These are nft transfers, not hbar or fungible token transfers
                .nftTransfers(
                        SERIAL_1_FROM_3333_TO_4444.copyBuilder().serialNumber(0).build())
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> tokenAirdropHandler.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER));
    }

    @Test
    void pureChecksNonFungibleTokenTransfersHasMissingSenderId() {
        final var txn = newTokenAirdrop(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                // These are nft transfers, not hbar or fungible token transfers
                .nftTransfers(SERIAL_2_FROM_4444_TO_3333
                        .copyBuilder()
                        .senderAccountID((AccountID) null)
                        .build())
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> tokenAirdropHandler.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID));
    }

    @Test
    void pureChecksNonFungibleTokenTransfersHasMissingReceiverId() {
        final var txn = newTokenAirdrop(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                .nftTransfers(SERIAL_1_FROM_3333_TO_4444
                        .copyBuilder()
                        .receiverAccountID((AccountID) null)
                        .build())
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> tokenAirdropHandler.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID));
    }

    @Test
    void pureChecksNonFungibleTokenTransfersHasRepeatedNftId() {
        final var txn = newTokenAirdrop(
                TokenTransferList.newBuilder()
                        .token(TOKEN_2468)
                        .nftTransfers(SERIAL_1_FROM_3333_TO_4444)
                        .build(),
                TokenTransferList.newBuilder()
                        .token(TOKEN_2468)
                        .nftTransfers(SERIAL_2_FROM_4444_TO_3333)
                        .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> tokenAirdropHandler.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.TOKEN_ID_REPEATED_IN_TOKEN_LIST));
    }

    @Test
    void pureChecksHasValidNonFungibleTokenTransfers() {
        // Note: this test only checks for valid non-fungible token transfers (WITHOUT any hbar or fungible token
        // transfers)
        final var txn = newTokenAirdrop(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                .nftTransfers(
                        SERIAL_1_FROM_3333_TO_4444,
                        SERIAL_1_FROM_3333_TO_4444.copyBuilder().serialNumber(3).build())
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatCode(() -> tokenAirdropHandler.pureChecks(pureChecksContext))
                .doesNotThrowAnyException();
    }

    @Test
    void pureChecksTokenTransfersDoesNotHaveFungibleOrNonFungibleAmount() {
        // This test checks that, if any token transfer is present, it must have at least one fungible or non-fungible
        // balance not equal to zero
        final var txn = newTokenAirdrop(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                // transfers and nftTransfers are intentionally empty (will result in a count of zero transfers)
                .transfers()
                .nftTransfers()
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> tokenAirdropHandler.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(EMPTY_TOKEN_TRANSFER_ACCOUNT_AMOUNTS));
    }

    @Test
    void pureChecksTokenTransferHasBothFungibleAndNonFungibleAmounts() {
        // This test checks that, if a transfer for a token is present, it must have ONLY a fungible transfer OR an NFT
        // transfer, but not both
        final var txn = newTokenAirdrop(TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                .transfers(ACCT_3333_MINUS_10, ACCT_4444_PLUS_10)
                .nftTransfers(SERIAL_1_FROM_3333_TO_4444, SERIAL_1_FROM_3333_TO_4444)
                .build());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatThrownBy(() -> tokenAirdropHandler.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_ACCOUNT_AMOUNTS));
    }

    @Test
    void pureChecksForEmptyHbarTransferAndEmptyTokenTransfers() {
        // It's actually valid to have no token transfers
        final var txn = newTokenAirdrop(Collections.emptyList());
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatCode(() -> tokenAirdropHandler.pureChecks(pureChecksContext))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(EMPTY_TOKEN_TRANSFER_BODY));
    }

    @Test
    void pureChecksHasValidHbarAndTokenTransfers() {
        // Tests that valid fungible transfers, and non-fungible transfers are all valid when given
        // together
        final var token9753 = asToken(9753);
        final var token9754 = asToken(9754);
        final var txn = newTokenAirdrop(List.of(
                // Valid fungible token transfers
                TokenTransferList.newBuilder()
                        .token(TOKEN_2468)
                        .transfers(ACCT_4444_MINUS_5, ACCT_3333_PLUS_5)
                        .build(),
                TokenTransferList.newBuilder()
                        .token(token9754)
                        .transfers(ACCT_4444_MINUS_5, ACCT_3333_PLUS_5)
                        .build(),
                // Valid nft token transfers
                TokenTransferList.newBuilder()
                        .token(token9753)
                        .nftTransfers(SERIAL_1_FROM_3333_TO_4444, SERIAL_2_FROM_3333_TO_4444)
                        .build()));
        given(pureChecksContext.body()).willReturn(txn);

        Assertions.assertThatCode(() -> tokenAirdropHandler.pureChecks(pureChecksContext))
                .doesNotThrowAnyException();
    }

    @Test
    void handleAirdropMultipleTokensToPendingState() {
        givenStoresAndConfig(handleContext);
        tokenAirdropHandler = new TokenAirdropHandler(tokenAirdropValidator, validator);
        given(handleContext.savepointStack()).willReturn(stack);
        given(handleContext.dispatchMetadata()).willReturn(DispatchMetadata.EMPTY_METADATA);
        given(stack.getBaseBuilder(TokenAirdropStreamBuilder.class)).willReturn(tokenAirdropRecordBuilder);
        var tokenWithNoCustomFees =
                fungibleToken.copyBuilder().customFees(Collections.emptyList()).build();
        var nftWithNoCustomFees = nonFungibleToken
                .copyBuilder()
                .customFees(Collections.emptyList())
                .build();
        writableTokenStore.putAndIncrementCount(tokenWithNoCustomFees);
        writableTokenStore.putAndIncrementCount(nftWithNoCustomFees);
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);
        givenAirdropTxn();

        given(handleContext.dispatch(
                        argThat(options -> TokenAirdropStreamBuilder.class.equals(options.streamBuilderType())
                                && payerId.equals(options.payerId()))))
                .will(invocation -> {
                    var pendingAirdropId = PendingAirdropId.newBuilder().build();
                    var pendingAirdropValue = PendingAirdropValue.newBuilder().build();
                    var pendingAirdropRecord = PendingAirdropRecord.newBuilder()
                            .pendingAirdropId(pendingAirdropId)
                            .pendingAirdropValue(pendingAirdropValue)
                            .build();

                    return tokenAirdropRecordBuilder.addPendingAirdrop(pendingAirdropRecord);
                });

        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        var sigVerificationMock = mock(SignatureVerification.class);
        given(keyVerifier.verificationFor(any())).willReturn(sigVerificationMock);
        given(handleContext.keyVerifier()).willReturn(keyVerifier);
        given(handleContext.feeCalculatorFactory()).willReturn(feeCalculatorFactory);
        given(feeCalculatorFactory.feeCalculator(SubType.DEFAULT)).willReturn(feeCalculator);
        given(feeCalculator.calculate()).willReturn(new Fees(10, 10, 10));
        given(handleContext.tryToChargePayer(anyLong())).willReturn(true);

        tokenAirdropHandler.handle(handleContext);

        assertThat(writableAirdropStore.sizeOfState()).isEqualTo(2);
        assertThat(writableAccountStore.get(ownerId)).isNotNull();
        var ownerAccount = Objects.requireNonNull(writableAccounts.get(ownerId));
        assertThat(ownerAccount.hasHeadPendingAirdropId()).isTrue();
        assertThat(ownerAccount.numberPendingAirdrops()).isEqualTo(2);
        var headPendingAirdropId = ownerAccount.headPendingAirdropId();
        var headAirdrop = writableAirdropStore.get(Objects.requireNonNull(headPendingAirdropId));
        assertThat(Objects.requireNonNull(headAirdrop).hasNextAirdrop()).isTrue();
        var nextAirdrop = writableAirdropStore.get(Objects.requireNonNull(headAirdrop.nextAirdrop()));
        assertThat(Objects.requireNonNull(nextAirdrop).hasNextAirdrop()).isFalse();
        assertThat(nextAirdrop.hasPreviousAirdrop()).isTrue();
        assertThat(nextAirdrop.previousAirdrop()).isEqualTo(headPendingAirdropId);
    }

    @Test
    void tokenTransfersAboveMax() {
        givenStoresAndConfig(handleContext);
        tokenAirdropHandler = new TokenAirdropHandler(tokenAirdropValidator, validator);
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getBaseBuilder(TokenAirdropStreamBuilder.class)).willReturn(tokenAirdropRecordBuilder);
        var nftWithNoCustomFees = nonFungibleToken
                .copyBuilder()
                .customFees(Collections.emptyList())
                .build();
        writableTokenStore.put(nftWithNoCustomFees);
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);

        // airdropping more then 10 airdrops
        final var txn = TokenAirdropTransactionBody.newBuilder()
                .tokenTransfers(transactionBodyAboveMaxTransferLimit())
                .build();
        givenAirdropTxn(txn, payerId);

        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(handleContext.feeCalculatorFactory()).willReturn(feeCalculatorFactory);
        given(handleContext.tryToChargePayer(anyLong())).willReturn(true);

        Assertions.assertThatThrownBy(() -> tokenAirdropHandler.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(BATCH_SIZE_LIMIT_EXCEEDED));
    }

    @Test
    void handleAirdropMultipleTokensTransfers() {
        // setup all states
        handlerTestBaseInternalSetUp(true);

        // setup airdrop states
        givenAssociatedReceiver(tokenReceiverId, nonFungibleTokenId);
        givenAssociatedReceiver(tokenReceiverId, fungibleTokenId);
        refreshReadableStores();
        refreshWritableStores();
        givenStoresAndConfig(handleContext);
        given(handleContext.dispatchMetadata()).willReturn(DispatchMetadata.EMPTY_METADATA);
        // mock record builder
        tokenAirdropHandler = new TokenAirdropHandler(tokenAirdropValidator, validator);
        var tokenWithNoCustomFees =
                fungibleToken.copyBuilder().customFees(Collections.emptyList()).build();
        var nftWithNoCustomFees = nonFungibleToken
                .copyBuilder()
                .customFees(Collections.emptyList())
                .build();
        writableTokenStore.put(tokenWithNoCustomFees);
        writableTokenStore.put(nftWithNoCustomFees);
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);

        // set up transaction and context
        givenAirdropTxn(true, ownerId, TOKEN_AND_NFT_AIRDROP);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);
        given(handleContext.feeCalculatorFactory()).willReturn(feeCalculatorFactory);
        given(feeCalculatorFactory.feeCalculator(SubType.DEFAULT)).willReturn(feeCalculator);
        given(feeCalculator.calculate()).willReturn(new Fees(10, 10, 10));
        given(handleContext.tryToChargePayer(anyLong())).willReturn(true);
        tokenAirdropHandler.handle(handleContext);

        assertThat(writableAccountStore.get(tokenReceiverId)).isNotNull();
        var relationToFungible = Objects.requireNonNull(writableTokenRelStore.get(tokenReceiverId, fungibleTokenId));
        var relationToNFT = Objects.requireNonNull(writableTokenRelStore.get(tokenReceiverId, nonFungibleTokenId));
        assertThat(relationToNFT.balance()).isEqualTo(1);
        assertThat(relationToFungible.balance()).isEqualTo(1000L);
    }

    @Test
    void handleAirdropWithCustomFees() {
        // setup all states
        handlerTestBaseInternalSetUp(true);

        // setup airdrop states
        givenAssociatedReceiver(tokenReceiverId, nonFungibleTokenId);
        givenAssociatedReceiver(tokenReceiverId, fungibleTokenId);
        refreshReadableStores();
        refreshWritableStores();
        givenStoresAndConfig(handleContext);
        given(handleContext.dispatchMetadata()).willReturn(DispatchMetadata.EMPTY_METADATA);
        // mock record builder
        tokenAirdropHandler = new TokenAirdropHandler(tokenAirdropValidator, validator);

        final var fees = List.of(
                withFractionalFee(FractionalFee.newBuilder()
                        .fractionalAmount(Fraction.newBuilder()
                                .numerator(1)
                                .denominator(2)
                                .build())
                        .build()),
                withFixedFee(FixedFee.newBuilder().amount(66).build()));

        // token with custom fees
        final var tokenWithCustomFees =
                fungibleToken.copyBuilder().customFees(fees).build();

        final var nftWithNoCustomFees = nonFungibleToken
                .copyBuilder()
                .customFees(Collections.emptyList())
                .build();
        writableTokenStore.put(tokenWithCustomFees);
        writableTokenStore.put(nftWithNoCustomFees);
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);

        // set up transaction and context
        givenAirdropTxn(true, ownerId, TOKEN_AND_NFT_AIRDROP);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);

        var sigVerificationMock = mock(SignatureVerification.class);
        given(keyVerifier.verificationFor(any())).willReturn(sigVerificationMock);
        given(handleContext.keyVerifier()).willReturn(keyVerifier);

        given(handleContext.feeCalculatorFactory()).willReturn(feeCalculatorFactory);
        given(feeCalculatorFactory.feeCalculator(SubType.DEFAULT)).willReturn(feeCalculator);
        given(feeCalculator.calculate()).willReturn(new Fees(10, 10, 10));
        given(handleContext.tryToChargePayer(anyLong())).willReturn(true);

        tokenAirdropHandler.handle(handleContext);

        assertThat(writableAccountStore.get(tokenReceiverId)).isNotNull();
        var relationToFungible = Objects.requireNonNull(writableTokenRelStore.get(tokenReceiverId, fungibleTokenId));
        var relationToNFT = Objects.requireNonNull(writableTokenRelStore.get(tokenReceiverId, nonFungibleTokenId));
        assertThat(relationToNFT.balance()).isEqualTo(1);
        // check that fixed fee of 66 is deducted from the owner
        assertThat(writableAccountStore.get(ownerId).tinybarBalance()).isEqualTo(10000 - 66);
        // fractional fee is 1/2, so new fungible balance is 500 instead of 1000
        assertThat(relationToFungible.balance()).isEqualTo(500L);
    }

    @Test
    void handleAirdropNotAssociatedToAccountTransfers() {
        // setup all states
        handlerTestBaseInternalSetUp(true);
        givenStoresAndConfig(handleContext);

        // mock record builder
        tokenAirdropHandler = new TokenAirdropHandler(tokenAirdropValidator, validator);
        var tokenWithNoCustomFees =
                fungibleToken.copyBuilder().customFees(Collections.emptyList()).build();
        var nftWithNoCustomFees = nonFungibleToken
                .copyBuilder()
                .customFees(Collections.emptyList())
                .build();
        writableTokenStore.put(tokenWithNoCustomFees);
        writableTokenStore.put(nftWithNoCustomFees);
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);

        // set up transaction and context
        givenAirdropTxn(false, zeroAccountId, TOKEN_AIRDROP);
        given(handleContext.expiryValidator()).willReturn(expiryValidator);
        given(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong())).willReturn(OK);

        Assertions.assertThatThrownBy(() -> tokenAirdropHandler.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));

        givenAirdropTxn(false, zeroAccountId, NFT_AIRDROP);
        Assertions.assertThatThrownBy(() -> tokenAirdropHandler.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT));
    }

    @Test
    void calculateFeesWithNoAirdropBody() {
        when(feeContext.body()).thenReturn(TransactionBody.DEFAULT);
        assertThrows(NullPointerException.class, () -> tokenAirdropHandler.calculateFees(feeContext));
    }

    @Test
    void calculateFeesNotSupportedOperation() {
        setupAirdropMocks(TokenAirdropTransactionBody.DEFAULT, false);

        final var exception = assertThrows(HandleException.class, () -> tokenAirdropHandler.calculateFees(feeContext));
        assertEquals(ResponseCodeEnum.NOT_SUPPORTED, exception.getStatus());
    }

    @Test
    void calculateFeesShouldChargeBaseFee() {
        final var fungibleTransferList = TokenTransferList.newBuilder()
                .token(TOKEN_2468)
                .transfers(ACCT_4444_MINUS_5)
                .build();
        final var nonFungibleTransferList = TokenTransferList.newBuilder()
                .token(asToken(2469))
                .nftTransfers(SERIAL_1_FROM_3333_TO_4444)
                .build();
        final var airdropBody = TokenAirdropTransactionBody.newBuilder()
                .tokenTransfers(fungibleTransferList, nonFungibleTransferList)
                .build();
        setupAirdropMocks(airdropBody, true);

        when(feeContext.dispatchComputeFees(any(), any())).thenReturn(new Fees(30, 30, 30));

        final var fees = tokenAirdropHandler.calculateFees(feeContext);
        assertEquals(30, fees.networkFee());
        assertEquals(30, fees.nodeFee());
        assertEquals(30, fees.serviceFee());
    }

    @Test
    void updateUpdatesExistingAirdrop() {
        final var airdropId = getFungibleAirdrop();
        final var airdropValue = airdropWithValue(30);
        final var accountAirdrop = accountAirdropWith(airdropValue);
        writableAirdropState = emptyWritableAirdropStateBuilder()
                .value(airdropId, accountAirdrop)
                .build();
        given(writableStates.<PendingAirdropId, AccountPendingAirdrop>get(AIRDROPS_STATE_ID))
                .willReturn(writableAirdropState);
        writableAirdropStore = new WritableAirdropStore(writableStates, writableEntityCounters);
        tokenAirdropHandler = new TokenAirdropHandler(tokenAirdropValidator, validator);

        final var newAirdropValue = airdropWithValue(20);
        final var newAccountAirdrop = accountAirdrop
                .copyBuilder()
                .pendingAirdropValue(newAirdropValue)
                .build();

        Assertions.assertThat(writableAirdropState.contains(airdropId)).isTrue();

        tokenAirdropHandler.update(airdropId, newAccountAirdrop, writableAirdropStore);

        Assertions.assertThat(writableAirdropState.contains(airdropId)).isTrue();
        final var tokenValue = Objects.requireNonNull(Objects.requireNonNull(writableAirdropState.get(airdropId))
                        .pendingAirdropValue())
                .amount();
        Assertions.assertThat(tokenValue).isEqualTo(airdropValue.amount() + newAirdropValue.amount());
    }

    @Mock(strictness = LENIENT)
    protected PreHandleContext preHandleContext;

    @Test
    void prehandlerVerifiesValidAirdrop() {
        final var airdropId = getFungibleAirdrop();
        final var airdropValue = airdropWithValue(30);
        final var accountAirdrop = accountAirdropWith(airdropValue);
        writableAirdropState = emptyWritableAirdropStateBuilder()
                .value(airdropId, accountAirdrop)
                .build();
        given(writableStates.<PendingAirdropId, AccountPendingAirdrop>get(AIRDROPS_STATE_ID))
                .willReturn(writableAirdropState);
        writableAirdropStore = new WritableAirdropStore(writableStates, writableEntityCounters);
        tokenAirdropHandler = new TokenAirdropHandler(tokenAirdropValidator, validator);

        var tokenWithNoCustomFees =
                fungibleToken.copyBuilder().customFees(Collections.emptyList()).build();
        var nftWithNoCustomFees = nonFungibleToken
                .copyBuilder()
                .customFees(Collections.emptyList())
                .build();
        writableTokenStore.put(tokenWithNoCustomFees);
        writableTokenStore.put(nftWithNoCustomFees);
        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(writableTokenStore);

        Assertions.assertThat(writableAirdropState.contains(airdropId)).isTrue();

        given(preHandleContext.createStore(ReadableTokenStore.class)).willReturn(writableTokenStore);
        given(preHandleContext.createStore(ReadableAccountStore.class)).willReturn(readableAccountStore);
        given(preHandleContext.configuration()).willReturn(configuration);

        givenAirdropTxn(false, payerId, TOKEN_AIRDROP);
        given(preHandleContext.body()).willReturn(txn);
        Assertions.assertThatCode(() -> tokenAirdropHandler.preHandle(preHandleContext))
                .doesNotThrowAnyException();
    }

    private void setupAirdropMocks(TokenAirdropTransactionBody body, boolean enableAirdrop) {
        when(feeContext.body()).thenReturn(transactionBody);
        when(transactionBody.tokenAirdropOrThrow()).thenReturn(body);

        when(feeContext.configuration()).thenReturn(config);
        when(config.getConfigData(TokensConfig.class)).thenReturn(tokensConfig);
        when(tokensConfig.airdropsEnabled()).thenReturn(enableAirdrop);
    }

    private List<TokenTransferList> transactionBodyAboveMaxTransferLimit() {
        List<TokenTransferList> result = new ArrayList<>();

        for (int i = 0; i <= MAX_TOKEN_TRANSFERS; i++) {
            result.add(TokenTransferList.newBuilder()
                    .token(nonFungibleTokenId)
                    .nftTransfers(NftTransfer.newBuilder()
                            .serialNumber(1)
                            .senderAccountID(ownerId)
                            .receiverAccountID(tokenReceiverId)
                            .build())
                    .build());
        }

        return result;
    }

    private PendingAirdropId getFungibleAirdrop() {
        return PendingAirdropId.newBuilder()
                .fungibleTokenType(
                        TokenID.newBuilder().realmNum(1).shardNum(2).tokenNum(3).build())
                .build();
    }

    private PendingAirdropValue airdropWithValue(long value) {
        return PendingAirdropValue.newBuilder().amount(value).build();
    }

    private AccountPendingAirdrop accountAirdropWith(PendingAirdropValue pendingAirdropValue) {
        return AccountPendingAirdrop.newBuilder()
                .pendingAirdropValue(pendingAirdropValue)
                .build();
    }
}
