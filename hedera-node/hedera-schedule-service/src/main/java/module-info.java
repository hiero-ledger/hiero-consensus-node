// SPDX-License-Identifier: Apache-2.0
module com.hedera.node.app.service.schedule {
    requires transitive com.hedera.node.app.spi;
    requires transitive com.hedera.node.hapi;
    requires transitive com.hedera.pbj.runtime;
    requires com.hedera.node.app.hapi.utils;
    requires com.swirlds.state.api;
    requires static transitive com.github.spotbugs.annotations;

    exports com.hedera.node.app.service.schedule;

    uses com.hedera.node.app.service.schedule.ScheduleService;
}
