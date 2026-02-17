// SPDX-License-Identifier: Apache-2.0
package org.hiero.interledger.clpr.impl.handlers;

import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class ClprMessageQueueHandlers {
    private final ClprGetMessageQueueMetadataHandler clprGetMessageQueueMetadataHandler;
    private final ClprGetMessagesHandler clprGetMessagesHandler;
    private final ClprUpdateMessageQueueMetadataHandler clprUpdateMessageQueueMetadataHandler;
    private final ClprProcessMessageBundleHandler clprProcessMessageBundleHandler;

    @Inject
    public ClprMessageQueueHandlers(
            @NonNull final ClprGetMessageQueueMetadataHandler clprGetMessageQueueMetadataHandler,
            @NonNull final ClprGetMessagesHandler clprGetMessagesHandler,
            @NonNull final ClprUpdateMessageQueueMetadataHandler clprUpdateMessageQueueMetadataHandler,
            @NonNull final ClprProcessMessageBundleHandler clprProcessMessageBundleHandler) {
        this.clprGetMessageQueueMetadataHandler = clprGetMessageQueueMetadataHandler;
        this.clprGetMessagesHandler = clprGetMessagesHandler;
        this.clprUpdateMessageQueueMetadataHandler = clprUpdateMessageQueueMetadataHandler;
        this.clprProcessMessageBundleHandler = clprProcessMessageBundleHandler;
    }

    @NonNull
    public ClprGetMessageQueueMetadataHandler clprGetMessageQueueMetadataHandler() {
        return clprGetMessageQueueMetadataHandler;
    }

    @NonNull
    public ClprGetMessagesHandler clprGetMessagesHandler() {
        return clprGetMessagesHandler;
    }

    @NonNull
    public ClprUpdateMessageQueueMetadataHandler clprUpdateMessageQueueMetadataHandler() {
        return clprUpdateMessageQueueMetadataHandler;
    }

    @NonNull
    public ClprProcessMessageBundleHandler clprProcessMessageBundleHandler() {
        return clprProcessMessageBundleHandler;
    }
}
