// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.precompile;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.accountAmount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.transferList;
import static com.hedera.services.bdd.suites.regression.factories.IdFuzzingProviderFactory.*;
import static com.hedera.services.bdd.suites.utils.EvmAddressUtils.getEvmAddressFromString;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import java.util.Optional;

public class RandomHbarTransferLazyCreate implements OpProvider {
    private static final String TRANSFER_TXN = "transferTxn";

    private final HapiSpecRegistry registry;
    private static final long GAS_TO_OFFER = 5_000_000L;
    private static final Tuple[] EMPTY_TUPLE_ARRAY = new Tuple[] {};
    private final EntityNameProvider keys;

    public RandomHbarTransferLazyCreate(HapiSpecRegistry registry, EntityNameProvider keys) {
        this.registry = registry;
        this.keys = keys;
    }

    private Optional<String> randomKey() {
        return keys.getQualifying().filter(k -> !k.isEmpty());
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        return randomKey().map(this::generateHbarTransferLazyCreate);
    }

    private HapiContractCall generateHbarTransferLazyCreate(String evmAddressRecipient) {
        final var cryptoTransferV2LazyCreateFn = "cryptoTransferV2LazyCreate";
        final var sender = registry.getAccountID(SENDER);
        final var amountToBeSent = 50L;

        final var addressBytes = recoverAddressFromPubKey(getEvmAddressFromString(registry, evmAddressRecipient));
        return contractCall(
                        NESTED_LAZY_PRECOMPILE_CONTRACT,
                        cryptoTransferV2LazyCreateFn,
                        transferList()
                                .withAccountAmounts(
                                        accountAmount(sender, -amountToBeSent, false),
                                        UtilVerbs.accountAmountAlias(addressBytes, amountToBeSent, false))
                                .build(),
                        EMPTY_TUPLE_ARRAY,
                        transferList()
                                .withAccountAmounts(
                                        accountAmount(sender, -amountToBeSent, false),
                                        UtilVerbs.accountAmountAlias(addressBytes, amountToBeSent, false))
                                .build(),
                        EMPTY_TUPLE_ARRAY)
                .payingWith(UNIQUE_PAYER_ACCOUNT)
                .via(TRANSFER_TXN)
                .signedBy(UNIQUE_PAYER_ACCOUNT, MULTI_KEY)
                .alsoSigningWithFullPrefix(MULTI_KEY)
                .hasPrecheckFrom(STANDARD_PERMISSIBLE_PRECHECKS)
                .gas(GAS_TO_OFFER);
    }
}
