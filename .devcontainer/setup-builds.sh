#!/bin/bash
set -e

# Create build folders sequentially
KAS_BUILD_DIR=build KAS_MACHINE=beaglebone-yocto kas checkout .config.yaml:.devcontainer.config.yml
