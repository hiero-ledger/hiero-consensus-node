// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.iss;

import com.swirlds.platform.system.status.actions.CatastrophicFailureAction;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import java.util.List;
import java.util.Set;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.notification.IssNotification.IssType;

public class DefaultCatastrophicActionTrigger implements CatastrophicActionTrigger {
    /** The types of ISSs that should trigger a catastrophic failure */
    private static final Set<IssType> CATASTROPHIC_ISS_TYPES = Set.of(IssType.SELF_ISS, IssType.CATASTROPHIC_ISS);

    @Override
    public PlatformStatusAction trigger(final List<IssNotification> issNotifications) {
        if (!issNotifications.isEmpty()
                && issNotifications.stream()
                        .map(IssNotification::getIssType)
                        .anyMatch(CATASTROPHIC_ISS_TYPES::contains)) {
            return new CatastrophicFailureAction();
        }
        return null;
    }
}
