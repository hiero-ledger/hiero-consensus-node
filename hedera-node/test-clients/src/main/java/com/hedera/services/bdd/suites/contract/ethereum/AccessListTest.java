// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.*;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.genRandomBytes;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.node.app.hapi.utils.ethereum.AccessListItem;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class AccessListTest {

    private static final String SLOT_KEY_0 = "0x0000000000000000000000000000000000000000000000000000000000000000";
    private static final String SLOT_KEY_1 = "0x0000000000000000000000000000000000000000000000000000000000000001";

    @Contract(contract = "AccessListCallerContract")
    static SpecContract callerContract;

    @Contract(contract = "AccessListTargetContract")
    static SpecContract targetContract;

    private static final AtomicReference<Address> TARGET_CONTRACT_ADDRESS = new AtomicReference<>();
    private static final AtomicReference<Bytes> TARGET_CONTRACT_ADDRESS_BYTES = new AtomicReference<>();

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                callerContract.getInfo(),
                targetContract
                        .getInfo()
                        .andAssert(e -> e.exposingEvmAddress(address -> {
                            TARGET_CONTRACT_ADDRESS.set(asHeadlongAddress(address));
                            TARGET_CONTRACT_ADDRESS_BYTES.set(
                                    Bytes.fromHexString(address.startsWith("0x") ? address : "0x" + address));
                        })));
    }

    // ------------------------------- Utils -------------------------------
    private static HapiEthereumCall ethereumCallWithRandomAccessList(
            final EthTxData.EthTransactionType type,
            final AtomicLong expectedLegacyGas,
            final List<Integer> storageKeysCount) {
        // generate access list
        final List<AccessListItem> accessList = new ArrayList<>();
        for (final Integer count : storageKeysCount) {
            accessList.add(new AccessListItem(
                    Bytes.wrap(genRandomBytes(20)),
                    IntStream.range(0, count)
                            .mapToObj(e -> Bytes32.wrap(genRandomBytes(32)))
                            .toList()));
        }
        // execute ethereumCall
        return ethereumCallWithAccessList(
                type,
                accessList,
                () -> expectedLegacyGas.get()
                        + 2_400L * storageKeysCount.size()
                        + 1_900L * storageKeysCount.stream().mapToInt(e -> e).sum());
    }

    private static HapiEthereumCall ethereumCallWithAccessList(
            final EthTxData.EthTransactionType type,
            final List<AccessListItem> accessList,
            final LongSupplier expectedGas) {
        return ethereumCall(callerContract.name(), "call", TARGET_CONTRACT_ADDRESS.get())
                .signingWith(SECP_256K1_SOURCE_KEY)
                .type(type)
                .withAccessList(accessList)
                .exposingGasTo((status, gas) -> Assertions.assertEquals(expectedGas.getAsLong(), gas));
    }
    // ------------------------------- /Utils -------------------------------

    @HapiTest
    final Stream<DynamicTest> accessListIntrinsicGasTest() {
        final AtomicLong legacyGas = new AtomicLong();
        return hapiTest(flattened(
                // prepare sender
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                // LEGACY_ETHEREUM call
                ethereumCall(callerContract.name(), "call", TARGET_CONTRACT_ADDRESS.get())
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .type(EthTxData.EthTransactionType.LEGACY_ETHEREUM)
                        .exposingGasTo((status, gas) -> legacyGas.set(gas)),
                // EIP2930/EIP1559 calls with random accessList
                Stream.of(EthTxData.EthTransactionType.EIP2930, EthTxData.EthTransactionType.EIP1559)
                        .flatMap(type -> Stream.of(
                                ethereumCallWithRandomAccessList(type, legacyGas, List.of()),
                                ethereumCallWithRandomAccessList(type, legacyGas, List.of(0)),
                                ethereumCallWithRandomAccessList(type, legacyGas, List.of(1)),
                                ethereumCallWithRandomAccessList(type, legacyGas, List.of(2, 3, 4))))
                        .toList()));
    }

    @HapiTest
    final Stream<DynamicTest> accessListDiscountTest() {
        final AtomicLong legacyGas = new AtomicLong();
        return hapiTest(flattened(
                // prepare sender
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                // LEGACY_ETHEREUM call
                ethereumCall(callerContract.name(), "call", TARGET_CONTRACT_ADDRESS.get())
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .type(EthTxData.EthTransactionType.LEGACY_ETHEREUM)
                        .exposingGasTo((status, gas) -> legacyGas.set(gas)),
                // EIP2930/EIP1559 calls to check the discount
                Stream.of(EthTxData.EthTransactionType.EIP2930, EthTxData.EthTransactionType.EIP1559)
                        .flatMap(type -> Stream.of(
                                ethereumCallWithAccessList(
                                        type,
                                        List.of(new AccessListItem(TARGET_CONTRACT_ADDRESS_BYTES.get(), List.of())),
                                        () -> legacyGas.get() - 100),
                                ethereumCallWithAccessList(
                                        type,
                                        List.of(new AccessListItem(
                                                TARGET_CONTRACT_ADDRESS_BYTES.get(),
                                                List.of(
                                                        Bytes32.fromHexString(SLOT_KEY_0) // storage slot 0
                                                        ))),
                                        () -> legacyGas.get() - 200),
                                ethereumCallWithAccessList(
                                        type,
                                        List.of(new AccessListItem(
                                                TARGET_CONTRACT_ADDRESS_BYTES.get(),
                                                List.of(
                                                        Bytes32.fromHexString(SLOT_KEY_0), // storage slot 0
                                                        Bytes32.fromHexString(SLOT_KEY_1) // storage slot 1
                                                        ))),
                                        () -> legacyGas.get() - 300)))
                        .toList()));
    }
}
