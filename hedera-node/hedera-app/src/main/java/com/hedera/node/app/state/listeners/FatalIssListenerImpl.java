// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.listeners;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.cache.RecordBlockCache;
import com.swirlds.platform.system.state.notifications.AsyncFatalIssListener;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.notification.IssNotification;

@Singleton
public class FatalIssListenerImpl implements AsyncFatalIssListener {

    private static final Logger log = LogManager.getLogger(FatalIssListenerImpl.class);

    private final RecordBlockCache recordBlockCache;

    @Inject
    public FatalIssListenerImpl(@NonNull final RecordBlockCache recordBlockCache) {
        this.recordBlockCache = requireNonNull(recordBlockCache);
    }

    @Override
    public void notify(@NonNull final IssNotification data) {
        log.warn("ISS detected (type={}, round={})", data.getIssType(), data.getRound());
        recordBlockCache.setIssRoundNumber(data.getRound());
    }
}
