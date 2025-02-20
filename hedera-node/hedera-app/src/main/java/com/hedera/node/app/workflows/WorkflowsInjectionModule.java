// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows;

import com.hedera.node.app.workflows.handle.HandleWorkflowModule;
import com.hedera.node.app.workflows.ingest.IngestWorkflowInjectionModule;
import com.hedera.node.app.workflows.prehandle.PreHandleWorkflowInjectionModule;
import com.hedera.node.app.workflows.query.QueryWorkflowInjectionModule;
import dagger.Module;

/**
 * Dagger module for all workflows
 */
@Module(
        includes = {
            HandleWorkflowModule.class,
            IngestWorkflowInjectionModule.class,
            PreHandleWorkflowInjectionModule.class,
            QueryWorkflowInjectionModule.class
        })
public interface WorkflowsInjectionModule {}
