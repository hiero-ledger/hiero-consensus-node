// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.3;

contract CircularTransfers {
    address[] nodes;

    constructor() public payable { }

    function setNodes(uint160[] memory accounts) public payable {
        for (uint32 i = 0; i < accounts.length; i++) {
            nodes.push(address(uint160(accounts[i])));
        }
    }

    function receiveAndSend(
        uint32 keepAmountDivisor,
        uint stopBalance
    ) public payable {
        require(nodes.length > 1);
        address here = address(this);
        uint balanceToRemain = here.balance / keepAmountDivisor;
        if (balanceToRemain > stopBalance) {
            uint balanceToTransfer = here.balance - balanceToRemain;
            uint32 i = me();
            uint32 j = uint32((i + 1) % nodes.length);
            CircularTransfers next = CircularTransfers(nodes[j]);
            uint32 nextKeepAmountDivisor = keepAmountDivisor + uint32(1);
            next.receiveAndSend{value: balanceToTransfer}(
                uint32(keepAmountDivisor + 1),
                stopBalance
            );
        }
    }

    function me() internal view returns (uint32) {
        for (uint32 i = 0; i < nodes.length; i++) {
            if (nodes[i] == address(this)) {
                return i;
            }
        }
        return uint32(nodes.length);
    }
}
