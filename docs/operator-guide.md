# Operator Guide

This guide is for people who want to start an open business from
`cloud-itonami-isic-3811`.

## 1. Fork and Run

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-3811
cd cloud-itonami-isic-3811
clojure -M:dev:test
clojure -M:dev:run
```

The default demo uses entirely fictitious generators, facilities and
pickups. Production data must stay outside the repository and be injected
through a store adapter, and every classification claim must carry a
real, citable regulatory-basis source.

## 2. Choose an Operating Mode

| Mode | Use when |
|---|---|
| Demo | validating the actor and governor contract |
| Self-host | one organization owns infrastructure and data |
| Managed tenant | an operator hosts for a customer |
| Certified operator | itonami.cloud has reviewed security and process controls |

## 3. Production Checklist

- replace demo generators/facilities/pickups with real, source-cited data
  (extend `wastecollect.facts/catalog` honestly for real regulatory
  frameworks — never fabricate one)
- register each real facility's actual permitted daily capacity per
  waste-class (`daily-capacity-kg` in `wastecollect.store`)
- configure Datomic Local, kotoba-server or an equivalent durable SSoT
- configure the LLM adapter through environment variables or secret manager
- define client-billing contract tenants/tiers and RBAC rules
- run `clojure -M:dev:test`
- run `clojure -M:lint`
- verify audit-ledger export
- document backup and restore
- document incident response
- document the dispute-handling SLA
- get written legal review for the jurisdictions you serve (hazardous-
  waste classification and facility-permit regulation vary by
  jurisdiction)

## 4. Sales Motion

Start with a narrow offer:

1. onboard one real facility with its actual permitted capacity
2. prove governed, tier-scoped disclosure end to end
3. run one manifest-recording workflow in assisted mode (human-approved)
4. export the audit ledger for review
5. convert to a metered or subscription contract

## 5. Certification Requirements

itonami.cloud certification should require:

- passing tests and lint on the published version
- written data-flow diagram (intake → governor → disclosure)
- backup/restore evidence
- incident contact and response window
- proof that production scheduling/disclosures go through
  WasteDispatchGovernor
- proof that real generator/facility/customer data is not stored in Git
- proof that a dispute channel exists and is human-reviewed
- customer-facing support and licensing terms

## 6. Operator Responsibilities

Operators are responsible for:

- lawful basis for each classification-basis source used
- local hazardous-waste-classification and facility-permit-law review
- secure infrastructure and tenant isolation
- honest classification-catalog maintenance
- human review workflow for dispute-request operations
- data-retention policy
- security updates

The OSS project provides software and an operating blueprint. It does not
make an operator compliant by itself, and it does not license or endorse
hazardous-waste handling of any kind — that is structurally out of scope.
