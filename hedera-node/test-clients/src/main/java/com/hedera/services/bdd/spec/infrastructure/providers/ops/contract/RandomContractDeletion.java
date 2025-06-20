// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.infrastructure.providers.ops.contract;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.names.RegistrySourcedNameProvider;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.Optional;
import java.util.Set;

public class RandomContractDeletion implements OpProvider {
    private final RegistrySourcedNameProvider<AccountID> accounts;
    private final RegistrySourcedNameProvider<ContractID> contracts;
    private final ResponseCodeEnum[] customOutcomes;
    private boolean transferToContract = true;

    private final ResponseCodeEnum[] permissiblePrechecks =
            standardPrechecksAnd(ACCOUNT_DELETED, CONTRACT_DELETED, INVALID_ACCOUNT_ID);
    private final ResponseCodeEnum[] permissibleOutcomes =
            standardOutcomesAnd(ACCOUNT_DELETED, CONTRACT_DELETED, INVALID_ACCOUNT_ID, INVALID_CONTRACT_ID);

    public RandomContractDeletion(
            RegistrySourcedNameProvider<AccountID> accounts,
            RegistrySourcedNameProvider<ContractID> contracts,
            ResponseCodeEnum[] customOutcomes) {
        this.accounts = accounts;
        this.contracts = contracts;
        this.customOutcomes = customOutcomes;
    }

    @Override
    public Optional<HapiSpecOperation> get() {
        final var tbd = contracts.getQualifying();
        final var signer = accounts.getQualifying();
        if (tbd.isEmpty() || signer.isEmpty()) {
            return Optional.empty();
        }

        boolean contractThisTime = transferToContract;
        Optional<String> transfer;
        if (contractThisTime) {
            transfer = contracts.getQualifyingExcept(Set.of(tbd.get()));
        } else {
            transfer = accounts.getQualifying();
        }
        transferToContract = !transferToContract;

        if (transfer.isEmpty()) {
            return Optional.empty();
        }

        var op = contractDelete(tbd.get())
                .purging()
                .payingWith(signer.get())
                .signedBy(signer.get())
                .hasPrecheckFrom(plus(permissiblePrechecks, customOutcomes))
                .hasKnownStatusFrom(plus(permissibleOutcomes, customOutcomes));
        if (contractThisTime) {
            op.transferContract(transfer.get());
        } else {
            op.transferAccount(transfer.get());
        }

        return Optional.of(op);
    }
}
