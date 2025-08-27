// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.types.LongPair;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.util.List;

@ConfigData("contracts.ops.duration")
public record OpsDurationConfig(
        // The duration of the operations in microseconds. The key is the operation number and the value is the
        // duration. The op codes are broken into sets of 64 values.
        @ConfigProperty(
                        defaultValue =
                                "1-123,2-105,3-93,4-100,5-116,6-212,7-208,8-290,9-262,10-307,11-106,16-55,17-56,18-77,19-77,20-63,21-35,22-91,23-92,24-92,25-85,26-63,27-136,28-149,29-131,32-693,48-23,49-270,50-23,51-23,52-23,53-69,54-28,55-161,56-29,57-243,58-23,59-271,60-349,61-30,62-106,63-279,64-49")
                @NetworkProperty
                List<LongPair> opsDurations1_to_64,
        @ConfigProperty(
                        defaultValue =
                                "65-23,66-30,67-29,68-23,69-30,70-23,71-32,80-20,81-77,82-102,83-78,84-260,85-713,86-143,87-155,88-29,89-30,90-29,91-5,96-21,97-20,98-20,99-21,100-20,101-20,102-21,103-21,104-21,105-21,106-27,107-21,108-21,109-21,110-21,111-23,112-21,113-21,114-22,115-23,116-22,117-22,118-22,119-22,120-22,121-22,122-23,123-23,124-22,125-22,126-23,127-23,128-17")
                @NetworkProperty
                List<LongPair> opsDurations65_to_128,
        @ConfigProperty(
                        defaultValue =
                                "144-27,129-17,145-27,130-17,146-28,131-17,147-27,132-17,148-28,133-17,149-28,134-17,150-28,135-17,151-28,136-17,152-28,137-18,153-29,138-18,154-28,139-18,155-29,140-18,156-29,141-18,157-29,142-18,158-29,143-18,159-29,160-109,161-677,162-734,163-808,164-959")
                @NetworkProperty
                List<LongPair> opsDurations129_to_192,
        @ConfigProperty(defaultValue = "240-26552,241-98859,242-2011,244-1596,245-11291,250-2091") @NetworkProperty
                List<LongPair> opsDurations193_to_256,
        @ConfigProperty(defaultValue = "1575") @NetworkProperty long opsGasBasedDurationMultiplier,
        @ConfigProperty(defaultValue = "1575") @NetworkProperty long precompileGasBasedDurationMultiplier,
        @ConfigProperty(defaultValue = "1575") @NetworkProperty long systemContractGasBasedDurationMultiplier) {}
