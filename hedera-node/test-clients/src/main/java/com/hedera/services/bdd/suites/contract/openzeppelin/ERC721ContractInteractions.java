// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.openzeppelin;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_RECEIVER;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_CONTRACT_SENDER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_RECEIVER_SOURCE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
public class ERC721ContractInteractions {
    @HapiTest
    final Stream<DynamicTest> callsERC721ContractInteractions() {
        final var CONTRACT = "GameItem";
        final var nftId = BigInteger.ONE;
        final var CREATE_TX = "create";
        final var MINT_TX = "mint";
        final var APPROVE_TX = "approve";
        final var TRANSFER_FROM_TX = "transferFrom";

        return hapiTest(
                QueryVerbs.getAccountBalance(DEFAULT_CONTRACT_SENDER).logged(),
                uploadInitCode(CONTRACT),
                QueryVerbs.getAccountBalance(DEFAULT_CONTRACT_SENDER),
                contractCreate(CONTRACT)
                        .payingWith(DEFAULT_CONTRACT_SENDER)
                        .hasKnownStatus(SUCCESS)
                        .gas(2_500_000L)
                        .via(CREATE_TX),
                cryptoTransfer(tinyBarsFromTo(DEFAULT_CONTRACT_SENDER, DEFAULT_CONTRACT_RECEIVER, 10 * ONE_HBAR))
                        .payingWith(DEFAULT_CONTRACT_SENDER),
                QueryVerbs.getAccountBalance(DEFAULT_CONTRACT_RECEIVER),
                QueryVerbs.getAccountInfo(DEFAULT_CONTRACT_SENDER).savingSnapshot(DEFAULT_CONTRACT_SENDER),
                QueryVerbs.getAccountInfo(DEFAULT_CONTRACT_RECEIVER).savingSnapshot(DEFAULT_CONTRACT_RECEIVER),
                withOpContext((spec, log) -> {
                    final var contractCreatorId = spec.registry()
                            .getAccountInfo(DEFAULT_CONTRACT_SENDER)
                            .getContractAccountID();
                    final var nftSenderId = spec.registry()
                            .getAccountInfo(DEFAULT_CONTRACT_RECEIVER)
                            .getContractAccountID();

                    final var mintParams = new Object[] {asHeadlongAddress(nftSenderId), nftId};
                    final var approveParams = new Object[] {asHeadlongAddress(contractCreatorId), nftId};
                    final var transferFromParams =
                            new Object[] {asHeadlongAddress(nftSenderId), asHeadlongAddress(contractCreatorId), nftId};

                    final var mint = contractCall(CONTRACT, "mint", mintParams)
                            .payingWith(DEFAULT_CONTRACT_SENDER)
                            .via(MINT_TX);
                    allRunFor(spec, mint);

                    final var approve = contractCall(CONTRACT, "approve", approveParams)
                            .payingWith(DEFAULT_CONTRACT_RECEIVER)
                            .signingWith(SECP_256K1_RECEIVER_SOURCE_KEY)
                            .gas(4_000_000L)
                            .via(APPROVE_TX);
                    allRunFor(spec, approve);

                    final var transferFrom = contractCall(CONTRACT, "transferFrom", transferFromParams)
                            .payingWith(DEFAULT_CONTRACT_SENDER)
                            .via(TRANSFER_FROM_TX);
                    allRunFor(spec, transferFrom);
                }),
                QueryVerbs.getTxnRecord(CREATE_TX).logged(),
                QueryVerbs.getTxnRecord(MINT_TX).logged(),
                QueryVerbs.getTxnRecord(APPROVE_TX).logged(),
                QueryVerbs.getTxnRecord(TRANSFER_FROM_TX).logged());
    }
}
