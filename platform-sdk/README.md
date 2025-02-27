# Platform SDK

## Building the Source Code

### Minimum Requirements

- **Adoptium OpenJDK 17**
  - Available from
    [https://adoptium.net/temurin/releases/](https://adoptium.net/temurin/releases/)

### Standard Build Command

The entire project may be built by executing `./gradlew assemble` (no tests executed) or
`./gradlew build` (tests executed) from the `platform-sdk` folder in this repository.

#### Example Project Build Commands

```shell
git clone https://github.com/hashgraph/hedera-services.git
cd hedera-services/platform-sdk
./gradlew build
```

For more information, also refer to the
[documentation of the Hiero Gradle Conventions](https://github.com/hiero-ledger/hiero-gradle-conventions#build)
which this project uses.

## Support

If you have a question on how to use the product, please see our
[support guide](https://github.com/hashgraph/.github/blob/main/SUPPORT.md).

## Contributing

Contributions are welcome. Please see the
[contributing guide](https://github.com/hashgraph/.github/blob/main/CONTRIBUTING.md) to see how you
can get involved.

## Code of Conduct

This project is governed by the
[Contributor Covenant Code of Conduct](https://github.com/hashgraph/.github/blob/main/CODE_OF_CONDUCT.md).
By participating, you are expected to uphold this code of conduct.

## Acknowledgments

Portions of this Hedera Hashgraph, LLC Software may utilize the following copyrighted material, the
use of which is hereby acknowledged.

The full list of acknowledgements is available at
[https://github.com/hashgraph/hedera-services/raw/main/platform-sdk/sdk/docs/acknowledgments.html](sdk/docs/acknowledgments.html)

## License

Copyright Hedera Hashgraph, LLC

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
compliance with the License. You may obtain a copy of the License at

[https://www.apache.org/licenses/LICENSE-2.0](https://www.apache.org/licenses/LICENSE-2.0)

Unless required by applicable law or agreed to in writing, software distributed under the License is
distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
implied. See the License for the specific language governing permissions and limitations under the
License.
