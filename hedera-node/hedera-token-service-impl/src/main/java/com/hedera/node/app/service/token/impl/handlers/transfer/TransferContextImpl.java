// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.AMOUNT_EXCEEDS_ALLOWANCE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SPENDER_DOES_NOT_HAVE_ALLOWANCE;
import static com.hedera.node.app.service.token.AliasUtils.isSerializedProtoKey;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.AssessedCustomFee;
import com.hedera.node.app.service.token.AliasUtils;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.handlers.transfer.customfees.AssessedFeeWithPayerDebits;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The context of a token transfer. This is used to pass information between the steps of the transfer.
 */
public class TransferContextImpl implements TransferContext {
    private final WritableAccountStore accountStore;
    private final AutoAccountCreator autoAccountCreator;
    private final HandleContext context;
    private int numAutoCreations;
    private int numLazyCreations;
    private final Map<Bytes, AccountID> resolutions = new LinkedHashMap<>();
    private final List<TokenAssociation> automaticAssociations = new ArrayList<>();
    private final List<AssessedFeeWithPayerDebits> assessedFeeWithPayerDebits = new ArrayList<>();
    private CryptoTransferTransactionBody syntheticBody = null;
    private final boolean enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments;

    /**
     * Create a new {@link TransferContextImpl} instance.
     * @param context The context to use.
     */
    public TransferContextImpl(final HandleContext context) {
        this(context, true);
    }

    /**
     * Create a new {@link TransferContextImpl} instance.
     * @param context The context to use.
     * @param enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments Whether to enforce mono service restrictions
     *                                                                      on auto creation custom fee payments.
     */
    public TransferContextImpl(
            final HandleContext context, final boolean enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments) {
        this.context = context;
        this.accountStore = context.storeFactory().writableStore(WritableAccountStore.class);
        this.autoAccountCreator = new AutoAccountCreator(context);
        this.enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments =
                enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments;
    }

    /**
     * Create a new {@link TransferContextImpl} instance.
     * Allow initializing transfer context from another handler, by providing synthetic tnx body.
     *
     * @param context The context to use.
     * @param syntheticBody The body of a crypto transfer transaction
     * @param enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments Whether to enforce mono service restrictions
     *                                                                      on auto creation custom fee payments.
     */
    public TransferContextImpl(
            final HandleContext context,
            final CryptoTransferTransactionBody syntheticBody,
            final boolean enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments) {
        this.context = context;
        this.syntheticBody = syntheticBody;
        this.accountStore = context.storeFactory().writableStore(WritableAccountStore.class);
        this.autoAccountCreator = new AutoAccountCreator(context);
        this.enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments =
                enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments;
    }

    @Override
    public AccountID getFromAlias(final AccountID aliasedId) {
        final var account = accountStore.getAliasedAccountById(aliasedId);

        if (account != null) {
            final var id = account.accountId();
            resolutions.put(aliasedId.alias(), id);
            return id;
        }
        return null;
    }

    @Override
    public void createFromAlias(final Bytes alias, final int reqMaxAutoAssociations) {
        // if it is a serialized proto key, auto-create account
        if (AliasUtils.isOfEvmAddressSize(alias)) {
            // if it is an evm address create a hollow account
            numLazyCreations++;
        } else if (isSerializedProtoKey(alias)) {
            numAutoCreations++;
        } else {
            // Only EVM addresses and key aliases are supported when creating a new account.
            throw new HandleException(INVALID_ALIAS_KEY);
        }

        // Keep the created account in the resolutions map
        final var createdAccount = autoAccountCreator.create(alias, reqMaxAutoAssociations);
        resolutions.put(alias, createdAccount);
    }

    @Override
    public HandleContext getHandleContext() {
        return context;
    }

    @Override
    public int numOfAutoCreations() {
        return numAutoCreations;
    }

    @Override
    public void chargeExtraFeeToHapiPayer(final long amount) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public Map<Bytes, AccountID> resolutions() {
        return resolutions;
    }

    @Override
    public int numOfLazyCreations() {
        return numLazyCreations;
    }

    /* ------------------- Needed for building records ------------------- */
    public void addToAutomaticAssociations(TokenAssociation newAssociation) {
        automaticAssociations.add(newAssociation);
    }

    /**
     * Get the automatic associations.
     * @return The automatic associations
     */
    public List<TokenAssociation> getAutomaticAssociations() {
        return automaticAssociations;
    }

    public void addToAssessedCustomFee(AssessedFeeWithPayerDebits assessedCustomFee) {
        assessedFeeWithPayerDebits.add(assessedCustomFee);
    }

    @Override
    public List<AssessedCustomFee> getAssessedCustomFees() {
        return new ArrayList<>(assessedFeeWithPayerDebits.stream()
                .map(AssessedFeeWithPayerDebits::assessedCustomFee)
                .toList());
    }

    @Override
    public List<AssessedFeeWithPayerDebits> getAssessedFeesWithPayerDebits() {
        return assessedFeeWithPayerDebits;
    }

    public boolean isEnforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments() {
        return enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments;
    }

    @Override
    public void validateHbarAllowances() {
        final var topLevelPayer = context.payer();
        // use the synthetic body if we have one
        var body = syntheticBody != null ? syntheticBody : context.body().cryptoTransferOrThrow();
        for (final var aa : body.transfersOrElse(TransferList.DEFAULT).accountAmounts()) {
            if (aa.isApproval() && aa.amount() < 0L) {
                maybeValidateHbarAllowance(
                        accountStore.getAliasedAccountById(aa.accountIDOrElse(AccountID.DEFAULT)),
                        topLevelPayer,
                        aa.amount());
            }
        }
    }

    private void maybeValidateHbarAllowance(
            @Nullable final Account account, @NonNull final AccountID topLevelPayer, final long amount) {
        if (account != null) {
            final var cryptoAllowances = account.cryptoAllowances();
            for (final var allowance : cryptoAllowances) {
                if (topLevelPayer.equals(allowance.spenderId())) {
                    final var newAllowanceAmount = allowance.amount() + amount;
                    validateTrue(newAllowanceAmount >= 0, AMOUNT_EXCEEDS_ALLOWANCE);
                    return;
                }
            }
            throw new HandleException(SPENDER_DOES_NOT_HAVE_ALLOWANCE);
        }
    }
}
