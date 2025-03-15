// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.precompile;

import static com.hedera.node.app.hapi.utils.EthSigsUtils.recoverAddressFromPubKey;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.regression.factories.IdFuzzingProviderFactory.*;
import static com.hedera.services.bdd.suites.utils.EvmAddressUtils.getEvmAddressFromString;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.EntityNameProvider;
import com.hedera.services.bdd.spec.infrastructure.HapiSpecRegistry;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import java.util.Optional;

public class RandomNonFungibleTransferLazyCreate implements OpProvider {
    private final HapiSpecRegistry registry;
    private static final long GAS_TO_OFFER = 5_000_000L;

    private final EntityNameProvider keys;

    public RandomNonFungibleTransferLazyCreate(HapiSpecRegistry registry, EntityNameProvider keys) {
        this.registry = registry;
        this.keys = keys;
    }

    private Optional<String> randomKey() {
        return keys.getQualifying().filter(k -> !k.isEmpty());
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        return randomKey().map(this::generateLazyCreateTransferOfNonFungibleToken);
    }

    private HapiContractCall generateLazyCreateTransferOfNonFungibleToken(String evmAddressRecipient) {
        final var addressBytes = recoverAddressFromPubKey(getEvmAddressFromString(registry, evmAddressRecipient));
        return contractCall(
                        TRANSFER_TO_ALIAS_PRECOMPILE_CONTRACT,
                        "transferNFTCallNestedThenAgain",
                        HapiParserUtil.asHeadlongAddress(asAddress(registry.getTokenID(NON_FUNGIBLE_TOKEN))),
                        HapiParserUtil.asHeadlongAddress(asAddress(registry.getAccountID(OWNER))),
                        HapiParserUtil.asHeadlongAddress(addressBytes),
                        1L,
                        2L)
                .via(TRANSFER_NFT_TXN)
                .alsoSigningWithFullPrefix(OWNER)
                .gas(GAS_TO_OFFER)
                .hasKnownStatus(SUCCESS);
    }
}
