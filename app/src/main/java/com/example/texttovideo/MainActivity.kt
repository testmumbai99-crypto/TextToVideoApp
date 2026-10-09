package com.example.texttovideo

import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var editTextLines: EditText
    private lateinit var btnGenerate: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var videoPreview: VideoView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editTextLines = findViewById(R.id.editTextLines)
        btnGenerate = findViewById(R.id.btnGenerate)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        videoPreview = findViewById(R.id.videoPreview)

        btnGenerate.setOnClickListener {
            val text = editTextLines.text.toString().trim()
            if (text.isEmpty()) {
                tvStatus.text = "Please enter some text."
                return@setOnClickListener
            }
            val lines = text.lines().filter { it.isNotBlank() }
            startVideoGeneration(lines)
        }
    }

    private fun startVideoGeneration(lines: List<String>) {
        btnGenerate.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Encoding video..."

        thread {
            try {
                val outputFile = File(cacheDir, "text_video.mp4")
                if (outputFile.exists()) outputFile.delete()

                renderTextToVideo(lines, outputFile)

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnGenerate.isEnabled = true
                    tvStatus.text = "Video rendered successfully!"
                    videoPreview.setVideoURI(Uri.fromFile(outputFile))
                    videoPreview.setOnPreparedListener { mp -> mp.isLooping = true }
                    videoPreview.start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnGenerate.isEnabled = true
                    tvStatus.text = "Error: \${e.localizedMessage}"
                }
            }
        }
    }

    private fun renderTextToVideo(lines: List<String>, outputFile: File) {
        val width = 720
        val height = 1280
        val fps = 30
        val durationPerSlideSeconds = 2
        val framesPerSlide = fps * durationPerSlideSeconds
        val bitRate = 2_000_000
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
        var trackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 52f
            textAlign = Paint.Align.CENTER
        }

        var presentationTimeUs = 0L
        val frameDurationUs = 1_000_000L / fps

        for (line in lines) {
            for (i in 0 until framesPerSlide) {
                val canvas = surface.lockHardwareCanvas()
                canvas.drawColor(Color.parseColor("#121212"))
                val yPos = (canvas.height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
                canvas.drawText(line, canvas.width / 2f, yPos, textPaint)
                surface.unlockCanvasAndPost(canvas)

                presentationTimeUs += frameDurationUs
                drainEncoder(encoder, muxer, bufferInfo, false) { newTrack ->
                    trackIndex = newTrack
                    muxerStarted = true
                }
            }
        }

        encoder.signalEndOfInputStream()
        drainEncoder(encoder, muxer, bufferInfo, true) { newTrack ->
            trackIndex = newTrack
            muxerStarted = true
        }

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
        val timeoutUs = 10_000L

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

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    break
                }
            }
        }
    }
}
