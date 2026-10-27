package com.angelonmx.reloquence

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import java.util.Locale
import java.util.concurrent.TimeUnit
import timber.log.Timber

/**
 * Detección del idioma de una emisión, híbrida de verdad:
 *
 * 1. ML Kit Language ID (on-device, sin internet tras la primera
 *    descarga del modelo). En teléfonos con Google es lo más
 *    preciso, sobre todo en frases y párrafos.
 * 2. Heurística propia (palabras frecuentes + marcas como ñ/ß/ç):
 *    entra cuando no hay Google (Huawei, ROMs degoogladas, relojes
 *    chinos), cuando ML Kit duda o cuando el modelo aún se está
 *    descargando. Cero dependencias y microsegundos por emisión.
 *
 * ML Kit va primero con un presupuesto corto; si tarda, falla o no
 * hay GMS, decide la heurística. Si ninguna está segura, contesta
 * nada y quien llama usa el idioma habitual (ajuste o pedido).
 *
 * Límites honestos: una etiqueta de 1-2 palabras ("OK", "Atrás")
 * es ambigua para cualquier detector; ahí siempre gana el idioma
 * por defecto. Donde esto brilla es en frases y párrafos.
 */
object AutoLang {

	/** Presupuesto por emisión para ML Kit. El modelo es local y
	 *  suele contestar en decenas de ms; más de esto es descarga
	 *  en curso o aparato lento, y no se espera. 5.2.1: bajó de
	 *  200 ms a 80 ms. La detección va antes de la primera palabra
	 *  y cada milisegundo de espera se oye como silencio. */
	private const val MLKIT_TIMEOUT_MS = 80L

	/** Tras dos tiempos seguidos se descansa un minuto antes de
	 *  reintentar, en vez de frenar cada emisión o morir del todo. */
	private const val COOLDOWN_MS = 60_000L

	/** Muestra para detectar: la cabeza del texto. El idioma no
	 *  cambia a mitad de una emisión del lector. */
	private const val SAMPLE_CHARS = 800

	/** ISO-639-1 a ISO-639-2: el detector habla "es" y el motor
	 *  "spa". Sin esta tabla nada casa y nada cambia nunca. */
	private val TO_ISO3 = mapOf(
		"en" to "eng", "es" to "spa", "fr" to "fra", "de" to "deu", "it" to "ita"
	)

	private val TO_ISO1 = mapOf(
		"eng" to "en", "spa" to "es", "fra" to "fr", "deu" to "de", "ita" to "it"
	)

	/**
	 * 4.3: el cliente de ML Kit, uno solo para todo el proceso.
	 *
	 * ML Kit pide que se reusen, y crear uno por emisión era trabajo
	 * en el hilo de síntesis para nada: el modelo ya estaba
	 * descargado de la primera vez. Se crea con `lazy`, que ya es
	 * seguro entre hilos, y si falla se queda en nada y la
	 * heurística manda para siempre.
	 */
	private val client: com.google.mlkit.nl.languageid.LanguageIdentifier? by lazy {
		// 4.4: sin ML Kit no se llega a pedir el cliente, pero el
		// `lazy` se construye en cuanto se lee el campo, así que la
		// guarda va aquí también. Sin esto, una edición sin la
		// dependencia tocaría esta línea y la excepción sería un
		// `NoClassDefFoundError`.
		if (!BuildConfig.MLKIT) return@lazy null
		runCatching {
			LanguageIdentification.getClient(
				com.google.mlkit.nl.languageid.LanguageIdentificationOptions.Builder()
					.setConfidenceThreshold(0.34f)
					.build()
			)
		}.getOrNull()
	}

	/** Cualquier etiqueta a ISO-3 ("en" o "eng" → "eng"). */
	fun iso3(tag: String): String {
		val t = tag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
		if (t.length == 3) return t
		return TO_ISO3[t] ?: runCatching { Locale.forLanguageTag(t).isO3Language }.getOrNull()
			?.takeIf { it.isNotEmpty() } ?: t
	}

	/** ISO-3 a ISO-1 para mostrar ("spa" → "es"). */
	fun iso1(tag: String): String {
		val t = iso3(tag)
		return TO_ISO1[t] ?: t
	}

	@Volatile
	private var mlkitDead = false

	@Volatile
	private var mlkitTimeouts = 0

	@Volatile
	private var mlkitCooldownUntil = 0L

	/** Detecta el idioma de [text] en ISO-3 ("eng", "spa"...,
	 *  como lo nombra el motor) o nada si no hay seguridad.
	 *  4.0: textos con menos de 4 letras (letras sueltas del
	 *  teclado, "OK", "de") no se detectan nunca: esas se leen
	 *  con el idioma en vigor en vez de saltar de voz en voz. */
	fun detect(text: String): String? {
		val sample = text.take(SAMPLE_CHARS)
		if (sample.isBlank()) return null
		if (sample.count { it.isLetter() } < 4) return null
		mlkitDetect(sample)?.let { return it }
		return heuristic(sample)
	}

	private fun mlkitDetect(sample: String): String? {
		// 4.4: en la edición Lite esto es código muerto y R8 lo quita
		// entero, junto con la biblioteca nativa y el modelo, que el
		// empaquetador excluye aparte. La rama está detrás de una
		// constante `final` justamente para eso: si fuera un
		// `if (!isLite)` con una función, R8 no podría borrarla y el
		// modelo se quedaría en el APK sin nada que lo use.
		//
		// El interruptor de autodetección y la heurística siguen
		// como están; esto solo decide quién responde primero.
		if (!BuildConfig.MLKIT) return null
		if (mlkitDead) return null
		// Enfriamiento tras rachas lentas: no se frena cada emisión
		// mientras el modelo se descarga, pero se reintenta solo.
		if (System.currentTimeMillis() < mlkitCooldownUntil) return null
		return try {
			// Umbral 0.34 (el de los ejemplos de Google) en vez del
			// 0.5 de fábrica: las etiquetas cortas casi nunca llegan
			// a 0.5 y sin esto ML Kit calla justo donde más falta hace.
			// 4.3: el cliente se guarda y se reúsa. ML Kit pide
			// justo eso, y crear uno por emisión era trabajo en el
			// hilo de síntesis para nada: el modelo ya estaba
			// descargado de la primera vez.
			val client = client ?: return null
			val code = Tasks.await(client.identifyLanguage(sample), MLKIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
			mlkitTimeouts = 0
			if (code.isNullOrEmpty() || code == "und") null
			else iso3(code)
		} catch (timeout: java.util.concurrent.TimeoutException) {
			// Descarga en curso o aparato lento: dos seguidas y se
			// descansa un minuto en vez de morir la sesión entera.
			if (++mlkitTimeouts >= 2) {
				mlkitCooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
				mlkitTimeouts = 0
			}
			null
		} catch (e: Throwable) {
			// 4.4: `Throwable` y no `Exception`. Lo que falla aquí
			// cuando falta la biblioteca nativa es un
			// `UnsatisfiedLinkError`, que es un `Error`, y con
			// `Exception` se habría llevado por delante el hilo de
			// síntesis. Sin GMS, sin modelo o sin biblioteca, la
			// heurística manda para siempre. GMS no aparece a mitad
			// de sesión, así que esto sí es final.
			Timber.d("ML Kit no disponible, solo heurística: ${e.message}")
			mlkitDead = true
			null
		}
	}

	// ---- heurística propia -------------------------------------------

	private val EN = ("the of and to in is you that it he was for on are as with his they at be this " +
		"have from or one had by but not what all were we when your can said there use each which " +
		"their will how about out many then them these some her would make like into time has look " +
		"more write see number could people than first been call who now find long did get come made " +
		"may part over new sound take only little work know place year live back give most very after " +
		"thing just name good where help through much before line right mean old same does three want " +
		"also play small home read hand large must high follow why ask change went light need house " +
		"again world near own should country answer still learn between state keep never last city tree " +
		"start night life open next white always music often letter until book carry took hear watch " +
		"color enough young ready above feel talk bird soon body dog family leave song door black short " +
		"question happen area order fire south problem piece since whole space heard best hour better " +
		"during five remember step early hold fast sing listen table travel morning simple several hello " +
		"thanks please yes back next settings open close delete save cancel search send play stop continue " +
		"share edit create done call type look listen previous end help exit enter download update accept " +
		"button buttons menu window item selected checked unchecked enabled disabled visible hidden available " +
		"loading error wait goodbye screen phone message camera photo gallery music video clock alarm calendar " +
		"contacts dial keypad speaker brightness wifi bluetooth airplane battery charger notification notifications " +
		"permission permissions allow deny retry refresh install uninstall folder file image text name password " +
		"email address number time date today tomorrow morning night week month talkback android google").split(" ").toSet()

	private val ES = ("el la los las de en que un una por con se su para como más pero sus me hasta " +
		"desde está son entre cuando todo esta ser hay tiene donde cada cual fueron han sido " +
		"están este esta estos estas eso aquí ahora muy hay sin sobre también tras durante " +
		"contra ante bajo cabe sino aunque pues porque sino mientras tanto poco mucho " +
		"gracias hola favor atrás ajustes abrir cerrar borrar guardar buscar volumen velocidad tono " +
		"voz idioma palabra palabras número hora fecha batería pantalla atrás inicio " +
		"uno dos tres cuatro cinco seis siete ocho nueve diez cien mil año día noche " +
		"casa agua mano día mundo vida hombre mujer niño gente tiempo mano noche día " +
		"buenos días tardes noches señor señora quiero puede estoy tiene hace dice " +
		"hablar leer escribir oír ver hacer decir tener estar ser ir dar saber querer " +
		"llegar pasar quedar poner salir venir volver nuevo nuevo buena bueno gran " +
		"primero primero mismo mismo otro otra cada cual quien donde como cuando " +
		"porque sino aunque pues entonces luego después antes ahora hoy ayer mañana " +
		"siempre nunca todo nada algo alguien nadie mucho poco muy tan más menos " +
		"enviar cancelar aceptar reproducir detener continuar compartir eliminar editar crear listo " +
		"llamar escribir mirar escuchar siguiente anterior fin ayuda salir entrar descargar actualizar " +
		"botón botones menú ventana lista elemento seleccionado marcado desmarcado activado desactivado " +
		"visible oculto disponible cargando error espera adiós pantalla teléfono mensaje cámara foto galería " +
		"música video reloj alarma calendario contactos marcar teclado altavoz brillo wifi bluetooth avión " +
		"batería cargador notificación notificaciones permiso permisos permitir denegar reintentar actualizar " +
		"instalar desinstalar carpeta archivo imagen texto nombre contraseña correo dirección número hora " +
		"fecha hoy mañana mañana noche semana mes atrás talkback").split(" ").toSet()

	private val FR = ("le la les de des du un une et est en que qui pour dans pas plus par sur " +
		"avec ce ces cette son sa ses leur leurs mon ma mes ton ta tes notre votre " +
		"nous vous ils elles il elle on au aux été être avoir fait comme tout toute " +
		"tous toutes mais ou donc car ainsi alors aussi très bien bon bonne grand " +
		"petit merci bonjour soir madame monsieur arrière réglages ouvrir fermer " +
		"supprimer enregistrer chercher volume vitesse voix langue mot mots numéro " +
		"heure date écran accueil un deux trois quatre cinq six sept huit neuf dix " +
		"cent mille jour nuit maison eau main monde vie homme femme enfant gens " +
		"temps bonjour merci oui non arrière avant nouveau nouvelle premier même " +
		"autre chaque qui où comment quand pourquoi parce donc alors après avant " +
		"aujourd hui hier demain toujours jamais tout rien quelque quelqu un " +
		"beaucoup peu très plus moins parler lire écrire entendre voir faire dire " +
		"avoir être aller donner savoir vouloir arriver passer rester mettre sortir " +
		"envoyer annuler accepter lire arrêter continuer partager supprimer modifier créer terminé " +
		"appeler taper regarder écouter suivant précédent fin aide quitter entrer télécharger " +
		"bouton boutons menu fenêtre liste élément sélectionné coché décoché activé désactivé " +
		"visible caché disponible chargement erreur attente au revoir écran téléphone message caméra " +
		"photo galerie musique vidéo horloge alarme calendrier contacts composer clavier haut-parleur " +
		"luminosité wifi bluetooth avion batterie chargeur notification notifications autorisation " +
		"autorisations autoriser refuser réessayer actualiser installer désinstaller dossier fichier " +
		"image texte nom mot de passe email adresse numéro heure date aujourd hui demain matin nuit " +
		"semaine mois arrière talkback").split(" ").toSet()

	private val DE = ("der die das und ist in den von zu mit dem sich des auf für ist im " +
		"nicht ein eine als auch es an werden aus er hat dass sie nach wird bei " +
		"einer einem einen einer um haben nur oder aber vor zur schon wenn dann " +
		"mehr keine seine ihre ihre alle seine wurde wird sein heißt gibt soll " +
		"will muss kann darf möchte danke hallo bitte zurück einstellungen öffnen " +
		"schließen löschen speichern suchen lautstärke geschwindigkeit stimme " +
		"sprache wort wörter nummer uhr datum bildschirm start eins zwei drei vier " +
		"fünf sechs sieben acht neun zehn hundert tausend tag nacht haus wasser " +
		"hand welt leben mann frau kind leute zeit danke ja nein zurück vor neu " +
		"neue erste gleiche andere jede wer wo wie wann warum weil also dann nach " +
		"vor heute gestern morgen immer nie alles nichts etwas jemand niemand viel " +
		"wenig sehr mehr weniger sprechen lesen schreiben hören sehen machen sagen " +
		"haben sein gehen geben wissen wollen kommen gehen stellen bleiben legen " +
		"senden abbrechen akzeptieren abspielen stoppen fortsetzen teilen löschen bearbeiten erstellen fertig " +
		"anrufen tippen ansehen hören weiter zurück ende hilfe beenden eingeben herunterladen aktualisieren " +
		"taste tasten menü fenster liste eintrag ausgewählt markiert abgewählt aktiviert deaktiviert " +
		"sichtbar versteckt verfügbar laden fehler warte tschüss bildschirm telefon nachricht kamera " +
		"foto galerie musik video uhr wecker kalender kontakte wählen tastatur lautsprecher helligkeit " +
		"wifi bluetooth flugzeug akku ladegerät benachrichtigung benachrichtigungen berechtigung " +
		"berechtigungen erlauben verweigern erneut aktualisieren installieren deinstallieren ordner datei " +
		"bild text name passwort email adresse nummer uhrzeit datum heute morgen nacht woche monat zurück talkback").split(" ").toSet()

	private val IT = ("il lo la gli che di del della dei degli al allo alla ai agli dal " +
		"dallo dalla dai dagli nel nello nella nei negli sul sullo sulla sui " +
		"sugli un uno una e è in con per tra fra non si come più poco molto " +
		"sono sei siamo siete ho hai ha abbiamo avete hanno sta stiamo questo " +
		"questa questi queste quello quella quelli quelle qui qua dove quando " +
		"perché poiché mentre anche ancora sempre mai tutto niente qualcosa " +
		"qualcuno nessuno tanto troppo grazie ciao favore indietro impostazioni " +
		"apri chiudi elimina salva cerca volume velocità voce lingua parola " +
		"parole numero ora data schermo inizio uno due tre quattro cinque sei " +
		"sette otto nove dieci cento mille giorno notte casa acqua mano mondo " +
		"vita uomo donna bambino gente tempo buongiorno grazie sì indietro " +
		"avanti nuovo nuova primo stessa altro altra ogni chi dove come quando " +
		"perché poiché allora dopo prima ora oggi ieri domani parlare leggere " +
		"scrivere sentire vedere fare dire avere essere andare dare sapere " +
		"volere arrivare passare restare mettere uscire tornare nuovo buona " +
		"invia annulla accetta riproduci ferma continua condividi elimina modifica crea fatto " +
		"chiama digita guarda ascolta avanti indietro fine aiuto esci entra scarica aggiorna " +
		"pulsante pulsanti menu finestra elenco elemento selezionato spuntato deselezionato attivato disattivato " +
		"visibile nascosto disponibile caricamento errore attesa ciao schermo telefono messaggio fotocamera " +
		"foto galleria musica video orologio sveglia calendario contatti comporre tastiera altoparlante " +
		"luminosità wifi bluetooth aereo batteria caricabatterie notifica notifiche permesso permessi " +
		"consenti nega riprova aggiorna installa disinstalla cartella file immagine testo nome password " +
		"email indirizzo numero ora data oggi domani mattina notte settimana mese indietro talkback").split(" ").toSet()

	private val SETS = mapOf("en" to EN, "es" to ES, "fr" to FR, "de" to DE, "it" to IT)

	private val TOKEN = Regex("[\\p{L}]+")

	/** El motor para la etiqueta [tag] ("spa" o "es", da igual):
	 *  la variante preferida ([preferred], de los ajustes de
	 *  autodetección) si vale; si no, la que pida el llamante; si
	 *  no, la del aparato; si no, la primera. Para el español sin
	 *  país reconocible manda el módulo latinoamericano (`es-419`),
	 *  que es el que cubre todo lo que no es España. Nada si el
	 *  motor no trae ese idioma. */
	fun preferredEngine(tag: String, wantCountry: String?, preferred: Int): Int? {
		val want3 = iso3(tag)
		if (preferred != 0 && EvvEngine.available.contains(preferred) &&
			Eci.localeOf(preferred)?.first.equals(want3, ignoreCase = true)
		) return preferred
		val matches = ArrayList<Int>()
		for (candidate in EvvEngine.available) {
			val loc = Eci.localeOf(candidate) ?: continue
			if (loc.first.equals(want3, ignoreCase = true)) matches.add(candidate)
		}
		if (matches.isEmpty()) return null
		if (matches.size == 1) return matches.first()
		val want = wantCountry?.takeIf { it.isNotEmpty() }?.let {
			runCatching { java.util.Locale.forLanguageTag("und-$it").isO3Country }.getOrNull()
				?.takeIf { code -> code.isNotEmpty() } ?: it
		}
		want?.let { w ->
			matches.firstOrNull { Eci.localeOf(it)?.second.equals(w, ignoreCase = true) }?.let { return it }
		}
		val device = runCatching { Locale.getDefault().country }.getOrNull()
			?.takeIf { it.isNotEmpty() }?.let {
				runCatching { java.util.Locale.forLanguageTag("und-$it").isO3Country }.getOrNull()
					?.takeIf { code -> code.isNotEmpty() } ?: it
			}
		device?.let { d ->
			matches.firstOrNull { Eci.localeOf(it)?.second.equals(d, ignoreCase = true) }?.let { return it }
		}
		// 5.2: el español que no es de España cae en `es-419`. Antes
		// el primero de la lista era `es-ES`, así que un teléfono
		// cuyo país no casa con ningún módulo oía castellano aunque
		// el texto fuera latinoamericano.
		if (want3.equals("spa", ignoreCase = true) && matches.contains(Eci.SPANISH_LATAM)) {
			return Eci.SPANISH_LATAM
		}
		return matches.first()
	}

	/** Heurística por palabras frecuentes + marcas. Contesta la
	 *  etiqueta o nada. Regla: la ganadora necesita 2+ aciertos y
	 *  ventaja sobre la segunda; con un solo acierto vale si es
	 *  una palabra larga en un texto corto (etiquetas como
	 *  "Settings" o "Ajustes"); las marcas (ñ, ß, ç, ¿¡)
	 *  desempatan. */
	fun heuristic(text: String): String? {
		val lower = text.lowercase(Locale.ROOT)
		val tokens = TOKEN.findAll(lower).map { it.value }.toList()
		if (tokens.isEmpty()) return null
		val scores = LinkedHashMap<String, Int>()
		for ((tag, words) in SETS) {
			var n = 0
			for (t in tokens) if (t in words) n++
			scores[tag] = n
		}
		if ('ß' in lower) scores["de"] = (scores["de"] ?: 0) + 4
		if ('ñ' in lower || '¿' in lower || '¡' in lower) scores["es"] = (scores["es"] ?: 0) + 4
		if ('ä' in lower || 'ö' in lower) scores["de"] = (scores["de"] ?: 0) + 3
		if ('ç' in lower) scores["fr"] = (scores["fr"] ?: 0) + 2
		var best = 0
		var bestTag: String? = null
		var second = 0
		for ((tag, n) in scores) {
			if (n > best) {
				second = best
				best = n
				bestTag = tag
			} else if (n > second) {
				second = n
			}
		}
		if (bestTag == null || best - second < 1) return null
		// Dentro se habla ISO-1 ("en"); fuera, ISO-3 ("eng").
		if (best >= 2) return iso3(bestTag)
		// Un solo acierto: solo en texto corto y palabra larga,
		// para no adivinar con "la" o "ok".
		if (tokens.size <= 3) {
			val words = SETS[bestTag] ?: return null
			if (tokens.any { it.length >= 4 && it in words }) return iso3(bestTag)
		}
		return null
	}
}
