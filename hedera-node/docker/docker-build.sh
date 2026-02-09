#!/usr/bin/env bash

if [[ $# -lt 2 ]]; then
  echo "Usage: ${0} [tag] [project_dir]"
  exit 1
fi

TAG=$(./read-env.sh)
echo "CREATING CONTAINER FOR VERSION ${TAG}"
echo "Using project directory: ${2}"
echo

echo "Building container:"
docker buildx build --load -t "services-node:${TAG}" --build-context services-data=../data . || exit "${?}"
