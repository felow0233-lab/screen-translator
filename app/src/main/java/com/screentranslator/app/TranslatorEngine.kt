package com.screentranslator.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.concurrent.thread

/** Блок текста на экране + его перевод. rect — координаты в пикселях экрана. */
data class TranslatedBlock(val rect: Rect, val text: String)

/**
 * Движок: OCR (ML Kit, офлайн) + перевод.
 * Офлайн — ML Kit (модель en-ru качается при первом запуске, ~30 МБ).
 * Онлайн — Google Cloud Translation v2 через OkHttp, при сбое откатывается на офлайн.
 */
class TranslatorEngine(private val context: Context) {

    enum class Mode { OFFLINE, ONLINE }

    var mode = Mode.OFFLINE
    var apiKey = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.RUSSIAN)
            .build()
    )
    private val http = OkHttpClient()

    /** Скачивает офлайн-модель перевода (в офлайн-режиме). Вызывается перед стартом цикла. */
    fun prepare(onReady: (Boolean) -> Unit) {
        if (mode == Mode.ONLINE) {
            onReady(true)
            return
        }
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener { onReady(true) }
            .addOnFailureListener { e ->
                Log.e(TAG, "Model download failed", e)
                onReady(false)
            }
    }

    /** Распознаёт текст на кадре и переводит его. Результат приходит в onResult (главный поток). */
    fun process(bitmap: Bitmap, onResult: (List<TranslatedBlock>) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val source = visionText.textBlocks
                    .filter { it.text.isNotBlank() && it.boundingBox != null }
                    .map { TranslatedBlock(Rect(it.boundingBox!!), it.text.trim()) }
                if (source.isEmpty()) {
                    onResult(emptyList())
                } else {
                    translateBlocks(source, onResult)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                onResult(emptyList())
            }
    }

    private fun translateBlocks(blocks: List<TranslatedBlock>, onResult: (List<TranslatedBlock>) -> Unit) {
        if (mode == Mode.ONLINE && apiKey.isNotBlank()) {
            translateCloud(blocks, onResult)
        } else {
            translateOffline(blocks, onResult)
        }
    }

    private fun translateOffline(blocks: List<TranslatedBlock>, onResult: (List<TranslatedBlock>) -> Unit) {
        val results = arrayOfNulls<String>(blocks.size)
        var remaining = blocks.size
        blocks.forEachIndexed { i, block ->
            translator.translate(block.text)
                .addOnSuccessListener { results[i] = it; if (--remaining == 0) emit(blocks, results, onResult) }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Offline translate failed for block $i", e)
                    results[i] = block.text
                    if (--remaining == 0) emit(blocks, results, onResult)
                }
        }
    }

    private fun emit(
        blocks: List<TranslatedBlock>,
        results: Array<String?>,
        onResult: (List<TranslatedBlock>) -> Unit
    ) {
        onResult(blocks.mapIndexed { i, b -> b.copy(text = results[i] ?: b.text) })
    }

    private fun translateCloud(blocks: List<TranslatedBlock>, onResult: (List<TranslatedBlock>) -> Unit) {
        thread {
            try {
                val url = "https://translation.googleapis.com/language/translate/v2"
                    .toHttpUrl().newBuilder()
                    .addQueryParameter("key", apiKey)
                    .build()
                val form = FormBody.Builder()
                    .add("source", "en")
                    .add("target", "ru")
                    .add("format", "text")
                    .apply { blocks.forEach { add("q", it.text) } }
                    .build()
                val request = Request.Builder().url(url).post(form).build()
                http.newCall(request).execute().use { resp ->
                    val body = resp.body?.string()
                    if (!resp.isSuccessful || body == null) throw java.io.IOException("HTTP ${resp.code}")
                    val translations = JSONObject(body)
                        .getJSONObject("data")
                        .getJSONArray("translations")
                    val texts = (0 until translations.length()).map {
                        translations.getJSONObject(it).getString("translatedText")
                    }
                    mainHandler.post {
                        onResult(blocks.mapIndexed { i, b -> b.copy(text = texts.getOrElse(i) { b.text }) })
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cloud translate failed, fallback to offline", e)
                mainHandler.post { translateOffline(blocks, onResult) }
            }
        }
    }

    fun close() {
        translator.close()
        recognizer.close()
    }

    private companion object {
        const val TAG = "TranslatorEngine"
    }
}
