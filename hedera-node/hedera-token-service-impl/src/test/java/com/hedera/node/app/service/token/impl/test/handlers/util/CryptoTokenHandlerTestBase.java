// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.handlers.util;

import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_LABEL;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.TokenServiceImpl.HBARS_TO_TINYBARS;
import static com.hedera.node.app.service.token.impl.TokenServiceImpl.ZONE_UTC;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.asAccount;
import static com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler.asToken;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.NFTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFOS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_INFOS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.STAKING_NETWORK_REWARDS_STATE_LABEL;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKEN_RELS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema.AIRDROPS_STATE_ID;
import static com.hedera.node.app.service.token.impl.test.handlers.util.CryptoHandlerTestBase.A_COMPLEX_KEY;
import static com.hedera.node.app.service.token.impl.test.handlers.util.CryptoHandlerTestBase.B_COMPLEX_KEY;
import static com.hedera.node.app.service.token.impl.test.handlers.util.CryptoHandlerTestBase.C_COMPLEX_KEY;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Fraction;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.PendingAirdropValue;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenSupplyType;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountApprovalForAllAllowance;
import com.hedera.hapi.node.state.token.AccountCryptoAllowance;
import com.hedera.hapi.node.state.token.AccountFungibleTokenAllowance;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.token.CryptoAllowance;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.hapi.node.token.TokenAllowance;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.FractionalFee;
import com.hedera.hapi.node.transaction.RoyaltyFee;
import com.hedera.node.app.ids.ReadableEntityIdStoreImpl;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableAirdropStore;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableAirdropStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableNetworkStakingRewardsStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableNftStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableStakingInfoStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenRelationStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenStoreImpl;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableAirdropStore;
import com.hedera.node.app.service.token.impl.WritableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.impl.WritableNftStore;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.records.FinalizeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.ids.ReadableEntityIdStore;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.validation.ExpiryValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Base class for testing both Crypto and Token implementations.
 */
@ExtendWith(MockitoExtension.class)
public class CryptoTokenHandlerTestBase extends StateBuilderUtil {
    public static final String UNKNOWN_FIELDS = "$unknownFields";
    protected static final Instant originalInstant = Instant.ofEpochSecond(12345678910L);
    protected static final long stakePeriodStart =
            LocalDate.ofInstant(originalInstant, ZONE_UTC).toEpochDay() - 1;
    protected static final Instant stakePeriodStartInstant =
            LocalDate.ofEpochDay(stakePeriodStart).atStartOfDay(ZoneOffset.UTC).toInstant();
    /* ---------- Keys */
    protected final Key key = A_COMPLEX_KEY;
    protected static final Key payerKey = A_COMPLEX_KEY;
    protected final Key ownerKey = B_COMPLEX_KEY;
    protected final Key spenderKey = C_COMPLEX_KEY;
    protected final Key adminKey = A_COMPLEX_KEY;
    protected final Key pauseKey = B_COMPLEX_KEY;
    protected final Key wipeKey = C_COMPLEX_KEY;
    protected final Key kycKey = A_COMPLEX_KEY;
    protected final Key feeScheduleKey = A_COMPLEX_KEY;
    protected final Key supplyKey = A_COMPLEX_KEY;
    protected final Key freezeKey = A_COMPLEX_KEY;
    protected final Key treasuryKey = C_COMPLEX_KEY;
    protected final Key EMPTY_KEYLIST =
            Key.newBuilder().keyList(KeyList.DEFAULT).build();
    protected final Key metadataKey = A_COMPLEX_KEY;
    /* ---------- Ids ---------- */
    protected static final Configuration configuration = HederaTestConfigBuilder.createConfig();
    protected static final long SHARD =
            configuration.getConfigData(HederaConfig.class).shard();
    protected static final long REALM =
            configuration.getConfigData(HederaConfig.class).realm();
    protected static EntityIdFactory idFactory = new FakeEntityIdFactoryImpl(SHARD, REALM);
    /* ---------- Node IDs */
    protected final EntityNumber node0Id = EntityNumber.newBuilder().number(0L).build();
    protected final EntityNumber node1Id = EntityNumber.newBuilder().number(1L).build();

    /* ---------- Account IDs */
    protected final AccountID payerId = idFactory.newAccountId(3);
    protected final AccountID deleteAccountId = idFactory.newAccountId(3213);
    protected final AccountID transferAccountId = idFactory.newAccountId(32134);
    protected final AccountID delegatingSpenderId = idFactory.newAccountId(1234567);
    protected final AccountID ownerId = idFactory.newAccountId(123456);
    protected final AccountID treasuryId = idFactory.newAccountId(1000000);
    protected final AccountID autoRenewId = idFactory.newAccountId(4);
    protected final AccountID spenderId = idFactory.newAccountId(12345);
    protected final AccountID feeCollectorId = transferAccountId;
    protected final AccountID stakingRewardId = idFactory.newAccountId(800);
    protected final AccountID zeroAccountId =
            AccountID.newBuilder().accountNum(0).build();

    /* ---------- Account Numbers ---------- */
    protected final Long accountNum = payerId.accountNum();

    /* ---------- Aliases ----------  */
    private static final Key aPrimitiveKey = Key.newBuilder()
            .ed25519(Bytes.wrap("01234567890123456789012345678901"))
            .build();
    private static final Bytes edKeyAlias = aPrimitiveKey.ed25519();
    protected final AccountID alias = AccountID.newBuilder().alias(edKeyAlias).build();
    protected final byte[] evmAddress = CommonUtils.unhex("6aea3773ea468a814d954e6dec795bfee7d76e25");
    protected final ContractID contractAlias =
            ContractID.newBuilder().evmAddress(Bytes.wrap(evmAddress)).build();
    /*Contracts */
    protected final ContractID contract =
            ContractID.newBuilder().contractNum(1234).build();
    /* ---------- Tokens ---------- */
    protected final TokenID fungibleTokenId = asToken(1L);

    protected final TokenID nonFungibleTokenId = asToken(2L);
    protected final TokenID fungibleTokenIDB = asToken(6L);
    protected final TokenID fungibleTokenIDC = asToken(7L);
    protected final TokenID fungibleTokenIDD = asToken(8L);
    protected final int hbarReceiver = 10000000;
    protected final AccountID hbarReceiverId =
            AccountID.newBuilder().accountNum(hbarReceiver).build();
    protected final int tokenReceiver = hbarReceiver + 1;
    protected final int tokenReceiverNoAssociation = tokenReceiver + 1;
    protected final AccountID tokenReceiverId =
            AccountID.newBuilder().accountNum(tokenReceiver).build();
    protected final AccountID tokenReceiverNoAssociationId =
            AccountID.newBuilder().accountNum(tokenReceiverNoAssociation).build();

    protected final EntityIDPair fungiblePair = EntityIDPair.newBuilder()
            .accountId(payerId)
            .tokenId(fungibleTokenId)
            .build();
    protected final EntityIDPair nonFungiblePair = EntityIDPair.newBuilder()
            .accountId(payerId)
            .tokenId(nonFungibleTokenId)
            .build();
    protected final EntityIDPair ownerFTPair = EntityIDPair.newBuilder()
            .accountId(ownerId)
            .tokenId(fungibleTokenId)
            .build();
    protected final EntityIDPair ownerNFTPair = EntityIDPair.newBuilder()
            .accountId(ownerId)
            .tokenId(nonFungibleTokenId)
            .build();

    protected final EntityIDPair feeCollectorFTPair = EntityIDPair.newBuilder()
            .accountId(feeCollectorId)
            .tokenId(fungibleTokenId)
            .build();
    protected final EntityIDPair feeCollectorNFTPair = EntityIDPair.newBuilder()
            .accountId(feeCollectorId)
            .tokenId(nonFungibleTokenId)
            .build();

    protected final EntityIDPair treasuryFTPair = EntityIDPair.newBuilder()
            .accountId(treasuryId)
            .tokenId(fungibleTokenId)
            .build();
    protected final EntityIDPair treasuryNFTPair = EntityIDPair.newBuilder()
            .accountId(treasuryId)
            .tokenId(nonFungibleTokenId)
            .build();
    protected final EntityIDPair ownerFTBPair = EntityIDPair.newBuilder()
            .accountId(ownerId)
            .tokenId(fungibleTokenIDB)
            .build();
    protected final EntityIDPair ownerFTCPair = EntityIDPair.newBuilder()
            .accountId(ownerId)
            .tokenId(fungibleTokenIDC)
            .build();
    protected final EntityIDPair feeCollectorFTBPair = EntityIDPair.newBuilder()
            .accountId(feeCollectorId)
            .tokenId(fungibleTokenIDB)
            .build();
    protected final EntityIDPair feeCollectorFTCPair = EntityIDPair.newBuilder()
            .accountId(feeCollectorId)
            .tokenId(fungibleTokenIDC)
            .build();
    protected final NftID nftIdSl1 =
            NftID.newBuilder().tokenId(nonFungibleTokenId).serialNumber(1L).build();
    protected final NftID nftIdSl2 =
            NftID.newBuilder().tokenId(nonFungibleTokenId).serialNumber(2L).build();

    /* ---------- Allowances --------------- */
    protected final CryptoAllowance cryptoAllowance = CryptoAllowance.newBuilder()
            .spender(spenderId)
            .owner(ownerId)
            .amount(10L)
            .build();
    protected final TokenAllowance tokenAllowance = TokenAllowance.newBuilder()
            .spender(spenderId)
            .amount(10L)
            .tokenId(fungibleTokenId)
            .owner(ownerId)
            .build();
    protected final NftAllowance nftAllowance = NftAllowance.newBuilder()
            .spender(spenderId)
            .owner(ownerId)
            .tokenId(nonFungibleTokenId)
            .serialNumbers(List.of(1L, 2L))
            .build();
    protected final NftAllowance nftAllowanceWithApproveForALl =
            nftAllowance.copyBuilder().approvedForAll(Boolean.TRUE).build();
    protected final NftAllowance nftAllowanceWithDelegatingSpender = NftAllowance.newBuilder()
            .spender(spenderId)
            .owner(ownerId)
            .tokenId(nonFungibleTokenId)
            .approvedForAll(Boolean.FALSE)
            .serialNumbers(List.of(1L, 2L))
            .delegatingSpender(delegatingSpenderId)
            .build();
    /* ---------- Fees ------------------ */
    protected FixedFee hbarFixedFee = FixedFee.newBuilder().amount(1_000L).build();
    protected FixedFee htsFixedFee = FixedFee.newBuilder()
            .amount(10L)
            .denominatingTokenId(fungibleTokenId)
            .build();
    protected FractionalFee fractionalFee = FractionalFee.newBuilder()
            .maximumAmount(100L)
            .minimumAmount(1L)
            .fractionalAmount(
                    Fraction.newBuilder().numerator(1).denominator(100).build())
            .netOfTransfers(false)
            .build();
    protected RoyaltyFee royaltyFee = RoyaltyFee.newBuilder()
            .exchangeValueFraction(
                    Fraction.newBuilder().numerator(1).denominator(2).build())
            .fallbackFee(hbarFixedFee)
            .build();
    protected CustomFee customFractionalFee = withFractionalFee(fractionalFee, feeCollectorId, false);
    protected List<CustomFee> customFees =
            List.of(withFixedFee(hbarFixedFee, feeCollectorId, false), customFractionalFee);

    /* ---------- Misc ---------- */
    protected static final Timestamp consensusTimestamp =
            Timestamp.newBuilder().seconds(1_234_567L).build();
    protected final Instant consensusInstant = Instant.ofEpochSecond(1_234_567L);
    protected final String tokenName = "test token";
    protected final String tokenSymbol = "TT";
    protected final String memo = "test memo";
    protected final Bytes metadata = Bytes.wrap(new byte[] {1, 2, 3, 4});
    protected final long expirationTime = 1_234_567L;
    protected final long autoRenewSecs = 100L;
    protected static final long payerBalance = 10_000L;
    /* ---------- States ---------- */
    protected MapReadableKVState<ProtoBytes, AccountID> readableAliases;
    protected MapReadableKVState<AccountID, Account> readableAccounts;
    protected MapWritableKVState<ProtoBytes, AccountID> writableAliases;
    protected MapWritableKVState<AccountID, Account> writableAccounts;
    protected MapReadableKVState<TokenID, Token> readableTokenState;
    protected MapWritableKVState<TokenID, Token> writableTokenState;
    protected MapReadableKVState<PendingAirdropId, AccountPendingAirdrop> readableAirdropState;
    protected MapWritableKVState<PendingAirdropId, AccountPendingAirdrop> writableAirdropState;
    protected MapReadableKVState<EntityIDPair, TokenRelation> readableTokenRelState;
    protected MapWritableKVState<EntityIDPair, TokenRelation> writableTokenRelState;
    protected MapReadableKVState<NftID, Nft> readableNftState;
    protected MapWritableKVState<NftID, Nft> writableNftState;
    protected MapReadableKVState<EntityNumber, StakingNodeInfo> readableStakingInfoState;
    protected MapWritableKVState<EntityNumber, StakingNodeInfo> writableStakingInfoState;
    protected ReadableSingletonState<NetworkStakingRewards> readableRewardsState;
    protected WritableSingletonState<NetworkStakingRewards> writableRewardsState;

    /* ---------- Stores */

    protected ReadableTokenStore readableTokenStore;
    protected WritableTokenStore writableTokenStore;

    protected ReadableAccountStore readableAccountStore;
    protected WritableAccountStore writableAccountStore;
    protected ReadableAirdropStore readableAirdropStore;
    protected WritableAirdropStore writableAirdropStore;
    protected ReadableTokenRelationStore readableTokenRelStore;
    protected WritableTokenRelationStore writableTokenRelStore;
    protected ReadableNftStore readableNftStore;
    protected WritableNftStore writableNftStore;

    protected ReadableNetworkStakingRewardsStore readableRewardsStore;
    protected WritableNetworkStakingRewardsStore writableRewardsStore;

    protected ReadableStakingInfoStore readableStakingInfoStore;
    protected WritableStakingInfoStore writableStakingInfoStore;

    /* ---------- StakingInfos ---------- */
    protected StakingNodeInfo node0Info;
    protected StakingNodeInfo node1Info;
    /* ---------- Tokens ---------- */
    protected Token fungibleToken;
    protected Token fungibleTokenB;
    protected Token fungibleTokenC;
    protected Token fungibleTokenD;
    protected Token nonFungibleToken;
    protected Nft nftSl1;
    protected Nft nftSl2;
    /* ---------- Token Relations ---------- */
    protected TokenRelation fungibleTokenRelation;
    protected TokenRelation nonFungibleTokenRelation;
    protected TokenRelation ownerFTRelation;
    protected TokenRelation ownerNFTRelation;
    protected TokenRelation treasuryFTRelation;
    protected TokenRelation treasuryNFTRelation;
    protected TokenRelation feeCollectorFTRelation;
    protected TokenRelation feeCollectorNFTRelation;
    protected TokenRelation ownerFTBRelation;
    protected TokenRelation ownerFTCRelation;
    protected TokenRelation feeCollectorFTBRelation;
    protected TokenRelation feeCollectorFTCRelation;

    /* ---------- Accounts ---------- */
    protected Account account;
    protected Account deleteAccount;
    protected Account transferAccount;
    protected Account ownerAccount;
    protected Account spenderAccount;
    protected Account delegatingSpenderAccount;
    protected Account treasuryAccount;
    protected Account stakingRewardAccount;
    protected Account tokenReceiverAccount;
    protected Account tokenReceiverNoAssociationsAccount;
    protected Account hbarReceiverAccount;
    protected Account zeroAccount;

    /* ---------- Maps for updating both readable and writable stores ---------- */
    private Map<AccountID, Account> accountsMap;
    private Map<ProtoBytes, AccountID> aliasesMap;
    private Map<TokenID, Token> tokensMap;
    private Map<EntityIDPair, TokenRelation> tokenRelsMap;
    private Map<Long, StakingNodeInfo> stakingNodeInfoMap;
    private Map<PendingAirdropId, AccountPendingAirdrop> pendingAirdropMap;

    @Mock
    protected ReadableStates readableStates;

    @Mock
    protected WritableStates writableStates;

    @Mock(strictness = Strictness.LENIENT)
    protected StoreFactory storeFactory;

    protected ReadableEntityIdStore readableEntityCounters;
    protected WritableEntityIdStore writableEntityCounters;

    protected VersionedConfigImpl versionedConfig;
    /**
     * Sets up the test environment.
     */
    @BeforeEach
    public void setUp() {
        handlerTestBaseInternalSetUp(true);
    }

    protected void handlerTestBaseInternalSetUp(final boolean prepopulateReceiverIds) {
        versionedConfig = new VersionedConfigImpl(configuration, 1);
        givenValidAccounts();
        givenValidTokens();
        givenValidTokenRelations();
        givenStakingInfos();
        setUpAllEntities(prepopulateReceiverIds);
        refreshReadableStores();
        refreshWritableStores();
    }

    private void setUpAllEntities(final boolean prepopulateReceiverIds) {
        accountsMap = new HashMap<>();
        accountsMap.put(payerId, account);
        if (prepopulateReceiverIds) {
            accountsMap.put(hbarReceiverId, hbarReceiverAccount);
            accountsMap.put(tokenReceiverId, tokenReceiverAccount);
            accountsMap.put(tokenReceiverNoAssociationId, tokenReceiverNoAssociationsAccount);
        }
        accountsMap.put(deleteAccountId, deleteAccount);
        accountsMap.put(transferAccountId, transferAccount);
        accountsMap.put(ownerId, ownerAccount);
        accountsMap.put(delegatingSpenderId, delegatingSpenderAccount);
        accountsMap.put(spenderId, spenderAccount);
        accountsMap.put(treasuryId, treasuryAccount);
        accountsMap.put(zeroAccountId, zeroAccount);
        accountsMap.put(stakingRewardId, stakingRewardAccount);

        tokensMap = new HashMap<>();
        tokensMap.put(fungibleTokenId, fungibleToken);
        tokensMap.put(nonFungibleTokenId, nonFungibleToken);
        tokensMap.put(fungibleTokenIDB, fungibleTokenB);
        tokensMap.put(fungibleTokenIDC, fungibleTokenC);
        tokensMap.put(fungibleTokenIDD, fungibleTokenD);

        aliasesMap = new HashMap<>();
        aliasesMap.put(new ProtoBytes(alias.alias()), payerId);
        aliasesMap.put(new ProtoBytes(contractAlias.evmAddress()), asAccount(0L, 0L, contract.contractNum()));

        tokenRelsMap = new HashMap<>();
        tokenRelsMap.put(fungiblePair, fungibleTokenRelation);
        tokenRelsMap.put(nonFungiblePair, nonFungibleTokenRelation);
        tokenRelsMap.put(ownerFTPair, ownerFTRelation);
        tokenRelsMap.put(ownerNFTPair, ownerNFTRelation);
        tokenRelsMap.put(treasuryFTPair, treasuryFTRelation);
        tokenRelsMap.put(treasuryNFTPair, treasuryNFTRelation);
        tokenRelsMap.put(feeCollectorFTPair, feeCollectorFTRelation);
        tokenRelsMap.put(feeCollectorNFTPair, feeCollectorNFTRelation);
        tokenRelsMap.put(ownerFTBPair, ownerFTBRelation);
        tokenRelsMap.put(ownerFTCPair, ownerFTCRelation);
        tokenRelsMap.put(feeCollectorFTBPair, feeCollectorFTBRelation);
        tokenRelsMap.put(feeCollectorFTCPair, feeCollectorFTCRelation);

        stakingNodeInfoMap = new HashMap<>();
        stakingNodeInfoMap.put(0L, node0Info);
        stakingNodeInfoMap.put(1L, node1Info);
        pendingAirdropMap = new HashMap<>();
    }

    protected void givenAssociatedReceiver(AccountID accountID, TokenID tokenID) {
        EntityIDPair pair =
                EntityIDPair.newBuilder().accountId(accountID).tokenId(tokenID).build();
        TokenRelation rel = TokenRelation.newBuilder()
                .tokenId(tokenID)
                .accountId(accountID)
                .balance(0L)
                .frozen(false)
                .kycGranted(true)
                .automaticAssociation(true)
                .build();

        tokenRelsMap.put(pair, rel);
    }

    protected void givenPendingFungibleTokenAirdrop(
            TokenID tokenID, AccountID senderID, AccountID receiverID, long amount) {
        PendingAirdropId id = PendingAirdropId.newBuilder()
                .fungibleTokenType(tokenID)
                .receiverId(receiverID)
                .senderId(senderID)
                .build();
        PendingAirdropValue value =
                PendingAirdropValue.newBuilder().amount(amount).build();
        var senderAccount = accountsMap.get(senderID);
        var sendersAirdropCount = senderAccount.numberPendingAirdrops();
        if (senderAccount.hasHeadPendingAirdropId()) {
            var headId = senderAccount.headPendingAirdropIdOrThrow();
            var headPending = pendingAirdropMap.get(headId);
            final var updatedAirdrop =
                    headPending.copyBuilder().previousAirdrop(id).build();
            pendingAirdropMap.put(headId, updatedAirdrop);
            var airdrop = AccountPendingAirdrop.newBuilder()
                    .pendingAirdropValue(value)
                    .nextAirdrop(headId)
                    .build();
            pendingAirdropMap.put(id, airdrop);
            // update sender account
            var updatedSenderAccount = senderAccount
                    .copyBuilder()
                    .numberPendingAirdrops(sendersAirdropCount + 1)
                    .headPendingAirdropId(id)
                    .build();
            accountsMap.put(senderID, updatedSenderAccount);
        } else {
            var updatedSenderAccount = senderAccount
                    .copyBuilder()
                    .numberPendingAirdrops(sendersAirdropCount + 1)
                    .headPendingAirdropId(id)
                    .build();
            accountsMap.put(senderID, updatedSenderAccount);
            var airdrop = AccountPendingAirdrop.newBuilder()
                    .pendingAirdropValue(value)
                    .build();
            pendingAirdropMap.put(id, airdrop);
        }
    }

    // todo: create new tokens instead of updating existing
    protected void removeTokenCustomFee(TokenID tokenId) {
        var token = tokensMap.get(tokenId);
        var newToken = token.copyBuilder()
                .customFees(Collections.emptyList())
                .kycKey((Key) null)
                .build();
        tokensMap.put(tokenId, newToken);
    }

    protected void basicMetaAssertions(final PreHandleContext context, final int keysSize) {
        assertThat(context.requiredNonPayerKeys()).hasSize(keysSize);
    }

    protected void refreshReadableStores() {
        givenEntityCounters();
        givenAccountsInReadableStore();
        givenTokensInReadableStore();
        givenReadableTokenRelsStore();
        givenReadableAirdropStore();
        givenReadableNftStore();
        givenReadableAirdropStore();
        givenReadableStakingRewardsStore();
        givenReadableStakingInfoStore();
    }

    protected void refreshWritableStores() {
        givenEntityCounters();
        givenAccountsInWritableStore();
        givenTokensInWritableStore();
        givenWritableTokenRelsStore();
        givenWritableAirdropStore();
        givenWritableNftStore();
        givenWritableStakingRewardsStore();
        givenWritableStakingInfoStore();
    }

    private void givenEntityCounters() {
        final var entityCounts = EntityCounts.newBuilder()
                .numAliases(aliasesMap.size())
                .numAccounts(accountsMap.size())
                .numTokens(tokensMap.size())
                .numTokenRelations(tokenRelsMap.size())
                .numNfts(2)
                .numAirdrops(pendingAirdropMap.size())
                .build();
        given(writableStates.getSingleton(ENTITY_ID_STATE_ID))
                .willReturn(new FunctionWritableSingletonState<>(
                        ENTITY_ID_STATE_ID,
                        ENTITY_ID_STATE_LABEL,
                        () -> EntityNumber.newBuilder().build(),
                        c -> {}));
        given(writableStates.getSingleton(ENTITY_COUNTS_STATE_ID))
                .willReturn(new FunctionWritableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID, ENTITY_COUNTS_STATE_LABEL, () -> entityCounts, c -> {}));
        given(readableStates.getSingleton(ENTITY_ID_STATE_ID))
                .willReturn(new FunctionReadableSingletonState<>(
                        ENTITY_ID_STATE_ID, ENTITY_ID_STATE_LABEL, () -> EntityNumber.newBuilder()
                                .build()));
        given(readableStates.getSingleton(ENTITY_COUNTS_STATE_ID))
                .willReturn(new FunctionReadableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID, ENTITY_COUNTS_STATE_LABEL, () -> entityCounts));
        readableEntityCounters = new ReadableEntityIdStoreImpl(readableStates);
        writableEntityCounters = new WritableEntityIdStore(writableStates);
    }

    private void givenAccountsInReadableStore() {
        readableAccounts = readableAccountState();
        writableAccounts = emptyWritableAccountStateBuilder().build();
        readableAliases = readableAliasState();
        writableAliases = emptyWritableAliasStateBuilder().build();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        given(readableStates.<ProtoBytes, AccountID>get(ALIASES_STATE_ID)).willReturn(readableAliases);
        given(writableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(writableAccounts);
        given(writableStates.<ProtoBytes, AccountID>get(ALIASES_STATE_ID)).willReturn(writableAliases);
        readableAccountStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        writableAccountStore = new WritableAccountStore(writableStates, writableEntityCounters);
    }

    private void givenAccountsInWritableStore() {
        givenEntityCounters();
        readableAccounts = readableAccountState();
        writableAccounts = writableAccountState();
        readableAliases = readableAliasState();
        writableAliases = writableAliasesState();
        given(readableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(readableAccounts);
        given(readableStates.<ProtoBytes, AccountID>get(ALIASES_STATE_ID)).willReturn(readableAliases);
        given(writableStates.<AccountID, Account>get(ACCOUNTS_STATE_ID)).willReturn(writableAccounts);
        given(writableStates.<ProtoBytes, AccountID>get(ALIASES_STATE_ID)).willReturn(writableAliases);
        readableAccountStore = new ReadableAccountStoreImpl(readableStates, readableEntityCounters);
        writableAccountStore = new WritableAccountStore(writableStates, writableEntityCounters);
    }

    private void givenTokensInReadableStore() {
        readableTokenState = readableTokenState();
        writableTokenState = emptyWritableTokenState();
        given(readableStates.<TokenID, Token>get(TOKENS_STATE_ID)).willReturn(readableTokenState);
        given(writableStates.<TokenID, Token>get(TOKENS_STATE_ID)).willReturn(writableTokenState);
        readableTokenStore = new ReadableTokenStoreImpl(readableStates, readableEntityCounters);
        writableTokenStore = new WritableTokenStore(writableStates, writableEntityCounters);
    }

    private void givenTokensInWritableStore() {
        readableTokenState = readableTokenState();
        writableTokenState = writableTokenState();
        given(readableStates.<TokenID, Token>get(TOKENS_STATE_ID)).willReturn(readableTokenState);
        given(writableStates.<TokenID, Token>get(TOKENS_STATE_ID)).willReturn(writableTokenState);
        readableTokenStore = new ReadableTokenStoreImpl(readableStates, readableEntityCounters);
        writableTokenStore = new WritableTokenStore(writableStates, writableEntityCounters);
    }

    private void givenReadableStakingInfoStore() {
        readableStakingInfoState = MapReadableKVState.<EntityNumber, StakingNodeInfo>builder(
                        STAKING_INFOS_STATE_ID, STAKING_INFOS_STATE_LABEL)
                .value(node0Id, node0Info)
                .value(node1Id, node1Info)
                .build();
        given(readableStates.<EntityNumber, StakingNodeInfo>get(STAKING_INFOS_STATE_ID))
                .willReturn(readableStakingInfoState);
        readableStakingInfoStore = new ReadableStakingInfoStoreImpl(readableStates, readableEntityCounters);
    }

    private void givenWritableStakingInfoStore() {
        writableStakingInfoState = MapWritableKVState.<EntityNumber, StakingNodeInfo>builder(
                        STAKING_INFOS_STATE_ID, STAKING_INFOS_STATE_LABEL)
                .value(node0Id, node0Info)
                .value(node1Id, node1Info)
                .build();
        given(writableStates.<EntityNumber, StakingNodeInfo>get(STAKING_INFOS_STATE_ID))
                .willReturn(writableStakingInfoState);
        final var entityIdStore = new WritableEntityIdStore(new MapWritableStates(Map.of(
                ENTITY_ID_STATE_ID,
                new FunctionWritableSingletonState<>(ENTITY_ID_STATE_ID, ENTITY_ID_STATE_LABEL, () -> null, c -> {}),
                ENTITY_COUNTS_STATE_ID,
                new FunctionWritableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID, ENTITY_COUNTS_STATE_LABEL, () -> null, c -> {}))));
        writableStakingInfoStore = new WritableStakingInfoStore(writableStates, entityIdStore);
    }

    private void givenReadableStakingRewardsStore() {
        final AtomicReference<NetworkStakingRewards> backingValue =
                new AtomicReference<>(new NetworkStakingRewards(true, 100000L, 50000L, 1000L, Timestamp.DEFAULT));
        final var stakingRewardsState = new FunctionReadableSingletonState<>(
                STAKING_NETWORK_REWARDS_STATE_ID, STAKING_NETWORK_REWARDS_STATE_LABEL, backingValue::get);
        given(readableStates.getSingleton(STAKING_NETWORK_REWARDS_STATE_ID))
                .willReturn((ReadableSingletonState) stakingRewardsState);
        readableRewardsStore = new ReadableNetworkStakingRewardsStoreImpl(readableStates);
    }

    private void givenWritableStakingRewardsStore() {
        final AtomicReference<NetworkStakingRewards> backingValue =
                new AtomicReference<>(new NetworkStakingRewards(true, 100000L, 50000L, 1000L, Timestamp.DEFAULT));
        final var stakingRewardsState = new FunctionWritableSingletonState<>(
                STAKING_NETWORK_REWARDS_STATE_ID,
                STAKING_NETWORK_REWARDS_STATE_LABEL,
                backingValue::get,
                backingValue::set);
        given(writableStates.getSingleton(STAKING_NETWORK_REWARDS_STATE_ID))
                .willReturn((WritableSingletonState) stakingRewardsState);
        writableRewardsStore = new WritableNetworkStakingRewardsStore(writableStates);
    }

    private void givenReadableTokenRelsStore() {
        readableTokenRelState = readableTokenRelState();
        given(readableStates.<EntityIDPair, TokenRelation>get(TOKEN_RELS_STATE_ID))
                .willReturn(readableTokenRelState);
        readableTokenRelStore = new ReadableTokenRelationStoreImpl(readableStates, readableEntityCounters);
    }

    private void givenWritableTokenRelsStore() {
        writableTokenRelState = writableTokenRelState();
        given(writableStates.<EntityIDPair, TokenRelation>get(TOKEN_RELS_STATE_ID))
                .willReturn(writableTokenRelState);
        writableTokenRelStore = new WritableTokenRelationStore(writableStates, writableEntityCounters);
    }

    private void givenReadableNftStore() {
        readableNftState = emptyReadableNftStateBuilder()
                .value(nftIdSl1, nftSl1)
                .value(nftIdSl2, nftSl2)
                .build();
        given(readableStates.<NftID, Nft>get(NFTS_STATE_ID)).willReturn(readableNftState);
        readableNftStore = new ReadableNftStoreImpl(readableStates, readableEntityCounters);
    }

    private void givenWritableNftStore() {
        writableNftState = emptyWritableNftStateBuilder()
                .value(nftIdSl1, nftSl1)
                .value(nftIdSl2, nftSl2)
                .build();
        given(writableStates.<NftID, Nft>get(NFTS_STATE_ID)).willReturn(writableNftState);
        writableNftStore = new WritableNftStore(writableStates, writableEntityCounters);
    }

    private void givenReadableAirdropStore() {
        readableAirdropState = readableAirdropState();
        doReturn(readableAirdropState)
                .when(readableStates)
                .<PendingAirdropId, AccountPendingAirdrop>get(AIRDROPS_STATE_ID);
        readableAirdropStore = new ReadableAirdropStoreImpl(readableStates, readableEntityCounters);
    }

    private void givenWritableAirdropStore() {
        writableAirdropState = writableAirdropState();
        given(writableStates.<PendingAirdropId, AccountPendingAirdrop>get(AIRDROPS_STATE_ID))
                .willReturn(writableAirdropState);
        writableAirdropStore = new WritableAirdropStore(writableStates, writableEntityCounters);
    }

    @NonNull
    protected MapWritableKVState<AccountID, Account> writableAccountState() {
        final var builder = emptyWritableAccountStateBuilder();
        for (final var entry : accountsMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    @NonNull
    protected MapReadableKVState<AccountID, Account> readableAccountState() {
        final var builder = emptyReadableAccountStateBuilder();
        for (final var entry : accountsMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private MapWritableKVState<EntityIDPair, TokenRelation> writableTokenRelState() {
        final var builder = emptyWritableTokenRelsStateBuilder();
        for (final var entry : tokenRelsMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private MapReadableKVState<EntityIDPair, TokenRelation> readableTokenRelState() {
        final var builder = emptyReadableTokenRelsStateBuilder();
        for (final var entry : tokenRelsMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    @NonNull
    protected MapWritableKVState<ProtoBytes, AccountID> writableAliasesState() {
        final var builder = emptyWritableAliasStateBuilder();
        for (final var entry : aliasesMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private MapReadableKVState<PendingAirdropId, AccountPendingAirdrop> readableAirdropState() {
        final var builder = emptyReadableAirdropStateBuilder();
        for (final var entry : pendingAirdropMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    @NonNull
    protected MapWritableKVState<PendingAirdropId, AccountPendingAirdrop> writableAirdropState() {
        final var builder = emptyWritableAirdropStateBuilder();
        for (final var entry : pendingAirdropMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    @NonNull
    protected MapReadableKVState<ProtoBytes, AccountID> readableAliasState() {
        final var builder = emptyReadableAliasStateBuilder();
        for (final var entry : aliasesMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    @NonNull
    protected MapWritableKVState<TokenID, Token> writableTokenState() {
        final var builder = emptyWritableTokenStateBuilder();
        for (final var entry : tokensMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    @NonNull
    protected MapReadableKVState<TokenID, Token> readableTokenState() {
        final var builder = emptyReadableTokenStateBuilder();
        for (final var entry : tokensMap.entrySet()) {
            builder.value(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private void givenValidTokenRelations() {
        fungibleTokenRelation = givenFungibleTokenRelation();
        nonFungibleTokenRelation = givenNonFungibleTokenRelation();
        ownerFTRelation =
                givenFungibleTokenRelation().copyBuilder().accountId(ownerId).build();
        ownerFTBRelation = givenFungibleTokenRelation()
                .copyBuilder()
                .accountId(ownerId)
                .tokenId(fungibleTokenIDB)
                .build();
        ownerFTCRelation = givenFungibleTokenRelation()
                .copyBuilder()
                .tokenId(fungibleTokenIDC)
                .accountId(ownerId)
                .build();
        ownerNFTRelation =
                givenNonFungibleTokenRelation().copyBuilder().accountId(ownerId).build();
        treasuryFTRelation =
                givenFungibleTokenRelation().copyBuilder().accountId(treasuryId).build();
        treasuryNFTRelation = givenNonFungibleTokenRelation()
                .copyBuilder()
                .accountId(treasuryId)
                .build();
        feeCollectorFTRelation = givenFungibleTokenRelation()
                .copyBuilder()
                .accountId(feeCollectorId)
                .build();
        feeCollectorNFTRelation = givenNonFungibleTokenRelation()
                .copyBuilder()
                .accountId(feeCollectorId)
                .build();
        feeCollectorFTBRelation = givenFungibleTokenRelation()
                .copyBuilder()
                .accountId(feeCollectorId)
                .tokenId(fungibleTokenIDB)
                .build();
        feeCollectorFTCRelation = givenFungibleTokenRelation()
                .copyBuilder()
                .tokenId(fungibleTokenIDC)
                .accountId(feeCollectorId)
                .build();
    }

    private void givenStakingInfos() {
        node0Info = StakingNodeInfo.newBuilder()
                .nodeNumber(0)
                .stake(1000L)
                .stakeToNotReward(400L)
                .stakeToReward(666L * HBARS_TO_TINYBARS)
                .stakeRewardStart(2 * 555L * HBARS_TO_TINYBARS)
                .maxStake(1000000000L)
                .minStake(500000000)
                .weight(200)
                .rewardSumHistory(List.of(300L, 200L, 100L))
                .unclaimedStakeRewardStart(0L)
                .pendingRewards(1000000)
                .build();
        node1Info = StakingNodeInfo.newBuilder()
                .nodeNumber(1)
                .stake(1000L)
                .stakeToNotReward(400L)
                .stakeToReward(666L * HBARS_TO_TINYBARS)
                .stakeRewardStart(2 * 555L * HBARS_TO_TINYBARS)
                .maxStake(1000000000L)
                .minStake(500000000)
                .weight(300)
                .rewardSumHistory(List.of(300L, 200L, 100L))
                .unclaimedStakeRewardStart(0L)
                .pendingRewards(1000000)
                .build();
    }

    private void givenValidTokens() {
        fungibleToken = givenValidFungibleToken();
        fungibleTokenB = givenValidFungibleToken()
                .copyBuilder()
                .tokenId(fungibleTokenIDB)
                .customFees(withFixedFee(
                        FixedFee.newBuilder()
                                .denominatingTokenId(fungibleTokenIDC)
                                .amount(1000)
                                .build(),
                        feeCollectorId,
                        false))
                .build();
        fungibleTokenC = givenValidFungibleToken()
                .copyBuilder()
                .tokenId(fungibleTokenIDC)
                .customFees(withFixedFee(
                        FixedFee.newBuilder()
                                .denominatingTokenId(fungibleTokenId)
                                .amount(40)
                                .build(),
                        feeCollectorId,
                        false))
                .build();
        fungibleTokenD = givenValidFungibleToken()
                .copyBuilder()
                .tokenId(fungibleTokenIDD)
                .customFees(Collections.emptyList())
                .kycKey((Key) null)
                .build();
        nonFungibleToken = givenValidNonFungibleToken(true);
        nftSl1 = givenNft(nftIdSl1).copyBuilder().ownerNextNftId(nftIdSl2).build();
        nftSl2 = givenNft(nftIdSl2).copyBuilder().ownerPreviousNftId(nftIdSl1).build();
    }

    private void givenValidAccounts() {
        account = givenValidAccountBuilder().stakedNodeId(1L).build();
        spenderAccount = givenValidAccountBuilder()
                .key(spenderKey)
                .accountId(spenderId)
                .headNftSerialNumber(0L)
                .headNftId((NftID) null)
                .build();
        ownerAccount = givenValidAccountBuilder()
                .accountId(ownerId)
                .cryptoAllowances(AccountCryptoAllowance.newBuilder()
                        .spenderId(spenderId)
                        .amount(1000)
                        .build())
                .tokenAllowances(AccountFungibleTokenAllowance.newBuilder()
                        .tokenId(fungibleTokenId)
                        .spenderId(spenderId)
                        .amount(1000)
                        .build())
                .approveForAllNftAllowances(AccountApprovalForAllAllowance.newBuilder()
                        .tokenId(nonFungibleTokenId)
                        .spenderId(spenderId)
                        .build())
                .key(ownerKey)
                .build();
        delegatingSpenderAccount =
                givenValidAccountBuilder().accountId(delegatingSpenderId).build();
        transferAccount =
                givenValidAccountBuilder().accountId(transferAccountId).build();
        treasuryAccount = givenValidAccountBuilder()
                .accountId(treasuryId)
                .key(treasuryKey)
                .build();
        stakingRewardAccount = givenValidAccountBuilder()
                .accountId(stakingRewardId)
                .key(EMPTY_KEYLIST)
                .build();
        tokenReceiverAccount = givenValidAccountBuilder()
                .accountId(tokenReceiverId)
                .tinybarBalance(Long.MAX_VALUE)
                .headNftId((NftID) null)
                .headNftSerialNumber(0L)
                .receiverSigRequired(false)
                .build();
        tokenReceiverNoAssociationsAccount = givenValidAccountBuilder()
                .build()
                .copyBuilder()
                .accountId(tokenReceiverNoAssociationId)
                .tinybarBalance(Long.MAX_VALUE)
                .headNftId((NftID) null)
                .headNftSerialNumber(0L)
                .maxAutoAssociations(0)
                .usedAutoAssociations(0)
                .build();
        tokenReceiverNoAssociationsAccount = givenValidAccountBuilder()
                .build()
                .copyBuilder()
                .accountId(tokenReceiverNoAssociationId)
                .tinybarBalance(Long.MAX_VALUE)
                .headNftId((NftID) null)
                .headNftSerialNumber(0L)
                .maxAutoAssociations(0)
                .usedAutoAssociations(0)
                .build();
        hbarReceiverAccount = givenValidAccountBuilder()
                .accountId(hbarReceiverId)
                .tinybarBalance(Long.MAX_VALUE)
                .headNftId((NftID) null)
                .headNftSerialNumber(0L)
                .build();
        zeroAccount = givenValidAccountBuilder()
                .accountId(zeroAccountId)
                .key(EMPTY_KEYLIST)
                .build();
    }

    protected Token givenValidFungibleToken() {
        return givenValidFungibleToken(spenderId);
    }

    protected Token givenValidFungibleToken(AccountID autoRenewAccountId) {
        return givenValidFungibleToken(autoRenewAccountId, false, false, false, false, true);
    }

    protected Token givenValidFungibleToken(
            AccountID autoRenewAccountId,
            boolean deleted,
            boolean paused,
            boolean accountsFrozenByDefault,
            boolean accountsKycGrantedByDefault,
            boolean hasKyc) {
        return new Token(
                fungibleTokenId,
                tokenName,
                tokenSymbol,
                1000,
                1000,
                treasuryId,
                adminKey,
                hasKyc ? kycKey : null,
                freezeKey,
                wipeKey,
                supplyKey,
                feeScheduleKey,
                pauseKey,
                2,
                deleted,
                TokenType.FUNGIBLE_COMMON,
                TokenSupplyType.FINITE,
                autoRenewAccountId,
                autoRenewSecs,
                expirationTime,
                memo,
                100000,
                paused,
                accountsFrozenByDefault,
                accountsKycGrantedByDefault,
                customFees,
                metadata,
                metadataKey);
    }

    protected Token givenValidNonFungibleToken(boolean hasKyc) {
        return fungibleToken
                .copyBuilder()
                .tokenId(nonFungibleTokenId)
                .treasuryAccountId(treasuryId)
                .customFees(List.of(withRoyaltyFee(royaltyFee, feeCollectorId)))
                .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                .kycKey(hasKyc ? kycKey : null)
                .build();
    }

    protected Account.Builder givenValidAccountBuilder() {
        return Account.newBuilder()
                .accountId(payerId)
                .tinybarBalance(payerBalance)
                .alias(alias.alias())
                .key(key)
                .expirationSecond(1_234_567L)
                .memo("testAccount")
                .deleted(false)
                .stakedToMe(1_234L * HBARS_TO_TINYBARS)
                .stakePeriodStart(stakePeriodStart)
                .declineReward(false)
                .receiverSigRequired(true)
                .headTokenId(TokenID.newBuilder().tokenNum(3L).build())
                .headNftId(NftID.newBuilder()
                        .tokenId(TokenID.newBuilder().tokenNum(2L))
                        .serialNumber(1L)
                        .build())
                .headNftSerialNumber(1L)
                .numberOwnedNfts(2L)
                .maxAutoAssociations(10)
                .usedAutoAssociations(1)
                .numberAssociations(3)
                .smartContract(false)
                .numberPositiveBalances(2)
                .ethereumNonce(0L)
                .stakeAtStartOfLastRewardedPeriod(1000L)
                .autoRenewAccountId(AccountID.DEFAULT)
                .autoRenewSeconds(72000L)
                .contractKvPairsNumber(0)
                .cryptoAllowances(emptyList())
                .tokenAllowances(emptyList())
                .approveForAllNftAllowances(emptyList())
                .numberTreasuryTitles(2)
                .expiredAndPendingRemoval(false)
                .firstContractStorageKey(null);
    }

    protected TokenRelation givenFungibleTokenRelation() {
        return TokenRelation.newBuilder()
                .tokenId(fungibleTokenId)
                .accountId(payerId)
                .balance(1000L)
                .frozen(false)
                .kycGranted(true)
                .automaticAssociation(true)
                .nextToken(asToken(2L))
                .previousToken(asToken(3L))
                .build();
    }

    protected TokenRelation givenNonFungibleTokenRelation() {
        return TokenRelation.newBuilder()
                .tokenId(nonFungibleTokenId)
                .accountId(payerId)
                .balance(1)
                .frozen(false)
                .kycGranted(true)
                .automaticAssociation(true)
                .nextToken(asToken(2L))
                .previousToken(asToken(3L))
                .build();
    }

    protected Nft givenNft(NftID tokenID) {
        return Nft.newBuilder()
                .ownerId(ownerId)
                .metadata(Bytes.wrap("test"))
                .nftId(tokenID)
                .build();
    }

    public static CustomFee withFixedFee(
            final FixedFee fixedFee, final AccountID feeCollectorId, final boolean allCollectorsExempt) {
        return CustomFee.newBuilder()
                .feeCollectorAccountId(feeCollectorId)
                .allCollectorsAreExempt(allCollectorsExempt)
                .fixedFee(fixedFee)
                .build();
    }

    public static CustomFee withFractionalFee(
            final FractionalFee fractionalFee, final AccountID feeCollectorId, final boolean allCollectorsExempt) {
        return CustomFee.newBuilder()
                .fractionalFee(fractionalFee)
                .feeCollectorAccountId(feeCollectorId)
                .allCollectorsAreExempt(allCollectorsExempt)
                .build();
    }

    public static CustomFee withRoyaltyFee(final RoyaltyFee royaltyFee, final AccountID feeCollectorId) {
        return CustomFee.newBuilder()
                .royaltyFee(royaltyFee)
                .feeCollectorAccountId(feeCollectorId)
                .build();
    }

    protected CustomFee withFixedFee(final FixedFee fixedFee) {
        return CustomFee.newBuilder()
                .feeCollectorAccountId(feeCollectorId)
                .fixedFee(fixedFee)
                .build();
    }

    protected CustomFee withFractionalFee(final FractionalFee fractionalFee) {
        return CustomFee.newBuilder()
                .fractionalFee(fractionalFee)
                .feeCollectorAccountId(feeCollectorId)
                .build();
    }

    protected CustomFee withRoyaltyFee(final RoyaltyFee royaltyFee) {
        return CustomFee.newBuilder()
                .royaltyFee(royaltyFee)
                .feeCollectorAccountId(feeCollectorId)
                .build();
    }

    protected void givenStoresAndConfig(final HandleContext context) {
        given(context.configuration()).willReturn(configuration);
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(storeFactory.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);

        given(storeFactory.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(storeFactory.readableStore(ReadableTokenStore.class)).willReturn(readableTokenStore);

        given(storeFactory.readableStore(ReadableAirdropStore.class)).willReturn(readableAirdropStore);
        given(storeFactory.writableStore(WritableAirdropStore.class)).willReturn(writableAirdropStore);

        given(storeFactory.readableStore(ReadableTokenRelationStore.class)).willReturn(readableTokenRelStore);
        given(storeFactory.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);

        given(storeFactory.readableStore(ReadableNftStore.class)).willReturn(readableNftStore);
        given(storeFactory.writableStore(WritableNftStore.class)).willReturn(writableNftStore);

        given(storeFactory.readableStore(ReadableNetworkStakingRewardsStore.class))
                .willReturn(readableRewardsStore);
        given(storeFactory.writableStore(WritableNetworkStakingRewardsStore.class))
                .willReturn(writableRewardsStore);

        given(storeFactory.readableStore(ReadableStakingInfoStore.class)).willReturn(readableStakingInfoStore);
        given(storeFactory.writableStore(WritableStakingInfoStore.class)).willReturn(writableStakingInfoStore);

        given(storeFactory.readableStore(ReadableNetworkStakingRewardsStore.class))
                .willReturn(readableRewardsStore);
        given(storeFactory.writableStore(WritableNetworkStakingRewardsStore.class))
                .willReturn(writableRewardsStore);

        given(storeFactory.readableStore(ReadableEntityIdStore.class)).willReturn(readableEntityCounters);
        given(storeFactory.writableStore(WritableEntityIdStore.class)).willReturn(writableEntityCounters);

        given(context.dispatchComputeFees(any(), any(), any())).willReturn(new Fees(1L, 2L, 3L));

        final var expiryValidator = mock(ExpiryValidator.class);
        lenient().when(context.expiryValidator()).thenReturn(expiryValidator);
        lenient()
                .when(expiryValidator.expirationStatus(any(), anyBoolean(), anyLong()))
                .thenReturn(ResponseCodeEnum.OK);
    }

    protected void givenStoresAndConfig(final FinalizeContext context) {
        given(context.configuration()).willReturn(configuration);
        given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);
        given(context.readableStore(ReadableAccountStore.class)).willReturn(readableAccountStore);

        given(context.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
        given(context.readableStore(ReadableTokenStore.class)).willReturn(readableTokenStore);

        given(context.readableStore(ReadableTokenRelationStore.class)).willReturn(readableTokenRelStore);
        given(context.writableStore(WritableTokenRelationStore.class)).willReturn(writableTokenRelStore);

        given(context.readableStore(ReadableNftStore.class)).willReturn(readableNftStore);
        given(context.writableStore(WritableNftStore.class)).willReturn(writableNftStore);

        given(context.readableStore(ReadableNetworkStakingRewardsStore.class)).willReturn(readableRewardsStore);
        given(context.writableStore(WritableNetworkStakingRewardsStore.class)).willReturn(writableRewardsStore);

        given(context.readableStore(ReadableStakingInfoStore.class)).willReturn(readableStakingInfoStore);
        given(context.writableStore(WritableStakingInfoStore.class)).willReturn(writableStakingInfoStore);

        given(context.readableStore(ReadableNetworkStakingRewardsStore.class)).willReturn(readableRewardsStore);
        given(context.writableStore(WritableNetworkStakingRewardsStore.class)).willReturn(writableRewardsStore);
    }
}
