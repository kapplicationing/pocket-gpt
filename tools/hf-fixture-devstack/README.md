# PocketGPT HF Fixture Devstack

This folder packages `scripts/dev/hf-fixture-server.py` as a short-lived Devstack service so Maestro Cloud can reach the deterministic Hugging Face fixture.

The app still parses canonical `https://huggingface.co/...` URLs. The debug/test endpoint adapter rewrites only the API and download network calls to this fixture endpoint.

## Deploy

Install Devstack if needed:

```bash
brew tap devops/tap git@gitlab.agodadev.io:devops/homebrew.git
brew install devstack
```

If Homebrew requires tap trust, trust the specific formulae and retry:

```bash
brew trust --formula devops/tap/devstack
brew tap metalbear-co/mirrord
brew trust --formula metalbear-co/mirrord/mirrord
brew install devstack
```

Deploy from this directory so the Docker build context paths match `devstack.yaml`:

```bash
cd tools/hf-fixture-devstack
devstack validate
devstack deploy --wait --build
devstack permissions grant --all
devstack ps
```

Use the `https://...devstack.qa.agoda.is` endpoint printed by `deploy --wait` or `devstack ps`:

```bash
export POCKETGPT_HF_FIXTURE_BASE_URL="https://<fixture-endpoint>"
cd ../..
bash scripts/dev/maestro-cloud-hf-fixture-smoke.sh \
  --fixture-base-url "${POCKETGPT_HF_FIXTURE_BASE_URL}" \
  --api-key-env MAESTRO_CLOUD_API_KEY
```

If `devstack validate` reports a VPN or network issue, fix that first. This is
the preferred path for Maestro Cloud because accountless tunnel providers may be
blocked or rewritten by corporate VPN/firewall policy before they reach the local
fixture.

## Local Container Check

```bash
docker build \
  -f tools/hf-fixture-devstack/Dockerfile \
  -t pocketgpt-hf-fixture:local \
  scripts/dev
docker run --rm -p 8765:8765 pocketgpt-hf-fixture:local
```

Then verify:

```bash
curl -fsS http://127.0.0.1:8765/health
curl -fsS 'http://127.0.0.1:8765/api/models?search=tiny'
curl -fsS -H 'Range: bytes=0-0' \
  http://127.0.0.1:8765/fixture/tiny-gguf/resolve/main/tiny.gguf
```

## Sources

This config follows Agoda Devstack guidance from:

- `https://docs.agodadev.io/pages/devenv/devstack/reference/yaml-keyword-reference.html`
- `https://docs.agodadev.io/pages/devenv/devstack/reference/cli.html`
- `https://docs.agodadev.io/pages/devenv/devstack/quickstart.html`
