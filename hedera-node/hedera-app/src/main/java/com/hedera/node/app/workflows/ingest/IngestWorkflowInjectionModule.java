// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest;

import com.hedera.node.app.components.IngestInjectionComponent;
import dagger.Binds;
import dagger.Module;

/**
 * Module for Ingest processing.
 */
@Module(subcomponents = {IngestInjectionComponent.class})
public interface IngestWorkflowInjectionModule {
    @Binds
    IngestWorkflow bindIngestWorkflow(IngestWorkflowImpl ingestWorkflow);
}
