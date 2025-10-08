// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.test.handlers;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKEN_RELS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKEN_RELS_STATE_LABEL;
import static com.hedera.node.app.state.recordcache.RecordCacheService.NAME;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenSupplyType;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountApprovalForAllAllowance;
import com.hedera.hapi.node.state.token.AccountCryptoAllowance;
import com.hedera.hapi.node.state.token.AccountFungibleTokenAllowance;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.fixtures.state.FakeSchemaRegistry;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenRelationStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenStoreImpl;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.app.state.DeduplicationCache;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.state.WorkingStateAccessor;
import com.hedera.node.app.state.recordcache.DeduplicationCacheImpl;
import com.hedera.node.app.state.recordcache.PartialRecordSource;
import com.hedera.node.app.state.recordcache.RecordCacheImpl;
import com.hedera.node.app.state.recordcache.RecordCacheService;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class NetworkAdminHandlerTestBase {

    private static final OneOf<Account.StakedIdOneOfType> UNSET_STAKED_ID =
            new OneOf<>(Account.StakedIdOneOfType.UNSET, null);

    protected final Bytes ledgerId = Bytes.wrap(new byte[] {0});

    protected final AccountID accountId = AccountID.newBuilder().accountNum(3).build();
    protected final AccountID autoRenewId = AccountID.newBuilder().accountNum(4).build();
    protected final Long accountNum = accountId.accountNum();
    protected final AccountID alias =
            AccountID.newBuilder().alias(Bytes.wrap("testAlias")).build();

    protected final AccountID deleteAccountId =
            AccountID.newBuilder().accountNum(3213).build();
    protected final AccountID transferAccountId =
            AccountID.newBuilder().accountNum(32134).build();

    protected static final long payerBalance = 10_000L;
    protected final TokenID fungibleTokenId = asToken(1L);
    protected final TokenID nonFungibleTokenId = asToken(2L);
    protected final EntityIDPair fungiblePair = EntityIDPair.newBuilder()
            .accountId(accountId)
            .tokenId(fungibleTokenId)
            .build();
    protected final EntityIDPair nonFungiblePair = EntityIDPair.newBuilder()
            .accountId(accountId)
            .tokenId(nonFungibleTokenId)
            .build();

    protected final String tokenName = "test token";
    protected final String tokenSymbol = "TT";
    protected final AccountID treasury = AccountID.newBuilder().accountNum(100).build();
    protected final long autoRenewSecs = 100L;
    protected final long expirationTime = 1_234_567L;
    protected final String memo = "test memo";

    protected final Bytes metadata = Bytes.wrap(new byte[] {1, 2, 3, 4});
    protected final Key metadataKey = null;

    protected MapReadableKVState<AccountID, Account> readableAccounts;
    protected MapReadableKVState<TokenID, Token> readableTokenState;
    protected MapReadableKVState<EntityIDPair, TokenRelation> readableTokenRelState;

    protected ReadableTokenStore readableTokenStore;

    protected ReadableAccountStore readableAccountStore;
    protected ReadableTokenRelationStore readableTokenRelStore;

    protected Token fungibleToken;
    protected Token nonFungibleToken;
    protected Account account;
    protected TokenRelation fungibleTokenRelation;
    protected TokenRelation nonFungibleTokenRelation;

    protected static final AccountID PAYER_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(1001).build();

    protected TransactionID transactionID;
    protected TransactionID otherNonceOneTransactionID;
    protected TransactionID otherNonceTwoTransactionID;
    protected TransactionID otherNonceThreeTransactionID;
    protected TransactionID transactionIDNotInCache;

    protected TransactionRecord primaryRecord;
    protected TransactionRecord duplicate1;
    protected TransactionRecord duplicate2;
    protected TransactionRecord duplicate3;
    protected TransactionRecord otherRecord;
    protected TransactionRecord recordOne;
    protected TransactionRecord recordTwo;
    protected TransactionRecord recordThree;

    private static final int MAX_QUERYABLE_PER_ACCOUNT = 10;

    protected RecordCacheImpl cache;

    @Mock
    private DeduplicationCache dedupeCache;

    @Mock
    protected SavepointStackImpl stack;

    @Mock
    private WorkingStateAccessor wsa;

    @Mock
    private ConfigProvider props;

    @Mock
    protected ReadableStates readableStates;

    @Mock
    protected Account deleteAccount;

    @Mock
    protected Account transferAccount;

    @Mock
    private VersionedConfiguration versionedConfig;

    @Mock
    private HederaConfig hederaConfig;

    @Mock
    private LedgerConfig ledgerConfig;

    @Mock
    private NetworkInfo networkInfo;

    @Mock
    private StartupNetworks startupNetworks;

    @Mock
    protected FeeCalculator feeCalculator;

    @Mock
    protected ReadableEntityCounters readableEntityCounters;

    private final InstantSource instantSource = InstantSource.system();

    @BeforeEach
    void commonSetUp() {
        final var validStartTime = Instant.ofEpochMilli(123456789L); // aligned to millisecond boundary for convenience.
        transactionID = transactionID(validStartTime, 0);
        otherNonceOneTransactionID = transactionID(validStartTime.plusNanos(1), 1);
        otherNonceTwoTransactionID = transactionID(validStartTime.plusNanos(1), 2);
        otherNonceThreeTransactionID = transactionID(validStartTime.plusNanos(1), 3);
        transactionIDNotInCache = transactionID(validStartTime.plusNanos(5), 5);

        givenValidAccount(false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        refreshStoresWithEntitiesOnlyInReadable();
        refreshRecordCache();
    }

    protected void refreshStoresWithEntitiesOnlyInReadable() {
        givenAccountsInReadableStore();
        givenTokensInReadableStore();
        givenReadableTokenRelsStore();
    }

    protected void refreshRecordCache() {
        final var state = new FakeState();
        final var registry = new FakeSchemaRegistry();
        final var svc = new RecordCacheService();
        svc.registerSchemas(registry);
        registry.migrate(svc.getServiceName(), state, startupNetworks);
        lenient().when(wsa.getState()).thenReturn(state);
        lenient().when(stack.getWritableStates(NAME)).thenReturn(state.getWritableStates(NAME));
        lenient().when(props.getConfiguration()).thenReturn(versionedConfig);
        lenient().when(versionedConfig.getConfigData(HederaConfig.class)).thenReturn(hederaConfig);
        lenient().when(hederaConfig.transactionMaxValidDuration()).thenReturn(123456789999L);
        lenient().when(versionedConfig.getConfigData(LedgerConfig.class)).thenReturn(ledgerConfig);
        lenient().when(ledgerConfig.recordsMaxQueryableByAccount()).thenReturn(MAX_QUERYABLE_PER_ACCOUNT);
        givenRecordCacheState();
    }

    private void givenAccountsInReadableStore() {
        readableAccounts = readableAccountState();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        readableAccountStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
    }

    private void givenTokensInReadableStore() {
        readableTokenState = readableTokenState();
        given(readableStates.<TokenID, Token>get(TOKENS_STATE_ID)).willReturn(readableTokenState);
        readableTokenStore = new ReadableTokenStoreImpl(readableStates, readableEntityCounters);
    }

    private void givenReadableTokenRelsStore() {
        readableTokenRelState = emptyReadableTokenRelsStateBuilder()
                .value(fungiblePair, fungibleTokenRelation)
                .value(nonFungiblePair, nonFungibleTokenRelation)
                .build();
        given(readableStates.<EntityIDPair, TokenRelation>get(TOKEN_RELS_STATE_ID))
                .willReturn(readableTokenRelState);
        readableTokenRelStore = new ReadableTokenRelationStoreImpl(readableStates, readableEntityCounters);
    }

    private void givenRecordCacheState() {
        cache = emptyRecordCacheBuilder();
        final var consensusTimestamp = Instant.ofEpochSecond(123456789L).plusNanos(1000);
        final var receipt = TransactionReceipt.newBuilder()
                .accountID(accountId)
                .status(ResponseCodeEnum.UNKNOWN)
                .build();
        primaryRecord = TransactionRecord.newBuilder()
                .transactionID(transactionID)
                .receipt(receipt)
                .consensusTimestamp(asTimestamp(consensusTimestamp))
                .build();
        duplicate1 = TransactionRecord.newBuilder()
                .transactionID(transactionID)
                .receipt(receipt)
                .consensusTimestamp(asTimestamp(consensusTimestamp.plusMillis(1)))
                .build();
        duplicate2 = TransactionRecord.newBuilder()
                .transactionID(transactionID)
                .receipt(receipt)
                .consensusTimestamp(asTimestamp(consensusTimestamp.plusMillis(2)))
                .build();
        duplicate3 = TransactionRecord.newBuilder()
                .transactionID(transactionID)
                .receipt(receipt)
                .consensusTimestamp(asTimestamp(consensusTimestamp.plusMillis(3)))
                .build();
        final var otherTxnId = otherNonceOneTransactionID.copyBuilder().nonce(0).build();
        otherRecord = TransactionRecord.newBuilder()
                .transactionID(otherTxnId)
                .receipt(receipt)
                .consensusTimestamp(asTimestamp(consensusTimestamp.plusNanos(5)))
                .build();
        recordOne = TransactionRecord.newBuilder()
                .transactionID(otherNonceOneTransactionID)
                .receipt(receipt)
                .consensusTimestamp(asTimestamp(consensusTimestamp.plusNanos(6)))
                .parentConsensusTimestamp(asTimestamp(consensusTimestamp))
                .build();
        recordTwo = TransactionRecord.newBuilder()
                .transactionID(otherNonceTwoTransactionID)
                .receipt(receipt)
                .consensusTimestamp(asTimestamp(consensusTimestamp.plusNanos(7)))
                .parentConsensusTimestamp(asTimestamp(consensusTimestamp))
                .build();
        recordThree = TransactionRecord.newBuilder()
                .transactionID(otherNonceThreeTransactionID)
                .receipt(receipt)
                .consensusTimestamp(asTimestamp(consensusTimestamp.plusNanos(8)))
                .parentConsensusTimestamp(asTimestamp(consensusTimestamp))
                .build();
        cache.addRecordSource(
                0,
                primaryRecord.transactionIDOrThrow(),
                HederaRecordCache.DueDiligenceFailure.NO,
                new PartialRecordSource(List.of(primaryRecord)));
        cache.addRecordSource(
                1,
                duplicate1.transactionIDOrThrow(),
                HederaRecordCache.DueDiligenceFailure.NO,
                new PartialRecordSource(List.of(duplicate1)));
        cache.addRecordSource(
                2,
                duplicate2.transactionIDOrThrow(),
                HederaRecordCache.DueDiligenceFailure.NO,
                new PartialRecordSource(List.of(duplicate2)));
        cache.addRecordSource(
                3,
                duplicate3.transactionIDOrThrow(),
                HederaRecordCache.DueDiligenceFailure.NO,
                new PartialRecordSource(List.of(duplicate3)));
        cache.addRecordSource(
                0,
                otherTxnId,
                HederaRecordCache.DueDiligenceFailure.NO,
                new PartialRecordSource(List.of(otherRecord, recordOne, recordTwo, recordThree)));
    }

    protected MapReadableKVState<AccountID, Account> readableAccountState() {
        return emptyReadableAccountStateBuilder()
                .value(accountId, account)
                .value(deleteAccountId, deleteAccount)
                .value(transferAccountId, transferAccount)
                .build();
    }

    @NonNull
    protected MapReadableKVState.Builder<AccountID, Account> emptyReadableAccountStateBuilder() {
        return MapReadableKVState.builder(ACCOUNTS_STATE_ID, ACCOUNTS_STATE_LABEL);
    }

    @NonNull
    protected MapReadableKVState.Builder<EntityIDPair, TokenRelation> emptyReadableTokenRelsStateBuilder() {
        return MapReadableKVState.builder(TOKEN_RELS_STATE_ID, TOKEN_RELS_STATE_LABEL);
    }

    @NonNull
    protected RecordCacheImpl emptyRecordCacheBuilder() {
        dedupeCache = new DeduplicationCacheImpl(props, instantSource);
        return new RecordCacheImpl(dedupeCache, wsa, props, networkInfo);
    }

    @NonNull
    protected MapReadableKVState<TokenID, Token> readableTokenState() {
        return MapReadableKVState.<TokenID, Token>builder(TOKENS_STATE_ID, TOKENS_STATE_LABEL)
                .value(fungibleTokenId, fungibleToken)
                .value(nonFungibleTokenId, nonFungibleToken)
                .build();
    }

    protected void givenValidFungibleToken() {
        givenValidFungibleToken(autoRenewId);
    }

    protected void givenValidFungibleToken(AccountID autoRenewAccountId) {
        givenValidFungibleToken(autoRenewAccountId, false, false, false, false, true, true);
    }

    protected void givenValidNonFungibleToken() {
        givenValidFungibleToken();
        nonFungibleToken = fungibleToken
                .copyBuilder()
                .tokenId(nonFungibleTokenId)
                .customFees(List.of())
                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                .build();
    }

    protected void givenValidFungibleToken(
            AccountID autoRenewAccountId,
            boolean deleted,
            boolean paused,
            boolean accountsFrozenByDefault,
            boolean accountsKycGrantedByDefault,
            boolean withAdminKey,
            boolean withSubmitKey) {
        fungibleToken = new Token(
                fungibleTokenId,
                tokenName,
                tokenSymbol,
                1000,
                1000,
                treasury,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                deleted,
                TokenType.FUNGIBLE_COMMON,
                TokenSupplyType.INFINITE,
                autoRenewAccountId,
                autoRenewSecs,
                expirationTime,
                memo,
                100000,
                paused,
                accountsFrozenByDefault,
                accountsKycGrantedByDefault,
                Collections.emptyList(),
                metadata,
                metadataKey);
    }

    protected void givenValidAccount(
            boolean isDeleted,
            @Nullable List<AccountCryptoAllowance> cryptoAllowances,
            @Nullable List<AccountApprovalForAllAllowance> approveForAllNftAllowances,
            @Nullable List<AccountFungibleTokenAllowance> tokenAllowances) {
        account = new Account(
                AccountID.newBuilder().accountNum(accountNum).build(),
                alias.alias(),
                null, //  key,
                1_234_567L,
                payerBalance,
                "testAccount",
                isDeleted,
                1_234L,
                1_234_568L,
                UNSET_STAKED_ID,
                true,
                true,
                TokenID.newBuilder().tokenNum(2L).build(),
                NftID.newBuilder().tokenId(TokenID.newBuilder().tokenNum(2L)).build(),
                1,
                2,
                10,
                1,
                3,
                false,
                2,
                0,
                1000L,
                AccountID.newBuilder().accountNum(2L).build(),
                72000,
                0,
                cryptoAllowances,
                approveForAllNftAllowances,
                tokenAllowances,
                2,
                false,
                null,
                null,
                0,
                0L,
                0L,
                0L);
    }

    protected void givenFungibleTokenRelation() {
        fungibleTokenRelation = TokenRelation.newBuilder()
                .tokenId(fungibleTokenId)
                .accountId(accountId)
                .balance(1000L)
                .frozen(false)
                .kycGranted(false)
                .automaticAssociation(true)
                .nextToken(asToken(0L))
                .previousToken(asToken(3L))
                .build();
    }

    protected void givenNonFungibleTokenRelation() {
        nonFungibleTokenRelation = TokenRelation.newBuilder()
                .tokenId(nonFungibleTokenId)
                .accountId(asAccount(0L, 0L, accountNum))
                .balance(1000L)
                .frozen(false)
                .kycGranted(false)
                .automaticAssociation(true)
                .nextToken(asToken(0L))
                .previousToken(asToken(3L))
                .build();
    }

    private TransactionID transactionID(Instant validStartTime, int nonce) {
        return TransactionID.newBuilder()
                .transactionValidStart(Timestamp.newBuilder()
                        .seconds(validStartTime.getEpochSecond())
                        .nanos(validStartTime.getNano()))
                .accountID(PAYER_ACCOUNT_ID)
                .nonce(nonce)
                .build();
    }

    protected TransactionID transactionIDWithoutAccount(int nanos, int nonce) {
        final var now = Instant.now();
        return TransactionID.newBuilder()
                .transactionValidStart(
                        Timestamp.newBuilder().seconds(now.getEpochSecond()).nanos(nanos))
                .nonce(nonce)
                .build();
    }
}
