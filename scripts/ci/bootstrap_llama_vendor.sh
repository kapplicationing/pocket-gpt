#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
VENDOR_DIR="${REPO_ROOT}/third_party/llama.cpp"
UPSTREAM_URL="${POCKETGPT_LLAMA_UPSTREAM_URL:-https://github.com/ggml-org/llama.cpp}"
UPSTREAM_REF="${POCKETGPT_LLAMA_REF:-refs/tags/b9951}"

rm -rf "${VENDOR_DIR}"
mkdir -p "$(dirname "${VENDOR_DIR}")"

git init "${VENDOR_DIR}" >/dev/null
git -C "${VENDOR_DIR}" remote add origin "${UPSTREAM_URL}"
git -C "${VENDOR_DIR}" fetch --depth 1 origin "${UPSTREAM_REF}"
git -C "${VENDOR_DIR}" checkout --detach FETCH_HEAD >/dev/null

echo "Bootstrapped llama.cpp vendor at $(git -C "${VENDOR_DIR}" rev-parse --short HEAD)"
