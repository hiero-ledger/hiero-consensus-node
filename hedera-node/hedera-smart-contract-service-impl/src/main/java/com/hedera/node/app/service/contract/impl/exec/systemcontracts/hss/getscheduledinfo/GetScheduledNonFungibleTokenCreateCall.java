// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.getscheduledinfo;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.FullResult.successResult;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult.gasOnly;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.getscheduledinfo.GetScheduledInfoTranslator.GET_SCHEDULED_CREATE_NON_FUNGIBLE_TOKEN_INFO;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.TokenTupleUtils.nftTokenInfoTupleFor;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import com.hedera.node.config.data.LedgerConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;

public class GetScheduledNonFungibleTokenCreateCall extends AbstractCall {

    private final ScheduleID scheduleID;
    private final Configuration configuration;

    public GetScheduledNonFungibleTokenCreateCall(
            @NonNull SystemContractGasCalculator gasCalculator,
            @NonNull Enhancement enhancement,
            @NonNull final Configuration configuration,
            @NonNull final ScheduleID scheduleID) {
        super(gasCalculator, enhancement, true);
        this.configuration = configuration;
        this.scheduleID = scheduleID;
    }

    @NonNull
    @Override
    public PricedResult execute(MessageFrame frame) {
        final var schedule = nativeOperations().getSchedule(scheduleID);
        // Validate that given schedule exists
        if (schedule == null) {
            return gasOnly(revertResult(RECORD_NOT_FOUND, gasCalculator.viewGasRequirement()), RECORD_NOT_FOUND, true);
        }
        // Validate that give schedule is a token creation schedule
        if (schedule.scheduledTransaction() == null
                || schedule.scheduledTransaction().tokenCreation() == null) {
            return gasOnly(
                    revertResult(INVALID_SCHEDULE_ID, gasCalculator.viewGasRequirement()), INVALID_SCHEDULE_ID, true);
        }
        // Revert if the given schedule is not a nft creation schedule
        final var tokenCreation = schedule.scheduledTransaction().tokenCreation();
        if (tokenCreation.tokenType() == TokenType.FUNGIBLE_COMMON) {
            return gasOnly(
                    revertResult(INVALID_SCHEDULE_ID, gasCalculator.viewGasRequirement()), INVALID_SCHEDULE_ID, true);
        }

        // Return the token create transaction body parsed to fungible token info tuple
        final var ledgerConfig = configuration.getConfigData(LedgerConfig.class);
        final var ledgerId = Bytes.wrap(ledgerConfig.id().toByteArray()).toString();
        return gasOnly(
                successResult(
                        GET_SCHEDULED_CREATE_NON_FUNGIBLE_TOKEN_INFO
                                .getOutputs()
                                .encode(Tuple.of(
                                        SUCCESS.protoOrdinal(), nftTokenInfoTupleFor(tokenCreation, ledgerId, 1))),
                        gasCalculator.viewGasRequirement()),
                SUCCESS,
                true);
    }
}
