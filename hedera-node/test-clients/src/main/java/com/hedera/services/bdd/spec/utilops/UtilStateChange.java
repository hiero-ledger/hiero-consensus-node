// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asHeadlongAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.explicitFromHeadlong;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.keys.SigControl.SECP256K1_ON;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.AUTO_CREATION_KEY_NAME_FN;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHip32Auto;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHollow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludePassFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.visibleNonSyntheticItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withAddressOfKey;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_RECEIVER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_SENDER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_RECEIVER_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoCreate;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.assertions.StateChange;
import com.hedera.services.bdd.spec.assertions.StorageChange;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.utilops.inventory.NewSpecKey;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import com.hedera.services.stream.proto.ContractStateChange;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class UtilStateChange {
    static final Logger log = LogManager.getLogger(UtilStateChange.class);

    public static final KeyShape secp256k1Shape = KeyShape.SECP256K1;
    private static final Map<String, Boolean> specToInitializedEthereumAccount = new HashMap<>();

    public static List<ContractStateChange> stateChangesToGrpc(List<StateChange> stateChanges, HapiSpec spec) {
        final List<ContractStateChange> additions = new ArrayList<>();

        for (StateChange stateChange : stateChanges) {
            final var addition = ContractStateChange.newBuilder()
                    .setContractId(TxnUtils.asContractId(stateChange.getContractID(), spec));

            for (StorageChange storageChange : stateChange.getStorageChanges()) {
                var newStorageChange = com.hedera.services.stream.proto.StorageChange.newBuilder()
                        .setSlot(storageChange.getSlot())
                        .setValueRead(storageChange.getValueRead());

                if (storageChange.getValueWritten() != null
                        && (!storageChange.getValueRead().isEmpty()
                                || !storageChange.getValueWritten().getValue().isEmpty())) {
                    newStorageChange.setValueWritten(storageChange.getValueWritten());
                }

                addition.addStorageChanges(newStorageChange.build());
            }

            additions.add(addition.build());
        }

        return additions;
    }

    public static List<SpecOperation> createEthereumAccountForSpec(final HapiSpec spec) {
        final var acc1 = createEthereumAccount(SECP_256K1_SOURCE_KEY, DEFAULT_CONTRACT_SENDER);
        final var acc2 = createEthereumAccount(SECP_256K1_RECEIVER_SOURCE_KEY, DEFAULT_CONTRACT_RECEIVER);
        specToInitializedEthereumAccount.putIfAbsent(spec.getSuitePrefix() + spec.getName(), true);
        return Stream.concat(acc1.stream(), acc2.stream()).toList();
    }

    /**
     * The four different kinds of EC-addressed accounts
     */
    public enum ECKind {
        LONG_ZERO,
        AUTO,
        HOLLOW,
        EXPLICIT_ALIAS;

        /**
         * Default names for each of the EC-addressed accounts
         */
        public static @NonNull Map<ECKind, String> defaultAccountNames() {
            final var r = new HashMap<ECKind, String>();
            for (final var e : ECKind.values()) {
                r.put(e, e.name().toLowerCase());
            }
            return r;
        }
    }

    /**
     * Create (and register) 4 accounts with EC addresses - one each of the four different ways
     * such accounts can be created: long-zero (with explicit EC key set), auto (HIP-32), hollow
     * aka lazy (HIP-583), explicit alias provided (HIP-583).  Accounts and keys (with same name
     * as accounts) are all registered.
     *
     * @param accountNamesByKind map of account kind to account name, for those accounts where
     *                           caller wants to specify a name to override the default from {@link ECKind#defaultAccountNames()}}
     * @return HAPI {@link com.hedera.services.bdd.spec.SpecOperation}s that create the accounts
     * and assert their creation happened
     */
    public static @NonNull List<SpecOperation> createEthereumAccountsWithECKeysAllDifferentWays(
            final @NonNull Map<ECKind, String> accountNamesByKind) {

        final var accountsToCreate = new TreeMap<ECKind, String>((Comparator.comparing(Enum::ordinal))) {
            {
                putAll(accountNamesByKind);
            }
        };
        final var accountCreationTxnIds = accountsToCreate.values().toArray(new String[0]);

        final Map<ECKind, Address> evmAddresses = new HashMap<>(); // Collect addresses of created accounts here

        final var ops = List.of(

                // Validate (after all ops executed) that our accounts did get created
                recordStreamMustIncludePassFrom(
                        visibleNonSyntheticItems(
                                ecAccountsValidator(evmAddresses, accountsToCreate), accountCreationTxnIds),
                        Duration.ofSeconds(15)),

                // Create the account with a long-zero EVM address
                cryptoCreate(accountsToCreate.get(ECKind.LONG_ZERO))
                        .via(accountsToCreate.get(ECKind.LONG_ZERO))
                        .keyShape(SECP256K1_ON)
                        .exposingEvmAddressTo(address -> evmAddresses.put(ECKind.LONG_ZERO, address)),

                // Auto-create an account with an ECDSA key alias
                createHip32Auto(1, KeyShape.SECP256K1, i -> accountsToCreate.get(ECKind.AUTO)),
                withAddressOfKey(accountsToCreate.get(ECKind.AUTO), evmAddress -> {
                    evmAddresses.put(ECKind.AUTO, evmAddress);
                    return withOpContext((spec, opLog) -> spec.registry()
                            .saveTxnId(
                                    accountsToCreate.get(ECKind.AUTO),
                                    spec.registry().getTxnId("hip32" + AUTO_CREATION_KEY_NAME_FN.apply(0))));
                }),

                // Create a hollow account and complete it
                createHollow(
                        1,
                        i -> accountsToCreate.get(ECKind.HOLLOW),
                        evmAddress -> cryptoTransfer(tinyBarsFromTo(GENESIS, evmAddress, ONE_HUNDRED_HBARS))),
                withAddressOfKey(accountsToCreate.get(ECKind.HOLLOW), evmAddress -> {
                    evmAddresses.put(ECKind.HOLLOW, evmAddress);
                    return withOpContext((spec, opLog) -> {
                        spec.registry()
                                .saveTxnId(
                                        accountsToCreate.get(ECKind.HOLLOW),
                                        spec.registry()
                                                .getTxnId(accountsToCreate.get(ECKind.AUTO)
                                                        + "Create" /*from UtilVerbs.createHollow*/ + evmAddress));
                    });
                }),
                cryptoTransfer(tinyBarsFromTo(accountsToCreate.get(ECKind.HOLLOW), FUNDING, 1))
                        .payingWith(accountsToCreate.get(ECKind.HOLLOW))
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(accountsToCreate.get(ECKind.HOLLOW))),

                // Create an account with an explicit EVM address
                newKeyNamed(accountsToCreate.get(ECKind.EXPLICIT_ALIAS)).shape(KeyShape.SECP256K1),
                withAddressOfKey(accountsToCreate.get(ECKind.EXPLICIT_ALIAS), evmAddress -> {
                    evmAddresses.put(ECKind.EXPLICIT_ALIAS, evmAddress);
                    return cryptoCreate(accountsToCreate.get(ECKind.EXPLICIT_ALIAS))
                            .key(accountsToCreate.get(ECKind.EXPLICIT_ALIAS))
                            .evmAddress(evmAddress)
                            .via(accountsToCreate.get(ECKind.EXPLICIT_ALIAS));
                }),
                withOpContext((spec, opLog) -> {
                    for (final var e : evmAddresses.entrySet()) {
                        spec.registry()
                                .saveEVMAddress(
                                        accountsToCreate.get(e.getKey()),
                                        e.getValue().value().toString(16));
                    }
                }));

        return ops;
    }

    /**
     * Like {@link #createEthereumAccountsWithECKeysAllDifferentWays(Map)} but with all defaulted account names.
     */
    public static @NonNull List<SpecOperation> createEthereumAccountsWithECKeysAllDifferentWays() {
        final var defaultAcctNamesByKind = ECKind.defaultAccountNames().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return createEthereumAccountsWithECKeysAllDifferentWays(defaultAcctNamesByKind);
    }

    private static @NonNull VisibleItemsValidator ecAccountsValidator(
            @NonNull Map<ECKind, Address> evmAddresses, @NonNull Map<ECKind, String> accountNamesByKind) {
        return (spec, records) -> {
            for (final var e : accountNamesByKind.entrySet()) {
                final var txnKind = e.getKey();
                final var txnId = e.getValue();
                final var successItems = requireNonNull(records.get(txnId), txnId + " not found");
                final var creationEntry = successItems.entries().stream()
                        .filter(entry -> entry.function() == CryptoCreate)
                        .findFirst()
                        .orElseThrow();
                final var recordEvmAddress = creationEntry.transactionRecord().getEvmAddress();
                final var bodyEvmAddress =
                        creationEntry.body().getCryptoCreateAccount().getAlias();
                final var numEvmAddresses =
                        ((recordEvmAddress.size() == 20) ? 1 : 0) + ((bodyEvmAddress.size() == 20) ? 1 : 0);
                assertTrue(numEvmAddresses <= 1);
                final var evmAddress = numEvmAddresses == 0
                        ? headlongAddressOf(creationEntry.createdAccountId())
                        : asHeadlongAddress(
                                (recordEvmAddress.size() == 20)
                                        ? recordEvmAddress.toByteArray()
                                        : bodyEvmAddress.toByteArray());
                assertEquals(evmAddresses.get(txnKind), evmAddress);
                allRunFor(
                        spec,
                        getAccountInfo(String.format(
                                        "%d.%d.%d",
                                        creationEntry.createdAccountId().shardNum(),
                                        creationEntry.createdAccountId().realmNum(),
                                        creationEntry.createdAccountId().accountNum()))
                                .has(accountWith().evmAddress(ByteString.copyFrom(explicitFromHeadlong(evmAddress)))));
            }
        };
    }

    private static List<SpecOperation> createEthereumAccount(final String secp256k1Key, final String txnName) {
        final var newSpecKey = new NewSpecKey(secp256k1Key).shape(secp256k1Shape);
        final var cryptoTransfer = new HapiCryptoTransfer(
                        tinyBarsFromAccountToAlias(GENESIS, secp256k1Key, 20 * ONE_MILLION_HBARS))
                .via(txnName)
                .payingWith(GENESIS);
        return List.of(newSpecKey, cryptoTransfer);
    }

    public static boolean isEthereumAccountCreatedForSpec(final HapiSpec spec) {
        return specToInitializedEthereumAccount.containsKey(spec.getSuitePrefix() + spec.getName());
    }
}
