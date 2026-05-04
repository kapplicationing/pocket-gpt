# `llama.cpp` Patch Stack

These patches reconstruct the PocketGPT vendor tree on top of the public upstream base commit documented in `docs/architecture/llama-vendor-maintenance.md`.

Use them through `bash scripts/ci/bootstrap_llama_vendor.sh`.

Do not hand-edit the generated patch files. Regenerate them from the intended vendor commit range when the vendor stack changes.
