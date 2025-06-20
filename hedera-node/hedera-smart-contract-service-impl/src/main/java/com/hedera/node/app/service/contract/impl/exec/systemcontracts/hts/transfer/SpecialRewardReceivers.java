// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.recordBuilderFor;

import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Provides logic to detect account ids that need to be treated as in special
 * reward situations for mono-service fidelity.
 */
public class SpecialRewardReceivers {
    public static final SpecialRewardReceivers SPECIAL_REWARD_RECEIVERS = new SpecialRewardReceivers();

    /**
     * Adds any special reward receivers to the given frame for the given {@link CryptoTransferTransactionBody}.
     *
     * @param frame the frame to add to
     * @param body the body to inspect
     */
    public void addInFrame(
            @NonNull final MessageFrame frame,
            @NonNull final CryptoTransferTransactionBody body,
            @NonNull final List<AssessedCustomFee> assessedCustomFees) {
        final var recordBuilder = recordBuilderFor(frame);
        body.transfersOrElse(TransferList.DEFAULT)
                .accountAmounts()
                .forEach(adjustment -> recordBuilder.trackExplicitRewardSituation(adjustment.accountIDOrThrow()));
        body.tokenTransfers().forEach(transfers -> {
            transfers
                    .transfers()
                    .forEach(adjustment -> recordBuilder.trackExplicitRewardSituation(adjustment.accountIDOrThrow()));
            transfers.nftTransfers().forEach(transfer -> {
                recordBuilder.trackExplicitRewardSituation(transfer.senderAccountIDOrThrow());
                recordBuilder.trackExplicitRewardSituation(transfer.receiverAccountIDOrThrow());
            });
        });
        assessedCustomFees.forEach(
                fee -> recordBuilder.trackExplicitRewardSituation(fee.feeCollectorAccountIdOrThrow()));
    }
}
