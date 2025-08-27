// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.precompile;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.*;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hedera.services.bdd.suites.regression.factories.IdFuzzingProviderFactory.*;
import static com.hedera.services.bdd.suites.utils.EvmAddressUtils.getEvmAddressFromString;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import java.math.BigInteger;
import java.util.Optional;

public class RandomERC20TransferLazyCreate implements OpProvider {
    private final HapiSpecRegistry registry;
    private static final long GAS_TO_OFFER = 5_000_000L;
    private static final String TRANSFER = "transfer";
    private static final String TRANSFER_TXN = "transferTxn";
    private final EntityNameProvider keys;

    public RandomERC20TransferLazyCreate(HapiSpecRegistry registry, EntityNameProvider keys) {
        this.registry = registry;
        this.keys = keys;
    }

    private Optional<String> randomKey() {
        return keys.getQualifying().filter(k -> !k.isEmpty());
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        return randomKey().map(this::generateLazyCreateTransferOfERC20);
    }

    private HapiSpecOperation generateLazyCreateTransferOfERC20(String evmAddressRecipient) {
        final var addressBytes = recoverAddressFromPubKey(getEvmAddressFromString(registry, evmAddressRecipient));
        return withOpContext((spec, opLog) -> {
            final var opContractCall = contractCall(
                            ERC_20_CONTRACT,
                            TRANSFER,
                            HapiParserUtil.asHeadlongAddress(asAddress(registry.getTokenID(ERC_FUNGIBLE_TOKEN))),
                            HapiParserUtil.asHeadlongAddress(addressBytes),
                            BigInteger.valueOf(2))
                    .via(TRANSFER_TXN)
                    .gas(GAS_TO_OFFER)
                    .hasKnownStatus(SUCCESS);

            final HapiGetTxnRecord hapiGetTxnRecord =
                    getTxnRecord(TRANSFER_TXN).andAllChildRecords().assertingNothingAboutHashes();

            allRunFor(spec, opContractCall, hapiGetTxnRecord);

            if (!hapiGetTxnRecord.getChildRecords().isEmpty()) {

                updateSpecFor(spec, evmAddressRecipient);
                final var opUpdate = cryptoUpdateAliased(evmAddressRecipient)
                        .maxAutomaticAssociations(5000)
                        .payingWith(GENESIS)
                        .signedBy(evmAddressRecipient, GENESIS)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(evmAddressRecipient))
                        .hasPrecheckFrom(STANDARD_PERMISSIBLE_PRECHECKS)
                        .hasKnownStatusFrom(STANDARD_PERMISSIBLE_OUTCOMES)
                        .logged();

                allRunFor(spec, opUpdate);
            }
        });
    }
}
