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

## Project Conventions

| File | Path | Notes |
|------|------|-------|
| AGENTS.md | `C:\Users\FGIAQUINTA\IdeaProjects\options-quant\AGENTS.md` | Java clean architecture, React hooks + vanilla CSS, SLF4J logging |

Read the convention files listed above for project-specific patterns and rules.
