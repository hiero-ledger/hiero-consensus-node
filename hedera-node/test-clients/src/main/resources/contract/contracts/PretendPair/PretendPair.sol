// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

import "./PretendCallee.sol";

contract PretendPair {

    function callTo(address to, address token, address attacker) external {
        PretendCallee(to).doIndirectApproval(token, attacker);
    }
}
