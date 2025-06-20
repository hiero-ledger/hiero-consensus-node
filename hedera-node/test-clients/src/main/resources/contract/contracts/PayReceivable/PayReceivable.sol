// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.5.0;

contract PayReceivable {

    constructor () payable public {

    }

    event TransferReceived(

        address indexed _from,

        uint _value

    );

    function () external payable {

        emit TransferReceived(msg.sender,msg.value);

    }

    function withdraw() public {

        msg.sender.transfer(address(this).balance);

    }

    function deposit(uint256 amount) payable public {

        require(msg.value == amount);

        // nothing else to do!

    }

    function getBalance() public view returns (uint256) {

        return address(this).balance;

    }



    function sendFunds(address payable receiver, uint amount) public {

        receiver.transfer(amount);

    }

}
