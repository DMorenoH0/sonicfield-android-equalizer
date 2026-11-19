# SonicField

![Logo de SonicField](docs/sonicfield-logo.svg)

Reproductor Android local con ecualización por sesión y una interfaz de audio oscura. El procesamiento usa efectos públicos de Android aplicados al audio reproducido por SonicField.

## Funciones disponibles

- Reproduce archivos de audio locales seleccionados por el usuario.
- Detecta en caliente salidas Bluetooth, cableadas, USB e integradas mediante `AudioDeviceCallback`; muestra el tipo y el nombre de producto que Android exponga. A2DP se muestra como salida Bluetooth porque el tipo de ruta no siempre permite distinguir de forma fiable entre auriculares y altavoz.
- La variante `store` puede mostrar título, artista, álbum, app de origen y estado de sesiones multimedia activas tras conceder el acceso especial de Android. El APK descargable de GitHub usa la variante `sideload`, que no declara ese servicio sensible y por ello evita el bloqueo automático de Play Protect en instalaciones directas; en ella la tarjeta muestra la reproducción propia de SonicField.
- Incluye diez curvas de ecualización de preferencia y controles de ganancia con preatenuación calculada para compensar el mayor realce.
- Usa `Equalizer`, `BassBoost`, `Virtualizer` y, en Android compatible, `DynamicsProcessing` en la sesión propia. Cada efecto se consulta/activa según el soporte reportado por la implementación del dispositivo.
- Ofrece una actividad de escucha subjetiva con tonos de bajo nivel para ajustar la curva personal. No es una prueba clínica ni diagnostica pérdida auditiva.
- Guarda ajustes localmente y ofrece navegación Inicio, EQ, Efectos y Perfil.

## Límites importantes

Esta aplicación **no ecualiza el audio de Spotify, YouTube, juegos ni otras aplicaciones**. Los efectos insertables de Android se conectan a una sesión que controla esta app. La captura de audio de otras aplicaciones exige consentimiento mediante `MediaProjection` y que la aplicación de origen permita la captura; no equivale a un efecto global del sistema.

La detección de canciones entre aplicaciones consulta metadatos de `MediaSession` mediante un servicio de acceso a notificaciones. No usa el micrófono ni reconoce canciones por su huella sonora; si una app no publica título/estado, no habrá datos que mostrar. [Google documenta que Play Protect bloquea ciertas instalaciones desde navegador/descarga directa cuando declaran ese acceso sensible](https://developers.google.com/android/play-protect/warning-dev-guidance?hl=es-419). Por eso el APK de GitHub omite el servicio; la función queda solo en la variante `store`, que debe distribuirse por un canal compatible con sus requisitos de revisión y políticas.

Android y los fabricantes pueden limitar el número de bandas, las frecuencias, la disponibilidad y el comportamiento de los efectos. La API nativa no ofrece de forma uniforme Q configurable ni una etapa de preamplificación independiente. La app muestra diez controles de usuario que se asignan a las bandas nativas disponibles; algunos pueden compartir banda. El limitador dinámico, donde se admite, limita muestras y no garantiza protección true-peak.

Las curvas incluidas son puntos de partida subjetivos acotados, no mediciones ni correcciones acústicas de un modelo de auricular. Identificar un nombre desde Android no garantiza modelo exacto. No se incluye todavía una base contrastada de mediciones por modelo, análisis visual de espectro, importación/exportación de presets, prueba automatizada en dispositivos físicos ni capturas de ejecución real. El modo espacial usa `Virtualizer` del fabricante, que puede no simular 360° en todos los equipos. La personalización auditiva no tiene finalidad médica.

vivo documenta DeepField como una experiencia de efectos y personalización dependiente de sus dispositivos y auriculares. No hay una API pública Android portátil que permita a terceros invocar esos algoritmos. SonicField presenta efectos propios basados en las APIs públicas de Android; no copia la marca ni afirma reproducir el DSP de vivo.

## Base técnica

- [Android `AudioEffect`](https://developer.android.com/reference/android/media/audiofx/AudioEffect), [`Equalizer`](https://developer.android.com/reference/android/media/audiofx/Equalizer), [`DynamicsProcessing`](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing)
- [Rutas y callbacks de `AudioManager`](https://developer.android.com/reference/android/media/AudioManager), [identificación de salidas `AudioDeviceInfo`](https://developer.android.com/reference/android/media/AudioDeviceInfo)
- [Sesiones multimedia activas y acceso requerido](https://developer.android.com/reference/android/media/session/MediaSessionManager), [servicio de acceso a notificaciones](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- [Restricciones de Audio Playback Capture](https://developer.android.com/media/platform/av-capture)
- Las curvas medidas de [AutoEq](https://github.com/jaakkopasanen/AutoEq) muestran por qué una compensación por modelo requiere datos medidos; no se distribuyen aquí perfiles no verificados.
- Referencias de vivo: [soporte DeepField](https://www.vivo.com/PT/support/questionList?categoryId=54720), [manual de producto (PDF)](https://eu-exstatic-vivofs.vivo.com/8Xa6evfY85lu15Pb/1793155431755/f0df45ec8ef697f4756ff67df9f9b878.pdf).

## Compilar

- Android Studio, JDK 17 y Android SDK Platform 35.
- Gradle 8.11.1; `minSdk 26`, `targetSdk 35`.
- Ejecutar `gradle :app:testSideloadDebugUnitTest :app:assembleSideloadDebug :app:assembleStoreDebug`.
- APK de instalación directa: `app/build/outputs/apk/sideload/debug/app-sideload-debug.apk`.
- La variante `store` se compila con `gradle :app:assembleStoreDebug` y mantiene la lectura opcional de sesiones multimedia.

GitHub Actions ejecuta pruebas unitarias, compila la variante descargable y publica el APK corregido en GitHub Releases. Es una compilación de depuración firmada con la clave de depuración de Android; las actualizaciones instaladas manualmente deben conservar esa misma firma. No subir claves privadas al repositorio.

## Licencia

MIT para SonicField. Las dependencias conservan sus licencias respectivas.
