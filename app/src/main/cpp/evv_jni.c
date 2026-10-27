/* The bridge between the ECI engine and Android's synthesis callback.
 *
 * The engine hands samples over on a thread of its own and blocks in the
 * callback until the caller has taken them, so the shape here is a ring buffer
 * with a lock either side of it: the engine's thread fills it and Android's
 * synthesis thread drains it. That blocking is the point rather than a cost.
 * It paces the engine to whatever the audio track is taking, so a long
 * utterance does not have to be held in memory before any of it is heard.
 *
 * No JNI call is ever made from the engine's thread, which is why there is no
 * thread to attach and none to detach.
 */

#include <jni.h>
#include <pthread.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <android/log.h>

#include "eci.h"

#define TAG "evvdroid"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* What the engine writes at a time, in samples, and what the ring holds, in
   bytes. The ring has to be bigger than one frame or the callback would wait
   for room that can never arrive. */
#define FRAME_SAMPLES 1024
#define RING_BYTES    (128 * 1024)

/* How many milliseconds the end-of-utterance wait will spend watching for the
   engine to take the work up before it stops waiting for that. */
#define START_SPINS 200

/* How long a reader waits for samples before deciding the engine has wedged.
   Frames are 1024 samples, so 128 ms even at 8 kHz: ten seconds of nothing is
   not a slow utterance, it is a stuck engine, and a reader that waits for it
   for ever is what the system reports as a stopped text-to-speech engine. */
#define READ_TIMEOUT_SEC 10

/* How long speak waits for the previous utterance's worker to leave the engine
   before it stops waiting politely and aborts it. Short, because the polite
   wait is only ever going to end by itself when the previous utterance really
   did finish, and that has already happened by the time speak is called; if it
   has not, the worker is stuck and waiting longer only adds silence. */
#define IDLE_GRACE_SEC 2

/* What read answers when the engine gave up waiting for samples. Distinct from
   the -1 of a stop, because the caller has to do something about it: throw the
   instance away rather than speak through it again. */
#define WEDGED (-2)

typedef struct {
	ECIHand         eci;
	pthread_mutex_t lock;
	pthread_cond_t  room;
	pthread_cond_t  filled;
	unsigned char*  ring;
	size_t          head;
	size_t          tail;
	size_t          count;
	int             aborted;
	int             started;
	int             done;
	pthread_t       worker;
	pthread_cond_t  work;
	int             running;
	int             pending;
	int             busy;
	int             quitting;
	/* 5.0: a timed-out read is not the same as a normal stop. The
	 * worker may still be inside the engine; remembering this keeps
	 * speak from reusing the instance and keeps destroy from freeing
	 * memory under a thread that has not returned. */
	int             wedged;
	int             exited;
	pthread_cond_t  exit_cond;
	/* How many callers are inside read right now. 4.2: a service
	 * that is being torn down while the synthesis thread is waiting
	 * for samples used to free the ring out from under it, and the
	 * process went down with it. abort() wakes the readers and
	 * destroy() waits for this to go back to zero before it frees
	 * anything. Kotlin counts its own readers too, for the window
	 * between deciding to call read and being inside it. */
	int             readers;
	unsigned char*  text;
	ECIDictHand     dict;
	short           frame[FRAME_SAMPLES];
} instance;

static instance* unwrap(jlong handle) {
	return (instance*)(intptr_t)handle;
}

static void put(instance* in, const unsigned char* src, size_t n) {
	size_t first = RING_BYTES - in->head;
	if (first > n)
		first = n;
	memcpy(in->ring + in->head, src, first);
	memcpy(in->ring, src + first, n - first);
	in->head = (in->head + n) % RING_BYTES;
	in->count += n;
}

static void take(instance* in, unsigned char* dst, size_t n) {
	size_t first = RING_BYTES - in->tail;
	if (first > n)
		first = n;
	memcpy(dst, in->ring + in->tail, first);
	memcpy(dst + first, in->ring, n - first);
	in->tail = (in->tail + n) % RING_BYTES;
	in->count -= n;
}

/* What says an utterance is over, which took three goes to get right.
 *
 * eciSpeaking cannot be it. It answers whether anything is outstanding right
 * now, and a reader that drains the ring faster than the engine fills it sees
 * nought between two of the engine's own messages and calls an utterance
 * finished part way through.
 *
 * eciSynchronize alone cannot be it either. eciSynthesize only queues the work
 * and answers; asked before the synthesis thread has taken the utterance up,
 * eciSynchronize finds nothing outstanding and answers at once, and then the
 * utterance is over before it began. That is not a rare window -- it cost a
 * whole utterance about one time in four.
 *
 * So the wait is in two parts: first until the engine has visibly taken the
 * work, then eciSynchronize, which is exact once there is something for it to
 * wait on. Having taken it shows either as a buffer already delivered or as
 * eciSpeaking answering yes. Text that yields no audio at all shows as
 * neither, which is what the spin limit is for; it is the only case that waits
 * it out, and it waits a fifth of a second rather than for ever.
 *
 * It has to be its own thread because the thread that reads cannot block.
 *
 * It is one thread for the life of the instance rather than one per utterance,
 * and that is not tidiness. The engine has no thread of its own: asking it
 * anything is what makes it run, so whichever thread calls eciSynchronize is
 * the thread the engine runs on, and it took the callback with it. Handing it
 * a thread made fresh each time faults after about fifty-five utterances, on
 * the phone and in a plain C harness alike, with the machine jumping into its
 * own data. The same loop on one kept thread runs for hundreds. */
static void* worker_loop(void* data) {
	instance* in = (instance*)data;
	struct timespec tick = { 0, 1000L * 1000L };
	for (;;) {
		int spins;
		pthread_mutex_lock(&in->lock);
		while (!in->pending && !in->quitting)
			pthread_cond_wait(&in->work, &in->lock);
		if (in->quitting) {
			in->exited = 1;
			pthread_cond_broadcast(&in->exit_cond);
			pthread_mutex_unlock(&in->lock);
			return NULL;
		}
		in->pending = 0;
		pthread_mutex_unlock(&in->lock);
		for (spins = 0; spins < START_SPINS; spins++) {
			int begun;
			pthread_mutex_lock(&in->lock);
			begun = in->started || in->aborted;
			pthread_mutex_unlock(&in->lock);
			if (begun || eciSpeaking(in->eci))
				break;
			nanosleep(&tick, NULL);
		}
		eciSynchronize(in->eci);
		pthread_mutex_lock(&in->lock);
		in->done = 1;
		in->busy = 0;
		pthread_cond_broadcast(&in->filled);
		pthread_cond_broadcast(&in->work);
		pthread_mutex_unlock(&in->lock);
	}
}

/* Marks an utterance over that never started, so that a reader waiting on the
   end condition is not waiting for work nothing handed over. */
static jboolean finished(instance* in) {
	pthread_mutex_lock(&in->lock);
	in->done = 1;
	in->busy = 0;
	pthread_cond_broadcast(&in->filled);
	pthread_cond_broadcast(&in->work);
	pthread_mutex_unlock(&in->lock);
	return JNI_FALSE;
}

/* Waits for the worker to be out of the engine, which is what makes it safe to
   put the next utterance in. The lock must not be held.
 *
 * This used to wait with no deadline at all, which turns one wedged engine into
 * a service that never speaks again: the synthesis thread of the framework is
 * the only thread that drives an utterance, so a wait here with no end is a
 * wait forever, and the symptom is silence rather than a crash, which is why it
 * is so hard to trace back.
 *
 * A stuck worker is inside the engine's own callback with the ring full, and
 * the only thing that unparks it is the abort flag. So the first deadline is
 * not "give up", it is "stop waiting politely and go and stop it". Only if
 * that does not work either is the answer no, and the caller drops the
 * instance and opens another.
 *
 * Returns 1 when the engine is idle, 0 when it could not be got out of. */
static int wait_until_idle(instance* in) {
	struct timespec deadline;
	int gave_up = 0;
	pthread_mutex_lock(&in->lock);
	if (in->wedged) {
		pthread_mutex_unlock(&in->lock);
		return 0;
	}
	clock_gettime(CLOCK_REALTIME, &deadline);
	deadline.tv_sec += IDLE_GRACE_SEC;
	while (in->busy) {
		if (pthread_cond_timedwait(&in->work, &in->lock, &deadline) == ETIMEDOUT)
			break;
	}
	if (in->busy) {
		/* Polite wait is over. Ask the engine to stop, which is what
		 * releases a thread parked in on_message, and give it a
		 * moment to unwind. */
		in->aborted = 1;
		pthread_cond_broadcast(&in->room);
		pthread_cond_broadcast(&in->filled);
		pthread_cond_broadcast(&in->work);
		clock_gettime(CLOCK_REALTIME, &deadline);
		deadline.tv_sec += IDLE_GRACE_SEC;
		while (in->busy) {
			if (pthread_cond_timedwait(&in->work, &in->lock, &deadline) == ETIMEDOUT) {
				gave_up = 1;
				break;
			}
		}
	}
	pthread_mutex_unlock(&in->lock);
	if (gave_up) {
		pthread_mutex_lock(&in->lock);
		in->wedged = 1;
		pthread_mutex_unlock(&in->lock);
		LOGE("el hilo del motor no salio del motor tras %d s dos veces",
			IDLE_GRACE_SEC);
	}
	return !gave_up;
}

/* Runs on the engine's synthesis thread. It may not call back into the same
   instance, and it does not: everything it touches is ours. */
static int ECICALL on_message(ECIHand handle, ECIMessage message, int param, void* data) {
	instance* in = (instance*)data;
	size_t want;
	(void)handle;
	if (message != eciWaveformBuffer)
		return eciDataProcessed;
	want = (size_t)param * sizeof(short);
	if (want == 0)
		return eciDataProcessed;
	if (want > RING_BYTES) {
		/* The ring can never fit this frame, so waiting for room
		 * would wait forever while holding the engine thread. */
		LOGE("frame of %zu bytes does not fit the ring", want);
		pthread_mutex_lock(&in->lock);
		in->aborted = 1;
		in->wedged = 1;
		pthread_cond_broadcast(&in->filled);
		pthread_cond_broadcast(&in->work);
		pthread_mutex_unlock(&in->lock);
		return eciDataAbort;
	}
	pthread_mutex_lock(&in->lock);
	while (!in->aborted && !in->quitting && RING_BYTES - in->count < want)
		pthread_cond_wait(&in->room, &in->lock);
	if (in->aborted || in->quitting) {
		pthread_mutex_unlock(&in->lock);
		return eciDataAbort;
	}
	put(in, (const unsigned char*)in->frame, want);
	in->started = 1;
	pthread_cond_signal(&in->filled);
	pthread_mutex_unlock(&in->lock);
	return eciDataProcessed;
}

JNIEXPORT jlong JNICALL Java_com_angelonmx_reloquence_EvvNative_create(JNIEnv* env, jclass cls, jint language) {
	instance* in;
	(void)env;
	(void)cls;
	in = (instance*)calloc(1, sizeof *in);
	if (in == NULL)
		return 0;
	in->ring = (unsigned char*)malloc(RING_BYTES);
	if (in->ring == NULL) {
		free(in);
		return 0;
	}
	pthread_mutex_init(&in->lock, NULL);
	pthread_cond_init(&in->room, NULL);
	pthread_cond_init(&in->filled, NULL);
	pthread_cond_init(&in->work, NULL);
	pthread_cond_init(&in->exit_cond, NULL);
	in->eci = eciNewEx(language);
	if (in->eci == NULL_ECI_HAND) {
		LOGE("eciNewEx(%#x) gave no instance", language);
		pthread_cond_destroy(&in->exit_cond);
		pthread_cond_destroy(&in->work);
		pthread_cond_destroy(&in->filled);
		pthread_cond_destroy(&in->room);
		pthread_mutex_destroy(&in->lock);
		free(in->ring);
		free(in);
		return 0;
	}
	eciRegisterCallback(in->eci, on_message, in);
	if (!eciSetOutputBuffer(in->eci, FRAME_SAMPLES, in->frame)) {
		LOGE("the engine refused the output buffer");
		eciDelete(in->eci);
		pthread_cond_destroy(&in->exit_cond);
		pthread_cond_destroy(&in->work);
		pthread_cond_destroy(&in->filled);
		pthread_cond_destroy(&in->room);
		pthread_mutex_destroy(&in->lock);
		free(in->ring);
		free(in);
		return 0;
	}
	if (pthread_create(&in->worker, NULL, worker_loop, in) != 0) {
		LOGE("no thread to run the engine on");
		eciDelete(in->eci);
		pthread_cond_destroy(&in->exit_cond);
		pthread_cond_destroy(&in->work);
		pthread_cond_destroy(&in->filled);
		pthread_cond_destroy(&in->room);
		pthread_mutex_destroy(&in->lock);
		free(in->ring);
		free(in);
		return 0;
	}
	in->running = 1;
	return (jlong)(intptr_t)in;
}

/* Wakes everybody waiting on this instance and nothing else: the engine
 * thread, and a reader parked in read. No memory is released here, so
 * it is safe to call while somebody is still inside.
 *
 * 4.2: this is what lets Kotlin tell the engine to stop before it
 * waits for its readers to leave. Freeing the ring while the synthesis
 * thread was parked in pthread_cond_wait on it is what took the
 * process down when the TTS framework unbound a service that was in
 * the middle of an utterance, which the system then reports as a
 * stopped engine with an offer to clear the cache. */
JNIEXPORT void JNICALL Java_com_angelonmx_reloquence_EvvNative_abort(JNIEnv* env, jclass cls, jlong handle) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	if (in == NULL)
		return;
	pthread_mutex_lock(&in->lock);
	in->aborted = 1;
	in->quitting = 1;
	pthread_cond_broadcast(&in->room);
	pthread_cond_broadcast(&in->filled);
	pthread_cond_broadcast(&in->work);
	pthread_mutex_unlock(&in->lock);
	/* 5.0: abort only wakes. Kotlin already counts its readers and
	 * destroy waits for them; blocking here too doubled the wait on
	 * the main thread that calls close(). */
}

JNIEXPORT void JNICALL Java_com_angelonmx_reloquence_EvvNative_destroy(JNIEnv* env, jclass cls, jlong handle) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	if (in == NULL)
		return;
	struct timespec deadline;
	int readers_left;
	int exited;
	pthread_mutex_lock(&in->lock);
	in->aborted = 1;
	in->quitting = 1;
	pthread_cond_broadcast(&in->room);
	pthread_cond_broadcast(&in->filled);
	pthread_cond_broadcast(&in->work);
	clock_gettime(CLOCK_REALTIME, &deadline);
	deadline.tv_sec += IDLE_GRACE_SEC;
	while (in->readers > 0) {
		if (pthread_cond_timedwait(&in->filled, &in->lock, &deadline) == ETIMEDOUT)
			break;
	}
	readers_left = in->readers;
	exited = in->exited || !in->running;
	if (!exited && in->running) {
		clock_gettime(CLOCK_REALTIME, &deadline);
		deadline.tv_sec += IDLE_GRACE_SEC;
		while (!in->exited) {
			if (pthread_cond_timedwait(&in->exit_cond, &in->lock, &deadline) == ETIMEDOUT)
				break;
		}
		exited = in->exited;
	}
	pthread_mutex_unlock(&in->lock);
	/* 5.0: a worker that is still inside the engine, or a reader that
	 * has not left, means this instance is not ours to free. Leaking a
	 * few kilobytes on a teardown is better than pulling the ring out
	 * from under the engine thread. The handle is gone from Kotlin's
	 * side, so nothing will touch it again. */
	if (readers_left > 0 || !exited) {
		LOGE("destroy: leaking instance (%d readers, worker %s)",
			readers_left, exited ? "gone" : "still inside");
		return;
	}
	if (in->running) {
		pthread_join(in->worker, NULL);
		in->running = 0;
	}
	if (in->dict != NULL_DICT_HAND)
		eciDeleteDict(in->eci, in->dict);
	eciDelete(in->eci);
	pthread_cond_destroy(&in->exit_cond);
	pthread_cond_destroy(&in->room);
	pthread_cond_destroy(&in->filled);
	pthread_cond_destroy(&in->work);
	pthread_mutex_destroy(&in->lock);
	free(in->text);
	free(in->ring);
	free(in);
}

/* Hands the text over and starts the engine. It answers at once; the samples
   come out of read below.
 *
 * The text is bytes in the language's own code set rather than UTF-16, which
 * the engine allows only for Chinese, Japanese and Korean; Kotlin has already
 * turned it into that set. It is kept until the next utterance because nothing
 * here knows whether the engine copied it. */
JNIEXPORT jboolean JNICALL Java_com_angelonmx_reloquence_EvvNative_speak(JNIEnv* env, jclass cls, jlong handle, jbyteArray text) {
	instance*      in = unwrap(handle);
	jsize          n;
	unsigned char* buf;
	(void)cls;
	if (in == NULL || text == NULL)
		return JNI_FALSE;
	pthread_mutex_lock(&in->lock);
	if (in->wedged || in->quitting || !in->running) {
		pthread_mutex_unlock(&in->lock);
		return JNI_FALSE;
	}
	pthread_mutex_unlock(&in->lock);
	n = (*env)->GetArrayLength(env, text);
	buf = (unsigned char*)malloc((size_t)n + 1);
	if (buf == NULL)
		return JNI_FALSE;
	(*env)->GetByteArrayRegion(env, text, 0, n, (jbyte*)buf);
	buf[n] = 0;
	/* If the previous worker could not be got out of the engine even after
	 * aborting it, this instance is not going to work again. Saying no is
	 * what makes the service drop it and open another, which is the
	 * difference between one lost utterance and a service that never
	 * speaks again. */
	if (!wait_until_idle(in)) {
		free(buf);
		return JNI_FALSE;
	}
	pthread_mutex_lock(&in->lock);
	if (in->wedged || in->quitting) {
		pthread_mutex_unlock(&in->lock);
		free(buf);
		return JNI_FALSE;
	}
	in->aborted = 0;
	in->started = 0;
	in->done = 0;
	in->wedged = 0;
	in->busy = 1;
	in->head = in->tail = in->count = 0;
	pthread_mutex_unlock(&in->lock);
	free(in->text);
	in->text = buf;
	if (!in->running) {
		LOGE("there is no worker to run the engine");
		return finished(in);
	}
	if (!eciAddText(in->eci, buf)) {
		LOGE("eciAddText refused the text");
		return finished(in);
	}
	if (!eciSynthesize(in->eci)) {
		LOGE("eciSynthesize refused");
		return finished(in);
	}
	pthread_mutex_lock(&in->lock);
	in->pending = 1;
	pthread_cond_broadcast(&in->work);
	pthread_mutex_unlock(&in->lock);
	return JNI_TRUE;
}

/* Bytes out, blocking until there are some. Answers 0 at the end of the
   utterance and -1 when it was stopped or the instance is going away.
   4.0: sin malloc por chunk. El servicio pide 1-8k, así que un
   buffer de pila basta y ahorra un alloc+free por lectura (miles
   por minuto en TalkBack). Solo lo grande va a malloc.
 *
 * 4.2, dos cosas más:
 *
 * - La espera tiene plazo. El motor entrega marcos de 1024 muestras,
 *   que a 8 kHz son 128 ms, así que diez segundos de silencio ya no
 *   son una emisión lenta: son un motor que se quedó parado. Antes
 *   esta espera no tenía fin, así que un motor colgado dejaba al
 *   hilo de síntesis esperando para siempre y el sistema acababa
 *   dando el motor por parado. Al vencer el plazo se contesta 0, que
 *   es "se acabó", y la emisión se cierra sola.
 *
 * - Entra y sale con la cuenta de lectores. destroy() no suelta el
 *   búfer hasta que no queda nadie dentro, que es el caso de un
 *   servicio que se desmonta a mitad de una frase.
 */
JNIEXPORT jint JNICALL Java_com_angelonmx_reloquence_EvvNative_read(JNIEnv* env, jclass cls, jlong handle, jbyteArray dst) {
	instance*      in = unwrap(handle);
	size_t         room;
	size_t         got = 0;
	unsigned char  stack[8192];
	unsigned char* scratch = stack;
	int            heap = 0;
	int            answer;
	int            wedged = 0;
	(void)cls;
	if (in == NULL || dst == NULL)
		return -1;
	room = (size_t)(*env)->GetArrayLength(env, dst);
	if (room == 0)
		return -1;
	pthread_mutex_lock(&in->lock);
	if (in->quitting) {
		pthread_mutex_unlock(&in->lock);
		return -1;
	}
	if (in->wedged) {
		pthread_mutex_unlock(&in->lock);
		return WEDGED;
	}
	in->readers++;
	while (in->count == 0 && !in->done && !in->aborted) {
		struct timespec deadline;
		clock_gettime(CLOCK_REALTIME, &deadline);
		deadline.tv_sec += READ_TIMEOUT_SEC;
		if (pthread_cond_timedwait(&in->filled, &in->lock, &deadline) == ETIMEDOUT) {
			/* Ni audio, ni fin, ni parada: el motor se quedó parado.
			 * 5.0: no se toca busy ni done. El worker puede seguir
			 * dentro del motor; marcarlo libre aquí era lo que
			 * permitía cerrar y liberar mientras aún escribía. Se
			 * contesta WEDGED, el servicio suelta la instancia y
			 * destroy() espera con plazo antes de liberar. */
			in->wedged = 1;
			in->aborted = 1;
			pthread_cond_broadcast(&in->room);
			pthread_cond_broadcast(&in->filled);
			pthread_cond_broadcast(&in->work);
			wedged = 1;
			break;
		}
	}
	if (wedged) {
		answer = WEDGED;
	} else if (in->aborted || in->quitting) {
		answer = -1;
	} else if (in->count == 0) {
		answer = 0;
	} else {
		got = in->count < room ? in->count : room;
		if (got > sizeof(stack)) {
			scratch = (unsigned char*)malloc(got);
			if (scratch == NULL) {
				answer = -1;
				got = 0;
			} else {
				heap = 1;
				take(in, scratch, got);
				pthread_cond_signal(&in->room);
				answer = (jint)got;
			}
		} else {
			take(in, scratch, got);
			pthread_cond_signal(&in->room);
			answer = (jint)got;
		}
	}
	if (--in->readers == 0 && in->quitting)
		pthread_cond_broadcast(&in->filled);
	pthread_mutex_unlock(&in->lock);
	/* Fuera del candado: ya no se toca in, solo el buffer propio. */
	if (answer > 0)
		(*env)->SetByteArrayRegion(env, dst, 0, (jsize)answer, (const jbyte*)scratch);
	if (heap) free(scratch);
	return answer;
}

/* Wakes the callback before asking the engine to stop, since the callback may
   be waiting for room that a stopped reader will never make. */
JNIEXPORT void JNICALL Java_com_angelonmx_reloquence_EvvNative_stop(JNIEnv* env, jclass cls, jlong handle) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	if (in == NULL)
		return;
	pthread_mutex_lock(&in->lock);
	in->aborted = 1;
	in->head = in->tail = in->count = 0;
	pthread_cond_broadcast(&in->room);
	pthread_cond_broadcast(&in->filled);
	pthread_mutex_unlock(&in->lock);
	/* eciStop is deliberately not called, here or anywhere else on this path.
	   The flag makes the callback answer eciDataAbort the next time the engine
	   offers samples, and that unwinds it from inside its own call and leaves
	   it fit for the next utterance. eciStop does not: about eighty of them on
	   one instance and the engine either goes silent or jumps into its own
	   data. openevv's own harnesses show both halves of that -- the one that
	   aborts from the callback runs clean for hundreds of turns, the one that
	   calls eciStop dies around turn eighty. */
}

/* The instance's own dictionary set, made the first time one is wanted. An
   instance has one set in force at a time and the three volumes live in it. */
static int ensure_dict(instance* in) {
	if (in->dict != NULL_DICT_HAND)
		return 1;
	in->dict = eciNewDict(in->eci);
	if (in->dict == NULL_DICT_HAND) {
		LOGE("the engine would not make a dictionary set");
		return 0;
	}
	/* An error code rather than a flag, so nought is success. */
	if (eciSetDict(in->eci, in->dict) != 0) {
		LOGE("the engine would not take the dictionary set");
		return 0;
	}
	return 1;
}

/* Hands a file to the engine's own dictionary loader. Answers one of the
   eciDict codes, so nought is success. */
JNIEXPORT jint JNICALL Java_com_angelonmx_reloquence_EvvNative_loadDictionary(JNIEnv* env, jclass cls, jlong handle, jint volume, jstring path) {
	instance*   in = unwrap(handle);
	const char* name;
	jint        answer;
	(void)cls;
	if (in == NULL || path == NULL || !ensure_dict(in))
		return -1;
	name = (*env)->GetStringUTFChars(env, path, NULL);
	if (name == NULL)
		return -1;
	answer = eciLoadDict(in->eci, in->dict, volume, name);
	(*env)->ReleaseStringUTFChars(env, path, name);
	return answer;
}

/* Teaches one word, which is the other way in and the one that does not depend
   on the engine agreeing about a file format. */
JNIEXPORT jint JNICALL Java_com_angelonmx_reloquence_EvvNative_teachWord(JNIEnv* env, jclass cls, jlong handle, jint volume, jbyteArray key, jbyteArray say) {
	instance*      in = unwrap(handle);
	jsize          keyLen;
	jsize          sayLen;
	unsigned char* pair;
	jint           answer;
	(void)cls;
	if (in == NULL || key == NULL || say == NULL || !ensure_dict(in))
		return -1;
	keyLen = (*env)->GetArrayLength(env, key);
	sayLen = (*env)->GetArrayLength(env, say);
	pair = (unsigned char*)malloc((size_t)keyLen + (size_t)sayLen + 2);
	if (pair == NULL)
		return -1;
	(*env)->GetByteArrayRegion(env, key, 0, keyLen, (jbyte*)pair);
	pair[keyLen] = 0;
	(*env)->GetByteArrayRegion(env, say, 0, sayLen, (jbyte*)(pair + keyLen + 1));
	pair[keyLen + 1 + sayLen] = 0;
	answer = eciUpdateDict(in->eci, in->dict, volume, pair, pair + keyLen + 1);
	free(pair);
	return answer;
}

/* What a key was taught, or nothing. This is how a test says a dictionary
   really went in rather than that a call answered nought. */
JNIEXPORT jstring JNICALL Java_com_angelonmx_reloquence_EvvNative_lookUpWord(JNIEnv* env, jclass cls, jlong handle, jint volume, jbyteArray key) {
	instance*      in = unwrap(handle);
	jsize          keyLen;
	unsigned char* want;
	const char*    found;
	jstring        answer;
	(void)cls;
	if (in == NULL || key == NULL || in->dict == NULL_DICT_HAND)
		return NULL;
	keyLen = (*env)->GetArrayLength(env, key);
	want = (unsigned char*)malloc((size_t)keyLen + 1);
	if (want == NULL)
		return NULL;
	(*env)->GetByteArrayRegion(env, key, 0, keyLen, (jbyte*)want);
	want[keyLen] = 0;
	found = eciDictLookup(in->eci, in->dict, volume, want);
	answer = found == NULL ? NULL : (*env)->NewStringUTF(env, found);
	free(want);
	return answer;
}

/* Puts the set aside and takes it away, so the engine is back to the language's
   own dictionary. */
JNIEXPORT void JNICALL Java_com_angelonmx_reloquence_EvvNative_forgetDictionaries(JNIEnv* env, jclass cls, jlong handle) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	if (in == NULL || in->dict == NULL_DICT_HAND)
		return;
	eciSetDict(in->eci, NULL_DICT_HAND);
	eciDeleteDict(in->eci, in->dict);
	in->dict = NULL_DICT_HAND;
}

JNIEXPORT jint JNICALL Java_com_angelonmx_reloquence_EvvNative_setParam(JNIEnv* env, jclass cls, jlong handle, jint param, jint value) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	return in == NULL ? -1 : eciSetParam(in->eci, param, value);
}

JNIEXPORT jint JNICALL Java_com_angelonmx_reloquence_EvvNative_getParam(JNIEnv* env, jclass cls, jlong handle, jint param) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	return in == NULL ? -1 : eciGetParam(in->eci, param);
}

JNIEXPORT jint JNICALL Java_com_angelonmx_reloquence_EvvNative_setVoiceParam(JNIEnv* env, jclass cls, jlong handle, jint voice, jint param, jint value) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	return in == NULL ? -1 : eciSetVoiceParam(in->eci, voice, param, value);
}

JNIEXPORT jint JNICALL Java_com_angelonmx_reloquence_EvvNative_getVoiceParam(JNIEnv* env, jclass cls, jlong handle, jint voice, jint param) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	return in == NULL ? -1 : eciGetVoiceParam(in->eci, voice, param);
}

JNIEXPORT jint JNICALL Java_com_angelonmx_reloquence_EvvNative_copyVoice(JNIEnv* env, jclass cls, jlong handle, jint from, jint to) {
	instance* in = unwrap(handle);
	(void)env;
	(void)cls;
	return in == NULL ? -1 : eciCopyVoice(in->eci, from, to);
}

JNIEXPORT jintArray JNICALL Java_com_angelonmx_reloquence_EvvNative_languages(JNIEnv* env, jclass cls) {
	unsigned int found[32];
	int          n = 0;
	jintArray    out;
	(void)cls;
	if (eciGetAvailableLanguages(NULL, &n) != 0 || n < 1)
		return (*env)->NewIntArray(env, 0);
	if (n > 32)
		n = 32;
	if (eciGetAvailableLanguages(found, &n) != 0)
		return (*env)->NewIntArray(env, 0);
	out = (*env)->NewIntArray(env, n);
	if (out == NULL)
		return NULL;
	(*env)->SetIntArrayRegion(env, out, 0, n, (const jint*)found);
	return out;
}

JNIEXPORT jstring JNICALL Java_com_angelonmx_reloquence_EvvNative_version(JNIEnv* env, jclass cls) {
	char buffer[ECI_VERSION_LENGTH];
	(void)cls;
	memset(buffer, 0, sizeof buffer);
	eciVersion(buffer);
	buffer[sizeof buffer - 1] = 0;
	return (*env)->NewStringUTF(env, buffer);
}
