// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.7;

contract CalldataSize {
    event Info(uint size);

    function callme(bytes calldata _calldata) public {
        emit Info(_calldata.length);
    }
}
