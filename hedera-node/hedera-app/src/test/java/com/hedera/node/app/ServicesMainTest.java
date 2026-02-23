// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app;

import static com.swirlds.platform.system.SystemExitCode.NODE_ID_NOT_PROVIDED;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import com.swirlds.platform.config.legacy.ConfigurationException;
import com.swirlds.platform.system.SystemExitUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class ServicesMainTest {
    // no local nodes specified, no environment nodes specified
    @Test
    void throwsExceptionOnNoNodesToRun() {
        String[] args = {};
        try (MockedStatic<SystemExitUtils> systemExitUtilsMockedStatic = mockStatic(SystemExitUtils.class)) {
            assertThatThrownBy(() -> ServicesMain.main(args)).isInstanceOf(ConfigurationException.class);
            systemExitUtilsMockedStatic.verify(() -> SystemExitUtils.exitSystem(
                    NODE_ID_NOT_PROVIDED, "No node id specified on command line. Use -local <nodeId>"));
        }
    }

    // more than one local node specified on the commandline
    @Test
    void hardExitOnTooManyCliNodes() {
        String[] args = {"-local", "1", "2"}; // both "1" and "2" match entries in address book

        try (MockedStatic<SystemExitUtils> systemExitUtilsMockedStatic = mockStatic(SystemExitUtils.class)) {
            assertThatThrownBy(() -> ServicesMain.main(args)).isInstanceOf(ConfigurationException.class);
            systemExitUtilsMockedStatic.verify(() -> SystemExitUtils.exitSystem(NODE_ID_NOT_PROVIDED));
        }
    }
}
