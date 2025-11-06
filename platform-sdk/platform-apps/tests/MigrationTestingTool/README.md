# MigrationTestingTool

# Generate a new saved state

1. Checkout the main repo and switch to the right branch, e.g. `release/0.44`

2. Build the repo

   ```bash
   ./gradlew clean assemble
   ```

   It will create `MigrationTestingTool.jar` in platform-sdk/sdk/data/apps/

3. Change `platform-sdk/sdk/config.txt` to set the active app to `MigrationTestingTool.jar` Comment the line with `HashgraphDemo.jar`, which is enabled by default, and uncomment the line with `MigrationTestingTool.jar`. Ensure the parameters of the app match below.

   ```bash
   app,		MigrationTestingTool.jar, 4685095852903873060, 100000, 1000
   ```
4. Set the following config in `platform-sdk/sdk/settings.txt`

   ```bash
   virtualMap.copyFlushThreshold,            10000000
   migrationTestingToolConfig.applyFreezeTimeInRound,            400
   ```

   These settings are used to make sure virtual maps are flushed to disk rather than accumulated in memory, and to be able to freeze the platform and that the state reflects that fact.

5. Located in `$HEDERA_SERVICES_REPO/platform-sdk/sdk`, run the migration tool

   ```bash

   ../swirlds-cli/pcli.sh browse --log4j=log4j2.xml
   ```

   or

   ```bash
   java -jar swirlds.jar
   ```
6. Watch the states in `platform-sdk/sdk/data/saved/com.swirlds.demo.migration.MigrationTestingToolMain/0/123` folder. They are usually saved about once a minute, depending on the `state.saveStatePeriod` setting.
7. Wait until the application reports that the freeze is completed and close the UI.
   The log statement that will be produced looks similar to:

   ```
   2025-06-11 11:48:31.941 5751     INFO  PLATFORM_STATUS  <platformForkJoinThread-3> DefaultStatusStateMachine: Platform spent 12.3 s in FREEZING. Now in FREEZE_COMPLETE {"oldStatus":"FREEZING","newStatus":"FREEZE_COMPLETE"} [com.swirlds.logging.legacy.payload.PlatformStatusPayload]
   ```
8. A previous log line will appear in the file or console like this

   ```
   2025-06-11 11:48:31.934 5749     INFO  STATE_TO_DISK    <<scheduler StateSnapshotManager>> SignedStateFileWriter: Finished writing state for round 411 to disk. Reason: FREEZE_STATE, directory: ./hedera-services/platform-sdk/sdk/data/saved/com.swirlds.demo.migration.MigrationTestingToolMain/3/123/411 {"round":411,"freezeState":true,"reason":"FREEZE_STATE","directory":"file:./hedera-services/platform-sdk/sdk/data/saved/com.swirlds.demo.migration.MigrationTestingToolMain/3/123/411/"} [com.swirlds.logging.legacy.payload.StateSavedToDiskPayload]
   ```

   which will tell:
   (a) the freezeRound (411 in this example)
   (b) the location of the file to upload (`./hedera-services/platform-sdk/sdk/data/saved/com.swirlds.demo.migration.MigrationTestingToolMain/3/123/411/SignedState.swh`)

9. Upload the state to GCP bucket `gs://swirlds-regression/saved-states/MigrationTestingApp/`

   ```bash
   gsutil cp -r (b) gs://swirlds-regression/saved-states/MigrationTestingApp/2023Nov16_v44/
   ```
10. Change version of MTT: see `com.swirlds.demo.migration.MigrationTestingToolMain#SOFTWARE_VERSION`
11. Update regression Repo search for `saved-states/MigrationTestingApp`
    1. Update `location` (eg `gs://swirlds-regression/saved-states/MigrationTestingApp/2023Nov16_v44/`) and `round` (eg `411`) property according to your uploaded state
    2. You can navigate the GCP cloud buckets that contain the available states here [https://console.cloud.google.com/storage/browser/swirlds-regression/saved-states/MigrationTestingApp/2025May19_v62/456?pageState=("StorageObjectListTable":("f":"%255B%255D")](https://console.cloud.google.com/storage/browser/swirlds-regression/saved-states/MigrationTestingApp/2025May19_v62/456?pageState=(%22StorageObjectListTable%22:(%22f%22:%22%255B%255D%22))
