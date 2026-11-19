# SonicField

![Logo de SonicField](docs/sonicfield-logo.svg)

Reproductor Android local con ecualización PCM en tiempo real y una interfaz de audio oscura. SonicField procesa su propio reproductor antes de enviar audio al sistema Android.

## Funciones disponibles

- Reproduce archivos de audio locales seleccionados por el usuario.
- Detecta en caliente salidas Bluetooth, cableadas, USB e integradas mediante `AudioDeviceCallback`; muestra el tipo y el nombre de producto que Android exponga. A2DP se muestra como salida Bluetooth porque el tipo de ruta no siempre permite distinguir de forma fiable entre auriculares y altavoz.
- La variante `store` puede mostrar título, artista, álbum, app de origen y estado de sesiones multimedia activas tras conceder el acceso especial de Android. El APK descargable de GitHub usa la variante `sideload`, que no declara ese servicio sensible y por ello evita el bloqueo automático de Play Protect en instalaciones directas; en ella la tarjeta muestra la reproducción propia de SonicField.
- Incluye diez curvas de ecualización de preferencia y controles de ganancia con preatenuación calculada para compensar el mayor realce.
- Decodifica el audio seleccionado con `MediaCodec`, procesa las muestras en un DSP propio y las envía por `AudioTrack`. El EQ son diez filtros peaking biquad en dB; Megabajos añade un low-shelf de +6 dB a 88 Hz; la preatenuación calcula margen a partir de la respuesta combinada y el limitador interno mantiene picos bajo −1 dBFS. Los controles se actualizan en los siguientes bloques PCM durante la reproducción.
- El efecto espacial es el `Virtualizer` opcional de Android y se informa cuando el dispositivo no permite controlarlo. No se depende de `Equalizer` o `BassBoost` del fabricante para las funciones de EQ y graves.
- Ofrece una actividad de escucha subjetiva con tonos de bajo nivel para ajustar la curva personal. No es una prueba clínica ni diagnostica pérdida auditiva.
- Guarda ajustes localmente y ofrece navegación Inicio, EQ, Efectos y Perfil.

## Límites importantes

Esta aplicación **no ecualiza el audio de Spotify, YouTube, juegos ni otras aplicaciones**. Los efectos insertables de Android se conectan a una sesión que controla esta app. La captura de audio de otras aplicaciones exige consentimiento mediante `MediaProjection` y que la aplicación de origen permita la captura; no equivale a un efecto global del sistema.

La detección de canciones entre aplicaciones consulta metadatos de `MediaSession` mediante un servicio de acceso a notificaciones. No usa el micrófono ni reconoce canciones por su huella sonora; si una app no publica título/estado, no habrá datos que mostrar. [Google documenta que Play Protect bloquea ciertas instalaciones desde navegador/descarga directa cuando declaran ese acceso sensible](https://developers.google.com/android/play-protect/warning-dev-guidance?hl=es-419). Por eso el APK de GitHub omite el servicio; la función queda solo en la variante `store`, que debe distribuirse por un canal compatible con sus requisitos de revisión y políticas.

La ruta DSP procesa solo archivos reproducidos dentro de SonicField: Android no ofrece a apps normales acceso de ecualización global a Spotify, YouTube ni juegos. La decodificación depende de que el dispositivo incluya un `MediaCodec` compatible con el formato del archivo. El low-shelf y el EQ se aplican antes de la salida Android en mono/estéreo PCM; el Q del EQ está fijado a 1,15. El limitador de muestras evita clipping digital a −1 dBFS, pero no es un limitador true-peak ni sustituye la protección acústica del auricular.

Las curvas incluidas son puntos de partida subjetivos acotados, no mediciones ni correcciones acústicas de un modelo de auricular. Identificar un nombre desde Android no garantiza modelo exacto. No se incluye todavía una base contrastada de mediciones por modelo, análisis visual de espectro, importación/exportación de presets, prueba automatizada en dispositivos físicos ni capturas de ejecución real. El modo espacial usa `Virtualizer` del fabricante, que puede no simular 360° en todos los equipos. La personalización auditiva no tiene finalidad médica.

vivo documenta DeepField como una experiencia de efectos y personalización dependiente de sus dispositivos y auriculares. No hay una API pública Android portátil que permita a terceros invocar esos algoritmos. SonicField presenta efectos propios basados en las APIs públicas de Android; no copia la marca ni afirma reproducir el DSP de vivo.

## Base técnica

- [Android `MediaCodec`](https://developer.android.com/reference/android/media/MediaCodec), [`MediaExtractor`](https://developer.android.com/reference/android/media/MediaExtractor) y [`AudioTrack`](https://developer.android.com/reference/android/media/AudioTrack)
- [Efectos opcionales de Android `AudioEffect`](https://developer.android.com/reference/android/media/audiofx/AudioEffect), [`Equalizer`](https://developer.android.com/reference/android/media/audiofx/Equalizer) y [`DynamicsProcessing`](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing)
- [Rutas y callbacks de `AudioManager`](https://developer.android.com/reference/android/media/AudioManager), [identificación de salidas `AudioDeviceInfo`](https://developer.android.com/reference/android/media/AudioDeviceInfo)
- [Sesiones multimedia activas y acceso requerido](https://developer.android.com/reference/android/media/session/MediaSessionManager), [servicio de acceso a notificaciones](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- [Restricciones de Audio Playback Capture](https://developer.android.com/media/platform/av-capture)
- Las curvas medidas de [AutoEq](https://github.com/jaakkopasanen/AutoEq) muestran por qué una compensación por modelo requiere datos medidos; no se distribuyen aquí perfiles no verificados.
- Referencias de vivo: [soporte DeepField](https://www.vivo.com/PT/support/questionList?categoryId=54720), [manual de producto (PDF)](https://eu-exstatic-vivofs.vivo.com/8Xa6evfY85lu15Pb/1793155431755/f0df45ec8ef697f4756ff67df9f9b878.pdf).

## Compilar

- Android Studio, JDK 17 y Android SDK Platform 35.
- Gradle 8.11.1; `minSdk 26`, `targetSdk 35`.
- Ejecutar `gradle :app:testSideloadDebugUnitTest :app:assembleSideloadDebug :app:assembleStoreDebug`.
- Las pruebas de `PcmDspTest` generan tonos y miden RMS tras los filtros para validar cambios de EQ, Megabajos, bypass, preamplificación, limitador y presets.
- APK de instalación directa: `app/build/outputs/apk/sideload/debug/app-sideload-debug.apk`.
- La variante `store` se compila con `gradle :app:assembleStoreDebug` y mantiene la lectura opcional de sesiones multimedia.

GitHub Actions ejecuta pruebas unitarias, compila la variante descargable y publica el APK corregido en GitHub Releases. Es una compilación de depuración firmada con la clave de depuración de Android; las actualizaciones instaladas manualmente deben conservar esa misma firma. No subir claves privadas al repositorio.

## Licencia

MIT para SonicField. Las dependencias conservan sus licencias respectivas.
