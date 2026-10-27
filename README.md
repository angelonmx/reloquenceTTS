# reloquenceTTS

Un motor de texto a voz para Android, pensado para leer en voz alta con la
voz que tú elijas y con el sonido que tú quieras. Es una app independiente:
no necesita cuenta, no muestra anuncios y no envía lo que lees a ningún sitio.

## Qué hace

- **Voces y ritmo.** Elige voz, idioma, velocidad, tono y volumen. La
  configuración se guarda por voz, así que cambiar de una a otra no obliga a
  volver a ajustarlo todo.
- **Lectura cuidada.** Pausas, abreviaturas, números, horas, decimales,
  direcciones y archivos se leen como se espera, no como una cadena de
  caracteres suelta. Detecta el idioma del texto y puede cambiar de voz.
- **Efectos de audio.** Ecualizador, reverberación, eco, coro, trémolo,
  compresión, puerta de ruido, filtros y una lista larga de ajustes de color.
  Todo se puede apagar, y apagado no hace nada.
- **Perfiles.** Guarda una configuración completa con nombre y aplícala cuando
  quieras. Los perfiles viajan en un archivo que puedes copiar a otro teléfono.
- **Diccionario.** Añade palabras y su pronunciación, importa listas y
  expórtalas. Las reglas se aplican antes de que el texto llegue al motor.
- **Historial.** Vuelve a escuchar lo último que se leyó, con los mismos
  ajustes con los que sonó.
- **Diagnóstico.** Un registro local, apagado de fábrica, para ver qué pasó
  cuando algo no suena. Nunca guarda el texto que se está leyendo.
- **Actualizaciones.** La app puede buscar versiones nuevas, avisarte y
  descargarlas desde Ajustes. Android siempre pide confirmación antes de
  instalar; no hay instalación silenciosa.

## Dos ediciones

| edición | versión | paquete | voces |
| --- | --- | --- | --- |
| **full** | 5.4 (5400) | `com.angelonmx.reloquence` | inglés (EE. UU. y Reino Unido), español (España y Latinoamérica), francés (Francia y Canadá), alemán, italiano y japonés |
| **lite** | 2.4 (2400) | `com.angelonmx.reloquence.lite` | español (España y Latinoamérica) |

Las dos se pueden instalar en el mismo teléfono: tienen paquetes distintos y
no comparten ajustes ni diccionario. La Lite es la misma app con menos voces,
pensada para quien solo necesita español y quiere un APK más pequeño.

La interfaz está traducida a español, inglés, francés, alemán, italiano,
portugués, japonés, polaco, ruso y turco. Que la interfaz esté en un idioma no
implica que haya una voz de ese idioma: las voces disponibles son las de la
tabla de arriba.

## Requisitos

- Android 6.0 o posterior.
- Un teléfono ARM (`arm64-v8a` en casi todos los modelos modernos;
  `armeabi-v7a` en equipos antiguos). También hay un APK universal.
- Para usar la app como motor del sistema, actívala en **Ajustes > Sistema >
  Idiomas e introducción de texto > Salida de texto a voz**.

## Instalación

1. Descarga el APK de la edición y la arquitectura que te correspondan desde
   [GitHub Releases](https://github.com/angelonmx/reloquenceTTS/releases).
2. Abre el archivo. Android pedirá permiso para instalar apps desde esa fuente
   la primera vez; concédelo y confirma la instalación.
3. Abre **reloquenceTTS**, elige la voz y los ajustes, y prueba con el botón de
   escuchar.
4. En los ajustes de texto a voz de Android, selecciona **reloquenceTTS** como
   motor. Si tienes las dos ediciones instaladas, aparecerán las dos.

Si actualizas desde una versión anterior, no desinstales primero: la app está
firmada con la misma clave y Android conserva los ajustes, el diccionario y los
perfiles.

## Actualizaciones

En **Ajustes > Actualizaciones** están, en este orden:

1. **Buscar actualizaciones** — comprueba ahora, sin esperar.
2. **Ver cambios de la versión actual** — el changelog viaja dentro de la app,
   así que funciona sin conexión.
3. **Versión actual** y **código de la versión** — lo que está instalado.
4. **Comprobar automáticamente** — un interruptor, con frecuencia de 2, 6, 12,
   24, 48 o 72 horas.

Cuando hay una versión nueva, la app muestra **Actualización disponible**, la
versión actual, la versión nueva y los botones **Ahora no** / **Actualizar
ahora**. La comprobación automática solo deja una notificación: no descarga ni
instala nada por su cuenta.

Antes de abrir el instalador, la app comprueba el tamaño, el SHA-256, el
paquete, el `versionCode` y el certificado de firma del APK. Si algo no
coincide, no se instala. Aun así, Android muestra su propia pantalla de
confirmación y tú decides.

La app lee los manifiestos de actualización de un archivo JSON estático. No
necesita token ni la API de GitHub, y no envía ningún dato tuyo. Si el
repositorio es privado, esa comprobación no puede funcionar; consulta
[PUBLICAR.md](PUBLICAR.md).

## Privacidad

- No hay cuentas, anuncios ni analítica.
- El texto que lees se procesa en el teléfono. No se sube a ningún servidor.
- La comprobación de actualizaciones descarga un JSON pequeño y, si aceptas,
  el APK. No incluye identificadores personales.
- El diagnóstico es local y está apagado de fábrica. Cuando se enciende,
  guarda sucesos del motor y del servicio, nunca el texto leído.
- Los diccionarios y los perfiles se guardan en el almacenamiento privado de la
  app. Exportarlos es una acción tuya.

## Compilar

El proyecto se compila con Gradle y el SDK de Android. Las instrucciones
completas, las dos ediciones, la firma y las pruebas están en
[COMPILACION.md](COMPILACION.md).

Para publicar una versión con GitHub Actions, incluidos los secretos de firma y
la generación de los manifiestos, está [PUBLICAR.md](PUBLICAR.md).

## Licencia y avisos

El código de la aplicación se distribuye bajo la licencia MIT de
[LICENSE](LICENSE). El motor y los datos de voz que lleva dentro tienen sus
propios avisos: léelos en [NOTICE](NOTICE) antes de redistribuir la app. En
particular, los datos de voz no están cubiertos por la MIT y no se pueden
relicenciar desde aquí.

Este es un proyecto independiente. No está afiliado ni respaldado por ningún
fabricante de motores de voz, tienda o plataforma.
