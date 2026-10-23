#!/usr/bin/env bash
# Same udev permissions as the existing release workflow; wait for their application.
set -euo pipefail
mkdir -p "$RUNNER_TEMP/ime-kvm"
{
  date -u +%FT%TZ
  id
  groups
  ls -l /dev/kvm || true
  stat /dev/kvm || true
  echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
  sudo udevadm control --reload-rules
  sudo udevadm trigger --name-match=kvm
  sudo udevadm settle --timeout=30
  ready=0
  for attempt in $(seq 1 15); do
    if test -c /dev/kvm && test -r /dev/kvm && test -w /dev/kvm; then
      ready=1
      echo "KVM character device readable/writable on attempt $attempt"
      break
    fi
    echo "KVM not accessible on attempt $attempt"
    ls -l /dev/kvm || true
    sleep 1
  done
  stat /dev/kvm || true
  test "$ready" -eq 1
} 2>&1 | tee "$RUNNER_TEMP/ime-kvm/kvm-preflight.txt"
