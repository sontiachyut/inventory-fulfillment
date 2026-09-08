# Project instructions

Read docs/STATUS.md, docs/SPEC.md, docs/ROADMAP.md and relevant ADRs before implementation.

- Scope work to the next acceptance gate. Preserve invariants and document behavior changes before implementing them.
- Run ./mvnw verify once available. Do not skip failing tests or silently skip dependency integration suites.
- Keep demo-only in-memory behavior explicit; never label it durable or distributed.
- No fake metrics, altered commit dates, synthetic development history or unsupported production claims.
- Use synthetic fixtures only. Do not import private job-search, employer or contact data.
- No billable cloud resources, real payments or public runtime exposure without owner approval.
- Commit real milestones and update docs/STATUS.md with evidence and next tasks. Future work is not automatically scheduled.
