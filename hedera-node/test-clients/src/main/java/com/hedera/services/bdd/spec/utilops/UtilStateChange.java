// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.asHeadlongAddress;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.explicitFromHeadlong;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_RECEIVER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_SENDER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public static @NonNull VisibleItemsValidator ecAccountsValidator(
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
