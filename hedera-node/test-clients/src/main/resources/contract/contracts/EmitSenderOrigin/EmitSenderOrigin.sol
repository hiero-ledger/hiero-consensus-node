// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.7;

contract EmitSenderOrigin {
    event Info(address origin, address sender);

    function logNow() public {
        emit Info(tx.origin, msg.sender);
    }
}
