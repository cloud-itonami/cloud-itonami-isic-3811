# cloud-itonami-isic-3811

Open Business Blueprint for **ISIC Rev.4 3811**: collection of
non-hazardous waste — a municipal/commercial waste-collection dispatch and
routing service, published as an OSS business that any qualified operator
can fork, deploy, run, improve and sell.

Schedules pickups, records collection manifests and serves client reports,
all while structurally refusing anything hazardous — this actor covers
**non-hazardous** collection only, by design, never by a filter someone
could forget to call. Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph runtime
(portable `.cljc`, supervised superstep loop, interrupts, Datomic/in-mem
checkpoints) — the same actor pattern as
[`cloud-itonami-isic-6311`](https://github.com/cloud-itonami/cloud-itonami-isic-6311)
and [`cloud-itonami-isic-7820`](https://github.com/cloud-itonami/cloud-itonami-isic-7820).

> **Why an actor layer at all?** A WasteDispatch-LLM is great at
> normalizing intake requests, drafting manifest records, and proposing
> client-report column sets — but it has **no notion of hazard
> classification discipline, a facility's permitted intake capacity, or a
> client's disclosure entitlement**. Letting it schedule directly invites
> a hazard-flagged stream silently routed as routine, over-commitment past
> a facility's environmental permit, or over-disclosure beyond a
> contract's tier. This project seals the WasteDispatch-LLM into a single
> node and wraps it with an independent **WasteDispatchGovernor**, a human
> **review workflow**, and an immutable **audit ledger**.

## Scope (deliberately narrow — read this before anything else)

This actor **schedules pickups, records manifests and serves reports for
non-hazardous waste only**. There is no field anywhere in this schema for
hazardous-waste handling, storage or transport (see
`docs/adr/0001-architecture.md`) — a pickup carrying ANY hazard flag is
structurally rejected by the hazard-misclassification-gate, at any
confidence, regardless of phase. Classification claims must cite a real
regulatory-framework-grounded basis (`src/wastecollect/facts.cljc`): US
RCRA, EU Waste Framework Directive, or Japan's 廃棄物処理法.

## Consuming this actor from another blueprint

`:report/query` is the governed read surface: a pickup's schedule/manifest
data, columns limited to your contract tier. It always runs through the
WasteDispatchGovernor's licensed-disclosure check — there is no bypass.

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full architecture and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
decision record. See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an open
business on itonami.cloud.

## Open business

This repository is not only source code. It is a public, forkable business
model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, WasteDispatchGovernor, governed disclosure, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, deploy, support and sell the service |
| Trust controls | Governance, security reporting, policy tests, audit requirements |

The primary industry classification is **ISIC Rev.4 3811** because the
commercial activity is collecting non-hazardous waste on behalf of
generators and delivering it to permitted facilities.

## The core contract

```
request + injected role/tenant/phase context
        │
        ▼
   ┌──────────────────┐  proposal      ┌───────────────────────┐
   │ WasteDispatch-LLM │ ──────────────▶│ WasteDispatchGovernor  │  (independent system)
   │ (sealed)          │  draft + basis │  hazard · capacity ·   │
   └──────────────────┘  citation       │  provenance · human    │
                                        └───────────────────────┘
                                              │
                                   commit / serve only if allowed
                                              ▼
                                    append-only audit ledger
```

**Single invariant**: WasteDispatch-LLM never schedules, records or
discloses anything the WasteDispatchGovernor would reject.

## Run

```bash
clojure -M:dev:test   # governor contract · store parity · phases · facts
clojure -M:dev:run    # 8-operation demo through one OperationActor
clojure -M:lint
```

## Non-Negotiables

- Do not commit real generator/facility records or client contract
  documents.
- Do not add a schema field for hazardous-waste handling, storage or
  transport.
- Do not bypass the WasteDispatchGovernor for production scheduling or
  disclosures.
- Do not serve a disclosure without an active, registered contract.
- Do not fabricate a classification-basis catalog entry.

License: AGPL-3.0-or-later.
