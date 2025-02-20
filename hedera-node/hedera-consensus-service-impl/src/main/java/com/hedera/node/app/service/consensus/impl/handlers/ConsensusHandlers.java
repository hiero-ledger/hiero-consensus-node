// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.handlers;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Class to hold all the consensus handlers.
 */
@Singleton
public class ConsensusHandlers {

    private final ConsensusCreateTopicHandler consensusCreateTopicHandler;

    private final ConsensusDeleteTopicHandler consensusDeleteTopicHandler;

    private final ConsensusGetTopicInfoHandler consensusGetTopicInfoHandler;

    private final ConsensusSubmitMessageHandler consensusSubmitMessageHandler;

    private final ConsensusUpdateTopicHandler consensusUpdateTopicHandler;

    /**
     * Constructor for ConsensusHandlers.
     * @param consensusCreateTopicHandler   the handler for create topic
     * @param consensusDeleteTopicHandler   the handler for delete topic
     * @param consensusGetTopicInfoHandler  the handler for topic info
     * @param consensusSubmitMessageHandler the handler for message submit
     * @param consensusUpdateTopicHandler   the handler for update topic
     */
    @Inject
    public ConsensusHandlers(
            @NonNull final ConsensusCreateTopicHandler consensusCreateTopicHandler,
            @NonNull final ConsensusDeleteTopicHandler consensusDeleteTopicHandler,
            @NonNull final ConsensusGetTopicInfoHandler consensusGetTopicInfoHandler,
            @NonNull final ConsensusSubmitMessageHandler consensusSubmitMessageHandler,
            @NonNull final ConsensusUpdateTopicHandler consensusUpdateTopicHandler) {
        this.consensusCreateTopicHandler =
                Objects.requireNonNull(consensusCreateTopicHandler, "consensusCreateTopicHandler must not be null");
        this.consensusDeleteTopicHandler =
                Objects.requireNonNull(consensusDeleteTopicHandler, "consensusDeleteTopicHandler must not be null");
        this.consensusGetTopicInfoHandler =
                Objects.requireNonNull(consensusGetTopicInfoHandler, "consensusGetTopicInfoHandler must not be null");
        this.consensusSubmitMessageHandler =
                Objects.requireNonNull(consensusSubmitMessageHandler, "consensusSubmitMessageHandler must not be null");
        this.consensusUpdateTopicHandler =
                Objects.requireNonNull(consensusUpdateTopicHandler, "consensusUpdateTopicHandler must not be null");
    }

    /**
     * Get the consensusCreateTopicHandler.
     *
     * @return the consensusCreateTopicHandler
     */
    public ConsensusCreateTopicHandler consensusCreateTopicHandler() {
        return consensusCreateTopicHandler;
    }

    /**
     * Get the consensusDeleteTopicHandler.
     *
     * @return the consensusDeleteTopicHandler
     */
    public ConsensusDeleteTopicHandler consensusDeleteTopicHandler() {
        return consensusDeleteTopicHandler;
    }

    /**
     * Get the consensusGetTopicInfoHandler.
     *
     * @return the consensusGetTopicInfoHandler
     */
    public ConsensusGetTopicInfoHandler consensusGetTopicInfoHandler() {
        return consensusGetTopicInfoHandler;
    }

    /**
     * Get the consensusSubmitMessageHandler.
     *
     * @return the consensusSubmitMessageHandler
     */
    public ConsensusSubmitMessageHandler consensusSubmitMessageHandler() {
        return consensusSubmitMessageHandler;
    }

    /**
     * Get the consensusUpdateTopicHandler.
     *
     * @return the consensusUpdateTopicHandler
     */
    public ConsensusUpdateTopicHandler consensusUpdateTopicHandler() {
        return consensusUpdateTopicHandler;
    }
}
