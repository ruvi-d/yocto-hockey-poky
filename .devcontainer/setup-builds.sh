#!/bin/bash
set -e

# Create build folders sequentially
KAS_BUILD_DIR=build KAS_MACHINE=beaglebone-yocto kas checkout .config.yaml:.devcontainer.config.yml

KAS_BUILD_DIR=build-ti KAS_MACHINE=beaglebone kas checkout .config.yaml:.config.ti-sdk.yaml:.devcontainer.config.yml

KAS_BUILD_DIR=build-st KAS_MACHINE=stm32mp15-disco kas checkout .config.yaml:.config.st.yaml:.devcontainer.config.yml