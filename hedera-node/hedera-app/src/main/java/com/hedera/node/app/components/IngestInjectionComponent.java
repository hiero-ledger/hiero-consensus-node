// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.components;

import com.hedera.node.app.workflows.ingest.IngestWorkflow;
import dagger.Subcomponent;

/**
 * A Dagger subcomponent that provides the Ingest workflow.
 */
@Subcomponent
public interface IngestInjectionComponent {
    IngestWorkflow ingestWorkflow();

    @Subcomponent.Factory
    interface Factory {
        IngestInjectionComponent create();
    }
}
