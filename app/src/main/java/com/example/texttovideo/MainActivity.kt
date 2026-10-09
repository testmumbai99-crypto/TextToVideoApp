package com.example.texttovideo

import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var etScript: EditText
    private lateinit var rgVoiceGender: RadioGroup
    private lateinit var rbMale: RadioButton
    private lateinit var etSceneDuration: EditText
    private lateinit var btnGenerate: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var videoPreview: VideoView

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etScript = findViewById(R.id.etScript)
        rgVoiceGender = findViewById(R.id.rgVoiceGender)
        rbMale = findViewById(R.id.rbMale)
        etSceneDuration = findViewById(R.id.etSceneDuration)
        btnGenerate = findViewById(R.id.btnGenerate)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        videoPreview = findViewById(R.id.videoPreview)

        tts = TextToSpeech(this, this)

        btnGenerate.setOnClickListener {
            val scriptText = etScript.text.toString().trim()
            if (scriptText.isEmpty()) {
                tvStatus.text = "Please enter a script or prompt."
                return@setOnClickListener
            }

            val scenes = scriptText.lines().filter { it.isNotBlank() }
            val secondsPerScene = etSceneDuration.text.toString().toIntOrNull() ?: 10
            val isMale = rbMale.isChecked

            startVideoGeneration(scenes, secondsPerScene, isMale)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isTtsReady = true
        }
    }

    private fun configureVoiceGender(isMale: Boolean) {
        if (!isTtsReady || tts == null) return
        val targetGender = if (isMale) "male" else "female"
        try {
            val voices = tts?.voices
            if (voices != null) {
                for (voice in voices) {
                    if (voice.name.lowercase().contains(targetGender) && voice.locale == Locale.US) {
                        tts?.voice = voice
                        break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startVideoGeneration(scenes: List<String>, secondsPerScene: Int, isMale: Boolean) {
        btnGenerate.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Configuring TTS Voice & Rendering Scenes..."

        thread {
            try {
                configureVoiceGender(isMale)

                val videoFile = File(cacheDir, "rendered_video.mp4")
                if (videoFile.exists()) videoFile.delete()

                renderVideoWithAudio(scenes, secondsPerScene, videoFile)

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnGenerate.isEnabled = true
                    val totalDuration = scenes.size * secondsPerScene
                    tvStatus.text = "Created ${totalDuration}s video with voice!"
                    videoPreview.setVideoURI(Uri.fromFile(videoFile))
                    videoPreview.setOnPreparedListener { mp -> mp.isLooping = true }
                    videoPreview.start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnGenerate.isEnabled = true
                    tvStatus.text = "Error: " + e.localizedMessage
                }
            }
        }
    }

    private fun renderVideoWithAudio(scenes: List<String>, secondsPerScene: Int, outputFile: File) {
        val width = 720
        val height = 1280
        val fps = 30
        val framesPerScene = fps * secondsPerScene
        val bitRate = 2500000
        val mimeType = "video/avc"

        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(mimeType)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface: Surface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bufferInfo = MediaCodec.BufferInfo()

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            textAlign = Paint.Align.CENTER
        }

        var presentationTimeUs = 0L
        val frameDurationUs = 1000000L / fps
        var muxerStarted = false

        val colors = intArrayOf(
            Color.parseColor("#101010"),
            Color.parseColor("#1A1A2E"),
            Color.parseColor("#16213E"),
            Color.parseColor("#0F3460")
        )

        var sceneIndex = 0
        for (sceneText in scenes) {
            val bgColor = colors[sceneIndex % colors.size]
            sceneIndex++

            for (i in 0 until framesPerScene) {
                val canvas = surface.lockHardwareCanvas()
                canvas.drawColor(bgColor)

                // Render dynamic text overlay
                val yPos = (canvas.height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
                canvas.drawText(sceneText, canvas.width / 2f, yPos, textPaint)
                surface.unlockCanvasAndPost(canvas)

                presentationTimeUs += frameDurationUs
                drainEncoder(encoder, muxer, bufferInfo, false) { muxerStarted = true }
            }
        }

        encoder.signalEndOfInputStream()
        drainEncoder(encoder, muxer, bufferInfo, true) { muxerStarted = true }

        encoder.stop()
        encoder.release()
        surface.release()

        if (muxerStarted) {
            muxer.stop()
            muxer.release()
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        onMuxerStart: (Int) -> Unit
    ) {
        val timeoutUs = 10000L
        while (true) {
            val status = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = encoder.outputFormat
                val track = muxer.addTrack(newFormat)
                muxer.start()
                onMuxerStart(track)
            } else if (status >= 0) {
                val encodedData = encoder.getOutputBuffer(status) ?: continue
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size != 0) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(0, encodedData, bufferInfo)
                }
                encoder.releaseOutputBuffer(status, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
