// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.mod;

import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withClearedField;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_ID;
import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumSet;
import java.util.Map;

public class QueryIdClearingStrategy extends IdClearingStrategy<QueryModification>
        implements QueryModificationStrategy {
    private static final Map<String, ExpectedAnswer> CLEARED_ID_ANSWERS = Map.ofEntries(
            Map.entry("proto.ContractGetInfoQuery.contractID", ExpectedAnswer.onCostAnswer(INVALID_CONTRACT_ID)),
            Map.entry("proto.ContractCallLocalQuery.contractID", ExpectedAnswer.onCostAnswer(INVALID_CONTRACT_ID)),
            Map.entry("proto.CryptoGetAccountBalanceQuery.accountID", ExpectedAnswer.onAnswerOnly(INVALID_ACCOUNT_ID)),
            Map.entry("proto.CryptoGetAccountRecordsQuery.accountID", ExpectedAnswer.onCostAnswer(INVALID_ACCOUNT_ID)),
            Map.entry("proto.CryptoGetInfoQuery.accountID", ExpectedAnswer.onCostAnswer(INVALID_ACCOUNT_ID)),
            Map.entry("proto.ContractGetBytecodeQuery.contractID", ExpectedAnswer.onCostAnswer(INVALID_CONTRACT_ID)),
            Map.entry("proto.FileGetContentsQuery.fileID", ExpectedAnswer.onCostAnswer(INVALID_FILE_ID)),
            Map.entry("proto.FileGetInfoQuery.fileID", ExpectedAnswer.onCostAnswer(INVALID_FILE_ID)),
            // Since both the free getTxnReceipt query AND the paid getTxnRecord query use the same
            // TransactionID.accountID field, we have two possible expected answers: For the free query,
            // a failure when using the ANSWER_ONLY response type; and for the paid query, a failure
            // one step earlier when using the COST_ANSWER response type
            Map.entry(
                    "proto.TransactionID.accountID",
                    new ExpectedAnswer(
                            EnumSet.of(INVALID_ACCOUNT_ID, INVALID_TRANSACTION_ID),
                            EnumSet.of(INVALID_ACCOUNT_ID, INVALID_TRANSACTION_ID))),
            Map.entry("proto.ConsensusGetTopicInfoQuery.topicID", ExpectedAnswer.onCostAnswer(INVALID_TOPIC_ID)),
            Map.entry("proto.TokenGetInfoQuery.token", ExpectedAnswer.onCostAnswer(INVALID_TOKEN_ID)),
            Map.entry("proto.ScheduleGetInfoQuery.scheduleID", ExpectedAnswer.onCostAnswer(INVALID_SCHEDULE_ID)),
            Map.entry("proto.NftID.token_ID", ExpectedAnswer.onCostAnswer(INVALID_TOKEN_ID)),
            Map.entry("proto.GetAccountDetailsQuery.account_id", ExpectedAnswer.onAnswerOnly(INVALID_ACCOUNT_ID)));

    @NonNull
    @Override
    public QueryModification modificationForTarget(@NonNull final TargetField targetField, final int encounterIndex) {
        final var expectedAnswer = CLEARED_ID_ANSWERS.get(targetField.name());
        requireNonNull(expectedAnswer, "No expected answer for field " + targetField.name());
        return new QueryModification(
                "Clearing field " + targetField.name() + " (#" + encounterIndex + ")",
                QueryMutation.withTransform(q -> withClearedField(q, targetField.descriptor(), encounterIndex)),
                expectedAnswer);
    }
}
