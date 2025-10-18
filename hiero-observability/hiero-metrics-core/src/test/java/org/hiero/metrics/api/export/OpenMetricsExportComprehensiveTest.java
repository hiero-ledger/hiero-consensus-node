// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import org.hiero.metrics.ConsoleMetricsExporter;
import org.hiero.metrics.TestExporterContext;
import org.hiero.metrics.api.BooleanGauge;
import org.hiero.metrics.api.DoubleCounter;
import org.hiero.metrics.api.DoubleGauge;
import org.hiero.metrics.api.LongCounter;
import org.hiero.metrics.api.StatContainer;
import org.hiero.metrics.api.StatelessMetric;
import org.hiero.metrics.api.StatsGaugeAdapter;
import org.hiero.metrics.api.core.Label;
import org.hiero.metrics.api.core.LongOrDoubleSupplier;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.core.MetricsFacade;
import org.hiero.metrics.api.export.extension.writer.OpenMetricsSnapshotsWriter;
import org.hiero.metrics.api.stat.StatUtils;
import org.hiero.metrics.api.stat.container.AtomicDouble;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

public class OpenMetricsExportComprehensiveTest {

    @Test
    public void testEmptyMetrics() throws IOException {
        TestExporterContext context = new TestExporterContext(OpenMetricsSnapshotsWriter.DEFAULT);
        context.exportAndVerify("""
                # EOF
                """);
        context.exportAndVerify("""
                # EOF
                """);
    }

    // TODO test escaping in names, labels, descriptions

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class BooleanGaugeTest {

        private static final TestExporterContext context = new TestExporterContext(OpenMetricsSnapshotsWriter.DEFAULT);

        private static BooleanGauge onlyName;
        private static BooleanGauge nameAndCategory;
        private static BooleanGauge nameAndDescription;
        private static BooleanGauge nameAndUnit;
        private static BooleanGauge nameDescriptionAndUnit;
        private static BooleanGauge trueInit;
        private static BooleanGauge constLabel;
        private static BooleanGauge dynamicLabel;
        private static BooleanGauge manyLabels;

        @Test
        @Order(1)
        public void testInitWithoutObservation() throws IOException {
            onlyName = BooleanGauge.builder("only_name").register(context.getRegistry());
            nameAndCategory = BooleanGauge.builder(
                            BooleanGauge.key("name_category").withCategory("bool"))
                    .register(context.getRegistry());
            nameAndDescription = BooleanGauge.builder("name_description")
                    .withDescription("Boolean gauge with description")
                    .register(context.getRegistry());
            nameAndUnit =
                    BooleanGauge.builder("name_unit").withUnit("bool_unit").register(context.getRegistry());
            nameDescriptionAndUnit = BooleanGauge.builder("name_description_unit")
                    .withDescription("Boolean gauge with description and unit")
                    .withUnit("bool_unit")
                    .register(context.getRegistry());
            trueInit = BooleanGauge.builder("true_init").withInitValue(true).register(context.getRegistry());
            constLabel = BooleanGauge.builder("const_label")
                    .withConstantLabel(new Label("c1", "c1_v1"))
                    .register(context.getRegistry());
            dynamicLabel = BooleanGauge.builder("dynamic_label")
                    .withDynamicLabelNames("d1")
                    .register(context.getRegistry());
            manyLabels = BooleanGauge.builder("many_labels")
                    .withConstantLabels(new Label("c1", "c1_v1"), new Label("c2", "c2_v1"))
                    .withDynamicLabelNames("d1", "d2")
                    .register(context.getRegistry());

            context.exportAndVerify(
                    """
                # TYPE only_name gauge
                # TYPE bool:name_category gauge
                # TYPE name_description gauge
                # HELP name_description Boolean gauge with description
                # TYPE name_unit_bool_unit gauge
                # UNIT name_unit_bool_unit bool_unit
                # TYPE name_description_unit_bool_unit gauge
                # UNIT name_description_unit_bool_unit bool_unit
                # HELP name_description_unit_bool_unit Boolean gauge with description and unit
                # TYPE true_init gauge
                # TYPE const_label gauge
                # TYPE dynamic_label gauge
                # TYPE many_labels gauge
                # EOF
                """);
        }

        @Test
        @Order(2)
        public void testObserve1() throws IOException {
            onlyName.getOrCreateNotLabeled().setTrue();
            onlyName.getOrCreateNotLabeled().setFalse(); // back to false

            nameAndCategory.getOrCreateNotLabeled().setFalse();
            nameAndCategory.getOrCreateNotLabeled().setFalse(); // stays false

            nameAndDescription.getOrCreateNotLabeled().setFalse();
            nameAndDescription.getOrCreateNotLabeled().setTrue(); // changes to true

            // nameAndUnit - no observation, stays false and not reported

            nameDescriptionAndUnit.getOrCreateNotLabeled().setTrue();
            nameDescriptionAndUnit.getOrCreateNotLabeled().setTrue(); // changes to true

            // trueInit - no observation, stays true and to be reported

            constLabel.getOrCreateNotLabeled().setTrue(); // changes to true

            dynamicLabel.getOrCreateLabeled("d1", "d1_v1").setTrue(); // new data point
            dynamicLabel.getOrCreateLabeled("d1", "d1_v1").setTrue(); // stays true
            dynamicLabel.getOrCreateLabeled("d1", "d1_v2").setFalse(); // new data point

            manyLabels.getOrCreateLabeled("d1", "d1_v1", "d2", "d2_v1").setFalse(); // new data point
            manyLabels.getOrCreateLabeled("d1", "d1_v1", "d2", "d2_v1").setTrue(); // changes to true

            context.exportAndVerify(
                    """
                # TYPE only_name gauge
                only_name 0
                # TYPE bool:name_category gauge
                bool:name_category 0
                # TYPE name_description gauge
                # HELP name_description Boolean gauge with description
                name_description 1
                # TYPE name_unit_bool_unit gauge
                # UNIT name_unit_bool_unit bool_unit
                # TYPE name_description_unit_bool_unit gauge
                # UNIT name_description_unit_bool_unit bool_unit
                # HELP name_description_unit_bool_unit Boolean gauge with description and unit
                name_description_unit_bool_unit 1
                # TYPE true_init gauge
                # TYPE const_label gauge
                const_label{c1="c1_v1"} 1
                # TYPE dynamic_label gauge
                dynamic_label{d1="d1_v1"} 1
                dynamic_label{d1="d1_v2"} 0
                # TYPE many_labels gauge
                many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v1"} 1
                # EOF
                """);
        }

        @Test
        @Order(3)
        public void testObserve2() throws IOException {
            onlyName.getOrCreateNotLabeled().setTrue(); // changes to true

            nameAndCategory.getOrCreateNotLabeled().setTrue(); // changes to true

            nameAndDescription.getOrCreateNotLabeled().setFalse(); // changes to false

            nameAndUnit.getOrCreateNotLabeled().setTrue(); // changes to true

            // nameAndDescriptionAndUnit - no observation, stays true

            trueInit.getOrCreateNotLabeled().setFalse();
            trueInit.getOrCreateNotLabeled().setTrue();
            trueInit.getOrCreateNotLabeled().setFalse(); // changes to false

            // constLabel - no observation, stays true

            dynamicLabel.getOrCreateLabeled("d1", "d1_v1").setFalse();
            dynamicLabel.getOrCreateLabeled("d1", "d1_v1").setFalse(); // changes to false
            // no change for d1_v2, stays false
            dynamicLabel.getOrCreateLabeled("d1", "d1_v3").setTrue(); // new vdata point

            // no changes for d1_v1, d2_v1
            manyLabels.getOrCreateLabeled("d1", "d1_v2", "d2", "d2_v1").setTrue(); // new data point
            manyLabels.getOrCreateLabeled("d1", "d1_v1", "d2", "d2_v2").setFalse(); // changes to false
            manyLabels.getOrCreateLabeled("d1", "d1_v1", "d2", "d2_v2").setTrue(); // changes to true
            manyLabels.getOrCreateLabeled("d1", "d1_v1", "d2", "d2_v2").set(false); // changes to false

            context.exportAndVerify(
                    """
                # TYPE only_name gauge
                only_name 1
                # TYPE bool:name_category gauge
                bool:name_category 1
                # TYPE name_description gauge
                # HELP name_description Boolean gauge with description
                name_description 0
                # TYPE name_unit_bool_unit gauge
                # UNIT name_unit_bool_unit bool_unit
                name_unit_bool_unit 1
                # TYPE name_description_unit_bool_unit gauge
                # UNIT name_description_unit_bool_unit bool_unit
                # HELP name_description_unit_bool_unit Boolean gauge with description and unit
                name_description_unit_bool_unit 1
                # TYPE true_init gauge
                true_init 0
                # TYPE const_label gauge
                const_label{c1="c1_v1"} 1
                # TYPE dynamic_label gauge
                dynamic_label{d1="d1_v1"} 0
                dynamic_label{d1="d1_v2"} 0
                dynamic_label{d1="d1_v3"} 1
                # TYPE many_labels gauge
                many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v1"} 1
                many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v2",d2="d2_v1"} 1
                many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v2"} 0
                # EOF
                """);
        }

        @Test
        @Order(4)
        public void testNoChangesWithoutObservation() throws IOException {
            context.exportAndVerify(
                    """
                # TYPE only_name gauge
                only_name 1
                # TYPE bool:name_category gauge
                bool:name_category 1
                # TYPE name_description gauge
                # HELP name_description Boolean gauge with description
                name_description 0
                # TYPE name_unit_bool_unit gauge
                # UNIT name_unit_bool_unit bool_unit
                name_unit_bool_unit 1
                # TYPE name_description_unit_bool_unit gauge
                # UNIT name_description_unit_bool_unit bool_unit
                # HELP name_description_unit_bool_unit Boolean gauge with description and unit
                name_description_unit_bool_unit 1
                # TYPE true_init gauge
                true_init 0
                # TYPE const_label gauge
                const_label{c1="c1_v1"} 1
                # TYPE dynamic_label gauge
                dynamic_label{d1="d1_v1"} 0
                dynamic_label{d1="d1_v2"} 0
                dynamic_label{d1="d1_v3"} 1
                # TYPE many_labels gauge
                many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v1"} 1
                many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v2",d2="d2_v1"} 1
                many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v2"} 0
                # EOF
                """);
        }
    }

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DoubleCounterTest {

        private static final TestExporterContext context = new TestExporterContext(OpenMetricsSnapshotsWriter.DEFAULT);

        private static DoubleCounter onlyName;
        private static DoubleCounter nameAndCategory;
        private static DoubleCounter nameAndDescription;
        private static DoubleCounter nameAndUnit;
        private static DoubleCounter nameDescriptionAndUnit;
        private static DoubleCounter customInit;
        private static DoubleCounter constLabel;
        private static DoubleCounter dynamicLabel;
        private static DoubleCounter manyLabels;

        @Test
        @Order(1)
        public void testInitWithoutObservation() throws IOException {
            onlyName = DoubleCounter.builder("only_name").register(context.getRegistry());
            nameAndCategory = DoubleCounter.builder(
                            DoubleCounter.key("name_category").withCategory("cnt"))
                    .register(context.getRegistry());
            nameAndDescription = DoubleCounter.builder("name_description")
                    .withDescription("Double counter with description")
                    .register(context.getRegistry());
            nameAndUnit =
                    DoubleCounter.builder("name_unit").withUnit("requests").register(context.getRegistry());
            nameDescriptionAndUnit = DoubleCounter.builder("name_description_unit")
                    .withDescription("Double counter with description and unit")
                    .withUnit("requests")
                    .register(context.getRegistry());
            customInit = DoubleCounter.builder("custom_init").withInitValue(1.1).register(context.getRegistry());
            constLabel = DoubleCounter.builder("const_label")
                    .withConstantLabel(new Label("c1", "c1_v1"))
                    .register(context.getRegistry());
            dynamicLabel = DoubleCounter.builder("dynamic_label")
                    .withDynamicLabelNames("d1")
                    .register(context.getRegistry());
            manyLabels = DoubleCounter.builder("many_labels")
                    .withConstantLabels(new Label("c1", "c1_v1"), new Label("c2", "c2_v1"))
                    .withDynamicLabelNames("d1", "d2")
                    .register(context.getRegistry());

            context.exportAndVerify(
                    """
                # TYPE only_name counter
                # TYPE cnt:name_category counter
                # TYPE name_description counter
                # HELP name_description Double counter with description
                # TYPE name_unit_requests counter
                # UNIT name_unit_requests requests
                # TYPE name_description_unit_requests counter
                # UNIT name_description_unit_requests requests
                # HELP name_description_unit_requests Double counter with description and unit
                # TYPE custom_init counter
                # TYPE const_label counter
                # TYPE dynamic_label counter
                # TYPE many_labels counter
                # EOF
                """);
        }

        @Test
        @Order(2)
        public void testObserve1() throws IOException {
            onlyName.getOrCreateNotLabeled().increment(0.0); // stays 0

            nameAndCategory.getOrCreateNotLabeled().increment(); // +1, changes to 1

            nameAndDescription.getOrCreateNotLabeled().increment(1.75); // +1.75, changes to 1.75

            // nameAndUnit - no observation, stays 0 and not reported

            nameDescriptionAndUnit.getOrCreateNotLabeled().increment();
            nameDescriptionAndUnit.getOrCreateNotLabeled().increment(1.123); // +2.123, changes to 2.123

            // customInit - no observation, stays 1.1 and not reported

            constLabel.getOrCreateNotLabeled().increment(10.234);
            constLabel.getOrCreateNotLabeled().increment(); // +11.234, changes to 10.234

            dynamicLabel.getOrCreateLabeled("d1", "d1_v1").increment(); // new data point, changes to 1
            dynamicLabel.getOrCreateLabeled("d1", "d1_v1").increment(0.01); // changes to 1.01
            dynamicLabel.getOrCreateLabeled("d1", "d1_v2").increment(0); // new data point, stays 0

            manyLabels
                    .getOrCreateLabeled("d1", "d1_v1", "d2", "d2_v1")
                    .increment(1.99); // new data point, changes to 1.99
            manyLabels.getOrCreateLabeled("d1", "d1_v1", "d2", "d2_v1").increment(1.01); // changes to 3.0

            context.exportAndVerify(
                    """
                # TYPE only_name counter
                only_name_total 0
                # TYPE cnt:name_category counter
                cnt:name_category_total 1
                # TYPE name_description counter
                # HELP name_description Double counter with description
                name_description_total 1.75
                # TYPE name_unit_requests counter
                # UNIT name_unit_requests requests
                # TYPE name_description_unit_requests counter
                # UNIT name_description_unit_requests requests
                # HELP name_description_unit_requests Double counter with description and unit
                name_description_unit_requests_total 2.123
                # TYPE custom_init counter
                # TYPE const_label counter
                const_label_total{c1="c1_v1"} 11.234
                # TYPE dynamic_label counter
                dynamic_label_total{d1="d1_v1"} 1.01
                dynamic_label_total{d1="d1_v2"} 0
                # TYPE many_labels counter
                many_labels_total{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v1"} 3
                # EOF
                """);
        }

        @Test
        @Order(3)
        public void testObserve2() throws IOException {
            onlyName.getOrCreateNotLabeled().increment(0.001); // changes to 0.001

            nameAndCategory.getOrCreateNotLabeled().increment(0.001); // changes to 1.001

            // no observation to nameAndDescription - stays 1.75

            nameAndUnit.getOrCreateNotLabeled().increment(1.123);
            nameAndUnit.getOrCreateNotLabeled().increment(1.123);

            nameDescriptionAndUnit.getOrCreateNotLabeled().increment(1.123);
            nameDescriptionAndUnit.getOrCreateNotLabeled().increment(); // +2.123, changes to 4.246

            customInit.getOrCreateNotLabeled().increment(0.001); // changes to 1.101

            // no observation to constLabel - stays 11.234

            dynamicLabel.getOrCreateLabeled("d1", "d1_v2").increment(0.01); // changes to 0.01
            dynamicLabel.getOrCreateLabeled("d1", "d1_v2").increment(0.04); // changes to 0.05
            dynamicLabel.getOrCreateLabeled("d1", "d1_v3").increment(0); // new data point, stays 0
            dynamicLabel.getOrCreateLabeled("d1", "d1_v3").increment(0); // stays 0

            manyLabels
                    .getOrCreateLabeled("d1", "d1_v1", "d2", "d2_v2")
                    .increment(1.9); // new data point, changes to 1.9

            context.exportAndVerify(
                    """
                 # TYPE only_name counter
                 only_name_total 0.001
                 # TYPE cnt:name_category counter
                 cnt:name_category_total 1.001
                 # TYPE name_description counter
                 # HELP name_description Double counter with description
                 name_description_total 1.75
                 # TYPE name_unit_requests counter
                 # UNIT name_unit_requests requests
                 name_unit_requests_total 2.246
                 # TYPE name_description_unit_requests counter
                 # UNIT name_description_unit_requests requests
                 # HELP name_description_unit_requests Double counter with description and unit
                 name_description_unit_requests_total 4.246
                 # TYPE custom_init counter
                 custom_init_total 1.101
                 # TYPE const_label counter
                 const_label_total{c1="c1_v1"} 11.234
                 # TYPE dynamic_label counter
                 dynamic_label_total{d1="d1_v1"} 1.01
                 dynamic_label_total{d1="d1_v2"} 0.05
                 dynamic_label_total{d1="d1_v3"} 0
                 # TYPE many_labels counter
                 many_labels_total{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v1"} 3
                 many_labels_total{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v2"} 1.9
                 # EOF
                 """);
        }

        @Test
        @Order(4)
        public void testNoChangesWithoutObservation() throws IOException {
            context.exportAndVerify(
                    """
                 # TYPE only_name counter
                 only_name_total 0.001
                 # TYPE cnt:name_category counter
                 cnt:name_category_total 1.001
                 # TYPE name_description counter
                 # HELP name_description Double counter with description
                 name_description_total 1.75
                 # TYPE name_unit_requests counter
                 # UNIT name_unit_requests requests
                 name_unit_requests_total 2.246
                 # TYPE name_description_unit_requests counter
                 # UNIT name_description_unit_requests requests
                 # HELP name_description_unit_requests Double counter with description and unit
                 name_description_unit_requests_total 4.246
                 # TYPE custom_init counter
                 custom_init_total 1.101
                 # TYPE const_label counter
                 const_label_total{c1="c1_v1"} 11.234
                 # TYPE dynamic_label counter
                 dynamic_label_total{d1="d1_v1"} 1.01
                 dynamic_label_total{d1="d1_v2"} 0.05
                 dynamic_label_total{d1="d1_v3"} 0
                 # TYPE many_labels counter
                 many_labels_total{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v1"} 3
                 many_labels_total{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v2"} 1.9
                 # EOF
                 """);
        }
    }

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class LongCounterTest {

        private static final TestExporterContext context = new TestExporterContext(OpenMetricsSnapshotsWriter.DEFAULT);

        private static LongCounter onlyName;
        private static LongCounter nameAndCategory;
        private static LongCounter nameAndDescription;
        private static LongCounter nameAndUnit;
        private static LongCounter nameDescriptionAndUnit;
        private static LongCounter customInit;
        private static LongCounter constLabel;
        private static LongCounter dynamicLabel;
        private static LongCounter manyLabels;

        @Test
        @Order(1)
        public void testInitWithoutObservation() throws IOException {
            onlyName = LongCounter.builder("only_name").register(context.getRegistry());
            nameAndCategory = LongCounter.builder(
                            LongCounter.key("name_category").withCategory("cnt"))
                    .register(context.getRegistry());
            nameAndDescription = LongCounter.builder("name_description")
                    .withDescription("Long counter with description")
                    .register(context.getRegistry());
            nameAndUnit = LongCounter.builder("name_unit").withUnit("requests").register(context.getRegistry());
            nameDescriptionAndUnit = LongCounter.builder("name_description_unit")
                    .withDescription("Long counter with description and unit")
                    .withUnit("requests")
                    .register(context.getRegistry());
            customInit = LongCounter.builder("custom_init").withInitValue(10).register(context.getRegistry());
            constLabel = LongCounter.builder("const_label")
                    .withConstantLabel(new Label("c1", "c1_v1"))
                    .register(context.getRegistry());
            dynamicLabel = LongCounter.builder("dynamic_label")
                    .withDynamicLabelNames("d1")
                    .register(context.getRegistry());
            manyLabels = LongCounter.builder("many_labels")
                    .withConstantLabels(new Label("c1", "c1_v1"), new Label("c2", "c2_v1"))
                    .withDynamicLabelNames("d1", "d2")
                    .register(context.getRegistry());

            context.exportAndVerify(
                    """
                # TYPE only_name counter
                # TYPE cnt:name_category counter
                # TYPE name_description counter
                # HELP name_description Long counter with description
                # TYPE name_unit_requests counter
                # UNIT name_unit_requests requests
                # TYPE name_description_unit_requests counter
                # UNIT name_description_unit_requests requests
                # HELP name_description_unit_requests Long counter with description and unit
                # TYPE custom_init counter
                # TYPE const_label counter
                # TYPE dynamic_label counter
                # TYPE many_labels counter
                # EOF
                """);
        }

        @Test
        @Order(2)
        public void testObserve1() throws IOException {
            onlyName.getOrCreateNotLabeled().increment(0); // stays 0

            nameAndCategory.getOrCreateNotLabeled().increment(); // +1, changes to 1

            nameAndDescription.getOrCreateNotLabeled().increment(2); // +2 changes to 2

            // nameAndUnit - no observation, stays 0 and not reported

            nameDescriptionAndUnit.getOrCreateNotLabeled().increment();
            nameDescriptionAndUnit.getOrCreateNotLabeled().increment(4); // +5, changes to 5

            // customInit - no observation, stays 10 and not reported

            constLabel.getOrCreateNotLabeled().increment(10);
            constLabel.getOrCreateNotLabeled().increment(); // +11, changes to 11

            dynamicLabel.getOrCreateLabeled("d1", "d1_v1").increment(); // new data point, changes to 1
            dynamicLabel.getOrCreateLabeled("d1", "d1_v1").increment(3); // changes to 4
            dynamicLabel.getOrCreateLabeled("d1", "d1_v2").increment(0); // new data point, stays 0

            manyLabels.getOrCreateLabeled("d1", "d1_v1", "d2", "d2_v1").increment(2); // new data point, changes to 2
            manyLabels.getOrCreateLabeled("d1", "d1_v1", "d2", "d2_v1").increment(1); // changes to 3

            context.exportAndVerify(
                    """
                # TYPE only_name counter
                only_name_total 0
                # TYPE cnt:name_category counter
                cnt:name_category_total 1
                # TYPE name_description counter
                # HELP name_description Long counter with description
                name_description_total 2
                # TYPE name_unit_requests counter
                # UNIT name_unit_requests requests
                # TYPE name_description_unit_requests counter
                # UNIT name_description_unit_requests requests
                # HELP name_description_unit_requests Long counter with description and unit
                name_description_unit_requests_total 5
                # TYPE custom_init counter
                # TYPE const_label counter
                const_label_total{c1="c1_v1"} 11
                # TYPE dynamic_label counter
                dynamic_label_total{d1="d1_v1"} 4
                dynamic_label_total{d1="d1_v2"} 0
                # TYPE many_labels counter
                many_labels_total{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v1"} 3
                # EOF
                """);
        }

        @Test
        @Order(3)
        public void testObserve2() throws IOException {
            onlyName.getOrCreateNotLabeled().increment(); // changes to 1

            nameAndCategory.getOrCreateNotLabeled().increment(3); // changes to 4

            // no observation to nameAndDescription - stays 5

            nameAndUnit.getOrCreateNotLabeled().increment(2);
            nameAndUnit.getOrCreateNotLabeled().increment(8); // +10, changes to 10

            nameDescriptionAndUnit.getOrCreateNotLabeled().increment(1);
            nameDescriptionAndUnit.getOrCreateNotLabeled().increment(); // +2 changes to 7

            customInit.getOrCreateNotLabeled().increment(); // changes to 11

            // no observation to constLabel - stays 11

            dynamicLabel.getOrCreateLabeled("d1", "d1_v2").increment(1); // changes to 1
            dynamicLabel.getOrCreateLabeled("d1", "d1_v2").increment(); // changes to 2
            dynamicLabel.getOrCreateLabeled("d1", "d1_v3").increment(0); // new data point, stays 0
            dynamicLabel.getOrCreateLabeled("d1", "d1_v3").increment(0); // stays 0

            manyLabels.getOrCreateLabeled("d1", "d1_v1", "d2", "d2_v2").increment(2); // new data point, changes to 2
            manyLabels.getOrCreateLabeled("d1", "d1_v2", "d2", "d2_v1").increment(); // new data point, changes to 1

            context.exportAndVerify(
                    """
                # TYPE only_name counter
                only_name_total 1
                # TYPE cnt:name_category counter
                cnt:name_category_total 4
                # TYPE name_description counter
                # HELP name_description Long counter with description
                name_description_total 2
                # TYPE name_unit_requests counter
                # UNIT name_unit_requests requests
                name_unit_requests_total 10
                # TYPE name_description_unit_requests counter
                # UNIT name_description_unit_requests requests
                # HELP name_description_unit_requests Long counter with description and unit
                name_description_unit_requests_total 7
                # TYPE custom_init counter
                custom_init_total 11
                # TYPE const_label counter
                const_label_total{c1="c1_v1"} 11
                # TYPE dynamic_label counter
                dynamic_label_total{d1="d1_v1"} 4
                dynamic_label_total{d1="d1_v2"} 2
                dynamic_label_total{d1="d1_v3"} 0
                # TYPE many_labels counter
                many_labels_total{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v1"} 3
                many_labels_total{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v2"} 2
                many_labels_total{c1="c1_v1",c2="c2_v1",d1="d1_v2",d2="d2_v1"} 1
                # EOF
                """);
        }

        @Test
        @Order(4)
        public void testNoChangesWithoutObservation() throws IOException {
            context.exportAndVerify(
                    """
                # TYPE only_name counter
                only_name_total 1
                # TYPE cnt:name_category counter
                cnt:name_category_total 4
                # TYPE name_description counter
                # HELP name_description Long counter with description
                name_description_total 2
                # TYPE name_unit_requests counter
                # UNIT name_unit_requests requests
                name_unit_requests_total 10
                # TYPE name_description_unit_requests counter
                # UNIT name_description_unit_requests requests
                # HELP name_description_unit_requests Long counter with description and unit
                name_description_unit_requests_total 7
                # TYPE custom_init counter
                custom_init_total 11
                # TYPE const_label counter
                const_label_total{c1="c1_v1"} 11
                # TYPE dynamic_label counter
                dynamic_label_total{d1="d1_v1"} 4
                dynamic_label_total{d1="d1_v2"} 2
                dynamic_label_total{d1="d1_v3"} 0
                # TYPE many_labels counter
                many_labels_total{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v1"} 3
                many_labels_total{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v2"} 2
                many_labels_total{c1="c1_v1",c2="c2_v1",d1="d1_v2",d2="d2_v1"} 1
                # EOF
                """);
        }
    }

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class DoubleGaugeTest {

        private static final TestExporterContext context = new TestExporterContext(OpenMetricsSnapshotsWriter.DEFAULT);

        private static DoubleGauge onlyName;
        private static DoubleGauge nameAndDescriptionMaxReset;
        private static DoubleGauge nameAndUnitMinReset;
        private static DoubleGauge nameDescriptionAndUnitAggNoReset;
        private static DoubleGauge customInitAggReset;
        private static DoubleGauge constLabel;
        private static DoubleGauge dynamicLabelSumReset;
        private static DoubleGauge manyLabelsSumNoReset;

        @Test
        @Order(1)
        public void testInitWithoutObservation() throws IOException {
            onlyName = DoubleGauge.builder("only_name").register(context.getRegistry());
            nameAndDescriptionMaxReset = DoubleGauge.builder(
                            DoubleGauge.key("name_description").withCategory("max_reset"))
                    .withDescription("Double gauge with description")
                    .withTrackingMaxSpike()
                    .register(context.getRegistry());
            nameAndUnitMinReset = DoubleGauge.builder(
                            DoubleGauge.key("name_unit").withCategory("min_reset"))
                    .withUnit("currency")
                    .withTrackingMinSpike()
                    .register(context.getRegistry());
            nameDescriptionAndUnitAggNoReset = DoubleGauge.builder(
                            DoubleGauge.key("name_description_unit").withCategory("agg_no_reset"))
                    .withDescription("Double gauge with description and unit")
                    .withOperator((prev, cur) -> prev + cur + 0.01, false) // sum + 0.01
                    .withUnit("currency")
                    .register(context.getRegistry());
            customInitAggReset = DoubleGauge.builder(
                            DoubleGauge.key("custom_init").withCategory("agg_reset"))
                    .withInitValue(0.001)
                    .withOperator((prev, cur) -> prev + cur + 0.01, true) // sum + 0.01
                    .register(context.getRegistry());
            constLabel = DoubleGauge.builder("const_label")
                    .withConstantLabel(new Label("c1", "c1_v1"))
                    .register(context.getRegistry());
            dynamicLabelSumReset = DoubleGauge.builder(
                            DoubleGauge.key("dynamic_label").withCategory("sum_reset"))
                    .withOperator(StatUtils.DOUBLE_SUM, true)
                    .withDynamicLabelNames("d1")
                    .register(context.getRegistry());
            manyLabelsSumNoReset = DoubleGauge.builder(
                            DoubleGauge.key("many_labels").withCategory("sum_no_reset"))
                    .withOperator(StatUtils.DOUBLE_SUM, false)
                    .withConstantLabels(new Label("c1", "c1_v1"), new Label("c2", "c2_v1"))
                    .withDynamicLabelNames("d1", "d2")
                    .register(context.getRegistry());

            context.exportAndVerify(
                    """
                    # TYPE only_name gauge
                    # TYPE max_reset:name_description gauge
                    # HELP max_reset:name_description Double gauge with description
                    # TYPE min_reset:name_unit_currency gauge
                    # UNIT min_reset:name_unit_currency currency
                    # TYPE agg_no_reset:name_description_unit_currency gauge
                    # UNIT agg_no_reset:name_description_unit_currency currency
                    # HELP agg_no_reset:name_description_unit_currency Double gauge with description and unit
                    # TYPE agg_reset:custom_init gauge
                    # TYPE const_label gauge
                    # TYPE sum_reset:dynamic_label gauge
                    # TYPE sum_no_reset:many_labels gauge
                    # EOF
                    """);
        }

        @Test
        @Order(2)
        public void testObserve1() throws IOException {
            onlyName.getOrCreateNotLabeled().update(0.0);
            onlyName.getOrCreateNotLabeled().update(0.0); // stays 0.0

            nameAndDescriptionMaxReset.getOrCreateNotLabeled().update(2.0);
            nameAndDescriptionMaxReset.getOrCreateNotLabeled().update(3.0);
            nameAndDescriptionMaxReset.getOrCreateNotLabeled().update(2.99); // becomes 3.0 (max)

            nameAndUnitMinReset.getOrCreateNotLabeled().update(2.0);
            nameAndUnitMinReset.getOrCreateNotLabeled().update(-1.01);
            nameAndUnitMinReset.getOrCreateNotLabeled().update(-1.0); // becomes -1.01 (min)

            nameDescriptionAndUnitAggNoReset.getOrCreateNotLabeled().update(0.0);
            nameDescriptionAndUnitAggNoReset.getOrCreateNotLabeled().update(1.1);
            nameDescriptionAndUnitAggNoReset.getOrCreateNotLabeled().update(0.1); // becomes 1.23

            customInitAggReset.getOrCreateNotLabeled().update(0.0);
            customInitAggReset.getOrCreateNotLabeled().update(1.1);
            customInitAggReset.getOrCreateNotLabeled().update(0.1); // becomes 1.231

            // no observation to constLabel - stays NaN and not reported

            dynamicLabelSumReset.getOrCreateLabeled("d1", "d1_v1").update(); // new data point, changes to 1
            dynamicLabelSumReset.getOrCreateLabeled("d1", "d1_v1").update(3.1); // changes to 4.1
            dynamicLabelSumReset.getOrCreateLabeled("d1", "d1_v2").update(0); // new data point, stays 0

            manyLabelsSumNoReset
                    .getOrCreateLabeled("d1", "d1_v1", "d2", "d2_v1")
                    .update(2.1); // new data point, changes to 2.1
            manyLabelsSumNoReset
                    .getOrCreateLabeled("d1", "d1_v1", "d2", "d2_v1")
                    .update(2.9); // changes to 5
            manyLabelsSumNoReset
                    .getOrCreateLabeled("d1", "d1_v1", "d2", "d2_v1")
                    .update(); // changes to 6

            context.exportAndVerify(
                    """
                    # TYPE only_name gauge
                    only_name 0
                    # TYPE max_reset:name_description gauge
                    # HELP max_reset:name_description Double gauge with description
                    max_reset:name_description 3
                    # TYPE min_reset:name_unit_currency gauge
                    # UNIT min_reset:name_unit_currency currency
                    min_reset:name_unit_currency -1.01
                    # TYPE agg_no_reset:name_description_unit_currency gauge
                    # UNIT agg_no_reset:name_description_unit_currency currency
                    # HELP agg_no_reset:name_description_unit_currency Double gauge with description and unit
                    agg_no_reset:name_description_unit_currency 1.23
                    # TYPE agg_reset:custom_init gauge
                    agg_reset:custom_init 1.231
                    # TYPE const_label gauge
                    # TYPE sum_reset:dynamic_label gauge
                    sum_reset:dynamic_label{d1="d1_v1"} 4.1
                    sum_reset:dynamic_label{d1="d1_v2"} 0
                    # TYPE sum_no_reset:many_labels gauge
                    sum_no_reset:many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v1"} 6
                    # EOF
                    """);
        }

        @Test
        @Order(2)
        public void testObserve2() throws IOException {
            onlyName.getOrCreateNotLabeled().update(1.1);
            onlyName.getOrCreateNotLabeled().update(Double.NEGATIVE_INFINITY);

            nameAndDescriptionMaxReset.getOrCreateNotLabeled().update(-2.1);
            nameAndDescriptionMaxReset.getOrCreateNotLabeled().update(-1.01);
            nameAndDescriptionMaxReset.getOrCreateNotLabeled().update(-1.02); // becomes -1.01 (max)

            nameAndUnitMinReset.getOrCreateNotLabeled().update(2.6);
            nameAndUnitMinReset.getOrCreateNotLabeled().update(2.5);
            nameAndUnitMinReset.getOrCreateNotLabeled().update(2.51); // becomes 2.5 (min)

            nameDescriptionAndUnitAggNoReset.getOrCreateNotLabeled().update(); // becomes 2.24
            nameDescriptionAndUnitAggNoReset.getOrCreateNotLabeled().update(-1.1); // becomes 1.15

            customInitAggReset.getOrCreateNotLabeled().update(); // becomes 1.011
            customInitAggReset.getOrCreateNotLabeled().update(-1.011); // becomes 0.01

            constLabel.getOrCreateNotLabeled().update(Double.POSITIVE_INFINITY);

            dynamicLabelSumReset.getOrCreateLabeled("d1", "d1_v2").update(1.1); // changes to 1,1

            manyLabelsSumNoReset
                    .getOrCreateLabeled("d1", "d1_v1", "d2", "d2_v1")
                    .update(); // becomes 7
            manyLabelsSumNoReset
                    .getOrCreateLabeled("d1", "d1_v1", "d2", "d2_v2")
                    .update(Double.NaN); // new data point
            manyLabelsSumNoReset
                    .getOrCreateLabeled("d1", "d1_v2", "d2", "d2_v1")
                    .update(Double.POSITIVE_INFINITY); // new data point
            manyLabelsSumNoReset
                    .getOrCreateLabeled("d1", "d1_v2", "d2", "d2_v2")
                    .update(Double.NEGATIVE_INFINITY); // new data point
            manyLabelsSumNoReset
                    .getOrCreateLabeled("d1", "d1_v3", "d2", "d2_v1")
                    .update(); // new data point

            context.exportAndVerify(
                    """
                    # TYPE only_name gauge
                    only_name -Inf
                    # TYPE max_reset:name_description gauge
                    # HELP max_reset:name_description Double gauge with description
                    max_reset:name_description -1.01
                    # TYPE min_reset:name_unit_currency gauge
                    # UNIT min_reset:name_unit_currency currency
                    min_reset:name_unit_currency 2.5
                    # TYPE agg_no_reset:name_description_unit_currency gauge
                    # UNIT agg_no_reset:name_description_unit_currency currency
                    # HELP agg_no_reset:name_description_unit_currency Double gauge with description and unit
                    agg_no_reset:name_description_unit_currency 1.15
                    # TYPE agg_reset:custom_init gauge
                    agg_reset:custom_init 0.01
                    # TYPE const_label gauge
                    const_label{c1="c1_v1"} +Inf
                    # TYPE sum_reset:dynamic_label gauge
                    sum_reset:dynamic_label{d1="d1_v1"} 0
                    sum_reset:dynamic_label{d1="d1_v2"} 1.1
                    # TYPE sum_no_reset:many_labels gauge
                    sum_no_reset:many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v1"} 7
                    sum_no_reset:many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v2"} NaN
                    sum_no_reset:many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v2",d2="d2_v1"} +Inf
                    sum_no_reset:many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v2",d2="d2_v2"} -Inf
                    sum_no_reset:many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v3",d2="d2_v1"} 1
                    # EOF
                    """);
        }

        @Test
        @Order(4)
        public void testChangesWithoutObservation() throws IOException {
            // all aggregations expected to be reset where applicable
            context.exportAndVerify(
                    """
                    # TYPE only_name gauge
                    only_name -Inf
                    # TYPE max_reset:name_description gauge
                    # HELP max_reset:name_description Double gauge with description
                    max_reset:name_description -Inf
                    # TYPE min_reset:name_unit_currency gauge
                    # UNIT min_reset:name_unit_currency currency
                    min_reset:name_unit_currency +Inf
                    # TYPE agg_no_reset:name_description_unit_currency gauge
                    # UNIT agg_no_reset:name_description_unit_currency currency
                    # HELP agg_no_reset:name_description_unit_currency Double gauge with description and unit
                    agg_no_reset:name_description_unit_currency 1.15
                    # TYPE agg_reset:custom_init gauge
                    agg_reset:custom_init 0.001
                    # TYPE const_label gauge
                    const_label{c1="c1_v1"} +Inf
                    # TYPE sum_reset:dynamic_label gauge
                    sum_reset:dynamic_label{d1="d1_v1"} 0
                    sum_reset:dynamic_label{d1="d1_v2"} 0
                    # TYPE sum_no_reset:many_labels gauge
                    sum_no_reset:many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v1"} 7
                    sum_no_reset:many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v2"} NaN
                    sum_no_reset:many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v2",d2="d2_v1"} +Inf
                    sum_no_reset:many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v2",d2="d2_v2"} -Inf
                    sum_no_reset:many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v3",d2="d2_v1"} 1
                    # EOF
                    """);
        }
    }

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class StatelessMetricTest {

        private static final TestExporterContext context = new TestExporterContext(OpenMetricsSnapshotsWriter.DEFAULT);

        private static StatelessMetric onlyName;
        private static StatelessMetric constLabel;
        private static StatelessMetric dynamicLabel;
        private static StatelessMetric manyLabels;

        private static final AtomicDouble nameDescriptionAndUnitContainer = new AtomicDouble(0);
        private static final AtomicDouble dynamicLabelContainer1 = new AtomicDouble(0);
        private static final AtomicLong dynamicLabelContainer2 = new AtomicLong(0);
        private static final AtomicDouble manyLabelsContainer1 = new AtomicDouble(0);
        private static final AtomicDouble manyLabelsContainer2 = new AtomicDouble(0);

        @Test
        @Order(1)
        public void testInitWithoutObservation() throws IOException {
            onlyName = StatelessMetric.builder("only_name").register(context.getRegistry()); // no data points yet
            StatelessMetric.builder(StatelessMetric.key("name_category").withCategory("cnt"))
                    .registerDataPoint(() -> 1.0)
                    .register(context.getRegistry());
            StatelessMetric.builder("name_description")
                    .withDescription("Stateless with description")
                    .registerDataPoint(() -> 2.0)
                    .register(context.getRegistry());
            StatelessMetric.builder("name_unit")
                    .withUnit("requests")
                    .registerDataPoint(() -> 3.0)
                    .register(context.getRegistry());
            StatelessMetric.builder("name_description_unit")
                    .withDescription("Stateless with description and unit")
                    .withUnit("requests")
                    .registerDataPoint(nameDescriptionAndUnitContainer)
                    .register(context.getRegistry());
            constLabel = StatelessMetric.builder("const_label")
                    .withConstantLabel(new Label("c1", "c1_v1"))
                    .register(context.getRegistry());
            dynamicLabel = StatelessMetric.builder("dynamic_label")
                    .withDynamicLabelNames("d1")
                    .register(context.getRegistry());
            manyLabels = StatelessMetric.builder("many_labels")
                    .withConstantLabels(new Label("c1", "c1_v1"), new Label("c2", "c2_v1"))
                    .withDynamicLabelNames("d1", "d2")
                    .register(context.getRegistry());

            context.exportAndVerify(
                    """
                    # TYPE only_name gauge
                    # TYPE cnt:name_category gauge
                    cnt:name_category 1
                    # TYPE name_description gauge
                    # HELP name_description Stateless with description
                    name_description 2
                    # TYPE name_unit_requests gauge
                    # UNIT name_unit_requests requests
                    name_unit_requests 3
                    # TYPE name_description_unit_requests gauge
                    # UNIT name_description_unit_requests requests
                    # HELP name_description_unit_requests Stateless with description and unit
                    name_description_unit_requests 0
                    # TYPE const_label gauge
                    # TYPE dynamic_label gauge
                    # TYPE many_labels gauge
                    # EOF
                    """);
        }

        @Test
        @Order(2)
        public void testObserve1() throws IOException {
            onlyName.registerDataPoint(new LongOrDoubleSupplier(() -> 1.1));

            nameDescriptionAndUnitContainer.set(1.89);
            nameDescriptionAndUnitContainer.set(1.99); // becomes 1.99

            AtomicDouble constLabelContainer = new AtomicDouble(10.234);
            constLabelContainer.set(0.01);
            constLabel.registerDataPoint(new LongOrDoubleSupplier(constLabelContainer));

            dynamicLabelContainer2.set(10);
            dynamicLabel.registerDataPoint(new LongOrDoubleSupplier(dynamicLabelContainer1), "d1", "d1_v1");
            dynamicLabel.registerDataPoint(new LongOrDoubleSupplier(dynamicLabelContainer2::longValue), "d1", "d1_v2");

            manyLabels.registerDataPoint(new LongOrDoubleSupplier(manyLabelsContainer1), "d1", "d1_v1", "d2", "d2_v1");
            manyLabelsContainer1.set(3.14);

            context.exportAndVerify(
                    """
                        # TYPE only_name gauge
                        only_name 1.1
                        # TYPE cnt:name_category gauge
                        cnt:name_category 1
                        # TYPE name_description gauge
                        # HELP name_description Stateless with description
                        name_description 2
                        # TYPE name_unit_requests gauge
                        # UNIT name_unit_requests requests
                        name_unit_requests 3
                        # TYPE name_description_unit_requests gauge
                        # UNIT name_description_unit_requests requests
                        # HELP name_description_unit_requests Stateless with description and unit
                        name_description_unit_requests 1.99
                        # TYPE const_label gauge
                        const_label{c1="c1_v1"} 0.01
                        # TYPE dynamic_label gauge
                        dynamic_label{d1="d1_v1"} 0
                        dynamic_label{d1="d1_v2"} 10
                        # TYPE many_labels gauge
                        many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v1"} 3.14
                        # EOF
                        """);
        }

        @Test
        @Order(3)
        public void testObserve2() throws IOException {
            nameDescriptionAndUnitContainer.set(-1.99); // becomes -1.99

            dynamicLabelContainer1.set(0.01);
            dynamicLabelContainer2.set(42);

            manyLabels.registerDataPoint(new LongOrDoubleSupplier(manyLabelsContainer2), "d1", "d1_v1", "d2", "d2_v2");
            manyLabelsContainer2.set(1.9);

            context.exportAndVerify(
                    """
                    # TYPE only_name gauge
                    only_name 1.1
                    # TYPE cnt:name_category gauge
                    cnt:name_category 1
                    # TYPE name_description gauge
                    # HELP name_description Stateless with description
                    name_description 2
                    # TYPE name_unit_requests gauge
                    # UNIT name_unit_requests requests
                    name_unit_requests 3
                    # TYPE name_description_unit_requests gauge
                    # UNIT name_description_unit_requests requests
                    # HELP name_description_unit_requests Stateless with description and unit
                    name_description_unit_requests -1.99
                    # TYPE const_label gauge
                    const_label{c1="c1_v1"} 0.01
                    # TYPE dynamic_label gauge
                    dynamic_label{d1="d1_v1"} 0.01
                    dynamic_label{d1="d1_v2"} 42
                    # TYPE many_labels gauge
                    many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v1"} 3.14
                    many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v2"} 1.9
                    # EOF
                    """);
        }

        @Test
        @Order(4)
        public void testNoChangeWithoutObservation() throws IOException {
            context.exportAndVerify(
                    """
                    # TYPE only_name gauge
                    only_name 1.1
                    # TYPE cnt:name_category gauge
                    cnt:name_category 1
                    # TYPE name_description gauge
                    # HELP name_description Stateless with description
                    name_description 2
                    # TYPE name_unit_requests gauge
                    # UNIT name_unit_requests requests
                    name_unit_requests 3
                    # TYPE name_description_unit_requests gauge
                    # UNIT name_description_unit_requests requests
                    # HELP name_description_unit_requests Stateless with description and unit
                    name_description_unit_requests -1.99
                    # TYPE const_label gauge
                    const_label{c1="c1_v1"} 0.01
                    # TYPE dynamic_label gauge
                    dynamic_label{d1="d1_v1"} 0.01
                    dynamic_label{d1="d1_v2"} 42
                    # TYPE many_labels gauge
                    many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v1"} 3.14
                    many_labels{c1="c1_v1",c2="c2_v1",d1="d1_v1",d2="d2_v2"} 1.9
                    # EOF
                    """);
        }
    }

    @Test
    public void demo() throws InterruptedException {
        MetricRegistry registry = MetricsFacade.createRegistry(new Label("env", "test"));
        MetricsExportManager snapshotManager =
                MetricsFacade.createExportManager(new ConsoleMetricsExporter(OpenMetricsSnapshotsWriter.DEFAULT), 1);
        snapshotManager.manageMetricRegistry(registry);

        BooleanGauge booleanGauge = BooleanGauge.builder("boolean_gauge")
                .withDescription("A test boolean gauge without labels")
                .register(registry);
        booleanGauge.getOrCreateNotLabeled().setTrue();

        StatelessMetric.builder(StatelessMetric.key("memory").withCategory("jvm"))
                .withDynamicLabelNames("type")
                .withDescription("JVM memory usage")
                .withUnit("bytes")
                .registerDataPoint(() -> Runtime.getRuntime().maxMemory(), "type", "max")
                .registerDataPoint(() -> Runtime.getRuntime().totalMemory(), "type", "total")
                .registerDataPoint(() -> Runtime.getRuntime().freeMemory(), "type", "free")
                .register(registry);

        LongCounter longCounter = LongCounter.builder("request_count")
                .withUnit("requests")
                .withConstantLabel(new Label("constant_label", "constant_value"))
                .withDynamicLabelNames("method")
                .register(registry);
        longCounter.getOrCreateLabeled("method", "POST").increment(42);
        longCounter.getOrCreateLabeled("method", "GET").increment(17);

        DoubleGauge doubleGauge = DoubleGauge.builder("test_double_gauge")
                .withOperator(StatUtils.DOUBLE_SUM, false)
                .withDynamicLabelNames("init")
                .register(registry);
        doubleGauge.getOrCreateLabeled(() -> 1.0, "init", "one").update(10.0);
        doubleGauge.getOrCreateLabeled("init", "default").update(10.0);

        StatsGaugeAdapter<IntSupplier, StatContainer> statGauge = StatsGaugeAdapter.builder(
                        StatsGaugeAdapter.key("test_stats_gauge"), StatUtils.INT_INIT, StatContainer::new)
                .withConstantLabel(new Label("constant_label", "constant-value"))
                .withDynamicLabelNames("name")
                .withUnit("ms")
                .withLongStat("counter", StatContainer::getCounter)
                .withLongStat("sum", StatContainer::getSum)
                .withDoubleStat("average", StatContainer::getAverage)
                .withReset(StatContainer::reset)
                .register(registry);

        String[] labels1 = new String[] {"name", "default"};
        statGauge.getOrCreateLabeled(labels1).update(3);
        statGauge.getOrCreateLabeled(labels1).update(5);

        String[] labels2 = new String[] {"name", "custom"};
        statGauge.getOrCreateLabeled(labels2).update(10);
        statGauge.getOrCreateLabeled(labels2).update(2);

        Thread.sleep(1200);

        statGauge.getOrCreateLabeled(labels1).update(8);
        statGauge.getOrCreateLabeled(labels2).update(12);

        Thread.sleep(1200);
    }
}
