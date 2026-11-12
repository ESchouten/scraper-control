#!/bin/sh
# Xcode build step (project.yml): the bundle's version is the apps' version, like Gradle and
# the M5 do it: MAJOR.MINOR from ../gradle.properties (scraper.version) + the build number
# (commit count + scraper.buildBase), so 1.6.120 with build 120. Written into the built
# Info.plist, before Xcode signs the bundle.
set -e
PROPS="$SRCROOT/../gradle.properties"
VERSION=$(sed -n 's/^scraper.version=//p' "$PROPS")
BASE=$(sed -n 's/^scraper.buildBase=//p' "$PROPS")
COUNT=$(git -C "$SRCROOT" rev-list --count HEAD 2>/dev/null || echo 0)
BUILD=$((COUNT + ${BASE:-0}))
PLIST="$TARGET_BUILD_DIR/$INFOPLIST_PATH"
put() {   # set the key, or add it when the plist has none
  /usr/libexec/PlistBuddy -c "Set :$1 $2" "$PLIST" 2>/dev/null || /usr/libexec/PlistBuddy -c "Add :$1 string $2" "$PLIST"
}
put CFBundleShortVersionString "$VERSION.$BUILD"
put CFBundleVersion "$BUILD"
echo "version $VERSION.$BUILD ($BUILD)"
