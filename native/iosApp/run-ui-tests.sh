#!/bin/zsh
# Builds the iOS app, runs the XCUITest scenarios on a simulator and exports the screenshots.
#   ./run-ui-tests.sh [test_name] [--appearance dark] [--text accessibility-extra-large]
# Screenshots: $SHOTS (default /tmp/shots)/<nl|en>/NN-name.png. The simulator's test runner
# cannot write outside its sandbox, so they are taken from the result bundle afterwards.
set -u
cd ${0:A:h}
DEVICE=${IOS_DEVICE:-"iPhone 15 Pro"}
SHOTS=${SHOTS:-/tmp/shots}
WORK=${WORK:-/tmp/scraper-ios}
ONLY=() APPEARANCE=light TEXT=large
while [ $# -gt 0 ]; do
  case $1 in
    --appearance) APPEARANCE=$2; shift ;;
    --text) TEXT=$2; shift ;;
    test_*) ONLY=(-only-testing:ScraperControlUITests/ScenarioTests/$1) ;;
  esac
  shift
done
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
UDID=$(xcrun simctl list devices available | grep -m1 "    $DEVICE (" | sed -E 's/.*\(([0-9A-F-]{36})\).*/\1/')
[ -z "$UDID" ] && { echo "no simulator '$DEVICE'"; exit 2; }
xcrun simctl boot $UDID 2>/dev/null; xcrun simctl bootstatus $UDID -b >/dev/null
xcrun simctl ui $UDID appearance $APPEARANCE
xcrun simctl ui $UDID content_size $TEXT
xcodegen >/dev/null
mkdir -p $WORK; rm -rf $WORK/result.xcresult $WORK/att
xcodebuild test -project ScraperControl.xcodeproj -scheme ScraperControl -destination "id=$UDID" \
  -derivedDataPath $WORK/dd -resultBundlePath $WORK/result.xcresult $ONLY > $WORK/test.log 2>&1
rc=$?
grep -E "^/.*error:" $WORK/test.log | sed 's#.*/iosApp/##' | sort -u | head -20
grep -E "Test Case .*(passed|failed)" $WORK/test.log | sed -E "s/.*ScenarioTests (test_[a-z_]+)\]' (passed|failed).*/\2 \1/" | sort -u
grep -E "error: -\[ScraperControlUITests" $WORK/test.log | sed -E 's#.*ScenarioTests.swift:([0-9]+): error: -\[ScraperControlUITests.ScenarioTests (test_[a-z_]+)\] : (.*)#\2 (line \1): \3#' | sort -u
mkdir -p $WORK/att $SHOTS/nl $SHOTS/en
xcrun xcresulttool export attachments --path $WORK/result.xcresult --output-path $WORK/att >/dev/null 2>&1
python3 - $WORK/att $SHOTS <<'EOF'
import json, re, shutil, sys
att, shots = sys.argv[1:3]
for t in json.load(open(f"{att}/manifest.json")):
    for a in t.get("attachments", []):
        m = re.match(r"(nl|en)-(\d\d-[a-z-]+)_", a.get("suggestedHumanReadableName", ""))
        if m:
            shutil.copy(f"{att}/{a['exportedFileName']}", f"{shots}/{m.group(1)}/{m.group(2)}.png")
EOF
xcrun simctl ui $UDID appearance light; xcrun simctl ui $UDID content_size large
echo "exit=$rc  screenshots: $SHOTS"
exit $rc
