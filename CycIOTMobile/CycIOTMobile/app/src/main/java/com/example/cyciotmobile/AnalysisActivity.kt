package com.example.cyciotmobile

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class AnalysisActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val viewModel: AnalysisViewModel by viewModels()
    private lateinit var analysisText: TextView
    private lateinit var textToSpeech: TextToSpeech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analysis)

        initializeViews()
        initializeTextToSpeech()
        observeViewModel()
    }

    private fun initializeViews() {
        analysisText = findViewById(R.id.analysisText)
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech.setLanguage(Locale("tr"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Fallback to default language if Turkish is not available
                textToSpeech.setLanguage(Locale.getDefault())
            }
        }
    }

    private fun speakText(text: String) {
        textToSpeech.stop() // Stop any ongoing speech
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.analysisState.collectLatest { state ->
                when (state) {
                    is AnalysisState.Initial -> {
                        textToSpeech.stop()
                        analysisText.text = "Collecting sensor data..."
                    }
                    is AnalysisState.Analyzing -> {
                        textToSpeech.stop()
                        analysisText.text = "Analyzing data..."
                    }
                    is AnalysisState.Result -> {
                        analysisText.text = state.text
                        speakText(state.text)
                    }
                    is AnalysisState.Error -> {
                        val errorMessage = "Error: ${state.message}"
                        analysisText.text = errorMessage
                        speakText(errorMessage)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        textToSpeech.stop()
        textToSpeech.shutdown()
        super.onDestroy()
    }
} 