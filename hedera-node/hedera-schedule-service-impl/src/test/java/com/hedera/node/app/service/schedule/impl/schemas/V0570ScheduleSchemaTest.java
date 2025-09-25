// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.schemas;

import static com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema.SCHEDULES_BY_EQUALITY_STATE_ID;
import static com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema.SCHEDULES_BY_EXPIRY_SEC_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.service.schedule.impl.ScheduleTestBase;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.app.spi.fixtures.util.LogCaptureExtension;
import com.hedera.node.app.spi.fixtures.util.LoggingSubject;
import com.hedera.node.app.spi.fixtures.util.LoggingTarget;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.swirlds.state.lifecycle.MigrationContext;
import java.security.InvalidKeyException;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class V0570ScheduleSchemaTest extends ScheduleTestBase {

    @LoggingTarget
    private LogCaptor logCaptor;

    @LoggingSubject
    private V0570ScheduleSchema subject;

    @Mock
    private MigrationContext migrationContext;

    @BeforeEach
    void setUp() throws PreCheckException, InvalidKeyException {
        setUpBase();
        subject = new V0570ScheduleSchema();
    }

    @Test
    void constructorHappyPath() {
        assertThat(subject.getVersion())
                .isEqualTo(
                        SemanticVersion.newBuilder().major(0).minor(57).patch(0).build());
    }

    @Test
    void testStatesToRemove() {
        Set<Integer> statesToRemove = subject.statesToRemove();
        assertNotNull(statesToRemove);
        assertEquals(2, statesToRemove.size());
        assertTrue(
                statesToRemove.containsAll(Set.of(SCHEDULES_BY_EXPIRY_SEC_STATE_ID, SCHEDULES_BY_EQUALITY_STATE_ID)));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void restartNullArgThrows() {
        Assertions.assertThatThrownBy(() -> subject.restart(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void restartHappyPath() {
        Assertions.assertThatNoException().isThrownBy(() -> subject.restart(migrationContext));
    }
}
