// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl.operations.transactions;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.dsl.entities.SpecToken;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoApproveAllowance;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds option for approving allowance for fungible, non-fungible tokens and hBars as well.
 */
public class ApproveAllowanceOperation
        extends AbstractSpecTransaction<ApproveAllowanceOperation, HapiCryptoApproveAllowance>
        implements SpecOperation {

    @NonNull
    private final String ownerName;

    @NonNull
    private final String spenderName;

    @Nullable
    private final String tokenName;

    private final AllowanceType allowanceType;
    private final long allowance;
    private final List<Long> serialNumbers = new ArrayList<>();
    private boolean isApproveForAll;

    /**
     * This constructor is used for approving allowance for hBars.
     */
    public ApproveAllowanceOperation(
            @NonNull final SpecAccount owner, @NonNull final SpecContract spender, final long amount) {
        super(List.of(owner, spender));

        this.allowance = amount;
        this.ownerName = requireNonNull(owner.name());
        this.spenderName = requireNonNull(spender.name());
        this.tokenName = null;
        this.allowanceType = AllowanceType.HBAR;
    }

    /**
     * This constructor is used for approving allowance for fungible tokens.
     */
    public ApproveAllowanceOperation(
            @NonNull final SpecToken token,
            @NonNull final SpecAccount owner,
            @NonNull final SpecContract spender,
            final long amount) {
        super(List.of(token, owner, spender));

        this.allowance = amount;
        this.ownerName = requireNonNull(owner.name());
        this.spenderName = spender.name();
        this.tokenName = requireNonNull(token.name());
        this.allowanceType = AllowanceType.FUNGIBLE_TOKEN;
    }

    /**
     * This constructor is used for approving allowance for non-fungible tokens.
     */
    public ApproveAllowanceOperation(
            @NonNull final SpecToken token,
            @NonNull final SpecAccount owner,
            @NonNull final SpecContract spender,
            final boolean isApproveForAll,
            @NonNull final List<Long> serialNumbers) {
        super(List.of(token, owner, spender));

        this.allowance = 0;
        this.ownerName = requireNonNull(owner.name());
        this.spenderName = requireNonNull(spender.name());
        this.tokenName = requireNonNull(token.name());
        this.serialNumbers.addAll(serialNumbers);
        this.isApproveForAll = isApproveForAll;
        this.allowanceType = AllowanceType.NFT;
    }

    @Override
    protected ApproveAllowanceOperation self() {
        return this;
    }

    @NonNull
    @Override
    protected SpecOperation computeDelegate(@NonNull final HapiSpec spec) {
        return switch (allowanceType) {
            case HBAR -> cryptoApproveAllowance()
                    .signedByPayerAnd(ownerName)
                    .addCryptoAllowance(ownerName, spenderName, allowance);
            case NFT -> cryptoApproveAllowance()
                    .signedByPayerAnd(ownerName)
                    .addNftAllowance(ownerName, tokenName, spenderName, isApproveForAll, serialNumbers);
            case FUNGIBLE_TOKEN -> cryptoApproveAllowance()
                    .signedByPayerAnd(ownerName)
                    .addTokenAllowance(ownerName, tokenName, spenderName, allowance);
        };
    }

    public enum AllowanceType {
        HBAR,
        NFT,
        FUNGIBLE_TOKEN
    }
}
