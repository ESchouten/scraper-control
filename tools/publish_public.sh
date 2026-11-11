#!/bin/sh
# Push this repository's current main to the PUBLIC repository as one fresh commit.
#
#   tools/publish_public.sh https://github.com/<owner>/scraper-control.git
#
# Why a fresh commit: the old history of this (private) repository still holds build secrets,
# so the public repository gets the files of today, not the commits. Build numbers keep
# rising: scraper.buildBase in the copy is set to this repository's commit count, so a device
# with an old build still takes the first public release.
# Run it once, from a full clone (not shallow), on the machine with ~/.scraper-control/secrets:
# it refuses when a build secret or a private key would go along.
set -eu
URL=${1:?"usage: tools/publish_public.sh <git url of the empty public repository>"}
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

test -z "$(git status --porcelain)" || { echo "Uncommitted changes: commit or stash them first."; exit 1; }
test "$(git rev-parse --is-shallow-repository)" = false || { echo "Shallow clone: run git fetch --unshallow first."; exit 1; }

# Never publish a secret.
SECRETS="${HOME}/.scraper-control/secrets"
if [ -f "$SECRETS" ]; then
  while IFS= read -r line; do
    case "$line" in *=*) VALUE=${line#*=} ;; *) continue ;; esac
    if [ -n "$VALUE" ] && git grep -q -F -e "$VALUE" HEAD; then echo "STOP: a build secret is in the files."; exit 1; fi
  done < "$SECRETS"
fi
# (this script itself holds the search pattern, so leave it out of the search)
if git grep -q -e "BEGIN EC PRIVATE KEY" -e "BEGIN PRIVATE KEY" HEAD -- . ':!tools/publish_public.sh'; then echo "STOP: a private key is in the files."; exit 1; fi
if git ls-files | grep -q -e "drivers/secrets.h" -e "include/config.h$"; then echo "STOP: a git-ignored secret file is tracked."; exit 1; fi

BASE=$(git rev-list --count HEAD)
VERSION=$(sed -n 's/^scraper.version=//p' native/gradle.properties)
TMP=$(mktemp -d)
git archive HEAD | tar -x -C "$TMP"
sed -i.bak "s/^scraper.buildBase=.*/scraper.buildBase=$BASE/" "$TMP/native/gradle.properties"
rm "$TMP/native/gradle.properties.bak"

cd "$TMP"
git init -q -b main
git add -A
git -c user.name="$(git -C "$ROOT" config user.name)" -c user.email="$(git -C "$ROOT" config user.email)" \
  commit -q -m "Scraper Control $VERSION

A controller for manure scraper robots: M5Stack buttons, Android, iOS and desktop apps, and
a Python library, on one robot API with a driver per robot type."
git remote add origin "$URL"
git push -u origin main
echo "Published to $URL (build numbers continue from $BASE)."
echo "Next: set the secrets there (BUILD_SECRETS, FIRMWARE_SIGNING_KEY, ANDROID_KEYSTORE, ANDROID_KEYSTORE_PASSWORD)"
echo "and run Actions > release."
