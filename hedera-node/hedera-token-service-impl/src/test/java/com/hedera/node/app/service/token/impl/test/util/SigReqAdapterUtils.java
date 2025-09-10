// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.util;

import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ALIASES_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKEN_RELS_KEY;
import static com.hedera.node.app.service.token.impl.test.handlers.util.AdapterUtils.mockStates;
import static com.hedera.node.app.service.token.impl.test.handlers.util.AdapterUtils.mockWritableStates;
import static com.hedera.node.app.service.token.impl.test.handlers.util.AdapterUtils.wellKnownAliasState;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.COMPLEX_KEY_ACCOUNT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.COMPLEX_KEY_ACCOUNT_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.CUSTOM_PAYER_ACCOUNT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.CUSTOM_PAYER_ACCOUNT_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.DEFAULT_BALANCE;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.DEFAULT_NODE;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.DEFAULT_PAYER;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.DEFAULT_PAYER_BALANCE;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.DEFAULT_PAYER_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.DELEGATING_SPENDER;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.DELEGATING_SPENDER_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.DELETED_TOKEN;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.DILIGENT_SIGNING_PAYER;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.DILIGENT_SIGNING_PAYER_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.FIRST_TOKEN_SENDER;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.FIRST_TOKEN_SENDER_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.FROM_OVERLAP_PAYER;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.FROM_OVERLAP_PAYER_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_IMMUTABLE;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_NO_SPECIAL_KEYS;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_WITH_FEE_SCHEDULE_KEY;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_WITH_FREEZE;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_WITH_KYC;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_WITH_PAUSE;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_WITH_ROYALTY_FEE_AND_FALLBACK;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_WITH_SUPPLY;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.KNOWN_TOKEN_WITH_WIPE;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.MASTER_PAYER;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.MISC_ACCOUNT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.MISC_ACCOUNT_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.NO_RECEIVER_SIG;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.NO_RECEIVER_SIG_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.OWNER_ACCOUNT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.OWNER_ACCOUNT_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.RECEIVER_SIG;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.RECEIVER_SIG_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.SECOND_TOKEN_SENDER;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.SECOND_TOKEN_SENDER_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.STAKING_FUND;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.SYS_ACCOUNT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.SYS_ACCOUNT_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.TOKEN_ADMIN_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.TOKEN_FEE_SCHEDULE_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.TOKEN_FREEZE_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.TOKEN_KYC_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.TOKEN_PAUSE_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.TOKEN_RECEIVER;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.TOKEN_SUPPLY_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.TOKEN_TREASURY;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.TOKEN_TREASURY_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.TOKEN_WIPE_KT;
import static com.hedera.node.app.service.token.impl.test.keys.KeysAndIds.TREASURY_PAYER;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Fraction;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountApprovalForAllAllowance;
import com.hedera.hapi.node.state.token.AccountCryptoAllowance;
import com.hedera.hapi.node.state.token.AccountFungibleTokenAllowance;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.FixedFee;
import com.hedera.hapi.node.transaction.RoyaltyFee;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.ReadableAccountStoreImpl;
import com.hedera.node.app.service.token.impl.ReadableTokenStoreImpl;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Provides utility methods for testing signature requirements in the token service.
 */
@ExtendWith(MockitoExtension.class)
public class SigReqAdapterUtils {
    private static final String TOKENS_KEY = "TOKENS";
    private static final String ACCOUNTS_KEY = "ACCOUNTS";
    private static final Configuration CONFIGURATION = HederaTestConfigBuilder.createConfig();

    protected final Bytes metadata = Bytes.wrap(new byte[] {1, 2, 3, 4});
    /**
     * Represents an unset staked ID.
     */
    public static final OneOf<Account.StakedIdOneOfType> UNSET_STAKED_ID =
            new OneOf<>(Account.StakedIdOneOfType.UNSET, null);

    private static final AccountCryptoAllowance CRYPTO_ALLOWANCES = AccountCryptoAllowance.newBuilder()
            .spenderId(AccountID.newBuilder()
                    .accountNum(DEFAULT_PAYER.getAccountNum())
                    .build())
            .amount(500L)
            .build();
    private static final AccountFungibleTokenAllowance FUNGIBLE_TOKEN_ALLOWANCES =
            AccountFungibleTokenAllowance.newBuilder()
                    .tokenId(TokenID.newBuilder()
                            .tokenNum(KNOWN_TOKEN_NO_SPECIAL_KEYS.getTokenNum())
                            .build())
                    .spenderId(AccountID.newBuilder()
                            .accountNum(DEFAULT_PAYER.getAccountNum())
                            .build())
                    .amount(10_000L)
                    .build();

    private static final AccountApprovalForAllAllowance NFT_ALLOWANCES = AccountApprovalForAllAllowance.newBuilder()
            .tokenId(TokenID.newBuilder()
                    .tokenNum(KNOWN_TOKEN_WITH_WIPE.getTokenNum())
                    .build())
            .spenderId(AccountID.newBuilder()
                    .accountNum(DEFAULT_PAYER.getAccountNum())
                    .build())
            .build();

    @Mock
    private ReadableEntityCounters readableEntityCounters;

    /**
     * Returns the {@link ReadableTokenStore} containing the "well-known" tokens that exist in a
     * {@code SigRequirementsTest} scenario. This allows us to re-use these scenarios in unit tests
     * that require a {@link ReadableTokenStore}.
     *
     * @return the well-known token store
     */
    public static ReadableTokenStore wellKnownTokenStoreAt() {
        final var state = wellKnownTokenState();
        return new ReadableTokenStoreImpl(mockStates(Map.of(TOKENS_KEY, state)), mock(ReadableEntityCounters.class));
    }

    /**
     * Returns the {@link WritableTokenStore} containing the "well-known" tokens that exist in a
     * {@code SigRequirementsTest} scenario (see also {@link #wellKnownTokenStoreAt()})
     *
     * @return the well-known token store
     */
    public static WritableTokenStore wellKnownWritableTokenStoreAt() {
        return new WritableTokenStore(
                mockWritableStates(Map.of(TOKENS_KEY, wellKnownTokenState())), mock(WritableEntityCounters.class));
    }

    private static WritableKVState<TokenID, Token> wellKnownTokenState() {
        final Map<TokenID, Token> destination = new HashMap<>();
        destination.put(
                toPbj(KNOWN_TOKEN_IMMUTABLE),
                Token.newBuilder()
                        .tokenId(toPbj(KNOWN_TOKEN_IMMUTABLE))
                        .maxSupply(Long.MAX_VALUE)
                        .totalSupply(100)
                        .symbol("ImmutableToken")
                        .name("ImmutableTokenName")
                        .treasuryAccountId(AccountID.newBuilder().accountNum(3).build())
                        .build());
        destination.put(
                toPbj(KNOWN_TOKEN_NO_SPECIAL_KEYS),
                Token.newBuilder()
                        .tokenId(toPbj(KNOWN_TOKEN_NO_SPECIAL_KEYS))
                        .maxSupply(Long.MAX_VALUE)
                        .totalSupply(100)
                        .symbol("VanillaToken")
                        .name("TOKENNAME")
                        .adminKey(TOKEN_ADMIN_KT.asPbjKey())
                        .treasuryAccountId(AccountID.newBuilder().accountNum(3).build())
                        .build());
        destination.put(
                toPbj(KNOWN_TOKEN_WITH_PAUSE),
                Token.newBuilder()
                        .tokenId(toPbj(KNOWN_TOKEN_WITH_PAUSE))
                        .maxSupply(Long.MAX_VALUE)
                        .totalSupply(100)
                        .symbol("PausedToken")
                        .name("PAUSEDTOKEN")
                        .adminKey(TOKEN_ADMIN_KT.asPbjKey())
                        .pauseKey(TOKEN_PAUSE_KT.asPbjKey())
                        .paused(true)
                        .treasuryAccountId(AccountID.newBuilder().accountNum(3).build())
                        .build());
        destination.put(
                toPbj(KNOWN_TOKEN_WITH_FREEZE),
                Token.newBuilder()
                        .tokenId(toPbj(KNOWN_TOKEN_WITH_FREEZE))
                        .maxSupply(Long.MAX_VALUE)
                        .totalSupply(100)
                        .symbol("FrozenToken")
                        .name("FRZNTKN")
                        .adminKey(TOKEN_ADMIN_KT.asPbjKey())
                        .freezeKey(TOKEN_FREEZE_KT.asPbjKey())
                        .accountsFrozenByDefault(true)
                        .treasuryAccountId(AccountID.newBuilder().accountNum(3).build())
                        .build());
        destination.put(
                toPbj(KNOWN_TOKEN_WITH_KYC),
                Token.newBuilder()
                        .tokenId(toPbj(KNOWN_TOKEN_WITH_KYC))
                        .maxSupply(Long.MAX_VALUE)
                        .totalSupply(100)
                        .symbol("KycToken")
                        .name("KYCTOKENNAME")
                        .adminKey(TOKEN_ADMIN_KT.asPbjKey())
                        .kycKey(TOKEN_KYC_KT.asPbjKey())
                        .treasuryAccountId(AccountID.newBuilder().accountNum(4).build())
                        .build());
        destination.put(
                toPbj(KNOWN_TOKEN_WITH_FEE_SCHEDULE_KEY),
                Token.newBuilder()
                        .tokenId(toPbj(KNOWN_TOKEN_WITH_FEE_SCHEDULE_KEY))
                        .maxSupply(Long.MAX_VALUE)
                        .totalSupply(100)
                        .symbol("FsToken")
                        .name("FEE_SCHEDULETOKENNAME")
                        .feeScheduleKey(TOKEN_FEE_SCHEDULE_KT.asPbjKey())
                        .accountsKycGrantedByDefault(true)
                        .treasuryAccountId(AccountID.newBuilder().accountNum(4).build())
                        .build());
        destination.put(
                toPbj(KNOWN_TOKEN_WITH_ROYALTY_FEE_AND_FALLBACK),
                Token.newBuilder()
                        .tokenId(toPbj(KNOWN_TOKEN_WITH_ROYALTY_FEE_AND_FALLBACK))
                        .maxSupply(Long.MAX_VALUE)
                        .totalSupply(100)
                        .symbol("ZPHYR")
                        .name("West Wind Art")
                        .feeScheduleKey(TOKEN_FEE_SCHEDULE_KT.asPbjKey())
                        .accountsKycGrantedByDefault(true)
                        .treasuryAccountId(
                                AccountID.newBuilder().accountNum(1339).build())
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .customFees(CustomFee.newBuilder()
                                .royaltyFee(RoyaltyFee.newBuilder()
                                        .exchangeValueFraction(new Fraction(1, 2))
                                        .fallbackFee(
                                                FixedFee.newBuilder().amount(1).build())
                                        .build())
                                .build())
                        .build());
        destination.put(
                toPbj(KNOWN_TOKEN_WITH_SUPPLY),
                Token.newBuilder()
                        .tokenId(toPbj(KNOWN_TOKEN_WITH_SUPPLY))
                        .maxSupply(Long.MAX_VALUE)
                        .totalSupply(100)
                        .symbol("SupplyToken")
                        .name("SUPPLYTOKENNAME")
                        .adminKey(TOKEN_ADMIN_KT.asPbjKey())
                        .supplyKey(TOKEN_SUPPLY_KT.asPbjKey())
                        .treasuryAccountId(AccountID.newBuilder().accountNum(4).build())
                        .build());
        destination.put(
                toPbj(KNOWN_TOKEN_WITH_WIPE),
                Token.newBuilder()
                        .tokenId(toPbj(KNOWN_TOKEN_WITH_WIPE))
                        .maxSupply(Long.MAX_VALUE)
                        .totalSupply(100)
                        .symbol("WipeToken")
                        .name("WIPETOKENNAME")
                        .adminKey(TOKEN_ADMIN_KT.asPbjKey())
                        .wipeKey(TOKEN_WIPE_KT.asPbjKey())
                        .treasuryAccountId(AccountID.newBuilder().accountNum(4).build())
                        .build());
        destination.put(
                toPbj(DELETED_TOKEN),
                Token.newBuilder()
                        .tokenId(toPbj(DELETED_TOKEN))
                        .maxSupply(Long.MAX_VALUE)
                        .totalSupply(100)
                        .symbol("DeletedToken")
                        .name("DELETEDTOKENNAME")
                        .adminKey(TOKEN_ADMIN_KT.asPbjKey())
                        .deleted(true)
                        .treasuryAccountId(AccountID.newBuilder().accountNum(4).build())
                        .build());
        return new MapWritableKVState<>(TokenService.NAME, TOKENS_KEY, destination);
    }

    /**
     * Returns the {@link WritableTokenRelationStore} containing the "well-known" token relations that exist in a
     * {@code SigRequirementsTest} scenario.
     * @return the well-known token relation store
     */
    public static WritableTokenRelationStore wellKnownTokenRelStoreAt() {
        final var destination = new HashMap<EntityIDPair, TokenRelation>();
        destination.put(
                EntityIDPair.newBuilder()
                        .accountId(toPbj(MISC_ACCOUNT))
                        .tokenId(toPbj(KNOWN_TOKEN_IMMUTABLE))
                        .build(),
                TokenRelation.newBuilder()
                        .accountId(toPbj(MISC_ACCOUNT))
                        .tokenId(toPbj(KNOWN_TOKEN_IMMUTABLE))
                        .balance(10)
                        .build());
        destination.put(
                EntityIDPair.newBuilder()
                        .accountId(toPbj(MISC_ACCOUNT))
                        .tokenId(toPbj(KNOWN_TOKEN_NO_SPECIAL_KEYS))
                        .build(),
                TokenRelation.newBuilder()
                        .accountId(toPbj(MISC_ACCOUNT))
                        .tokenId(toPbj(KNOWN_TOKEN_NO_SPECIAL_KEYS))
                        .balance(20)
                        .build());
        destination.put(
                EntityIDPair.newBuilder()
                        .accountId(toPbj(MISC_ACCOUNT))
                        .tokenId(toPbj(KNOWN_TOKEN_WITH_KYC))
                        .build(),
                TokenRelation.newBuilder()
                        .accountId(toPbj(MISC_ACCOUNT))
                        .tokenId(toPbj(KNOWN_TOKEN_WITH_KYC))
                        .balance(30)
                        .build());

        final var wrappedState = new MapWritableKVState<>(TokenService.NAME, TOKEN_RELS_KEY, destination);
        return new WritableTokenRelationStore(
                mockWritableStates(Map.of(TOKEN_RELS_KEY, wrappedState)), mock(WritableEntityCounters.class));
    }

    /**
     * Returns the {@link ReadableAccountStore} containing the "well-known" accounts that exist in a
     * {@code SigRequirementsTest} scenario.
     * @return the well-known account store
     */
    public static ReadableAccountStoreImpl wellKnownAccountStoreAt() {
        return new ReadableAccountStoreImpl(
                mockStates(Map.of(ACCOUNTS_KEY, wrappedAccountState(), ALIASES_KEY, wellKnownAliasState())),
                mock(ReadableEntityCounters.class));
    }

    /**
     * Returns the {@link WritableAccountStore} containing the "well-known" accounts that exist in a
     * {@code SigRequirementsTest} scenario.
     * @return the well-known account store
     */
    public static WritableAccountStore wellKnownWritableAccountStoreAt() {
        return new WritableAccountStore(
                mockWritableStates(Map.of(ACCOUNTS_KEY, wrappedAccountState(), ALIASES_KEY, wellKnownAliasState())),
                mock(WritableEntityCounters.class));
    }

    private static WritableKVState<AccountID, Account> wrappedAccountState() {
        final var destination = new HashMap<AccountID, Account>();
        destination.put(
                toPbj(FIRST_TOKEN_SENDER),
                toPbjAccount(FIRST_TOKEN_SENDER.getAccountNum(), FIRST_TOKEN_SENDER_KT.asPbjKey(), 10_000L));
        destination.put(
                toPbj(SECOND_TOKEN_SENDER),
                toPbjAccount(SECOND_TOKEN_SENDER.getAccountNum(), SECOND_TOKEN_SENDER_KT.asPbjKey(), 10_000L));
        destination.put(
                toPbj(TOKEN_RECEIVER), toPbjAccount(TOKEN_RECEIVER.getAccountNum(), TOKEN_WIPE_KT.asPbjKey(), 0L));
        destination.put(
                toPbj(DEFAULT_NODE), toPbjAccount(DEFAULT_NODE.getAccountNum(), DEFAULT_PAYER_KT.asPbjKey(), 0L));
        destination.put(
                toPbj(DEFAULT_PAYER),
                toPbjAccount(DEFAULT_PAYER.getAccountNum(), DEFAULT_PAYER_KT.asPbjKey(), DEFAULT_PAYER_BALANCE));
        destination.put(toPbj(STAKING_FUND), toPbjAccount(STAKING_FUND.getAccountNum(), IMMUTABILITY_SENTINEL_KEY, 0L));
        destination.put(
                toPbj(MASTER_PAYER),
                toPbjAccount(MASTER_PAYER.getAccountNum(), DEFAULT_PAYER_KT.asPbjKey(), DEFAULT_PAYER_BALANCE));
        destination.put(
                toPbj(TREASURY_PAYER),
                toPbjAccount(TREASURY_PAYER.getAccountNum(), DEFAULT_PAYER_KT.asPbjKey(), DEFAULT_PAYER_BALANCE));
        destination.put(
                toPbj(NO_RECEIVER_SIG),
                toPbjAccount(NO_RECEIVER_SIG.getAccountNum(), NO_RECEIVER_SIG_KT.asPbjKey(), DEFAULT_BALANCE));
        destination.put(
                toPbj(RECEIVER_SIG),
                toPbjAccount(RECEIVER_SIG.getAccountNum(), RECEIVER_SIG_KT.asPbjKey(), DEFAULT_BALANCE, true));
        destination.put(
                toPbj(SYS_ACCOUNT),
                toPbjAccount(SYS_ACCOUNT.getAccountNum(), SYS_ACCOUNT_KT.asPbjKey(), DEFAULT_BALANCE));
        destination.put(
                toPbj(MISC_ACCOUNT),
                toPbjAccount(MISC_ACCOUNT.getAccountNum(), MISC_ACCOUNT_KT.asPbjKey(), DEFAULT_BALANCE));
        destination.put(
                toPbj(CUSTOM_PAYER_ACCOUNT),
                toPbjAccount(
                        CUSTOM_PAYER_ACCOUNT.getAccountNum(), CUSTOM_PAYER_ACCOUNT_KT.asPbjKey(), DEFAULT_BALANCE));
        destination.put(
                toPbj(OWNER_ACCOUNT),
                toPbjAccount(
                        OWNER_ACCOUNT.getAccountNum(),
                        OWNER_ACCOUNT_KT.asPbjKey(),
                        DEFAULT_BALANCE,
                        false,
                        List.of(CRYPTO_ALLOWANCES),
                        List.of(FUNGIBLE_TOKEN_ALLOWANCES),
                        List.of(NFT_ALLOWANCES)));
        destination.put(
                toPbj(DELEGATING_SPENDER),
                toPbjAccount(
                        DELEGATING_SPENDER.getAccountNum(),
                        DELEGATING_SPENDER_KT.asPbjKey(),
                        DEFAULT_BALANCE,
                        false,
                        List.of(CRYPTO_ALLOWANCES),
                        List.of(FUNGIBLE_TOKEN_ALLOWANCES),
                        List.of(NFT_ALLOWANCES)));
        destination.put(
                toPbj(COMPLEX_KEY_ACCOUNT),
                toPbjAccount(COMPLEX_KEY_ACCOUNT.getAccountNum(), COMPLEX_KEY_ACCOUNT_KT.asPbjKey(), DEFAULT_BALANCE));
        destination.put(
                toPbj(TOKEN_TREASURY),
                toPbjAccount(TOKEN_TREASURY.getAccountNum(), TOKEN_TREASURY_KT.asPbjKey(), DEFAULT_BALANCE));
        destination.put(
                toPbj(DILIGENT_SIGNING_PAYER),
                toPbjAccount(
                        DILIGENT_SIGNING_PAYER.getAccountNum(), DILIGENT_SIGNING_PAYER_KT.asPbjKey(), DEFAULT_BALANCE));
        destination.put(
                toPbj(FROM_OVERLAP_PAYER),
                toPbjAccount(FROM_OVERLAP_PAYER.getAccountNum(), FROM_OVERLAP_PAYER_KT.asPbjKey(), DEFAULT_BALANCE));
        return new MapWritableKVState<>(TokenService.NAME, ACCOUNTS_KEY, destination);
    }

    private static Account toPbjAccount(final long number, final Key key, long balance) {
        return toPbjAccount(number, key, balance, false, List.of(), List.of(), List.of());
    }

    private static Account toPbjAccount(
            final long number, final Key key, long balance, final boolean isReceiverSigRequired) {
        return toPbjAccount(number, key, balance, isReceiverSigRequired, List.of(), List.of(), List.of());
    }

    private static Account toPbjAccount(
            final long number,
            final Key key,
            long balance,
            boolean receiverSigRequired,
            List<AccountCryptoAllowance> cryptoAllowances,
            List<AccountFungibleTokenAllowance> fungibleTokenAllowances,
            List<AccountApprovalForAllAllowance> nftTokenAllowances) {
        return new Account(
                AccountID.newBuilder().accountNum(number).build(),
                Bytes.EMPTY,
                key,
                10_000L,
                balance,
                "test",
                false,
                1_234_567L,
                1_234_567L,
                UNSET_STAKED_ID,
                false,
                receiverSigRequired,
                TokenID.newBuilder().tokenNum(3L).build(),
                NftID.newBuilder().tokenId(TokenID.newBuilder().tokenNum(2L)).build(),
                1L,
                2,
                3,
                3,
                3,
                false,
                3,
                0,
                1_234_5678L,
                AccountID.newBuilder().accountNum(2L).build(),
                76_000L,
                0,
                cryptoAllowances,
                nftTokenAllowances,
                fungibleTokenAllowances,
                2,
                false,
                null,
                null,
                0,
                0L,
                0L,
                0L);
    }
}
