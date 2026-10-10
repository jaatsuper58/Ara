package com.ara.feature.chat.ui

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Hold-to-record voice notes (§7.3). Records AAC into app cache; the file is
 * encrypted + chunked on send like any other attachment.
 */
class VoiceNoteRecorder(
    private val context: Context,
    private val outputDir: File,
) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val currentFile: File? get() = outputFile

    fun start(): Boolean = runCatching {
        val file = File(outputDir.apply { mkdirs() }, "voice_${System.currentTimeMillis()}.m4a")
            .also { outputFile = it }
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(64_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        true
    }.getOrDefault(false)

    /** Returns the recorded file, or null if too short / failed. */
    fun stop(): File? = runCatching {
        recorder?.stop()
        outputFile?.takeIf { it.exists() && it.length() > 512 }
    }.getOrNull().also {
        recorder?.release()
        recorder = null
        if (it == null) outputFile?.delete()
    }

    fun cancel(): File? {
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        outputFile?.delete()
        outputFile = null
        return null
    }
}
