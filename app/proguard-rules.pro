# The JNI bridge finds these by name.
-keepclasseswithmembernames class com.angelonmx.reloquence.EvvNative {
	native <methods>;
}

# 5.2: cualquier otra clase que declare métodos nativos. La regla de
# arriba cubre EvvNative, que hoy es la única; esta queda por si algún
# día aparece un segundo puente, porque R8 renombrando un símbolo
# `Java_...` rompe la búsqueda en silencio: el método simplemente "no
# existe" en tiempo de ejecución, no en tiempo de compilación.
-keepclasseswithmembernames class * {
	native <methods>;
}

# 5.2: el framework y el sistema instancian estas por nombre, desde el
# manifiesto y desde los intents de TTS. AGP ya conserva los
# componentes que salen en el manifiesto fusionado, pero nombrarlas
# aquí deja el contrato escrito y sobrevive a un cambio de AGP o a un
# `tools:node="remove"` que deje de leerlo.
-keep class com.angelonmx.reloquence.EvvTtsService { <init>(...); }
-keep class com.angelonmx.reloquence.CheckVoiceDataActivity { <init>(...); }
-keep class com.angelonmx.reloquence.GetSampleTextActivity { <init>(...); }
-keep class com.angelonmx.reloquence.ImportDictionaryActivity { <init>(...); }
-keep class com.angelonmx.reloquence.SettingsActivity { <init>(...); }
-keep class com.angelonmx.reloquence.UpdateInstallReceiver { <init>(...); }
-keep class com.angelonmx.reloquence.UpdateWorker { <init>(...); }

# 5.2: los modelos de kotlinx.serialization se leen y se escriben por
# nombre. El plugin del compilador genera el serializador de cada
# `@Serializable`, pero el campo `$serializer` y el `Companion` tienen
# que sobrevivir al recorte y al renombrado. Sin esto, exportar o
# importar un perfil de voz funciona en depuración y falla en release.
-keep,includedescriptorclasses class com.angelonmx.reloquence.**$$serializer { *; }
-keepclassmembers class com.angelonmx.reloquence.** {
	*** Companion;
}
-keepclasseswithmembers class com.angelonmx.reloquence.** {
	kotlinx.serialization.KSerializer serializer(...);
}
