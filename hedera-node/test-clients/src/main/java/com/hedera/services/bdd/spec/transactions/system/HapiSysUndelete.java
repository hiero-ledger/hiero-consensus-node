// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.transactions.system;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.asContractId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asFileId;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.SystemUndelete;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.junit.hedera.SystemFunctionalityTarget;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.SystemUndeleteTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Assertions;

public class HapiSysUndelete extends HapiTxnOp<HapiSysUndelete> {
    private Optional<String> file = Optional.empty();
    private Optional<String> contract = Optional.empty();

    public HapiSysUndelete file(String target) {
        file = Optional.of(target);
        return this;
    }

    public HapiSysUndelete contract(String target) {
        contract = Optional.of(target);
        return this;
    }

    @Override
    protected HapiSysUndelete self() {
        return this;
    }

    @Override
    public HederaFunctionality type() {
        return SystemUndelete;
    }

    @Override
    protected SystemFunctionalityTarget systemFunctionalityTarget() {
        return file.isPresent() ? SystemFunctionalityTarget.FILE : SystemFunctionalityTarget.CONTRACT;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        if (file.isPresent() && contract.isPresent()) {
            Assertions.fail("Ambiguous SystemUndelete---both file and contract present!");
        }
        SystemUndeleteTransactionBody opBody = spec.txns()
                .<SystemUndeleteTransactionBody, SystemUndeleteTransactionBody.Builder>body(
                        SystemUndeleteTransactionBody.class, b -> {
                            file.ifPresent(n -> b.setFileID(asFileId(n, spec)));
                            contract.ifPresent(n -> b.setContractID(asContractId(n, spec)));
                        });
        return b -> b.setSystemUndelete(opBody);
    }

    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        return spec.fees().maxFeeTinyBars();
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        MoreObjects.ToStringHelper helper = super.toStringHelper();
        file.ifPresent(n -> helper.add("file", n));
        contract.ifPresent(n -> helper.add("contract", n));
        return helper;
    }
}
