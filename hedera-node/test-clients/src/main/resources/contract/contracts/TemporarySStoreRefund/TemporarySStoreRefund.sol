// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.5.0;

contract TemporarySStoreRefund {

    uint value = 0;

    function holdTemporary(uint _tempValue) external {
        value = _tempValue;
        value = 0;
    }

    function holdPermanently(uint _permanentValue) external {
        value = _permanentValue;
        value = _permanentValue + 1;
    }

}
