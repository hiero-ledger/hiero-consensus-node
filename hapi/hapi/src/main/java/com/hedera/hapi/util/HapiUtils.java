// SPDX-License-Identifier: Apache-2.0
package com.hedera.hapi.util;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utility class for working with the HAPI. We might move this to the HAPI project.
 */
public class HapiUtils {
    private static final int EVM_ADDRESS_ALIAS_LENGTH = 20;
    public static final Key EMPTY_KEY_LIST =
            Key.newBuilder().keyList(KeyList.DEFAULT).build();
    public static final long FUNDING_ACCOUNT_EXPIRY = 33197904000L;
    /** Arbitrary limit to prevent stack overflow when parsing unrealistically long versions. */
    private static final int MAX_VERSION_LENGTH = 100;

    /** From <a href="https://semver.org/#is-there-a-suggested-regular-expression-regex-to-check-a-semver-string"></a> */
    // suppress the warning that the regular expression is too complicated
    @SuppressWarnings({"java:S5843", "java:S5998"})
    public static final Pattern SEMVER_SPEC_REGEX = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)"
                    + "(?:\\."
                    + "(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)"
                    + "*))?$");

    /** A {@link Comparator} for {@link AccountID}s. Sorts first by account number, then by alias. */
    public static final Comparator<AccountID> ACCOUNT_ID_COMPARATOR = (o1, o2) -> {
        if (o1 == o2) return 0;
        if (o1.hasAccountNum() && !o2.hasAccountNum()) return -1;
        if (!o1.hasAccountNum() && o2.hasAccountNum()) return 1;
        if (o1.hasAccountNum()) {
            return Long.compare(o1.accountNumOrThrow(), o2.accountNumOrThrow());
        } else {
            final var alias1 = o1.aliasOrElse(Bytes.EMPTY);
            final var alias2 = o2.aliasOrElse(Bytes.EMPTY);
            // FUTURE: Can replace with Bytes.compare or a built-in Bytes comparator when available
            final var diff = alias1.length() - alias2.length();
            if (diff < 0) return -1;
            if (diff > 0) return 1;
            for (long i = 0; i < alias1.length(); i++) {
                final var b1 = alias1.getByte(i);
                final var b2 = alias2.getByte(i);
                if (b1 < b2) return -1;
                if (b1 > b2) return 1;
            }
        }
        return 0;
    };

    /** A {@link Comparator} for {@link ContractID}s. Sorts first by contract number, then by evm address. */
    public static final Comparator<ContractID> CONTRACT_ID_COMPARATOR = (o1, o2) -> {
        if (o1 == o2) return 0;
        if (o1.hasContractNum() && !o2.hasContractNum()) return -1;
        if (!o1.hasContractNum() && o2.hasContractNum()) return 1;
        if (o1.hasContractNum()) {
            return Long.compare(o1.contractNumOrThrow(), o2.contractNumOrThrow());
        } else {
            final var alias1 = o1.evmAddressOrElse(Bytes.EMPTY);
            final var alias2 = o2.evmAddressOrElse(Bytes.EMPTY);
            // FUTURE: Can replace with Bytes.compare or a built-in Bytes comparator when available
            final var diff = alias1.length() - alias2.length();
            if (diff < 0) return -1;
            if (diff > 0) return 1;
            for (long i = 0; i < alias1.length(); i++) {
                final var b1 = alias1.getByte(i);
                final var b2 = alias2.getByte(i);
                if (b1 < b2) return -1;
                if (b1 > b2) return 1;
            }
        }
        return 0;
    };

    /** A simple {@link Comparator} for {@link Timestamp}s. */
    public static final Comparator<Timestamp> TIMESTAMP_COMPARATOR =
            Comparator.comparingLong(Timestamp::seconds).thenComparingInt(Timestamp::nanos);

    private HapiUtils() {}

    /**
     * Determines whether the given account is a "hollow" account, i.e., one that has no keys and has an alias
     * that matches the length of an EVM address.
     *
     * @param account The account to check
     * @return {@code true} if the account is a hollow account, {@code false} otherwise.
     */
    public static boolean isHollow(@NonNull final Account account) {
        requireNonNull(account);
        return (account.accountIdOrThrow().accountNum() > 1000
                && account.keyOrElse(EMPTY_KEY_LIST).equals(EMPTY_KEY_LIST)
                && account.alias() != null
                && account.alias().length() == EVM_ADDRESS_ALIAS_LENGTH);
    }

    /** Converts the given {@link Instant} into a {@link Timestamp}. */
    public static Timestamp asTimestamp(@NonNull final Instant instant) {
        return new Timestamp(instant.getEpochSecond(), instant.getNano());
    }

    /** Converts the given {@link Timestamp} into an {@link Instant}. */
    public static Instant asInstant(@NonNull final Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.seconds(), timestamp.nanos());
    }

    /** Subtracts the given number of seconds from the given {@link Timestamp}, returning a new {@link Timestamp}. */
    public static Timestamp minus(@NonNull final Timestamp ts, final long seconds) {
        return Timestamp.newBuilder()
                .seconds(ts.seconds() - seconds)
                .nanos(ts.nanos())
                .build();
    }

    /** Determines whether the first timestamp is before the second timestamp. Think of it as, "Is t1 before t2?" */
    public static boolean isBefore(@NonNull final Timestamp t1, @NonNull final Timestamp t2) {
        return TIMESTAMP_COMPARATOR.compare(t1, t2) < 0;
    }

    /** Given a key, determines the number of cryptographic keys contained within it. */
    public static int countOfCryptographicKeys(@NonNull final Key key) {
        return switch (key.key().kind()) {
            case ECDSA_384, ED25519, RSA_3072, ECDSA_SECP256K1 -> 1;
            case KEY_LIST ->
                key.keyListOrThrow().keys().stream()
                        .mapToInt(HapiUtils::countOfCryptographicKeys)
                        .sum();
            case THRESHOLD_KEY ->
                key.thresholdKeyOrThrow().keysOrElse(KeyList.DEFAULT).keys().stream()
                        .mapToInt(HapiUtils::countOfCryptographicKeys)
                        .sum();
            case CONTRACT_ID, DELEGATABLE_CONTRACT_ID, UNSET -> 0;
        };
    }

    // Suppressing the warning that this field is not used
    @SuppressWarnings("java:S1068")
    private static final Set<HederaFunctionality> QUERY_FUNCTIONS = EnumSet.of(
            HederaFunctionality.CONSENSUS_GET_TOPIC_INFO,
            HederaFunctionality.GET_BY_SOLIDITY_ID,
            HederaFunctionality.CONTRACT_CALL_LOCAL,
            HederaFunctionality.CONTRACT_GET_INFO,
            HederaFunctionality.CONTRACT_GET_BYTECODE,
            HederaFunctionality.CONTRACT_GET_RECORDS,
            HederaFunctionality.CRYPTO_GET_ACCOUNT_BALANCE,
            HederaFunctionality.CRYPTO_GET_ACCOUNT_RECORDS,
            HederaFunctionality.CRYPTO_GET_INFO,
            HederaFunctionality.CRYPTO_GET_LIVE_HASH,
            HederaFunctionality.FILE_GET_CONTENTS,
            HederaFunctionality.FILE_GET_INFO,
            HederaFunctionality.TRANSACTION_GET_RECEIPT,
            HederaFunctionality.TRANSACTION_GET_RECORD,
            HederaFunctionality.GET_VERSION_INFO,
            HederaFunctionality.TOKEN_GET_INFO,
            HederaFunctionality.SCHEDULE_GET_INFO,
            HederaFunctionality.TOKEN_GET_NFT_INFO,
            HederaFunctionality.TOKEN_GET_NFT_INFOS,
            HederaFunctionality.TOKEN_GET_ACCOUNT_NFT_INFOS,
            HederaFunctionality.NETWORK_GET_EXECUTION_TIME,
            HederaFunctionality.GET_ACCOUNT_DETAILS);

    public static HederaFunctionality functionOf(final TransactionBody txn) throws UnknownHederaFunctionality {
        return switch (txn.data().kind()) {
            case ATOMIC_BATCH -> HederaFunctionality.ATOMIC_BATCH;
            case CONSENSUS_CREATE_TOPIC -> HederaFunctionality.CONSENSUS_CREATE_TOPIC;
            case CONSENSUS_UPDATE_TOPIC -> HederaFunctionality.CONSENSUS_UPDATE_TOPIC;
            case CONSENSUS_DELETE_TOPIC -> HederaFunctionality.CONSENSUS_DELETE_TOPIC;
            case CONSENSUS_SUBMIT_MESSAGE -> HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
            case CONTRACT_CALL -> HederaFunctionality.CONTRACT_CALL;
            case CONTRACT_CREATE_INSTANCE -> HederaFunctionality.CONTRACT_CREATE;
            case CONTRACT_UPDATE_INSTANCE -> HederaFunctionality.CONTRACT_UPDATE;
            case CONTRACT_DELETE_INSTANCE -> HederaFunctionality.CONTRACT_DELETE;
            case CRYPTO_ADD_LIVE_HASH -> HederaFunctionality.CRYPTO_ADD_LIVE_HASH;
            case CRYPTO_APPROVE_ALLOWANCE -> HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE;
            case CRYPTO_CREATE_ACCOUNT -> HederaFunctionality.CRYPTO_CREATE;
            case CRYPTO_UPDATE_ACCOUNT -> HederaFunctionality.CRYPTO_UPDATE;
            case CRYPTO_DELETE -> HederaFunctionality.CRYPTO_DELETE;
            case CRYPTO_DELETE_ALLOWANCE -> HederaFunctionality.CRYPTO_DELETE_ALLOWANCE;
            case CRYPTO_DELETE_LIVE_HASH -> HederaFunctionality.CRYPTO_DELETE_LIVE_HASH;
            case CRYPTO_TRANSFER -> HederaFunctionality.CRYPTO_TRANSFER;
            case ETHEREUM_TRANSACTION -> HederaFunctionality.ETHEREUM_TRANSACTION;
            case LAMBDA_SSTORE -> HederaFunctionality.LAMBDA_S_STORE;
            case HOOK_DISPATCH -> HederaFunctionality.HOOK_DISPATCH;
            case FILE_APPEND -> HederaFunctionality.FILE_APPEND;
            case FILE_CREATE -> HederaFunctionality.FILE_CREATE;
            case FILE_UPDATE -> HederaFunctionality.FILE_UPDATE;
            case FILE_DELETE -> HederaFunctionality.FILE_DELETE;
            case FREEZE -> HederaFunctionality.FREEZE;
            case NODE_CREATE -> HederaFunctionality.NODE_CREATE;
            case NODE_DELETE -> HederaFunctionality.NODE_DELETE;
            case NODE_STAKE_UPDATE -> HederaFunctionality.NODE_STAKE_UPDATE;
            case NODE_UPDATE -> HederaFunctionality.NODE_UPDATE;
            case SCHEDULE_CREATE -> HederaFunctionality.SCHEDULE_CREATE;
            case SCHEDULE_SIGN -> HederaFunctionality.SCHEDULE_SIGN;
            case SCHEDULE_DELETE -> HederaFunctionality.SCHEDULE_DELETE;
            case STATE_SIGNATURE_TRANSACTION -> HederaFunctionality.STATE_SIGNATURE_TRANSACTION;
            case SYSTEM_DELETE -> HederaFunctionality.SYSTEM_DELETE;
            case SYSTEM_UNDELETE -> HederaFunctionality.SYSTEM_UNDELETE;
            case TOKEN_AIRDROP -> HederaFunctionality.TOKEN_AIRDROP;
            case TOKEN_ASSOCIATE -> HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
            case TOKEN_BURN -> HederaFunctionality.TOKEN_BURN;
            case TOKEN_CANCEL_AIRDROP -> HederaFunctionality.TOKEN_CANCEL_AIRDROP;
            case TOKEN_CLAIM_AIRDROP -> HederaFunctionality.TOKEN_CLAIM_AIRDROP;
            case TOKEN_CREATION -> HederaFunctionality.TOKEN_CREATE;
            case TOKEN_DELETION -> HederaFunctionality.TOKEN_DELETE;
            case TOKEN_DISSOCIATE -> HederaFunctionality.TOKEN_DISSOCIATE_FROM_ACCOUNT;
            case TOKEN_FEE_SCHEDULE_UPDATE -> HederaFunctionality.TOKEN_FEE_SCHEDULE_UPDATE;
            case TOKEN_FREEZE -> HederaFunctionality.TOKEN_FREEZE_ACCOUNT;
            case TOKEN_GRANT_KYC -> HederaFunctionality.TOKEN_GRANT_KYC_TO_ACCOUNT;
            case TOKEN_MINT -> HederaFunctionality.TOKEN_MINT;
            case TOKEN_PAUSE -> HederaFunctionality.TOKEN_PAUSE;
            case TOKEN_REJECT -> HederaFunctionality.TOKEN_REJECT;
            case TOKEN_REVOKE_KYC -> HederaFunctionality.TOKEN_REVOKE_KYC_FROM_ACCOUNT;
            case TOKEN_UNFREEZE -> HederaFunctionality.TOKEN_UNFREEZE_ACCOUNT;
            case TOKEN_UNPAUSE -> HederaFunctionality.TOKEN_UNPAUSE;
            case TOKEN_UPDATE -> HederaFunctionality.TOKEN_UPDATE;
            case TOKEN_UPDATE_NFTS -> HederaFunctionality.TOKEN_UPDATE_NFTS;
            case TOKEN_WIPE -> HederaFunctionality.TOKEN_ACCOUNT_WIPE;
            case UNCHECKED_SUBMIT -> HederaFunctionality.UNCHECKED_SUBMIT;
            case UTIL_PRNG -> HederaFunctionality.UTIL_PRNG;
            case HINTS_PREPROCESSING_VOTE -> HederaFunctionality.HINTS_PREPROCESSING_VOTE;
            case HINTS_KEY_PUBLICATION -> HederaFunctionality.HINTS_KEY_PUBLICATION;
            case HINTS_PARTIAL_SIGNATURE -> HederaFunctionality.HINTS_PARTIAL_SIGNATURE;
            case HISTORY_PROOF_SIGNATURE -> HederaFunctionality.HISTORY_ASSEMBLY_SIGNATURE;
            case HISTORY_PROOF_KEY_PUBLICATION -> HederaFunctionality.HISTORY_PROOF_KEY_PUBLICATION;
            case HISTORY_PROOF_VOTE -> HederaFunctionality.HISTORY_PROOF_VOTE;
            case CRS_PUBLICATION -> HederaFunctionality.CRS_PUBLICATION;
            case UNSET -> throw new UnknownHederaFunctionality();
        };
    }

    public static HederaFunctionality functionOf(final Query txn) throws UnknownHederaFunctionality {
        return switch (txn.query().kind()) {
            case TOKEN_GET_ACCOUNT_NFT_INFOS -> HederaFunctionality.TOKEN_GET_ACCOUNT_NFT_INFOS;
            case TOKEN_GET_NFT_INFOS -> HederaFunctionality.TOKEN_GET_NFT_INFOS;
            case ACCOUNT_DETAILS -> HederaFunctionality.GET_ACCOUNT_DETAILS;
            case CONSENSUS_GET_TOPIC_INFO -> HederaFunctionality.CONSENSUS_GET_TOPIC_INFO;
            case CONTRACT_CALL_LOCAL -> HederaFunctionality.CONTRACT_CALL_LOCAL;
            case CONTRACT_GET_BYTECODE -> HederaFunctionality.CONTRACT_GET_BYTECODE;
            case CONTRACT_GET_INFO -> HederaFunctionality.CONTRACT_GET_INFO;
            case CONTRACT_GET_RECORDS -> HederaFunctionality.CONTRACT_GET_RECORDS;
            case CRYPTO_GET_ACCOUNT_RECORDS -> HederaFunctionality.CRYPTO_GET_ACCOUNT_RECORDS;
            case CRYPTO_GET_INFO -> HederaFunctionality.CRYPTO_GET_INFO;
            case CRYPTO_GET_LIVE_HASH -> HederaFunctionality.CRYPTO_GET_LIVE_HASH;
            case FILE_GET_CONTENTS -> HederaFunctionality.FILE_GET_CONTENTS;
            case FILE_GET_INFO -> HederaFunctionality.FILE_GET_INFO;
            case CRYPTO_GET_PROXY_STAKERS -> HederaFunctionality.CRYPTO_GET_STAKERS;
            case GET_BY_SOLIDITY_ID -> HederaFunctionality.GET_BY_SOLIDITY_ID;
            case CRYPTOGET_ACCOUNT_BALANCE -> HederaFunctionality.CRYPTO_GET_ACCOUNT_BALANCE;
            case GET_BY_KEY -> HederaFunctionality.GET_BY_KEY;
            case NETWORK_GET_EXECUTION_TIME -> HederaFunctionality.NETWORK_GET_EXECUTION_TIME;
            case SCHEDULE_GET_INFO -> HederaFunctionality.SCHEDULE_GET_INFO;
            case TOKEN_GET_INFO -> HederaFunctionality.TOKEN_GET_INFO;
            case TOKEN_GET_NFT_INFO -> HederaFunctionality.TOKEN_GET_NFT_INFO;
            case NETWORK_GET_VERSION_INFO -> HederaFunctionality.GET_VERSION_INFO;
            case TRANSACTION_GET_RECEIPT -> HederaFunctionality.TRANSACTION_GET_RECEIPT;
            case TRANSACTION_GET_RECORD -> HederaFunctionality.TRANSACTION_GET_RECORD;
            case TRANSACTION_GET_FAST_RECORD -> HederaFunctionality.TRANSACTION_GET_FAST_RECORD;
            case UNSET -> throw new UnknownHederaFunctionality();
        };
    }

    /**
     * Utility to convert a {@link SemanticVersion} into a nicely formatted String.
     * @param version The version to convert
     * @return The string representation
     */
    public static String toString(@Nullable final SemanticVersion version) {
        if (version == null) {
            return "<NONE>";
        }
        var baseVersion = new StringBuilder("v");
        baseVersion
                .append(version.major())
                .append(".")
                .append(version.minor())
                .append(".")
                .append(version.patch());
        if (!version.pre().isBlank()) {
            baseVersion.append("-").append(version.pre());
        }
        if (!version.build().isBlank()) {
            baseVersion.append("+").append(version.build());
        }
        return baseVersion.toString();
    }

    /**
     * Parses a semantic version string and converts it into a {@link SemanticVersion} object.
     * The input string must adhere to the semantic versioning format as defined by semver.org.
     *
     * @param value The semantic version string to be parsed.
     * @return A {@link SemanticVersion} object representing the parsed version.
     * @throws IllegalArgumentException if the input string is not a valid semantic version.
     */
    public static SemanticVersion fromString(@NonNull final String value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.length() > MAX_VERSION_LENGTH) {
            throw new IllegalArgumentException("Semantic version '" + value + "' is too long");
        }
        final var matcher = SEMVER_SPEC_REGEX.matcher(value);
        if (matcher.matches()) {
            final var builder = SemanticVersion.newBuilder()
                    .major(Integer.parseInt(matcher.group(1)))
                    .minor(Integer.parseInt(matcher.group(2)))
                    .patch(Integer.parseInt(matcher.group(3)));
            if (matcher.group(4) != null) {
                builder.pre(matcher.group(4));
            }
            if (matcher.group(5) != null) {
                builder.build(matcher.group(5));
            }
            return builder.build();
        } else {
            throw new IllegalArgumentException("'" + value + "' is not a valid semantic version");
        }
    }

    /**
     * Parses an account from a string of the form shardNum.realmNum.accountNum
     * @param string The input string
     * @return The corresponding {@link AccountID}
     * @throws IllegalArgumentException if the string is not a dot-separated triplet of numbers
     */
    public static AccountID parseAccountFromLegacy(@NonNull final String string, final long shard, final long realm) {
        try {
            final var parts = string.split("\\.");
            return AccountID.newBuilder()
                    .shardNum(shard)
                    .realmNum(realm)
                    .accountNum(Long.parseLong(parts[2]))
                    .build();
        } catch (final NumberFormatException | ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException(String.format("'%s' is not a dot-separated triplet", string));
        }
    }

    /**
     * Utility to convert an {@link AccountID} into a nicely formatted String.
     * @param id The id to convert
     * @return The string representation
     */
    public static String toString(@NonNull final AccountID id) {
        var builder = new StringBuilder()
                .append(id.shardNum())
                .append(".")
                .append(id.realmNum())
                .append(".");

        if (id.hasAccountNum()) {
            builder.append(id.accountNum());
        } else if (id.hasAlias()) {
            builder.append(id.alias());
        } else {
            builder.append("-");
        }
        return builder.toString();
    }

    /**
     * Converts the given {@link Bytes} instance to a readable IPv4 address string.
     * @param ipV4Addr the {@link Bytes} instance to convert
     * @return the readable IPv4 address string
     */
    public static String asReadableIp(@NonNull final Bytes ipV4Addr) {
        requireNonNull(ipV4Addr);
        return "%d.%d.%d.%d"
                .formatted(
                        // Java expands a byte into an int, and the "sign bit" of the byte gets extended,
                        // making it possibly a negative integer for values > 0x7F. So we AND 0xFF
                        // to get rid of the extended "sign bits" to keep this an actual, positive byte.
                        ipV4Addr.getByte(0) & 0xFF,
                        ipV4Addr.getByte(1) & 0xFF,
                        ipV4Addr.getByte(2) & 0xFF,
                        ipV4Addr.getByte(3) & 0xFF);
    }

    /**
     * Converts given AccountID to string.
     * @param accountID account id to convert
     * @return string representation
     */
    public static String asAccountString(@NonNull final AccountID accountID) {
        return String.format("%d.%d.%d", accountID.shardNum(), accountID.realmNum(), accountID.accountNum());
    }
}
