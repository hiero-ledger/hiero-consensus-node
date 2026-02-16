// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.gui.internal.components;

public interface ChartLabelModel {

    void setYLabels(String[] labels);

    void setYLabelValues(double[] values);

    void setXLabels(String[] labels);

    void setXLabelValues(double[] values);

    String[] getYLabels();

    double[] getYLabelValues();

    String[] getXLabels();

    double[] getXLabelValues();
}
