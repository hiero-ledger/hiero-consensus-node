// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.utils;

import static com.esaulpaugh.headlong.abi.Address.toChecksumAddress;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.MISSING_ENTITY_NUMBER;
import static com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations.NON_CANONICAL_REFERENCE_NUMBER;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.ZERO_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.entityIdFactory;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.proxyUpdaterFor;
import static com.hedera.node.app.service.contract.impl.utils.SynthTxnUtils.hasNonDegenerateAutoRenewAccountId;
import static com.hedera.node.app.service.token.AliasUtils.extractEvmAddress;
import static java.lang.System.arraycopy;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.utility.CommonUtils.unhex;

import com.esaulpaugh.headlong.abi.Tuple;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.ContractLoginfo;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.ExchangeRate;
import com.hedera.hapi.streams.ContractStateChange;
import com.hedera.hapi.streams.ContractStateChanges;
import com.hedera.hapi.streams.StorageChange;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaNativeOperations;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.StorageAccesses;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.HederaConfig;
import com.swirlds.state.lifecycle.EntityIdFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.log.LogsBloomFilter;

/**
 * Some utility methods for converting between PBJ and Besu types and the various kinds of addresses and ids.
 */
public class ConversionUtils {
    /** The standard length as long of an address in Ethereum.*/
    public static final long EVM_ADDRESS_LENGTH_AS_LONG = 20L;
    /** The standard length of an address in Ethereum.*/
    public static final int EVM_ADDRESS_LENGTH_AS_INT = 20;
    /** The count of zero bytes in a long-zero address format.*/
    public static final int NUM_LONG_ZEROS = 12;
    /** Fee schedule units per tinycent.*/
    public static final long FEE_SCHEDULE_UNITS_PER_TINYCENT = 1000;

    private static final BigInteger MIN_LONG_VALUE = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX_LONG_VALUE = BigInteger.valueOf(Long.MAX_VALUE);

    private ConversionUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Given a list of {@link com.esaulpaugh.headlong.abi.Address}, returns their implied token ids.
     *
     * @param tokenAddresses the {@link com.esaulpaugh.headlong.abi.Address}es
     * @return the implied token ids
     */
    public static TokenID[] asTokenIds(@NonNull final com.esaulpaugh.headlong.abi.Address... tokenAddresses) {
        requireNonNull(tokenAddresses);
        final TokenID[] tokens = new TokenID[tokenAddresses.length];
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = asTokenId(tokenAddresses[i]);
        }
        return tokens;
    }

    /**
     * Given a numeric {@link AccountID}, returns its equivalent contract id.
     *
     * @param entityIdFactory the entity id factory
     * @param accountId the numeric {@link AccountID}
     * @return the equivalent account id
     */
    public static ContractID asNumericContractId(
            @NonNull final EntityIdFactory entityIdFactory, @NonNull final AccountID accountId) {
        return entityIdFactory.newContractId(accountId.accountNumOrThrow());
    }

    /**
     * Given a {@link com.esaulpaugh.headlong.abi.Address}, returns its implied token id.
     *
     * <p><b>IMPORTANT:</b> Mono-service ignores the shard and realm, c.f. De
     * codingFacade#convertAddressBytesToTokenID(), so we continue to do that here; might
     * want to revisit this later
     *
     * @param address the {@link com.esaulpaugh.headlong.abi.Address}
     * @return the implied token id
     */
    public static TokenID asTokenId(@NonNull final com.esaulpaugh.headlong.abi.Address address) {
        final var explicit = explicitFromHeadlong(address);
        return TokenID.newBuilder()
                .shardNum(shardOfLongZero(explicit))
                .realmNum(realmOfLongZero(explicit))
                .tokenNum(numberOfLongZero(explicit))
                .build();
    }

    /**
     * Given a {@link BigInteger}, returns either its long value or zero if it is out-of-range.
     *
     * @param value the {@link BigInteger}
     * @return its long value or zero if it is out-of-range
     */
    public static long asExactLongValueOrZero(@NonNull final BigInteger value) {
        requireNonNull(value);
        if (value.compareTo(MIN_LONG_VALUE) < 0 || value.compareTo(MAX_LONG_VALUE) > 0) {
            return 0L;
        }
        return value.longValueExact();
    }

    /**
     * Given a {@link AccountID}, returns its address as a headlong address.
     *
     * @param accountID the account id
     * @return the headlong address
     */
    public static com.esaulpaugh.headlong.abi.Address headlongAddressOf(@NonNull final AccountID accountID) {
        requireNonNull(accountID);
        final var integralAddress = accountID.hasAccountNum()
                ? asEvmAddress(accountID.shardNum(), accountID.realmNum(), accountID.accountNumOrThrow())
                : accountID
                        .aliasOrElse(com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY)
                        .toByteArray();
        return asHeadlongAddress(integralAddress);
    }

    /**
     * Given a {@link ContractID}, returns its address as a headlong address.
     *
     * @param contractId the contract id
     * @return the headlong address
     */
    public static com.esaulpaugh.headlong.abi.Address headlongAddressOf(@NonNull final ContractID contractId) {
        requireNonNull(contractId);
        final var integralAddress = contractId.hasContractNum()
                ? asEvmAddress(contractId.shardNum(), contractId.realmNum(), contractId.contractNumOrThrow())
                : contractId.evmAddressOrThrow().toByteArray();
        return asHeadlongAddress(integralAddress);
    }

    /**
     * Given a {@link ScheduleID}, returns its address as a headlong address.
     *
     * @param scheduleID the schedule id
     * @return the headlong address
     */
    public static com.esaulpaugh.headlong.abi.Address headlongAddressOf(@NonNull final ScheduleID scheduleID) {
        requireNonNull(scheduleID);
        final var integralAddress =
                asEvmAddress(scheduleID.shardNum(), scheduleID.realmNum(), scheduleID.scheduleNum());
        return asHeadlongAddress(integralAddress);
    }

    /**
     * Given a {@link ScheduleID}, returns its address as a headlong address.
     *
     * @param scheduleID the schedule id
     * @return the headlong address
     */
    public static com.esaulpaugh.headlong.abi.Address headlongAddressOf(
            @NonNull final com.hederahashgraph.api.proto.java.ScheduleID scheduleID) {
        requireNonNull(scheduleID);
        final var integralAddress =
                asEvmAddress(scheduleID.getShardNum(), scheduleID.getRealmNum(), scheduleID.getScheduleNum());
        return asHeadlongAddress(integralAddress);
    }

    /**
     * Given a {@link TokenID}, returns its address as a headlong address.
     *
     * @param tokenId the Token Id
     * @return the headlong address
     */
    public static com.esaulpaugh.headlong.abi.Address headlongAddressOf(@NonNull final TokenID tokenId) {
        requireNonNull(tokenId);
        return asHeadlongAddress(asEvmAddress(tokenId.shardNum(), tokenId.realmNum(), tokenId.tokenNum()));
    }

    /**
     * Given an account, returns its "priority" address as a Besu address.
     *
     * @param account the account
     * @return the priority address
     */
    public static Address priorityAddressOf(@NonNull final Account account) {
        requireNonNull(account);
        return Address.wrap(Bytes.wrap(explicitAddressOf(account)));
    }

    /**
     * Given a contract id, returns its "priority" form if the id refers to an extant contract with an
     * EVM address outside the long-zero subspace.
     *
     * <p>If there is no such contract; or if the id refers to a contract with an EVM address within the
     * long-zero subspace; then returns the given contract id.
     *
     * @param contractID the contract id
     * @param accountStore the account store
     * @return the priority form of the contract id
     */
    public static ContractID asPriorityId(
            @NonNull final ContractID contractID, @NonNull final ReadableAccountStore accountStore) {
        final var maybeContract = accountStore.getContractById(contractID);
        if (maybeContract != null && maybeContract.alias().length() == EVM_ADDRESS_LENGTH_AS_LONG) {
            return ContractID.newBuilder()
                    .shardNum(contractID.shardNum())
                    .realmNum(contractID.realmNum())
                    .evmAddress(maybeContract.alias())
                    .build();
        }
        return contractID;
    }

    /**
     * Given an account, returns its "priority" address as a headlong address.
     *
     * @param account the account
     * @return the headlong address
     */
    public static com.esaulpaugh.headlong.abi.Address headlongAddressOf(@NonNull final Account account) {
        requireNonNull(account);
        return asHeadlongAddress(explicitAddressOf(account));
    }

    /**
     * Given an explicit 20-byte array, converts it to a headlong address.
     *
     * @param explicit the explicit address
     * @return the headlong address
     */
    public static com.esaulpaugh.headlong.abi.Address asHeadlongAddress(@NonNull final byte[] explicit) {
        requireNonNull(explicit);
        final var integralAddress = Bytes.wrap(explicit).toUnsignedBigInteger();
        return com.esaulpaugh.headlong.abi.Address.wrap(toChecksumAddress(integralAddress));
    }

    /**
     * Given a list of Besu {@link Log}s, converts them to a list of PBJ {@link ContractLoginfo}.
     *
     * @param entityIdFactory the entity id factory
     * @param logs the Besu {@link Log}s
     * @return the PBJ {@link ContractLoginfo}s
     */
    public static List<ContractLoginfo> pbjLogsFrom(
            @NonNull final EntityIdFactory entityIdFactory, @NonNull final List<Log> logs) {
        final List<ContractLoginfo> pbjLogs = new ArrayList<>();
        for (final var log : logs) {
            pbjLogs.add(pbjLogFrom(entityIdFactory, log));
        }
        return pbjLogs;
    }

    /**
     * Wraps the first 32 bytes of the given SHA-384 {@link org.hiero.base.crypto.Hash hash} in a Besu {@link Hash}.
     *
     * @param sha384Hash the SHA-384 hash
     * @return the first 32 bytes as a Besu {@link Hash}
     */
    public static org.hyperledger.besu.datatypes.Hash ethHashFrom(
            @NonNull final com.hedera.pbj.runtime.io.buffer.Bytes sha384Hash) {
        requireNonNull(sha384Hash);
        final byte[] prefixBytes = new byte[32];
        sha384Hash.getBytes(0, prefixBytes, 0, prefixBytes.length);
        return org.hyperledger.besu.datatypes.Hash.wrap(Bytes32.wrap(prefixBytes));
    }

    /**
     * Given a list of {@link StorageAccesses}, converts them to a PBJ {@link ContractStateChanges}.
     *
     * @param storageAccesses the {@link StorageAccesses}
     * @return the PBJ {@link ContractStateChanges}
     */
    public static ContractStateChanges asPbjStateChanges(@NonNull final List<StorageAccesses> storageAccesses) {
        final List<ContractStateChange> allStateChanges = new ArrayList<>();
        for (final var storageAccess : storageAccesses) {
            final List<StorageChange> changes = new ArrayList<>();
            for (final var access : storageAccess.accesses()) {
                changes.add(new StorageChange(
                        tuweniToPbjBytes(access.key().trimLeadingZeros()),
                        tuweniToPbjBytes(access.value().trimLeadingZeros()),
                        access.isReadOnly()
                                ? null
                                : tuweniToPbjBytes(
                                        requireNonNull(access.writtenValue()).trimLeadingZeros())));
            }
            allStateChanges.add(new ContractStateChange(storageAccess.contractID(), changes));
        }
        return new ContractStateChanges(allStateChanges);
    }

    /**
     * Given a Besu {@link Log}, converts it a PBJ {@link ContractLoginfo}.
     *
     * @param entityIdFactory the entity id factory
     * @param log the Besu {@link Log}
     * @return the PBJ {@link ContractLoginfo}
     */
    public static ContractLoginfo pbjLogFrom(@NonNull final EntityIdFactory entityIdFactory, @NonNull final Log log) {
        final var loggerNumber = numberOfLongZero(log.getLogger());
        final List<com.hedera.pbj.runtime.io.buffer.Bytes> loggedTopics = new ArrayList<>();
        for (final var topic : log.getTopics()) {
            loggedTopics.add(tuweniToPbjBytes(topic));
        }
        return ContractLoginfo.newBuilder()
                .contractID(entityIdFactory.newContractId(loggerNumber))
                .data(tuweniToPbjBytes(log.getData()))
                .topic(loggedTopics)
                .bloom(bloomFor(log))
                .build();
    }

    /**
     * Given a {@link MessageFrame}, returns the long-zero address of its {@code contract} address.
     *
     * @param frame the {@link MessageFrame}
     * @return the long-zero address of the {@code contract} address
     */
    public static long hederaIdNumOfContractIn(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        return hederaIdNumberIn(frame, frame.getContractAddress());
    }

    /**
     * Given a {@link MessageFrame}, returns the long-zero address of its {@code originator} address.
     *
     * @param frame the {@link MessageFrame}
     * @return the long-zero address of the {@code originator} address
     */
    public static long hederaIdNumOfOriginatorIn(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        return hederaIdNumberIn(frame, frame.getOriginatorAddress());
    }

    /**
     * Given a {@link MessageFrame}, returns the id number of the given address's Hedera id.
     *
     * @param frame the {@link MessageFrame}
     * @param address the address to get the id number of
     * @return the id number of the given address's Hedera id
     */
    private static long hederaIdNumberIn(@NonNull final MessageFrame frame, @NonNull final Address address) {
        return isLongZero(entityIdFactory(frame), address)
                ? numberOfLongZero(address)
                : proxyUpdaterFor(frame).getHederaContractId(address).contractNumOrThrow();
    }

    /**
     * Given a {@link MessageFrame}, returns the long-zero address of its {@code recipient} address.
     *
     * @param frame the {@link MessageFrame}
     * @return the long-zero address of the {@code recipient} address
     */
    public static Address longZeroAddressOfRecipient(@NonNull final MessageFrame frame) {
        return longZeroAddressIn(frame, frame.getRecipientAddress());
    }

    /**
     * Given an EVM address (possibly long-zero), returns the number of the corresponding Hedera entity
     * within the given {@link HandleHederaNativeOperations}; or {@link HederaNativeOperations#MISSING_ENTITY_NUMBER}
     * if the address does not correspond to a known Hedera entity; or {@link HederaNativeOperations#NON_CANONICAL_REFERENCE_NUMBER}
     * if the address references an account by its "non-priority" long-zero address.
     *
     * @param address the EVM address
     * @param nativeOperations the {@link HandleHederaNativeOperations} to use for resolving aliases
     * @return the number of the corresponding Hedera entity, if it exists and has this priority address
     */
    public static long accountNumberForEvmReference(
            @NonNull final com.esaulpaugh.headlong.abi.Address address,
            @NonNull final HederaNativeOperations nativeOperations) {
        final var explicit = explicitFromHeadlong(address);
        final var number = maybeMissingNumberOf(explicit, nativeOperations);
        if (number == MISSING_ENTITY_NUMBER) {
            return MISSING_ENTITY_NUMBER;
        } else {
            final var account = nativeOperations.getAccount(
                    nativeOperations.entityIdFactory().newAccountId(number));
            if (account == null) {
                return MISSING_ENTITY_NUMBER;
            } else if (!Arrays.equals(explicit, explicitAddressOf(account))) {
                return NON_CANONICAL_REFERENCE_NUMBER;
            }
            return number;
        }
    }

    /**
     * Given an EVM address (possibly long-zero), returns the number of the corresponding Hedera entity
     * within the given {@link HandleHederaNativeOperations}; or {@link HederaNativeOperations#MISSING_ENTITY_NUMBER}
     * if the address is not long-zero and does not correspond to a known Hedera entity.
     *
     * @param address the EVM address
     * @param nativeOperations the {@link HandleHederaNativeOperations} to use for resolving aliases
     * @return the number of the corresponding Hedera entity, if it exists
     */
    public static long maybeMissingNumberOf(
            @NonNull final Address address, @NonNull final HederaNativeOperations nativeOperations) {
        return maybeMissingNumberOf(address.toArrayUnsafe(), nativeOperations);
    }

    /**
     * Given a long-zero EVM address, returns the implied Hedera entity number.
     *
     * @param address the EVM address
     * @return the implied Hedera entity number
     */
    @SuppressWarnings("java:S2201")
    public static long numberOfLongZero(@NonNull final Address address) {
        final var longVal = numberOfLongZero(address.toArray());
        if (longVal < 0) {
            throw new ArithmeticException("Long zero address is negative");
        }
        return longVal;
    }

    /**
     * Given an Besu EVM address, returns whether it is long-zero.
     *
     * @param entityIdFactory the entity id factory
     * @param address the EVM address (as a BESU {@link org.hyperledger.besu.datatypes.Address})
     * @return whether it is long-zero
     */
    public static boolean isLongZero(@NonNull final EntityIdFactory entityIdFactory, @NonNull final Address address) {
        return isLongZeroAddress(entityIdFactory, address.toArrayUnsafe());
    }

    /**
     * Given an headlong EVM address, returns whether it is long-zero.
     *
     * @param entityIdFactory the entity id factory
     * @param address the EVM address (as a headlong {@link com.esaulpaugh.headlong.abi.Address})
     * @return whether it is long-zero
     */
    public static boolean isLongZero(
            @NonNull final EntityIdFactory entityIdFactory,
            @NonNull final com.esaulpaugh.headlong.abi.Address address) {
        return isLongZeroAddress(entityIdFactory, explicitFromHeadlong(address));
    }

    /**
     * Given an explicit 20-byte array, returns whether it is a long-zero address.
     * True if the first 4 bytes matches the shard and if the next 8 bytes match the realm.
     *
     * @param entityIdFactory the entity id factory
     * @param explicit the explicit 20-byte array
     * @return whether it is a long-zero address
     */
    public static boolean isLongZeroAddress(@NonNull final EntityIdFactory entityIdFactory, final byte[] explicit) {
        // check if first bytes are matching the shard and the realm
        final var zeroAddress = unhex(entityIdFactory.hexLongZero(0));

        for (int i = 0; i < NUM_LONG_ZEROS; i++) {
            if (explicit[i] != zeroAddress[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts an EVM address to a PBJ {@link com.hedera.pbj.runtime.io.buffer.Bytes} alias.
     *
     * @param address the EVM address
     * @return the PBJ bytes alias
     */
    public static com.hedera.pbj.runtime.io.buffer.Bytes aliasFrom(@NonNull final Address address) {
        return com.hedera.pbj.runtime.io.buffer.Bytes.wrap(address.toArrayUnsafe());
    }

    /**
     * Converts a shard, realm, number to a long zero address.
     *
     * @param entityIdFactory the entity id factory
     * @param number the number to convert
     * @return the long zero address
     */
    public static Address asLongZeroAddress(@NonNull final EntityIdFactory entityIdFactory, final long number) {
        return Address.wrap(Bytes.wrap(asEvmAddress(entityIdFactory, number)));
    }

    /**
     * Converts a number to a long zero address.
     *
     * @param accountID the account id to convert
     * @return the long zero address
     */
    public static Address asLongZeroAddress(@NonNull final AccountID accountID) {
        return Address.wrap(
                Bytes.wrap(asEvmAddress(accountID.shardNum(), accountID.realmNum(), accountID.accountNumOrThrow())));
    }

    /**
     * Converts a Tuweni bytes to a PBJ bytes.
     *
     * @param bytes the Tuweni bytes
     * @return the PBJ bytes
     */
    public static @NonNull com.hedera.pbj.runtime.io.buffer.Bytes tuweniToPbjBytes(@NonNull final Bytes bytes) {
        return com.hedera.pbj.runtime.io.buffer.Bytes.wrap(requireNonNull(bytes).toArrayUnsafe());
    }

    /**
     * Converts an EVM address to a PBJ {@link ContractID} with alias instead of id number.
     *
     * @param entityIdFactory the entity id factory
     * @param address the EVM address
     * @return the PBJ {@link ContractID}
     */
    public static ContractID asEvmContractId(
            @NonNull final EntityIdFactory entityIdFactory, @NonNull final Address address) {
        return entityIdFactory.newContractIdWithEvmAddress(tuweniToPbjBytes(address));
    }

    /**
     * Converts a long-zero address to a PBJ {@link AccountID} with id number instead of alias.
     *
     * @param entityIdFactory the entity id factory
     * @param address the EVM address
     * @return the PBJ {@link AccountID}
     */
    public static AccountID asNumberedAccountId(
            @NonNull final EntityIdFactory entityIdFactory, @NonNull final Address address) {
        if (!isLongZero(entityIdFactory, address)) {
            throw new IllegalArgumentException("Cannot extract id number from address " + address);
        }
        return entityIdFactory.newAccountId(numberOfLongZero(address));
    }

    /**
     * Converts a long-zero address to a PBJ {@link ContractID} with id number instead of alias.
     *
     * @param entityIdFactory the entity id factory
     * @param address the EVM address
     * @return the PBJ {@link ContractID}
     */
    public static ContractID asNumberedContractId(
            @NonNull final EntityIdFactory entityIdFactory, @NonNull final Address address) {
        if (!isLongZero(entityIdFactory, address)) {
            throw new IllegalArgumentException("Cannot extract id number from address " + address);
        }
        return entityIdFactory.newContractId(numberOfLongZero(address));
    }

    /**
     * Converts a headlong address to a PBJ {@link ScheduleID}.
     *
     * @param entityIdFactory the entity id factory
     * @param address the schedule address
     * @return the PBJ {@link ScheduleID}
     */
    public static ScheduleID addressToScheduleID(
            @NonNull final EntityIdFactory entityIdFactory,
            @NonNull final com.esaulpaugh.headlong.abi.Address address) {
        if (!isLongZero(entityIdFactory, address)) {
            throw new IllegalArgumentException("Cannot extract id number from address " + address);
        }

        // Get the last 16 characters of the address. We need only the schedule number skipping the shard and realm
        var addressHex = toChecksumAddress(address.value());
        var scheduleNum = addressHex.substring(26, 42);

        return entityIdFactory.newScheduleId(new BigInteger(scheduleNum, 16).longValue());
    }

    /**
     * Throws a {@link HandleException} if the given outcome did not succeed for a call.
     * @param outcome the outcome
     * @param hederaOperations the Hedera operations
     * @param streamBuilder the stream builder
     */
    public static void throwIfUnsuccessfulCall(
            @NonNull final CallOutcome outcome,
            @NonNull final HederaOperations hederaOperations,
            @NonNull final ContractCallStreamBuilder streamBuilder) {
        requireNonNull(outcome);
        requireNonNull(hederaOperations);
        requireNonNull(streamBuilder);
        if (outcome.status() != SUCCESS) {
            throw new HandleException(outcome.status(), feeChargingContext -> {
                hederaOperations.replayGasChargingIn(feeChargingContext);
                outcome.addCalledContractIfNotAborted(streamBuilder);
            });
        }
    }

    /**
     * Throws a {@link HandleException} if the given outcome did not succeed for a call.
     * @param outcome the outcome
     * @param hederaOperations the Hedera operations
     */
    public static void throwIfUnsuccessfulCreate(
            @NonNull final CallOutcome outcome, @NonNull final HederaOperations hederaOperations) {
        requireNonNull(outcome);
        requireNonNull(hederaOperations);
        if (outcome.status() != SUCCESS) {
            throw new HandleException(outcome.status(), hederaOperations::replayGasChargingIn);
        }
    }

    /**
     * Converts a PBJ bytes to Tuweni bytes.
     *
     * @param bytes the PBJ bytes
     * @return the Tuweni bytes
     */
    public static @NonNull Bytes pbjToTuweniBytes(@NonNull final com.hedera.pbj.runtime.io.buffer.Bytes bytes) {
        if (bytes.length() == 0) {
            return Bytes.EMPTY;
        }
        return Bytes.wrap(clampedBytes(bytes, 0, Integer.MAX_VALUE));
    }

    /**
     * Returns whether the given alias is an EVM address.
     *
     * @param alias the alias
     * @return whether it is an EVM address
     */
    public static boolean isEvmAddress(@Nullable final com.hedera.pbj.runtime.io.buffer.Bytes alias) {
        return alias != null && alias.length() == EVM_ADDRESS_LENGTH_AS_LONG;
    }

    /**
     * Converts a PBJ bytes to a Besu address.
     *
     * @param bytes the PBJ bytes
     * @return the Besu address
     * @throws IllegalArgumentException if the bytes are not 20 bytes long
     */
    public static @NonNull Address pbjToBesuAddress(@NonNull final com.hedera.pbj.runtime.io.buffer.Bytes bytes) {
        return Address.wrap(Bytes.wrap(clampedBytes(bytes, EVM_ADDRESS_LENGTH_AS_INT, EVM_ADDRESS_LENGTH_AS_INT)));
    }

    /**
     * Converts a PBJ bytes to a Besu hash.
     *
     * @param bytes the PBJ bytes
     * @return the Besu hash
     * @throws IllegalArgumentException if the bytes are not 32 bytes long
     */
    public static @NonNull Hash pbjToBesuHash(@NonNull final com.hedera.pbj.runtime.io.buffer.Bytes bytes) {
        return Hash.wrap(Bytes32.wrap(clampedBytes(bytes, 32, 32)));
    }

    /**
     * Converts a PBJ bytes to a Tuweni UInt256.
     *
     * @param bytes the PBJ bytes
     * @return the Tuweni bytes
     * @throws IllegalArgumentException if the bytes are more than 32 bytes long
     */
    public static @NonNull UInt256 pbjToTuweniUInt256(@NonNull final com.hedera.pbj.runtime.io.buffer.Bytes bytes) {
        return (bytes.length() == 0) ? UInt256.ZERO : UInt256.fromBytes(Bytes32.wrap(clampedBytes(bytes, 0, 32)));
    }

    /**
     * Returns the PBJ bloom for a list of Besu {@link Log}s.
     *
     * @param logs the Besu {@link Log}s
     * @return the PBJ bloom
     */
    public static com.hedera.pbj.runtime.io.buffer.Bytes bloomForAll(@NonNull final List<Log> logs) {
        return com.hedera.pbj.runtime.io.buffer.Bytes.wrap(
                LogsBloomFilter.builder().insertLogs(logs).build().toArray());
    }

    private static byte[] clampedBytes(
            @NonNull final com.hedera.pbj.runtime.io.buffer.Bytes bytes, final int minLength, final int maxLength) {
        final var length = Math.toIntExact(requireNonNull(bytes).length());
        if (length < minLength) {
            throw new IllegalArgumentException("Expected at least " + minLength + " bytes, got " + bytes);
        }
        if (length > maxLength) {
            throw new IllegalArgumentException("Expected at most " + maxLength + " bytes, got " + bytes);
        }
        final byte[] data = new byte[length];
        bytes.getBytes(0, data);
        return data;
    }

    /**
     * Given a long entity number, returns its 20-byte EVM address.
     * The shard is downcast to an int so it must not exceed the range of an int.
     *
     * @param entityIdFactory the entity id factory
     * @param num the entity number
     * @return its 20-byte EVM address
     */
    public static byte[] asEvmAddress(@NonNull final EntityIdFactory entityIdFactory, final long num) {
        return unhex(entityIdFactory.hexLongZero(num));
    }

    /**
     * Given a long entity number, returns its 20-byte EVM address.
     * The shard is downcast to an int so it must not exceed the range of an int.
     *
     * @param shard the shard number
     * @param realm the realm number
     * @param num the entity number
     * @return its 20-byte EVM address
     */
    public static byte[] asEvmAddress(final long shard, final long realm, final long num) {
        final byte[] evmAddress = new byte[20];

        arraycopy(Ints.toByteArray((int) shard), 0, evmAddress, 0, 4);
        arraycopy(Longs.toByteArray(realm), 0, evmAddress, 4, 8);
        arraycopy(Longs.toByteArray(num), 0, evmAddress, 12, 8);

        return evmAddress;
    }

    /**
     * Given a value and a destination byte array, copies the value to the destination array, left-padded.
     *
     * @param value the value
     * @param dest the destination byte array
     * @return the destination byte array
     */
    public static byte[] copyToLeftPaddedByteArray(long value, final byte[] dest) {
        for (int i = 7, j = dest.length - 1; i >= 0; i--, j--) {
            dest[j] = (byte) (value & 0xffL);
            value >>= 8;
        }
        return dest;
    }

    /**
     * Given a headlong address, returns its explicit 20-byte array.
     *
     * @param address the headlong address
     * @return its explicit 20-byte array
     */
    public static byte[] explicitFromHeadlong(@NonNull final com.esaulpaugh.headlong.abi.Address address) {
        return unhex(address.toString().substring(2));
    }

    /**
     * Given an explicit 20-byte addresss, returns its long value.
     *
     * @param explicit the explicit 20-byte address
     * @return its long value
     */
    public static long numberOfLongZero(@NonNull final byte[] explicit) {
        final var number = longFrom(
                explicit[12],
                explicit[13],
                explicit[14],
                explicit[15],
                explicit[16],
                explicit[17],
                explicit[18],
                explicit[19]);
        if (number < 0) {
            throw new IllegalArgumentException("Number is negative");
        }
        return number;
    }

    /**
     * Given an explicit 20-byte addresss, returns its realm value.
     *
     * @param explicit the explicit 20-byte address
     * @return its realm value
     */
    public static long realmOfLongZero(@NonNull final byte[] explicit) {
        final var realm = longFrom(
                explicit[4],
                explicit[5],
                explicit[6],
                explicit[7],
                explicit[8],
                explicit[9],
                explicit[10],
                explicit[11]);
        if (realm < 0) {
            throw new IllegalArgumentException("Realm is negative");
        }
        return realm;
    }

    /**
     * Given an explicit 20-byte addresss, returns its shard value.
     *
     * @param explicit the explicit 20-byte address
     * @return its shard value
     */
    public static int shardOfLongZero(@NonNull final byte[] explicit) {
        final var shard = longFrom(explicit[0], explicit[1], explicit[2], explicit[3]);
        if (shard < 0) {
            throw new IllegalArgumentException("Shard is negative");
        }
        return shard;
    }

    // too many arguments
    @SuppressWarnings("java:S107")
    private static long longFrom(
            final byte b1,
            final byte b2,
            final byte b3,
            final byte b4,
            final byte b5,
            final byte b6,
            final byte b7,
            final byte b8) {
        return (b1 & 0xFFL) << 56
                | (b2 & 0xFFL) << 48
                | (b3 & 0xFFL) << 40
                | (b4 & 0xFFL) << 32
                | (b5 & 0xFFL) << 24
                | (b6 & 0xFFL) << 16
                | (b7 & 0xFFL) << 8
                | (b8 & 0xFFL);
    }

    private static int longFrom(final byte b1, final byte b2, final byte b3, final byte b4) {
        return (b1 & 0xFF) << 24 | (b2 & 0xFF) << 16 | (b3 & 0xFF) << 8 | (b4 & 0xFF);
    }

    private static com.hedera.pbj.runtime.io.buffer.Bytes bloomFor(@NonNull final Log log) {
        return com.hedera.pbj.runtime.io.buffer.Bytes.wrap(
                LogsBloomFilter.builder().insertLog(log).build().toArray());
    }

    private static Address longZeroAddressIn(@NonNull final MessageFrame frame, @NonNull final Address address) {
        return isLongZero(entityIdFactory(frame), address)
                ? address
                : asLongZeroAddress(
                        entityIdFactory(frame),
                        proxyUpdaterFor(frame).getHederaContractId(address).contractNumOrThrow());
    }

    private static long maybeMissingNumberOf(
            @NonNull final byte[] explicit, @NonNull final HederaNativeOperations nativeOperations) {
        if (isLongZeroAddress(nativeOperations.entityIdFactory(), explicit)) {
            return longFrom(
                    explicit[12],
                    explicit[13],
                    explicit[14],
                    explicit[15],
                    explicit[16],
                    explicit[17],
                    explicit[18],
                    explicit[19]);
        } else {
            final var evmAddress = extractEvmAddress(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(explicit));
            final var config = nativeOperations.configuration().getConfigData(HederaConfig.class);
            return evmAddress == null
                    ? HederaNativeOperations.MISSING_ENTITY_NUMBER
                    : nativeOperations.resolveAlias(config.shard(), config.realm(), evmAddress);
        }
    }

    /**
     * Given an account, returns its explicit 20-byte address.
     *
     * @param account the account
     * @return the explicit 20-byte address
     */
    public static byte[] explicitAddressOf(@NonNull final Account account) {
        requireNonNull(account);
        final var evmAddress = extractEvmAddress(account.alias());
        return evmAddress != null
                ? evmAddress.toByteArray()
                : asEvmAddress(
                        account.accountIdOrThrow().shardNum(),
                        account.accountIdOrThrow().realmNum(),
                        account.accountIdOrThrow().accountNumOrThrow());
    }

    /**
     * Given a headlong address, converts it to a Besu {@link Address}.
     *
     * @param address the headlong address
     * @return the Besu {@link Address}
     */
    public static Address fromHeadlongAddress(@NonNull final com.esaulpaugh.headlong.abi.Address address) {
        requireNonNull(address);
        return Address.fromHexString(address.toString());
    }

    /**
     * Given an exchange rate and a tinycent amount, returns the equivalent tinybar amount.
     *
     * @param exchangeRate the exchange rate
     * @param tinycents the tinycent amount
     * @return the equivalent tinybar amount
     */
    public static long fromTinycentsToTinybars(final ExchangeRate exchangeRate, final long tinycents) {
        return fromAToB(BigInteger.valueOf(tinycents), exchangeRate.hbarEquiv(), exchangeRate.centEquiv())
                .longValueExact();
    }

    /**
     * Given an exchange rate and a tinybar amount, returns the equivalent tinycent amount.
     *
     * @param exchangeRate the exchange rate
     * @param tinyBars the tinybar amount
     * @return the equivalent tinycent amount
     */
    public static long fromTinybarsToTinycents(final ExchangeRate exchangeRate, final long tinyBars) {
        return fromAToB(BigInteger.valueOf(tinyBars), exchangeRate.centEquiv(), exchangeRate.hbarEquiv())
                .longValueExact();
    }

    /**
     * Given an amount in one unit and its conversion rate to another unit, returns the equivalent amount
     * in the other unit.
     *
     * @param aAmount the amount in one unit
     * @param bEquiv the numerator of the conversion rate
     * @param aEquiv the denominator of the conversion rate
     * @return the equivalent amount in the other unit
     */
    public static @NonNull BigInteger fromAToB(@NonNull final BigInteger aAmount, final int bEquiv, final int aEquiv) {
        return aAmount.multiply(BigInteger.valueOf(bEquiv)).divide(BigInteger.valueOf(aEquiv));
    }

    /**
     * Given a {@link ContractID} return the corresponding Besu {@link Address}
     * Importantly, this method does NOT check for the existence of the contract in the ledger
     *
     * @param entityIdFactory the entity id factory
     * @param contractId the contract id
     * @return the equivalent Besu address
     */
    public static @NonNull Address contractIDToBesuAddress(
            @NonNull final EntityIdFactory entityIdFactory, @NonNull final ContractID contractId) {
        if (contractId.hasEvmAddress()) {
            return pbjToBesuAddress(contractId.evmAddressOrThrow());
        } else {
            // OrElse(0) is needed, as an UNSET contract OneOf has null number
            return asLongZeroAddress(entityIdFactory.newAccountId(contractId.contractNumOrElse(0L)));
        }
    }

    /**
     * Given a {@link ContractID} return the corresponding Long entity id
     * The id can be tokenId, scheduleId, etc. Depends on what user is sending in the 'contractID' field.
     * <p>
     * Returns 0 if contractId in a non-long zero evm address
     *
     * @param entityIdFactory the entity id factory
     * @param contractId the contract id
     * @return the equivalent entity id (0 if contractId in a non-long zero evm address)
     */
    public static @NonNull Long contractIDToNum(
            @NonNull final EntityIdFactory entityIdFactory, final ContractID contractId) {
        final Long id;
        // For convenience also translate a long-zero address to a entity id
        if (contractId.hasEvmAddress()) {
            final var evmAddress = contractId.evmAddressOrThrow().toByteArray();
            if (isLongZeroAddress(entityIdFactory, evmAddress)) {
                id = numberOfLongZero(evmAddress);
            } else {
                id = 0L;
            }
        } else {
            id = contractId.contractNumOrElse(0L);
        }
        return id;
    }

    /**
     * Given a {@link ContractCreateTransactionBody} and a sponsor {@link Account}, returns a creation body
     * fully customized with the sponsor's properties.
     *
     * @param op the creation body
     * @param sponsor the sponsor
     * @return the fully customized creation body
     */
    public static @NonNull ContractCreateTransactionBody sponsorCustomizedCreation(
            @NonNull final ContractCreateTransactionBody op, @NonNull final Account sponsor) {
        requireNonNull(op);
        requireNonNull(sponsor);
        final var builder = op.copyBuilder();
        if (sponsor.memo() != null) {
            builder.memo(sponsor.memo());
        }
        if (hasNonDegenerateAutoRenewAccountId(sponsor)) {
            builder.autoRenewAccountId(sponsor.autoRenewAccountId());
        }
        if (sponsor.stakedAccountId() != null) {
            builder.stakedAccountId(sponsor.stakedAccountId());
        }
        if (sponsor.autoRenewSeconds() > 0) {
            builder.autoRenewPeriod(
                    Duration.newBuilder().seconds(sponsor.autoRenewSeconds()).build());
        }
        return builder.maxAutomaticTokenAssociations(sponsor.maxAutoAssociations())
                .declineReward(sponsor.declineReward())
                .build();
    }

    /**
     * Given a {@link ContractCreateTransactionBody} and a new account number, returns a creation body
     * that contains a self-managed admin key (contract key with the new account number).
     *
     * @param op the creation body
     * @param contractID the contractID for the about to be newly created contract
     * @return the fully customized creation body
     */
    public static @NonNull ContractCreateTransactionBody selfManagedCustomizedCreation(
            @NonNull final ContractCreateTransactionBody op, @NonNull final ContractID contractID) {
        requireNonNull(op);
        final var builder = op.copyBuilder();
        return builder.adminKey(Key.newBuilder()
                        .contractID(contractID.copyBuilder().build())
                        .build())
                .build();
    }

    /**
     * Returns a tuple of the {@code KeyValue} struct
     * <br><a href="https://github.com/hashgraph/hedera-smart-contracts/blob/main/contracts/hts-precompile/IHederaTokenService.sol#L92">Link</a>
     * @param key the key to get the tuple for
     * @return Tuple encoding of the KeyValue
     */
    @NonNull
    public static Tuple keyTupleFor(@NonNull final Key key) {
        return Tuple.of(
                false,
                headlongAddressOf(key.contractIDOrElse(ZERO_CONTRACT_ID)),
                key.ed25519OrElse(com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY).toByteArray(),
                key.ecdsaSecp256k1OrElse(com.hedera.pbj.runtime.io.buffer.Bytes.EMPTY)
                        .toByteArray(),
                headlongAddressOf(key.delegatableContractIdOrElse(ZERO_CONTRACT_ID)));
    }

    /**
     * @param contents Ethereum content
     * @return remove the leading 0x from an Ethereum content
     */
    public static byte[] removeIfAnyLeading0x(com.hedera.pbj.runtime.io.buffer.Bytes contents) {
        final var hexPrefix = new byte[] {(byte) '0', (byte) 'x'};
        final var offset = contents.matchesPrefix(hexPrefix) ? hexPrefix.length : 0L;
        final var len = contents.length() - offset;
        return contents.getBytes(offset, len).toByteArray();
    }
}
