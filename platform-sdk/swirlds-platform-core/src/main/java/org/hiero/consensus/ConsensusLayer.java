package org.hiero.consensus;

import com.hedera.hapi.node.state.roster.Roster;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

public interface ConsensusLayer {

    void start();

    void destroy();

    void nextRound(final Roster newRoster);

    void quiescenceCommand(final QuiescenceCommand command);

}
