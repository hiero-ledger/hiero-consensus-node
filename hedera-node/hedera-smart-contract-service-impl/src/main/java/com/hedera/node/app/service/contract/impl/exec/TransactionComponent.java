// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.hevm.HydratedEthTxData;
import com.hedera.node.app.service.contract.impl.state.EvmFrameStates;
import com.hedera.node.app.spi.workflows.HandleContext;
import dagger.BindsInstance;
import dagger.Subcomponent;
import edu.umd.cs.findbugs.annotations.Nullable;

@Subcomponent(modules = {TransactionModule.class})
@TransactionScope
public interface TransactionComponent {
    @Subcomponent.Factory
    interface Factory {
        TransactionComponent create(
                @BindsInstance HandleContext context,
                @BindsInstance HederaFunctionality functionality,
                @BindsInstance EvmFrameStates evmFrameStates);
    }

    ContextTransactionProcessor contextTransactionProcessor();

    HederaOperations hederaOperations();

    @Nullable
    HydratedEthTxData hydratedEthTxData();
}
