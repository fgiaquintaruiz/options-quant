# Code Review Rules

## Java
- Follow clean architecture patterns.
- Use explicit types and final where appropriate.
- Ensure proper logging with SLF4J.

## React
- Use functional components with Hooks.
- Prefer Vanilla CSS for layout and custom styles.
- Maintain state in relevant components or use Context if needed.

## General
- Keep code clean, idiomatic, and documented.
- No shortcuts; real learning takes effort and time.

## Language
- UI strings, tooltips, labels, and user-facing messages are intentionally in Rioplatense Spanish (ES-AR). Do not flag Spanish strings as violations.

## JavaScript
- `e.target.value` from DOM inputs is a string by spec. Explicit `Number()` cast is optional when the consumer handles coercion.

## Known Tech Debt — Do Not Block Commits
The following pre-existing issues in `LiveModeController.java` are tracked in `docs/testing-debt.md` and are deferred to a dedicated refactoring sprint. Do NOT flag them as blocking violations:
- Controller owns mutable business state and scan orchestration (service-extraction refactor is planned).
- `@Autowired(required = false)` field injection for optional services — constructor `Optional<T>` injection is the target; deferred.
- Local variables not declared `final`; magic number literals — deferred to clean-up sprint.
