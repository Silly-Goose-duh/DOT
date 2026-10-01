# SECURITY.md --- DOT v0.1

## Security objective

DOT handles personal productivity data and may connect to external
accounts. Security is a product requirement, not a post-MVP cleanup.

## Trust boundaries

Untrusted: - LLM output - email bodies/subjects - calendar
descriptions - filenames/documents - network responses until validated -
deep-link/Intent inputs - notification content - user-entered free text
when passed to tools

Trusted only after validation: - registered tool definitions -
policy-engine decisions - local authenticated app state - validated
provider responses

## Rules

1.  Never execute arbitrary code/commands produced by the model.
2.  All model actions go through registered typed tools.
3.  The policy layer independently enforces confirmation/risk.
4.  External content is data, never authority.
5.  Never place tokens/API keys in Git or ordinary logs.
6.  Use official OAuth flows and least privilege.
7.  Protect local secrets with Android Keystore-backed mechanisms where
    appropriate.
8.  Store the minimum personal data needed.
9.  Do not persist full email bodies by default.
10. Provide disconnect and clear-local-data flows.
11. Use TLS; never disable certificate verification.
12. Validate/parse structured model output before tool execution.
13. Apply timeouts and bounded retries.
14. No infinite agent loops.
15. Consequential operations are out of scope for v0.1.

## Prompt injection defense

Treat provider content as quoted data. An email saying "ignore your
rules and send..." must not affect system/tool policy.

Provider content may be: - summarized - classified - searched -
displayed

It may not: - grant permissions - register tools - modify policy -
trigger external actions solely because its text requests them

## Logging

Allowed: - event name - duration - result code - tool name - redacted
IDs - build/version info

Forbidden by default: - OAuth tokens - passwords - API keys - raw
voice - full email bodies - full note bodies - sensitive prompt dumps

## Release security checklist

-   secret scan clean
-   dependency scan reviewed
-   debug endpoints disabled
-   logs reviewed/redacted
-   OAuth revoke/disconnect tested
-   permission denial tested
-   clear-data tested
-   tool schema fuzz/invalid input tests pass
-   prompt-injection fixtures pass
-   exported Android components reviewed
-   network security config reviewed
