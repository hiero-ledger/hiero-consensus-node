// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
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
import com.hedera.services.bdd.spec.SpecOperation;
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
    private static final String SLOT_KEY_2 = "0x0000000000000000000000000000000000000000000000000000000000000002";

    @Contract(contract = "AccessListCallerContract", creationGas = 1_000_000)
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
                "call",
                new Object[]{TARGET_CONTRACT_ADDRESS.get()},
                type,
                accessList,
                () -> expectedLegacyGas.get()
                        + 2_400L * storageKeysCount.size()
                        + 1_900L * storageKeysCount.stream().mapToInt(e -> e).sum());
    }

    private static HapiEthereumCall ethereumCallWithAccessList(
            final String functionName,
            final Object[] params,
            final EthTxData.EthTransactionType type,
            final List<AccessListItem> accessList,
            final LongSupplier expectedGas) {
        return ethereumCall(callerContract.name(), functionName, params)
                .signingWith(SECP_256K1_SOURCE_KEY)
                .type(type)
                .withAccessList(accessList)
                .via("qweqwe") //TODO
                .exposingGasTo((status, gas) -> Assertions.assertEquals(
                        expectedGas.getAsLong(),
                        gas,
                        "Wrong gas for type:%s AccessList:%s".formatted(type, accessList)));
    }
    // ------------------------------- /Utils -------------------------------

    @HapiTest
    final Stream<DynamicTest> accessListIntrinsicGasTest() {
        final var legacyGas = new AtomicLong();
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

    private SpecOperation[] checkAccessListDiscount(final String functionName,
                                                    final Object[] params) {
        final var legacyGas = new AtomicLong();
        // LEGACY_ETHEREUM call
        return flattened(ethereumCall(callerContract.name(), functionName, params)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .type(EthTxData.EthTransactionType.LEGACY_ETHEREUM)
                        .exposingGasTo((status, gas) -> legacyGas.set(gas)),
                // EIP2930/EIP1559 calls to check the discount
                Stream.of(EthTxData.EthTransactionType.EIP2930, EthTxData.EthTransactionType.EIP1559)
                        .flatMap(type -> Stream.of(
                                ethereumCallWithAccessList(
                                        functionName, params,
                                        type,
                                        List.of(new AccessListItem(TARGET_CONTRACT_ADDRESS_BYTES.get(), List.of())),
                                        () -> legacyGas.get() - 100), // -100 for CALL
                                ethereumCallWithAccessList(
                                        functionName, params,
                                        type,
                                        List.of(new AccessListItem(
                                                TARGET_CONTRACT_ADDRESS_BYTES.get(),
                                                List.of(Bytes32.fromHexString(SLOT_KEY_0)))),
                                        () -> legacyGas.get() - 200), // -100 for CALL, -100 for SLOAD
                                ethereumCallWithAccessList(
                                        functionName, params,
                                        type,
                                        List.of(new AccessListItem(
                                                TARGET_CONTRACT_ADDRESS_BYTES.get(),
                                                List.of(
                                                        Bytes32.fromHexString(SLOT_KEY_0),
                                                        Bytes32.fromHexString(SLOT_KEY_1)))),
                                        () -> legacyGas.get() - 300), // -100 for CALL, -100 x 2 for SLOAD x 2
                                ethereumCallWithAccessList(
                                        functionName, params,
                                        type,
                                        List.of(new AccessListItem(
                                                TARGET_CONTRACT_ADDRESS_BYTES.get(),
                                                List.of(
                                                        Bytes32.fromHexString(SLOT_KEY_0),
                                                        Bytes32.fromHexString(SLOT_KEY_1),
                                                        Bytes32.fromHexString(SLOT_KEY_2)))),
                                        () -> legacyGas.get()
                                                - 500) // -100 for CALL, -100 x 3 for SLOAD x 3, -100 for SSTORE
                        ))
                        .toList()
                , getTxnRecord("qweqwe").logged()//TODO
        );
    }

    @HapiTest
    final Stream<DynamicTest> accessListDiscountTest() {
        return hapiTest(flattened(
                // prepare sender
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                // execute calls with access list
                checkAccessListDiscount("call",
                        new Object[]{TARGET_CONTRACT_ADDRESS.get()})
        ));
    }

//    @HapiTest
    final Stream<DynamicTest> accessListDiscountForDelegationCallTest() {
        return hapiTest(flattened(
                // prepare sender
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(
                        GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                // set code delegation
                ethereumCall(callerContract.name(), "callDelegation")
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .type(EthTxData.EthTransactionType.EIP7702)
                        .addSenderCodeDelegationWithSpecNonce(TARGET_CONTRACT_ADDRESS.get()),
                // execute calls with access list
                checkAccessListDiscount("callDelegation",
                        new Object[0])
        ));
    }

    //TODO callDelegation execution is not working, returning success = true, result = 0
//    @HapiTest
    final Stream<DynamicTest> accessListAndAuthorizationListTest() {
        return hapiTest(
                // prepare sender
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(
                        GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                // set code delegation
                ethereumCall(callerContract.name(), "callDelegation")
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .type(EthTxData.EthTransactionType.EIP7702)
                        .addSenderCodeDelegationWithSpecNonce(TARGET_CONTRACT_ADDRESS.get())
                        .exposingGasTo((status, gas) -> System.out.println("!!!!!!!!!!!callDelegation1:" + gas)),
                // set code delegation with access list
                ethereumCall(callerContract.name(), "callDelegation")
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .type(EthTxData.EthTransactionType.EIP7702)
                        .addSenderCodeDelegationWithSpecNonce(TARGET_CONTRACT_ADDRESS.get())
                        .withAccessList(List.of(new AccessListItem(TARGET_CONTRACT_ADDRESS_BYTES.get(), List.of())))
                        .exposingGasTo((status, gas) -> System.out.println("!!!!!!!!!!!callDelegation2:" + gas)),
                ethereumCall(callerContract.name(), "callDelegation")
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .type(EthTxData.EthTransactionType.EIP7702)
                        .addSenderCodeDelegationWithSpecNonce(TARGET_CONTRACT_ADDRESS.get())
                        .withAccessList(List.of(new AccessListItem(
                                TARGET_CONTRACT_ADDRESS_BYTES.get(), List.of(Bytes32.fromHexString(SLOT_KEY_0)))))
                        .exposingGasTo((status, gas) -> System.out.println("!!!!!!!!!!!callDelegation3:" + gas)),
                ethereumCall(callerContract.name(), "callDelegation")
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .type(EthTxData.EthTransactionType.EIP7702)
                        .addSenderCodeDelegationWithSpecNonce(TARGET_CONTRACT_ADDRESS.get())
                        .withAccessList(List.of(new AccessListItem(
                                TARGET_CONTRACT_ADDRESS_BYTES.get(),
                                List.of(Bytes32.fromHexString(SLOT_KEY_0), Bytes32.fromHexString(SLOT_KEY_1)))))
                        .exposingGasTo((status, gas) -> System.out.println("!!!!!!!!!!!callDelegation4:" + gas)),
                ethereumCall(callerContract.name(), "callDelegation")
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .type(EthTxData.EthTransactionType.EIP7702)
                        .addSenderCodeDelegationWithSpecNonce(TARGET_CONTRACT_ADDRESS.get())
                        .withAccessList(List.of(new AccessListItem(
                                TARGET_CONTRACT_ADDRESS_BYTES.get(),
                                List.of(
                                        Bytes32.fromHexString(SLOT_KEY_0),
                                        Bytes32.fromHexString(SLOT_KEY_1),
                                        Bytes32.fromHexString(SLOT_KEY_2)))))
                        .exposingGasTo((status, gas) -> System.out.println("!!!!!!!!!!!callDelegation5:" + gas)),
                // use access list
                ethereumCall(callerContract.name(), "callDelegation")
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .type(EthTxData.EthTransactionType.EIP2930)
                        .exposingGasTo((status, gas) -> System.out.println("!!!!!!!!!!!AccessList1:" + gas)),
                ethereumCall(callerContract.name(), "callDelegation")
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .type(EthTxData.EthTransactionType.EIP2930)
                        .withAccessList(List.of(new AccessListItem(TARGET_CONTRACT_ADDRESS_BYTES.get(), List.of())))
                        .exposingGasTo((status, gas) -> System.out.println("!!!!!!!!!!!AccessList2:" + gas)),
                ethereumCall(callerContract.name(), "callDelegation")
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .type(EthTxData.EthTransactionType.EIP2930)
                        .withAccessList(List.of(new AccessListItem(
                                TARGET_CONTRACT_ADDRESS_BYTES.get(), List.of(Bytes32.fromHexString(SLOT_KEY_0)))))
                        .via("AccessList3")
                        .exposingGasTo((status, gas) -> System.out.println("!!!!!!!!!!!AccessList3:" + gas)),
                getTxnRecord("AccessList3").logged(), //TODO
                ethereumCall(callerContract.name(), "callDelegation")
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .type(EthTxData.EthTransactionType.EIP2930)
                        .withAccessList(List.of(new AccessListItem(
                                TARGET_CONTRACT_ADDRESS_BYTES.get(),
                                List.of(Bytes32.fromHexString(SLOT_KEY_0), Bytes32.fromHexString(SLOT_KEY_1)))))
                        .exposingGasTo((status, gas) -> System.out.println("!!!!!!!!!!!AccessList4:" + gas)),
                ethereumCall(callerContract.name(), "callDelegation")
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .type(EthTxData.EthTransactionType.EIP2930)
                        .withAccessList(List.of(new AccessListItem(
                                TARGET_CONTRACT_ADDRESS_BYTES.get(),
                                List.of(
                                        Bytes32.fromHexString(SLOT_KEY_0),
                                        Bytes32.fromHexString(SLOT_KEY_1),
                                        Bytes32.fromHexString(SLOT_KEY_2)))))
                        .exposingGasTo((status, gas) -> System.out.println("!!!!!!!!!!!AccessList5:" + gas)));
    }
}
