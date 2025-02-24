// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.lambda;

import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.CommonTransferAllowanceSwaps.swapWithSenderAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.lambda.HookInstaller.lambdaBytecode;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.hedera.hapi.node.base.EvmHookCall;
import com.hedera.hapi.node.base.HookCall;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.dsl.annotations.NonFungibleToken;
import com.hedera.services.bdd.spec.dsl.entities.SpecNonFungibleToken;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(TOKEN)
public class HipExamplesTest {
    @HapiTest
    final Stream<DynamicTest> canUpdateExpiryOnlyOpWithoutAdminKey(
            @NonFungibleToken(numPreMints = 1) SpecNonFungibleToken cleverCoin) {
        final long index = 123L;
        return hapiTest(
                cryptoCreate("sphinx")
                        .maxAutomaticTokenAssociations(1)
                        .installing(lambdaBytecode().atIndex(index)),
                cryptoCreate("traveler").balance(ONE_HUNDRED_HBARS),
                cleverCoin.doWith(token -> cryptoTransfer(movingUnique(cleverCoin.name(), 1L)
                        .between(cleverCoin.treasury().name(), "sphinx"))),
                cryptoTransfer(swapWithSenderAllowanceHook(
                                "sphinx",
                                "traveler",
                                cleverCoin.name(),
                                1L,
                                HookCall.newBuilder()
                                        .index(index)
                                        .evmHookCall(EvmHookCall.newBuilder()
                                                .evmCallData(Bytes.EMPTY)
                                                .build())
                                        .build()))
                        .payingWith("traveler"));
    }
}
