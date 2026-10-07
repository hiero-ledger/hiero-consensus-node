// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.token.AliasUtils.extractEvmAddress;
import static com.hedera.node.app.service.token.AliasUtils.isEntityNumAlias;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.aaWith;
import static com.hedera.node.app.service.token.impl.test.handlers.transfer.AccountAmountUtils.nftTransferWith;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.EvmHookCall;
import com.hedera.hapi.node.base.HookCall;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.handlers.CryptoTransferHandler;
import com.hedera.node.app.service.token.impl.handlers.transfer.hooks.HookCallsFactory;
import com.hedera.node.app.service.token.records.CryptoTransferStreamBuilder;
import com.hedera.node.app.service.token.records.HookDispatchStreamBuilder;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.store.ReadableStoreFactory;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.workflows.TransactionChecker;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.prehandle.PreHandleContextImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Exercises both workflow phases with real account stores and the real signature-collection context. */
class CryptoTransferAccountResolutionTest extends CryptoTransferHandlerTestBase {
    private static final Key RECEIVER_KEY =
            Key.newBuilder().ed25519(Bytes.fromHex("ab".repeat(32))).build();
    private static final Bytes EVM_ALIAS = Bytes.fromHex("ab".repeat(20));
    private ReadableStoreFactory preHandleStores;

    enum Form {
        NUMBER,
        LONG_ZERO,
        EVM,
        ED25519,
        ECDSA,
        ECDSA_DERIVED
    }

    enum Kind {
        HBAR,
        FUNGIBLE,
        NFT
    }

    @Override
    @BeforeEach
    public void setUp() {
        super.setUp();
        givenStoresAndConfig(handleContext);
        // Account zero is a generic fixture entry, not a valid account that can exist on a network.
        writableAccounts.remove(zeroAccountId);
        lenient().when(handleContext.dispatchMetadata()).thenReturn(HandleContext.DispatchMetadata.EMPTY_METADATA);
        lenient().when(handleContext.savepointStack()).thenReturn(stack);
        lenient().when(stack.getBaseBuilder(StreamBuilder.class)).thenReturn(xferRecordBuilder);
        lenient().when(stack.getBaseBuilder(CryptoTransferStreamBuilder.class)).thenReturn(xferRecordBuilder);
        lenient().when(xferRecordBuilder.category()).thenReturn(HandleContext.TransactionCategory.USER);
        writableAccountStore.put(tokenReceiverAccount
                .copyBuilder()
                .key(RECEIVER_KEY)
                .tinybarBalance(1_000_000)
                .receiverSigRequired(true)
                .build());
        writableTokenStore.put(fungibleToken
                .copyBuilder()
                .kycKey((Key) null)
                .customFees(List.of())
                .build());
        writableTokenStore.put(nonFungibleToken.copyBuilder().kycKey((Key) null).build());
        writableTokenRelStore.put(givenFungibleTokenRelation()
                .copyBuilder()
                .accountId(tokenReceiverId)
                .balance(0)
                .kycGranted(true)
                .build());
        writableTokenRelStore.put(givenNonFungibleTokenRelation()
                .copyBuilder()
                .accountId(tokenReceiverId)
                .balance(0)
                .kycGranted(true)
                .build());
        preHandleStores = mock(ReadableStoreFactory.class);
        // Separate readable and writable store instances over identical state, so no lookup is mocked.
        final var accounts = new ReadableAccountStoreImpl(writableStates, readableEntityCounters);
        lenient()
                .when(preHandleStores.readableStore(ReadableAccountStore.class))
                .thenReturn(accounts);
        lenient().when(storeFactory.readableStore(ReadableAccountStore.class)).thenReturn(accounts);
        lenient().when(storeFactory.readableStore(ReadableTokenStore.class)).thenReturn(writableTokenStore);
        lenient()
                .when(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong()))
                .thenReturn(OK);
        lenient().when(preHandleStores.readableStore(ReadableTokenStore.class)).thenReturn(writableTokenStore);
    }

    static Stream<Arguments> existingForms() {
        return Arrays.stream(Form.values()).flatMap(form -> Arrays.stream(Kind.values())
                .flatMap(kind -> Stream.of(false, true).flatMap(sender -> Stream.of(0, 1, 2, 3)
                        .map(domain -> Arguments.of(form, kind, sender, domain)))));
    }

    @ParameterizedTest(name = "{0} {1}, sender={2}, domain={3}")
    @MethodSource("existingForms")
    void preHandleAndCompleteHandleUseTheSameExistingAccount(Form form, Kind kind, boolean sender, int domain)
            throws PreCheckException {
        final var original = sender ? ownerId : tokenReceiverId;
        final var canonical = form == Form.NUMBER || form == Form.LONG_ZERO ? inDomain(original, domain) : original;
        if (!canonical.equals(original)) {
            writableAccountStore.put(writableAccountStore
                    .get(original)
                    .copyBuilder()
                    .accountId(canonical)
                    .build());
            writableTokenRelStore.put(writableTokenRelStore
                    .get(original, fungibleTokenId)
                    .copyBuilder()
                    .accountId(canonical)
                    .build());
            writableTokenRelStore.put(writableTokenRelStore
                    .get(original, nonFungibleTokenId)
                    .copyBuilder()
                    .accountId(canonical)
                    .build());
            if (sender) {
                writableNftStore.put(nftSl1.copyBuilder().ownerId(canonical).build());
            }
        }
        final var reference = reference(form, canonical, domain);
        final var senderId = sender ? reference : ownerId;
        final var receiverId = sender ? tokenReceiverId : reference;
        givenTxn(operation(kind, senderId, receiverId), payerId);
        final var pre = preHandleContext();
        subject.preHandle(pre);
        assertThat(pre.requiredNonPayerKeys()).contains(ownerKey, RECEIVER_KEY);
        final var balanceBefore = writableAccountStore.get(canonical).tinybarBalance();
        final var tokenBalanceBefore =
                writableTokenRelStore.get(canonical, fungibleTokenId).balance();

        subject.handle(handleContext);

        switch (kind) {
            case HBAR ->
                assertThat(writableAccountStore.get(canonical).tinybarBalance())
                        .isEqualTo(balanceBefore + (sender ? -1 : 1));
            case FUNGIBLE ->
                assertThat(writableTokenRelStore.get(canonical, fungibleTokenId).balance())
                        .isEqualTo(tokenBalanceBefore + (sender ? -1 : 1));
            case NFT -> {
                assertThat(writableNftStore.get(nftSl1.nftId()).ownerId())
                        .isEqualTo(sender ? tokenReceiverId : canonical);
                assertThat(writableAccountStore
                                .get(sender ? tokenReceiverId : canonical)
                                .tinybarBalance())
                        .isEqualTo(1_000_000 - 1_000);
            }
        }
        verify(handleContext, never()).dispatch(any());
    }

    static Stream<Arguments> missingLongZeros() {
        return Arrays.stream(Kind.values()).flatMap(kind -> Stream.of(1, 2, 3)
                .flatMap(domain -> Stream.of(0L, 1L).map(seed -> Arguments.of(kind, domain, seed))));
    }

    @ParameterizedTest(name = "{0}, foreign domain={1}, seed credit={2}")
    @MethodSource("missingLongZeros")
    void localAliasSeedCannotMakeAMissingForeignRecipientExist(Kind kind, int domain, long seed)
            throws PreCheckException {
        final var localAlias = longZero(tokenReceiverId);
        final var foreignAlias = inDomain(localAlias, domain);
        final var transfer = operation(kind, ownerId, foreignAlias);
        // HBAR is processed before token transfers in handle. Zero also seeds the old byte-only cache.
        final var hbars = new java.util.ArrayList<>(
                transfer.transfersOrElse(TransferList.DEFAULT).accountAmounts());
        // Use a separate numeric payer adjustment so HBAR sender IDs remain unique.
        hbars.add(aaWith(localAlias, seed));
        if (seed > 0) hbars.add(aaWith(payerId, -seed));
        givenTxn(
                transfer.copyBuilder()
                        .transfers(TransferList.newBuilder().accountAmounts(hbars))
                        .build(),
                payerId);
        final var before = writableAccountStore.get(tokenReceiverId);
        final var nftBefore = writableNftStore.get(nftSl1.nftId());
        final var pre = preHandleContext();
        // Run both phases independently even if one assertion fails; in particular, verify the
        // consensus path cannot use the seed without relying on pre-handle to reject the input.
        assertAll(
                () -> assertThatThrownBy(() -> subject.preHandle(pre))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(INVALID_ALIAS_KEY)),
                () -> assertThatThrownBy(() -> subject.handle(handleContext))
                        .isInstanceOf(HandleException.class)
                        .has(responseCode(INVALID_ALIAS_KEY)),
                () -> assertThat(writableAccountStore.get(tokenReceiverId)).isEqualTo(before),
                () -> assertThat(writableNftStore.get(nftSl1.nftId())).isEqualTo(nftBefore),
                () -> verify(handleContext, never()).dispatch(any()));
    }

    @ParameterizedTest
    @CsvSource({"0,1", "1,0", "0,2", "2,0", "1,2", "2,1"})
    void identicalLongZeroBytesInExistingDomainsKeepTheirOwnBalances(int firstDomain, int secondDomain)
            throws PreCheckException {
        final var firstId = inDomain(tokenReceiverId, firstDomain);
        final var secondId = inDomain(tokenReceiverId, secondDomain);
        final var receiver = writableAccountStore.get(tokenReceiverId);
        writableAccountStore.put(receiver.copyBuilder().accountId(firstId).build());
        writableAccountStore.put(receiver.copyBuilder().accountId(secondId).build());
        givenTxn(
                CryptoTransferTransactionBody.newBuilder()
                        .transfers(TransferList.newBuilder()
                                .accountAmounts(
                                        aaWith(ownerId, -3),
                                        aaWith(longZero(firstId), 1),
                                        aaWith(longZero(secondId), 2)))
                        .build(),
                payerId);
        subject.preHandle(preHandleContext());
        subject.handle(handleContext);
        assertThat(writableAccountStore.get(firstId).tinybarBalance()).isEqualTo(1_000_001);
        assertThat(writableAccountStore.get(secondId).tinybarBalance()).isEqualTo(1_000_002);
    }

    @ParameterizedTest
    @MethodSource("royaltyForms")
    void royaltyFallbackRequiresTheCanonicalReceiverKey(Form form, int domain) throws PreCheckException {
        writableAccountStore.put(writableAccountStore
                .get(tokenReceiverId)
                .copyBuilder()
                .receiverSigRequired(false)
                .build());
        // Ordinary aliases retain the account store's byte-map semantics across outer shard/realm values.
        final var receiver = reference(form, tokenReceiverId, domain);
        givenTxn(operation(Kind.NFT, ownerId, receiver), payerId);
        final var pre = preHandleContext();
        subject.preHandle(pre);
        assertThat(pre.requiredNonPayerKeys()).contains(RECEIVER_KEY);
        subject.handle(handleContext);
        assertThat(writableAccountStore.get(tokenReceiverId).tinybarBalance()).isEqualTo(999_000);
    }

    static Stream<Arguments> royaltyForms() {
        return Arrays.stream(Form.values())
                .flatMap(form -> (form == Form.NUMBER || form == Form.LONG_ZERO ? Stream.of(0) : Stream.of(0, 1, 2, 3))
                        .map(domain -> Arguments.of(form, domain)));
    }

    static Stream<Arguments> missingIds() {
        final var missingNumber = AccountID.newBuilder().accountNum(9_876_543).build();
        return Arrays.stream(Kind.values())
                .flatMap(kind -> Stream.of(false, true).flatMap(sender -> Stream.of(
                                AccountID.DEFAULT,
                                AccountID.newBuilder().accountNum(0).build(),
                                AccountID.newBuilder().accountNum(-1).build(),
                                longZero(AccountID.newBuilder().accountNum(0).build()),
                                AccountID.newBuilder().alias(Bytes.EMPTY).build(),
                                AccountID.newBuilder()
                                        .alias(Bytes.fromHex("ff"))
                                        .build(),
                                missingNumber,
                                inDomain(missingNumber, 1),
                                inDomain(missingNumber, 2),
                                longZero(missingNumber),
                                inDomain(longZero(missingNumber), 1),
                                inDomain(longZero(missingNumber), 2))
                        .map(id -> Arguments.of(kind, sender, id))));
    }

    @ParameterizedTest
    @MethodSource("missingIds")
    void missingAndMalformedReferencesFailWithoutResolvingToAnotherAccount(Kind kind, boolean sender, AccountID missing)
            throws PreCheckException {
        givenTxn(operation(kind, sender ? missing : ownerId, sender ? tokenReceiverId : missing), payerId);
        final var pre = preHandleContext();
        final var receiverBefore = writableAccountStore.get(tokenReceiverId);
        final var nftBefore = writableNftStore.get(nftSl1.nftId());
        // A nonempty, non-long-zero alias may be deferred to creation validation during handle.
        if (sender || !missing.hasAlias() || missing.aliasOrThrow().length() != 1) {
            final var status = !sender && missing.hasAlias() && isEntityNumAlias(missing.aliasOrThrow())
                    ? INVALID_ALIAS_KEY
                    : INVALID_ACCOUNT_ID;
            assertThatThrownBy(() -> subject.preHandle(pre))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(status));
        } else {
            subject.preHandle(pre);
        }
        assertThatThrownBy(() -> subject.handle(handleContext)).isInstanceOf(HandleException.class);
        assertThat(writableAccountStore.get(tokenReceiverId)).isEqualTo(receiverBefore);
        assertThat(writableNftStore.get(nftSl1.nftId())).isEqualTo(nftBefore);
        verify(handleContext, never()).dispatch(any());
    }

    static Stream<Arguments> missingLongZeroAmounts() {
        return Stream.of(Kind.HBAR, Kind.FUNGIBLE).flatMap(kind -> Stream.of(0, 1, 2, 3)
                .flatMap(domain -> Stream.of(-1L, 0L, 1L).map(amount -> Arguments.of(kind, domain, amount))));
    }

    @ParameterizedTest
    @MethodSource("missingLongZeroAmounts")
    void missingLongZeroCreditsRetainCreationFailureButDebitsAndZeroAmountsRequireAnAccount(
            Kind kind, int domain, long amount) throws PreCheckException {
        final var missing =
                inDomain(longZero(AccountID.newBuilder().accountNum(9_876_543).build()), domain);
        final var amounts = List.of(aaWith(ownerId, -amount), aaWith(missing, amount));
        final var op = CryptoTransferTransactionBody.newBuilder();
        if (kind == Kind.HBAR) {
            op.transfers(TransferList.newBuilder().accountAmounts(amounts));
        } else {
            op.tokenTransfers(TokenTransferList.newBuilder()
                    .token(fungibleTokenId)
                    .transfers(amounts)
                    .build());
        }
        givenTxn(op.build(), payerId);
        final var pre = preHandleContext();
        final var expectedStatus = amount > 0 ? INVALID_ALIAS_KEY : INVALID_ACCOUNT_ID;
        final var ownerBefore = writableAccountStore.get(ownerId);
        assertAll(
                () -> assertThatThrownBy(() -> subject.preHandle(pre))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(expectedStatus)),
                () -> assertThatThrownBy(() -> subject.handle(handleContext))
                        .isInstanceOf(HandleException.class)
                        .has(responseCode(expectedStatus)),
                () -> assertThat(writableAccountStore.get(ownerId)).isEqualTo(ownerBefore),
                () -> verify(handleContext, never()).dispatch(any()));
    }

    @ParameterizedTest
    @EnumSource(Form.class)
    void unknownAliasesCannotBeSendersEvenWithAHook(Form form) throws PreCheckException {
        final var missing =
                switch (form) {
                    case NUMBER -> inDomain(ownerId, 1);
                    case LONG_ZERO -> inDomain(longZero(ownerId), 1);
                    case EVM -> AccountID.newBuilder().alias(EVM_ALIAS).build();
                    case ED25519 ->
                        AccountID.newBuilder()
                                .alias(Key.PROTOBUF.toBytes(RECEIVER_KEY))
                                .build();
                    case ECDSA, ECDSA_DERIVED -> unknownAliasedId;
                };
        final var nft = nftTransferWith(missing, tokenReceiverId, 1)
                .copyBuilder()
                .preTxSenderAllowanceHook(HookCall.newBuilder().hookId(1).build())
                .build();
        givenTxn(
                CryptoTransferTransactionBody.newBuilder()
                        .tokenTransfers(TokenTransferList.newBuilder()
                                .token(nonFungibleTokenId)
                                .nftTransfers(nft)
                                .build())
                        .build(),
                payerId);
        final var pre = preHandleContext();
        assertThatThrownBy(() -> subject.preHandle(pre))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(INVALID_ACCOUNT_ID));
    }

    @ParameterizedTest
    @EnumSource(Form.class)
    void aliasedHbarAllowanceDebitsUseTheSameOwner(Form form) throws PreCheckException {
        final var reference = reference(form, ownerId, 0);
        final var op = CryptoTransferTransactionBody.newBuilder()
                .transfers(TransferList.newBuilder()
                        .accountAmounts(
                                aaWith(reference, -1)
                                        .copyBuilder()
                                        .isApproval(true)
                                        .build(),
                                aaWith(tokenReceiverId, 1)))
                .build();
        givenTxn(op, spenderId);
        final var pre = preHandleContext();
        subject.preHandle(pre);
        assertThat(pre.requiredNonPayerKeys()).contains(RECEIVER_KEY).doesNotContain(ownerKey);
        final var before = writableAccountStore.get(ownerId);
        subject.handle(handleContext);
        assertThat(writableAccountStore.get(ownerId).tinybarBalance()).isEqualTo(before.tinybarBalance() - 1);
        assertThat(writableAccountStore
                        .get(ownerId)
                        .cryptoAllowances()
                        .getFirst()
                        .amount())
                .isEqualTo(999);
    }

    static Stream<Arguments> treasuryForms() {
        return Arrays.stream(Form.values())
                .flatMap(form -> Stream.of(false, true).map(sender -> Arguments.of(form, sender)));
    }

    @ParameterizedTest
    @MethodSource("treasuryForms")
    void aliasedTreasuryGetsTheSameRoyaltyExemption(Form form, boolean sender) throws PreCheckException {
        writableAccountStore.put(writableAccountStore
                .get(tokenReceiverId)
                .copyBuilder()
                .receiverSigRequired(false)
                .build());
        writableAccountStore.put(writableAccountStore
                .get(treasuryId)
                .copyBuilder()
                .receiverSigRequired(false)
                .build());
        final var treasury = reference(form, treasuryId, 0);
        if (sender)
            writableNftStore.put(nftSl1.copyBuilder().ownerId((AccountID) null).build());
        final var receiver = sender ? tokenReceiverId : treasuryId;
        final var before = writableAccountStore.get(receiver).tinybarBalance();
        givenTxn(operation(Kind.NFT, sender ? treasury : ownerId, sender ? tokenReceiverId : treasury), payerId);
        final var pre = preHandleContext();
        subject.preHandle(pre);
        assertThat(pre.requiredNonPayerKeys())
                .doesNotContain(writableAccountStore.get(receiver).key());
        subject.handle(handleContext);
        assertThat(writableAccountStore.get(receiver).tinybarBalance()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(Form.class)
    void royaltyExchangeRecognizesTheSameSenderThroughAnAlias(Form form) throws PreCheckException {
        writableAccountStore.put(writableAccountStore
                .get(tokenReceiverId)
                .copyBuilder()
                .receiverSigRequired(false)
                .build());
        final var sender = reference(form, ownerId, 0);
        final var op = operation(Kind.NFT, ownerId, tokenReceiverId)
                .copyBuilder()
                .transfers(TransferList.newBuilder().accountAmounts(aaWith(payerId, -10), aaWith(sender, 10)))
                .build();
        givenTxn(op, payerId);
        final var pre = preHandleContext();
        subject.preHandle(pre);
        assertThat(pre.requiredNonPayerKeys()).doesNotContain(RECEIVER_KEY);
        subject.handle(handleContext);
        assertThat(writableAccountStore.get(tokenReceiverId).tinybarBalance()).isEqualTo(1_000_000);
    }

    @ParameterizedTest
    @EnumSource(
            value = Form.class,
            names = {"EVM", "ED25519", "ECDSA"})
    void repeatedNewAliasesAcrossDomainsCreateOneAccount(Form form) throws PreCheckException {
        final var alias =
                switch (form) {
                    case EVM -> EVM_ALIAS;
                    case ED25519 -> Key.PROTOBUF.toBytes(RECEIVER_KEY);
                    case ECDSA -> ecKeyAlias.value();
                    default -> throw new IllegalArgumentException();
                };
        final var newId = AccountID.newBuilder().accountNum(9_876_543).build();
        final var input = AccountID.newBuilder().alias(alias).build();
        writableTokenStore.put(nonFungibleToken
                .copyBuilder()
                .kycKey((Key) null)
                .customFees(List.of())
                .build());
        when(cryptoCreateRecordBuilder.status()).thenReturn(SUCCESS);
        when(handleContext.dispatch(any())).thenAnswer(invocation -> {
            final DispatchOptions<?> options = invocation.getArgument(0);
            assertThat(options.body().cryptoCreateAccountOrThrow().alias()).isEqualTo(alias);
            writableAccountStore.put(account.copyBuilder()
                    .accountId(newId)
                    .alias(alias)
                    .key(RECEIVER_KEY)
                    .receiverSigRequired(false)
                    .tinybarBalance(0)
                    .headTokenId((TokenID) null)
                    .numberAssociations(0)
                    .usedAutoAssociations(0)
                    .headNftId((NftID) null)
                    .headNftSerialNumber(0)
                    .numberOwnedNfts(0)
                    .numberPositiveBalances(0)
                    .build());
            writableAccountStore.putAlias(alias, newId);
            return cryptoCreateRecordBuilder;
        });
        givenTxn(
                CryptoTransferTransactionBody.newBuilder()
                        .transfers(TransferList.newBuilder()
                                .accountAmounts(aaWith(ownerId, -1), aaWith(inDomain(input, 1), 1)))
                        .tokenTransfers(
                                TokenTransferList.newBuilder()
                                        .token(fungibleTokenId)
                                        .transfers(aaWith(ownerId, -1), aaWith(inDomain(input, 2), 1))
                                        .build(),
                                TokenTransferList.newBuilder()
                                        .token(nonFungibleTokenId)
                                        .nftTransfers(nftTransferWith(ownerId, inDomain(input, 3), 1))
                                        .build())
                        .build(),
                payerId);
        subject.preHandle(preHandleContext());
        subject.handle(handleContext);
        assertThat(writableAccountStore.get(newId).tinybarBalance()).isEqualTo(1);
        assertThat(writableTokenRelStore.get(newId, fungibleTokenId).balance()).isEqualTo(1);
        assertThat(writableNftStore.get(nftSl1.nftId()).ownerId()).isEqualTo(newId);
        verify(handleContext).dispatch(any());
    }

    static Stream<Arguments> hookForms() {
        return Arrays.stream(Form.values()).flatMap(form -> Arrays.stream(Kind.values())
                .flatMap(kind -> Stream.of(false, true).flatMap(sender -> Stream.of(false, true)
                        .map(prePost -> Arguments.of(form, kind, sender, prePost)))));
    }

    @ParameterizedTest
    @MethodSource("hookForms")
    void hooksDispatchForTheSameCanonicalAccount(Form form, Kind kind, boolean sender, boolean prePost)
            throws PreCheckException {
        subject = new CryptoTransferHandler(validator, new HookCallsFactory(), entityIdFactory);
        final var canonical = sender ? ownerId : tokenReceiverId;
        final var reference = reference(form, canonical, 0);
        final var hook = HookCall.newBuilder()
                .hookId(1)
                .evmHookCall(EvmHookCall.newBuilder().gasLimit(2_000).data(Bytes.EMPTY))
                .build();
        final var op = operation(kind, sender ? reference : ownerId, sender ? tokenReceiverId : reference);
        final var hooked = op.copyBuilder();
        if (kind == Kind.NFT) {
            final var nft =
                    op.tokenTransfers().getFirst().nftTransfers().getFirst().copyBuilder();
            if (sender) {
                if (prePost) nft.prePostTxSenderAllowanceHook(hook);
                else nft.preTxSenderAllowanceHook(hook);
            } else {
                if (prePost) nft.prePostTxReceiverAllowanceHook(hook);
                else nft.preTxReceiverAllowanceHook(hook);
            }
            hooked.tokenTransfers(op.tokenTransfers()
                    .getFirst()
                    .copyBuilder()
                    .nftTransfers(nft.build())
                    .build());
        } else {
            final var amounts = new java.util.ArrayList<>(
                    kind == Kind.HBAR
                            ? op.transfersOrThrow().accountAmounts()
                            : op.tokenTransfers().getFirst().transfers());
            final var index = sender ? 0 : 1;
            final var aa = amounts.get(index).copyBuilder();
            if (prePost) aa.prePostTxAllowanceHook(hook);
            else aa.preTxAllowanceHook(hook);
            amounts.set(index, aa.build());
            if (kind == Kind.HBAR) hooked.transfers(TransferList.newBuilder().accountAmounts(amounts));
            else
                hooked.tokenTransfers(op.tokenTransfers()
                        .getFirst()
                        .copyBuilder()
                        .transfers(amounts)
                        .build());
        }
        givenTxn(hooked.build(), payerId);
        when(handleContext.configuration())
                .thenReturn(HederaTestConfigBuilder.create()
                        .withValue("hooks.hooksEnabled", true)
                        .getOrCreateConfig());
        final var pre = preHandleContext();
        subject.preHandle(pre);
        assertThat(pre.requiredNonPayerKeys()).doesNotContain(sender ? ownerKey : RECEIVER_KEY);
        final var hookResult = mock(HookDispatchStreamBuilder.class);
        when(hookResult.status()).thenReturn(SUCCESS);
        when(hookResult.getEvmCallResult()).thenReturn(Bytes.fromHex("00".repeat(31) + "01"));
        when(handleContext.dispatch(any())).thenAnswer(invocation -> {
            final DispatchOptions<?> options = invocation.getArgument(0);
            assertThat(options.body()
                            .hookDispatchOrThrow()
                            .executionOrThrow()
                            .hookEntityIdOrThrow()
                            .accountIdOrThrow())
                    .isEqualTo(canonical);
            return hookResult;
        });
        subject.handle(handleContext);
        verify(handleContext, times(prePost ? 2 : 1)).dispatch(any());
    }

    @ParameterizedTest
    @CsvSource({"1", "2", "3"})
    void laterNftResolutionCannotOverwriteAnEarlierHbarResolution(int domain) throws PreCheckException {
        final var foreign = inDomain(tokenReceiverId, domain);
        writableAccountStore.put(writableAccountStore
                .get(tokenReceiverId)
                .copyBuilder()
                .accountId(foreign)
                .build());
        writableTokenRelStore.put(givenNonFungibleTokenRelation()
                .copyBuilder()
                .accountId(foreign)
                .kycGranted(true)
                .build());
        givenTxn(
                operation(Kind.NFT, ownerId, longZero(foreign))
                        .copyBuilder()
                        .transfers(TransferList.newBuilder()
                                .accountAmounts(aaWith(ownerId, -1), aaWith(longZero(tokenReceiverId), 1)))
                        .build(),
                payerId);
        subject.preHandle(preHandleContext());
        subject.handle(handleContext);
        assertThat(writableAccountStore.get(tokenReceiverId).tinybarBalance()).isEqualTo(1_000_001);
        assertThat(writableAccountStore.get(foreign).tinybarBalance()).isEqualTo(999_000);
        assertThat(writableNftStore.get(nftSl1.nftId()).ownerId()).isEqualTo(foreign);
    }

    static Stream<Arguments> tokenSeeds() {
        return Stream.of(Kind.FUNGIBLE, Kind.NFT).flatMap(kind -> Stream.of(1, 2, 3)
                .flatMap(domain -> Stream.of(0L, 1L).map(amount -> Arguments.of(kind, domain, amount))));
    }

    @ParameterizedTest
    @MethodSource("tokenSeeds")
    void earlierTokenListCannotSeedAMissingForeignRecipient(Kind kind, int domain, long amount)
            throws PreCheckException {
        final var local = longZero(tokenReceiverId);
        final var foreign = inDomain(local, domain);
        final var seed = TokenTransferList.newBuilder()
                .token(fungibleTokenId)
                .transfers(aaWith(ownerId, -amount), aaWith(local, amount))
                .build();
        final var target = kind == Kind.NFT
                ? TokenTransferList.newBuilder()
                        .token(nonFungibleTokenId)
                        .nftTransfers(nftTransferWith(ownerId, foreign, 1))
                        .build()
                : TokenTransferList.newBuilder()
                        .token(fungibleTokenIDB)
                        .transfers(aaWith(ownerId, -1), aaWith(foreign, 1))
                        .build();
        givenTxn(
                CryptoTransferTransactionBody.newBuilder()
                        .tokenTransfers(seed, target)
                        .build(),
                payerId);
        final var pre = preHandleContext();
        assertAll(
                () -> assertThatThrownBy(() -> subject.preHandle(pre))
                        .isInstanceOf(PreCheckException.class)
                        .has(responseCode(INVALID_ALIAS_KEY)),
                () -> assertThatThrownBy(() -> subject.handle(handleContext))
                        .isInstanceOf(HandleException.class)
                        .has(responseCode(INVALID_ALIAS_KEY)),
                () -> verify(handleContext, never()).dispatch(any()));
    }

    @ParameterizedTest
    @EnumSource(
            value = Form.class,
            names = {"EVM", "ED25519", "ECDSA"})
    void sameOrdinaryAliasAcrossDomainsStillFailsCanonicalDuplicateChecks(Form form) throws PreCheckException {
        final var reference = reference(form, tokenReceiverId, 0);
        givenTxn(
                CryptoTransferTransactionBody.newBuilder()
                        .transfers(TransferList.newBuilder()
                                .accountAmounts(
                                        aaWith(ownerId, -3), aaWith(reference, 1), aaWith(inDomain(reference, 1), 2)))
                        .build(),
                payerId);
        subject.preHandle(preHandleContext());
        assertThatThrownBy(() -> subject.handle(handleContext))
                .isInstanceOf(HandleException.class)
                .has(responseCode(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS));
    }

    private PreHandleContextImpl preHandleContext() throws PreCheckException {
        return new PreHandleContextImpl(
                preHandleStores,
                txn,
                configuration,
                mock(TransactionDispatcher.class),
                mock(TransactionChecker.class),
                mock(NodeInfo.class));
    }

    private CryptoTransferTransactionBody operation(Kind kind, AccountID sender, AccountID receiver) {
        return switch (kind) {
            case HBAR ->
                CryptoTransferTransactionBody.newBuilder()
                        .transfers(TransferList.newBuilder().accountAmounts(aaWith(sender, -1), aaWith(receiver, 1)))
                        .build();
            case FUNGIBLE ->
                CryptoTransferTransactionBody.newBuilder()
                        .tokenTransfers(TokenTransferList.newBuilder()
                                .token(fungibleTokenId)
                                .transfers(aaWith(sender, -1), aaWith(receiver, 1))
                                .build())
                        .build();
            case NFT ->
                CryptoTransferTransactionBody.newBuilder()
                        .tokenTransfers(TokenTransferList.newBuilder()
                                .token(nonFungibleTokenId)
                                .nftTransfers(nftTransferWith(sender, receiver, 1))
                                .build())
                        .build();
        };
    }

    private AccountID reference(Form form, AccountID canonical, int domain) {
        if (form == Form.NUMBER) return canonical;
        if (form == Form.LONG_ZERO) return longZero(canonical);
        final var alias =
                switch (form) {
                    case EVM -> EVM_ALIAS;
                    case ED25519 -> Key.PROTOBUF.toBytes(RECEIVER_KEY);
                    case ECDSA, ECDSA_DERIVED -> ecKeyAlias.value();
                    default -> throw new IllegalArgumentException();
                };
        writableAccountStore.putAlias(form == Form.ECDSA_DERIVED ? extractEvmAddress(alias) : alias, canonical);
        return inDomain(AccountID.newBuilder().alias(alias).build(), domain);
    }

    private static AccountID inDomain(AccountID id, int domain) {
        return id.copyBuilder().shardNum(domain & 1).realmNum((domain >> 1) & 1).build();
    }

    private static AccountID longZero(AccountID id) {
        return id.copyBuilder()
                .alias(Bytes.wrap(ByteBuffer.allocate(20)
                        .putInt(0)
                        .putLong(0)
                        .putLong(id.accountNumOrThrow())
                        .array()))
                .build();
    }
}
