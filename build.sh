#!/bin/bash

docker run --rm -it \
    -v ${PWD}:${PWD} \
    -v /home/ubuntu/.cache:/home/ubuntu/.cache \
    -w ${PWD} \
    -e http_proxy=http://10.5.1.130:7890 \
    -e https_proxy=http://10.5.1.130:7890 \
    gcr.io/bazel-public/bazel:4.2.0 \
    build oauth
