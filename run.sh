#!/usr/bin/env bash
# Build debug APK, install to a connected device, and launch.
#   ./run.sh           # assemble + install + start
#   ./run.sh skip      # install last debug APK only (no rebuild)
#   ./run.sh build     # assemble only
# Needs: JDK 17+, Android SDK (adb), and a device/emulator (adb devices)
# Downloads (Gradle/deps) default to local proxy 127.0.0.1:10808 (socks5).
#   PROXY=off ./run.sh   # disable proxy
#   PROXY=http://127.0.0.1:7890 ./run.sh
set -euo pipefail
cd "$(dirname "$(readlink -f "$0" 2>/dev/null || realpath "$0" 2>/dev/null || echo "$0")")"

PKG="cn.rmshadows.textsend"
ACTIVITY="cn.rmshadows.textsend/.MainActivity"
APK="$PWD/app/build/outputs/apk/debug/app-debug.apk"
arg="${1:-}"

# AGP 需要带 jlink 的完整 JDK；Kotlin DSL 也吃不下 JDK 25 版本号。
# 即使外部环境已设 JAVA_HOME，缺 jlink（常见于 jre-headless）也强制换掉。
_pick_jdk() {
  local _j
  for _j in \
    "$HOME/Prog/jdk-17.0.21+9" \
    "$HOME"/Prog/jdk-17* \
    /usr/lib/jvm/java-17-openjdk-amd64 \
    /usr/lib/jvm/temurin-17-jdk-amd64 \
    /usr/lib/jvm/temurin-21-jdk-amd64 \
    /usr/lib/jvm/java-21-openjdk-amd64
  do
    if [[ -x "$_j/bin/java" && -x "$_j/bin/jlink" ]]; then
      echo "$_j"
      return 0
    fi
  done
  return 1
}
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/jlink" ]]; then
  _jdk="$(_pick_jdk || true)"
  if [[ -n "${_jdk:-}" ]]; then
    export JAVA_HOME="$_jdk"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  echo "==> JAVA_HOME $JAVA_HOME"
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/jlink" ]]; then
  echo "Need a full JDK with jlink (17/21). Current JAVA_HOME=${JAVA_HOME:-unset}" >&2
  exit 1
fi

# SDK：local.properties / 环境变量 / 默认 ~/Android/Sdk
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
  if [[ -f "$PWD/local.properties" ]]; then
    _sdk="$(sed -n 's/^sdk.dir=//p' "$PWD/local.properties" | tail -1 | tr -d '\r')"
    _sdk="${_sdk//\\//\/}"
    if [[ -n "$_sdk" && -d "$_sdk" ]]; then
      export ANDROID_HOME="$_sdk"
    fi
  fi
  if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/Android/Sdk" ]]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  fi
fi
if [[ -n "${ANDROID_HOME:-}" ]]; then
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
fi

if [[ "${PROXY:-}" != "off" ]]; then
  _proxy="${PROXY:-socks5://127.0.0.1:10808}"
  export ALL_PROXY="$_proxy" all_proxy="$_proxy"
  export http_proxy="$_proxy" https_proxy="$_proxy"
  export HTTP_PROXY="$_proxy" HTTPS_PROXY="$_proxy"
  # Gradle/JVM also honor socks when using socks5://127.0.0.1:10808
  case "$_proxy" in
    socks5://*|socks5h://*|socks://*)
      _host="${_proxy##*://}"; _host="${_host%%:*}"; _port="${_proxy##*:}"
      export GRADLE_OPTS="${GRADLE_OPTS:-} -DsocksProxyHost=${_host} -DsocksProxyPort=${_port}"
      ;;
    http://*|https://*)
      _rest="${_proxy#*://}"; _host="${_rest%%:*}"; _port="${_rest##*:}"; _port="${_port%%/*}"
      export GRADLE_OPTS="${GRADLE_OPTS:-} -Dhttp.proxyHost=${_host} -Dhttp.proxyPort=${_port} -Dhttps.proxyHost=${_host} -Dhttps.proxyPort=${_port}"
      ;;
  esac
  echo "==> proxy $_proxy"
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "Missing adb. Add Android platform-tools to PATH." >&2
  exit 1
fi

devices="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
unauthorized="$(adb devices | awk 'NR>1 && $2=="unauthorized" {print $1}')"
if [[ -z "$devices" ]]; then
  if [[ -n "$unauthorized" ]]; then
    echo "Device connected but unauthorized. Unlock the phone and allow USB debugging." >&2
  else
    echo "No adb device. Plug in the phone (USB debugging on) or start an emulator." >&2
  fi
  adb devices >&2
  exit 1
fi

if [[ "${arg,,}" != "skip" && "${arg,,}" != "fast" ]]; then
  echo "==> ./gradlew assembleDebug"
  ./gradlew assembleDebug
fi

if [[ "${arg,,}" == "build" ]]; then
  echo "APK: $APK"
  exit 0
fi

if [[ ! -f "$APK" ]]; then
  echo "Missing $APK" >&2
  echo "Run without \"skip\" so Gradle can build it." >&2
  exit 1
fi

echo "==> adb install -r \"$APK\""
if ! install_out="$(adb install -r "$APK" 2>&1)"; then
  echo "$install_out"
  if echo "$install_out" | grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'; then
    echo "==> signature mismatch with installed app; adb uninstall $PKG (app data cleared)"
    adb uninstall "$PKG" || true
    echo "==> adb install \"$APK\""
    adb install "$APK"
  else
    exit 1
  fi
else
  echo "$install_out"
fi

echo "==> adb shell am start -n $ACTIVITY"
adb shell am start -n "$ACTIVITY"
echo "Done ($PKG)"
