#!/bin/sh
# Push and (re)start the PocketPad receiver on an Android TV over adb.
# Usage: ./start-on-tv.sh <tv-ip> [pairing-code]
# Needed again after every TV reboot: without root only adb can start it.
set -e
TV="${1:?usage: $0 <tv-ip> [pairing-code]}"
DIR="$(cd "$(dirname "$0")" && pwd)"
S="$TV:5555"
adb connect "$S" >/dev/null
adb -s "$S" push "$DIR/pocketpad-tv" /data/local/tmp/pocketpad-tv >/dev/null
adb -s "$S" shell "chmod 755 /data/local/tmp/pocketpad-tv; killall pocketpad-tv 2>/dev/null; \
  nohup /data/local/tmp/pocketpad-tv $2 >/data/local/tmp/pocketpad-tv.log 2>&1 </dev/null &"
sleep 1
adb -s "$S" shell cat /data/local/tmp/pocketpad-tv.log
echo "On the phone: PC address = $TV, code as above, then Connect."
