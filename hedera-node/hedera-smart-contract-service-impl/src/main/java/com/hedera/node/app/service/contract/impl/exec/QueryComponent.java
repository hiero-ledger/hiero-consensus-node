/*
 * Copyright (C) 2023-2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
