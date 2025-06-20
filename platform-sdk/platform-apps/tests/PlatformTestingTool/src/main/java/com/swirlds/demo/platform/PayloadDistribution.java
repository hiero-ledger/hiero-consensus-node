// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import java.util.Arrays;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

public class PayloadDistribution {

    /** This version number should be used to handle compatibility issues that may arise from any future changes */
    private static final long VERSION = 1;

    /**
     * use this for all logging
     */
    private static final Logger logger = LogManager.getLogger(PayloadDistribution.class);

    private static final Marker MARKER = MarkerManager.getMarker("DEMO_INFO");

    private static final Marker ERROR = MarkerManager.getMarker("EXCEPTION");

    private PAYLOAD_TYPE[] typeDistribution;
    private int[] sizeDistribution;
    private float[] ratioDistribution;
    /** probability of payload distribution, sum of array should be 100 */

    // private PayloadDistribution(Builder builder) {
    // this.typeDistribution = builder.typeDistribution;
    // this.sizeDistribution = builder.sizeDistribution;
    // this.ratioDistribution = builder.ratioDistrbution;
    // }

    /**
     * Given a random percentage number return payload type and size according to defined distribution list
     *
     * @param randomNumber
     * @return
     */
    public PayloadProperty getPayloadProperty(float randomNumber) {
        if (ratioDistribution.length != sizeDistribution.length
                || ratioDistribution.length != typeDistribution.length) {
            System.out.println("ERROR Payload distribution array is not same size " + ratioDistribution.length
                    + sizeDistribution.length + typeDistribution.length);
            logger.error(
                    EXCEPTION.getMarker(),
                    " Payload distribution array is not same size {} {} {} ",
                    ratioDistribution.length,
                    sizeDistribution.length,
                    typeDistribution.length);
            while (true)
                ;
        }
        PayloadProperty property = new PayloadProperty();
        float sum = 0;
        if (randomNumber < 0 || randomNumber > 100) {
            return property;
        } else {
            try {
                for (int i = 0; i < ratioDistribution.length; i++) {
                    if (randomNumber >= sum && randomNumber < (sum + ratioDistribution[i])) {
                        property.setSize(sizeDistribution[i]);
                        property.setType(typeDistribution[i]);
                        return property;
                    }
                    sum += ratioDistribution[i];
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                logger.error(EXCEPTION.getMarker(), "", e);
            }
        }
        return property;
    }

    public PAYLOAD_TYPE[] getTypeDistribution() {
        return typeDistribution;
    }

    public void setTypeDistribution(PAYLOAD_TYPE[] typeDistribution) {
        this.typeDistribution = typeDistribution;
    }

    public int[] getSizeDistribution() {
        return sizeDistribution;
    }

    public void setSizeDistribution(int[] sizeDistribution) {
        this.sizeDistribution = sizeDistribution;
    }

    public float[] getRatioDistribution() {
        return ratioDistribution;
    }

    public void setRatioDistribution(float[] ratioDistribution) {
        this.ratioDistribution = ratioDistribution;
    }

    public void display() {
        logger.info(MARKER, "Size distribution  = " + Arrays.toString(sizeDistribution));
        logger.info(MARKER, "Type distribution  = " + Arrays.toString(typeDistribution));
        logger.info(MARKER, "Ratio distribution = " + Arrays.toString(ratioDistribution));
    }

    // public static Builder builder() {
    // return new Builder();
    // }
    //
    //
    // public static final class Builder {
    // private PAYLOAD_TYPE[] typeDistribution;
    // private int[] sizeDistribution;
    // private int[] ratioDistrbution;
    //
    // private Builder() {
    // }
    //
    // public Builder setTypeDistribution(PAYLOAD_TYPE[] typeDistribution) {
    // this.typeDistribution = typeDistribution;
    // return this;
    // }
    //
    // public Builder setSizeDistribution(int[] sizeDistribution) {
    // this.sizeDistribution = sizeDistribution;
    // return this;
    // }
    //
    // public Builder setRatioDistrbution(int[] ratioDistrbution) {
    // this.ratioDistrbution = ratioDistrbution;
    // return this;
    // }
    //
    // public PayloadDistribution build() {
    // return new PayloadDistribution(this);
    // }
    // }

}
