// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.hollow;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Key;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public class RandomHollowAccount implements OpProvider {
    // Added to hollow account names to differentiate them from the keys created for them
    public static final String ACCOUNT_SUFFIX = "#";
    public static final String KEY_PREFIX = "Fuzz#";
    public static final int DEFAULT_CEILING_NUM = 100;
    public static final long INITIAL_BALANCE = 1_000_000_000L;
    public static final String LAZY_CREATE = "LAZY_CREATE";
    private int ceilingNum = DEFAULT_CEILING_NUM;
    private final HapiSpecRegistry registry;

    private final RegistrySourcedNameProvider<Key> keys;
    private final RegistrySourcedNameProvider<AccountID> accounts;

    private final AtomicLong lazyCreateNum = new AtomicLong(0L);

    public RandomHollowAccount(
            HapiSpecRegistry registry,
            RegistrySourcedNameProvider<Key> keys,
            RegistrySourcedNameProvider<AccountID> accounts) {
        this.registry = registry;
        this.keys = keys;
        this.accounts = accounts;
    }

    public RandomHollowAccount ceiling(int n) {
        ceilingNum = n;
        return this;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        // doubling ceilingNum as keys are also saved in accounts registry when account is created
        if (accounts.numPresent() >= ceilingNum * 2) {
            return Optional.empty();
        }

        return randomKey().map(this::generateHollowAccount);
    }

    private Optional<String> randomKey() {
        return keys.getQualifying()
                .filter(k -> !k.endsWith(ACCOUNT_SUFFIX))
                .filter(k -> k.startsWith(KEY_PREFIX))
                .filter(k -> !registry.hasAccountId(k + ACCOUNT_SUFFIX));
    }

    private HapiSpecOperation generateHollowAccount(String keyName) {
        return withOpContext((spec, opLog) -> {
            final var evmAddress = getEvmAddress(keyName);
            final var currentLazyCreateNum = lazyCreateNum.getAndIncrement();
            final var txnName = LAZY_CREATE + currentLazyCreateNum;
            final var op = cryptoTransfer(tinyBarsFromTo(GENESIS, evmAddress, ONE_HUNDRED_HBARS))
                    .hasKnownStatusFrom(standardOutcomesAnd(ACCOUNT_DELETED))
                    .via(txnName);

            final HapiGetTxnRecord hapiGetTxnRecord =
                    getTxnRecord(txnName).andAllChildRecords().assertingNothingAboutHashes();

            allRunFor(spec, op, hapiGetTxnRecord);

            if (!hapiGetTxnRecord.getChildRecords().isEmpty()) {
                final var newAccountID = hapiGetTxnRecord
                        .getFirstNonStakingChildRecord()
                        .getReceipt()
                        .getAccountID();
                spec.registry().saveAccountId(keyName + ACCOUNT_SUFFIX, newAccountID);
            }
        });
    }

    private ByteString getEvmAddress(String keyName) {
        final var ecdsaKey = this.registry.getKey(keyName).getECDSASecp256K1().toByteArray();
        return ByteString.copyFrom(recoverAddressFromPubKey(ecdsaKey));
    }
}
