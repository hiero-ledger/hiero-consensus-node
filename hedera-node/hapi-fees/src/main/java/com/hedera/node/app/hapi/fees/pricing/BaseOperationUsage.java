// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsageUtils.TOKEN_OPS_USAGE_UTILS;
import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;
import static com.hederahashgraph.api.proto.java.SubType.SCHEDULE_CREATE_CONTRACT_CALL;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;

import com.google.protobuf.ByteString;
import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import com.hedera.node.app.hapi.fees.usage.BaseTransactionMeta;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.consensus.ConsensusOpsUsage;
import com.hedera.node.app.hapi.fees.usage.consensus.SubmitMessageMeta;
import com.hedera.node.app.hapi.fees.usage.contract.ExtantContractContext;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoApproveAllowanceMeta;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoCreateMeta;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoDeleteAllowanceMeta;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoOpsUsage;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoTransferMeta;
import com.hedera.node.app.hapi.fees.usage.crypto.CryptoUpdateMeta;
import com.hedera.node.app.hapi.fees.usage.crypto.ExtantCryptoContext;
import com.hedera.node.app.hapi.fees.usage.file.FileAppendMeta;
import com.hedera.node.app.hapi.fees.usage.file.FileOpsUsage;
import com.hedera.node.app.hapi.fees.usage.schedule.ScheduleOpsUsage;
import com.hedera.node.app.hapi.fees.usage.state.UsageAccumulator;
import com.hedera.node.app.hapi.fees.usage.token.TokenOpsUsage;
import com.hedera.node.app.hapi.fees.usage.token.meta.ExtantFeeScheduleContext;
import com.hedera.node.app.hapi.fees.usage.token.meta.FeeScheduleUpdateMeta;
import com.hedera.node.app.hapi.fees.usage.util.UtilOpsUsage;
import com.hedera.node.app.hapi.fees.usage.util.UtilPrngMeta;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractCallTransactionBody;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.CryptoAllowance;
import com.hederahashgraph.api.proto.java.CryptoApproveAllowanceTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoCreateTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoDeleteAllowanceTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.CustomFee;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.FixedFee;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.NftRemoveAllowance;
import com.hederahashgraph.api.proto.java.SchedulableTransactionBody;
import com.hederahashgraph.api.proto.java.ScheduleCreateTransactionBody;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignaturePair;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenBurnTransactionBody;
import com.hederahashgraph.api.proto.java.TokenCreateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenMintTransactionBody;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.TokenWipeAccountTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransferList;
import com.hederahashgraph.api.proto.java.UtilPrngTransactionBody;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Provides the resource usage of the "base configuration" for each Hedera operation.
 *
 * <p>The base configuration of an operation is usually the cheapest version of the operation that
 * still does something useful. (For example, the base CryptoTransfer adjusts only two ℏ accounts
 * using one signature, the base TokenFeeScheduleUpdate adds a single custom HTS fee to a token,
 * etc.)
 */
public class BaseOperationUsage {
    static final Logger log = LogManager.getLogger(BaseOperationUsage.class);
    public static final int CANONICAL_NUM_CONTRACT_KV_PAIRS = 64;
    public static final long THREE_MONTHS_IN_SECONDS = 7776000L;
    private static final ByteString CANONICAL_SIG =
            ByteString.copyFromUtf8("0123456789012345678901234567890123456789012345678901234567890123");
    private static final List<Long> SINGLE_SERIAL_NUM = List.of(1L);
    private static final ByteString CANONICAL_NFT_METADATA = ByteString.copyFromUtf8(
            "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789");
    private static final SignatureMap ONE_PAIR_SIG_MAP = SignatureMap.newBuilder()
            .addSigPair(SignaturePair.newBuilder()
                    .setPubKeyPrefix(ByteString.copyFromUtf8("a"))
                    .setEd25519(CANONICAL_SIG))
            .build();
    private static final SignatureMap TWO_PAIR_SIG_MAP = SignatureMap.newBuilder()
            .addSigPair(SignaturePair.newBuilder()
                    .setPubKeyPrefix(ByteString.copyFromUtf8("a"))
                    .setEd25519(CANONICAL_SIG))
            .addSigPair(SignaturePair.newBuilder()
                    .setPubKeyPrefix(ByteString.copyFromUtf8("b"))
                    .setEd25519(CANONICAL_SIG))
            .build();
    private static final SignatureMap FOUR_PAIR_SIG_MAP = SignatureMap.newBuilder()
            .addSigPair(SignaturePair.newBuilder()
                    .setPubKeyPrefix(ByteString.copyFromUtf8("a"))
                    .setEd25519(CANONICAL_SIG))
            .addSigPair(SignaturePair.newBuilder()
                    .setPubKeyPrefix(ByteString.copyFromUtf8("b"))
                    .setEd25519(CANONICAL_SIG))
            .addSigPair(SignaturePair.newBuilder()
                    .setPubKeyPrefix(ByteString.copyFromUtf8("c"))
                    .setEd25519(CANONICAL_SIG))
            .addSigPair(SignaturePair.newBuilder()
                    .setPubKeyPrefix(ByteString.copyFromUtf8("d"))
                    .setEd25519(CANONICAL_SIG))
            .build();
    private static final SigUsage SINGLE_SIG_USAGE = new SigUsage(1, ONE_PAIR_SIG_MAP.getSerializedSize(), 1);
    private static final SigUsage DUAL_SIG_USAGE = new SigUsage(2, TWO_PAIR_SIG_MAP.getSerializedSize(), 1);
    private static final SigUsage QUAD_SIG_USAGE = new SigUsage(4, FOUR_PAIR_SIG_MAP.getSerializedSize(), 1);
    private static final BaseTransactionMeta NO_MEMO_AND_NO_EXPLICIT_XFERS = new BaseTransactionMeta(0, 0);
    private static final Key A_KEY = Key.newBuilder()
            .setEd25519(ByteString.copyFromUtf8("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            .build();
    private static final AccountID AN_ACCOUNT =
            AccountID.newBuilder().setAccountNum(1_234L).build();

    private static final String A_TOKEN_NAME = "012345678912";
    private static final String A_TOKEN_SYMBOL = "ABCD";
    private static final String BLANK_MEMO = "";

    private static final TokenOpsUsage TOKEN_OPS_USAGE = new TokenOpsUsage();
    private static final ConsensusOpsUsage CONSENSUS_OPS_USAGE = new ConsensusOpsUsage();
    private static final CryptoOpsUsage CRYPTO_OPS_USAGE = new CryptoOpsUsage();
    private static final FileOpsUsage FILE_OPS_USAGE = new FileOpsUsage();
    private static final ScheduleOpsUsage SCHEDULE_OPS_USAGE = new ScheduleOpsUsage();

    private static final UtilOpsUsage UTIL_OPS_USAGE = new UtilOpsUsage();

    /**
     * Returns the total resource usage in the new {@link UsageAccumulator} process object for the
     * base configuration of the given type of the given operation.
     *
     * @param function the operation of interest
     * @param type the type of interest
     * @return the total resource usage of the base configuration
     */
    UsageAccumulator baseUsageFor(final HederaFunctionality function, final SubType type) {
        switch (function) {
            case ContractAutoRenew:
                if (type == DEFAULT) {
                    return contractAutoRenew();
                }
                break;
            case FileAppend:
                if (type == DEFAULT) {
                    return fileAppend();
                }
                break;
            case CryptoTransfer:
                switch (type) {
                    case DEFAULT:
                        return hbarCryptoTransfer();
                    case TOKEN_FUNGIBLE_COMMON:
                        return htsCryptoTransfer();
                    case TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES:
                        return htsCryptoTransferWithCustomFee();
                    case TOKEN_NON_FUNGIBLE_UNIQUE:
                        return nftCryptoTransfer();
                    case TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES:
                        return nftCryptoTransferWithCustomFee();
                    default:
                        break;
                }
                break;
            case CryptoCreate:
                if (type == DEFAULT) {
                    return cryptoCreate(0);
                }
                break;
            case CryptoUpdate:
                if (type == DEFAULT) {
                    return cryptoUpdate(0);
                }
                break;
            case CryptoApproveAllowance:
                if (type == DEFAULT) {
                    return cryptoApproveAllowance();
                }
                break;
            case CryptoDeleteAllowance:
                if (type == DEFAULT) {
                    return cryptoDeleteAllowance();
                }
                break;
            case TokenCreate:
                switch (type) {
                    case TOKEN_FUNGIBLE_COMMON:
                        return fungibleTokenCreate();
                    case TOKEN_NON_FUNGIBLE_UNIQUE:
                        return uniqueTokenCreate();
                    case TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES:
                        return fungibleTokenCreateWithCustomFees();
                    case TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES:
                        return uniqueTokenCreateWithCustomFees();
                    default:
                        break;
                }
                break;
            case TokenMint:
                if (type == TOKEN_NON_FUNGIBLE_UNIQUE) {
                    return uniqueTokenMint();
                } else if (type == TOKEN_FUNGIBLE_COMMON) {
                    return fungibleCommonTokenMint();
                }
                break;
            case TokenAccountWipe:
                if (type == TOKEN_NON_FUNGIBLE_UNIQUE) {
                    return uniqueTokenWipe();
                } else if (type == TOKEN_FUNGIBLE_COMMON) {
                    return fungibleCommonTokenWipe();
                }
                break;
            case TokenBurn:
                if (type == TOKEN_NON_FUNGIBLE_UNIQUE) {
                    return uniqueTokenBurn();
                } else if (type == TOKEN_FUNGIBLE_COMMON) {
                    return fungibleCommonTokenBurn();
                }
                break;
            case ScheduleCreate:
                if (type == SCHEDULE_CREATE_CONTRACT_CALL) {
                    return scheduleCreateWithContractCall();
                } else if (type == DEFAULT) {
                    return scheduleCreate();
                }
                break;
            case TokenFreezeAccount:
                return tokenFreezeAccount();
            case TokenUnfreezeAccount:
                return tokenUnfreezeAccount();
            case TokenFeeScheduleUpdate:
                return feeScheduleUpdate();
            case TokenPause:
                return tokenPause();
            case TokenUnpause:
                return tokenUnpause();
            case ConsensusSubmitMessage:
                return submitMessage();
            case UtilPrng:
                return utilPrng();
            default:
                break;
        }

        throw new IllegalArgumentException("Canonical usage unknown");
    }

    UsageAccumulator contractAutoRenew() {
        final var accountContext = ExtantCryptoContext.newBuilder()
                .setCurrentExpiry(0)
                .setCurrentMemo(BLANK_MEMO)
                .setCurrentKey(A_KEY)
                .setCurrentlyHasProxy(false)
                .setCurrentNumTokenRels(0)
                .setCurrentMaxAutomaticAssociations(0)
                .setCurrentCryptoAllowances(Collections.emptyList())
                .setCurrentTokenAllowances(Collections.emptyList())
                .setCurrentApproveForAllNftAllowances(Collections.emptyList())
                .setCurrentMaxAutomaticAssociations(0)
                .build();
        final var contractContext = new ExtantContractContext(CANONICAL_NUM_CONTRACT_KV_PAIRS, accountContext);
        final var into = new UsageAccumulator();
        into.addRbs(THREE_MONTHS_IN_SECONDS * contractContext.currentRb());
        return into;
    }

    UsageAccumulator utilPrng() {
        final var prngTxnBody = UtilPrngTransactionBody.newBuilder().build();
        final var prngMeta = new UtilPrngMeta(prngTxnBody);
        final var into = new UsageAccumulator();
        UTIL_OPS_USAGE.prngUsage(SINGLE_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, prngMeta, into);
        return into;
    }

    UsageAccumulator cryptoCreate(final int autoAssocSlots) {
        final var cryptoCreateTxnBody = CryptoCreateTransactionBody.newBuilder()
                .setMemo(BLANK_MEMO)
                .setMaxAutomaticTokenAssociations(autoAssocSlots)
                .setAutoRenewPeriod(Duration.newBuilder().setSeconds(THREE_MONTHS_IN_SECONDS))
                .setKey(A_KEY)
                .build();
        final var cryptoCreateMeta = new CryptoCreateMeta(cryptoCreateTxnBody);
        final var into = new UsageAccumulator();
        CRYPTO_OPS_USAGE.cryptoCreateUsage(SINGLE_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, cryptoCreateMeta, into);
        return into;
    }

    UsageAccumulator cryptoApproveAllowance() {
        final var now = Instant.now().getEpochSecond();
        final var canonicalTxn = TransactionBody.newBuilder()
                .setCryptoApproveAllowance(CryptoApproveAllowanceTransactionBody.newBuilder()
                        .addCryptoAllowances(CryptoAllowance.newBuilder()
                                .setSpender(AN_ACCOUNT)
                                .setAmount(1L)
                                .build()))
                .build();
        final var ctx = ExtantCryptoContext.newBuilder()
                .setCurrentExpiry(now + THREE_MONTHS_IN_SECONDS)
                .setCurrentMemo(BLANK_MEMO)
                .setCurrentKey(A_KEY)
                .setCurrentlyHasProxy(false)
                .setCurrentNumTokenRels(0)
                .setCurrentMaxAutomaticAssociations(0)
                .setCurrentCryptoAllowances(Collections.emptyMap())
                .setCurrentTokenAllowances(Collections.emptyMap())
                .setCurrentApproveForAllNftAllowances(Collections.emptySet())
                .build();

        final var cryptoApproveMeta = new CryptoApproveAllowanceMeta(canonicalTxn.getCryptoApproveAllowance(), now);
        final var into = new UsageAccumulator();
        CRYPTO_OPS_USAGE.cryptoApproveAllowanceUsage(
                SINGLE_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, cryptoApproveMeta, ctx, into);
        return into;
    }

    UsageAccumulator cryptoDeleteAllowance() {
        final var now = Instant.now().getEpochSecond();
        final var target = TokenID.newBuilder().setTokenNum(1_234).build();

        final var canonicalTxn = TransactionBody.newBuilder()
                .setCryptoDeleteAllowance(CryptoDeleteAllowanceTransactionBody.newBuilder()
                        .addNftAllowances(NftRemoveAllowance.newBuilder()
                                .setOwner(AN_ACCOUNT)
                                .setTokenId(target)
                                .addAllSerialNumbers(SINGLE_SERIAL_NUM)
                                .build()))
                .build();

        final var cryptoDeleteAllowanceMeta =
                new CryptoDeleteAllowanceMeta(canonicalTxn.getCryptoDeleteAllowance(), now);
        final var into = new UsageAccumulator();
        CRYPTO_OPS_USAGE.cryptoDeleteAllowanceUsage(
                SINGLE_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, cryptoDeleteAllowanceMeta, into);
        return into;
    }

    UsageAccumulator cryptoUpdate(final int newAutoAssocSlots) {
        final var now = Instant.now().getEpochSecond();
        final var canonicalTxn = TransactionBody.newBuilder()
                .setCryptoUpdateAccount(CryptoUpdateTransactionBody.newBuilder()
                        .setMemo(StringValue.of(BLANK_MEMO))
                        .setExpirationTime(Timestamp.newBuilder().setSeconds(now + THREE_MONTHS_IN_SECONDS))
                        .setMaxAutomaticTokenAssociations(
                                Int32Value.newBuilder().setValue(newAutoAssocSlots))
                        .setAccountIDToUpdate(AN_ACCOUNT))
                .build();
        final var ctx = ExtantCryptoContext.newBuilder()
                .setCurrentExpiry(now)
                .setCurrentMemo(BLANK_MEMO)
                .setCurrentKey(A_KEY)
                .setCurrentlyHasProxy(false)
                .setCurrentNumTokenRels(0)
                .setCurrentMaxAutomaticAssociations(0)
                .setCurrentCryptoAllowances(Collections.emptyMap())
                .setCurrentTokenAllowances(Collections.emptyMap())
                .setCurrentApproveForAllNftAllowances(Collections.emptySet())
                .build();
        final var cryptoUpdateMeta = new CryptoUpdateMeta(canonicalTxn.getCryptoUpdateAccount(), now);
        final var into = new UsageAccumulator();
        // Purposely don't include an explicit auto-assoc slot lifetime here
        CRYPTO_OPS_USAGE.cryptoUpdateUsage(
                SINGLE_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, cryptoUpdateMeta, ctx, into, 0);
        return into;
    }

    UsageAccumulator fileAppend() {
        /* The canonical usage and context */
        final var opMeta = new FileAppendMeta(1_000, THREE_MONTHS_IN_SECONDS);
        final var into = new UsageAccumulator();
        FILE_OPS_USAGE.fileAppendUsage(SINGLE_SIG_USAGE, opMeta, NO_MEMO_AND_NO_EXPLICIT_XFERS, into);
        return into;
    }

    UsageAccumulator tokenFreezeAccount() {
        final var tokenFreezeMeta = TOKEN_OPS_USAGE_UTILS.tokenFreezeUsageFrom();
        final var into = new UsageAccumulator();
        TOKEN_OPS_USAGE.tokenFreezeUsage(SINGLE_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, tokenFreezeMeta, into);
        log.info("TokenFreeze base accumulator: {}", into);
        return into;
    }

    UsageAccumulator tokenUnfreezeAccount() {
        final var tokenUnfreezeMeta = TOKEN_OPS_USAGE_UTILS.tokenUnfreezeUsageFrom();
        final var into = new UsageAccumulator();
        TOKEN_OPS_USAGE.tokenFreezeUsage(SINGLE_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, tokenUnfreezeMeta, into);
        return into;
    }

    UsageAccumulator tokenPause() {
        final var tokenPauseMeta = TOKEN_OPS_USAGE_UTILS.tokenPauseUsageFrom();
        final var into = new UsageAccumulator();
        TOKEN_OPS_USAGE.tokenPauseUsage(SINGLE_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, tokenPauseMeta, into);
        return into;
    }

    UsageAccumulator tokenUnpause() {
        final var tokenUnpauseMeta = TOKEN_OPS_USAGE_UTILS.tokenUnpauseUsageFrom();
        final var into = new UsageAccumulator();
        TOKEN_OPS_USAGE.tokenUnpauseUsage(SINGLE_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, tokenUnpauseMeta, into);
        return into;
    }

    UsageAccumulator uniqueTokenBurn() {
        final var target = TokenID.newBuilder().setTokenNum(1_234).build();
        final var canonicalTxn = TransactionBody.newBuilder()
                .setTokenBurn(
                        TokenBurnTransactionBody.newBuilder().setToken(target).addAllSerialNumbers(SINGLE_SERIAL_NUM))
                .build();

        final var tokenBurnMeta = TOKEN_OPS_USAGE_UTILS.tokenBurnUsageFrom(canonicalTxn, TOKEN_NON_FUNGIBLE_UNIQUE);
        final var into = new UsageAccumulator();
        TOKEN_OPS_USAGE.tokenBurnUsage(SINGLE_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, tokenBurnMeta, into);
        return into;
    }

    UsageAccumulator fungibleCommonTokenBurn() {
        final var target = TokenID.newBuilder().setTokenNum(1_235).build();
        final var canonicalTxn = TransactionBody.newBuilder()
                .setTokenBurn(
                        TokenBurnTransactionBody.newBuilder().setToken(target).setAmount(1000L))
                .build();

        final var tokenBurnMeta = TOKEN_OPS_USAGE_UTILS.tokenBurnUsageFrom(canonicalTxn, TOKEN_FUNGIBLE_COMMON);
        final var into = new UsageAccumulator();
        TOKEN_OPS_USAGE.tokenBurnUsage(SINGLE_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, tokenBurnMeta, into);
        return into;
    }

    UsageAccumulator uniqueTokenMint() {
        final var target = TokenID.newBuilder().setTokenNum(1_234).build();
        final var canonicalTxn = TransactionBody.newBuilder()
                .setTokenMint(
                        TokenMintTransactionBody.newBuilder().setToken(target).addMetadata(CANONICAL_NFT_METADATA))
                .build();

        final var tokenMintMeta = TOKEN_OPS_USAGE_UTILS.tokenMintUsageFrom(
                canonicalTxn, TOKEN_NON_FUNGIBLE_UNIQUE, THREE_MONTHS_IN_SECONDS);
        final var into = new UsageAccumulator();
        TOKEN_OPS_USAGE.tokenMintUsage(
                SINGLE_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, tokenMintMeta, into, TOKEN_NON_FUNGIBLE_UNIQUE);
        return into;
    }

    UsageAccumulator fungibleCommonTokenMint() {
        final var target = TokenID.newBuilder().setTokenNum(1_234).build();
        final var canonicalTxn = TransactionBody.newBuilder()
                .setTokenMint(
                        TokenMintTransactionBody.newBuilder().setToken(target).setAmount(1000))
                .build();

        final var tokenMintMeta =
                TOKEN_OPS_USAGE_UTILS.tokenMintUsageFrom(canonicalTxn, TOKEN_FUNGIBLE_COMMON, THREE_MONTHS_IN_SECONDS);
        final var into = new UsageAccumulator();
        TOKEN_OPS_USAGE.tokenMintUsage(
                SINGLE_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, tokenMintMeta, into, TOKEN_FUNGIBLE_COMMON);
        return into;
    }

    UsageAccumulator uniqueTokenWipe() {
        final var target = TokenID.newBuilder().setTokenNum(1_234).build();
        final var targetAcct = AccountID.newBuilder().setAccountNum(5_678).build();
        final var canonicalTxn = TransactionBody.newBuilder()
                .setTokenWipe(TokenWipeAccountTransactionBody.newBuilder()
                        .setToken(target)
                        .setAccount(targetAcct)
                        .addAllSerialNumbers(SINGLE_SERIAL_NUM))
                .build();

        final var tokenWipeMeta = TOKEN_OPS_USAGE_UTILS.tokenWipeUsageFrom(canonicalTxn.getTokenWipe());
        final var into = new UsageAccumulator();
        TOKEN_OPS_USAGE.tokenWipeUsage(SINGLE_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, tokenWipeMeta, into);
        return into;
    }

    UsageAccumulator fungibleCommonTokenWipe() {
        final var target = TokenID.newBuilder().setTokenNum(1_234).build();
        final var targetAcct = AccountID.newBuilder().setAccountNum(5_678).build();
        final var canonicalTxn = TransactionBody.newBuilder()
                .setTokenWipe(TokenWipeAccountTransactionBody.newBuilder()
                        .setToken(target)
                        .setAccount(targetAcct)
                        .setAmount(100))
                .build();

        final var tokenWipeMeta = TOKEN_OPS_USAGE_UTILS.tokenWipeUsageFrom(canonicalTxn.getTokenWipe());
        final var into = new UsageAccumulator();
        TOKEN_OPS_USAGE.tokenWipeUsage(SINGLE_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, tokenWipeMeta, into);
        return into;
    }

    UsageAccumulator fungibleTokenCreateWithCustomFees() {
        final var canonicalTxn = TransactionBody.newBuilder()
                .setTokenCreation(TokenCreateTransactionBody.newBuilder()
                        .setAutoRenewAccount(AN_ACCOUNT)
                        .setTreasury(AN_ACCOUNT)
                        .setName(A_TOKEN_NAME)
                        .setSymbol(A_TOKEN_SYMBOL)
                        .setAdminKey(A_KEY)
                        .setFeeScheduleKey(A_KEY)
                        .setAutoRenewPeriod(Duration.newBuilder().setSeconds(THREE_MONTHS_IN_SECONDS))
                        .setTokenType(TokenType.FUNGIBLE_COMMON)
                        .addCustomFees(CustomFee.newBuilder()
                                .setFeeCollectorAccountId(AN_ACCOUNT)
                                .setAllCollectorsAreExempt(false)
                                .setFixedFee(FixedFee.newBuilder()
                                        .setAmount(100_000_000)
                                        .build())))
                .build();

        final var tokenCreateMeta = TOKEN_OPS_USAGE_UTILS.tokenCreateUsageFrom(canonicalTxn);
        final var into = new UsageAccumulator();
        TOKEN_OPS_USAGE.tokenCreateUsage(QUAD_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, tokenCreateMeta, into);
        return into;
    }

    UsageAccumulator fungibleTokenCreate() {
        final var canonicalTxn = TransactionBody.newBuilder()
                .setTokenCreation(TokenCreateTransactionBody.newBuilder()
                        .setTreasury(AN_ACCOUNT)
                        .setAutoRenewAccount(AN_ACCOUNT)
                        .setName(A_TOKEN_NAME)
                        .setSymbol(A_TOKEN_SYMBOL)
                        .setAdminKey(A_KEY)
                        .setAutoRenewPeriod(Duration.newBuilder().setSeconds(THREE_MONTHS_IN_SECONDS))
                        .setTokenType(TokenType.FUNGIBLE_COMMON))
                .build();

        final var tokenCreateMeta = TOKEN_OPS_USAGE_UTILS.tokenCreateUsageFrom(canonicalTxn);
        final var into = new UsageAccumulator();
        TOKEN_OPS_USAGE.tokenCreateUsage(QUAD_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, tokenCreateMeta, into);
        return into;
    }

    UsageAccumulator uniqueTokenCreate() {
        final var canonicalTxn = TransactionBody.newBuilder()
                .setTokenCreation(TokenCreateTransactionBody.newBuilder()
                        .setTreasury(AN_ACCOUNT)
                        .setAutoRenewAccount(AN_ACCOUNT)
                        .setInitialSupply(0L)
                        .setName(A_TOKEN_NAME)
                        .setSymbol(A_TOKEN_SYMBOL)
                        .setAdminKey(A_KEY)
                        .setSupplyKey(A_KEY)
                        .setAutoRenewPeriod(Duration.newBuilder().setSeconds(THREE_MONTHS_IN_SECONDS))
                        .setTokenType(TokenType.NON_FUNGIBLE_UNIQUE))
                .build();

        final var tokenCreateMeta = TOKEN_OPS_USAGE_UTILS.tokenCreateUsageFrom(canonicalTxn);
        final var into = new UsageAccumulator();
        TOKEN_OPS_USAGE.tokenCreateUsage(QUAD_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, tokenCreateMeta, into);
        return into;
    }

    UsageAccumulator uniqueTokenCreateWithCustomFees() {
        final var canonicalTxn = TransactionBody.newBuilder()
                .setTokenCreation(TokenCreateTransactionBody.newBuilder()
                        .setAutoRenewAccount(AN_ACCOUNT)
                        .setTreasury(AN_ACCOUNT)
                        .setTokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .setInitialSupply(0L)
                        .setName(A_TOKEN_NAME)
                        .setSymbol(A_TOKEN_SYMBOL)
                        .setAdminKey(A_KEY)
                        .setSupplyKey(A_KEY)
                        .setFeeScheduleKey(A_KEY)
                        .setAutoRenewPeriod(Duration.newBuilder().setSeconds(THREE_MONTHS_IN_SECONDS))
                        .setTokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .addCustomFees(CustomFee.newBuilder()
                                .setFeeCollectorAccountId(AN_ACCOUNT)
                                .setFixedFee(FixedFee.newBuilder()
                                        .setAmount(100_000_000)
                                        .build())))
                .build();

        final var tokenCreateMeta = TOKEN_OPS_USAGE_UTILS.tokenCreateUsageFrom(canonicalTxn);
        final var into = new UsageAccumulator();
        TOKEN_OPS_USAGE.tokenCreateUsage(QUAD_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, tokenCreateMeta, into);
        return into;
    }

    UsageAccumulator submitMessage() {
        final var opMeta = new SubmitMessageMeta(100, 0);
        final var into = new UsageAccumulator();
        CONSENSUS_OPS_USAGE.submitMessageUsage(SINGLE_SIG_USAGE, opMeta, NO_MEMO_AND_NO_EXPLICIT_XFERS, into);
        return into;
    }

    UsageAccumulator feeScheduleUpdate() {
        /* A canonical op */
        final var target = TokenID.newBuilder()
                .setShardNum(1)
                .setRealmNum(2)
                .setTokenNum(3)
                .build();
        final List<CustomFee> theNewSchedule = List.of(CustomFee.newBuilder()
                .setFixedFee(FixedFee.newBuilder().setAmount(123L).setDenominatingTokenId(target))
                .build());

        /* The canonical usage and context */
        final var newReprBytes = TOKEN_OPS_USAGE.bytesNeededToRepr(theNewSchedule);
        final var opMeta = new FeeScheduleUpdateMeta(0L, newReprBytes);
        final var feeScheduleCtx = new ExtantFeeScheduleContext(THREE_MONTHS_IN_SECONDS, 0);

        final var into = new UsageAccumulator();
        TOKEN_OPS_USAGE.feeScheduleUpdateUsage(
                SINGLE_SIG_USAGE, NO_MEMO_AND_NO_EXPLICIT_XFERS, opMeta, feeScheduleCtx, into);
        return into;
    }

    UsageAccumulator hbarCryptoTransfer() {
        final var txnUsageMeta = new BaseTransactionMeta(0, 2);
        final var xferUsageMeta = new CryptoTransferMeta(380, 0, 0, 0);
        final var into = new UsageAccumulator();
        CRYPTO_OPS_USAGE.cryptoTransferUsage(SINGLE_SIG_USAGE, xferUsageMeta, txnUsageMeta, into);

        return into;
    }

    UsageAccumulator htsCryptoTransfer() {
        final var xferUsageMeta = new CryptoTransferMeta(380, 1, 2, 0);
        final var into = new UsageAccumulator();
        CRYPTO_OPS_USAGE.cryptoTransferUsage(SINGLE_SIG_USAGE, xferUsageMeta, NO_MEMO_AND_NO_EXPLICIT_XFERS, into);

        return into;
    }

    UsageAccumulator htsCryptoTransferWithCustomFee() {
        final var xferUsageMeta = new CryptoTransferMeta(380, 1, 2, 0);
        xferUsageMeta.setCustomFeeHbarTransfers(2);
        final var into = new UsageAccumulator();
        CRYPTO_OPS_USAGE.cryptoTransferUsage(SINGLE_SIG_USAGE, xferUsageMeta, NO_MEMO_AND_NO_EXPLICIT_XFERS, into);

        return into;
    }

    UsageAccumulator nftCryptoTransfer() {
        final var xferUsageMeta = new CryptoTransferMeta(380, 1, 0, 1);
        final var into = new UsageAccumulator();
        CRYPTO_OPS_USAGE.cryptoTransferUsage(SINGLE_SIG_USAGE, xferUsageMeta, NO_MEMO_AND_NO_EXPLICIT_XFERS, into);

        return into;
    }

    UsageAccumulator nftCryptoTransferWithCustomFee() {
        final var xferUsageMeta = new CryptoTransferMeta(380, 1, 0, 1);
        xferUsageMeta.setCustomFeeHbarTransfers(2);
        final var into = new UsageAccumulator();
        CRYPTO_OPS_USAGE.cryptoTransferUsage(SINGLE_SIG_USAGE, xferUsageMeta, NO_MEMO_AND_NO_EXPLICIT_XFERS, into);

        return into;
    }

    UsageAccumulator scheduleCreate() {
        final var txn = TransactionBody.newBuilder()
                .setTransactionID(TransactionID.newBuilder()
                        .setTransactionValidStart(
                                Timestamp.newBuilder().setNanos(1).setSeconds(1).build())
                        .setAccountID(AN_ACCOUNT)
                        .build())
                .setNodeAccountID(AN_ACCOUNT)
                .setScheduleCreate(ScheduleCreateTransactionBody.newBuilder()
                        .setAdminKey(A_KEY)
                        .setScheduledTransactionBody(SchedulableTransactionBody.newBuilder()
                                .setCryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                                        .setTransfers(TransferList.newBuilder()
                                                .addAccountAmounts(AccountAmount.newBuilder()
                                                        .setAmount(-1_000_000_000)
                                                        .setAccountID(AN_ACCOUNT))
                                                .addAccountAmounts(AccountAmount.newBuilder()
                                                        .setAmount(+1_000_000_000)
                                                        .setAccountID(AN_ACCOUNT))))
                                .setMemo("")
                                .setTransactionFee(100_000_000L)
                                .build()))
                .build();
        final var feeData = SCHEDULE_OPS_USAGE.scheduleCreateUsage(txn, DUAL_SIG_USAGE, 1800);
        return UsageAccumulator.fromGrpc(feeData);
    }

    UsageAccumulator scheduleCreateWithContractCall() {
        final var txn = TransactionBody.newBuilder()
                .setTransactionID(TransactionID.newBuilder()
                        .setTransactionValidStart(
                                Timestamp.newBuilder().setNanos(1).setSeconds(1).build())
                        .setAccountID(AN_ACCOUNT)
                        .build())
                .setNodeAccountID(AN_ACCOUNT)
                .setScheduleCreate(ScheduleCreateTransactionBody.newBuilder()
                        .setAdminKey(A_KEY)
                        .setScheduledTransactionBody(SchedulableTransactionBody.newBuilder()
                                .setContractCall(ContractCallTransactionBody.newBuilder()
                                        .setContractID(ContractID.newBuilder()
                                                .setShardNum(1)
                                                .setRealmNum(1)
                                                .setContractNum(1)
                                                .build())
                                        .setGas(10_000L)
                                        .setFunctionParameters(ByteString.copyFrom(new byte[] {
                                            1, 2, 3, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
                                            24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42,
                                            43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61,
                                            62, 63, 64, 65, 66, 67, 68
                                        })))
                                .setMemo("")
                                .setTransactionFee(100_000_000L)
                                .build()))
                .build();
        final var feeData = SCHEDULE_OPS_USAGE.scheduleCreateUsage(txn, SINGLE_SIG_USAGE, 1800);
        return UsageAccumulator.fromGrpc(feeData);
    }
}
