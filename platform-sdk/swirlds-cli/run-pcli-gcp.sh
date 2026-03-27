#! /bin/sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

SERVER_NAME=mainnet

        STATE_LOC="/Users/josh/Documents/GitHub/hiero-consensus-node/${SERVER_NAME}/latest-round/"
EVENT_STREAMS_LOC="/Users/josh/Documents/GitHub/hiero-consensus-node/${SERVER_NAME}/events"
             DDIR=/Users/josh/Documents/GitHub/hiero-consensus-node/hedera-node/data
CONFIG_PATH="${REPO_ROOT}/hedera-node/settings.txt"


OUT_RECORD_STREAMS="${DDIR}/${SERVER_NAME}_legacy/recordStreams"

echo "script dir is ${SCRIPT_DIR}"
echo "data dir is ${DDIR}"
echo "state dir is ${STATE_LOC}"
echo "events dir is ${EVENT_STREAMS_LOC}"
rm -rf "${SCRIPT_DIR}/out" "${SCRIPT_DIR}/data/apps" "${SCRIPT_DIR}/data/lib" "${OUT_RECORD_STREAMS}" "${SCRIPT_DIR}/data"
echo "making links"
mkdir "data"
ln -s "${DDIR}/apps" "${SCRIPT_DIR}/data/apps"
ln -s "${DDIR}/lib" "${SCRIPT_DIR}/data/lib"
echo "config path is ${CONFIG_PATH}"
echo "writing record stream to ${OUT_RECORD_STREAMS}"
"${SCRIPT_DIR}/pcli.sh" event-stream recover \
  --id=0 \
  --config="${CONFIG_PATH}" \
  --load-signing-keys \
  -L "${SCRIPT_DIR}/data/lib" -L "${SCRIPT_DIR}/data/apps" \
  -J "-Dhedera.recordStream.logDir=${OUT_RECORD_STREAMS}" \
  -J "-DblockStream.blockFileDir=${DDIR}/blockStreams" \
  -J "-Dhedera.recordStream.wrappedRecordHashesDir=${DDIR}/config" \
  -J "-Xms36g" \
  "${STATE_LOC}" "${EVENT_STREAMS_LOC}"