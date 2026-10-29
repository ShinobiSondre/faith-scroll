#!/bin/bash
# Runs FeedTest on the emulator and posts its FSTEST lines as a note on the build.
adb logcat -c
adb logcat -G 16M || true
gradle :app:connectedDebugAndroidTest --no-daemon -Pandroid.testInstrumentationRunnerArguments.class=com.faithscroll.app.FeedTest > gradle-test.log 2>&1
echo "gradle exit $?"
adb logcat -d -s FSTEST:I | sed -E 's/^.*FSTEST: //' > fstest.txt
if [ ! -s fstest.txt ]; then
  echo "(no FSTEST lines)" > fstest.txt
  grep -E "FAILED|Exception|Error|tests completed|BUILD" gradle-test.log | head -20 >> fstest.txt
fi
cat fstest.txt
msg=$(sed ':a;N;$!ba;s/%/%25/g;s/\r//g;s/\n/%0A/g' fstest.txt | cut -c1-60000)
echo "::warning title=Feed test::$msg"
