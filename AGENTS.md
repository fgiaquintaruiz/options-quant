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
