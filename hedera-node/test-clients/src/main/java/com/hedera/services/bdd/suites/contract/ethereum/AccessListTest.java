package com.hedera.services.bdd.suites.contract.ethereum;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.node.app.hapi.utils.ethereum.AccessListItem;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.transactions.contract.HapiEthereumCall;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.genRandomBytes;

@Tag(SMART_CONTRACT)
@HapiTestLifecycle
public class AccessListTest {

    @Contract(contract = "AccessListCallerContract")
    static SpecContract callerContract;
    private static final AtomicReference<Address> callerContractAddress = new AtomicReference<>();

    @Contract(contract = "AccessListTargetContract")
    static SpecContract targetContract;
    private static final AtomicReference<Address> targetContractAddress = new AtomicReference<>();

    @BeforeAll
    public static void setup(final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                callerContract.getInfo()
                        .andAssert(e -> e.exposingEvmAddress(address ->
                                callerContractAddress.set(asHeadlongAddress(address)))),
                targetContract.getInfo()
                        .andAssert(e -> e.exposingEvmAddress(address ->
                                targetContractAddress.set(asHeadlongAddress(address))))
        );
    }

    @HapiTest
    final Stream<DynamicTest> accessListIntrinsicGasTest() {
        final AtomicLong legacyGas = new AtomicLong();
        return hapiTest(
                // prepare sender
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                // LEGACY_ETHEREUM call
                ethereumCall(callerContract.name(), "call", targetContractAddress.get())
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .type(EthTxData.EthTransactionType.LEGACY_ETHEREUM)
                        .exposingGasTo((status, gas) -> legacyGas.set(gas)),
                // EIP1559 call with accessLists
                ethereumCallWithAccessList(EthTxData.EthTransactionType.EIP2930, legacyGas, List.of(0)),
                ethereumCallWithAccessList(EthTxData.EthTransactionType.EIP2930, legacyGas, List.of(1)),
                ethereumCallWithAccessList(EthTxData.EthTransactionType.EIP2930, legacyGas, List.of(2, 3, 4)),
                // EIP1559 call with accessLists
                ethereumCallWithAccessList(EthTxData.EthTransactionType.EIP1559, legacyGas, List.of(0)),
                ethereumCallWithAccessList(EthTxData.EthTransactionType.EIP1559, legacyGas, List.of(1)),
                ethereumCallWithAccessList(EthTxData.EthTransactionType.EIP1559, legacyGas, List.of(2, 3, 4))
        );
    }

    private static HapiEthereumCall ethereumCallWithAccessList(final EthTxData.EthTransactionType type, final AtomicLong legacyGas,
                                                               final List<Integer> storageKeysCount) {
        // generate access list
        final List<AccessListItem> accessLists = new ArrayList<>();
        for (final Integer count : storageKeysCount) {
            accessLists.add(new AccessListItem(
                    Bytes.wrap(genRandomBytes(20)),
                    IntStream.range(0, count)
                            .mapToObj(e -> Bytes32.wrap(genRandomBytes(32)))
                            .toList()));
        }
        // execute ethereumCall
        return ethereumCall(callerContract.name(), "call", targetContractAddress.get())
                .signingWith(SECP_256K1_SOURCE_KEY)
                .type(type)
                .withAccessList(accessLists)
                .exposingGasTo((status, gas) -> Assertions.assertEquals(
                        legacyGas.get()
                        + 2_400L * storageKeysCount.size()
                        + 1_900L * storageKeysCount.stream().mapToInt(e -> e).sum(), gas));
    }
}
