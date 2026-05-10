# Skill Registry

**Delegator use only.** Reads this registry to resolve compact rules, then injects them directly into sub-agent prompts. Sub-agents do NOT read this registry or individual SKILL.md files.

_Last updated: 2026-05-09_

## User Skills

| Trigger | Skill | Path |
|---------|-------|------|
| Code quality, refactoring, TDD, BDD, DDD, SDD, design patterns, simplicity, technical debt, "how to write better" | ultimate-coder | `C:\Users\FGIAQUINTA\.claude\skills\ultimate-coder\SKILL.md` |
| User input handling, authentication, authorization, API keys, secrets, external data, database queries, file uploads | security-coder | `C:\Users\FGIAQUINTA\.claude\skills\security-coder\SKILL.md` |
| Bug investigation, unexpected behavior, performance issue, "why is this happening", reproduction, root cause analysis | debugging-methodology | `C:\Users\FGIAQUINTA\.claude\skills\debugging-methodology\SKILL.md` |
| New endpoints, external API calls, production code, logging, metrics, tracing, alerting, health checks | observability-coder | `C:\Users\FGIAQUINTA\.claude\skills\observability-coder\SKILL.md` |
| Irreversible architectural decision, technology choice, team convention, "why did we choose", ADR | adr-writer | `C:\Users\FGIAQUINTA\.claude\skills\adr-writer\SKILL.md` |
| "Is this done?", acceptance criteria, QA review, test strategy, bug severity, regression, "can we ship?" | qa-coder | `C:\Users\FGIAQUINTA\.claude\skills\qa-coder\SKILL.md` |
| Java, Kotlin, JVM, Spring, JPA, Reactor, Coroutines, SQL, MongoDB, Redis, backend performance, *.java, *.kt | back-end-ultimate-coder | `C:\Users\FGIAQUINTA\.claude\skills\back-end-ultimate-coder\SKILL.md` |
| React, hooks, JSX, TSX, Python, FastAPI, bundle performance, Core Web Vitals, frontend quality | front-end-ultimate-coder | `C:\Users\FGIAQUINTA\.claude\skills\front-end-ultimate-coder\SKILL.md` |
| Architecture decisions, system design, "how should I structure", patterns, module boundaries | architecture-review | `C:\Users\FGIAQUINTA\.claude\skills\architecture-review\SKILL.md` |
| Después de explorar archivos, antes de lanzar subagentes con referencias de código | codebase-map | `C:\Users\FGIAQUINTA\.claude\skills\codebase-map\SKILL.md` |
| After ANY delegation touching Java/Kotlin/Spring/React/Python or producing an architecture decision | mentor-lens | `C:\Users\FGIAQUINTA\.claude\skills\mentor-lens\SKILL.md` |
| User directs orchestration, spawns subagents, coordinates multi-step work | orchestrator | `C:\Users\FGIAQUINTA\.claude\skills\orchestrator\SKILL.md` |
| Reviewing or designing persistence layer, CSV/JSON/Redis/RDBMS choice, N+1 queries, connection pools | persistence-mentor | `C:\Users\FGIAQUINTA\.claude\skills\persistence-mentor\SKILL.md` |
| Writing or updating README, CONTRIBUTING, ARCHITECTURE, API docs, runbooks | technical-docs | `C:\Users\FGIAQUINTA\.claude\skills\technical-docs\SKILL.md` |
| Creating a GitHub issue, bug report, or feature request | issue-creation | `C:\Users\FGIAQUINTA\.claude\skills\issue-creation\SKILL.md` |
| Creating a pull request, opening a PR, preparing changes for review | branch-pr | `C:\Users\FGIAQUINTA\.claude\skills\branch-pr\SKILL.md` |
| MVP, release gate, "funciona todo", "estamos listos para producción", feature freeze, regression, go/no-go | ultimate-mvp-manager | `C:\Users\FGIAQUINTA\.claude\skills\ultimate-mvp-manager\SKILL.md` |
| Sprint planning, backlog grooming, user stories, priorización, CAB, kickoff producción, acceptance criteria, PO role | ultimate-product-owner | `C:\Users\FGIAQUINTA\.claude\skills\ultimate-product-owner\SKILL.md` |
| Team leadership, 1:1, delivery commitment, escalación, team health, tech lead role | ultimate-team-leader | `C:\Users\FGIAQUINTA\.claude\skills\ultimate-team-leader\SKILL.md` |
| Sprint ceremonies, standup, estimation, retrospective, DoD, velocity, burndown, team coordination | ultimate-delivery-team | `C:\Users\FGIAQUINTA\.claude\skills\ultimate-delivery-team\SKILL.md` |
| UX review, user acceptance testing, UAT, bug from user perspective, usabilidad | ultimate-app-user | `C:\Users\FGIAQUINTA\.claude\skills\ultimate-app-user\SKILL.md` |
| Escribir mensaje para stakeholder, usuario de negocio, PO, cliente, "mandá un mensaje", progreso al usuario | ultimate-user-communicator | `C:\Users\FGIAQUINTA\.claude\skills\ultimate-user-communicator\SKILL.md` |
| Mensaje para el equipo, teammates, Slack, standup update, team communication | ultimate-team-communicator | `C:\Users\FGIAQUINTA\.claude\skills\ultimate-team-communicator\SKILL.md` |
| Escalación, pushback, comunicación difícil, conflicto, "cómo le digo", assertive communication | ultimate-assertive-communicator | `C:\Users\FGIAQUINTA\.claude\skills\ultimate-assertive-communicator\SKILL.md` |
| Greeks, delta, gamma, vega, theta, options pricing, vol surface, condor, straddle, backtest, VaR, IBKR, TWS | ultimate-quant-analyst | `C:\Users\FGIAQUINTA\.claude\skills\ultimate-quant-analyst\SKILL.md` |
| Generar standup diario, "qué reporto hoy", reporte de actividad del equipo | daily-standup | `C:\Users\FGIAQUINTA\.claude\skills\daily-standup\SKILL.md` |
| End session, save to engram before /clear, context handoff | end-session | `C:\Users\FGIAQUINTA\.claude\skills\end-session\SKILL.md` |
| MR feedback, PR reviewer comments, "reviewer said", code review feedback, GitLab suggestion | mr-feedback | `C:\Users\FGIAQUINTA\.claude\skills\mr-feedback\SKILL.md` |
| "judgment day", adversarial review, dual review, high-confidence pre-merge review | judgment-day | `C:\Users\FGIAQUINTA\.claude\skills\judgment-day\SKILL.md` |
| Writing Jira story description, acceptance criteria HTML, injecting into Jira editor | jira-story | `C:\Users\FGIAQUINTA\.claude\skills\jira-story\SKILL.md` |
| Optimizing a prompt, improving AI instructions, "mejorar este prompt", prompt engineering | prompt-master | `C:\Users\FGIAQUINTA\.claude\skills\prompt-master\SKILL.md` |
| Creating a new AI skill, documenting reusable agent patterns | skill-creator | `C:\Users\FGIAQUINTA\.claude\skills\skill-creator\SKILL.md` |
| Writing Go tests, Bubbletea TUI testing, table-driven tests, golden files | go-testing | `C:\Users\FGIAQUINTA\.claude\skills\go-testing\SKILL.md` |
| Extract design language from a website URL, design tokens, colors, typography | extract-design | `C:\Users\FGIAQUINTA\.claude\skills\extract-design\SKILL.md` |
| Language/tone for Rioplatense Spanish, voseo, warm energy English, Gentleman persona | gentleman-language | `C:\Users\FGIAQUINTA\.claude\skills\gentleman-language\SKILL.md` |

## Compact Rules

Pre-digested rules per skill. Delegators copy matching blocks into sub-agent prompts as `## Project Standards (auto-resolved)`.

### ultimate-coder
- TDD mandatory: RED (failing test) → GREEN (minimal impl) → REFACTOR. Never write impl without a failing test.
- Four Rules of Simple Design (priority order): passes tests → reveals intention → no duplication → fewest elements.
- SOLID: SRP (one reason to change), OCP (extend without modifying), LSP (substitutability), ISP (small interfaces), DIP (depend on abstractions).
- DRY via Rule of Three: tolerate duplication until the third occurrence, then extract. Wrong abstraction costs more than duplication.
- YAGNI: no "we might need this later" abstractions. KISS: the simple solution that works beats the clever one.
- Names reveal intent: verb phrases for functions, nouns for classes. No `Manager`, `Helper`, `Util`, `data`, `result`, `temp`.
- Test names describe BEHAVIOR: `whenUserHasInsufficientBalance_tradeIsRejected()` not `testTrade()`.
- Concepts > Code: explain WHY before HOW. Push back on shortcuts.

### security-coder
- Every endpoint requires explicit authorization — not just "authenticated", but "authorized for this resource".
- Default deny: if access isn't explicitly granted, deny it. Never rely on client-side role checks.
- Never expose resource IDs without ownership checks (`GET /trades/123` must verify ownership).
- Passwords: bcrypt (cost ≥ 12) or Argon2id. NEVER MD5, SHA1, or SHA256 for passwords.
- JWT: RS256 or HS256 with 256-bit secret. Always verify signature. Access token expiry ≤ 15 min.
- Never pass user input to shell commands. Never deserialize untrusted data (Java ObjectInputStream).
- Log all auth events (success, failure, logout, password change). NEVER log passwords, tokens, or PII.
- CORS: explicit allowlist — never `Access-Control-Allow-Origin: *` for authenticated endpoints.
- Rate-limit everything accepting user input or triggering external calls.
- Secrets → environment variables or secrets manager. Never hardcode. Never commit `.env` with real values.

### observability-coder
- Every new endpoint MUST have RED metrics (Rate, Errors, Duration) from day one.
- All error paths log at ERROR level with correlationId, userId, and full error context.
- All external API calls log request + response (mask sensitive fields — no tokens, no PII).
- Generate correlationId at the outermost boundary; propagate via `X-Correlation-Id` header everywhere.
- Include correlationId in every log entry and every outbound HTTP call.
- Every alert must be actionable (has a runbook) and defined on P99, not P50.
- Health endpoint must be updated when a new dependency is added.
- Pre-PR checklist: RED metrics ✓ | error logging ✓ | external call logging ✓ | correlationId propagated ✓ | no secrets in logs ✓.

### back-end-ultimate-coder
- STRICT TDD: RED → GREEN → REFACTOR. No production code without a failing test first.
- TDD on refactors: write a test capturing the NEW contract FIRST, then change implementation.
- Test names describe behavior: `processEntities_withDuplicateKey_throwsDataIntegrityViolation` not `testProcess`.
- N+1 detection: `stream().filter().findFirst()` inside a loop = O(n²) disaster. Fix: build a `Map` once, lookup O(1).
- `@Transactional` at service layer only. Never on private methods (proxy bypass). Never at controller.
- Domain classes must NEVER import `javax.persistence` or Spring annotations — infrastructure leaks into domain.
- JPA: prefer `JOIN FETCH` / `EntityGraph` / DTO projections over default lazy loading in loops.
- Aggregates protect invariants: one aggregate root per transaction. Use Outbox pattern for reliable event publishing.
- Repositories belong to domain (interface), implementations to infrastructure. Application services orchestrate only.
- Unit tests: pure domain, no Spring context, no DB. Integration tests: Testcontainers with real DB.
- `new ObjectMapper()` per class = loses Spring-registered modules. Inject the bean.
- Missing `@Transactional` + multiple operations = data inconsistency waiting to happen.

### security-coder
_(see security-coder block above — inject that block when touching security-sensitive Java code)_

### front-end-ultimate-coder
- TDD mandatory even for UI: write the failing component/integration test first.
- Prefer container/presentational split: container manages state + data, presentational renders only.
- Hooks: single responsibility. A hook that fetches, formats, and handles errors is three hooks.
- Never mutate state directly. Never put derived state in useState — derive it in the render.
- Bundle: lazy load routes and heavy components. No barrel imports that defeat tree-shaking.
- Core Web Vitals: LCP < 2.5s, CLS < 0.1, INP < 200ms. Measure before optimizing.
- Accessibility: semantic HTML first, ARIA only when semantics don't cover it. Keyboard navigable.
- Error boundaries on every dynamic section. Loading states visible on every async action.

### architecture-review
- Ask before designing: what problem exactly? Scale requirements? Team can maintain this? Operational cost?
- Coupling check: can this module be deployed and changed independently? If no — rethink.
- Data ownership: each service/module owns its data. No shared tables across bounded contexts.
- Prefer events (async) when multiple consumers react to the same fact and eventual consistency is acceptable.
- Prefer sync calls when immediate consistency is required (e.g., payment confirmed before showing success).
- No 2PC (distributed transactions) — use Saga + compensating actions instead.
- Outbox pattern for reliable event publishing: write event to DB in same TX, publish asynchronously.
- Caching: define TTL and invalidation BEFORE implementing. L1 (in-process) → L2 (Redis) → L3 (CDN).
- Observability first: if you can't measure it, you can't optimize it. Add metrics before tuning.
- Irreversibility check: how hard is this decision to reverse in 6 months? Document the tradeoffs now.

### debugging-methodology
- REPRODUCE FIRST. Before touching code: can you reliably reproduce the bug locally?
- Characterize: is it deterministic or intermittent? What are the exact conditions (input, state, timing)?
- Form a hypothesis BEFORE looking at code. What mechanism would explain the symptom?
- Isolate: binary search the call stack. Comment out halves. Find the smallest reproducible case.
- Never "try something and see" without understanding WHY it should fix it.
- When the fix is obvious: write a failing test FIRST that proves the bug, then fix, then the test goes green.
- After the fix: explain root cause, add regression test, check if same pattern exists elsewhere.

### qa-coder
- DoD (Definition of Done): code reviewed ✓ | unit tests written (TDD) ✓ | integration tests ✓ | AC verified ✓ | no regression ✓.
- Coverage minimums: statements ≥ 80%, branches ≥ 75%. Coverage thresholds must not regress.
- Acceptance criteria must be: specific (measurable), testable (pass/fail), and cover happy + unhappy paths.
- Bug severity: S1 (system down/data loss) and S2 (major feature broken) interrupt current sprint, no negotiation.
- Pre-release: smoke test from scratch in clean environment + full regression suite + real credentials on integrations.
- PR without tests = reject (not hostile — quality enforcement). A passing test before impl proves nothing.
- Never disable a regression test because it's slow — fix the slowness.
- E2E tests must cover login, trade execution, and history (critical user paths) for this project.

### adr-writer
- Write an ADR when: one-way door decision, team convention, technology choice, accepted tradeoff, or surprising choice.
- Status lifecycle: Proposed → Accepted → Deprecated → Superseded (always reference which ADR supersedes).
- File naming: `ADR-NNN-short-title.md`, sequential zero-padded integers. Maintain `docs/adr/README.md` index.
- Structure required: Context → Decision → Alternatives Considered (with ✅/❌) → Consequences (benefits + risks).
- Never document obvious implementations, temporary decisions, or decisions already in an existing ADR.

### codebase-map
- SAVE after exploring a new module, reading 4+ files, or completing a discovery session.
- LOAD at session start when working on known code; before launching a subagent touching specific files.
- UPDATE only the changed entry after a subagent modifies a file — not the entire map.
- Always use the same `topic_key: codebase-map/options-quant` (upsert, never duplicate).
- Format: `src/path/File.java — what it does; key method at L42`.
- Max ~40 entries per map. Reorganize by layer if it grows: domain / application / infrastructure / web.

### mentor-lens
- Emit educational block after EVERY delegation touching Java/Kotlin/Spring or producing an arch decision.
- Mechanical tasks (rename, format, commit): emit minimal "tarea mecánica" variant — never omit silently.
- Report token cost: ~Xk tokens | why it cost that | could it be reduced?
- Warn if delegation > 50k tokens for a focused task: "⚠ caro — revisar scope".
- Never omit the educational block — always declare which skill was applied and what pattern was enforced.

### orchestrator
- NEVER write code inline. Every code change goes through a subagent — even 1-line fixes.
- Inline allowed: read 1-3 files to decide/verify; bash for git/gh state; mechanical 1-file atomic writes.
- Delegate: 4+ file reads; any write with analysis; builds/tests; reads as prep for edits.
- Inject compact rules from this registry into EVERY subagent prompt as `## Project Standards (auto-resolved)`.
- After delegation: invoke mentor-lens with (skill applied + what was touched + what changed).
- Token budget: warn at >50k per delegation; ask before >80k; require explicit confirmation >100k.
- Pass file paths to subagents, not file contents — subagents read what they need.

### persistence-mentor
- `@ConfigurationProperties` for structured config — NEVER `@Value` for multi-field groups.
- `@Transactional` at service layer only. Never at controller, never at repository.
- Inject Spring's `ObjectMapper` bean — never `new ObjectMapper()` (loses JavaTimeModule and other modules).
- Atomic file writes: always write to `.tmp` then rename — never `Files.writeString` directly to target.
- Add `schemaVersion` field to any persisted JSON — forward compatibility breaks silently without it.
- `ConcurrentHashMap` is safe for per-key ops but `computeIfAbsent` + side-effects can still race.
- SQLite in this project: WAL mode enabled, busy_timeout=30s. Don't change these without a team decision.
- CSV + in-memory index at startup beats a DB query for read-only reference data under 100k rows.

### technical-docs
- Explore `package.json` / `build.gradle.kts`, `.env.example`, lockfile, docker-compose BEFORE writing.
- README order: badges → description → TOC → prerequisites → setup → env vars → run → test → deploy → arch.
- Env vars: ALWAYS a Markdown table (Variable | Required | Description | Example). Never bullets.
- Setup steps: numbered, copy-pasteable, mentally tested end-to-end.
- Every setup section must cover macOS / Windows / Linux with exact commands per OS.
- No TODO placeholders. No empty sections. No generic text.
- Use codebase-map from engram at start; update after writing docs if paths changed.

### issue-creation
- Use GitHub issue templates only; blank issues are disabled.
- New issues get `status:needs-review`; a maintainer MUST add `status:approved` before any PR.

### branch-pr
- Every PR MUST link an approved issue; every PR MUST have exactly one `type:*` label.
- Conventional commit format: `type(scope): description`. No `Co-Authored-By` trailers.
- Run shellcheck on modified scripts. Automated checks must pass before merge.

### ultimate-mvp-manager
- MVP = all defined use cases working end-to-end, not just unit tests passing.
- "done" = implemented + tested + edge cases known (even if deferred with owner + date documented).
- Feature freeze: no new scope, no refactors without failing test, no dep upgrades unless P0 fix.
- Go/No-Go: GREEN (all gates pass) | YELLOW (known gaps + mitigation + rollback plan) | RED (P0 open = no ship).
- Regression must cover integration points with real credentials, not mocks.
- Smoke test = full happy path from scratch in clean environment before every release.
- No rollback plan = no YELLOW — it becomes RED automatically.
- Decision log required before every scope advance: what shipped, what deferred, who signed off.

### ultimate-product-owner
- Story format: As a [user], I want [goal], so that [reason]. ACs in Given/When/Then.
- Never promise a delivery date without team commitment first. Use "target" not "deadline".
- Production process: CAB Tuesday 16h Spain | Kickoff Friday 11h Spain.
- DoR (Ready): story written ✓ | AC defined ✓ | pointed ✓ | dependencies clear ✓ | no blockers ✓.
- DoD: code reviewed ✓ | tests green ✓ | AC verified by PO or QA ✓ | deployed to staging ✓.
- Scope changes mid-sprint: update board + notify stakeholders immediately. Never wait for standup.
- Escalate blockers same day if blocking > 1 person.

### ultimate-team-leader
- Commitment levels: high > 90% (team has done it before, no dependencies) | medium 60-90% | low < 60% → spike first.
- SBI feedback: Situation → Behavior → Impact → their response. Never feedback as status update.
- Tech debt framing: risk to delivery, not code aesthetics. Frame as: "If this breaks in prod, rollback takes Xh."
- 1:1s: scheduled, not canceled, not status updates. Ask what's blocking them, not what they did.
- Sprint health signals: PRs sit > 48h without review | same person always picks hardest tasks | retro repeats same complaints.

### ultimate-delivery-team
- DoD checklist: code reviewed ✓ | unit tests green ✓ | integration tests pass ✓ | staging deployed ✓ | AC verified ✓ | no regression ✓ | story moved to Done ✓.
- Fibonacci pointing: 1, 2, 3, 5, 8, 13, 21, ∞, ?. Anything > 8 should be split.
- Capacity formula: velocity × 0.8 (20% buffer for unplanned work and ceremonies).
- Retro action rule: every action needs owner + due date. Max 3 per retro.
- Velocity variation > 30% sprint-to-sprint → estimation problem. Decreasing 3 sprints → team health or tech debt.
- Stories carried over > 20% → planning or blocker problem. Investigate root cause, not just carry forward.

### ultimate-app-user
- Validate from the user's perspective: "Can I verify this is done without asking a developer?"
- Every AC must cover: happy path + unhappy path + empty state + error state.
- UX red flags: Error 500 shown to user | no loading indicator on async | destructive action without confirmation | form loses data on validation error.
- AC must be user-visible outcomes, not technical specs. "Login stays active 30 days" not "JWT expiry 2592000s".
- Bug format: goal → steps → expected → actual → impact → environment → frequency.
- Mobile: touch targets ≥ 44px. Slow connection: show loading states. Screen reader: keyboard navigable.

### ultimate-user-communicator
- Non-technical framing: business value, not implementation. "You can now do X" not "I refactored the service layer."
- Answer "what does this mean for me?" BEFORE they ask it.
- Progress format: Done / In-progress / Blocker (omit Blocker if none).
- MVP negotiation: Deliver X by Y / Cut Z / Trade-off / Decision needed (max 2 options presented).
- Scope change: Fact / Impact / Options 1-2 / Recommendation.
- One question per message max — the most critical one only. Specific dates over vague timelines.
- Lead with the result, not the journey. No jargon — translate every technical term.

### ultimate-team-communicator
- Fixed standup format: WIP / Bloqueado / No contemplado / Delta SP / Próximo.
- Every WIP item must include story/task ID — no naked task descriptions.
- Blocker format: `{what broke} → necesito {what specifically} de {who}`.
- SP recalculation ONLY on scope change — never on time overrun or estimation error.
- Monday: add Sprint close section (DoD pass/fail | Velocity planned vs actual | Carried-over IDs).
- Technical precision: name files, endpoints, systems. Never vague descriptions.
- No hedging: no "creo que", "capaz", "más o menos" — facts only.

### ultimate-assertive-communicator
- Lead with result: state what happened or was decided FIRST, context second.
- No hedge words: remove "podría/tal vez/maybe/I think/it seems" — replace with direct statements.
- Structure: Fact → Interpretation → Decision (keep them separate, don't blend).
- One ask per message maximum. Active voice: "I fixed X" not "X was fixed".
- Length: ≤ 5 lines for updates, ≤ 10 lines for reports.
- Do NOT apply in emotional support or open brainstorming contexts.

### ultimate-quant-analyst
- Never evaluate a strategy by return alone — always risk-adjusted (Sharpe, Sortino, Calmar).
- Backtest with optimized params showing 70%+ win rate = overfitting signal, not success.
- Always model slippage + commissions before calling a strategy viable.
- Greeks at PORTFOLIO level, not per-leg. Short theta = short gamma — always, mathematically.
- Vega: sell vol when IVR > 50%, buy when IVR < 20%. Absolute IV without rank is meaningless.
- No naked short positions without a defined hedge — unlimited risk is a time bomb.
- Pre-live: walk-forward + paper trading 3-6 months + multi-regime test mandatory.
- Lookahead bias: signals computed before bar close? Vol snapshot point-in-time? Earnings date known in advance?
- In crisis, correlations go to 1 — do not rely on diversification during drawdowns.
- IBKR: always `reqContractDetails` before trading; combo orders for multi-leg strategies.

### daily-standup
- Query engram: `mem_context` + `mem_search` by project name + `session_summary` search.
- Format: [STANDUP] / [WIP] / [BLOQUEADO] / [NO-CONTEMPLADO] / [DELTA-SP] / [PROXIMO].
- Monday: add [SPRINT-CLOSE] section (DoD pass/fail | velocity | carried-over IDs).
- Assertive tone: no hedging, specific names, results first.
- Default projects if no args: 1, 2, 3 (Work).

### end-session
- Call `mem_session_summary` BEFORE /clear or saying "done". Never skip.
- Format: Goal / Instructions / Discoveries / Accomplished / Next Steps / Relevant Files / Open Bugs / Pending PRs / Open Decisions.
- After compaction: immediately call `mem_session_summary` with compacted content, then `mem_context`.
- Include: last commit hash (`git rev-parse --short HEAD`), working tree state (`git status --short`).

### mr-feedback
- Parse feedback first: what type (perf, bug, style, arch)? Quote the key sentence exactly.
- Locate and read the affected code BEFORE proposing anything — never assume from description alone.
- Perf feedback: scan for O(n²) (stream-in-loop), N+1 (lazy load in loop), linear scan (list.contains in loop).
- O(n²) fix: two key extractor Functions + `Collectors.toMap` built once outside the loop + `map.get(key)` inside.
- TDD mandatory for behavioral changes: RED first, always. A passing test before impl proves nothing.
- Update ALL callers — never leave dangling references or compilation errors.
- If reviewer was right, say so directly. If wrong, explain WHY with technical evidence.
- GitLab suggestions: use ` ```suggestion:-N+M ``` ` format. N=0 always (first line of change). M=total_lines-1.

### judgment-day
- Launch TWO parallel blind sub-agents via `delegate` (async). Never sequential. Never review inline.
- Neither agent knows about the other — no cross-contamination.
- Confirmed WARNINGs → fix inline without re-launching judges. Theoretical WARNINGs → report as INFO, don't fix.
- SUGGESTIONs → fix inline if trivial (dead code, style). Don't re-judge for suggestions.
- Max 2 fix rounds then escalate to human review.
- Before judges: resolve compact rules from this registry and inject into both judge prompts identically.

### jira-story
- Acceptance criteria: GIVEN/WHEN/THEN/AND in third person ALWAYS. NEVER first person ("I click", "I see").
- AC HTML: `<p><strong>Scenario N: Title</strong></p>` + `<ul>` with GIVEN/WHEN/THEN `<li>` items.
- No technical jargon in AC: no class names, endpoints, SQL, or internal identifiers.
- Bookmarklets: encodeURIComponent the IIFE body. Single line. No newlines. Max ~65k chars or use DevTools snippet fallback.

### prompt-master
- Analyze every user prompt: what's ambiguous, what's missing, what's underspecified (max 3 problems).
- Output improved prompt in structured format: Context → Task → Constraints → Output Format → Examples.
- Apply after every prompt when the goal is complex, multi-step, or has AI delegation involved.

### skill-creator
- SKILL.md structure: YAML frontmatter (name, description with Trigger) → When to Use → Critical Patterns → Commands.
- No Keywords section. No lengthy explanations. No web URLs in references. No troubleshooting sections.
- Code examples: minimal and focused. Tables for decision trees. Link to docs, don't duplicate.
- Checklist before creating: skill doesn't exist ✓ | pattern is reusable ✓ | name follows conventions ✓ | frontmatter complete ✓.

### go-testing
- Prefer table-driven tests. Use `teatest` for Bubbletea TUI. Golden files where output is deterministic.
- (This repo is Java-first; use only when editing Go code.)

### gentleman-language
- Rioplatense Spanish (voseo) for Spanish input; same warm energy in English for English input.
- NEVER sarcastic — warmth comes from caring about the person's growth, not from frustration.
- When correcting: (1) validate the question makes sense, (2) explain WHY technically, (3) show the correct way.
- CAPS for emphasis. Rhetorical questions to lead explanations.
- AC always third person: GIVEN/WHEN/THEN/AND. User stories: AS A / I WANT TO / SO THAT.

## Project Conventions

| File | Path | Notes |
|------|------|-------|
| Global CLAUDE.md | `C:\Users\FGIAQUINTA\.claude\CLAUDE.md` | TDD rules, language, delegation rules, persona, session protocol |
| Pending work | `C:\Users\FGIAQUINTA\IdeaProjects\options-quant\docs\pending-work.md` | Read at session start — SDD pendientes, QA audit state, fixes recientes |

Read the convention files listed above for project-specific patterns and rules.
