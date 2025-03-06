// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.transformers;

import static com.swirlds.component.framework.schedulers.builders.TaskSchedulerType.DIRECT_THREADSAFE;

import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.wires.input.BindableInputWire;
import com.swirlds.component.framework.wires.input.InputWire;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.component.framework.wires.output.StandardOutputWire;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * Create a wire router. A wire router takes a single input and splits data into multiple outputs with different
 * addresses. When data is sent to a router, the address is also sent. The router then sends the data to the output wire
 * at the specified address.
 *
 * @param <ROUTER_TYPE> an enum that describes the addresses where data can be routed. Each enum value corresponds to a
 *                      different address where data can be routed.
 */
public class WireRouter<ROUTER_TYPE extends Enum<ROUTER_TYPE>> {

    private final BindableInputWire<RoutableData<ROUTER_TYPE>, Void> inputWire;
    private final List<StandardOutputWire<Object>> outputWires;
    private final Class<ROUTER_TYPE> clazz;

    /**
     * Constructor.
     *
     * @param model           the wiring model containing this router
     * @param routerName      the name of the router
     * @param routerInputName the label for the input wire going into the router
     * @param clazz           the class of the enum that describes the different addresses that data can be routed to.
     */
    public WireRouter(
            @NonNull final WiringModel model,
            @NonNull final String routerName,
            @NonNull final String routerInputName,
            @NonNull final Class<ROUTER_TYPE> clazz) {
        final TaskScheduler<Void> scheduler = model.<Void>schedulerBuilder(routerName)
                .withType(DIRECT_THREADSAFE)
                .build();

        outputWires = new ArrayList<>(clazz.getEnumConstants().length);
        for (int index = 0; index < clazz.getEnumConstants().length; index++) {
            final ROUTER_TYPE dataType = clazz.getEnumConstants()[index];
            if (dataType.ordinal() != index) {
                throw new IllegalArgumentException("Enum values must be in order");
            }

            final StandardOutputWire<Object> outputWire = scheduler.buildSecondaryOutputWire();
            outputWires.add(outputWire);
        }

        inputWire = scheduler.buildInputWire(routerInputName);
        inputWire.bindConsumer(this::routeData);
        this.clazz = clazz;
    }

    /**
     * Get the input wire.
     *
     * @return the input wire
     */
    @NonNull
    public InputWire<RoutableData<ROUTER_TYPE>> getInput() {
        return inputWire;
    }

    /**
     * Get the output wire for a specific address.
     *
     * @param address       the data type
     * @param <OUTPUT_TYPE> the type of data that the output wire carries
     * @return the output wire
     */
    @NonNull
    @SuppressWarnings("unchecked")
    public <OUTPUT_TYPE> OutputWire<OUTPUT_TYPE> getOutput(@NonNull final ROUTER_TYPE address) {
        return (OutputWire<OUTPUT_TYPE>) outputWires.get(address.ordinal());
    }

    /**
     * Get the type of the enum that defines this router.
     *
     * @return the class of the enum
     */
    @NonNull
    public Class<ROUTER_TYPE> getRouterType() {
        return clazz;
    }

    /**
     * Route data to the appropriate output wire.
     *
     * @param routableData the data to route
     */
    private void routeData(@NonNull final RoutableData<ROUTER_TYPE> routableData) {
        final ROUTER_TYPE dataType = routableData.address();
        final StandardOutputWire<Object> outputWire = outputWires.get(dataType.ordinal());
        outputWire.forward(routableData.data());
    }
}
