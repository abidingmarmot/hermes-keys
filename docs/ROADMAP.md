# Hermes Keys roadmap

**Goal:** an independently implemented, GPLv3 Android keyboard that is dependable for ordinary daily typing, with optional, explicitly invoked agent assistance. No proprietary Acti source, graphics, catalog, accounts or entitlements are included. [Setup guide](SETUP.md) · [Source](https://github.com/abidingmarmot/hermes-keys).

## Reality check and present state

The main product risk is **trust and activation**, not the absence of another agent framework: a user already found the keyboard somewhat functional but could not get AI working because there was no usable guide or connection diagnostic. First prove setup, understandable failures, and safe typing on more than one device; do not build a community backend or custom peer-to-peer stack before that works.

The [signed 0.1.0-beta.1 testing prerelease](https://github.com/abidingmarmot/hermes-keys/releases/tag/v0.1.0-beta.1) is tagged at source commit `303b5667a1f19c365a80b00872511463c1b4a4e0`. It contains a HeliBoard-based IME, opt-in HTTPS Hermes Agent action, explicit prompt/response review and insertion, encrypted local Acts, single-Act file/share-sheet portability, a consent-gated connection diagnostic, and an optional *rectangular* hold-Space Action popup. One API 34 phone completed synthetic Agent Send/Insert with the debug package and diagnostic success; the signed release installed, rendered onboarding, typed ordinary Space, blocked Agent without setup, and retained a synthetic Act across a same-version reinstall. **Authenticated Agent on the release package, a second device and long-session reliability remain unverified.** The full suite ran 303 tests with 10 failures previously reproduced in upstream HeliBoard suites; lint had 68 errors and 97 warnings. This is **not** daily-use certification.

## P0 — Make the existing AI path work for a new user

1. Publish and validate [SETUP.md](SETUP.md): home-server profile, least-privilege tools, HTTPS/Tailscale, correct server-root URL, host-side provider login, Android opt-in, and synthetic prompt walkthrough. Keep secrets out of documentation and support requests.
2. **Implemented; one positive and pre-send Cancel device check passed:** **Test saved connection** requires fresh confirmation and saved opt-in, sends only `Reply exactly READY.` through the authenticated completion route, discards the response and displays safe local HTTP/TLS/DNS/network/timeout/invalid-response categories. No automatic send/retry; cancellation and the 45-second deadline ignore late results. Success proves a valid final answer, not profile identity, exact `READY` content or safe server tools. It does not use unauthenticated `/health`. Validate wrong key, missing route, offline, post-send cancellation, repeated taps and rotation on-device before daily-use certification.
3. Improve setup labels/error messages and a short in-app link to the guide. Verify on a fresh install and on an app update, online/offline, with opt-in off/on, no surrounding-text leak and no background calls during typing.

**Gate:** a new tester, without assistance, configures their *own* restricted server and gets `READY` in a plain field; wrong URL/key/offline failures point to actionable fixes. This is the next implementation priority, not new connectors.

## P1 — Stability and an installable test release

1. Reviewed and merged the Act-import data-loss/resource-exhaustion fixes. Targeted tests, full build, synthetic device import/Cancel/Add/Delete, and storage readability passed. Cross-device portability, duplicate/rejection cases and provider/rotation stress remain to be tested. No unreviewed dirty tree in a release.
2. Triage the ten baseline failures (link tests versus Unicode/emoji/Hangul behavior); fix any real keyboard defect, and record a bounded lint baseline for unrelated upstream translation warnings rather than pretending lint is green. Test passwords, incognito, focus/selection changes, editor types, lock/rotation, touch cancellation, long sessions, retry/offline behavior, TalkBack, and at least two distinct Android devices. Restore the phone's IME to its own initial setting after each QA session; the owner currently has Hermes Keys selected.
3. A dedicated release-signing key exists outside the repository; a separate, verified backup remains the owner's responsibility. The non-debuggable, minified release APK was built from clean tagged source, zipaligned, signed, verified and installed/updated on one phone. Version `0.1.0-beta.1` / code `10001` must increase for future updates using the same signing key. The `.debug` package remains separate and does not share encrypted data.
4. The GitHub **prerelease** includes the signed APK, SHA-256, exact commit, GPLv3/HeliBoard attribution, supported Android/ABI, setup instructions, known test/lint failures and a “testing, not daily-use certified” warning. The downloaded asset hash matched the local signed APK. Gather crash/IME input feedback before labeling stable.

**Gate:** signed APK installs and updates without data loss on two devices; ordinary typing, explicitly invoked Agent and safe failure paths pass a published checklist. No release from a dirty branch or using a generic debug signing key for a purported stable build.

## P2 — Self-hosting and provider freedom, without credential lock-in

**Current architecture:** the Android app speaks a single stateless OpenAI-style Chat Completions request to a dedicated HTTPS Hermes profile; the model alias is currently hard-coded as `hermes-agent`. Provider OAuth/API keys stay on the host, while Android stores only a scoped API-server bearer key. The agent can run on a **home server**; a tailnet-only Tailscale Serve route is a practical remote-access option, and a VPS is optional. A self-hosted *agent* can still call a cloud LLM. Fully local inference additionally requires a capable local model/server; Tailscale may still use its hosted coordination or relay infrastructure. Never expose the unrestricted main agent publicly.

1. First allow **named connection profiles** (server root, model alias, scoped token, clear tool-capability warning) and connection testing, while retaining the current safe defaults and privacy gates. Reuse the existing OpenAI-compatible client; do not add SDKs for every model brand. A plain model endpoint is not automatically an agent capable of approved tools.
2. Support documented **host-side** options: Hermes with its own `openai-codex` subscription OAuth when authorized, API-key providers including xAI/Anthropic, and local OpenAI-compatible inference. Test each server adapter's auth, model response shape, finish status, cost/rate-limit behavior and tool permissions. Never collect a provider's subscription OAuth token in the keyboard or promise that a ChatGPT subscription is an OpenAI API key.
3. Evaluate optional *other agent harnesses* through a restricted, authenticated host-side adapter: Codex app-server is a possible integration surface, but its remote WebSocket mode is experimental and requires explicit authentication/TLS and approval handling. Claude Agent SDK permits API-key integrations; Anthropic says third-party products may **not** offer claude.ai login/rate limits without approval. Grok/xAI API is another candidate, subject to its documented auth and behavior. Do not advertise Claude Code or “grokbot” subscription compatibility until a permitted, tested route exists. Keep arbitrary file/terminal/account tools disabled for keyboard-supplied text; result preview alone cannot approve server-side actions.

**Gate:** each advertised backend completes a synthetic request and exposes safe failure/approval semantics from a fresh install, without secrets in app logs or repository. Provider names in settings reflect actual verified implementations.

## P3 — Local-first device-to-device portability before automatic P2P sync

Existing local Acts use an Android Keystore key and `no_backup` storage. **Raw encrypted app data is not portable across devices.** The shipped explicit file/share-sheet single-Act import/export is the smallest useful portability path; Android's native share flow or an existing user-approved app such as LocalSend/KDE Connect may move an intentionally exported definition without building a server. Plaintext sharing requires a visible warning and user action.

Only if two-device use shows a need for *automatic* sync: pair devices explicitly (QR/manual verification), encrypt portable Act data end-to-end with a key distinct from Android's per-device Keystore key, authenticate peers, handle conflicting edits/deletes and key rotation/revocation, and test offline/local-LAN delivery, interruptions, reinstall recovery, battery/background restrictions and loss of a paired device. Prefer an existing open protocol/transport over a custom P2P framework. Discovery/transport choices (LAN discovery, Wi-Fi Direct, or a private tailnet for non-LAN reachability) are an evaluation, **not a selected design**; a relay or VPS would be opt-in, never a hidden requirement. Do not sync keystrokes, clipboard history, tokens, agent sessions or password fields by default.

**Gate:** two owned Android devices exchange only selected Acts with verifiable pairing, encrypted transit, revocation and deterministic conflict handling; no central service or account required for same-LAN operation.

## Later, after daily-use proof

- Voice input: reuse a compatible external Android speech IME where possible, or evaluate a locally licensed/offline engine; no installed recognition provider was found on the tested phone. Any additional keyboard/microphone app/model on a personal phone needs explicit consent.
- Community: explicit Act portability first. A searchable public catalog, moderation, malicious-prompt review and publish/remix accounts are separate work, not the proprietary catalog copied over.
- Cross-device Clips/Phone Mic and connected app actions: evaluate existing native services before writing a companion server. Connected actions need per-action consent **before** the server executes; the keyboard's result/insert preview is not that gate.

## Sources and non-goals

[Hermes API server](https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server) · [Hermes profiles](https://hermes-agent.nousresearch.com/docs/user-guide/profiles) · [Tailscale Serve](https://tailscale.com/kb/1312/serve) · [Codex authentication](https://developers.openai.com/codex/auth) · [Codex app-server](https://developers.openai.com/codex/app-server) · [Anthropic Agent SDK authentication note](https://docs.anthropic.com/en/docs/claude-code/sdk) · [xAI API](https://docs.x.ai/overview).

This roadmap records candidates and gates, **not** a claim that unfinished features work or that a hosted subscription may legally be resold/reused. Keep the private Acti APK/decompilation and credentials out of this repository.