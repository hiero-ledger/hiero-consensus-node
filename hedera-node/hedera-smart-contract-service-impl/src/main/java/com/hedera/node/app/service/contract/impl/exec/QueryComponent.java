// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.service.contract.impl.annotations.QueryScope;
import com.hedera.node.app.spi.workflows.QueryContext;
import dagger.BindsInstance;
import dagger.Subcomponent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

@Subcomponent(modules = {QueryModule.class})
@QueryScope
public interface QueryComponent {
    @Subcomponent.Factory
    interface Factory {
        QueryComponent create(
                @BindsInstance @NonNull QueryContext context,
                @BindsInstance @NonNull Instant approxConsensusTime,
                @BindsInstance @NonNull HederaFunctionality functionality);
    }

    ContextQueryProcessor contextQueryProcessor();
}
