#!/usr/bin/env bash
set -euo pipefail

pocketgpt_gpu_matrix_model_spec() {
  case "$1" in
    llama_3_2_1b)
      printf '%s\n' 'llama-3.2-1b-instruct-q4_k_m|q4_k_m'
      ;;
    qwen3_0_6b)
      printf '%s\n' 'qwen3-0.6b-q4_k_m|q4_k_m'
      ;;
    qwen3_1_7b)
      printf '%s\n' 'qwen3-1.7b-q4_k_m|q4_k_m'
      ;;
    qwen_0_8b)
      printf '%s\n' 'qwen3.5-0.8b-q4|q4_0'
      ;;
    qwen_0_8b_tiny)
      printf '%s\n' 'qwen3.5-0.8b-q4|ud_iq2_xxs'
      ;;
    *)
      echo "Unsupported model key: $1" >&2
      return 1
      ;;
  esac
}

pocketgpt_gpu_matrix_sanitize() {
  printf '%s' "$1" | tr '/: .' '____'
}

pocketgpt_gpu_matrix_make_flow() {
  local output_path="$1"
  local template_path="$2"
  local model_id="$3"
  local version="$4"
  local run_tag="$5"
  local download_row="${model_id} • ${version}"

  sed \
    -e "s|__TARGET_DOWNLOAD_ROW__|${download_row}|g" \
    -e "s|__TARGET_DOWNLOAD_START__|Start download ${model_id} ${version}|g" \
    -e "s|__TARGET_INSTALLED_ROW__|Installed version ${model_id} ${version}|g" \
    -e "s|__TARGET_ACTIVATE_BUTTON__|Activate version ${model_id} ${version}|g" \
    -e "s|__RUN_TAG__|${run_tag}|g" \
    "${template_path}" > "${output_path}"
}
