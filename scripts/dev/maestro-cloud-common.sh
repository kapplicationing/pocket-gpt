#!/usr/bin/env bash

pocketgpt_load_dotenv() {
  if [[ -f .env ]]; then
    set -a
    # shellcheck disable=SC1091
    source .env
    set +a
  fi
}

pocketgpt_known_maestro_cloud_key_envs() {
  printf '%s\n' \
    "MAESTRO_CLOUD_API_KEY" \
    "MAESTRO_CLOUD_API_KEY_2" \
    "MAESTRO_CLOUD_API_KEY_3"
}

pocketgpt_default_maestro_cloud_key_env() {
  printf '%s\n' "MAESTRO_CLOUD_API_KEY"
}

pocketgpt_maestro_cloud_env_is_supported() {
  case "${1:-}" in
    MAESTRO_CLOUD_API_KEY|MAESTRO_CLOUD_API_KEY_2|MAESTRO_CLOUD_API_KEY_3)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

pocketgpt_available_maestro_cloud_key_envs() {
  local key
  while IFS= read -r key; do
    if [[ -n "${!key:-}" ]]; then
      printf '%s\n' "${key}"
    fi
  done < <(pocketgpt_known_maestro_cloud_key_envs)
}

pocketgpt_maestro_cloud_account_label() {
  case "${1:-}" in
    MAESTRO_CLOUD_API_KEY)
      printf '%s\n' "account-1"
      ;;
    MAESTRO_CLOUD_API_KEY_2)
      printf '%s\n' "account-2"
      ;;
    MAESTRO_CLOUD_API_KEY_3)
      printf '%s\n' "account-3"
      ;;
    *)
      printf '%s' "${1:-unknown}" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9_' '-'
      printf '\n'
      ;;
  esac
}

pocketgpt_maestro_cloud_describe_available_accounts() {
  local available=()
  local key
  while IFS= read -r key; do
    [[ -n "${key}" ]] || continue
    available+=("${key}")
  done < <(pocketgpt_available_maestro_cloud_key_envs)

  if [[ ${#available[@]} -eq 0 ]]; then
    printf '%s\n' "none configured"
    return 0
  fi

  printf '%s\n' "${available[*]}"
}

pocketgpt_print_maestro_cloud_account_banner() {
  local requested_env="${1:?missing api key env}"
  printf 'Using Maestro Cloud %s (%s)\n' \
    "$(pocketgpt_maestro_cloud_account_label "${requested_env}")" \
    "${requested_env}"
}

pocketgpt_append_maestro_cloud_api_levels() {
  local array_name="${1:?missing array name}"
  local raw_levels="${2:?missing api levels}"
  local parsed_levels=()
  local level

  IFS=',' read -r -a parsed_levels <<< "${raw_levels}"
  for level in "${parsed_levels[@]}"; do
    level="${level//[[:space:]]/}"
    [[ -n "${level}" ]] || continue
    eval "${array_name}+=(\"\${level}\")"
  done
}

pocketgpt_redacted_maestro_cloud_command() {
  local previous=""
  local arg
  for arg in "$@"; do
    if [[ "${previous}" == "--api-key" ]]; then
      printf '%q ' "<redacted>"
    else
      printf '%q ' "${arg}"
    fi
    previous="${arg}"
  done
  printf '\n'
}

pocketgpt_require_maestro_cloud_api_key() {
  local requested_env="${1:-$(pocketgpt_default_maestro_cloud_key_env)}"
  if ! pocketgpt_maestro_cloud_env_is_supported "${requested_env}"; then
    echo "Unsupported Maestro Cloud API key env: ${requested_env}" >&2
    echo "Supported envs: $(pocketgpt_known_maestro_cloud_key_envs | tr '\n' ' ' | sed 's/[[:space:]]*$//')" >&2
    return 1
  fi
  if [[ -z "${!requested_env:-}" ]]; then
    echo "Set ${requested_env} in .env." >&2
    echo "Configured accounts: $(pocketgpt_maestro_cloud_describe_available_accounts)" >&2
    return 1
  fi
  MAESTRO_CLOUD_API_KEY_ENV="${requested_env}"
  MAESTRO_CLOUD_API_KEY_SELECTED="${!requested_env}"
  export MAESTRO_CLOUD_API_KEY_ENV
  export MAESTRO_CLOUD_API_KEY_SELECTED
}
