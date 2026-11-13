package com.angelonmx.reloquence

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/** La frase que la pantalla de ajustes del sistema habla cuando
 *  alguien pulsa Escuchar. */
class GetSampleTextActivity : Activity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val result = Intent().apply {
			putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, getString(R.string.sample_text))
		}
		setResult(TextToSpeech.LANG_AVAILABLE, result)
		finish()
	}
}
