#!/usr/bin/env bash
# Compila el launcher, lo instala en la TV por Wi-Fi, le da los permisos que no se pueden dar desde la
# TV y lo deja como pantalla de inicio.
# Uso:
#   ./scripts/deploy.sh [IP]           compilación release (necesita launcher/keystore.properties)
#   ./scripts/deploy.sh [IP] --debug   compilación debug
# IP por defecto: 192.168.1.198 (puerto 5555)
set -euo pipefail

IP="${1:-192.168.1.198}"
MODO="${2:-release}"
DEVICE="$IP:5555"
PKG="com.gathin.tvhome"
HOME_ACTIVITY="$PKG/.MainActivity"
LAUNCHER_DIR="$(cd "$(dirname "$0")/../launcher" && pwd)"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export ANDROID_HOME="${ANDROID_HOME:-$([ -d ~/Library/Android/sdk ] && echo ~/Library/Android/sdk || echo /opt/homebrew/share/android-commandlinetools)}"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$LAUNCHER_DIR"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
if [ "$MODO" != "--debug" ] && [ ! -f keystore.properties ]; then
  echo "No hay launcher/keystore.properties: compilo en modo debug."
  MODO="--debug"
fi
if [ "$MODO" = "--debug" ]; then
  ./gradlew assembleDebug -q --console=plain
  APK=app/build/outputs/apk/debug/app-debug.apk
else
  ./gradlew assembleRelease -q --console=plain
  APK=app/build/outputs/apk/release/app-release.apk
fi
echo "APK: $APK ($(du -h "$APK" | cut -f1))"

adb connect "$DEVICE" >/dev/null 2>&1 || true
if ! adb devices | grep -q "^$DEVICE[[:space:]]*device"; then
  echo "No se pudo conectar a $DEVICE. Revisa la IP y que la depuración de red esté activa."
  exit 1
fi

# Una firma distinta (debug ↔ release) no se puede actualizar encima: hay que desinstalar antes.
# Los ajustes del launcher se pierden en ese caso.
if ! OUT=$(adb -s "$DEVICE" install -r "$APK" 2>&1); then
  if echo "$OUT" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
    echo "La firma cambió: desinstalo la versión anterior y vuelvo a instalar."
    # El temporizador de apagado hace al launcher administrador del dispositivo, y Android no deja
    # desinstalar un administrador activo. Si esto falla, desactívalo en Ajustes de la TV → Seguridad
    # → Administradores de dispositivos y vuelve a ejecutar el script.
    adb -s "$DEVICE" shell dpm remove-active-admin --user 0 "'$PKG/.SleepTimer\$Admin'" >/dev/null 2>&1 || true
    if ! adb -s "$DEVICE" uninstall "$PKG" >/dev/null; then
      echo "No se pudo desinstalar: el launcher es administrador del dispositivo (apagado automático)."
      echo "Desactívalo en Ajustes de la TV → Seguridad → Administradores de dispositivos y vuelve a ejecutar."
      exit 1
    fi
    adb -s "$DEVICE" install "$APK"
  else
    echo "$OUT"
    exit 1
  fi
fi

# Permisos que la TV no permite dar desde sus menús.
adb -s "$DEVICE" shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
adb -s "$DEVICE" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
adb -s "$DEVICE" shell appops set "$PKG" WRITE_SETTINGS allow
# Abrir la entrada elegida al encender la TV aunque el inicio no esté delante.
adb -s "$DEVICE" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
# Temporizador de apagado: solo puede apagar la pantalla un administrador del dispositivo.
adb -s "$DEVICE" shell dpm set-active-admin --user 0 "'$PKG/.SleepTimer\$Admin'" >/dev/null 2>&1 || true
adb -s "$DEVICE" shell cmd package set-home-activity --user 0 "$HOME_ACTIVITY" >/dev/null
adb -s "$DEVICE" shell input keyevent KEYCODE_HOME
echo "Listo: el launcher está instalado y es la pantalla de inicio."
