#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
VENDOR_DIR="${REPO_ROOT}/third_party/llama.cpp"
PATCH_DIR="${REPO_ROOT}/third_party/llama.cpp-patches"
UPSTREAM_URL="${POCKETGPT_LLAMA_UPSTREAM_URL:-https://github.com/ggml-org/llama.cpp}"
UPSTREAM_BASE_REF="${POCKETGPT_LLAMA_BASE_REF:-refs/tags/b8265}"

if [[ ! -d "${PATCH_DIR}" ]]; then
  echo "Patch directory is missing: ${PATCH_DIR}" >&2
  exit 1
fi

rm -rf "${VENDOR_DIR}"
mkdir -p "$(dirname "${VENDOR_DIR}")"

git init "${VENDOR_DIR}" >/dev/null
git -C "${VENDOR_DIR}" remote add origin "${UPSTREAM_URL}"
git -C "${VENDOR_DIR}" fetch --depth 1 origin "${UPSTREAM_BASE_REF}"
git -C "${VENDOR_DIR}" checkout --detach FETCH_HEAD >/dev/null
git -C "${VENDOR_DIR}" config user.name "PocketGPT CI"
git -C "${VENDOR_DIR}" config user.email "ci@pocketgpt.local"
git -C "${VENDOR_DIR}" am "${PATCH_DIR}"/*.patch >/dev/null

echo "Bootstrapped llama.cpp vendor at $(git -C "${VENDOR_DIR}" rev-parse --short HEAD)"
