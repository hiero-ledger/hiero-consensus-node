[![Node: Build Application](https://github.com/hashgraph/hedera-services/actions/workflows/node-flow-build-application.yaml/badge.svg)](https://github.com/hashgraph/hedera-services/actions/workflows/node-flow-build-application.yaml)
[![Artifact Determinism](https://github.com/hashgraph/hedera-services/actions/workflows/flow-artifact-determinism.yaml/badge.svg)](https://github.com/hashgraph/hedera-services/actions/workflows/flow-artifact-determinism.yaml)
[![Node: Performance Tests](https://github.com/hashgraph/hedera-services/actions/workflows/flow-node-performance-tests.yaml/badge.svg)](https://github.com/hashgraph/hedera-services/actions/workflows/flow-node-performance-tests.yaml)

[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/hiero-ledger/hiero-consensus-node/badge)](https://scorecard.dev/viewer/?uri=github.com/hiero-ledger/hiero-consensus-node)
[![CII Best Practices](https://bestpractices.coreinfrastructure.org/projects/10697/badge)](https://bestpractices.coreinfrastructure.org/projects/10697)
[![codecov](https://codecov.io/gh/hashgraph/hedera-services/graph/badge.svg?token=ZPMV8C93DV)](https://codecov.io/gh/hashgraph/hedera-services)
[![Latest Version](https://img.shields.io/github/v/tag/hashgraph/hedera-services?sort=semver&label=version)](README.md)
[![Made With](https://img.shields.io/badge/made_with-java-blue)](https://github.com/hashgraph/hedera-services/)
[![Development Branch](https://img.shields.io/badge/docs-quickstart-green.svg)](docs/gradle-quickstart.md)
[![License](https://img.shields.io/badge/license-apache2-blue.svg)](LICENSE)

# Hiero Consensus Node

Implementation of the Platform and the
[services offered](https://github.com/hashgraph/hedera-protobufs) by nodes in a Hiero based network.

## Overview of child modules

- _platform-sdk/_ - the basic Platform – [documentation](platform-sdk/docs/platformWiki.md)
- _hedera-node/_ - implementation of services on the Platform –
  [documentation](hedera-node/docs/)

## Getting Started

Refer to the [Hiero Architecture and Design](hedera-node/docs/design/design.md) for an architectural overview of the
Hiero Services project.

Refer to the [Quickstart Guide](docs/README.md) for how to work with this project.

## Solidity

Our Contract service support `pragma solidity <=0.8.9`.

## Contributing

Whether you’re fixing bugs, enhancing features, or improving documentation, your contributions are important — let’s build something great together!

Please read our [contributing guide](https://github.com/hiero-ledger/.github/blob/main/CONTRIBUTING.md) to see how you can get involved.

## Code of Conduct

Hiero uses the Linux Foundation Decentralised Trust [Code of Conduct](https://www.lfdecentralizedtrust.org/code-of-conduct).

## License

[Apache License 2.0](LICENSE)
