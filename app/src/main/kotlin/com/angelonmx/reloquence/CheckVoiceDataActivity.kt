package com.angelonmx.reloquence

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * Lo que Android pide antes de usar el motor. Todo está en el APK,
 * así que cada idioma que compila ya está ahí y ninguno falta.
 */
class CheckVoiceDataActivity : Activity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val available = ArrayList<String>()
		for (language in EvvEngine.available) {
			// 5.0: BCP-47 y no ISO-3. Android construye un Locale con
			// esto y lo compara con lo que piden los lectores; con
			// "spa-MEX" o "jpn-JPN" no casaba nada y el motor aparecía
			// sin idiomas en los selectores de voz.
			for (tag in Eci.tagsOf(language)) {
				if (tag !in available) available.add(tag)
			}
		}
		val result = Intent().apply {
			putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
			putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, ArrayList())
		}
		val code = if (available.isEmpty()) {
			TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL
		} else {
			TextToSpeech.Engine.CHECK_VOICE_DATA_PASS
		}
		setResult(code, result)
		finish()
	}
}
