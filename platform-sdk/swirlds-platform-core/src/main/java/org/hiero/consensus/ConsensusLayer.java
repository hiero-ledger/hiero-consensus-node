package org.hiero.consensus;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

/**
 * initialization notes: If a freeze time is provided during construction of the consensus layer, that is the freeze
 * time and it must be honored. If there is no freeze time provided at construction time, there is no known future
 * freeze until this method is called.
 *
 * <p>The consensus layer does not need to know about lastFrozenTime. If a freeze time is provided
 * during construction of the consensus layer, that is the freeze time and it must be honored. If there is no freeze
 * time provided at construction time, there is no known future freeze until this method is called.
 */
public interface ConsensusLayer {

    void start();

    void destroy();

    /**
     * <p>This method causes more events to be added to the consensus algorithm. It will continue to feed events
     * into the consensus algorithm until the next round (or rounds) reach consensus. Then it will stop feeding it
     * events.
     *
     * <p>TODO Add roster behavior explanation
     *
     * <p>The freeze time informs the consensus layer of the next desired freeze time. A value of {@code null}
     * indicates that there is no freeze time, and any existing freeze time should be cleared.
     *
     * @param newRoster  a new roster to be applied at a deterministic number of rounds in the future, or {@code null}
     *                   if there is no new roster to adopt
     * @param freezeTime the next freeze time, or {@code null} if there is no planned freeze time
     */
    void requestNextRound(@Nullable final Roster newRoster, @Nullable final Instant freezeTime);

    void sendQuiescenceCommand(final QuiescenceCommand command);

}
