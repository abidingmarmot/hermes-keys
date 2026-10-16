# Hermes Keys — local Acts (wave A)

An independently written **GPLv3** agent integration on the [HeliBoard](https://github.com/HeliBorg/HeliBoard) fork at upstream commit `0c60dff`, retaining OpenBoard/AOSP provenance, notices, and licenses. This is not an upstream HeliBoard release. Application ID: `dev.abidingmarmot.hermeskeys` (`.debug` for debug builds); it coexists with HeliBoard. The existing launcher artwork remains upstream's CC-BY-SA artwork (credits below); the Agent conversation and Acts list icons are original GPLv3 work. No proprietary implementation or artwork was used.

Ordinary typing remains offline. This fork adds Android's install-time **INTERNET** permission solely for an explicitly invoked agent request. Android does not offer a runtime INTERNET permission prompt: the additional **application opt-in** below is required. No endpoint, token, backend, or account is supplied.

## Safe server setup (required)

Read the [Hermes API server documentation](https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server) and [profiles guide](https://hermes-agent.nousresearch.com/docs/user-guide/profiles).

1. Create a fresh, dedicated profile (`hermes profile create keyboard`), **not a clone of your main agent**. Configure its model/provider separately. Use `hermes -p keyboard tools` to disable tools for the API-server platform. Start with **no tools**; also disable skills/plugins, MCP servers, long-term memory integrations, delegation, and scheduled jobs. Check the effective configuration of your installed Hermes version. A prompt saying “do not use tools” is **not** a security boundary.
2. Run this profile under a separate restricted OS user/container without your home directory, SSH keys, repositories, browser sessions, or main agent credentials. A profile isolates configuration; it is not an OS sandbox. Restrict filesystem access and outbound network access. Limit provider spend and concurrency.
3. In that profile's private `.env`, configure `API_SERVER_ENABLED=true`, `API_SERVER_HOST=127.0.0.1`, a dedicated port (`API_SERVER_PORT=8643`), and a fresh, high-entropy `API_SERVER_KEY`. Generate/paste it locally with a password manager; never put it in Git, command examples, tickets, screenshots, URLs, or logs. Protect the file permissions. Start only this profile's gateway (`hermes -p keyboard gateway`). No new backend is needed.
4. Put that listener behind your existing authenticated HTTPS reverse proxy/VPN with a valid Android-trusted certificate. Do not expose a cleartext public listener. Restrict the proxy to `POST /v1/chat/completions` where practical; disable request/Authorization/body logging. Keep other administration/session/jobs routes private. Inspect authenticated `/v1/toolsets` locally to verify effective tools, and test that terminal/file/delegation access is actually unavailable **before** connecting the keyboard. If your version cannot enforce this, do not connect it.
5. This client sends model alias `hermes-agent`. Leave Hermes' direct model requests disabled (default), or explicitly route this alias to the dedicated profile's model. A multiplexed deployment can use its restricted `/p/keyboard` prefix and that profile's own key; never use the main profile's key.

**Never point keyboard text at your main full terminal-capable API.** Hermes executes tools server-side before the response reaches the keyboard; selected text can contain prompt injection. Result preview protects insertion, not server execution. Server/model providers may store prompts and results; review their retention settings. The keyboard cannot audit or restrict a remote agent's privileges.

## Configure and use

- Open **Hermes agent settings** from keyboard settings (or tap **Agent** in the expanded IME toolbar). Android 6+ is required for the optional agent; the original keyboard still supports Android 5.
- Paste an HTTPS **server-root** URL, without `/v1` (an optional profile/proxy prefix is supported), and the dedicated bearer token. Explicitly check **Allow explicit Agent requests** and save. There is no cleartext/dev bypass; redirects are refused. No probe is sent when saving.
- The token and configuration are AES-GCM encrypted using a non-exportable Android Keystore key in app-private `no_backup` storage (the IME uses device-protected storage; the blob is encrypted and credential access is blocked until user unlock), separate from keyboard preferences. Neither Android backup nor the keyboard's ZIP export includes them. The settings screen hides the token, prevents saved-instance/autofill storage and screenshots, and never reveals the saved token. Paste a token again on every save; **Disable and forget** deletes configuration and key. Revoke the key on the server if lost/compromised. Clipboard managers and third-party keyboards used to paste a token are outside this protection; clear your source clipboard afterwards.
- Compose a prompt in the target field, or select text, then expand the toolbar and tap **Agent**. With no selection, it previews text before the cursor (up to 4,000 characters, refusing overflow rather than silently truncating). Review the exact text and destination, then tap **Send prompt**. No surrounding text, raw keystroke stream, system prompt, or conversation history is sent automatically.
- One non-streaming `POST /v1/chat/completions` sends one user message with model `hermes-agent`. Review final text and finish reason, then explicitly **Insert after selection**. Original selected/composed text is kept, not silently replaced. Cancel changes nothing. Review model output as untrusted text.
- Password (including visible/web/numeric password), private/no-personalized-learning fields, incognito, locked devices, and missing connections are refused before reading text. Switching editor/selection or hiding the IME dismisses/cancels the flow. Fields must correctly advertise their input type; the keyboard cannot detect a mislabelled password field.
- Request limit: 45 seconds overall, 10-second connect and 30-second idle read timeouts; 64 KiB response and 16,000-character result caps. Only final `finish_reason: stop` answers are accepted; cancelled/error/truncated/tool-call responses are rejected. Errors remain visible with the prompt, leaving field text intact. There is no automatic retry. Cancelling/timeouts disconnect locally but **may not stop server execution**; retrying may incur another run/cost.

## Private local named Acts

- Open **Manage local Acts** in keyboard settings to list, add, edit, pin/unpin, or delete an Act. Deletion names the Act and requires confirmation. Summarize, Rewrite, Translate, and Lookup start as original, fully editable copies; deleted presets do not reappear. Translate initially targets English; edit its instruction for another language. Lookup is a prompt, not a built-in browser or search service.
- Each Act is only a unique name and reusable instruction template, never executable code. Use **exactly one literal `{input}`**. Example: `Rewrite clearly, keeping the meaning:\n\n{input}`. Expansion is a single literal replacement, not a scripting/expression language. Limits: 32 Acts, 48-character names, 2,000-character templates, and 4,000-character expanded prompts (UTF-16 code units). Settings fields cap typed/pasted length. Empty input, duplicate names (case-insensitive), invalid placeholders, and control characters are refused; expanded-prompt overflow fails closed without truncation.
- Do not put URLs or credentials in definitions. URL-like text and recognizable credential assignments/key formats are rejected. Validation cannot recognize every arbitrary secret or obfuscated URL: it is not a secret scanner. The only destination and authentication come from the separate Agent settings, never from an Act. Input text is preserved verbatim, not URL/secret-filtered; review before sending.
- Expand the IME toolbar and tap **Acts**, then choose a named Act (pinned first, creation order within each group). Existing customized toolbars may need **Acts** enabled in toolbar settings. The standard **Agent** button remains unchanged. Selected text, or text before the cursor, replaces `{input}`; no input means no request. The preview shows the **exact final prompt and full HTTPS completion destination**. Only explicit **Send prompt** starts the existing AgentClient. Review the result, then explicitly **Insert after selection**; original text remains. The same privacy/lock gates and fresh editor snapshots protect both flows. Choosing, editing, or pinning an Act does not send or type anything.
- Acts have their own AES-GCM file and non-exportable Android Keystore key, using the existing credential-storage pattern. The encrypted file is app-private `no_backup`, outside ordinary preferences, Android backups, and keyboard ZIP exports. The app defaults to device-protected storage; Act access is blocked until user unlock. Screens disable screenshots, autofill, and saved-instance serialization. No templates/prompts are logged. Storage/decryption failure does not overwrite the file or silently reset presets.
- **App updates/restarts retain Acts; clearing app data or uninstalling removes them.** A normal uninstall/reinstall cannot retain private no-backup data or its Keystore key. Reinstall retention is intentionally not claimed or implemented: it would require backup/export or external persistence, contrary to this wave's local-only privacy requirement. There is no Act backup/restore, sharing, sync, import, or recovery for lost keys. Other keyboards used to edit instructions and the remote server/provider remain outside local storage protections.

## Optional hold-Space Action popup

- **Advanced → Hold Space for Action popup** is **OFF by default**. Enabling it explicitly replaces Space's long-press IME picker, not the globe key. With it OFF, normal Space, IME picker and pre-hold swipes are unchanged. With it ON, taps still type Space and pre-hold swipes still use their configured behavior.
- Hold Space, slide up into **Agent** or one of **up to three pinned local Acts**, then release to open the existing exact prompt/destination preview. This is a **rectangular HeliBoard popup keyboard, not a radial wheel**. It reuses `PopupKeysKeyboard`, `PopupKeysKeyboardView` and `PointerTracker`; there is no new gesture recognizer or custom wheel drawing. Choices use at most two columns; names are shortened to ten code points, numbered to distinguish similar labels, scaled to fit, and exposed as accessibility labels. The preview retains the full Act name and exact prompt.
- Physical holds require a matching release after sliding into a visible choice. Action hit testing ignores the ordinary popup's themed vertical correction and slide allowance; normal popups retain both. With touch exploration enabled, a matching hover-enter/lift inside the popup or an explicit accessibility node click selects through the owning tracker's validated cleanup path; lift-to-type needs no slide. Holding without sliding (physical touch), releasing outside, sliding out after entering, cancellation, phantom release, or a second finger cancels/consumes the hold without a trailing Space. Multitouch cancellation consumes the remaining touch stream until a fresh down, including non-distinct-screen synthetic releases/downs. Preview is posted only after pointer cleanup; it never sends or inserts. **Send prompt** and **Insert after selection** remain separate explicit buttons.
- Enabled Agent configuration, an unlocked/visible editor and readable Act storage are required. Unavailable/configuration/storage/privacy failures disable that hold without opening the IME picker or typing Space. Editor/connection identity, generation, snapshot and the pinned mapping belong to that hold only; release reloads pins/configuration and rejects changed editor text, selection, pins, templates, names or ordering. No labels/mappings are cached across holds.
- Toolbar **Agent/Acts** remain available as the non-gesture/accessibility alternative. Real TalkBack gesture navigation and hardware/IME-window behavior have not been device-verified in this change.

## Scope and validation

Working in this wave: local named prompt shortcuts, native toolbar/settings integration, optional rectangular hold-Space popup, explicit prompt consent, Keystore-backed storage and opt-in configuration, bounded HTTPS requests, final-answer parsing, and confirmed non-destructive insertion. **Not implemented:** executable actions/tools, agent voice, radial action wheel, community sharing, cross-device sync, streaming/progress, server-side cancellation, or multi-turn sessions. No feature-parity claim is made.

Build/tests (Java 17, Android SDK 37 and NDK 28):

```sh
ANDROID_HOME=/home/devin/Android/Sdk ./gradlew :app:assembleDebugNoMinify
ANDROID_HOME=/home/devin/Android/Sdk ./gradlew :app:testDebugNoMinifyUnitTest --tests 'helium314.keyboard.latin.agent.*'
```

Targeted agent tests cover private/password/incognito/disconnection policy, HTTPS/URL/token validation, request shape, bounds, redirect/auth/error/timeout/cancellation handling, parser failures, editor insertion verification, and native settings privacy. Act tests cover encrypted persistence across store instances, edits/deletion/pins/order, empty-store persistence, atomic recovery, authenticated corruption, missing keys, locked-user access, limits, literal expansion, accessible settings CRUD/delete confirmation, no saved drafts, and mocked IME picker → exact preview → explicit Send → review → explicit Insert (including cancellation and editor changes). Storage tests inject a test AES key; they do not establish hardware-backed Keystore behavior. AGP 9 host tests are enabled for `debugNoMinify`; Robolectric uses API 28/35 for Java 17 compatibility.

Verification: **72 targeted agent/Acts/gesture tests passed**, zero failures/errors/skips; `:app:assembleDebugNoMinify` passed. Host tests cover themed hit testing, accessibility release, cancellation/multitouch, ordinary Space, stale editor/pins, privacy gates and no request on preview, but are not a full device test. The full suite was rerun: **253 tests, 10 failures** in the same three upstream HeliBoard suites previously reproduced on the unmodified baseline. Full lint remains red with **68 upstream missing-translation errors, 100 warnings and one hint**; no findings were reported in files changed for Agent/Acts/the Action popup.

On a physical API 34 phone, a **temporary 212ms key long-press delay** allowed ADB's `input draganddrop` to hold before sliding. With the Action popup enabled, hold → slide → release opened the exact generic Agent preview and a pinned Summarize preview. Cancelling each kept `wheel test` unchanged; sliding out opened no preview and added no Space; a normal Space tap added exactly one. With the feature OFF, holding Space opened the original input-method picker. The test delay was reset to 300ms, the QA pin removed, the feature turned OFF, and HeliBoard restored as default with Hermes Keys disabled. No wheel-initiated **Send** or **Insert** was performed. A separate earlier toolbar-based test exercised authenticated Hermes response and explicit insertion. This is a synthetic-field smoke test, **not** TalkBack, every editor/layout, or long-duration daily-use certification.

### Device QA still required

1. Repeat hold → slide → release at the default 300ms delay by hand; test two/three pins, narrow/landscape/split/floating layouts, large fonts, and TalkBack hover/click navigation.
2. Test pre-hold cursor swipes, stationary hold, release outside without entering, window `ACTION_CANCEL`, second-finger release orders and non-distinct multitouch; verify no stray Space or prompt.
3. Switch editors/selection, hide the IME, lock the phone, and edit/unpin an Act during a hold. Test password, incognito/no-learning and disconnected fields. Confirm no retargeting, text leak or request.
4. In the restricted test environment, exercise the wheel's explicit Send → result review → Insert path and longer offline/retry sessions. Host tests and this smoke test do not establish actual TalkBack behavior or broad daily-use reliability.

## Upstream HeliBoard documentation and attribution

The following retained documentation, distribution links, credits, and funding describe **upstream HeliBoard**, not distribution or sponsorship of Hermes Keys. Upstream HeliBoard is a privacy-conscious, customizable keyboard based on AOSP / OpenBoard and does not use INTERNET permission. That offline-only claim does **not** apply to this fork's opt-in agent feature.

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/helium314.keyboard/)
[<img src="https://user-images.githubusercontent.com/663460/26973090-f8fdc986-4d14-11e7-995a-e7c5e79ed925.png" alt="Get APK from GitHub" height="80">](https://github.com/HeliBorg/HeliBoard/releases/latest)
[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" alt="Get it on IzzyOnDroid" height="80">](https://apt.izzysoft.de/fdroid/index/apk/helium314.keyboard)

## Table of Contents

- [Features](#features)
- [Contributing](#contributing-)
   * [Reporting Issues](#reporting-issues)
   * [Translations](#translations)
   * [To Community](#to-community)
   * [Code Contribution](CONTRIBUTING.md)
- [Links](#links)
- [License](#license)
- [Credits](#credits)
  * [Funding](#funding)

# Features
<ul>
  <li>Add dictionaries for suggestions and spell check</li>
  <ul>
    <li>build your own, or get them  <a href="https://codeberg.org/Helium314/aosp-dictionaries#dictionaries">here</a> (quality may vary)</li>
    <li>additional dictionaries for emojis or scientific symbols can be used to provide suggestions (similar to "emoji search")</li>
    <li>note that for Korean layouts, suggestions only work using <a href="https://github.com/openboard-team/openboard/commit/83fca9533c03b9fecc009fc632577226bbd6301f">this dictionary</a>, the tools in the dictionary repository are not able to create working dictionaries</li>
  </ul>
  <li>Customize keyboard themes (style, colors and background image)</li>
  <li>Emoji search (inline and separate, requires <a href="https://codeberg.org/Helium314/aosp-dictionaries">emoji dictionary</a>)</li>
  <ul>
    <li>can follow the system's day/night setting on Android 10+ (and on some versions of Android 9)</li>
    <li>can follow dynamic colors for Android 12+</li>
  </ul>
  <li>Customize keyboard <a href="https://github.com/HeliBorg/HeliBoard/blob/main/layouts.md">layouts</a> (only available when disabling <i>use system languages</i>)</li>
  <li>Customize special layouts, like symbols, number,  or functional key layout</li>
  <li>Multilingual typing</li>
  <li>Glide typing (<i>only with closed source library</i> ☹️)</li>
  <ul>
    <li>library not included in the app, as there is no compatible open source library available</li>
    <li>can be extracted from GApps packages ("<i>swypelibs</i>"), or downloaded <a href="https://github.com/erkserkserks/openboard/tree/46fdf2b550035ca69299ce312fa158e7ade36967/app/src/main/jniLibs">here</a> (click on the file and then "raw" or the tiny download button)</li>
  </ul>
  <li>Clipboard history</li>
  <li>One-handed mode</li>
  <li>Split keyboard</li>
  <li>Number pad</li>
  <li>Backup and restore your settings and learned word / history data</li>
</ul>

For [FAQ](https://github.com/HeliBorg/HeliBoard/wiki/FAQ), [hidden features](https://github.com/HeliBorg/HeliBoard/wiki/9.-Hidden-features) and more information about the app and features, please visit the [wiki](https://github.com/HeliBorg/HeliBoard/wiki)

# Contributing ❤

## Reporting Issues

Whether you encountered a bug, or want to see a new feature in HeliBoard, you can contribute to the project by opening a new issue [here](https://github.com/HeliBorg/HeliBoard/issues). Your help is always welcome!

Before opening a new issue, be sure to check the following:
 - **Does the issue already exist?** Make sure a similar issue has not been reported by browsing [existing issues](https://github.com/HeliBorg/HeliBoard/issues?q=). Please search open and closed issues. In case of feature requests you could also check the [FAQ](https://github.com/HeliBorg/HeliBoard/wiki/FAQ) and [hidden features](https://github.com/HeliBorg/HeliBoard/wiki/9.-Hidden-features).
 - **Is the issue still relevant?** Make sure your issue is not already fixed in the latest version of HeliBoard.
 - **Is it a single topic?** If you want to suggest multiple things, open multiple issues.
 - **Did you use the issue template?** It is important to make life of our kind contributors easier by avoiding issues that miss key information to their resolution.
 - **Is it written by a human?** Do not use LLMs or similar to generate issues. Having LLMs help with translation or similar is acceptable, but must be disclosed. See also [AI_USAGE.md](AI_USAGE.md)
Note that issues that that ignore part of the issue template will likely get treated with very low priority, as often they are needlessly hard to read or understand (e.g. huge screenshots, not providing a proper description, or addressing multiple topics). Blatant violation of the guidelines may result in the issue getting closed.

If you're interested, you can read the following useful text about effective bug reporting (a bit longer read): https://www.chiark.greenend.org.uk/~sgtatham/bugs.html

## Translations
Translations can be added using [Weblate](https://translate.codeberg.org/projects/heliboard/). You will need an account to update translations and add languages. Add the language you want to translate to in Languages -> Manage translated languages in the top menu bar.
Updating translations in a PR will not be accepted, as it may cause conflicts with Weblate translations.

Some notes on translations
* when translating metadata, translating the changelogs is rather useless. It's available as it was requested by translators.
* the `hidden_features_message` is horrible to translate with Weblate, and serves little benefit as it's just a copy of what's already in the wiki: https://github.com/HeliBorg/HeliBoard/wiki/9.-Hidden-features. It's been made available in the app on user request/contribution.

## To Community
There is the [discussions on GitHub](https://github.com/HeliBorg/HeliBoard/discussions), or if you prefer a more open network there is [Lemmy](https://lemmy.world/c/Heliboard).
You can share your themes, layouts and dictionaries with other people:
* Themes can be saved and loaded using the menu on top-right in the _adjust colors_ screen
  * you can share custom colors in a separate [discussion section](https://github.com/HeliBorg/HeliBoard/discussions/categories/custom-colors)
  * there are theme collections available at [Star-Trowa/heliboard-themes](https://github.com/Star-Trowa/heliboard-themes) and [PickleHik3/droid-tings](https://github.com/PickleHik3/droid-tings)
* Custom keyboard layouts are text files whose content you can edit, copy and share
  * this applies to main keyboard layouts and to special layouts adjustable in advanced settings
  * see [layouts.md](layouts.md) for details
  * you can share custom layouts in a separate [discussion section](https://github.com/HeliBorg/HeliBoard/discussions/categories/custom-layout)
  * [Roccobot's Layout Maker](https://roccobot.github.io/HeliBoard-RLM/) is a browser-based editor for json layout files
* Creating dictionaries is a little more work
  * first you will need a wordlist in the format from [here](https://codeberg.org/Helium314/aosp-dictionaries/src/branch/main/wordlists/sample.combined) and in the repository readme
    * you can load the wordlist dictionary directly in HeliBoard like you would load a dictionary, but other AOSP keyboard forks will likely need the .dict file
  * then you need to compile the dictionary using [external tools](https://github.com/remi0s/aosp-dictionary-tools)
    * when you want to add the dictionary to the [repository](https://codeberg.org/Helium314/aosp-dictionaries/), no need to compile to a .dict file
  * the resulting file (and ideally the wordlist too) can be shared with other users
  * note that there will not be any further dictionaries added to this app, but you can add dictionaries to the [dictionaries repository](https://codeberg.org/Helium314/aosp-dictionaries)

## Code Contribution
See [Contribution Guidelines](CONTRIBUTING.md)

# Links
* Info
  * [Wiki](https://github.com/HeliBorg/HeliBoard/wiki), including FAQ, help on customizing layouts, and gesture data gathering
  * [Layout documentation](layouts.md) (more technical info regarding layout customization)
  * [For creating custom dictionaries](https://codeberg.org/Helium314/aosp-dictionaries#wordlist-information) (see also top of the linked readme)
* Community
  * [Lemmy](https://lemmy.world/c/Heliboard)
  * [Reddit](https://www.reddit.com/r/HeliBoard)
  * GitHub [discussions](https://github.com/HeliBorg/HeliBoard/discussions)
* Other
  * [Translations](https://translate.codeberg.org/projects/heliboard/)
  * [Dictionaries](https://codeberg.org/Helium314/aosp-dictionaries)
  * [k3lp](https://codeberg.org/k3lp/k3lp) is a WIP library for keyboard layout parsing that will be implemented in HeliBoard when ready (created by [FlorisBoard](https://github.com/florisboard/florisboard/) maintainers)
  * [swipe-o-scope](https://codeberg.org/eclexic/swipe-o-scope) for visualizing gesture data as created when using gesture data gathering

# License

HeliBoard (as a fork of OpenBoard) is licensed under GNU General Public License v3.0.

 > Permissions of this strong copyleft license are conditioned on making available complete source code of licensed works and modifications, which include larger works using a licensed work, under the same license. Copyright and license notices must be preserved. Contributors provide an express grant of patent rights.

See repo's [LICENSE](/LICENSE) file.

Since the app is based on Apache 2.0 licensed AOSP Keyboard, an [Apache 2.0](LICENSE-Apache-2.0) license file is provided.
The icon is licensed under [Creative Commons BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). A [license file](LICENSE-CC-BY-SA-4.0) is also included.

# Credits
- Icon by [Fabian OvrWrt](https://github.com/FabianOvrWrt) with contributions from [The Eclectic Dyslexic](https://github.com/the-eclectic-dyslexic)
- [OpenBoard](https://github.com/openboard-team/openboard)
- [AOSP Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/)
- [LineageOS](https://review.lineageos.org/admin/repos/LineageOS/android_packages_inputmethods_LatinIME)
- [Simple Keyboard](https://github.com/rkkr/simple-keyboard)
- [Indic Keyboard](https://gitlab.com/indicproject/indic-keyboard)
- [FlorisBoard](https://github.com/florisboard/florisboard/)
- Our [contributors](https://github.com/HeliBorg/HeliBoard/graphs/contributors)

## Funding

This project is funded through [NGI Mobifree Fund](https://nlnet.nl/mobifree), a fund established by [NLnet](https://nlnet.nl) with financial support from the European Commission's [Next Generation Internet](https://ngi.eu) program. Learn more at the [NLnet project page](https://nlnet.nl/project/GestureTyping).

[<img src="https://nlnet.nl/logo/banner.png" alt="NLnet foundation logo" width="20%" />](https://nlnet.nl)

Further the project benefits from donations provided by many users (thank you all!).
