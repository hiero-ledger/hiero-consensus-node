// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.model;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerBuilder.UNLIMITED_CAPACITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.component.framework.schedulers.SequentialTaskSchedulerAliveThreadCleanup;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.wires.SolderType;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ModelTests implements SequentialTaskSchedulerAliveThreadCleanup {

    /**
     * For debugging with a human in the loop.
     */
    private static final boolean printMermaidDiagram = false;

    private WiringModel model;
    /**
     * Validate the model.
     *
     * @param model                             the model to validate
     * @param cycleExpected                     true if a cycle is expected, false otherwise
     * @param illegalDirectSchedulerUseExpected true if illegal direct scheduler use is expected, false otherwise
     */
    private static void validateModel(
            @NonNull final WiringModel model,
            final boolean cycleExpected,
            final boolean illegalDirectSchedulerUseExpected) {

        final boolean cycleDetected = model.checkForCyclicalBackpressure();
        assertEquals(cycleExpected, cycleDetected);

        final boolean illegalDirectSchedulerUseDetected = model.checkForIllegalDirectSchedulerUsage();
        assertEquals(illegalDirectSchedulerUseExpected, illegalDirectSchedulerUseDetected);

        // Should not throw.
        final String diagram = model.generateWiringDiagram(List.of(), List.of(), List.of(), false);
        if (printMermaidDiagram) {
            System.out.println(diagram);
        }
    }

    @Test
    void emptyModelTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();
        validateModel(model, false, false);
    }

    @Test
    void singleVertexTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A

        */

        final TaskScheduler<Void> taskSchedulerA = model.<Void>schedulerBuilder("A")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();

        validateModel(model, false, false);
    }

    @Test
    void shortChainTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A -> B -> C

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Void> taskSchedulerC =
                model.<Void>schedulerBuilder("C").withUnhandledTaskCapacity(1).build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);

        validateModel(model, false, false);
    }

    @Test
    void loopSizeOneTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A --|
        ^   |
        |---|

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        taskSchedulerA.getOutputWire().solderTo(inputA);

        validateModel(model, true, false);
    }

    @Test
    void loopSizeOneBrokenByInjectionTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A --|
        ^   |
        |---|

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        taskSchedulerA.getOutputWire().solderTo(inputA, SolderType.INJECT);

        validateModel(model, false, false);
    }

    @Test
    void loopSizeTwoTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A -> B
        ^    |
        |----|

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputA);

        validateModel(model, true, false);
    }

    @Test
    void loopSizeTwoBrokenByInjectionTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A -> B
        ^    |
        |----|

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputA, SolderType.INJECT);

        validateModel(model, false, false);
    }

    @Test
    void loopSizeTwoBrokenByMissingBoundTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A -> B
        ^    |
        |----|

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputA);

        validateModel(model, false, false);
    }

    @Test
    void loopSizeThreeTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A -> B -> C
        ^         |
        |---------|

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputA);

        validateModel(model, true, false);
    }

    @Test
    void loopSizeThreeBrokenByInjectionTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A -> B -> C
        ^         |
        |---------|

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputA, SolderType.INJECT);

        validateModel(model, false, false);
    }

    @Test
    void loopSizeThreeBrokenByMissingBoundTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A -> B -> C
        ^         |
        |---------|

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputA);

        validateModel(model, false, false);
    }

    @Test
    void loopSizeFourTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A -----> B
        ^        |
        |        v
        D <----- C

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<Integer> taskSchedulerD = model.<Integer>schedulerBuilder("D")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        taskSchedulerD.getOutputWire().solderTo(inputA);

        validateModel(model, true, false);
    }

    @Test
    void loopSizeFourBrokenByInjectionTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A -----> B
        ^        |
        |        v
        D <----- C

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<Integer> taskSchedulerD = model.<Integer>schedulerBuilder("D")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        taskSchedulerD.getOutputWire().solderTo(inputA, SolderType.INJECT);

        validateModel(model, false, false);
    }

    @Test
    void loopSizeFourBrokenByMissingBoundTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A -----> B
        ^        |
        |        v
        D <----- C

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<Integer> taskSchedulerD = model.<Integer>schedulerBuilder("D")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        taskSchedulerD.getOutputWire().solderTo(inputA);

        validateModel(model, true, false);
    }

    @Test
    void loopSizeFourWithChainTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A
        |
        v
        B
        |
        v
        C
        |
        v
        D -----> E
        ^        |
        |        v
        G <----- F -----> H -----> I -----> J

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<Integer> taskSchedulerD = model.<Integer>schedulerBuilder("D")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        final TaskScheduler<Integer> taskSchedulerE = model.<Integer>schedulerBuilder("E")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputE = taskSchedulerE.buildInputWire("inputE");

        final TaskScheduler<Integer> taskSchedulerF = model.<Integer>schedulerBuilder("F")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputF = taskSchedulerF.buildInputWire("inputF");

        final TaskScheduler<Integer> taskSchedulerG = model.<Integer>schedulerBuilder("G")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputG = taskSchedulerG.buildInputWire("inputG");

        final TaskScheduler<Integer> taskSchedulerH = model.<Integer>schedulerBuilder("H")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputH = taskSchedulerH.buildInputWire("inputH");

        final TaskScheduler<Integer> taskSchedulerI = model.<Integer>schedulerBuilder("I")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputI = taskSchedulerI.buildInputWire("inputI");

        final TaskScheduler<Integer> taskSchedulerJ = model.<Integer>schedulerBuilder("J")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputJ = taskSchedulerJ.buildInputWire("inputJ");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        taskSchedulerD.getOutputWire().solderTo(inputE);
        taskSchedulerE.getOutputWire().solderTo(inputF);
        taskSchedulerF.getOutputWire().solderTo(inputG);
        taskSchedulerG.getOutputWire().solderTo(inputD);

        taskSchedulerF.getOutputWire().solderTo(inputH);
        taskSchedulerH.getOutputWire().solderTo(inputI);
        taskSchedulerI.getOutputWire().solderTo(inputJ);

        validateModel(model, true, false);
    }

    @Test
    void loopSizeFourWithChainBrokenByInjectionTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A
        |
        v
        B
        |
        v
        C
        |
        v
        D -----> E
        ^        |
        |        v
        G <----- F -----> H -----> I -----> J

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<Integer> taskSchedulerD = model.<Integer>schedulerBuilder("D")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        final TaskScheduler<Integer> taskSchedulerE = model.<Integer>schedulerBuilder("E")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputE = taskSchedulerE.buildInputWire("inputE");

        final TaskScheduler<Integer> taskSchedulerF = model.<Integer>schedulerBuilder("F")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputF = taskSchedulerF.buildInputWire("inputF");

        final TaskScheduler<Integer> taskSchedulerG = model.<Integer>schedulerBuilder("G")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputG = taskSchedulerG.buildInputWire("inputG");

        final TaskScheduler<Integer> taskSchedulerH = model.<Integer>schedulerBuilder("H")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputH = taskSchedulerH.buildInputWire("inputH");

        final TaskScheduler<Integer> taskSchedulerI = model.<Integer>schedulerBuilder("I")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputI = taskSchedulerI.buildInputWire("inputI");

        final TaskScheduler<Integer> taskSchedulerJ = model.<Integer>schedulerBuilder("J")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputJ = taskSchedulerJ.buildInputWire("");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        taskSchedulerD.getOutputWire().solderTo(inputE);
        taskSchedulerE.getOutputWire().solderTo(inputF);
        taskSchedulerF.getOutputWire().solderTo(inputG);
        taskSchedulerG.getOutputWire().solderTo(inputD, SolderType.INJECT);

        taskSchedulerF.getOutputWire().solderTo(inputH);
        taskSchedulerH.getOutputWire().solderTo(inputI);
        taskSchedulerI.getOutputWire().solderTo(inputJ);

        validateModel(model, false, false);
    }

    @Test
    void loopSizeFourWithChainBrokenByMissingBoundTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A
        |
        v
        B
        |
        v
        C
        |
        v
        D -----> E
        ^        |
        |        v
        G <----- F -----> H -----> I -----> J

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<Integer> taskSchedulerD = model.<Integer>schedulerBuilder("D")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        final TaskScheduler<Integer> taskSchedulerE = model.<Integer>schedulerBuilder("E")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputE = taskSchedulerE.buildInputWire("inputE");

        final TaskScheduler<Integer> taskSchedulerF = model.<Integer>schedulerBuilder("F")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputF = taskSchedulerF.buildInputWire("inputF");

        final TaskScheduler<Integer> taskSchedulerG = model.<Integer>schedulerBuilder("G")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputG = taskSchedulerG.buildInputWire("inputG");

        final TaskScheduler<Integer> taskSchedulerH = model.<Integer>schedulerBuilder("H")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputH = taskSchedulerH.buildInputWire("inputH");

        final TaskScheduler<Integer> taskSchedulerI = model.<Integer>schedulerBuilder("I")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputI = taskSchedulerI.buildInputWire("inputI");

        final TaskScheduler<Integer> taskSchedulerJ = model.<Integer>schedulerBuilder("J")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputJ = taskSchedulerJ.buildInputWire("inputJ");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        taskSchedulerD.getOutputWire().solderTo(inputE);
        taskSchedulerE.getOutputWire().solderTo(inputF);
        taskSchedulerF.getOutputWire().solderTo(inputG);
        taskSchedulerG.getOutputWire().solderTo(inputD);

        taskSchedulerF.getOutputWire().solderTo(inputH);
        taskSchedulerH.getOutputWire().solderTo(inputI);
        taskSchedulerI.getOutputWire().solderTo(inputJ);

        validateModel(model, false, false);
    }

    @Test
    void multiLoopTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A <---------------------------------|
        |                                   |
        v                                   |
        B                                   |
        |                                   |
        v                                   |
        C                                   |
        |                                   |
        v                                   |
        D -----> E <---------------|        |
        ^        |                 |        |
        |        v                 |        |
        G <----- F -----> H -----> I -----> J

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<Integer> taskSchedulerD = model.<Integer>schedulerBuilder("D")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        final TaskScheduler<Integer> taskSchedulerE = model.<Integer>schedulerBuilder("E")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputE = taskSchedulerE.buildInputWire("inputE");

        final TaskScheduler<Integer> taskSchedulerF = model.<Integer>schedulerBuilder("F")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputF = taskSchedulerF.buildInputWire("inputF");

        final TaskScheduler<Integer> taskSchedulerG = model.<Integer>schedulerBuilder("G")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputG = taskSchedulerG.buildInputWire("inputG");

        final TaskScheduler<Integer> taskSchedulerH = model.<Integer>schedulerBuilder("H")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputH = taskSchedulerH.buildInputWire("inputH");

        final TaskScheduler<Integer> taskSchedulerI = model.<Integer>schedulerBuilder("I")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputI = taskSchedulerI.buildInputWire("inputI");

        final TaskScheduler<Integer> taskSchedulerJ = model.<Integer>schedulerBuilder("J")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputJ = taskSchedulerJ.buildInputWire("inputJ");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        taskSchedulerD.getOutputWire().solderTo(inputE);
        taskSchedulerE.getOutputWire().solderTo(inputF);
        taskSchedulerF.getOutputWire().solderTo(inputG);
        taskSchedulerG.getOutputWire().solderTo(inputD);

        taskSchedulerF.getOutputWire().solderTo(inputH);
        taskSchedulerH.getOutputWire().solderTo(inputI);
        taskSchedulerI.getOutputWire().solderTo(inputJ);

        taskSchedulerJ.getOutputWire().solderTo(inputA);

        taskSchedulerI.getOutputWire().solderTo(inputE);

        validateModel(model, true, false);
    }

    @Test
    void multiLoopBrokenByInjectionTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A <---------------------------------|
        |                                   |
        v                                   |
        B                                   |
        |                                   |
        v                                   |
        C                                   |
        |                                   |
        v                                   |
        D -----> E <---------------|        |
        ^        |                 |        |
        |        v                 |        |
        G <----- F -----> H -----> I -----> J

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<Integer> taskSchedulerD = model.<Integer>schedulerBuilder("D")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        final TaskScheduler<Integer> taskSchedulerE = model.<Integer>schedulerBuilder("E")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputE = taskSchedulerE.buildInputWire("inputE");

        final TaskScheduler<Integer> taskSchedulerF = model.<Integer>schedulerBuilder("F")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputF = taskSchedulerF.buildInputWire("inputF");

        final TaskScheduler<Integer> taskSchedulerG = model.<Integer>schedulerBuilder("G")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputG = taskSchedulerG.buildInputWire("inputG");

        final TaskScheduler<Integer> taskSchedulerH = model.<Integer>schedulerBuilder("H")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputH = taskSchedulerH.buildInputWire("inputH");

        final TaskScheduler<Integer> taskSchedulerI = model.<Integer>schedulerBuilder("I")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputI = taskSchedulerI.buildInputWire("inputI");

        final TaskScheduler<Integer> taskSchedulerJ = model.<Integer>schedulerBuilder("J")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputJ = taskSchedulerJ.buildInputWire("inputJ");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        taskSchedulerD.getOutputWire().solderTo(inputE);
        taskSchedulerE.getOutputWire().solderTo(inputF);
        taskSchedulerF.getOutputWire().solderTo(inputG);
        taskSchedulerG.getOutputWire().solderTo(inputD, SolderType.INJECT);

        taskSchedulerF.getOutputWire().solderTo(inputH);
        taskSchedulerH.getOutputWire().solderTo(inputI);
        taskSchedulerI.getOutputWire().solderTo(inputJ);

        taskSchedulerJ.getOutputWire().solderTo(inputA, SolderType.INJECT);

        taskSchedulerI.getOutputWire().solderTo(inputE, SolderType.INJECT);

        validateModel(model, false, false);
    }

    @Test
    void multiLoopBrokenByMissingBoundTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A <---------------------------------|
        |                                   |
        v                                   |
        B                                   |
        |                                   |
        v                                   |
        C                                   |
        |                                   |
        v                                   |
        D -----> E <---------------|        |
        ^        |                 |        |
        |        v                 |        |
        G <----- F -----> H -----> I -----> J

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<Integer> taskSchedulerD = model.<Integer>schedulerBuilder("D")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        final TaskScheduler<Integer> taskSchedulerE = model.<Integer>schedulerBuilder("E")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputE = taskSchedulerE.buildInputWire("inputE");

        final TaskScheduler<Integer> taskSchedulerF = model.<Integer>schedulerBuilder("F")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputF = taskSchedulerF.buildInputWire("inputF");

        final TaskScheduler<Integer> taskSchedulerG = model.<Integer>schedulerBuilder("G")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputG = taskSchedulerG.buildInputWire("inputG");

        final TaskScheduler<Integer> taskSchedulerH = model.<Integer>schedulerBuilder("H")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputH = taskSchedulerH.buildInputWire("inputH");

        final TaskScheduler<Integer> taskSchedulerI = model.<Integer>schedulerBuilder("I")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputI = taskSchedulerI.buildInputWire("inputI");

        final TaskScheduler<Integer> taskSchedulerJ = model.<Integer>schedulerBuilder("J")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputJ = taskSchedulerJ.buildInputWire("inputJ");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        taskSchedulerD.getOutputWire().solderTo(inputE);
        taskSchedulerE.getOutputWire().solderTo(inputF);
        taskSchedulerF.getOutputWire().solderTo(inputG);
        taskSchedulerG.getOutputWire().solderTo(inputD);

        taskSchedulerF.getOutputWire().solderTo(inputH);
        taskSchedulerH.getOutputWire().solderTo(inputI);
        taskSchedulerI.getOutputWire().solderTo(inputJ);

        taskSchedulerJ.getOutputWire().solderTo(inputA);

        taskSchedulerI.getOutputWire().solderTo(inputE);

        validateModel(model, false, false);
    }

    @Test
    void filterInCycleTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A
        |
        v
        B
        |
        v
        C
        |
        v
        D -----> E
        ^        |
        |        v
        G <----- F -----> H -----> I -----> J

        Connection D -> E uses a filter

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<Integer> taskSchedulerD = model.<Integer>schedulerBuilder("D")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        final TaskScheduler<Integer> taskSchedulerE = model.<Integer>schedulerBuilder("E")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputE = taskSchedulerE.buildInputWire("inputE");

        final TaskScheduler<Integer> taskSchedulerF = model.<Integer>schedulerBuilder("F")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputF = taskSchedulerF.buildInputWire("inputF");

        final TaskScheduler<Integer> taskSchedulerG = model.<Integer>schedulerBuilder("G")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputG = taskSchedulerG.buildInputWire("inputG");

        final TaskScheduler<Integer> taskSchedulerH = model.<Integer>schedulerBuilder("H")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputH = taskSchedulerH.buildInputWire("inputH");

        final TaskScheduler<Integer> taskSchedulerI = model.<Integer>schedulerBuilder("I")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputI = taskSchedulerI.buildInputWire("inputI");

        final TaskScheduler<Integer> taskSchedulerJ = model.<Integer>schedulerBuilder("J")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputJ = taskSchedulerJ.buildInputWire("");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        taskSchedulerD
                .getOutputWire()
                .buildFilter("onlyEven", "onlyEvenInput", x -> x % 2 == 0)
                .solderTo(inputE);
        taskSchedulerE.getOutputWire().solderTo(inputF);
        taskSchedulerF.getOutputWire().solderTo(inputG);
        taskSchedulerG.getOutputWire().solderTo(inputD);

        taskSchedulerF.getOutputWire().solderTo(inputH);
        taskSchedulerH.getOutputWire().solderTo(inputI);
        taskSchedulerI.getOutputWire().solderTo(inputJ);

        validateModel(model, true, false);
    }

    @Test
    void transformerInCycleTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A
        |
        v
        B
        |
        v
        C
        |
        v
        D -----> E
        ^        |
        |        v
        G <----- F -----> H -----> I -----> J

        Connection D -> E uses a transformer

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<Integer> taskSchedulerD = model.<Integer>schedulerBuilder("D")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        final TaskScheduler<Integer> taskSchedulerE = model.<Integer>schedulerBuilder("E")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputE = taskSchedulerE.buildInputWire("inputE");

        final TaskScheduler<Integer> taskSchedulerF = model.<Integer>schedulerBuilder("F")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputF = taskSchedulerF.buildInputWire("inputF");

        final TaskScheduler<Integer> taskSchedulerG = model.<Integer>schedulerBuilder("G")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputG = taskSchedulerG.buildInputWire("inputG");

        final TaskScheduler<Integer> taskSchedulerH = model.<Integer>schedulerBuilder("H")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputH = taskSchedulerH.buildInputWire("inputH");

        final TaskScheduler<Integer> taskSchedulerI = model.<Integer>schedulerBuilder("I")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputI = taskSchedulerI.buildInputWire("inputI");

        final TaskScheduler<Integer> taskSchedulerJ = model.<Integer>schedulerBuilder("J")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputJ = taskSchedulerJ.buildInputWire("");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        taskSchedulerD
                .getOutputWire()
                .buildTransformer("inverter", "inverterInput", x -> -x)
                .solderTo(inputE);
        taskSchedulerE.getOutputWire().solderTo(inputF);
        taskSchedulerF.getOutputWire().solderTo(inputG);
        taskSchedulerG.getOutputWire().solderTo(inputD);

        taskSchedulerF.getOutputWire().solderTo(inputH);
        taskSchedulerH.getOutputWire().solderTo(inputI);
        taskSchedulerI.getOutputWire().solderTo(inputJ);

        validateModel(model, true, false);
    }

    @Test
    void splitterInCycleTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A
        |
        v
        B
        |
        v
        C
        |
        v
        D -----> E
        ^        |
        |        v
        G <----- F -----> H -----> I -----> J

        Connection D -> E uses a splitter

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<List<Integer>> taskSchedulerD = model.<List<Integer>>schedulerBuilder("D")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        final TaskScheduler<Integer> taskSchedulerE = model.<Integer>schedulerBuilder("E")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputE = taskSchedulerE.buildInputWire("inputE");

        final TaskScheduler<Integer> taskSchedulerF = model.<Integer>schedulerBuilder("F")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputF = taskSchedulerF.buildInputWire("inputF");

        final TaskScheduler<Integer> taskSchedulerG = model.<Integer>schedulerBuilder("G")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputG = taskSchedulerG.buildInputWire("inputG");

        final TaskScheduler<Integer> taskSchedulerH = model.<Integer>schedulerBuilder("H")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputH = taskSchedulerH.buildInputWire("inputH");

        final TaskScheduler<Integer> taskSchedulerI = model.<Integer>schedulerBuilder("I")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputI = taskSchedulerI.buildInputWire("inputI");

        final TaskScheduler<Integer> taskSchedulerJ = model.<Integer>schedulerBuilder("J")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputJ = taskSchedulerJ.buildInputWire("");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        final OutputWire<Integer> splitter = taskSchedulerD.getOutputWire().buildSplitter("splitter", "splitterInput");
        splitter.solderTo(inputE);
        taskSchedulerE.getOutputWire().solderTo(inputF);
        taskSchedulerF.getOutputWire().solderTo(inputG);
        taskSchedulerG.getOutputWire().solderTo(inputD);

        taskSchedulerF.getOutputWire().solderTo(inputH);
        taskSchedulerH.getOutputWire().solderTo(inputI);
        taskSchedulerI.getOutputWire().solderTo(inputJ);

        validateModel(model, true, false);
    }

    @Test
    void multipleOutputCycleTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A <---------------------------------|
        |                                   |
        v                                   |
        B                                   |
        |                                   |
        v                                   |
        C                                   |
        |                                   |
        v                                   |
        D -----> E <---------------|        |
        ^        |                 |        |
        |        v                 |        |
        G <----- F -----> H -----> I -----> J
                                   |        ^
                                   |        |
                                   |--------|

        I has secondary output channels

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<Integer> taskSchedulerD = model.<Integer>schedulerBuilder("D")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        final TaskScheduler<Integer> taskSchedulerE = model.<Integer>schedulerBuilder("E")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputE = taskSchedulerE.buildInputWire("inputE");

        final TaskScheduler<Integer> taskSchedulerF = model.<Integer>schedulerBuilder("F")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputF = taskSchedulerF.buildInputWire("inputF");

        final TaskScheduler<Integer> taskSchedulerG = model.<Integer>schedulerBuilder("G")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputG = taskSchedulerG.buildInputWire("inputG");

        final TaskScheduler<Integer> taskSchedulerH = model.<Integer>schedulerBuilder("H")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputH = taskSchedulerH.buildInputWire("inputH");

        final TaskScheduler<Integer> taskSchedulerI = model.<Integer>schedulerBuilder("I")
                .withUnhandledTaskCapacity(1)
                .build();
        final OutputWire<Integer> secondaryOutputI = taskSchedulerI.buildSecondaryOutputWire();
        final OutputWire<Integer> tertiaryOutputI = taskSchedulerI.buildSecondaryOutputWire();
        final InputWire<Integer> inputI = taskSchedulerI.buildInputWire("inputI");

        final TaskScheduler<Integer> taskSchedulerJ = model.<Integer>schedulerBuilder("J")
                .withUnhandledTaskCapacity(1)
                .build();
        final InputWire<Integer> inputJ = taskSchedulerJ.buildInputWire("inputJ");
        final InputWire<Integer> inputJ2 = taskSchedulerJ.buildInputWire("inputJ2");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        taskSchedulerD.getOutputWire().solderTo(inputE);
        taskSchedulerE.getOutputWire().solderTo(inputF);
        taskSchedulerF.getOutputWire().solderTo(inputG);
        taskSchedulerG.getOutputWire().solderTo(inputD);

        taskSchedulerF.getOutputWire().solderTo(inputH);
        taskSchedulerH.getOutputWire().solderTo(inputI);
        taskSchedulerI.getOutputWire().solderTo(inputJ);

        taskSchedulerJ.getOutputWire().solderTo(inputA);

        secondaryOutputI.solderTo(inputE);
        tertiaryOutputI.solderTo(inputJ2);

        validateModel(model, true, false);
    }

    /**
     * We should detect when a concurrent scheduler access a direct scheduler.
     */
    @Test
    void concurrentAccessingDirectTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A
        |
        v
        B
        |
        v
        C
        |
        v
        D -----> E
        ^        |
        |        v
        G <----- F -----> H -----> I -----> J

        D = CONCURRENT
        E = DIRECT

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<Integer> taskSchedulerD = model.<Integer>schedulerBuilder("D")
                .withType(TaskSchedulerType.CONCURRENT)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        final TaskScheduler<Integer> taskSchedulerE = model.<Integer>schedulerBuilder("E")
                .withType(TaskSchedulerType.DIRECT)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputE = taskSchedulerE.buildInputWire("inputE");

        final TaskScheduler<Integer> taskSchedulerF = model.<Integer>schedulerBuilder("F")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputF = taskSchedulerF.buildInputWire("inputF");

        final TaskScheduler<Integer> taskSchedulerG = model.<Integer>schedulerBuilder("G")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputG = taskSchedulerG.buildInputWire("inputG");

        final TaskScheduler<Integer> taskSchedulerH = model.<Integer>schedulerBuilder("H")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputH = taskSchedulerH.buildInputWire("inputH");

        final TaskScheduler<Integer> taskSchedulerI = model.<Integer>schedulerBuilder("I")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputI = taskSchedulerI.buildInputWire("inputI");

        final TaskScheduler<Integer> taskSchedulerJ = model.<Integer>schedulerBuilder("J")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputJ = taskSchedulerJ.buildInputWire("inputJ");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        taskSchedulerD.getOutputWire().solderTo(inputE);
        taskSchedulerE.getOutputWire().solderTo(inputF);
        taskSchedulerF.getOutputWire().solderTo(inputG);
        taskSchedulerG.getOutputWire().solderTo(inputD);

        taskSchedulerF.getOutputWire().solderTo(inputH);
        taskSchedulerH.getOutputWire().solderTo(inputI);
        taskSchedulerI.getOutputWire().solderTo(inputJ);

        validateModel(model, false, true);
    }

    /**
     * We should detect when a concurrent scheduler access a direct scheduler.
     */
    @Test
    void concurrentAccessingMultipleDirectTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A
        |
        v
        B
        |
        v
        C
        |
        v
        D -----> E
        ^        |
        |        v
        G <----- F -----> H -----> I -----> J

        D = CONCURRENT
        E = DIRECT
        F = DIRECT

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<Integer> taskSchedulerD = model.<Integer>schedulerBuilder("D")
                .withType(TaskSchedulerType.CONCURRENT)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        final TaskScheduler<Integer> taskSchedulerE = model.<Integer>schedulerBuilder("E")
                .withType(TaskSchedulerType.DIRECT)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputE = taskSchedulerE.buildInputWire("inputE");

        final TaskScheduler<Integer> taskSchedulerF = model.<Integer>schedulerBuilder("F")
                .withType(TaskSchedulerType.DIRECT)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputF = taskSchedulerF.buildInputWire("inputF");

        final TaskScheduler<Integer> taskSchedulerG = model.<Integer>schedulerBuilder("G")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputG = taskSchedulerG.buildInputWire("inputG");

        final TaskScheduler<Integer> taskSchedulerH = model.<Integer>schedulerBuilder("H")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputH = taskSchedulerH.buildInputWire("inputH");

        final TaskScheduler<Integer> taskSchedulerI = model.<Integer>schedulerBuilder("I")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputI = taskSchedulerI.buildInputWire("inputI");

        final TaskScheduler<Integer> taskSchedulerJ = model.<Integer>schedulerBuilder("J")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputJ = taskSchedulerJ.buildInputWire("inputJ");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        taskSchedulerD.getOutputWire().solderTo(inputE);
        taskSchedulerE.getOutputWire().solderTo(inputF);
        taskSchedulerF.getOutputWire().solderTo(inputG);
        taskSchedulerG.getOutputWire().solderTo(inputD);

        taskSchedulerF.getOutputWire().solderTo(inputH);
        taskSchedulerH.getOutputWire().solderTo(inputI);
        taskSchedulerI.getOutputWire().solderTo(inputJ);

        validateModel(model, false, true);
    }

    /**
     * We should detect when a concurrent scheduler access a direct scheduler through proxies (i.e. the concurrent
     * scheduler calls into a DIRECT_THREADSAFE scheduler which calls into a DIRECT scheduler).
     */
    @Test
    void concurrentAccessingDirectThroughProxyTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A
        |
        v
        B
        |
        v
        C
        |
        v
        D -----> E
        ^        |
        |        v
        G <----- F -----> H -----> I -----> J

        D = CONCURRENT
        E = DIRECT_THREADSAFE
        F = DIRECT_THREADSAFE
        G = DIRECT

        */

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC =
                model.<Integer>schedulerBuilder("C").build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<Integer> taskSchedulerD = model.<Integer>schedulerBuilder("D")
                .withType(TaskSchedulerType.CONCURRENT)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        final TaskScheduler<Integer> taskSchedulerE = model.<Integer>schedulerBuilder("E")
                .withType(TaskSchedulerType.DIRECT_THREADSAFE)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputE = taskSchedulerE.buildInputWire("inputE");

        final TaskScheduler<Integer> taskSchedulerF = model.<Integer>schedulerBuilder("F")
                .withType(TaskSchedulerType.DIRECT_THREADSAFE)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputF = taskSchedulerF.buildInputWire("inputF");

        final TaskScheduler<Integer> taskSchedulerG = model.<Integer>schedulerBuilder("G")
                .withType(TaskSchedulerType.DIRECT)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputG = taskSchedulerG.buildInputWire("inputG");

        final TaskScheduler<Integer> taskSchedulerH = model.<Integer>schedulerBuilder("H")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputH = taskSchedulerH.buildInputWire("inputH");

        final TaskScheduler<Integer> taskSchedulerI = model.<Integer>schedulerBuilder("I")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputI = taskSchedulerI.buildInputWire("inputI");

        final TaskScheduler<Integer> taskSchedulerJ = model.<Integer>schedulerBuilder("J")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputJ = taskSchedulerJ.buildInputWire("inputJ");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        taskSchedulerD.getOutputWire().solderTo(inputE);
        taskSchedulerE.getOutputWire().solderTo(inputF);
        taskSchedulerF.getOutputWire().solderTo(inputG);
        taskSchedulerG.getOutputWire().solderTo(inputD);

        taskSchedulerF.getOutputWire().solderTo(inputH);
        taskSchedulerH.getOutputWire().solderTo(inputI);
        taskSchedulerI.getOutputWire().solderTo(inputJ);

        validateModel(model, false, true);
    }

    /**
     * We should detect when multiple sequential schedulers call into a scheduler.
     */
    @Test
    void multipleSequentialSchedulerTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        /*

        A
        |
        v
        B
        |
        v
        C
        |
        v
        D -----> E
        ^        |
        |        v
        G <----- F -----> H -----> I -----> J

        B = SEQUENTIAL_THREAD
        C = DIRECT_THREADSAFE
        D = DIRECT

        */

        final TaskScheduler<Integer> taskSchedulerA =
                model.<Integer>schedulerBuilder("A").build();
        final InputWire<Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        final TaskScheduler<Integer> taskSchedulerB = model.<Integer>schedulerBuilder("B")
                .withType(TaskSchedulerType.SEQUENTIAL_THREAD)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputB = taskSchedulerB.buildInputWire("inputB");

        final TaskScheduler<Integer> taskSchedulerC = model.<Integer>schedulerBuilder("C")
                .withType(TaskSchedulerType.DIRECT_THREADSAFE)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputC = taskSchedulerC.buildInputWire("inputC");

        final TaskScheduler<Integer> taskSchedulerD = model.<Integer>schedulerBuilder("D")
                .withType(TaskSchedulerType.DIRECT)
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputD = taskSchedulerD.buildInputWire("inputD");

        final TaskScheduler<Integer> taskSchedulerE = model.<Integer>schedulerBuilder("E")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputE = taskSchedulerE.buildInputWire("inputE");

        final TaskScheduler<Integer> taskSchedulerF = model.<Integer>schedulerBuilder("F")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputF = taskSchedulerF.buildInputWire("inputF");

        final TaskScheduler<Integer> taskSchedulerG = model.<Integer>schedulerBuilder("G")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputG = taskSchedulerG.buildInputWire("inputG");

        final TaskScheduler<Integer> taskSchedulerH = model.<Integer>schedulerBuilder("H")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputH = taskSchedulerH.buildInputWire("inputH");

        final TaskScheduler<Integer> taskSchedulerI = model.<Integer>schedulerBuilder("I")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputI = taskSchedulerI.buildInputWire("inputI");

        final TaskScheduler<Integer> taskSchedulerJ = model.<Integer>schedulerBuilder("J")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final InputWire<Integer> inputJ = taskSchedulerJ.buildInputWire("inputJ");

        taskSchedulerA.getOutputWire().solderTo(inputB);
        taskSchedulerB.getOutputWire().solderTo(inputC);
        taskSchedulerC.getOutputWire().solderTo(inputD);
        taskSchedulerD.getOutputWire().solderTo(inputE);
        taskSchedulerE.getOutputWire().solderTo(inputF);
        taskSchedulerF.getOutputWire().solderTo(inputG);
        taskSchedulerG.getOutputWire().solderTo(inputD);

        taskSchedulerF.getOutputWire().solderTo(inputH);
        taskSchedulerH.getOutputWire().solderTo(inputI);
        taskSchedulerI.getOutputWire().solderTo(inputJ);

        validateModel(model, false, true);
    }

    @Test
    void unboundInputWireTest() {
        this.model =
                WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        final TaskScheduler<Integer> taskSchedulerA = model.<Integer>schedulerBuilder("A")
                .withUnhandledTaskCapacity(UNLIMITED_CAPACITY)
                .build();
        final BindableInputWire<Integer, Integer> inputA = taskSchedulerA.buildInputWire("inputA");

        assertTrue(model.checkForUnboundInputWires());

        inputA.bindConsumer(x -> {});

        model.start();
        assertFalse(model.checkForUnboundInputWires());

        model.stop();
    }

    @AfterEach
    void tearDown() {
        if (model != null) {
            try {
                model.stop();
            } catch (final Exception e) {
                // Do nothing
            }
        }
    }
}
