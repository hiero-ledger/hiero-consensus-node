// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions;

import static com.hedera.node.app.hapi.utils.CommonUtils.extractTransactionBody;
import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static com.hedera.services.bdd.spec.HapiPropertySource.asContract;
import static com.hedera.services.bdd.spec.HapiPropertySource.asContractString;
import static com.hedera.services.bdd.spec.HapiPropertySource.asEntityNumber;
import static com.hedera.services.bdd.spec.HapiPropertySource.asFile;
import static com.hedera.services.bdd.spec.HapiPropertySource.asFileString;
import static com.hedera.services.bdd.spec.HapiPropertySource.asSchedule;
import static com.hedera.services.bdd.spec.HapiPropertySource.asToken;
import static com.hedera.services.bdd.spec.HapiPropertySource.asTokenString;
import static com.hedera.services.bdd.spec.HapiPropertySource.asTopic;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.encodeParametersForConstructor;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileUpdate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PLATFORM_TRANSACTION_NOT_CREATED;
import static com.swirlds.common.stream.LinkedObjectStreamUtilities.getPeriod;
import static java.lang.System.arraycopy;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TextFormat;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hedera.pbj.runtime.JsonCodec;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyFactory;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.queries.contract.HapiGetContractInfo;
import com.hedera.services.bdd.spec.queries.file.HapiGetFileInfo;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.utilops.streams.InterruptibleRunnable;
import com.hedera.services.bdd.suites.contract.Utils;
import com.hedera.services.stream.proto.RecordStreamItem;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.EntityNumber;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.ThresholdKey;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TopicID;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.api.proto.java.TransferList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.hiero.base.utility.CommonUtils;

public class TxnUtils {
    private static final Logger log = LogManager.getLogger(TxnUtils.class);

    public static final ResponseCodeEnum[] NOISY_RETRY_PRECHECKS = {BUSY, PLATFORM_TRANSACTION_NOT_CREATED};

    public static final int BYTES_4K = 4 * (1 << 10);

    private static final Pattern ID_LITERAL_PATTERN = Pattern.compile("\\d+[.]\\d+[.]\\d+");
    private static final Pattern NUMERIC_LITERAL_PATTERN = Pattern.compile("\\d+");
    private static final Pattern POSNEG_NUMERIC_LITERAL_PATTERN = Pattern.compile("^-?\\d+");
    private static final int BANNER_WIDTH = 80;
    private static final int BANNER_BOUNDARY_THICKNESS = 2;
    // Wait just a bit longer than the 2-second block period to be certain we've ended the period
    private static final java.time.Duration END_OF_BLOCK_PERIOD_SLEEP_PERIOD = java.time.Duration.ofMillis(2_200L);
    // Wait just over a second to give the record stream file a chance to close
    private static final java.time.Duration BLOCK_CREATION_SLEEP_PERIOD = java.time.Duration.ofMillis(1_100L);

    public static Key EMPTY_THRESHOLD_KEY =
            Key.newBuilder().setThresholdKey(ThresholdKey.getDefaultInstance()).build();
    public static Key EMPTY_KEY_LIST =
            Key.newBuilder().setKeyList(KeyList.getDefaultInstance()).build();
    public static Key WRONG_LENGTH_EDDSA_KEY = Key.newBuilder()
            .setEd25519(ByteString.fromHex("0000000000000000000000000000000000000000"))
            .build();

    public static Key netOf(@NonNull final HapiSpec spec, @NonNull final Optional<String> keyName) {
        return netOf(spec, keyName, Optional.empty(), Optional.empty());
    }

    /**
     * Dumps the given records to a file at the given path.
     * @param path the path to the file to write to
     * @param codec the codec to use to serialize the records
     * @param records the records to dump
     * @param <T> the type of the records
     */
    public static <T> void dumpJsonList(
            @NonNull final Path path, @NonNull final JsonCodec<T> codec, @NonNull final List<T> records) {
        try (final var fout = Files.newBufferedWriter(path)) {
            fout.write("[");
            for (int i = 0, n = records.size(); i < n; i++) {
                fout.write(codec.toJSON(records.get(i)));
                if (i < n - 1) {
                    fout.write(",");
                }
            }
            fout.write("]");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Key netOf(
            @NonNull final HapiSpec spec,
            @NonNull final Optional<String> keyName,
            @NonNull final Optional<? extends SigControl> keyShape) {
        return netOf(spec, keyName, keyShape, Optional.empty());
    }

    public static Key netOf(
            @NonNull final HapiSpec spec,
            @NonNull final Optional<String> keyName,
            @NonNull final Optional<? extends SigControl> keyShape,
            @NonNull final Optional<KeyFactory.KeyType> keyType) {
        if (keyName.isEmpty()) {
            if (keyShape.isPresent()) {
                return spec.keys().generateSubjectTo(spec, keyShape.get());
            } else {
                return spec.keys().generate(spec, keyType.orElse(spec.setup().defaultKeyType()));
            }
        } else {
            return spec.registry().getKey(keyName.get());
        }
    }

    public static void turnLoggingOff(@NonNull final SpecOperation op) {
        requireNonNull(op);
        if (op instanceof HapiTxnOp<?> txnOp) {
            txnOp.noLogging();
        } else if (op instanceof HapiQueryOp<?> queryOp) {
            queryOp.noLogging();
        }
    }

    public static List<Function<HapiSpec, Key>> defaultUpdateSigners(
            final String owningEntity,
            final Optional<String> newKeyName,
            final Function<HapiSpec, String> effectivePayer) {
        final List<Function<HapiSpec, Key>> signers = new ArrayList<>();
        signers.add(spec -> spec.registry().getKey(effectivePayer.apply(spec)));
        signers.add(spec -> spec.registry().getKey(owningEntity));
        if (newKeyName.isPresent()) {
            signers.add(spec -> spec.registry().getKey(newKeyName.get()));
        }
        return signers;
    }

    public static Duration asDuration(final long secs) {
        return Duration.newBuilder().setSeconds(secs).build();
    }

    public static Timestamp asTimestamp(final long secs) {
        return Timestamp.newBuilder().setSeconds(secs).build();
    }

    public static Timestamp asTimestamp(final Instant when) {
        return Timestamp.newBuilder()
                .setSeconds(when.getEpochSecond())
                .setNanos(when.getNano())
                .build();
    }

    public static boolean isIdLiteral(final String s) {
        return ID_LITERAL_PATTERN.matcher(s).matches();
    }

    public static boolean isLiteralEvmAddress(@NonNull final String s) {
        return (s.startsWith("0x") && s.substring(2).matches("[0-9a-fA-F]+"))
                || (s.length() == 40 && s.matches("[0-9a-fA-F]+"));
    }

    public static ByteString asLiteralEvmAddress(@NonNull final String s) {
        return s.startsWith("0x")
                ? ByteString.copyFrom(CommonUtils.unhex(s.substring(2)))
                : ByteString.copyFrom(CommonUtils.unhex(s));
    }

    public static boolean isNumericLiteral(final String s) {
        return NUMERIC_LITERAL_PATTERN.matcher(s).matches();
    }

    public static boolean isPosNegNumericLiteral(final String s) {
        return POSNEG_NUMERIC_LITERAL_PATTERN.matcher(s).matches();
    }

    public static AccountID asId(final String s, final HapiSpec lookupSpec) {
        if (isIdLiteral(s)) {
            return asAccount(s);
        }
        if (isNumericLiteral(s)) {
            return asAccount(lookupSpec.shard(), lookupSpec.realm(), Long.parseLong(s));
        }
        return lookupSpec.registry().getAccountID(s);
    }

    public static AccountID asIdForKeyLookUp(final String s, final HapiSpec lookupSpec) {
        if (isLiteralEvmAddress(s)) {
            return AccountID.newBuilder()
                    .setShardNum(lookupSpec.shard())
                    .setRealmNum(lookupSpec.realm())
                    .setAlias(asLiteralEvmAddress(s))
                    .build();
        }
        if (isIdLiteral(s)) {
            return asAccount(s);
        }
        if (isNumericLiteral(s)) {
            return asAccount(lookupSpec.shard(), lookupSpec.realm(), Long.parseLong(s));
        }
        return lookupSpec.registry().hasAccountId(s)
                ? lookupSpec.registry().getAccountID(s)
                : lookUpAccount(lookupSpec, s);
    }

    private static AccountID lookUpAccount(final HapiSpec spec, final String alias) {
        final var key = spec.registry().getKey(alias);
        final var lookedUpKey = spec.registry().getKey(alias).toByteString().toStringUtf8();
        return spec.registry().hasAccountId(lookedUpKey)
                ? spec.registry().getAccountID(lookedUpKey)
                : asIdWithAlias(key.toByteString());
    }

    public static AccountID asIdWithAlias(final ByteString s) {
        return asAccount(s);
    }

    public static TokenID asTokenId(final String s, final HapiSpec lookupSpec) {
        if (isIdLiteral(s)) {
            return asToken(s);
        }
        if (isNumericLiteral(s)) {
            return asToken(String.valueOf(lookupSpec.shard()), String.valueOf(lookupSpec.realm()), s);
        }
        return lookupSpec.registry().getTokenID(s);
    }

    public static ScheduleID asScheduleId(final String s, final HapiSpec lookupSpec) {
        return isIdLiteral(s) ? asSchedule(s) : lookupSpec.registry().getScheduleId(s);
    }

    public static TopicID asTopicId(final String s, final HapiSpec lookupSpec) {
        if (isIdLiteral(s)) {
            return asTopic(s);
        }
        if (isNumericLiteral(s)) {
            return asTopic(lookupSpec.shard(), lookupSpec.realm(), Long.parseLong(s));
        }
        return lookupSpec.registry().getTopicID(s);
    }

    public static FileID asFileId(final String s, final HapiSpec lookupSpec) {
        if (isIdLiteral(s)) {
            return asFile(s);
        }
        if (isNumericLiteral(s)) {
            return asFile(lookupSpec.shard(), lookupSpec.realm(), Long.parseLong(s));
        }
        return lookupSpec.registry().getFileId(s);
    }

    public static EntityNumber asNodeId(final String s, final HapiSpec lookupSpec) {
        return isNumericLiteral(s) ? asEntityNumber(s) : lookupSpec.registry().getNodeId(s);
    }

    public static long asNodeIdLong(final String s, final HapiSpec lookupSpec) {
        return isNumericLiteral(s)
                ? asEntityNumber(s).getNumber()
                : lookupSpec.registry().getNodeId(s).getNumber();
    }

    public static long asPosNodeId(final String s, final HapiSpec lookupSpec) {
        return isPosNegNumericLiteral(s)
                ? asEntityNumber(s).getNumber()
                : lookupSpec.registry().getNodeId(s).getNumber();
    }

    public static ContractID asContractId(final String s, final HapiSpec lookupSpec) {
        final var effS = s.startsWith("0x") ? s.substring(2) : s;
        if (effS.length() == HapiContractCall.HEXED_EVM_ADDRESS_LEN) {
            return ContractID.newBuilder()
                    .setShardNum(lookupSpec.shard())
                    .setRealmNum(lookupSpec.realm())
                    .setEvmAddress(ByteString.copyFrom(CommonUtils.unhex(effS)))
                    .build();
        }
        if (isIdLiteral(s)) {
            return asContract(s);
        }
        if (isNumericLiteral(s)) {
            return asContract(lookupSpec.shard(), lookupSpec.realm(), Long.parseLong(s));
        }
        return lookupSpec.registry().getContractId(s);
    }

    public static String txnToString(final Transaction txn) {
        try {
            return toReadableString(txn);
        } catch (final InvalidProtocolBufferException e) {
            log.error("Got Grpc protocol buffer error: ", e);
        }
        return null;
    }

    public static boolean inConsensusOrder(final Timestamp t1, final Timestamp t2) {
        if (t1.getSeconds() < t2.getSeconds()) {
            return true;
        } else if (t1.getSeconds() == t2.getSeconds()) {
            return (t1.getNanos() < t2.getNanos());
        } else {
            return false;
        }
    }

    public static ContractID asContractId(final byte[] bytes) {
        final int shard = Ints.fromByteArray(Arrays.copyOfRange(bytes, 0, 4));
        final long realm = Longs.fromByteArray(Arrays.copyOfRange(bytes, 4, 12));
        final long accountNum = Longs.fromByteArray(Arrays.copyOfRange(bytes, 12, 20));

        return ContractID.newBuilder()
                .setContractNum(accountNum)
                .setRealmNum(realm)
                .setShardNum(shard)
                .build();
    }

    public static AccountID equivAccount(final ContractID contract) {
        return AccountID.newBuilder()
                .setShardNum(contract.getShardNum())
                .setRealmNum(contract.getRealmNum())
                .setAccountNum(contract.getContractNum())
                .build();
    }

    public static SigUsage suFrom(final SigValueObj svo) {
        return new SigUsage(svo.getTotalSigCount(), svo.getSignatureSize(), svo.getPayerAcctSigCount());
    }

    private static final int NANOS_IN_A_SECOND = 1_000_000_000;
    private static final AtomicInteger NEXT_NANO = new AtomicInteger(0);
    private static final int NANO_OFFSET = (int) (System.currentTimeMillis() % 1_000);

    public static synchronized Timestamp getUniqueTimestampPlusSecs(final long offsetSecs) {
        final Instant instant = Instant.now(Clock.systemUTC());

        int candidateNano = NEXT_NANO.getAndIncrement() + NANO_OFFSET;
        if (candidateNano >= NANOS_IN_A_SECOND) {
            candidateNano = 0;
            NEXT_NANO.set(1);
        }

        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond() + offsetSecs)
                .setNanos(candidateNano)
                .build();
    }

    public static TransactionID asTransactionID(final HapiSpec spec, final Optional<String> payer) {
        final var payerID =
                spec.registry().getAccountID(payer.orElse(spec.setup().defaultPayerName()));
        final var validStart = getUniqueTimestampPlusSecs(spec.setup().txnStartOffsetSecs());
        return TransactionID.newBuilder()
                .setTransactionValidStart(validStart)
                .setAccountID(payerID)
                .build();
    }

    public static String solidityIdFrom(final ContractID contract) {
        final byte[] solidityAddress = new byte[20];

        arraycopy(Ints.toByteArray((int) contract.getShardNum()), 0, solidityAddress, 0, 4);
        arraycopy(Longs.toByteArray(contract.getRealmNum()), 0, solidityAddress, 4, 8);
        arraycopy(Longs.toByteArray(contract.getContractNum()), 0, solidityAddress, 12, 8);

        return CommonUtils.hex(solidityAddress);
    }

    public static TransactionID extractTxnId(final Transaction txn) throws Throwable {
        return extractTransactionBody(txn).getTransactionID();
    }

    public static TransferList asTransferList(final List<AccountAmount>... specifics) {
        final TransferList.Builder builder = TransferList.newBuilder();
        Arrays.stream(specifics).forEach(builder::addAllAccountAmounts);
        return builder.build();
    }

    public static Map<AccountID, Long> asDebits(final TransferList xfers) {
        return xfers.getAccountAmountsList().stream()
                .filter(aa -> aa.getAmount() < 0)
                .collect(toMap(AccountAmount::getAccountID, AccountAmount::getAmount));
    }

    public static List<AccountAmount> tinyBarsFromTo(final long amount, final AccountID from, final AccountID to) {
        return Arrays.asList(
                AccountAmount.newBuilder()
                        .setAccountID(from)
                        .setAmount(-1L * amount)
                        .build(),
                AccountAmount.newBuilder().setAccountID(to).setAmount(amount).build());
    }

    public static String printable(final TransferList transfers) {
        return transfers.getAccountAmountsList().stream()
                .map(adjust -> String.format(
                        "%d %s %d",
                        adjust.getAmount(),
                        adjust.getAmount() < 0L ? "from" : "to",
                        adjust.getAccountID().getAccountNum()))
                .collect(joining(", "));
    }

    public static Timestamp currExpiry(final String file, final HapiSpec spec, final String payer) throws Throwable {
        final HapiGetFileInfo subOp = getFileInfo(file).payingWith(payer).noLogging();
        final Optional<Throwable> error = subOp.execFor(spec);
        if (error.isPresent()) {
            String message = String.format(
                    "Unable to look up current expiration timestamp of file %s",
                    asFileString(spec.registry().getFileId(file)));
            log.error(message);
            throw error.get();
        }
        return subOp.getResponse().getFileGetInfo().getFileInfo().getExpirationTime();
    }

    public static Timestamp currExpiry(final String file, final HapiSpec spec) throws Throwable {
        return currExpiry(file, spec, spec.setup().defaultPayerName());
    }

    public static Timestamp currContractExpiry(final String contract, final HapiSpec spec) throws Throwable {
        final HapiGetContractInfo subOp = getContractInfo(contract).noLogging();
        final Optional<Throwable> error = subOp.execFor(spec);
        if (error.isPresent()) {
            String message = String.format(
                    "Unable to look up current expiration timestamp of contract %s",
                    asContractString(spec.registry().getContractId(contract)));
            log.error(message);
            throw error.get();
        }
        return subOp.getResponse().getContractGetInfo().getContractInfo().getExpirationTime();
    }

    public static TopicID asTopicId(final AccountID id) {
        return TopicID.newBuilder()
                .setShardNum(id.getShardNum())
                .setRealmNum(id.getRealmNum())
                .setTopicNum(id.getAccountNum())
                .build();
    }

    public static byte[] randomUtf8Bytes(final int n) {
        final byte[] data = new byte[n];
        int i = 0;
        while (i < n) {
            final byte[] rnd = UUID.randomUUID().toString().getBytes();
            System.arraycopy(rnd, 0, data, i, Math.min(rnd.length, n - 1 - i));
            i += rnd.length;
        }
        return data;
    }

    public static String randomUppercase(final int l) {
        return randomSampling(l, UPPER);
    }

    public static String randomAlphaNumeric(final int l) {
        return randomSampling(l, ALNUM);
    }

    private static String randomSampling(final int l, final char[] src) {
        final var sb = new StringBuilder();
        for (int i = 0, n = src.length; i < l; i++) {
            sb.append(src[r.nextInt(n)]);
        }
        return sb.toString();
    }

    private static final SplittableRandom r = new SplittableRandom(1_234_567);
    private static final char[] UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final char[] ALNUM = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    public static String readableTokenTransfers(final List<TokenTransferList> tokenTransfers) {
        return tokenTransfers.stream()
                .map(scopedXfers -> String.format(
                        "%s(%s)(%s)",
                        asTokenString(scopedXfers.getToken()),
                        readableTransferList(scopedXfers.getTransfersList()),
                        readableNftTransferList(scopedXfers.getNftTransfersList())))
                .collect(joining(", "));
    }

    public static String readableTransferList(final TransferList accountAmounts) {
        return readableTransferList(accountAmounts.getAccountAmountsList());
    }

    public static String readableTransferList(final List<AccountAmount> adjustments) {
        return adjustments.stream()
                .map(aa -> String.format(
                        "%s %s %s%s",
                        HapiPropertySource.asAliasableAccountString(aa.getAccountID()),
                        aa.getAmount() < 0 ? "->" : "<-",
                        aa.getAmount() < 0 ? "-" : "+",
                        BigInteger.valueOf(aa.getAmount()).abs()))
                .collect(toList())
                .toString();
    }

    public static String readableNftTransferList(final List<NftTransfer> adjustments) {
        return adjustments.stream()
                .map(nftTranfer -> String.format(
                        "serialNumber:%s senderAccountID:%s receiverAccountId:%s",
                        nftTranfer.getSerialNumber(),
                        HapiPropertySource.asAccountString(nftTranfer.getSenderAccountID()),
                        HapiPropertySource.asAccountString(nftTranfer.getReceiverAccountID())))
                .collect(toList())
                .toString();
    }

    public static OptionalLong getNonFeeDeduction(final TransactionRecord record) {
        final var payer = record.getTransactionID().getAccountID();
        final var txnFee = record.getTransactionFee();
        final var totalDeduction = getDeduction(record.getTransferList(), payer);
        return totalDeduction.isPresent() ? OptionalLong.of(totalDeduction.getAsLong() + txnFee) : totalDeduction;
    }

    public static OptionalLong getDeduction(final TransferList accountAmounts, final AccountID payer) {
        final var deduction = accountAmounts.getAccountAmountsList().stream()
                .filter(aa -> aa.getAccountID().equals(payer) && aa.getAmount() < 0)
                .findAny();
        return deduction.isPresent() ? OptionalLong.of(deduction.get().getAmount()) : OptionalLong.empty();
    }

    // Following methods are for negative test cases purpose, use with caution
    public static Transaction replaceTxnMemo(final Transaction txn, final String newMemo) {
        try {
            final TransactionBody.Builder txnBody = TransactionBody.newBuilder().mergeFrom(txn.getBodyBytes());
            txnBody.setMemo(newMemo);
            return txn.toBuilder().setBodyBytes(txnBody.build().toByteString()).build();
        } catch (final Exception e) {
            log.warn("Transaction's body can't be parsed: {}", txnToString(txn), e);
        }
        return null;
    }

    public static Transaction replaceTxnPayerAccount(final Transaction txn, final AccountID accountID) {
        try {
            final TransactionBody.Builder txnBody = TransactionBody.newBuilder().mergeFrom(txn.getBodyBytes());
            txnBody.setTransactionID(TransactionID.newBuilder()
                    .setAccountID(accountID)
                    .setTransactionValidStart(txnBody.getTransactionID().getTransactionValidStart())
                    .build());
            return txn.toBuilder().setBodyBytes(txnBody.build().toByteString()).build();
        } catch (final Exception e) {
            log.warn("Transaction's body can't be parsed: {}", txnToString(txn), e);
        }
        return null;
    }

    public static Transaction replaceTxnStartTime(
            final Transaction txn, final long newStartTimeSecs, final int newStartTimeNanos) {
        try {
            final TransactionBody.Builder txnBody = TransactionBody.newBuilder().mergeFrom(txn.getBodyBytes());

            txnBody.setTransactionID(TransactionID.newBuilder()
                    .setAccountID(txnBody.getTransactionID().getAccountID())
                    .setTransactionValidStart(Timestamp.newBuilder()
                            .setSeconds(newStartTimeSecs)
                            .setNanos(newStartTimeNanos)
                            .build())
                    .build());
            return txn.toBuilder().setBodyBytes(txnBody.build().toByteString()).build();
        } catch (final Exception e) {
            log.warn("Transaction's body can't be parsed: {}", txnToString(txn), e);
        }
        return null;
    }

    public static Transaction replaceTxnDuration(final Transaction txn, final long newDuration) {
        try {
            final TransactionBody.Builder txnBody = TransactionBody.newBuilder().mergeFrom(txn.getBodyBytes());
            txnBody.setTransactionValidDuration(
                    Duration.newBuilder().setSeconds(newDuration).build());
            return txn.toBuilder().setBodyBytes(txnBody.build().toByteString()).build();
        } catch (final Exception e) {
            log.warn("Transaction's body can't be parsed: {}", txnToString(txn), e);
        }
        return null;
    }

    public static Transaction replaceTxnNodeAccount(final Transaction txn, final AccountID newNodeAccount) {
        log.info(String.format("Old Txn attr: %s", TxnUtils.txnToString(txn)));
        try {

            final TransactionBody.Builder txnBody = TransactionBody.newBuilder().mergeFrom(txn.getBodyBytes());
            txnBody.setNodeAccountID(newNodeAccount);
            return txn.toBuilder().setBodyBytes(txnBody.build().toByteString()).build();
        } catch (final Exception e) {
            log.warn("Transaction's body can't be parsed: {}", txnToString(txn), e);
        }
        return null;
    }

    public static String nAscii(final int n) {
        return IntStream.range(0, n).mapToObj(ignore -> "A").collect(joining());
    }

    /**
     * Generates a human readable string for grpc transaction.
     *
     * @param grpcTransaction GRPC transaction
     * @return generated readable string
     * @throws InvalidProtocolBufferException when protocol buffer is invalid
     */
    public static String toReadableString(final Transaction grpcTransaction) throws InvalidProtocolBufferException {
        final TransactionBody body = extractTransactionBody(grpcTransaction);
        return "body="
                + TextFormat.shortDebugString(body)
                + "; sigs="
                + TextFormat.shortDebugString(
                        com.hedera.node.app.hapi.utils.CommonUtils.extractSignatureMap(grpcTransaction));
    }

    public static String bytecodePath(final String contractName) {
        // TODO: Quick fix for https://github.com/hashgraph/hedera-services/issues/6821, a better solution
        // will be provided when the issue is resolved
        return Utils.getResourcePath(contractName, ".bin");
    }

    public static ByteString literalInitcodeFor(final String contract) {
        try {
            return ByteString.copyFrom(Files.readAllBytes(Paths.get(bytecodePath(contract))));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<TransactionRecord> nonStakingRecordsFrom(@NonNull final List<TransactionRecord> records) {
        return records.stream().filter(TxnUtils::isNotEndOfStakingPeriodRecord).toList();
    }

    public static boolean isEndOfStakingPeriodRecord(final TransactionRecord record) {
        return record.getMemo().startsWith("End of staking period calculation record");
    }

    public static boolean isNotEndOfStakingPeriodRecord(final TransactionRecord record) {
        return !isEndOfStakingPeriodRecord(record);
    }

    public static ByteString constructorArgsToByteString(final String abi, final Object[] args) {
        var params = encodeParametersForConstructor(args, abi);

        var paramsAsHex = Bytes.wrap(params).toHexString();
        // remove the 0x prefix
        var paramsToUse = paramsAsHex.substring(2);

        try {
            return ByteString.copyFrom(paramsToUse, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Calculates the duration until the start of the next staking period.
     *
     * @param now the current time
     * @param stakePeriodMins the duration of a staking period in minutes
     * @return the duration until the start of the next staking period
     */
    public static java.time.Duration timeUntilNextPeriod(@NonNull final Instant now, final long stakePeriodMins) {
        final var stakePeriodMillis = stakePeriodMins * 60 * 1000L;
        final var currentPeriod = getPeriod(now, stakePeriodMillis);
        final var nextPeriod = currentPeriod + 1;
        return java.time.Duration.between(now, Instant.ofEpochMilli(nextPeriod * stakePeriodMillis));
    }

    public static Instant instantOf(@NonNull final Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    /**
     * Generates a banner with the given messages to speed up identifying key information in logs.
     *
     * @param msgs the messages to be displayed in the banner
     * @return the banner with the given messages
     */
    public static String bannerWith(@NonNull final String... msgs) {
        requireNonNull(msgs);
        var sb = new StringBuilder();
        var partial = IntStream.range(0, BANNER_BOUNDARY_THICKNESS)
                .mapToObj(ignore -> "*")
                .collect(joining());
        int printableWidth = BANNER_WIDTH - 2 * (partial.length() + 1);
        addFullBoundary(sb);
        List<String> allMsgs = Stream.concat(Stream.of(""), Stream.concat(Arrays.stream(msgs), Stream.of("")))
                .toList();
        for (String msg : allMsgs) {
            int rightPaddingLen = printableWidth - msg.length();
            var rightPadding =
                    IntStream.range(0, rightPaddingLen).mapToObj(ignore -> " ").collect(joining());
            sb.append(partial)
                    .append(" ")
                    .append(msg)
                    .append(rightPadding)
                    .append(" ")
                    .append(partial)
                    .append("\n");
        }
        addFullBoundary(sb);
        return sb.toString();
    }

    private static void addFullBoundary(StringBuilder sb) {
        var full = IntStream.range(0, BANNER_WIDTH).mapToObj(ignore -> "*").collect(joining());
        for (int i = 0; i < BANNER_BOUNDARY_THICKNESS; i++) {
            sb.append(full).append("\n");
        }
    }

    public static void triggerAndCloseAtLeastOneFile(@NonNull final HapiSpec spec) throws InterruptedException {
        spec.sleepConsensusTime(END_OF_BLOCK_PERIOD_SLEEP_PERIOD);
        // Should trigger a new record to be written if we have crossed a 2-second boundary
        final var triggerOp = TxnVerbs.cryptoTransfer(HapiCryptoTransfer.tinyBarsFromTo(DEFAULT_PAYER, FUNDING, 1L))
                .deferStatusResolution()
                .hasAnyStatusAtAll()
                .noLogging();
        allRunFor(spec, triggerOp);
    }

    public static void triggerAndCloseAtLeastOneFileIfNotInterrupted(@NonNull final HapiSpec spec) {
        doIfNotInterrupted(() -> {
            triggerAndCloseAtLeastOneFile(spec);
            log.info("Sleeping a bit to give the record stream a chance to close");
            spec.sleepConsensusTime(BLOCK_CREATION_SLEEP_PERIOD);
        });
    }

    public static void doIfNotInterrupted(@NonNull final InterruptibleRunnable runnable) {
        requireNonNull(runnable);
        try {
            runnable.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static KeyList getCompositeList(final Key key) {
        return key.hasKeyList() ? key.getKeyList() : key.getThresholdKey().getKeys();
    }

    /**
     * Returns the contents of the resource at the given location as a string.
     *
     * @param loc the location of the resource
     * @return the contents of the resource as a string
     */
    public static String resourceAsString(@NonNull final String loc) {
        try {
            try (final var in = TxnUtils.class.getClassLoader().getResourceAsStream(loc);
                    final var bridge = new InputStreamReader(requireNonNull(in));
                    final var reader = new BufferedReader(bridge)) {
                return reader.lines().collect(joining("\n"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Predicate that checks if a given {@link RecordStreamItem} is a system file update relative to the given spec.
     * @param spec the spec to use for the check
     * @param item the item to check
     * @return {@code true} if the item is a system file update, {@code false} otherwise
     */
    public static boolean isSysFileUpdate(@NonNull final HapiSpec spec, @NonNull final RecordStreamItem item) {
        final var firstUserNum = spec.startupProperties().getLong("hedera.firstUserEntity");
        return filterForSysFileUpdate(spec, item, id -> id.getFileNum() < firstUserNum);
    }

    /**
     * Returns a predicate that checks if a given {@link RecordStreamItem} is a system file update targeting a
     * particular system file relative to the given spec.
     * @param sysFileProperties the name(s) of the system file properties to screen for
     * @return a predicate testing if the item is a system file update to a given file
     */
    public static BiPredicate<HapiSpec, RecordStreamItem> sysFileUpdateTo(@NonNull final String... sysFileProperties) {
        requireNonNull(sysFileProperties);
        return (spec, item) -> {
            final var sysFileNums = Arrays.stream(sysFileProperties)
                    .map(spec.startupProperties()::getLong)
                    .collect(toSet());
            return filterForSysFileUpdate(spec, item, id -> sysFileNums.contains(id.getFileNum()));
        };
    }

    private static boolean filterForSysFileUpdate(
            @NonNull final HapiSpec spec,
            @NonNull final RecordStreamItem item,
            @NonNull final Predicate<FileID> idFilter) {
        final var txnId = item.getRecord().getTransactionID();
        final var sysAdminNum = spec.startupProperties().getLong("accounts.systemAdmin");
        if (txnId.getAccountID().getAccountNum() != sysAdminNum) {
            return false;
        } else {
            final var entry = RecordStreamEntry.from(item);
            return entry.function() == FileUpdate
                    && idFilter.test(entry.body().getFileUpdate().getFileID());
        }
    }
}
