// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.hapi;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateAnyLogAfter;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static java.lang.Integer.MAX_VALUE;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ContractStateSuite {
    private static final String CONTRACT = "StateContract";
    private static final SplittableRandom RANDOM = new SplittableRandom(1_234_567L);

    @HapiTest
    @DisplayName("inserting new slots after a net-zero usage change doesn't cause IterableStorageManager ERROR logs")
    final Stream<DynamicTest> netZeroSlotUsageUpdateLogsNoErrors() {
        final var contract = "ThreeSlots";
        return hapiTest(
                overriding("contracts.maxGasPerSec", "15_000_000_000"),
                uploadInitCode(contract),
                contractCreate(contract).gas(500_000),
                // Use slot 'b' only
                contractCall(contract, "setAB", BigInteger.ZERO, BigInteger.ONE),
                // Clear slot 'b', use slot 'a' (net-zero slot usage but first key impact)
                contractCall(contract, "setAB", BigInteger.ONE, BigInteger.ZERO),
                // And now use slot 'c' (will trigger ERROR log unless first key is 'a')
                contractCall(contract, "setC", BigInteger.ONE),
                // Ensure there are still no problems in the logs
                validateAnyLogAfter(Duration.ofMillis(450)));
    }

    @HapiTest
    final Stream<DynamicTest> stateChangesSpec() {
        final var iterations = 2;
        final var integralTypes = Map.ofEntries(
                Map.entry("Uint8", 0x01),
                Map.entry("Uint16", 0x02),
                Map.entry("Uint32", (long) 0x03),
                Map.entry("Uint64", BigInteger.valueOf(4)),
                Map.entry("Uint128", BigInteger.valueOf(5)),
                Map.entry("Uint256", BigInteger.valueOf(6)),
                Map.entry("Int8", 0x01),
                Map.entry("Int16", 0x02),
                Map.entry("Int32", 0x03),
                Map.entry("Int64", 4L),
                Map.entry("Int128", BigInteger.valueOf(5)),
                Map.entry("Int256", BigInteger.valueOf(6)));

        return hapiTest(flattened(
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT),
                IntStream.range(0, iterations)
                        .boxed()
                        .flatMap(i -> Stream.of(
                                        Stream.of(contractCall(CONTRACT, "setVarBool", RANDOM.nextBoolean())),
                                        Arrays.stream(integralTypes.keySet().toArray(new String[0]))
                                                .map(type -> contractCall(
                                                        CONTRACT, "setVar" + type, integralTypes.get(type))),
                                        Stream.of(contractCall(CONTRACT, "setVarAddress", randomHeadlongAddress())),
                                        Stream.of(contractCall(CONTRACT, "setVarContractType")
                                                .gas(5_000_000)),
                                        Stream.of(contractCall(CONTRACT, "setVarBytes32", randomBytes32())),
                                        Stream.of(contractCall(CONTRACT, "setVarString", randomString())),
                                        Stream.of(contractCall(CONTRACT, "setVarEnum", randomEnum())),
                                        randomSetAndDeleteVarInt(),
                                        randomSetAndDeleteString(),
                                        randomSetAndDeleteStruct())
                                .flatMap(s -> s))
                        .toArray(HapiSpecOperation[]::new)));
    }

    private Stream<HapiSpecOperation> randomSetAndDeleteVarInt() {
        final var numSetsAndDeletes = 2;
        return IntStream.range(0, numSetsAndDeletes)
                .boxed()
                .flatMap(i -> Stream.of(
                        contractCall(CONTRACT, "setVarIntArrDataAlloc", new Object[] {randomInts()})
                                .gas(5_000_000),
                        contractCall(CONTRACT, "deleteVarIntArrDataAlloc")));
    }

    private Stream<HapiSpecOperation> randomSetAndDeleteString() {
        final var numCycles = 2;
        return IntStream.range(0, numCycles)
                .boxed()
                .flatMap(i -> Stream.of(
                        contractCall(CONTRACT, "setVarStringConcat", randomString())
                                .gas(5_000_000),
                        contractCall(CONTRACT, "setVarStringConcat", randomString())
                                .gas(5_000_000),
                        contractCall(CONTRACT, "deleteVarStringConcat").gas(5_000_000)));
    }

    private Stream<HapiSpecOperation> randomSetAndDeleteStruct() {
        final var numCycles = 4;
        return IntStream.range(0, numCycles)
                .boxed()
                .flatMap(i -> Stream.of(
                        contractCall(CONTRACT, "setVarContractStruct", randomContractStruct())
                                .gas(5_000_000),
                        contractCall(CONTRACT, "deleteVarContractStruct").gas(5_000_000)));
    }

    private Tuple randomContractStruct() {
        return Tuple.from(
                BigInteger.valueOf(RANDOM.nextInt(MAX_VALUE)),
                randomHeadlongAddress(),
                randomBytes32(),
                randomString(),
                RANDOM.nextInt(3),
                randomInts(),
                randomString());
    }

    private Address randomHeadlongAddress() {
        final var bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return asHeadlongAddress(bytes);
    }

    private byte[] randomBytes32() {
        final var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private String randomString() {
        return new String(randomBytes32());
    }

    private int randomEnum() {
        return RANDOM.nextInt(3);
    }

    private BigInteger[] randomInts() {
        return new BigInteger[] {
            BigInteger.valueOf(RANDOM.nextInt(MAX_VALUE)),
            BigInteger.valueOf(RANDOM.nextInt(MAX_VALUE)),
            BigInteger.valueOf(RANDOM.nextInt(MAX_VALUE)),
            BigInteger.valueOf(RANDOM.nextInt(MAX_VALUE)),
        };
    }
}
