# Skill Registry

**Delegator use only.** Any agent that launches sub-agents reads this registry to resolve compact rules, then injects them directly into sub-agent prompts. Sub-agents do NOT read this registry or individual SKILL.md files.

See `_shared/skill-resolver.md` for the full resolution protocol.

## User Skills

| Trigger | Skill | Path |
|---------|-------|------|
| When creating a GitHub issue, reporting a bug, or requesting a feature. | issue-creation | `C:\Users\FGIAQUINTA\.claude\skills\issue-creation\SKILL.md` |
| When creating a pull request, opening a PR, or preparing changes for review. | branch-pr | `C:\Users\FGIAQUINTA\.claude\skills\branch-pr\SKILL.md` |
| When user asks to create a new skill, add agent instructions, or document patterns for AI. | skill-creator | `C:\Users\FGIAQUINTA\.claude\skills\skill-creator\SKILL.md` |
| When writing Go tests, using teatest, or adding test coverage. | go-testing | `C:\Users\FGIAQUINTA\.claude\skills\go-testing\SKILL.md` |
| When user says "judgment day", "judgment-day", "review adversarial", "dual review", "doble review", "juzgar", "que lo juzguen". | judgment-day | `C:\Users\FGIAQUINTA\.claude\skills\judgment-day\SKILL.md` |
| Greeks, delta, gamma, vega, theta, rho, implied volatility, vol surface, options pricing, Black-Scholes, Heston, SABR, condor, butterfly, straddle, strangle, vertical spread, calendar spread, VaR, CVaR, backtest, overfitting, lookahead bias, IBKR options, TWS, opciones financieras, estrategia de opciones, cobertura delta. | ultimate-quant-analyst | `C:\Users\FGIAQUINTA\.claude\skills\ultimate-quant-analyst\SKILL.md` |
| MVP, release gate, use case coverage, "funciona todo", "estamos listos para producción", feature freeze, regression, go/no-go, "qué falta para el MVP", acceptance sign-off, production readiness. | ultimate-mvp-manager | `C:\Users\FGIAQUINTA\.claude\skills\ultimate-mvp-manager\SKILL.md` |
| Before every `use_figma` call; Figma Plugin API writes/reads. | figma-use | `C:\Users\FGIAQUINTA\.cursor\plugins\cache\cursor-public\figma\9680714bad40503ef37a9f815fd1d2cd15150af4\skills\figma-use\SKILL.md` |
| "implement design", Figma URLs, code from designs. | figma-implement-design | `C:\Users\FGIAQUINTA\.cursor\plugins\cache\cursor-public\figma\9680714bad40503ef37a9f815fd1d2cd15150af4\skills\figma-implement-design\SKILL.md` |
| Code Connect, `.figma.ts` / `.figma.js` mappings. | figma-code-connect | `C:\Users\FGIAQUINTA\.cursor\plugins\cache\cursor-public\figma\9680714bad40503ef37a9f815fd1d2cd15150af4\skills\figma-code-connect\SKILL.md` |
| Build/update full screens in Figma from code or descriptions. | figma-generate-design | `C:\Users\FGIAQUINTA\.cursor\plugins\cache\cursor-public\figma\9680714bad40503ef37a9f815fd1d2cd15150af4\skills\figma-generate-design\SKILL.md` |
| Variables, tokens, libraries, theming from codebase. | figma-generate-library | `C:\Users\FGIAQUINTA\.cursor\plugins\cache\cursor-public\figma\9680714bad40503ef37a9f815fd1d2cd15150af4\skills\figma-generate-library\SKILL.md` |
| "create design system rules", project Figma-to-code conventions. | figma-create-design-system-rules | `C:\Users\FGIAQUINTA\.cursor\plugins\cache\cursor-public\figma\9680714bad40503ef37a9f815fd1d2cd15150af4\skills\figma-create-design-system-rules\SKILL.md` |
| New blank Figma/FigJam file before plugin work. | figma-create-new-file | `C:\Users\FGIAQUINTA\.cursor\plugins\cache\cursor-public\figma\9680714bad40503ef37a9f815fd1d2cd15150af4\skills\figma-create-new-file\SKILL.md` |

## Compact Rules

Pre-digested rules per skill. Delegators copy matching blocks into sub-agent prompts as `## Project Standards (auto-resolved)`.

### issue-creation
- Use GitHub issue templates only; blank issues are disabled.
- New issues get `status:needs-review`; a maintainer MUST add `status:approved` before any PR.

### branch-pr
- Every PR MUST link an approved issue; every PR MUST have exactly one `type:*` label.
- Automated checks must pass before merge.

### skill-creator
- Follow Agent Skills layout: YAML frontmatter (`name`, `description` with Trigger), body with When to Use / Critical Rules.
- Do not duplicate existing docs for one-off patterns.

### go-testing
- Prefer table-driven tests; use `teatest` for Bubbletea TUI; golden files where appropriate.
- (This repo is Java-first; use only when editing Go code.)

### judgment-day
- Before judges: resolve skills via `mem_search("skill-registry")` or `.atl/skill-registry.md`.
- Run two parallel blind judges, merge findings, fix, re-judge; max two iterations then escalate.

### figma-use
- Load this before every `use_figma`; pass `skillNames` for logging.
- Use top-level `return` for output; no `figma.notify`; work in small incremental scripts; RGB 0–1; load fonts before text; on error, fix script then retry (do not blindly retry).

### figma-implement-design
- Needs Figma URL with `node-id` or desktop selection; deliverable is repo code, not canvas edits.
- For writes in Figma, switch to figma-use; for full pages in Figma from code, add figma-generate-design.

### figma-code-connect
- Requires Figma MCP; published components; Org/Enterprise for Code Connect; URL must include `node-id`.
- Template `.figma.ts` with `@figma/code-connect/figma-types` in `tsconfig` types.

### figma-generate-design
- Always pair with figma-use; discover design system via `search_design_system`; assemble with tokens, not raw hex.

### figma-generate-library
- Pair with figma-use for all `use_figma` calls; multi-phase library work; pass `skillNames: "figma-generate-library"`.

### figma-create-design-system-rules
- Generates agent rule files for Figma-to-code; requires Figma MCP connection.

### figma-create-new-file
- Use `create_new_file` MCP when a new file is needed before `use_figma`; optional `/figma-create-new-file [editorType] [fileName]`.

### ultimate-mvp-manager
- MVP = all defined use cases working end-to-end, not just unit tests passing
- "done" requires: implemented + tested + edge cases known (even if deferred explicitly with owner + date)
- Feature freeze = no new scope, no refactors without failing test, no dep upgrades unless P0 fix
- Go/No-Go: GREEN (all gates), YELLOW (known gaps + mitigation + rollback plan), RED (P0 open = no ship)
- Regression test must cover integration points with real credentials, not mocks
- Smoke test = full happy path from scratch in clean environment before every release
- Never advance MVP scope until current scope passes all gates
- No rollback plan = no YELLOW — it becomes RED
- Decision log required before every scope advance: what shipped, what deferred, who signed off

### ultimate-quant-analyst
- Never evaluate a strategy by return alone — always risk-adjusted (Sharpe, Sortino, Calmar).
- Backtest with optimized params showing 70%+ win rate = overfitting signal, not success.
- Always model slippage + commissions before calling a strategy viable.
- Greeks must be measured at portfolio level, not per-leg.
- Vega exposure management requires IV rank context (sell high IVR >50%, buy low IVR <20%).
- No naked short positions without a defined hedge — unlimited risk is a time bomb.
- Pre-live = walk-forward + paper trading 3-6 months + multi-regime test mandatory.
- Lookahead bias check: signals computed before bar close? Vol snapshot point-in-time?
- Short theta = short gamma — always. Anyone ignoring this tradeoff is not managing risk.
- In crisis, correlations go to 1 — do not rely on diversification during drawdowns.
- Never use Black-Scholes alone for illiquid strikes or long-dated options.
- IBKR: always reqContractDetails before trading; use combo orders for multi-leg strategies.

## Project Conventions

| File | Path | Notes |
|------|------|-------|
| AGENTS.md | `C:\Users\FGIAQUINTA\IdeaProjects\options-quant\AGENTS.md` | Java clean architecture, React hooks + vanilla CSS, SLF4J logging |

Read the convention files listed above for project-specific patterns and rules.
