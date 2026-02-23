// SPDX-License-Identifier: Apache-2.0
pragma solidity >=0.8.0 <0.9.0;
pragma experimental ABIEncoderV2;

import "./IHieroAccountAllowanceHook.sol";
import "./IHieroHook.sol";
import "./IERC20.sol";

contract TinyCreate {
    function ping() external pure returns (uint256) { return 1; }
}

contract CreateOpHook is IHieroAccountAllowanceHook {
    address constant HOOK_ADDR = address(uint160(0x16d));
    event Created(address child);

    function allow(
        IHieroHook.HookContext calldata context,
        ProposedTransfers memory proposedTransfers
    ) external payable override returns (bool) {
        require(address(this) == HOOK_ADDR, "Only callable as a hook");
        Child c = new Child();
        emit Created(address(c));
        return true;
    }
}
contract Child {
    address public owner;

    constructor() payable {
        owner = msg.sender;
    }

    function onlyOwner() external view {
        require(msg.sender == owner, "Only owner can call this function");
    }
    event Alive(address who); constructor() payable { emit Alive(address(this)); }
}

