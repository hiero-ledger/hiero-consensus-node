# Service Configuration

The bulleted list below provides a comprehensive overview of the various configuration settings used in the Hedera Services. These settings are defined in various Config classes,
each managing a specific aspect of the system. For instance,
`AccountsConfig.java` controls account-related properties, while
`ContractsConfig.java` manages smart contract settings.
This document serves as a valuable resource for understanding how
Hedera Services can be customized and configured to meet specific requirements.
For more detailed information on other types of configuration, please refer to the
[Service Configuration](hedera-node/docs/services-configuration.md).

- **AccountsConfig.java**: Provides configuration settings for various account-related properties, including admin roles and treasury accounts.
- **ApiPermissionConfig.java**: Manages permissions for different API functionalities, specifying which accounts are allowed to execute certain operations.
- **AutoCreationConfig.java**: Configures settings related to the automatic creation of accounts.
- **AutoRenew2Config.java**: Contains settings for automatic renewal processes, specifying scan and renewal parameters.
- **AutoRenewConfig.java**: Defines configurations for the auto-renewal feature, including target types for auto-renewal.
- **BalancesConfig.java**: Manages the export and handling of account balance information, including export directory and compression settings.
- **BlockRecordStreamConfig.java**: Configures the parameters for recording and streaming block records, including file directories and compression options.
- **BlockStreamConfig.java**: Manages settings for the block stream, including stream mode and file compression.
- **BootstrapConfig.java**: Contains initial configuration settings for bootstrapping the system, including fee schedules, public keys, and rate settings.
- **CacheConfig.java**: Manages settings related to caching, including record TTL and the number of warm-up threads.
- **ConsensusConfig.java**: Configures various consensus-related settings, including message size limits and handling parameters for preceding and following records.
- **ContractsConfig.java**: Manages configurations for smart contracts, including storage fees, delegate caller permissions, and gas usage limits.
- **EntitiesConfig.java**: Manages settings related to entities, including maximum lifetime and token association limits.
- **ExpiryConfig.java**: Configures expiration-related settings, including throttle resources.
- **FeesConfig.java**: Manages fee-related configurations, including congestion multipliers and token transfer usage multipliers.
- **FilesConfig.java**: Configures file-related settings, including file IDs for various system files and maximum file sizes.
- **GrpcConfig.java**: Manages gRPC configuration settings, including port numbers, message size limits, and node operator port settings.
- **HederaConfig.java**: Contains general Hedera network settings, including user entity IDs, transaction parameters, and active profiles.
- **LazyCreationConfig.java**: Enables or disables the lazy creation feature for accounts.
- **LedgerConfig.java**: Manages ledger-related settings including system accounts, auto-renew periods, and token transfer limits.
- **NettyConfig.java**: Contains settings for Netty-based gRPC server configurations, including connection limits, retry intervals, and TLS paths.
- **NetworkAdminConfig.java**: Configures the network administration service, including paths for upgrade artifacts and system file locations.
- **NodesConfig.java**: Manages node-related settings such as maximum number of nodes, gossip configurations, and service endpoints.
- **RatesConfig.java**: Contains settings for rate configurations including intraday change limits and midnight check intervals.
- **SchedulingConfig.java**: Manages scheduling-related settings, including transaction per second limits and expiration times.
- **StakingConfig.java**: Configures staking settings including reward percentages, period durations, and enabled states.
- **StatsConfig.java**: Manages settings related to statistics collection, including throttle sampling and update intervals.
- **TokensConfig.java**: Contains token-related settings such as maximum number of tokens, metadata sizes, and NFT configurations.
- **TopicsConfig.java**: Configures topic-related settings including the maximum number of topics.
- **TraceabilityConfig.java**: Manages traceability settings including export limits and gas throttle ratios.
- **TssConfig.java**: Configures the Threshold Signature Scheme (TSS) service, including retry settings and ledger ID enabling.
- **UtilPrngConfig.java**: Contains settings related to the pseudo-random number generator utility.
- **VersionConfig.java**: Manages version-related settings including software version numbers and configuration versions.
