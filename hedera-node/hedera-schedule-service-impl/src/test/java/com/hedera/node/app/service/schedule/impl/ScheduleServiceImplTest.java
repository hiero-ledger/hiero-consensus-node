// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema;
import com.hedera.node.app.service.schedule.impl.schemas.V0570ScheduleSchema;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.throttle.Throttle;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.StateDefinition;
import java.time.InstantSource;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.BDDAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceImplTest {
    @Mock
    private SchemaRegistry registry;

    @Mock
    private AppContext appContext;

    @Mock
    private InstantSource instantSource;

    @Mock
    private Throttle.Factory throttleFactory;

    @Mock
    private EntityIdFactory idFactory;

    @Mock
    private FeeCharging feeCharging;

    @Test
    void testsSpi() {
        given(appContext.idFactory()).willReturn(idFactory);
        given(appContext.instantSource()).willReturn(instantSource);
        given(appContext.throttleFactory()).willReturn(throttleFactory);
        given(appContext.feeChargingSupplier()).willReturn(() -> feeCharging);
        final ScheduleService service = new ScheduleServiceImpl(appContext);
        BDDAssertions.assertThat(service).isNotNull();
        BDDAssertions.assertThat(service.getClass()).isEqualTo(ScheduleServiceImpl.class);
        BDDAssertions.assertThat(service.getServiceName()).isEqualTo("ScheduleService");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void registersExpectedSchema() {
        given(appContext.idFactory()).willReturn(idFactory);
        given(appContext.instantSource()).willReturn(instantSource);
        given(appContext.throttleFactory()).willReturn(throttleFactory);
        given(appContext.feeChargingSupplier()).willReturn(() -> feeCharging);
        final ScheduleServiceImpl subject = new ScheduleServiceImpl(appContext);
        ArgumentCaptor<Schema> schemaCaptor = ArgumentCaptor.forClass(Schema.class);
        subject.registerSchemas(registry);
        verify(registry, times(2)).register(schemaCaptor.capture());

        final var schemas = schemaCaptor.getAllValues();
        assertThat(schemas).hasSize(2);
        assertThat(schemas.getFirst()).isInstanceOf(V0490ScheduleSchema.class);
        assertThat(schemas.get(1)).isInstanceOf(V0570ScheduleSchema.class);

        Set<StateDefinition> statesToCreate = schemas.getFirst().statesToCreate();
        BDDAssertions.assertThat(statesToCreate).isNotNull();
        List<String> statesList =
                statesToCreate.stream().map(StateDefinition::stateKey).sorted().toList();
        BDDAssertions.assertThat(statesToCreate.size()).isEqualTo(3);
        BDDAssertions.assertThat(statesList.get(0)).isEqualTo(V0490ScheduleSchema.SCHEDULES_BY_EQUALITY_KEY);
        BDDAssertions.assertThat(statesList.get(1)).isEqualTo(V0490ScheduleSchema.SCHEDULES_BY_EXPIRY_SEC_KEY);
        BDDAssertions.assertThat(statesList.get(2)).isEqualTo(V0490ScheduleSchema.SCHEDULES_BY_ID_KEY);

        statesToCreate = schemas.get(1).statesToCreate();
        BDDAssertions.assertThat(statesToCreate).isNotNull();
        statesList =
                statesToCreate.stream().map(StateDefinition::stateKey).sorted().toList();
        BDDAssertions.assertThat(statesToCreate.size()).isEqualTo(4);
        BDDAssertions.assertThat(statesList.get(0)).isEqualTo(V0570ScheduleSchema.SCHEDULED_COUNTS_KEY);
        BDDAssertions.assertThat(statesList.get(1)).isEqualTo(V0570ScheduleSchema.SCHEDULED_ORDERS_KEY);
        BDDAssertions.assertThat(statesList.get(2)).isEqualTo(V0570ScheduleSchema.SCHEDULED_USAGES_KEY);
        BDDAssertions.assertThat(statesList.get(3)).isEqualTo(V0570ScheduleSchema.SCHEDULE_ID_BY_EQUALITY_KEY);
    }
}
