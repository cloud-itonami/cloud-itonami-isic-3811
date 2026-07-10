# Governance

`cloud-itonami-isic-3811` is an OSS open-business blueprint. Governance
covers both code and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- WasteDispatch-LLM cannot directly schedule, record or resolve a dispute
  request.
- WasteDispatchGovernor remains independent of the advisor.
- hard governor violations (hazard-misclassification-gate,
  facility-permit-capacity-gate, source-provenance-gate,
  licensed-disclosure) cannot be overridden by human approval.
- a generator/facility dispute request never auto-resolves, at any
  rollout phase.
- a hazard-flagged pickup is never scheduled, at any confidence, any
  phase.
- every commit, hold and disclosure event is auditable.
- no schema field exists for hazardous-waste handling, storage or
  transport — scope is structural, not a runtime filter someone could
  forget to call.
- real generator/facility/customer data stays outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, disclosure scope, public business model, operator
certification or license should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit, support and
data-flow review.

Certified operators can lose certification for:

- bypassing governor checks
- disclosing data to an uncontracted party
- scheduling a hazard-flagged pickup as non-hazardous
- exceeding a facility's permitted daily intake capacity
- misrepresenting certification status
- failing to respond to security incidents or classification disputes
- hiding material changes to customer-facing operation
