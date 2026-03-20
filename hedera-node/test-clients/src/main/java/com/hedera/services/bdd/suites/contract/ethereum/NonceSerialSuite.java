// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.ethereum;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_GAS_LIMIT_EXCEEDED;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@OrderedInIsolation
public class NonceSerialSuite {

    private static final String INTERNAL_CALLEE_CONTRACT = "InternalCallee";
    private static final String EXTERNAL_FUNCTION = "externalFunction";

    @LeakyHapiTest
    final Stream<DynamicTest> nonceNotUpdatedWhenMaxGasPerSecPrecheckFailed() {
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                contractCreate(INTERNAL_CALLEE_CONTRACT),
                sourcing(() -> ethereumCall(INTERNAL_CALLEE_CONTRACT, EXTERNAL_FUNCTION)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .gasLimit(Long.MAX_VALUE)
                        .hasPrecheckFrom(MAX_GAS_LIMIT_EXCEEDED, BUSY)),
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)));
    }
}
