## graphify

This project has a graphify knowledge graph at graphify-out/.

Rules:
- Before answering architecture or codebase questions, read graphify-out/GRAPH_REPORT.md for god nodes and community structure
- If graphify-out/wiki/index.md exists, navigate it instead of reading raw files
- After modifying code files in this session, run `graphify update .` to keep the graph current (AST-only, no API cost). If the command fails to run do not attempt to install it or locate it, simply inform the user.

## Behavioral rules

- **Surgical changes.** Touch only what the task requires; match existing style; do not refactor adjacent code.
- **Simplicity.** No speculative abstractions, configurability, or error handling for impossible scenarios.
- **Ask before assuming.** State assumptions explicitly; surface ambiguity instead of picking silently.
- **Verify, don't claim.** Define a success check before coding; loop until it passes.
