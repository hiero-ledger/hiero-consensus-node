// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

public class PayloadProperty {
    private int size = 100;
    private PAYLOAD_TYPE type;

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public PAYLOAD_TYPE getType() {
        return type;
    }

    public void setType(PAYLOAD_TYPE type) {
        this.type = type;
    }

    public PayloadProperty() {}

    public PayloadProperty(int size, PAYLOAD_TYPE type) {
        super();
        this.size = size;
        this.type = type;
    }
}
