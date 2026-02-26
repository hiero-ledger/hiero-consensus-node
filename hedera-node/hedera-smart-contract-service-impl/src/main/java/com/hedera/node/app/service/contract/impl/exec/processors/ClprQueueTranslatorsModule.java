// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.processors;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprQueueCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.deliverinboundmessage.ClprQueueDeliverInboundMessageTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.deliverinboundmessagereply.ClprQueueDeliverInboundMessageReplyTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.enqueuemessage.ClprQueueEnqueueMessageTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.enqueuemessageresponse.ClprQueueEnqueueMessageResponseTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Provides the {@link CallTranslator} implementations for the CLPR queue system contract.
 */
@Module
public interface ClprQueueTranslatorsModule {
    @Provides
    @Singleton
    @Named("ClprQueueTranslators")
    static List<CallTranslator<ClprQueueCallAttempt>> provideCallAttemptTranslators(
            @NonNull @Named("ClprQueueTranslators") final Set<CallTranslator<ClprQueueCallAttempt>> translators) {
        return List.copyOf(translators);
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("ClprQueueTranslators")
    static CallTranslator<ClprQueueCallAttempt> provideEnqueueMessageTranslator(
            @NonNull final ClprQueueEnqueueMessageTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("ClprQueueTranslators")
    static CallTranslator<ClprQueueCallAttempt> provideEnqueueMessageResponseTranslator(
            @NonNull final ClprQueueEnqueueMessageResponseTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("ClprQueueTranslators")
    static CallTranslator<ClprQueueCallAttempt> provideDeliverInboundMessageTranslator(
            @NonNull final ClprQueueDeliverInboundMessageTranslator translator) {
        return translator;
    }

    @Provides
    @Singleton
    @IntoSet
    @Named("ClprQueueTranslators")
    static CallTranslator<ClprQueueCallAttempt> provideDeliverInboundMessageReplyTranslator(
            @NonNull final ClprQueueDeliverInboundMessageReplyTranslator translator) {
        return translator;
    }
}
