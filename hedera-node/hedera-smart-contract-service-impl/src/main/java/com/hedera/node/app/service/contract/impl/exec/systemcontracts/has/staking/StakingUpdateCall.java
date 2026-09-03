// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.staking;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.encodedRc;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.standardized;
import static com.hedera.node.app.spi.workflows.DispatchOptions.UsePresetTxnId.NO;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.ACCOUNT_SERVICE_STAKING_UPDATE;
import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Applies a staking configuration change to an account by dispatching a {@code CryptoUpdate} carrying staking
 * fields only, as specified by
 * <a href="https://github.com/hiero-ledger/hiero-improvement-proposals/pull/1522">HIP-1522</a>.
 *
 * <p>Backs all five mutating functions — {@code stakeToNode}, {@code stakeToAccount}, {@code unstake},
 * {@code setDeclineReward} and {@code stakeToNodeAndDeclineReward} — on both the {@code IHRC632} facade and the
 * {@code IHederaAccountService} interface, since every one of them produces the same artifact and is priced the
 * same. {@link StakingTranslator} decides which fields the body carries.
 *
 * <p>Per the HAS convention and the HIP, a business failure is <b>returned</b> as a response code rather than
 * reverting; only a malformed call or an out-of-gas condition reverts.
 */
public class StakingUpdateCall extends AbstractCall {
    /**
     * Marks the dispatch as this system contract configuring an account's staking, which is what permits it to
     * name a contract account. See {@code CryptoUpdateHandler#isAccountServiceStakingUpdate}.
     */
    private static final DispatchMetadata STAKING_UPDATE_METADATA =
            new DispatchMetadata(ACCOUNT_SERVICE_STAKING_UPDATE, Boolean.TRUE);

    private final AccountID sender;

    @Nullable
    private final TransactionBody transactionBody;

    @Nullable
    private final VerificationStrategy verificationStrategy;

    @Nullable
    private final ResponseCodeEnum preDispatchFailure;

    /**
     * A call that dispatches the given staking-only {@code CryptoUpdate}.
     *
     * @param attempt the call attempt
     * @param transactionBody the synthetic body to dispatch
     */
    public StakingUpdateCall(@NonNull final HasCallAttempt attempt, @NonNull final TransactionBody transactionBody) {
        super(attempt.systemContractGasCalculator(), attempt.enhancement(), false);
        this.sender = attempt.senderId();
        this.transactionBody = requireNonNull(transactionBody);
        this.verificationStrategy = attempt.defaultVerificationStrategy();
        this.preDispatchFailure = null;
    }

    /**
     * A call that fails before dispatching anything, for input the translator can reject on its own — an
     * unresolvable target account, or a negative node id passed to {@code stakeToNodeAndDeclineReward}.
     *
     * @param attempt the call attempt
     * @param preDispatchFailure the response code to return
     */
    public StakingUpdateCall(
            @NonNull final HasCallAttempt attempt, @NonNull final ResponseCodeEnum preDispatchFailure) {
        super(attempt.systemContractGasCalculator(), attempt.enhancement(), false);
        this.sender = attempt.senderId();
        this.transactionBody = null;
        this.verificationStrategy = null;
        this.preDispatchFailure = requireNonNull(preDispatchFailure);
    }

    @NonNull
    @Override
    public PricedResult execute(@NonNull final MessageFrame frame) {
        requireNonNull(frame);
        if (preDispatchFailure != null) {
            return completionWith(
                    preDispatchFailure, gasCalculator.canonicalGasRequirement(DispatchType.CRYPTO_UPDATE));
        }
        final var body = requireNonNull(transactionBody);
        final var recordBuilder = systemContractOperations()
                .dispatch(
                        body,
                        requireNonNull(verificationStrategy),
                        sender,
                        ContractCallStreamBuilder.class,
                        emptySet(),
                        NO,
                        STAKING_UPDATE_METADATA);
        final var gasRequirement = gasCalculator.gasRequirement(body, DispatchType.CRYPTO_UPDATE, sender);
        return completionWith(gasRequirement, recordBuilder, encodedRc(standardized(recordBuilder.status())));
    }
}
