#!/usr/bin/env bash
set -euo pipefail

pocketgpt_gpu_matrix_model_spec() {
  case "$1" in
    smollm3_3b)
      printf '%s\n' 'smollm3-3b-q4_k_m|q4_k_m'
      ;;
    phi_4_mini)
      printf '%s\n' 'phi-4-mini-instruct-q4_k_m|q4_k_m'
      ;;
    qwen3_0_6b)
      printf '%s\n' 'qwen3-0.6b-q4_k_m|q4_k_m'
      ;;
    qwen_0_8b)
      printf '%s\n' 'qwen3.5-0.8b-q4|q4_0'
      ;;
    qwen_2b)
      printf '%s\n' 'qwen3.5-2b-q4|q4_0'
      ;;
    qwen_0_8b_tiny)
      printf '%s\n' 'qwen3.5-0.8b-q4|ud_iq2_xxs'
      ;;
    qwen_2b_tiny)
      printf '%s\n' 'qwen3.5-2b-q4|ud_iq2_xxs'
      ;;
    gemma_4_e2b)
      printf '%s\n' 'gemma-4-e2b-it-q4_k_m|q4_k_m'
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
