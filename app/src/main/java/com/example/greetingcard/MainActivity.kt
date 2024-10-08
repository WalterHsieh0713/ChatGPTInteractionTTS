package com.example.greetingcard

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.example.greetingcard.ui.theme.GreetingCardTheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.IllegalStateException

class MainActivity : ComponentActivity() {
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording: Boolean = false
    private var outputFileUri: Uri? = null
    private var isChatGPTLoading by mutableStateOf(false)


    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startRecording()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GreetingCardTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GreetingWithButton(onButtonClick = { checkPermissionsAndRecord() }, isChatGPTLoading)
                }
            }
        }
    }

    private fun checkPermissionsAndRecord() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    startRecording()
                } else {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                startRecording()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            outputFileUri = createAudioFileUri()
            outputFileUri?.let { uri ->
                mediaRecorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(contentResolver.openFileDescriptor(uri, "w")?.fileDescriptor)
                    try {
                        prepare()
                    } catch (e: IOException) {
                        e.printStackTrace()
                        Toast.makeText(this@MainActivity, "Failed to prepare MediaRecorder", Toast.LENGTH_SHORT).show()
                    } catch (e: IllegalStateException) {
                        e.printStackTrace()
                        Toast.makeText(this@MainActivity, "MediaRecorder start called in an invalid state", Toast.LENGTH_SHORT).show()
                    }
                    start()
                }
                isRecording = true
                Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
            isRecording = false
            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()

            outputFileUri?.let { uri ->
                val audioFile = getFileFromUri(uri)
                audioFile?.let {
                    transcribeAudio(it)
                }
            }
        } catch (e: RuntimeException) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to stop recording", Toast.LENGTH_SHORT).show()
        } finally {
            mediaRecorder = null
        }
    }

    private fun getFileFromUri(uri: Uri): File? {
        return try {
            val parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
            parcelFileDescriptor?.let {
                val fileDescriptor = it.fileDescriptor
                val inputStream = FileInputStream(fileDescriptor)
                val tempFile = File(cacheDir, "temp_audio_file.m4a")
                val outputStream = FileOutputStream(tempFile)
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                it.close()
                tempFile
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    private fun createAudioFileUri(): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "recording_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/m4a")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Recordings")
        }

        return contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
    }

    @Composable
    fun GreetingWithButton(
        onButtonClick: () -> Unit,
        isChatGPTLoading: Boolean, // New parameter to track ChatGPT loading state
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = onButtonClick,
                enabled = !isChatGPTLoading // Disable button when ChatGPT is loading
            ) {
                // Set button text based on ChatGPT loading state
                Text(text = if (isChatGPTLoading) "Thinking..." else "Start Recording")
            }
        }
    }


    @Preview(showBackground = true)
    @Composable
    fun GreetingWithButtonPreview() {
        GreetingCardTheme {
            GreetingWithButton(onButtonClick = {}, false)
        }
    }
    private fun handleTranscriptionResponse(responseBody: String) {
        // Parse the response and extract the transcribed text
        val transcribedText = parseTranscribedText(responseBody)
        isChatGPTLoading = false
        // Send the transcribed text to the ChatGPT API and get the response
        getChatGPTResponse(transcribedText) { chatGPTResponse ->
            // Print the ChatGPT response in the log
            println("ChatGPT Response: $chatGPTResponse")
            textToSpeechOpenAI(chatGPTResponse)
        }
    }

    private fun parseTranscribedText(responseBody: String): String {
        // Implement parsing logic to extract the transcribed text from the API response
        // Assuming the response is in JSON format: {"text": "Transcribed text goes here"}
        val jsonObject = JSONObject(responseBody)
        return jsonObject.getString("text")
    }

    private fun getChatGPTResponse(transcribedText: String, onResponse: (String) -> Unit) {
        isChatGPTLoading = true
        val client = OkHttpClient()

        val requestBody = RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            "{\"model\": \"gpt-3.5-turbo\", \"messages\": [{\"role\": \"user\", \"content\": \"$transcribedText\"}]}"
        )

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer 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")
            .post(requestBody)
            .build()

        val clientScope = CoroutineScope(Dispatchers.IO)
        clientScope.launch {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    responseBody?.let { parseAndHandleChatGPTResponse(it, onResponse) }
                } else {
                    println("Response code: ${response.code}")
                    println("Response message: ${response.message}")
                    response.body?.string()?.let { println("Response body: $it") }
                }
            } catch (e: IOException) {
                println("IOException: ${e.message}")
            }
        }
    }

    private fun parseAndHandleChatGPTResponse(responseBody: String, onResponse: (String) -> Unit) {
        // Implement parsing logic to extract the generated text from the API response
        val generatedText = parseGeneratedText(responseBody)
        onResponse(generatedText)
    }

    private fun parseGeneratedText(responseBody: String): String {
        // Implement parsing logic to extract the generated text from the API response
        // Assuming the response is in JSON format: {"choices": [{"message": {"content": "Generated text goes here"}}]}
        val jsonObject = JSONObject(responseBody)
        val choices = jsonObject.getJSONArray("choices")
        val firstChoice = choices.getJSONObject(0)
        val message = firstChoice.getJSONObject("message")
        return message.getString("content")
    }
    private fun transcribeAudio(audioFile: File) {
        val client = OkHttpClient()

        println("audioFile")
        println(audioFile)

        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.m4a", audioFile.asRequestBody("audio/m4a".toMediaTypeOrNull()))
            .addFormDataPart("model", "whisper-1")
            .build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer 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")
            .post(requestBody)
            .build()

        val clientScope = CoroutineScope(Dispatchers.IO)
        clientScope.launch {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    println("responseBody")
                    println(responseBody)
                    handleTranscriptionResponse(responseBody ?: "Failed to generate response")
                } else {
                    println("Response code: ${response.code}")
                    println("Response message: ${response.message}")
                    response.body?.string()?.let { println("Response body: $it") }
                }
            } catch (e: IOException) {
                println("IOException: ${e.message}")
            }
        }
    }
    private fun textToSpeechOpenAI(text: String) {
        val client = OkHttpClient()

        // Properly formatted JSON string
        val jsonPayload = """
        {
            "model": "tts-1",
            "input": "$text",
            "voice": "alloy"
        }
    """.trimIndent()

        // Create the request body with the correct media type
            val requestBody = RequestBody.create(
                "application/json".toMediaTypeOrNull(),
                jsonPayload
            )

            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/speech")
                .addHeader("Authorization", "Bearer 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")
                .post(requestBody)
                .build()

        val clientScope = CoroutineScope(Dispatchers.IO)
        clientScope.launch {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val audioResponseBody = response.body?.bytes()
                    audioResponseBody?.let {
                        playAudio(it)
                    }
                } else {
                    println("Response code: ${response.code}")
                    println("Response message: ${response.message}")
                    response.body?.string()?.let { println("Response body: $it") }
                }
            } catch (e: IOException) {
                println("IOException: ${e.message}")
            }
        }
    }

    private fun playAudio(audioBytes: ByteArray) {
        val audioFile = File.createTempFile("tts_audio", ".mp3", cacheDir)
        FileOutputStream(audioFile).use {
            it.write(audioBytes)
        }

        val mediaPlayer = MediaPlayer()
        mediaPlayer.apply {
            setDataSource(audioFile.absolutePath)
            prepare()
            start()
            setOnCompletionListener {
                // Handle completion here, such as changing button text back to original
                isChatGPTLoading = false
            }
        }
    }
}


