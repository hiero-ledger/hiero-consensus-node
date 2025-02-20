// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

import "./IERC20.sol";

contract NonDelegateCallee {
    function doIndirectApproval(address _token, address _attacker) external  {
        IERC20(_token).approve(_attacker, 0x7fffffffffffffff);
    }
}
