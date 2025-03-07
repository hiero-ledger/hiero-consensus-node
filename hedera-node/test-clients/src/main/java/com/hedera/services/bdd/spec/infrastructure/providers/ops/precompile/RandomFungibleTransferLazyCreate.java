// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.precompile;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdateAliased;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hedera.services.bdd.suites.regression.factories.IdFuzzingProviderFactory.*;
import static com.hedera.services.bdd.suites.utils.EvmAddressUtils.getEvmAddressFromString;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import java.util.Optional;

public class RandomFungibleTransferLazyCreate implements OpProvider {
    private final HapiSpecRegistry registry;
    private static final long GAS_TO_OFFER = 5_000_000L;

    private final EntityNameProvider keys;

    public RandomFungibleTransferLazyCreate(HapiSpecRegistry registry, EntityNameProvider keys) {
        this.registry = registry;
        this.keys = keys;
    }

    private Optional<String> randomKey() {
        return keys.getQualifying().filter(k -> !k.isEmpty());
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        return randomKey().map(this::generateLazyCreateTransferOfFungibleToken);
    }

    private HapiSpecOperation generateLazyCreateTransferOfFungibleToken(String evmAddressRecipient) {
        final var addressBytes = recoverAddressFromPubKey(getEvmAddressFromString(registry, evmAddressRecipient));

        return withOpContext((spec, opLog) -> {
            final var opContractCall = contractCall(
                            TRANSFER_TO_ALIAS_PRECOMPILE_CONTRACT,
                            "transferTokenCallNestedThenAgain",
                            HapiParserUtil.asHeadlongAddress(asAddress(registry.getTokenID(FUNGIBLE_TOKEN))),
                            HapiParserUtil.asHeadlongAddress(asAddress(registry.getAccountID(OWNER))),
                            HapiParserUtil.asHeadlongAddress(addressBytes),
                            2L,
                            2L)
                    .via(TRANSFER_TOKEN_TXN)
                    .alsoSigningWithFullPrefix(OWNER)
                    .gas(GAS_TO_OFFER)
                    .hasKnownStatus(SUCCESS);

            final HapiGetTxnRecord hapiGetTxnRecord =
                    getTxnRecord(TRANSFER_TOKEN_TXN).andAllChildRecords().assertingNothingAboutHashes();

            allRunFor(spec, opContractCall, hapiGetTxnRecord);

            if (!hapiGetTxnRecord.getChildRecords().isEmpty()) {
                updateSpecFor(spec, evmAddressRecipient);
                final var opUpdate = cryptoUpdateAliased(evmAddressRecipient)
                        .maxAutomaticAssociations(5000)
                        .payingWith(GENESIS)
                        .signedBy(evmAddressRecipient, GENESIS)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(evmAddressRecipient))
                        .hasPrecheckFrom(OK)
                        .hasKnownStatusFrom(SUCCESS, INVALID_SIGNATURE)
                        .logged();

                allRunFor(spec, opUpdate);
            }
        });
    }
}
