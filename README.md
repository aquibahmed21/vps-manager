# VPS Host (Android)

Turns the phone into a small server host:

1. Downloads `aquibahmed21/express` (zip from GitHub) to `/sdcard/VpsHost/express`; **Update** re-downloads the latest.
2. **Start server** runs it with embedded Node (nodejs-mobile) and opens a Cloudflare quick tunnel to it.
3. Each new `*.trycloudflare.com` URL is committed to `aquibahmed21/server` (`server-url.json`) and GitHub Pages is rebuilt.
4. **Verify URL** compares the live tunnel with what the repository holds.

Stays alive: foreground service (sticky), restart alarm watchdog, boot receiver, battery-optimisation exemption,
device-admin. Network loss / tunnel exit / node exit are all retried automatically. "Force stop" from system settings
cannot be resisted by any normal app; it stays stopped until the user opens the app again.

Locked until all permissions are granted: notifications, all-files access, unrestricted battery, device administrator.

Build: `scripts/fetch-binaries.sh` then `./gradlew assembleDebug`.

## Local LLM (GGUF)

The **LLM** tab runs llama.cpp's `llama-server` on the phone. It uses any `.gguf` in `/sdcard/VpsHost/models/` or one downloaded from a link in the app. Start and Stop are manual.

- Build the binary once with `scripts/build-llama.sh` (needs the Android NDK). It lands in `jniLibs/` as `libllama-server.so`.
- The model server listens on `127.0.0.1:8080`. The Node server forwards `/v1/*` to it open by default (no login system yet). Switch on "Require API key" in the LLM tab to demand `Authorization: Bearer <key>`.
- Any OpenAI-compatible client works with base URL `<tunnel-url>/v1`.

## Remote management (VPS Monitor app)

The app serves a management API on `127.0.0.1:3001`; the Node gateway forwards `/manage/*` to it, so it is reachable through the same tunnel.
Every call needs `Authorization: Bearer <admin token>` (Settings > Remote access shows and rotates it). It covers status, logs, file browsing/deleting, and server/LLM control. See `../05-remote-manager-app/README.md` for the endpoint list.
