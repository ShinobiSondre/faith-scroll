#!/usr/bin/env bash
# Runs the on-device subtitle test on the emulator and reports the result as a build annotation.
set +e
adb logcat -c
gradle :app:connectedDebugAndroidTest --no-daemon -Pandroid.testInstrumentationRunnerArguments.class=com.faithscroll.app.AsrDeviceTest > device.log 2>&1
code=$?
adb logcat -d -s FaithScrollTest:I | sed 's/^.*FaithScrollTest: //' > report.txt
grep -E "^e: |FAILED|What went wrong" -A3 device.log | head -20 >> report.txt
echo "gradle exit: $code" >> report.txt
msg=$(sed ':a;N;$!ba;s/%/%25/g;s/\r//g;s/\n/%0A/g' report.txt | cut -c1-60000)
echo "::warning title=Device subtitle test::$msg"
exit 0
