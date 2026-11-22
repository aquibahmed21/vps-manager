#!/usr/bin/env bash
# Fetches the large runtime pieces into app/src/main/{jniLibs,assets} (gitignored).
# Needs: gh, curl, unzip, npm. Run once before ./gradlew assembleDebug.
set -euo pipefail
cd "$(dirname "$0")/.."
LIBS=app/src/main/jniLibs/arm64-v8a
ASSETS=app/src/main/assets
mkdir -p "$LIBS" "$ASSETS"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

echo "== Node runtime (nodejs-mobile v18.20.4, arm64)"
gh release download v18.20.4 -R nodejs-mobile/nodejs-mobile -p 'nodejs-mobile-v18.20.4-android.zip' -D "$WORK"
unzip -q -o "$WORK/nodejs-mobile-v18.20.4-android.zip" 'bin/arm64-v8a/libnode.so' -d "$WORK/node"
cp "$WORK/node/bin/arm64-v8a/libnode.so" "$LIBS/libnode.so"

echo "== libc++_shared.so  (TODO: libnode.so needs it; place an arm64 copy in $LIBS manually)"

echo "== cloudflared (arm64)"
# NOTE: the stock build cannot resolve DNS on Android (no /etc/resolv.conf). A build with a patched resolver is
#       needed; place it as $LIBS/libcloudflared.so. See README.
[ -f "$LIBS/libcloudflared.so" ] || echo "   missing: $LIBS/libcloudflared.so"

echo "== express dependencies bundle"
git clone -q --depth 1 https://github.com/aquibahmed21/express.git "$WORK/express"
(cd "$WORK/express" && npm install --omit=dev --no-audit --no-fund >/dev/null && zip -qr "$OLDPWD/$ASSETS/node_modules.zip" node_modules)
node -e "console.log(JSON.stringify(require('$WORK/express/package.json').dependencies||{}))" > "$ASSETS/deps.json"
echo "done"
