// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.iss;

import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import java.util.List;
import org.hiero.consensus.model.notification.IssNotification;

public interface CatastrophicActionTrigger {

    @InputWireLabel("triggers a catastrophic action")
    PlatformStatusAction trigger(List<IssNotification> issNotifications);
}
