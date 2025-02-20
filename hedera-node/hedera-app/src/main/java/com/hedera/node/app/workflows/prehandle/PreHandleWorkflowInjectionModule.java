// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.prehandle;

import com.hedera.node.app.signature.SignatureExpander;
import com.hedera.node.app.signature.SignatureVerifier;
import com.hedera.node.app.signature.impl.SignatureExpanderImpl;
import com.hedera.node.app.signature.impl.SignatureVerifierImpl;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

@Module
public interface PreHandleWorkflowInjectionModule {
    @Binds
    PreHandleWorkflow bindPreHandleWorkflow(PreHandleWorkflowImpl preHandleWorkflow);

    @Binds
    SignatureVerifier bindSignatureVerifier(SignatureVerifierImpl signatureVerifier);

    @Binds
    SignatureExpander bindSignatureExpander(SignatureExpanderImpl signatureExpander);

    @Provides
    static ExecutorService provideExecutorService() {
        return ForkJoinPool.commonPool();
    }
}
