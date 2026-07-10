# Open Business Blueprint: cloud-itonami-isic-3811

This repository publishes an OSS business model for operating a
non-hazardous waste-collection dispatch and routing service on
itonami.cloud, with a governed schedule-collect-report operating model.

## Classification

- Repository name: `cloud-itonami-isic-3811`
- Primary classification: ISIC Rev.4 3811
- Activity: collection of non-hazardous waste (municipal or commercial)
- Served domain: pickup scheduling, collection manifests, facility-permit
  capacity tracking, client reporting — never hazardous-waste handling

## Customer

Primary customers (contracted, licensed access only — never public/
anonymous):

- municipal waste-management departments
- commercial/industrial waste generators needing scheduled pickup
- receiving/transfer facilities needing permit-capacity-aware intake
  scheduling
- other `cloud-itonami-{ISIC}` blueprint operators needing a governed
  waste-logistics capability

## Problem

Waste-collection dispatch software today rarely enforces hazard
classification and facility-permit capacity as hard, structural
constraints — a misclassified stream or a facility running past its
environmental permit is a real regulatory failure mode that manual
processes and loosely-typed software both allow to slip through.

## Offer

Operators provide an OSS actor for non-hazardous waste-collection
dispatch:

- generator and facility reference data
- pickup scheduling with source-cited classification
- collection manifests (actual weight recorded post-pickup)
- structural hazard-misclassification rejection (never scheduled, any
  confidence)
- facility-permit-capacity enforcement per waste-class
- governed, tier-scoped client reporting
- a generator/facility dispute channel, always human-reviewed
- immutable audit ledger of every schedule/manifest/disclosure event

The core promise: WasteDispatch-LLM can draft intake normalization and
manifest records, but it cannot schedule, record, or disclose unless the
independent WasteDispatchGovernor allows it.

## Revenue

Operators can sell:

- per-pickup or per-route metered access
- tiered subscriptions: `:tier/basic` (schedule/status) → `:tier/detailed`
  (+ weights) → `:tier/audit` (+ hazard-flag/source detail)
- managed hosting: monthly subscription per municipality/facility
- facility-permit integration: onboarding a real environmental-permit
  capacity feed
- compliance package: audit export, dispute-handling SLA, security review

| Package | Customer | Price shape |
|---|---|---|
| Basic scheduling | small commercial generator | per-pickup or low monthly tier |
| Detailed tier | municipal waste department | monthly platform fee |
| Audit tier | facility/regulatory compliance team | monthly fee + usage |
| Fleet wholesale | other cloud-itonami operators | API metering |

## Unit Economics

Track these numbers for every operator:

- facility-permit integration hours per new facility
- monthly infrastructure cost
- LLM cost per operation (schedule / manifest / disclosure)
- dispute-handling hours per tenant
- gross margin after infrastructure and support
- churn and expansion revenue per contract tier

## Open Participation

Anyone may fork the repository, run the demo, deploy a self-hosted
instance, submit issues and patches, and create a local operator
business. itonami.cloud should require certification before listing an
operator as a trusted provider.

## Operator Trust Levels

| Level | Capability |
|---|---|
| Contributor | patches, docs, issues, examples |
| Self-host operator | runs their own instance with no platform endorsement |
| Certified operator | listed on itonami.cloud after review |
| Managed operator | may receive leads and operate customer tenants |
| Core maintainer | can approve changes to governor, security and governance |

## Marketplace Metadata

```edn
{:itonami.blueprint/id "cloud-itonami-isic-3811"
 :itonami.blueprint/name "Non-Hazardous Waste Collection Dispatch Actor"
 :itonami.blueprint/isic-rev4 "3811"
 :itonami.blueprint/domain :logistics/waste-collection
 :itonami.blueprint/license "AGPL-3.0-or-later"
 :itonami.blueprint/operator-model :certified-open-business
 :itonami.blueprint/repo "https://github.com/cloud-itonami/cloud-itonami-isic-3811"
 :itonami.blueprint/status :public-oss
 :itonami.blueprint/required-technologies [:identity :forms :audit-ledger]
 :itonami.blueprint/optional-technologies [:dmn :bpmn]}
```

## Non-Negotiables

- Do not commit real generator/facility records or client contract
  documents.
- Do not add a schema field for hazardous-waste handling, storage or
  transport.
- Do not bypass the WasteDispatchGovernor for production scheduling or
  disclosures.
- Do not serve a disclosure to a tenant without an active, registered
  contract.
- Do not fabricate a classification-basis catalog entry to expand
  apparent coverage.
- Do not market an uncertified deployment as an itonami.cloud certified
  operator.
