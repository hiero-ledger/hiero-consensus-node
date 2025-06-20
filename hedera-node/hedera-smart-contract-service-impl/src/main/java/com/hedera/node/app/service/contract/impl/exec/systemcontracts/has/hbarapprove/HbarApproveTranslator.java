// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.hbarapprove;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.token.CryptoAllowance;
import com.hedera.hapi.node.token.CryptoApproveAllowanceTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.CallVia;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code hbarApprove()} calls to the HAS system contract.
 */
@Singleton
public class HbarApproveTranslator extends AbstractCallTranslator<HasCallAttempt> {

    /** Selector for hbarApprove(address,int256) method. */
    public static final SystemContractMethod HBAR_APPROVE_PROXY = SystemContractMethod.declare(
                    "hbarApprove(address,int256)", ReturnTypes.INT_64)
            .withVia(CallVia.PROXY)
            .withCategories(Category.APPROVAL);

    /** Selector for hbarApprove(address,address,int256) method. */
    public static final SystemContractMethod HBAR_APPROVE = SystemContractMethod.declare(
                    "hbarApprove(address,address,int256)", ReturnTypes.INT_64)
            .withCategories(Category.APPROVAL);

    /**
     * Default constructor for injection.
     */
    @Inject
    public HbarApproveTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HAS, systemContractMethodRegistry, contractMetrics);

        registerMethods(HBAR_APPROVE, HBAR_APPROVE_PROXY);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HasCallAttempt attempt) {
        requireNonNull(attempt);

        return attempt.isMethod(HBAR_APPROVE, HBAR_APPROVE_PROXY);
    }
    /**
     * {@inheritDoc}
     */
    @Override
    public Call callFrom(@NonNull final HasCallAttempt attempt) {
        requireNonNull(attempt);

        if (attempt.isSelector(HBAR_APPROVE)) {
            return new HbarApproveCall(attempt, bodyForApprove(attempt));
        } else if (attempt.isSelector(HBAR_APPROVE_PROXY)) {
            return new HbarApproveCall(attempt, bodyForApproveProxy(attempt));
        }
        return null;
    }

    @NonNull
    private TransactionBody bodyForApprove(@NonNull final HasCallAttempt attempt) {
        requireNonNull(attempt);
        final var call = HBAR_APPROVE.decodeCall(attempt.inputBytes());
        var owner = attempt.addressIdConverter().convert(call.get(0));
        var spender = attempt.addressIdConverter().convert(call.get(1));

        return bodyOf(cryptoApproveTransactionBody(owner, spender, call.get(2)));
    }

    @NonNull
    private TransactionBody bodyForApproveProxy(@NonNull final HasCallAttempt attempt) {
        requireNonNull(attempt);
        final var call = HBAR_APPROVE_PROXY.decodeCall(attempt.inputBytes());
        final var owner = attempt.redirectAccount() == null
                ? attempt.senderId()
                : attempt.redirectAccount().accountId();
        final var spender = attempt.addressIdConverter().convert(call.get(0));

        return bodyOf(cryptoApproveTransactionBody(owner, spender, call.get(1)));
    }

    @NonNull
    private CryptoApproveAllowanceTransactionBody cryptoApproveTransactionBody(
            @NonNull final AccountID owner, @NonNull final AccountID operatorId, @NonNull final BigInteger amount) {
        requireNonNull(owner);
        requireNonNull(operatorId);
        requireNonNull(amount);
        return CryptoApproveAllowanceTransactionBody.newBuilder()
                .cryptoAllowances(CryptoAllowance.newBuilder()
                        .owner(owner)
                        .spender(operatorId)
                        .amount(amount.longValueExact())
                        .build())
                .build();
    }

    @NonNull
    private TransactionBody bodyOf(
            @NonNull final CryptoApproveAllowanceTransactionBody approveAllowanceTransactionBody) {
        return TransactionBody.newBuilder()
                .cryptoApproveAllowance(approveAllowanceTransactionBody)
                .build();
    }
}
