/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.state.listeners;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.BlockStreamManager;
import com.swirlds.platform.system.state.notifications.AsyncFatalIssListener;
import com.swirlds.platform.system.state.notifications.IssNotification;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Singleton
public class FatalIssListenerImpl implements AsyncFatalIssListener {

    private static final Logger log = LogManager.getLogger(FatalIssListenerImpl.class);

    private final BlockStreamManager blockStreamManager;

    @Inject
    public FatalIssListenerImpl(@NonNull final BlockStreamManager blockStreamManager) {
        this.blockStreamManager = requireNonNull(blockStreamManager);
    }

    @Override
    public void notify(@NonNull final IssNotification data) {
        log.warn("ISS detected (type={}, round={})", data.getIssType(), data.getRound());
        blockStreamManager.notifyFatalEvent();
    }
}
