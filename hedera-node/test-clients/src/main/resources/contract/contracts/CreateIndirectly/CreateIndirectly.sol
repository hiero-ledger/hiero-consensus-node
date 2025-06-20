// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.9;
import "./ToyMaker.sol";

contract CreateIndirectly {
    function makeOpaquely(address makerAddress) public returns (address) {
        ToyMaker maker = ToyMaker(makerAddress);
        return maker.make();
    }
}
