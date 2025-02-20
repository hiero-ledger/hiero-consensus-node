// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.4.0;

contract Child {

    uint public storeThis = 1;

}

contract FailingChild {

    constructor () public {
        require(false);
    }

}

contract FactoryContract {

    function deploymentSuccess() public {
        new Child();
    }

    function failureAfterDeploy() public {
        new Child();
        require(false);
    }

    function deploymentFailure() public {
        new FailingChild();
    }

}
