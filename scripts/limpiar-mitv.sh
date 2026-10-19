#!/usr/bin/env bash
# Limpieza de bloatware para Mi TV (MiTV_MOOR6) por adb.
# Uso:
#   ./limpiar-mitv.sh [IP]              quita todos los paquetes de la lista
#   ./limpiar-mitv.sh [IP] --restaurar  recupera todos los paquetes de la lista
# IP por defecto: 192.168.1.198 (puerto 5555)

IP="${1:-192.168.1.198}"
MODO="${2:-quitar}"
DEVICE="$IP:5555"
LAUNCHER="com.gathin.tvhome/.MainActivity"

PAQUETES=(
  # Telemetría, publicidad y apps de Xiaomi/Google
  com.miui.tv.analytics
  com.xiaomi.statistic
  com.xiaomi.tvqs.overseas
  com.mitv.tvhome.michannel
  com.mitv.tvhome.oemtab
  com.mitv.tvhome.mitvplus
  com.xiaomi.mimusic2
  com.xiaomi.mitv.scenemode
  com.google.android.feedback
  com.google.android.play.games
  com.google.android.videos
  com.xiaomi.mitv.tvmanager
  # Streaming
  com.tubitv
  tv.twitch.android.app
  # Reproductores locales
  com.mitv.videoplayer
  com.mitv.gallery
  com.xiaomi.mitv.mediaexplorer
  # Transmitir pantalla y Chromecast
  com.xiaomi.mitv.smartshare
  com.xiaomi.wfdsinkhelperservice
  com.google.android.apps.mediashell
  # Textos legales
  com.xiaomo.tv.milegal
  com.xm.webcontent
  # Del repo tv-vibehack: autoinstalación, demo, actualizaciones de firmware y voz
  android.autoinstalls.config.xiaomi.amelie
  fusion.android.tv.demo
  com.xiaomi.mitv.updateservice
  com.google.android.katniss
  # Protector de pantalla
  com.google.android.apps.tv.dreamx
  # Launchers de fábrica (com.gathin.tvhome queda como inicio)
  com.google.android.apps.tv.launcherx
  com.mitv.tvhome.atv
  com.google.android.tungsten.setupwraith
)

adb connect "$DEVICE" >/dev/null 2>&1
if ! adb devices | grep -q "^$DEVICE[[:space:]]*device"; then
  echo "No se pudo conectar a $DEVICE. Revisa la IP y que la depuración de red esté activa."
  exit 1
fi

if [ "$MODO" = "--restaurar" ]; then
  for p in "${PAQUETES[@]}"; do
    printf "%s: " "$p"
    adb -s "$DEVICE" shell cmd package install-existing "$p" | tr -d '\r'
  done
  echo "Listo. Si quitaste los launchers, el inicio vuelve a ser el de fábrica al reiniciar la TV."
  exit 0
fi

if ! adb -s "$DEVICE" shell pm list packages | tr -d '\r' | grep -q "package:com.gathin.tvhome"; then
  echo "El launcher com.gathin.tvhome no está instalado. Compílalo e instálalo como dice el README (sección «Compilar e instalar»)"
  exit 1
fi

# el launcher nuevo como inicio antes de quitar los launchers de fábrica
adb -s "$DEVICE" shell cmd package set-home-activity "$LAUNCHER" | tr -d '\r'

for p in "${PAQUETES[@]}"; do
  if adb -s "$DEVICE" shell pm list packages | tr -d '\r' | grep -qx "package:$p"; then
    printf "%s: " "$p"
    adb -s "$DEVICE" shell pm uninstall -k --user 0 "$p" 2>&1 | tr -d '\r'
  else
    echo "$p: ya no está"
  fi
done

# Animaciones a 0.5
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb -s "$DEVICE" shell settings put global "$k" 0.5
done

# Volver a fijar el launcher nuevo como inicio
adb -s "$DEVICE" shell cmd package set-home-activity "$LAUNCHER" | tr -d '\r'
echo "Terminado. Reinicia la TV y comprueba que el botón Home abre el launcher nuevo."
