# Contributing

`cloud-itonami-isic-3811` accepts contributions to the OSS actor, governor
tests, documentation, examples and open business blueprint.

## Development

```bash
clojure -M:dev:test
clojure -M:lint
```

Keep changes small and include tests for governor, audit, store or
disclosure behavior.

## Rules

- Do not commit real generator/facility records or customer contract
  documents.
- Keep production scheduling and disclosures behind WasteDispatchGovernor.
- Treat every new waste-class or facility integration as high-risk: add
  tests for hazard-misclassification-gate, facility-permit-capacity-gate,
  source-provenance-gate, licensed-disclosure and audit logging.
- Never allow a hazardous-waste field or code path to bypass the
  hazard-misclassification-gate — this actor is structurally non-hazardous
  collection only. If a proposed feature needs hazardous-waste handling,
  it does not belong in this repository — raise it as an ADR instead.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which governor invariant is affected
- how it was tested
- whether operator or certification docs need updates
