// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.components;

import com.hedera.node.app.workflows.query.QueryWorkflow;
import dagger.Subcomponent;

/**
 * A Dagger subcomponent that provides the query workflow.
 */
@Subcomponent
public interface QueryInjectionComponent {
    QueryWorkflow queryWorkflow();

    @Subcomponent.Factory
    interface Factory {
        QueryInjectionComponent create();
    }
}
