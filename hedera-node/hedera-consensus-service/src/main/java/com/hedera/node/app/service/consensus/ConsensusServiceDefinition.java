// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.pbj.runtime.RpcMethodDefinition;
import com.hedera.pbj.runtime.RpcServiceDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * The Consensus Service provides the ability for Hedera Hashgraph to provide aBFT consensus as to
 * the order and validity of messages submitted to a *topic*, as well as a *consensus timestamp* for
 * those messages.
 */
@SuppressWarnings("java:S6548")
public final class ConsensusServiceDefinition implements RpcServiceDefinition {
    /**
     * Singleton instance of the Token Service
     */
    public static final ConsensusServiceDefinition INSTANCE = new ConsensusServiceDefinition();

    private static final Set<RpcMethodDefinition<?, ?>> methods = Set.of(
            //
            // Create a topic to be used for consensus.
            // If an autoRenewAccount is specified, that account must also sign this transaction.
            // If an adminKey is specified, the adminKey must sign the transaction.
            // On success, the resulting TransactionReceipt contains the newly created TopicId.
            // Request is [ConsensusCreateTopicTransactionBody](#proto.ConsensusCreateTopicTransactionBody)
            //
            new RpcMethodDefinition<>("createTopic", Transaction.class, TransactionResponse.class),
            //
            // Update a topic.
            // If there is no adminKey, the only authorized update (available to anyone) is to extend the
            // expirationTime.
            // Otherwise, transaction must be signed by the adminKey.
            // If an adminKey is updated, the transaction must be signed by the pre-update adminKey and post-update
            // adminKey.
            // If a new autoRenewAccount is specified (not just being removed), that account must also sign the
            // transaction.
            // Request is [ConsensusUpdateTopicTransactionBody](#proto.ConsensusUpdateTopicTransactionBody)
            //
            new RpcMethodDefinition<>("updateTopic", Transaction.class, TransactionResponse.class),
            //
            // Delete a topic. No more transactions or queries on the topic (via HAPI) will succeed.
            // If an adminKey is set, this transaction must be signed by that key.
            // If there is no adminKey, this transaction will fail UNAUTHORIZED.
            // Request is [ConsensusDeleteTopicTransactionBody](#proto.ConsensusDeleteTopicTransactionBody)
            //
            new RpcMethodDefinition<>("deleteTopic", Transaction.class, TransactionResponse.class),
            //
            // Retrieve the latest state of a topic. This method is unrestricted and allowed on any topic by any payer
            // account.
            // Deleted accounts will not be returned.
            // Request is [ConsensusGetTopicInfoQuery](#proto.ConsensusGetTopicInfoQuery)
            // Response is [ConsensusGetTopicInfoResponse](#proto.ConsensusGetTopicInfoResponse)
            //
            new RpcMethodDefinition<>("getTopicInfo", Query.class, Response.class),
            //
            // Submit a message for consensus.
            // Valid and authorized messages on valid topics will be ordered by the consensus service, gossipped to the
            // mirror net, and published (in order) to all subscribers (from the mirror net) on this topic.
            // The submitKey (if any) must sign this transaction.
            // On success, the resulting TransactionReceipt contains the topic's updated topicSequenceNumber and
            // topicRunningHash.
            // Request is [ConsensusSubmitMessageTransactionBody](#proto.ConsensusSubmitMessageTransactionBody)
            //
            new RpcMethodDefinition<>("submitMessage", Transaction.class, TransactionResponse.class));

    private ConsensusServiceDefinition() {
        // Just something to keep the Gradle build believing we have a non-transitive
        // "requires" and hence preserving our module-info.class in the compiled JAR
        requireNonNull(CommonUtils.class);
    }

    @Override
    @NonNull
    public String basePath() {
        return "proto.ConsensusService";
    }

    @Override
    @NonNull
    public Set<RpcMethodDefinition<?, ?>> methods() {
        return methods;
    }
}
