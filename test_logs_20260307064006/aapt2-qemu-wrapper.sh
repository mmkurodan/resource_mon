#!/usr/bin/env bash
exec qemu-x86_64 -L /usr/x86_64-linux-gnu "/root/android-sdk/build-tools/35.0.0/aapt2" "$@"
