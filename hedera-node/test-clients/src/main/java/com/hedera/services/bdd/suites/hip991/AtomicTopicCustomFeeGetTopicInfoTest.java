// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip991;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.expectedConsensusFixedHTSFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeTests.expectedConsensusFixedHbarFee;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

// This test cases are direct copies of TopicCustomFeeGetTopicInfoTest. The difference here is that
// we are wrapping the operations in an atomic batch to confirm the fees are the same
@HapiTestLifecycle
@DisplayName("Topic get info")
public class AtomicTopicCustomFeeGetTopicInfoTest extends TopicCustomFeeBase {

    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(setupBaseKeys());
        lifecycle.overrideInClass(Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    @HapiTest
    @DisplayName("with 10 custom fees")
    final Stream<DynamicTest> getInfoWith10CustomFees() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(COLLECTOR),
                tokenCreate("testToken1").tokenType(TokenType.FUNGIBLE_COMMON).initialSupply(500),
                tokenCreate("testToken2").tokenType(TokenType.FUNGIBLE_COMMON).initialSupply(500),
                tokenCreate("testToken3").tokenType(TokenType.FUNGIBLE_COMMON).initialSupply(500),
                tokenCreate("testToken4").tokenType(TokenType.FUNGIBLE_COMMON).initialSupply(500),
                tokenCreate("testToken5").tokenType(TokenType.FUNGIBLE_COMMON).initialSupply(500),
                tokenCreate("testToken6").tokenType(TokenType.FUNGIBLE_COMMON).initialSupply(500),
                tokenCreate("testToken7").tokenType(TokenType.FUNGIBLE_COMMON).initialSupply(500),
                tokenCreate("testToken8").tokenType(TokenType.FUNGIBLE_COMMON).initialSupply(500),
                tokenCreate("testToken9").tokenType(TokenType.FUNGIBLE_COMMON).initialSupply(500),
                tokenCreate("testToken10").tokenType(TokenType.FUNGIBLE_COMMON).initialSupply(500),
                tokenAssociate(COLLECTOR, "testToken1"),
                tokenAssociate(COLLECTOR, "testToken2"),
                tokenAssociate(COLLECTOR, "testToken3"),
                tokenAssociate(COLLECTOR, "testToken4"),
                tokenAssociate(COLLECTOR, "testToken5"),
                tokenAssociate(COLLECTOR, "testToken6"),
                tokenAssociate(COLLECTOR, "testToken7"),
                tokenAssociate(COLLECTOR, "testToken8"),
                tokenAssociate(COLLECTOR, "testToken9"),
                tokenAssociate(COLLECTOR, "testToken10"),
                atomicBatch(createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .submitKeyName(SUBMIT_KEY)
                                .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                .withConsensusCustomFee(fixedConsensusHtsFee(1, "testToken1", COLLECTOR))
                                .withConsensusCustomFee(fixedConsensusHtsFee(2, "testToken2", COLLECTOR))
                                .withConsensusCustomFee(fixedConsensusHtsFee(3, "testToken3", COLLECTOR))
                                .withConsensusCustomFee(fixedConsensusHtsFee(4, "testToken4", COLLECTOR))
                                .withConsensusCustomFee(fixedConsensusHtsFee(5, "testToken5", COLLECTOR))
                                .withConsensusCustomFee(fixedConsensusHtsFee(6, "testToken6", COLLECTOR))
                                .withConsensusCustomFee(fixedConsensusHtsFee(7, "testToken7", COLLECTOR))
                                .withConsensusCustomFee(fixedConsensusHtsFee(8, "testToken8", COLLECTOR))
                                .withConsensusCustomFee(fixedConsensusHtsFee(9, "testToken9", COLLECTOR))
                                .withConsensusCustomFee(fixedConsensusHtsFee(10, "testToken10", COLLECTOR))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTopicInfo(TOPIC)
                        .hasAdminKey(ADMIN_KEY)
                        .hasSubmitKey(SUBMIT_KEY)
                        .hasFeeScheduleKey(FEE_SCHEDULE_KEY)
                        .hasCustomFee(expectedConsensusFixedHTSFee(1, "testToken1", COLLECTOR))
                        .hasCustomFee(expectedConsensusFixedHTSFee(2, "testToken2", COLLECTOR))
                        .hasCustomFee(expectedConsensusFixedHTSFee(3, "testToken3", COLLECTOR))
                        .hasCustomFee(expectedConsensusFixedHTSFee(4, "testToken4", COLLECTOR))
                        .hasCustomFee(expectedConsensusFixedHTSFee(5, "testToken5", COLLECTOR))
                        .hasCustomFee(expectedConsensusFixedHTSFee(6, "testToken6", COLLECTOR))
                        .hasCustomFee(expectedConsensusFixedHTSFee(7, "testToken7", COLLECTOR))
                        .hasCustomFee(expectedConsensusFixedHTSFee(8, "testToken8", COLLECTOR))
                        .hasCustomFee(expectedConsensusFixedHTSFee(9, "testToken9", COLLECTOR))
                        .hasCustomFee(expectedConsensusFixedHTSFee(10, "testToken10", COLLECTOR)));
    }

    @HapiTest
    @DisplayName("with FT and HBAR")
    final Stream<DynamicTest> withFTAndHbar() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(COLLECTOR),
                tokenCreate("testToken").tokenType(TokenType.FUNGIBLE_COMMON).initialSupply(500),
                tokenAssociate(COLLECTOR, "testToken"),
                atomicBatch(createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .submitKeyName(SUBMIT_KEY)
                                .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                .withConsensusCustomFee(fixedConsensusHtsFee(1, "testToken", COLLECTOR))
                                .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                getTopicInfo(TOPIC)
                        .hasAdminKey(ADMIN_KEY)
                        .hasSubmitKey(SUBMIT_KEY)
                        .hasFeeScheduleKey(FEE_SCHEDULE_KEY)
                        .hasCustomFee(expectedConsensusFixedHTSFee(1, "testToken", COLLECTOR))
                        .hasCustomFee(expectedConsensusFixedHbarFee(1, COLLECTOR)));
    }

    @HapiTest
    @DisplayName("with deleted topic")
    final Stream<DynamicTest> getTopicInfoWithDeletedTopic() {
        return hapiTest(
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(COLLECTOR),
                tokenCreate("testToken").tokenType(TokenType.FUNGIBLE_COMMON).initialSupply(500),
                tokenAssociate(COLLECTOR, "testToken"),
                atomicBatch(createTopic(TOPIC)
                                .adminKeyName(ADMIN_KEY)
                                .submitKeyName(SUBMIT_KEY)
                                .feeScheduleKeyName(FEE_SCHEDULE_KEY)
                                .withConsensusCustomFee(fixedConsensusHtsFee(1, "testToken", COLLECTOR))
                                .withConsensusCustomFee(fixedConsensusHbarFee(1, COLLECTOR))
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR),
                deleteTopic(TOPIC),
                getTopicInfo(TOPIC).hasCostAnswerPrecheck(ResponseCodeEnum.INVALID_TOPIC_ID));
    }
}
