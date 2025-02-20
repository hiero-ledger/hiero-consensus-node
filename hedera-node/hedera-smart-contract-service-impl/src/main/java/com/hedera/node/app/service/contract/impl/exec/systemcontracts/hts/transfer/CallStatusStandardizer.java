// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.standardized;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.transfer.TransferAuditTest.isAuditFlagRaised;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.configOf;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.node.config.data.AccountsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Standardizes the failure status of a call to a HTS transfer system contract.
 */
public class CallStatusStandardizer {
    /**
     * Default CallStatusStandardizer to use.
     */
    public static final CallStatusStandardizer CALL_STATUS_STANDARDIZER = new CallStatusStandardizer();

    private CallStatusStandardizer() {
        // Singleton
    }

    /**
     * @param status the response code to return before treatment
     * @param frame the current message frame that is used
     * @param op the body of crypto transfer operation
     * @return the response code after treatment
     */
    public ResponseCodeEnum codeForFailure(
            @NonNull final ResponseCodeEnum status,
            @NonNull final MessageFrame frame,
            @NonNull final CryptoTransferTransactionBody op) {
        requireNonNull(op);
        requireNonNull(status);
        requireNonNull(frame);
        if (status == INVALID_ACCOUNT_ID) {
            return isAuditFlagRaised(op, immutableAccountDebitAuditFor(frame))
                    ? INVALID_FULL_PREFIX_SIGNATURE_FOR_PRECOMPILE
                    : status;
        } else {
            return standardized(status);
        }
    }

    private TransferAuditTest immutableAccountDebitAuditFor(@NonNull final MessageFrame frame) {
        final var accountsConfig = configOf(frame).getConfigData(AccountsConfig.class);
        final Set<Long> immutableAccounts =
                Set.of(accountsConfig.stakingRewardAccount(), accountsConfig.nodeRewardAccount());
        return new TransferAuditTest() {
            @Override
            public boolean flagsAdjustment(@NonNull final AccountAmount adjust) {
                return adjust.amount() < 0
                        && immutableAccounts.contains(
                                adjust.accountIDOrElse(AccountID.DEFAULT).accountNumOrElse(0L));
            }

            @Override
            public boolean flagsNftTransfer(@NonNull final NftTransfer nftTransfer) {
                return immutableAccounts.contains(
                        nftTransfer.senderAccountIDOrElse(AccountID.DEFAULT).accountNumOrElse(0L));
            }
        };
    }
}
