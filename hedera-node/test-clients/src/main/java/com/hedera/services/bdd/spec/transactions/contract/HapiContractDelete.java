// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.contract;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.infrastructure.meta.SupportedContract;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.ContractDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

public class HapiContractDelete extends HapiTxnOp<HapiContractDelete> {
    private boolean shouldPurge = false;
    private boolean claimingPermanentRemoval = false;
    private final String contract;
    private Optional<String> transferAccount = Optional.empty();
    private Optional<String> transferContract = Optional.empty();

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.ContractDelete;
    }

    @Override
    protected HapiContractDelete self() {
        return this;
    }

    public HapiContractDelete(String contract) {
        this.contract = contract;
    }

    public HapiContractDelete claimingPermanentRemoval() {
        claimingPermanentRemoval = true;
        return this;
    }

    public HapiContractDelete transferAccount(String to) {
        transferAccount = Optional.of(to);
        return this;
    }

    public HapiContractDelete transferContract(String to) {
        transferContract = Optional.of(to);
        return this;
    }

    public HapiContractDelete purging() {
        shouldPurge = true;
        return this;
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerSigs) throws Throwable {
        return spec.fees()
                .forActivityBasedOp(
                        HederaFunctionality.ContractDelete, scFees::getContractDeleteTxFeeMatrices, txn, numPayerSigs);
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        ContractDeleteTransactionBody opBody = spec.txns()
                .<ContractDeleteTransactionBody, ContractDeleteTransactionBody.Builder>body(
                        ContractDeleteTransactionBody.class, builder -> {
                            builder.setContractID(TxnUtils.asContractId(contract, spec));
                            transferContract.ifPresent(
                                    c -> builder.setTransferContractID(TxnUtils.asContractId(c, spec)));
                            transferAccount.ifPresent(a ->
                                    builder.setTransferAccountID(spec.registry().getAccountID(a)));
                            if (claimingPermanentRemoval) {
                                builder.setPermanentRemoval(true);
                            }
                        });
        return builder -> builder.setContractDeleteInstance(opBody);
    }

    @Override
    protected void updateStateOf(HapiSpec spec) throws Throwable {
        if (actualStatus != SUCCESS) {
            return;
        }
        if (shouldPurge) {
            if (spec.registry().hasAccountId(contract)) {
                spec.registry().removeAccount(contract);
            }
            spec.registry().removeKey(contract);
            spec.registry().removeContractId(contract);
            spec.registry().removeContractInfo(contract);
            if (spec.registry().hasContractChoice(contract)) {
                SupportedContract choice = spec.registry().getContractChoice(contract);
                AtomicInteger tag = new AtomicInteger();
                choice.getCallDetails().forEach(detail -> spec.registry()
                        .removeActionableCall(contract + "-" + tag.getAndIncrement()));
                choice.getLocalCallDetails().forEach(detail -> spec.registry()
                        .removeActionableLocalCall(contract + "-" + tag.getAndIncrement()));
                spec.registry().removeContractChoice(contract);
            }
        }
    }

    @Override
    protected List<Function<HapiSpec, Key>> defaultSigners() {
        return Arrays.asList(spec -> spec.registry().getKey(effectivePayer(spec)), spec -> spec.registry()
                .getKey(contract));
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        MoreObjects.ToStringHelper helper = super.toStringHelper().add("contract", contract);
        transferAccount.ifPresent(a -> helper.add("transferAccount", a));
        transferContract.ifPresent(c -> helper.add("transferContract", c));
        Optional.ofNullable(lastReceipt)
                .ifPresent(
                        receipt -> helper.add("deleted", receipt.getContractID().getContractNum()));
        return helper;
    }
}
