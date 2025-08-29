// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.SigControl.SECP256K1_ON;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilOp.flatten;
import static com.hedera.services.bdd.spec.utilops.UtilStateChange.ecAccountsValidator;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.AUTO_CREATION_KEY_NAME_FN;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHip32Auto;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHollow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludePassFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludePassWithoutBackgroundTrafficFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.visibleNonSyntheticItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withAddressOfKey;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ADDRESS_BOOK_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoUpdate;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.spec.utilops.UtilStateChange;
import com.hedera.services.bdd.spec.utilops.grouping.InBlockingOrder;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@OrderedInIsolation
public class KeyRotationDoesNotChangeEvmAddressTest {

    private static final UnaryOperator<String> ROTATION_TXN = account -> account + "KeyRotation";

    /*
      Test that long-zero EVM address has the expected value before and after
      key rotation, and that hte record stream does not imply anything different.
    */
    @Order(1)
    @HapiTest
    final Stream<DynamicTest> keyRotationDoesNotChangeEvmAddressForLongZero() {
        final var accountsToCreate = new TreeMap<UtilStateChange.ECKind, String>((Comparator.comparing(Enum::ordinal)));
        accountsToCreate.put(UtilStateChange.ECKind.LONG_ZERO, UtilStateChange.ECKind.LONG_ZERO.name());
        final var accountsToHaveKeysRotated = accountsToCreate.values().stream().toList();
        final Map<UtilStateChange.ECKind, Address> evmAddresses =
                new HashMap<>(); // Collect addresses of created accounts here
        return hapiTest(flatten(
                cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, 1)),

                // Validate (after all ops executed) that the keeys were rotated
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        visibleNonSyntheticItems(
                                keyRotationsValidator(accountsToHaveKeysRotated),
                                rotateAndCalculateAllTxnIds(accountsToHaveKeysRotated)),
                        Duration.ofSeconds(15)),

                // Validate (after all ops executed) that our accounts did get created
                recordStreamMustIncludePassFrom(
                                visibleNonSyntheticItems(
                                        ecAccountsValidator(evmAddresses, accountsToCreate),
                                        rotateAndCalculateAllTxnIds(accountsToHaveKeysRotated)),
                                Duration.ofSeconds(15))
                        .stopAfterFirstSuccess(),

                // If the FileAlterationObserver just started the monitor, there's a chance we could miss the
                // first couple of creations, so wait for a new record file boundary
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),

                // Create the account with a long-zero EVM address
                cryptoCreate(accountsToCreate.get(UtilStateChange.ECKind.LONG_ZERO))
                        .via(accountsToCreate.get(UtilStateChange.ECKind.LONG_ZERO))
                        .keyShape(SECP256K1_ON)
                        .exposingEvmAddressTo(address -> evmAddresses.put(UtilStateChange.ECKind.LONG_ZERO, address)),

                // Save all EVM addresses
                saveAllEvmAddresses(evmAddresses, accountsToCreate),
                rotateKeys(accountsToHaveKeysRotated),

                // --- ROTATE KEYS ---
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted)));
    }

    /*
      Test that auto and hollow EVM address has the expected value before and after
      key rotation, and that hte record stream does not imply anything different.
    */
    @Order(2)
    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> keyRotationDoesNotChangeEvmAddressForAutoAndHollow() {
        final var accountsToCreate = new TreeMap<UtilStateChange.ECKind, String>((Comparator.comparing(Enum::ordinal)));
        accountsToCreate.put(
                UtilStateChange.ECKind.AUTO, UtilStateChange.ECKind.AUTO.name().toLowerCase());
        accountsToCreate.put(
                UtilStateChange.ECKind.HOLLOW,
                UtilStateChange.ECKind.HOLLOW.name().toLowerCase());
        final var accountsToHaveKeysRotated = accountsToCreate.values().stream().toList();
        final Map<UtilStateChange.ECKind, Address> evmAddresses = new HashMap<>();
        return hapiTest(flatten(
                cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, 1)),

                // Validate (after all ops executed) that the keeys were rotated
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        visibleNonSyntheticItems(
                                keyRotationsValidator(accountsToHaveKeysRotated),
                                rotateAndCalculateAllTxnIds(accountsToHaveKeysRotated)),
                        Duration.ofSeconds(15)),

                // Validate (after all ops executed) that our accounts did get created
                recordStreamMustIncludePassFrom(
                                visibleNonSyntheticItems(
                                        ecAccountsValidator(evmAddresses, accountsToCreate),
                                        rotateAndCalculateAllTxnIds(accountsToHaveKeysRotated)),
                                Duration.ofSeconds(15))
                        .stopAfterFirstSuccess(),

                // If the FileAlterationObserver just started the monitor, there's a chance we could miss the
                // first couple of creations, so wait for a new record file boundary
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),

                // Auto-create an account with an ECDSA key alias
                createHip32Auto(1, KeyShape.SECP256K1, i -> accountsToCreate.get(UtilStateChange.ECKind.AUTO)),
                withAddressOfKey(accountsToCreate.get(UtilStateChange.ECKind.AUTO), evmAddress -> {
                    evmAddresses.put(UtilStateChange.ECKind.AUTO, evmAddress);
                    return withOpContext((spec, opLog) -> spec.registry()
                            .saveTxnId(
                                    accountsToCreate.get(UtilStateChange.ECKind.AUTO),
                                    spec.registry().getTxnId("hip32" + AUTO_CREATION_KEY_NAME_FN.apply(0))));
                }),

                // Create a hollow account and complete it
                createHollow(
                        1,
                        i -> accountsToCreate.get(UtilStateChange.ECKind.HOLLOW),
                        evmAddress -> cryptoTransfer(tinyBarsFromTo(GENESIS, evmAddress, ONE_HUNDRED_HBARS))),
                withAddressOfKey(accountsToCreate.get(UtilStateChange.ECKind.HOLLOW), evmAddress -> {
                    evmAddresses.put(UtilStateChange.ECKind.HOLLOW, evmAddress);
                    return withOpContext((spec, opLog) -> {
                        spec.registry()
                                .saveTxnId(
                                        accountsToCreate.get(UtilStateChange.ECKind.HOLLOW),
                                        spec.registry()
                                                .getTxnId(accountsToCreate.get(UtilStateChange.ECKind.AUTO)
                                                        + "Create" /*from UtilVerbs.createHollow*/ + evmAddress));
                    });
                }),
                cryptoTransfer(tinyBarsFromTo(accountsToCreate.get(UtilStateChange.ECKind.HOLLOW), FUNDING, 1))
                        .payingWith(accountsToCreate.get(UtilStateChange.ECKind.HOLLOW))
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(accountsToCreate.get(UtilStateChange.ECKind.HOLLOW))),
                saveAllEvmAddresses(evmAddresses, accountsToCreate),
                rotateKeys(accountsToHaveKeysRotated),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted)));
    }

    /*
    Test that explicit alias EVM address has the expected value before and after
    key rotation, and that hte record stream does not imply anything different.
     */
    @Order(3)
    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> keyRotationDoesNotChangeEvmAddressForExplicitAlias() {
        final var accountsToCreate = new TreeMap<UtilStateChange.ECKind, String>((Comparator.comparing(Enum::ordinal)));
        accountsToCreate.put(UtilStateChange.ECKind.EXPLICIT_ALIAS, UtilStateChange.ECKind.EXPLICIT_ALIAS.name());
        final var accountsToHaveKeysRotated = accountsToCreate.values().stream().toList();
        final Map<UtilStateChange.ECKind, Address> evmAddresses =
                new HashMap<>(); // Collect addresses of created accounts here
        return hapiTest(flatten(
                cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, 1)),

                // Validate (after all ops executed) that the keeys were rotated
                recordStreamMustIncludePassWithoutBackgroundTrafficFrom(
                        visibleNonSyntheticItems(
                                keyRotationsValidator(accountsToHaveKeysRotated),
                                rotateAndCalculateAllTxnIds(accountsToHaveKeysRotated)),
                        Duration.ofSeconds(15)),

                // Validate (after all ops executed) that our accounts did get created
                recordStreamMustIncludePassFrom(
                                visibleNonSyntheticItems(
                                        ecAccountsValidator(evmAddresses, accountsToCreate),
                                        rotateAndCalculateAllTxnIds(accountsToHaveKeysRotated)),
                                Duration.ofSeconds(15))
                        .stopAfterFirstSuccess(),

                // If the FileAlterationObserver just started the monitor, there's a chance we could miss the
                // first couple of creations, so wait for a new record file boundary
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),

                // Create an account with an explicit EVM address
                newKeyNamed(accountsToCreate.get(UtilStateChange.ECKind.EXPLICIT_ALIAS))
                        .shape(KeyShape.SECP256K1),
                withAddressOfKey(accountsToCreate.get(UtilStateChange.ECKind.EXPLICIT_ALIAS), evmAddress -> {
                    evmAddresses.put(UtilStateChange.ECKind.EXPLICIT_ALIAS, evmAddress);
                    return cryptoCreate(accountsToCreate.get(UtilStateChange.ECKind.EXPLICIT_ALIAS))
                            .key(accountsToCreate.get(UtilStateChange.ECKind.EXPLICIT_ALIAS))
                            .evmAddress(evmAddress)
                            .via(accountsToCreate.get(UtilStateChange.ECKind.EXPLICIT_ALIAS));
                }),
                saveAllEvmAddresses(evmAddresses, accountsToCreate),
                rotateKeys(accountsToHaveKeysRotated),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted)));
    }

    private static VisibleItemsValidator keyRotationsValidator(@NonNull final List<String> accountsToHaveKeysRotated) {
        return (spec, records) -> {
            final var rotationTxnIds =
                    accountsToHaveKeysRotated.stream().map(ROTATION_TXN).toArray(String[]::new);
            for (final var txnId : rotationTxnIds) {
                final var successItems = requireNonNull(records.get(txnId), txnId + " not found");
                final var updateEntry = successItems.entries().stream()
                        .filter(entry -> entry.function() == CryptoUpdate)
                        .findFirst()
                        .orElseThrow();
                assertEquals(0, updateEntry.txnRecord().getEvmAddress().size(), "for txnId " + txnId);
            }
        };
    }

    private InBlockingOrder rotateKeys(List<String> accountsToHaveKeysRotated) {
        return blockingOrder(IntStream.range(0, accountsToHaveKeysRotated.size())
                .mapToObj(i -> {
                    final var newKey = "replKey" + i;
                    final var targetAccount = accountsToHaveKeysRotated.get(i);
                    return blockingOrder(
                            newKeyNamed(newKey).shape(KeyShape.SECP256K1),
                            cryptoUpdate(targetAccount).key(newKey).via(ROTATION_TXN.apply(targetAccount)));
                })
                .toArray(SpecOperation[]::new));
    }

    private CustomSpecAssert saveAllEvmAddresses(
            Map<UtilStateChange.ECKind, Address> evmAddresses, Map<UtilStateChange.ECKind, String> accountsToCreate) {
        return withOpContext((spec, opLog) -> {
            for (final var e : evmAddresses.entrySet()) {
                spec.registry()
                        .saveEVMAddress(
                                accountsToCreate.get(e.getKey()),
                                e.getValue().value().toString(16));
            }
        });
    }

    private String[] rotateAndCalculateAllTxnIds(List<String> accountsToHaveKeysRotated) {
        final var allTxnIds = Stream.concat(
                        accountsToHaveKeysRotated.stream(),
                        accountsToHaveKeysRotated.stream().map(ROTATION_TXN))
                .toArray(String[]::new);
        return allTxnIds;
    }
}
