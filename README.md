# tv-vibehack · Mi TV sin anuncios

Launcher ligero tipo Apple TV para Android TV, con ajustes propios, y scripts para quitar el
bloatware y desplegar por adb. Está probado en una **Xiaomi Mi TV (MiTV_MOOR6, Android TV 11,
1,8 GB de RAM)**.

Es un fork de [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). El README original
(en inglés, con el método completo de debloat para otras marcas) está en
[`docs/README-original.md`](docs/README-original.md).

![Inicio con fondo de pantalla y 6 columnas](docs/screenshots/inicio-fondo-6-columnas.png)

---

## Contenido

- [Capturas](#capturas)
- [Funciones](#funciones)
- [Fondos de pantalla](#fondos-de-pantalla)
- [Compilar e instalar](#compilar-e-instalar)
- [Quitar el bloatware](#quitar-el-bloatware)
- [Estructura del código](#estructura-del-código)
- [Notas](#notas)
- [Créditos y licencia](#créditos-y-licencia)

---

## Capturas

Capturas tomadas en la TV con `adb shell screencap`.

| Inicio | Menú al mantener pulsada una app |
|---|---|
| ![Inicio con el dock](docs/screenshots/inicio.png) | ![Menú desplegable junto a la app, con el resto desenfocado](docs/screenshots/menu-app.png) |

| Ajustes: apps | Ajustes: ordenar apps |
|---|---|
| ![Mostrar u ocultar apps](docs/screenshots/ajustes-apps.png) | ![Moviendo una app en la cuadrícula](docs/screenshots/ajustes-ordenar.png) |

| Ajustes: personalización | Ajustes: apps del dock |
|---|---|
| ![Columnas y atenuar fondo](docs/screenshots/ajustes-personalizacion.png) | ![Selector de las 4 apps del dock](docs/screenshots/ajustes-apps-dock.png) |

| Ajustes: protector de pantalla | Protector de pantalla |
|---|---|
| ![Tiempo de espera y Probar ahora](docs/screenshots/ajustes-protector.png) | ![Reloj sobre el fondo de pantalla](docs/screenshots/protector.png) |

| Ajustes: rendimiento |  |
|---|---|
| ![Memoria, liberar memoria, animaciones y blur](docs/screenshots/ajustes-rendimiento.png) |  |

## Funciones

### Ajustes

El botón **Ajustes** de la barra superior abre un modal con barra lateral, con el mismo estilo de
vidrio que el menú de Entradas.

| Sección | Qué hace |
|---|---|
| **Idioma** | Inglés, español, o el idioma de la TV. Cambia textos, saludo y fecha al momento. |
| **Apps del dock** | Elige las apps del dock y su orden (una por columna; se numeran). Incluye "Restaurar apps predeterminadas". |
| **Apps** | *Mostrar u ocultar*: desmarca una app para quitarla del inicio. *Ordenar*: pulsa OK para tomar una app, muévela con arriba y abajo y suéltala con OK o Atrás. "Volver al orden alfabético" lo deshace. |
| **Personalización** | Columnas (3, 4, 5 o 6); los iconos cambian de tamaño para llenar la fila y el dock usa las mismas columnas, alineado con la cuadrícula. Atenuar el fondo de pantalla (sin atenuar, 20 %, 40 % o 60 %). |
| **Fondo de pantalla** | 8 fondos incluidos, con miniatura, o **Ninguno** para el negro con brillo de color. |
| **Protector de pantalla** | Empezar después de 5, 10, 15, 30 minutos o 1 hora, o desactivado. **Probar ahora** lo muestra al momento. |
| **Rendimiento** | Memoria en uso con barra, **Liberar memoria**, animaciones del launcher, blur (dock esmerilado y menús) y velocidad de las animaciones del sistema (normales, 0,5× o apagadas). |
| **Ajustes de la TV** | Abre los ajustes de Android (imagen, sonido, red…). |

Los ajustes se guardan en `SharedPreferences` y sobreviven a reinicios. Los valores por defecto
(dock inicial, apps ocultas, app de TV en vivo, columnas) están en
[`config.xml`](launcher/app/src/main/res/values/config.xml).

### Menú al mantener pulsada una app

Mantén pulsado OK sobre cualquier app. Aparece un desplegable junto a ella, con el mismo estilo que
el menú de Entradas (debajo de la app, o encima si no cabe). El resto del inicio se desenfoca y la app
pulsada queda nítida. Opciones:
**Abrir**, **Mover al dock** / **Quitar del dock**, **Mover en la cuadrícula** (abre Ajustes → Apps con
la app ya tomada), **Ocultar**, **Información de la app** y **Desinstalar** (solo en apps que no son
del sistema). Si el dock está lleno, la app nueva ocupa el último lugar.

### Protector de pantalla

El reloj del launcher se mueve despacio sobre tu fondo de pantalla oscurecido, o sobre un brillo de
color si no hay fondo. Cambia de sitio cada minuto para no marcar el panel. Se activa con el sistema
de Android, así que también funciona fuera del launcher. "Desactivado" solo quita el protector; el
temporizador de apagado de la TV no cambia.

### Pantalla de inicio

- **Saludo y fecha** en la línea pequeña de arriba ("BUENAS NOCHES · DOMINGO 18 DE OCTUBRE"), para
  que la hora tenga su fila y las fechas largas no choquen con los botones. De 19 a 22 h dice "Buenas
  noches".
- **Desvanecido superior.** Al bajar a la cuadrícula, las apps se desvanecen bajo la barra de la hora
  y los botones en vez de cortarse de golpe. Con fondo de pantalla, la barra lleva un degradado oscuro.
- **Brillo de color en todas las apps.** El fondo toma el color de la app enfocada, en el dock y en
  la cuadrícula.
- **Botones superiores con animación.** Al enfocarlos se escalan y suben con un rebote suave; al
  pulsarlos se hunden y rebotan antes de abrir.

## Fondos de pantalla

Los fondos van dentro de la app, en
[`launcher/app/src/main/assets/wallpapers/`](launcher/app/src/main/assets/wallpapers/). Vienen 8:
Acantilados estelares, Bajo el mar, Bosque al atardecer, Cometa sobre las nubes, Icebergs al
atardecer, Mirando los planetas, Nebulosa roja y Portal en ruinas.

- Formatos: `.jpg`, `.jpeg`, `.png` o `.webp`.
- El nombre del archivo es el título en los ajustes: `playa_azul-2.jpg` aparece como "Playa azul 2".
- Usa 1920 px de ancho. Con macOS puedes convertir cualquier imagen así:

  ```bash
  sips -s format jpeg -s formatOptions 85 --resampleWidth 1920 original.png --out launcher/app/src/main/assets/wallpapers/mi_fondo.jpg
  ```

- Después de añadir o quitar imágenes, vuelve a desplegar.

## Compilar e instalar

Requisitos en macOS (Homebrew):

```bash
brew install openjdk@17 android-commandlinetools android-platform-tools
```

```bash
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

Activa la **depuración de red** en la TV (Opciones de desarrollador). Después, un solo comando compila,
instala, da los permisos y deja el launcher como inicio:

```bash
./scripts/deploy.sh 192.168.1.198
```

[`scripts/deploy.sh`](scripts/deploy.sh) hace esto:

1. Compila la versión **release**: firmada con tu clave y optimizada con R8, más pequeña y rápida.
   Sin clave, compila la versión debug. Para forzar debug, añade `--debug`.
2. Instala el APK por Wi-Fi. Si la firma cambió (de debug a release), desinstala la anterior primero;
   en ese caso los ajustes del launcher vuelven a los valores por defecto.
3. Da los permisos que la TV no deja dar desde sus menús: ubicación (para leer el nombre del Wi-Fi),
   `WRITE_SECURE_SETTINGS` y `WRITE_SETTINGS` (para el protector de pantalla y las animaciones del
   sistema).
4. Lo pone como pantalla de inicio.

### Clave de firma

La versión release se firma con una clave propia: `launcher/release.jks`, con sus contraseñas en
`launcher/keystore.properties`. **Ninguno de los dos va en git.** Guárdalos en un lugar seguro: sin la
misma clave no se puede actualizar el launcher encima, hay que desinstalarlo y se pierden los ajustes.

Para crear una clave nueva:

```bash
keytool -genkeypair -keystore launcher/release.jks -alias tvhome -keyalg RSA -keysize 2048 -validity 10000
```

Y `launcher/keystore.properties`:

```properties
storeFile=release.jks
storePassword=TU_CONTRASEÑA
keyAlias=tvhome
keyPassword=TU_CONTRASEÑA
```

## Quitar el bloatware

[`scripts/limpiar-mitv.sh`](scripts/limpiar-mitv.sh) quita para el usuario 0
(`pm uninstall -k --user 0`) los paquetes de la lista: telemetría de Xiaomi y Google, filas de
anuncios y recomendaciones, apps preinstaladas, casting, textos legales, protector de pantalla de
Google, autoinstalación, actualizaciones de firmware, búsqueda por voz y los launchers de fábrica.
También pone las animaciones del sistema a 0.5 y deja este launcher como inicio. Antes de quitar los
launchers de fábrica comprueba que este esté instalado.

```bash
./scripts/limpiar-mitv.sh 192.168.1.198
```

Para recuperarlo todo:

```bash
./scripts/limpiar-mitv.sh 192.168.1.198 --restaurar
```

No hace falta root ni se borra nada de la partición del sistema. Un restablecimiento de fábrica también
devuelve todas las apps. La lista está pensada para la MiTV_MOOR6; revísala antes de usarla en otro
modelo.

> **Ojo:** la lista quita la búsqueda por voz (`com.google.android.katniss`) y las actualizaciones
> de firmware (`com.xiaomi.mitv.updateservice`). Quita esas líneas del script si las quieres conservar.

## Estructura del código

| Archivo | Qué hace |
|---|---|
| [`MainActivity.java`](launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java) | Pantalla de inicio: barra superior, brillo de color, fondo y atenuado, columnas, navegación y fondo desenfocado de los menús. |
| [`AppAdapter.java`](launcher/app/src/main/java/com/gathin/tvhome/AppAdapter.java) | Lista de apps: el dock (una app por columna), la cuadrícula con columnas variables, apps ocultas y orden propio. |
| [`AppMenu.java`](launcher/app/src/main/java/com/gathin/tvhome/AppMenu.java) | Desplegable al mantener pulsada una app. |
| [`SettingsDialog.java`](launcher/app/src/main/java/com/gathin/tvhome/SettingsDialog.java) | El modal de ajustes con barra lateral y sus 8 secciones. |
| [`Prefs.java`](launcher/app/src/main/java/com/gathin/tvhome/Prefs.java) | Ajustes guardados e idioma de la app. |
| [`SystemTweaks.java`](launcher/app/src/main/java/com/gathin/tvhome/SystemTweaks.java) | Protector de pantalla, animaciones del sistema y memoria. |
| [`ClockScene.java`](launcher/app/src/main/java/com/gathin/tvhome/ClockScene.java) | La escena del protector, compartida por [`ClockDream`](launcher/app/src/main/java/com/gathin/tvhome/ClockDream.java) (el del sistema) y [`ScreensaverActivity`](launcher/app/src/main/java/com/gathin/tvhome/ScreensaverActivity.java) (Probar ahora). |
| [`Blur.java`](launcher/app/src/main/java/com/gathin/tvhome/Blur.java) | Blur por software: captura desenfocada del inicio y copias suaves del fondo. |
| [`Wallpapers.java`](launcher/app/src/main/java/com/gathin/tvhome/Wallpapers.java) | Lista, carga y miniaturas de los fondos. |
| [`DockDecoration.java`](launcher/app/src/main/java/com/gathin/tvhome/DockDecoration.java) | Banda del dock: sólida o esmerilada con el fondo desenfocado. |
| [`FadeFrame.java`](launcher/app/src/main/java/com/gathin/tvhome/FadeFrame.java) | Desvanece la cuadrícula en su borde superior. |
| [`Motion.java`](launcher/app/src/main/java/com/gathin/tvhome/Motion.java) | Interruptor de las animaciones del launcher. |

Los textos están en `res/values/strings.xml` (inglés) y `res/values-es/strings.xml` (español).

## Notas

- **El blur es por software.** El blur nativo de Android (`RenderEffect`) necesita Android 12 y esta
  TV tiene Android 11. El launcher desenfoca copias pequeñas (6 a 12 veces más chicas) y las escala:
  el fondo del dock se calcula una vez por fondo de pantalla, y el del menú en cada pulsación larga.
  Con el blur apagado, el dock es sólido y el menú solo oscurece el fondo.
- **Memoria.** El fondo se carga en RGB_565 y a 1920 px. "Liberar memoria" pide a Android que cierre
  los procesos en segundo plano de las demás apps; no toca los procesos persistentes del sistema.
- El launcher sigue sin permiso de Internet.

## Créditos y licencia

Basado en [tv-vibehack](https://github.com/Gathin23/tv-vibehack) de Gathin. MIT, ver
[`LICENSE`](LICENSE). La tipografía Outfit usa la SIL Open Font License, ver
[`launcher/FONT-LICENSE-OFL.txt`](launcher/FONT-LICENSE-OFL.txt). Los fondos de pantalla incluidos
son de sus autores; revisa sus licencias antes de publicar el APK o el repositorio.
