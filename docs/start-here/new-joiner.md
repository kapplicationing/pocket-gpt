# New Joiner Guide

Use this path to get productive in 10 minutes.

## 1) Understand the project shape

1. Read `README.md`.
2. Read `docs/start-here/README.md`.
3. Read `docs/start-here/resource-map.md`.
4. Read `docs/start-here/source-of-truth-matrix.md`.
5. Open `docs/operations/execution-board.md` for current priorities.

## 2) Verify your local environment

```bash
python3 -m pip install -r tools/devctl/requirements.txt
python3 tools/devctl/main.py doctor
```

If `doctor` fails, fix issues first.

## 3) Run the confidence baseline

Use `scripts/dev/README.md` for the current command ladder. Start with `doctor` and `bash scripts/dev/test.sh fast`, then expand to the relevant device lanes from there.

The default Android evidence matrix is:

1. emulator for the fast bootstrap/runtime leg
2. one connected device for real hardware/OEM proof
3. one cloud path for hosted confirmation

Use the local emulator path in `scripts/dev/README.md` if you do not already have an emulator running.

For UI-focused changes, also run:

```bash
python3 tools/devctl/main.py lane screenshot-pack
python3 tools/devctl/main.py governance screenshot-inventory-check
```

## 4) Find your role lane

- Engineering: `docs/operations/role-playbooks/engineering-playbook.md`
- Product: `docs/operations/role-playbooks/product-playbook.md`
- QA: `docs/operations/role-playbooks/qa-playbook.md`
- Marketing: `docs/operations/role-playbooks/marketing-playbook.md`

## 5) Set up on a physical phone (optional)

If you want to test on a real device instead of the emulator:

- `docs/start-here/phone-setup-guide.md`

## 6) Understand first-session UX

If your work touches the initial user journey:

- `docs/start-here/onboarding-spec.md`

## 7) Work contract

1. `docs/operations/execution-board.md` is the only mutable status board.
2. Ticket specs live under `docs/operations/tickets/`.
3. Keep command/process details in canonical docs only:
   - `scripts/dev/README.md`
   - `docs/testing/test-strategy.md`
   - `docs/testing/runbooks.md`
4. Keep drift risks in `docs/start-here/documentation-drift-register.md`.
5. Evidence notes live under `docs/operations/evidence/` and follow retention policy in `docs/operations/evidence/index.md`.
