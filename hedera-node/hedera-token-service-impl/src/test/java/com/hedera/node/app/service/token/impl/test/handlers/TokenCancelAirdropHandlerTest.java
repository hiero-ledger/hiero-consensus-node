// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CANCEL_AIRDROP;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PENDING_AIRDROP_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PENDING_AIRDROP_ID_LIST_TOO_LONG;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory.newReadableStoreWithTokens;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory.newWritableStoreWithAccounts;
import static com.hedera.node.app.service.token.impl.test.handlers.util.TestStoreFactory.newWritableStoreWithAirdrops;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenCancelAirdropTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableAirdropStore;
import com.hedera.node.app.service.token.impl.handlers.TokenCancelAirdropHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.TokenHandlerTestBase;
import com.hedera.node.app.service.token.impl.util.PendingAirdropUpdater;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.FeeCalculatorFactory;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.hiero.hapi.support.fees.ServiceFeeDefinition;
import org.hiero.hapi.support.fees.ServiceFeeSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
class TokenCancelAirdropHandlerTest extends TokenHandlerTestBase {

    private TokenCancelAirdropHandler subject;

    private TransactionBody transactionBody;

    @Mock
    private HandleContext handleContext;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private StoreFactory storeFactory;

    private WritableAirdropStore airdropStore;
    protected Configuration testConfig;
    private static final AccountID senderId = asAccount(0L, 0L, 1001);
    private static final AccountID receiverId = asAccount(0L, 0L, 1010);
    private static final TokenID fungibleTokenId = asToken(333);
    private static final NftID nftId =
            NftID.newBuilder().tokenId(fungibleTokenId).build();

    @BeforeEach
    public void setup() {
        subject = new TokenCancelAirdropHandler(new PendingAirdropUpdater());
        testConfig = HederaTestConfigBuilder.create()
                .withValue("tokens.airdrops.cancel.enabled", true)
                .getOrCreateConfig();
        airdropStore = newWritableStoreWithAirdrops();
    }

    @Test
    void cancelAirdropHappyPath() {
        mockHandleContext();
        mockAccountStore();
        transactionBody = TransactionBody.newBuilder()
                .tokenCancelAirdrop(tokenCancelAirdrops())
                .build();
        when(handleContext.body()).thenReturn(transactionBody);

        airdropStore = newWritableStoreWithAirdrops(getFungibleAirdrop(), getNftAirdrop());
        when(storeFactory.writableStore(WritableAirdropStore.class)).thenReturn(airdropStore);

        readableTokenStore = newReadableStoreWithTokens(
                Token.newBuilder().tokenId(fungibleTokenId).build());

        mockAccountStore();

        // Act
        assertDoesNotThrow(() -> subject.handle(handleContext));

        // Assert
        assertFalse(airdropStore.exists(getFungibleAirdrop()));
        assertFalse(airdropStore.exists(getNftAirdrop()));
    }

    @Test
    void cancelAirdropWithNoPendingAirdropsThrowsException() {
        // setup common mocks
        mockHandleContext();
        mockAccountStore();
        readableTokenStore = newReadableStoreWithTokens(
                Token.newBuilder().tokenId(fungibleTokenId).build());

        // Transaction body with cancel airdrops that doesn't exist in the state
        transactionBody = TransactionBody.newBuilder()
                .tokenCancelAirdrop(tokenCancelAirdrops())
                .build();
        when(handleContext.body()).thenReturn(transactionBody);

        // Mocking the airdrop store to be empty
        when(storeFactory.writableStore(WritableAirdropStore.class)).thenReturn(airdropStore);

        // Act
        final var response = assertThrows(HandleException.class, () -> subject.handle(handleContext));

        // Assert
        assertEquals(INVALID_PENDING_AIRDROP_ID, response.getStatus());
    }

    @Test
    void cancelAirdropWithInvalidTokenIdThrowsException() {
        mockHandleContext();
        mockAccountStore();
        readableTokenStore = newReadableStoreWithTokens();

        // Transaction body with token id that doesn't exist in the token store
        transactionBody = TransactionBody.newBuilder()
                .tokenCancelAirdrop(tokenCancelAirdrop(true))
                .build();
        when(handleContext.body()).thenReturn(transactionBody);
        when(storeFactory.writableStore(WritableAirdropStore.class)).thenReturn(airdropStore);

        // Act
        final var response = assertThrows(HandleException.class, () -> subject.handle(handleContext));

        // Assert
        assertEquals(INVALID_PENDING_AIRDROP_ID, response.getStatus());
    }

    @Test
    void cancelAirdropWithInvalidNftIdThrowsException() {
        mockHandleContext();
        mockAccountStore();
        readableTokenStore = newReadableStoreWithTokens();

        // Transaction body with nfId that doesn't exist in the nft store
        transactionBody = TransactionBody.newBuilder()
                .tokenCancelAirdrop(tokenCancelAirdrop(false))
                .build();
        when(handleContext.body()).thenReturn(transactionBody);

        when(storeFactory.writableStore(WritableAirdropStore.class)).thenReturn(airdropStore);

        // Act
        final var response = assertThrows(HandleException.class, () -> subject.handle(handleContext));

        // Assert
        assertEquals(INVALID_PENDING_AIRDROP_ID, response.getStatus());
    }

    @Nested
    // Changing the strictness to lenient to avoid strictness problems with the setup method of the parent class
    @MockitoSettings(strictness = Strictness.LENIENT)
    class ConfigTests {

        @ParameterizedTest
        @MethodSource("maxAirdropsExceededArguments")
        void maxAirdropsExceeded(PendingAirdropId airdrop) {
            mockHandleContext();
            mockAccountStore();
            transactionBody = TransactionBody.newBuilder()
                    .tokenCancelAirdrop(tokenCancelRepeatedAirdrop(airdrop, 11))
                    .build();

            when(handleContext.body()).thenReturn(transactionBody);

            // Act
            final var response = assertThrows(HandleException.class, () -> subject.handle(handleContext));

            // Assert
            assertEquals(PENDING_AIRDROP_ID_LIST_TOO_LONG, response.getStatus());
        }

        private static Stream<Arguments> maxAirdropsExceededArguments() {
            return Stream.of(Arguments.of(getNftAirdrop()), Arguments.of(getFungibleAirdrop()));
        }

        @Test
        void cancelAirdropWhenDisabled() {
            // Arrange

            // Common Mocks
            transactionBody = TransactionBody.newBuilder()
                    .tokenCancelAirdrop(tokenCancelAirdrop(true))
                    .build();
            when(handleContext.body()).thenReturn(transactionBody);

            // Disabling the cancel airdrops
            testConfig = HederaTestConfigBuilder.create()
                    .withValue("tokens.airdrops.cancel.enabled", false)
                    .getOrCreateConfig();
            when(handleContext.configuration()).thenReturn(testConfig);

            // Act
            final var response = assertThrows(HandleException.class, () -> subject.handle(handleContext));

            // Assert
            assertEquals(NOT_SUPPORTED, response.getStatus());
        }

        private TokenCancelAirdropTransactionBody tokenCancelRepeatedAirdrop(PendingAirdropId airdrop, int n) {
            return TokenCancelAirdropTransactionBody.newBuilder()
                    .pendingAirdrops(repeatedAirdrops(airdrop, n))
                    .build();
        }

        private PendingAirdropId[] repeatedAirdrops(final PendingAirdropId airDrop, final int count) {
            return Collections.nCopies(count, airDrop).toArray(new PendingAirdropId[0]);
        }
    }

    @Test
    void pureChecksThrowsForDuplicateAirdropIds() {
        var duplicateAirdrop = PendingAirdropId.newBuilder()
                .senderId(senderId)
                .receiverId(receiverId)
                .fungibleTokenType(fungibleTokenId)
                .build();
        transactionBody = TransactionBody.newBuilder()
                .tokenCancelAirdrop(TokenCancelAirdropTransactionBody.newBuilder()
                        .pendingAirdrops(duplicateAirdrop, duplicateAirdrop)
                        .build())
                .build();
        when(pureChecksContext.body()).thenReturn(transactionBody);

        assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void handleThrowsWhenCancelDisabled() {
        testConfig = HederaTestConfigBuilder.create()
                .withValue("tokens.airdrops.cancel.enabled", false)
                .getOrCreateConfig();
        when(handleContext.configuration()).thenReturn(testConfig);

        transactionBody = TransactionBody.newBuilder()
                .tokenCancelAirdrop(tokenCancelAirdrops())
                .build();
        when(handleContext.body()).thenReturn(transactionBody);

        assertThrows(HandleException.class, () -> subject.handle(handleContext));
    }

    @Test
    void calculateFeesThrowsWhenCancelDisabled() {
        var testConfig = HederaTestConfigBuilder.create()
                .withValue("tokens.airdrops.cancel.enabled", false)
                .getOrCreateConfig();
        var feeContext = mock(FeeContext.class);
        when(feeContext.configuration()).thenReturn(testConfig);

        assertThrows(HandleException.class, () -> subject.calculateFees(feeContext));
    }

    @Test
    void airdropFeeCalculatesCorrectly() {
        final var feeContext = mock(FeeContext.class);
        final var feeCalculatorFactory = mock(FeeCalculatorFactory.class);
        final var feeCalculator = mock(FeeCalculator.class);
        final var feeSchedule = mock(FeeSchedule.class);
        when(feeSchedule.node()).thenReturn(new NodeFee(100, null));
        when(feeSchedule.network())
                .thenReturn(NetworkFee.newBuilder().multiplier(2).build());
        when(feeSchedule.services())
                .thenReturn(List.of(ServiceFeeSchedule.newBuilder()
                        .schedule(ServiceFeeDefinition.newBuilder()
                                .name(TOKEN_CANCEL_AIRDROP)
                                .baseFee(300)
                                .build())
                        .build()));
        when(feeCalculator.getSimpleFeesSchedule()).thenReturn(feeSchedule);
        when(feeCalculatorFactory.feeCalculator(SubType.DEFAULT)).thenReturn(feeCalculator);
        when(feeContext.feeCalculatorFactory()).thenReturn(feeCalculatorFactory);

        FeeResult result = subject.calculateFeeResult(feeContext);

        assertEquals(600L, result.total());
    }

    private void mockHandleContext() {
        when(handleContext.configuration()).thenReturn(testConfig);
        when(handleContext.storeFactory()).thenReturn(storeFactory);
    }

    private void mockAccountStore() {
        WritableAccountStore writableAccountStore = newWritableStoreWithAccounts(
                Account.newBuilder().accountId(senderId).build(),
                Account.newBuilder().accountId(receiverId).build());
        when(storeFactory.writableStore(WritableAccountStore.class)).thenReturn(writableAccountStore);
    }

    private static TokenCancelAirdropTransactionBody tokenCancelAirdrop(final boolean isFungible) {
        return TokenCancelAirdropTransactionBody.newBuilder()
                .pendingAirdrops(isFungible ? getFungibleAirdrop() : getNftAirdrop())
                .build();
    }

    private static TokenCancelAirdropTransactionBody tokenCancelAirdrops() {
        return TokenCancelAirdropTransactionBody.newBuilder()
                .pendingAirdrops(getFungibleAirdrop(), getNftAirdrop())
                .build();
    }

    private static PendingAirdropId getFungibleAirdrop() {
        return PendingAirdropId.newBuilder()
                .senderId(senderId)
                .receiverId(receiverId)
                .fungibleTokenType(fungibleTokenId)
                .build();
    }

    private static PendingAirdropId getNftAirdrop() {
        return PendingAirdropId.newBuilder()
                .senderId(senderId)
                .receiverId(receiverId)
                .nonFungibleToken(nftId)
                .build();
    }
}
