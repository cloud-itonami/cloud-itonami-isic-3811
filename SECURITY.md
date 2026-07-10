# Security Policy

This project handles waste-generator, facility-permit and client-billing
data. Treat vulnerabilities as potentially high impact even when the demo
data is synthetic — a hazard-misclassification or facility-permit-capacity
bypass has direct environmental and regulatory consequences.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real generator/facility record exposure
- authorization bypass
- WasteDispatchGovernor bypass (hazard-misclassification-gate,
  facility-permit-capacity-gate, source-provenance-gate)
- audit-ledger tampering
- over-disclosure beyond a client-billing contract's tier
- tenant isolation failures
- scheduling of a hazard-flagged pickup through an undocumented path

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on classification data, governor enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real generator/facility/client data outside this repository.
- Run governor tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for dispatch coordinators and service accounts.
- Alert on any hazard-misclassification-gate or facility-permit-capacity-
  gate HOLD spike — it may indicate a mislabeled waste stream or a
  facility approaching its permit limit.
