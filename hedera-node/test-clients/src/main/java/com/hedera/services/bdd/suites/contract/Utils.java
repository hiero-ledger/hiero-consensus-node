// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract;

import static com.esaulpaugh.headlong.abi.Address.toChecksumAddress;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.relocatedIfNotPresentInWorkingDir;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.NUM_LONG_ZEROS;
import static com.hedera.services.bdd.spec.HapiPropertySource.asDotDelimitedLongArray;
import static com.hedera.services.bdd.spec.dsl.entities.SpecContract.VARIANT_NONE;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.CONSTRUCTOR;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCall;
import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;
import static java.lang.System.arraycopy;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.hiero.base.utility.CommonUtils.hex;
import static org.hiero.base.utility.CommonUtils.unhex;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Address;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.node.app.hapi.fees.pricing.AssetsLoader;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.util.encoders.Hex;
import org.hiero.base.utility.CommonUtils;
import org.hyperledger.besu.crypto.Hash;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public class Utils {
    public static final String RESOURCE_PATH = "src/main/resources/contract/%1$s/%2$s/%2$s%3$s";
    public static final String DEFAULT_CONTRACTS_ROOT = "contracts";

    public static final String UNIQUE_CLASSPATH_RESOURCE_TPL = "contract/contracts/%s/%s";
    private static final Logger log = LogManager.getLogger(Utils.class);
    private static final String JSON_EXTENSION = ".json";

    public static ByteString eventSignatureOf(String event) {
        return ByteString.copyFrom(Hash.keccak256(Bytes.wrap(event.getBytes())).toArray());
    }

    public static ByteString parsedToByteString(long shard, long realm, long n) {
        final var hexString =
                Bytes.wrap(asSolidityAddress((int) shard, realm, n)).toHexString();
        return ByteString.copyFrom(Bytes32.fromHexStringLenient(hexString).toArray());
    }

    public static ByteString parsedToByteString(long n) {
        return ByteString.copyFrom(
                Bytes32.fromHexStringLenient(Long.toHexString(n)).toArray());
    }

    public static String asHexedAddress(final TokenID id) {
        return Bytes.wrap(asSolidityAddress((int) id.getShardNum(), id.getRealmNum(), id.getTokenNum()))
                .toHexString();
    }

    public static byte[] asAddress(final TokenID id) {
        return asSolidityAddress((int) id.getShardNum(), id.getRealmNum(), id.getTokenNum());
    }

    public static byte[] asAddress(final AccountID id) {
        return asSolidityAddress((int) id.getShardNum(), id.getRealmNum(), id.getAccountNum());
    }

    public static byte[] asAddress(final ContractID id) {
        if (id.getEvmAddress().size() == 20) {
            return id.getEvmAddress().toByteArray();
        }
        return asSolidityAddress((int) id.getShardNum(), id.getRealmNum(), id.getContractNum());
    }

    public static byte[] asAddressInTopic(final byte[] solidityAddress) {
        final byte[] topicAddress = new byte[32];

        arraycopy(solidityAddress, 0, topicAddress, 12, 20);
        return topicAddress;
    }

    /**
     * Returns the bytecode of the contract by the name of the contract from the classpath resource.
     *
     * @param contractName the name of the contract
     * @param variant the variant system contract if any
     * @return the bytecode of the contract
     * @throws IllegalArgumentException if the contract is not found
     * @throws UncheckedIOException if an I/O error occurs
     */
    public static ByteString getInitcodeOf(@NonNull final String contractName, @NonNull final String variant) {
        final var path = getResourcePath(defaultContractsRoot(variant), contractName, ".bin");
        try {
            final var bytes = Files.readAllBytes(relocatedIfNotPresentInWorkingDir(Path.of(path)));
            return ByteString.copyFrom(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ByteString extractByteCode(String path) {
        try {
            final var bytes = Files.readAllBytes(relocatedIfNotPresentInWorkingDir(Path.of(path)));
            return ByteString.copyFrom(bytes);
        } catch (IOException e) {
            log.warn("An error occurred while reading file", e);
            return ByteString.EMPTY;
        }
    }

    public static ByteString extractBytecodeUnhexed(final String path) {
        try {
            final var bytes = Files.readAllBytes(Path.of(path));
            return ByteString.copyFrom(Hex.decode(bytes));
        } catch (IOException e) {
            log.warn("An error occurred while reading file", e);
            return ByteString.EMPTY;
        }
    }

    /**
     * This method extracts the function ABI by the name of the desired function and the name of the
     * respective contract. Depending on the desired function type, it can deliver either a
     * constructor ABI, or function ABI from the contract ABI
     *
     * @param type accepts {@link FunctionType} - enum, either CONSTRUCTOR, or FUNCTION
     * @param functionName the name of the function. If the desired function is constructor, the
     *     function name must be EMPTY ("")
     * @param contractName the name of the contract
     */
    public static String getABIFor(final FunctionType type, final String functionName, final String contractName) {
        return getABIFor(VARIANT_NONE, type, functionName, contractName);
    }

    /**
     * This method extracts the function ABI by the name of the desired function and the name of the
     * respective contract. Depending on the desired function type, it can deliver either a
     * constructor ABI, or function ABI from the contract ABI
     *
     * This overloaded method allows for a variant contract root folder
     *
     * @param variant variant contract root folder
     * @param type accepts {@link FunctionType} - enum, either CONSTRUCTOR, or FUNCTION
     * @param functionName the name of the function. If the desired function is constructor, the
     *     function name must be EMPTY ("")
     * @param contractName the name of the contract
     */
    public static String getABIFor(
            final String variant, final FunctionType type, final String functionName, final String contractName) {
        try {
            final var path = getResourcePath(defaultContractsRoot(variant), contractName, JSON_EXTENSION);
            try (final var input = new FileInputStream(path)) {
                return getFunctionAbiFrom(input, functionName, type);
            }
        } catch (final Exception ignore) {
            return getResourceABIFor(type, functionName, contractName);
        }
    }

    public static String getResourceABIFor(
            final FunctionType type, final String functionName, final String contractName) {
        final var resourcePath =
                String.format(UNIQUE_CLASSPATH_RESOURCE_TPL, contractName, contractName + JSON_EXTENSION);
        try (final var input = Utils.class.getClassLoader().getResourceAsStream(resourcePath)) {
            return getFunctionAbiFrom(input, functionName, type);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String getFunctionAbiFrom(final InputStream in, final String functionName, final FunctionType type) {
        final var array = new JSONArray(new JSONTokener(in));
        return IntStream.range(0, array.length())
                .mapToObj(array::getJSONObject)
                .filter(object -> type == CONSTRUCTOR
                        ? object.getString("type").equals(type.toString().toLowerCase())
                        : object.getString("type").equals(type.toString().toLowerCase())
                                && object.getString("name").equals(functionName))
                .map(JSONObject::toString)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such function found: " + functionName));
    }

    /**
     * Delivers the entire contract ABI by contract name
     *
     * @param contractName the name of the contract
     */
    public static String getABIForContract(final String contractName) {
        final var path = getResourcePath(contractName, JSON_EXTENSION);
        var ABI = EMPTY;
        try {
            ABI = FileUtils.readFileToString(new File(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return ABI;
    }

    /**
     * Generates a path to a desired contract resource
     *
     * @param resourceName the name of the contract
     * @param extension the type of the desired contract resource (.bin or .json)
     */
    public static String getResourcePath(String resourceName, final String extension) {
        return getResourcePath(DEFAULT_CONTRACTS_ROOT, resourceName, extension);
    }

    /**
     * Generates a path to a desired contract resource
     *
     * @param resourceName the name of the contract
     * @param extension the type of the desired contract resource (.bin or .json)
     */
    public static String getResourcePath(String rootDirectory, String resourceName, final String extension) {
        resourceName = resourceName.replaceAll("\\d*$", "");
        final var path = String.format(RESOURCE_PATH, rootDirectory, resourceName, extension);
        final var file = relocatedIfNotPresentInWorkingDir(new File(path));
        if (!file.exists()) {
            throw new IllegalArgumentException("Invalid argument: " + path.substring(path.lastIndexOf('/') + 1));
        }
        return file.getPath();
    }

    public enum FunctionType {
        CONSTRUCTOR,
        FUNCTION
    }

    public static TokenID asToken(String v) {
        long[] nativeParts = asDotDelimitedLongArray(v);
        return TokenID.newBuilder()
                .setShardNum(nativeParts[0])
                .setRealmNum(nativeParts[1])
                .setTokenNum(nativeParts[2])
                .build();
    }

    public static AccountAmount aaWith(final AccountID account, final long amount) {
        return AccountAmount.newBuilder()
                .setAccountID(account)
                .setAmount(amount)
                .build();
    }

    public static AccountAmount aaWith(final HapiSpec spec, final byte[] bytes, final long amount) {
        final var acctId = accountIdWithHexedEvmAddress(spec.shard(), spec.realm(), hex(bytes));
        return AccountAmount.newBuilder().setAccountID(acctId).setAmount(amount).build();
    }

    public static AccountAmount aaWith(final HapiSpec spec, final ByteString hexedEvmAddress, final long amount) {
        final var acctId = accountIdWithHexedEvmAddress(spec.shard(), spec.realm(), hexedEvmAddress.toStringUtf8());
        return AccountAmount.newBuilder().setAccountID(acctId).setAmount(amount).build();
    }

    public static AccountAmount aaWith(HapiSpec spec, final String hexedEvmAddress, final long amount) {
        return aaWith(spec.shard(), spec.realm(), hexedEvmAddress, amount);
    }

    public static AccountAmount aaWith(long shard, long realm, final String hexedEvmAddress, final long amount) {
        return AccountAmount.newBuilder()
                .setAccountID(accountIdWithHexedEvmAddress(shard, realm, hexedEvmAddress))
                .setAmount(amount)
                .build();
    }

    public static NftTransfer ocWith(final AccountID from, final AccountID to, final long serialNo) {
        return NftTransfer.newBuilder()
                .setSenderAccountID(from)
                .setReceiverAccountID(to)
                .setSerialNumber(serialNo)
                .build();
    }

    public static AccountID accountIdWithHexedEvmAddress(HapiSpec spec, final String hexedEvmAddress) {
        return accountIdWithHexedEvmAddress(spec.shard(), spec.realm(), hexedEvmAddress);
    }

    public static AccountID accountIdWithHexedEvmAddress(
            final long shard, final long realm, final String hexedEvmAddress) {
        return AccountID.newBuilder()
                .setShardNum(shard)
                .setRealmNum(realm)
                .setAlias(ByteString.copyFrom(unhex(hexedEvmAddress)))
                .build();
    }

    public static AccountID accountIdFromEvmAddress(final HapiSpec spec, final ByteString evmAddress) {
        return AccountID.newBuilder()
                .setShardNum(spec.shard())
                .setRealmNum(spec.realm())
                .setAlias(evmAddress)
                .build();
    }

    public static Key aliasContractIdKey(HapiSpec spec, final String hexedEvmAddress) {
        return aliasContractIdKey(spec.shard(), spec.realm(), hexedEvmAddress);
    }

    public static Key aliasContractIdKey(final long shard, final long realm, final String hexedEvmAddress) {
        return Key.newBuilder()
                .setContractID(ContractID.newBuilder()
                        .setShardNum(shard)
                        .setRealmNum(realm)
                        .setEvmAddress(ByteString.copyFrom(CommonUtils.unhex(hexedEvmAddress))))
                .build();
    }

    public static Key aliasDelegateContractKey(final HapiSpec spec, final String hexedEvmAddress) {
        return aliasDelegateContractKey(spec.shard(), spec.realm(), hexedEvmAddress);
    }

    public static Key aliasDelegateContractKey(final long shard, final long realm, final String hexedEvmAddress) {
        return Key.newBuilder()
                .setDelegatableContractId(ContractID.newBuilder()
                        .setShardNum(shard)
                        .setRealmNum(realm)
                        .setEvmAddress(ByteString.copyFrom(CommonUtils.unhex(hexedEvmAddress))))
                .build();
    }

    public static HapiSpecOperation captureOneChildCreate2MetaFor(
            final String desc,
            final String creation2,
            final AtomicReference<String> mirrorAddr,
            final AtomicReference<String> create2Addr) {
        return captureChildCreate2MetaFor(1, 0, desc, creation2, mirrorAddr, create2Addr);
    }

    /**
     * This method captures the meta information of a CREATE2 operation. It extracts the mirror and the create2 addresses.
     * Additionally, it verifies the number of children
     */
    public static HapiSpecOperation captureChildCreate2MetaFor(
            final int givenNumExpectedChildren,
            final int givenChildOfInterest,
            final String desc,
            final String creation2,
            final AtomicReference<String> mirrorAddr,
            final AtomicReference<String> create2Addr) {
        return withOpContext((spec, opLog) -> {
            final var lookup = getTxnRecord(creation2).andAllChildRecords().logged();
            allRunFor(spec, lookup);
            final var response = lookup.getResponse().getTransactionGetRecord();
            final var numRecords = response.getChildTransactionRecordsCount();
            int numExpectedChildren = givenNumExpectedChildren;
            int childOfInterest = givenChildOfInterest;

            // if we use ethereum transaction for contract creation, we have one additional child record
            var creation2ContractId =
                    lookup.getResponseRecord().getContractCreateResult().getContractID();
            if (spec.registry().hasEVMAddress(String.valueOf(creation2ContractId.getContractNum()))) {
                numExpectedChildren++;
                childOfInterest++;
            }

            if (numRecords == numExpectedChildren + 1
                    && TxnUtils.isEndOfStakingPeriodRecord(response.getChildTransactionRecords(0))) {
                // This transaction may have had a preceding record for the end-of-day
                // staking calculations
                numExpectedChildren++;
                childOfInterest++;
            }
            assertEquals(numExpectedChildren, response.getChildTransactionRecordsCount(), "Wrong # of children");
            final var create2Record = response.getChildTransactionRecords(childOfInterest);
            final var create2Address =
                    create2Record.getContractCreateResult().getEvmAddress().getValue();
            create2Addr.set(hex(create2Address.toByteArray()));
            final var createdId = create2Record.getReceipt().getContractID();
            mirrorAddr.set(hex(asSolidityAddress(createdId)));
            opLog.info("{} is @ {} (mirror {})", desc, create2Addr.get(), mirrorAddr.get());
        });
    }

    public static Instant asInstant(final Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    public static Address[] nCopiesOfSender(final int n, final Address mirrorAddr) {
        return Collections.nCopies(n, mirrorAddr).toArray(Address[]::new);
    }

    public static Address[] nNonMirrorAddressFrom(final int n, final long m) {
        return LongStream.range(m, m + n).mapToObj(Utils::nonMirrorAddrWith).toArray(Address[]::new);
    }

    public static Address headlongFromHexed(final String addr) {
        return Address.wrap(toChecksumAddress("0x" + addr));
    }

    public static Address mirrorAddrWith(AccountID accountID) {
        return Address.wrap(toChecksumAddress(new BigInteger(
                1,
                asSolidityAddress((int) accountID.getShardNum(), accountID.getRealmNum(), accountID.getAccountNum()))));
    }

    public static Address mirrorAddrWith(ContractID contractId) {
        return Address.wrap(toChecksumAddress(new BigInteger(
                1,
                asSolidityAddress(
                        (int) contractId.getShardNum(), contractId.getRealmNum(), contractId.getContractNum()))));
    }

    public static Address mirrorAddrWith(HapiSpec spec, final long num) {
        return Address.wrap(
                toChecksumAddress(new BigInteger(1, asSolidityAddress((int) spec.shard(), spec.realm(), num))));
    }

    @Deprecated(forRemoval = true)
    public static Function<HapiSpec, Object[]> mirrorAddrParamFunction(final long contractNum) {
        return spec -> List.of(mirrorAddrWith(spec, contractNum)).toArray();
    }

    public static Address mirrorAddrWith(final long shard, final long realm, final long num) {
        return Address.wrap(toChecksumAddress(new BigInteger(1, asSolidityAddress((int) shard, realm, num))));
    }

    public static Address nonMirrorAddrWith(final long num) {
        return nonMirrorAddrWith(666, num);
    }

    public static Address nonMirrorAddrWith(final long seed, final long num) {
        return Address.wrap(toChecksumAddress(new BigInteger(1, asSolidityAddress((int) seed, seed, num))));
    }

    public static Address numAsHeadlongAddress(HapiSpec spec, final long num) {
        return idAsHeadlongAddress(AccountID.newBuilder()
                .setShardNum(spec.shard())
                .setRealmNum(spec.realm())
                .setAccountNum(num)
                .build());
    }

    public static Address idAsHeadlongAddress(final AccountID accountId) {
        return asHeadlongAddress(
                asSolidityAddress((int) accountId.getShardNum(), accountId.getRealmNum(), accountId.getAccountNum()));
    }

    public static Address idAsHeadlongAddress(final TokenID tokenId) {
        return asHeadlongAddress(
                asSolidityAddress((int) tokenId.getShardNum(), tokenId.getRealmNum(), tokenId.getTokenNum()));
    }

    public static byte[] asSolidityAddress(final AccountID accountId) {
        return asSolidityAddress((int) accountId.getShardNum(), accountId.getRealmNum(), accountId.getAccountNum());
    }

    public static byte[] asSolidityAddress(final ContractID contractId) {
        return asSolidityAddress((int) contractId.getShardNum(), contractId.getRealmNum(), contractId.getContractNum());
    }

    public static byte[] asSolidityAddress(final TokenID tokenId) {
        return asSolidityAddress((int) tokenId.getShardNum(), tokenId.getRealmNum(), tokenId.getTokenNum());
    }

    public static byte[] asSolidityAddress(HapiSpec spec, final long num) {
        return asSolidityAddress((int) spec.shard(), spec.realm(), num);
    }

    public static byte[] asSolidityAddress(final int shard, final long realm, final long num) {
        final byte[] solidityAddress = new byte[20];

        arraycopy(Ints.toByteArray(shard), 0, solidityAddress, 0, 4);
        arraycopy(Longs.toByteArray(realm), 0, solidityAddress, 4, 8);
        arraycopy(Longs.toByteArray(num), 0, solidityAddress, 12, 8);

        return solidityAddress;
    }

    public static String asHexedSolidityAddress(final AccountID accountId) {
        return CommonUtils.hex(asSolidityAddress(accountId));
    }

    public static String asHexedSolidityAddress(final ContractID contractId) {
        return CommonUtils.hex(asSolidityAddress(contractId));
    }

    public static String asHexedSolidityAddress(final TokenID tokenId) {
        return CommonUtils.hex(asSolidityAddress(tokenId));
    }

    public static String asHexedSolidityAddress(final HapiSpec spec, final long num) {
        return CommonUtils.hex(asSolidityAddress(spec, num));
    }

    public static String asHexedSolidityAddress(final int shard, final long realm, final long num) {
        return CommonUtils.hex(asSolidityAddress(shard, realm, num));
    }

    public static ContractID contractIdFromHexedMirrorAddress(final String hexedEvm) {
        byte[] unhex = CommonUtils.unhex(hexedEvm);
        return ContractID.newBuilder()
                .setShardNum(Ints.fromByteArray(Arrays.copyOfRange(unhex, 0, 4)))
                .setRealmNum(Longs.fromByteArray(Arrays.copyOfRange(unhex, 4, 12)))
                .setContractNum(Longs.fromByteArray(Arrays.copyOfRange(unhex, 12, 20)))
                .build();
    }

    public static AccountID accountIdFromHexedMirrorAddress(final String hexedEvm) {
        byte[] unhex = CommonUtils.unhex(hexedEvm);
        return AccountID.newBuilder()
                .setShardNum(Ints.fromByteArray(Arrays.copyOfRange(unhex, 0, 4)))
                .setRealmNum(Longs.fromByteArray(Arrays.copyOfRange(unhex, 4, 12)))
                .setAccountNum(Longs.fromByteArray(Arrays.copyOfRange(unhex, 12, 20)))
                .build();
    }

    public static String literalIdFromHexedMirrorAddress(final String hexedEvm) {
        byte[] unhex = CommonUtils.unhex(hexedEvm);
        return HapiPropertySource.asContractString(ContractID.newBuilder()
                .setShardNum(Ints.fromByteArray(Arrays.copyOfRange(unhex, 0, 4)))
                .setRealmNum(Longs.fromByteArray(Arrays.copyOfRange(unhex, 4, 12)))
                .setContractNum(Longs.fromByteArray(Arrays.copyOfRange(unhex, 12, 20)))
                .build());
    }

    public static long expectedPrecompileGasFor(
            final HapiSpec spec, final HederaFunctionality function, final SubType type) {
        final var gasThousandthsOfTinycentPrice = spec.fees()
                .getCurrentOpFeeData()
                .get(ContractCall)
                .get(DEFAULT)
                .getServicedata()
                .getGas();
        final var assetsLoader = new AssetsLoader();
        final BigDecimal hapiUsdPrice;
        try {
            hapiUsdPrice = assetsLoader.loadCanonicalPrices().get(function).get(type);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        final var precompileTinycentPrice = hapiUsdPrice
                .multiply(BigDecimal.valueOf(1.2))
                .multiply(BigDecimal.valueOf(100 * 100_000_000L))
                .longValueExact();
        return (precompileTinycentPrice * 1000 / gasThousandthsOfTinycentPrice);
    }

    @NonNull
    public static String getNestedContractAddress(final String outerContract, final HapiSpec spec) {
        return asHexedSolidityAddress(spec.registry().getContractId(outerContract));
    }

    @NonNull
    @SuppressWarnings("java:S5960")
    public static CustomSpecAssert assertTxnRecordHasNoTraceabilityEnrichedContractFnResult(
            final String nestedTransferTxn) {
        return assertionsHold((spec, log) -> {
            final var subOp = getTxnRecord(nestedTransferTxn);
            allRunFor(spec, subOp);

            final var rcd = subOp.getResponseRecord();

            final var contractCallResult = rcd.getContractCallResult();
            assertEquals(0L, contractCallResult.getGas(), "Result not expected to externalize gas");
            assertEquals(0L, contractCallResult.getAmount(), "Result not expected to externalize amount");
            assertEquals(ByteString.EMPTY, contractCallResult.getFunctionParameters());
        });
    }

    @NonNull
    public static String defaultContractsRoot(@NonNull final String variant) {
        return variant.isEmpty() ? DEFAULT_CONTRACTS_ROOT : DEFAULT_CONTRACTS_ROOT + "_" + requireNonNull(variant);
    }

    /**
     * Converts a long-zero address to a {@link ScheduleID} with id number instead of alias.
     *
     * @param address the EVM address
     * @return the {@link ScheduleID}
     */
    public static com.hederahashgraph.api.proto.java.ScheduleID asScheduleId(
            @NonNull final com.esaulpaugh.headlong.abi.Address address) {
        var addressHex = toChecksumAddress(address.value());
        if (addressHex.startsWith("0x")) {
            addressHex = addressHex.substring(2);
        }
        var shard = addressHex.substring(0, 8);
        var realm = addressHex.substring(8, 24);
        var scheduleNum = addressHex.substring(24, 40);

        return com.hederahashgraph.api.proto.java.ScheduleID.newBuilder()
                .setShardNum(new BigInteger(shard, 16).longValue())
                .setRealmNum(new BigInteger(realm, 16).longValue())
                .setScheduleNum(new BigInteger(scheduleNum, 16).longValue())
                .build();
    }

    public static boolean isLongZeroAddress(final long shard, final long realm, final byte[] explicit) {
        // check if first bytes are matching the shard and the realm
        final byte[] shardAndRealm = new byte[12];
        arraycopy(Ints.toByteArray((int) shard), 0, shardAndRealm, 0, 4);
        arraycopy(Longs.toByteArray(realm), 0, shardAndRealm, 4, 8);
        for (int i = 0; i < NUM_LONG_ZEROS; i++) {
            if (explicit[i] != shardAndRealm[i]) {
                return false;
            }
        }
        return true;
    }
}
