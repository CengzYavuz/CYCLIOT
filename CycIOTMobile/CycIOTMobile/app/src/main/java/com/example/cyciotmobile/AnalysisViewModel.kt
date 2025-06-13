package com.example.cyciotmobile

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerateContentResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

class AnalysisViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "cyciot-AnalysisVM"
    private val GEMINI_API_KEY = "AIzaSyBzBoRyXZl7oDQQxfRoNBvRVA-uUD3-OW8"
    
    private val bleService = BLEService.getInstance(application)
    private val sensorDataList = mutableListOf<SensorData>()
    private val lastAnalysisData = mutableListOf<SensorData>() // Store last 5 readings from previous analysis
    private var isAnalyzing = false
    private var needsMoreData = false // Flag to indicate we need more data after analysis
    private var dataCountAfterAnalysis = 0 // Counter for new readings after analysis
    
    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.0-flash",
        apiKey = GEMINI_API_KEY
    )

    private val _analysisState = MutableStateFlow<AnalysisState>(AnalysisState.Initial)
    val analysisState: StateFlow<AnalysisState> = _analysisState.asStateFlow()

    init {
        Log.d(TAG, "=== AnalysisViewModel Initialized ===")
        Log.d(TAG, "BLEService instance: $bleService")
        Log.d(TAG, "Connection state: ${bleService.connectionState.value}")
        startDataCollection()
    }

    private fun startDataCollection() {
        viewModelScope.launch {
            Log.d(TAG, "=== Starting Data Collection ===")
            try {
                launch {
                    Log.d(TAG, "Starting sensor data collection")
                    bleService.sensorData.collectLatest { data: SensorData? ->
                        if (data != null) {
                            Log.d(TAG, "Received sensor data: $data")
                            addData(data)
                        } else {
                            Log.d(TAG, "Received null sensor data")
                        }
                    }
                }
                
                launch {
                    Log.d(TAG, "Starting raw data collection")
                    bleService.rawData.collectLatest { raw: String ->
                        Log.d(TAG, "Received raw data: $raw")
                    }
                }

                launch {
                    Log.d(TAG, "Starting connection state monitoring")
                    bleService.connectionState.collectLatest { state ->
                        Log.d(TAG, "Connection state changed: $state")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in data collection", e)
                _analysisState.value = AnalysisState.Error("Data collection error: ${e.message}")
            }
        }
    }

    private fun addData(data: SensorData) {
        if (needsMoreData) {
            // We're collecting new data after analysis
            dataCountAfterAnalysis++
            Log.d(TAG, "Collecting new data after analysis. Count: $dataCountAfterAnalysis")
            
            if (dataCountAfterAnalysis >= 10) {
                // We have enough new data, combine with last analysis data
                Log.d(TAG, "=== Collected enough new data, combining with previous analysis ===")
                sensorDataList.clear()
                sensorDataList.addAll(lastAnalysisData) // Add last 5 readings from previous analysis
                sensorDataList.addAll(sensorDataList.takeLast(10)) // Add new 10 readings
                needsMoreData = false
                dataCountAfterAnalysis = 0
                performAnalysis()
            }
        } else {
            // Normal data collection
            sensorDataList.add(data)
            Log.d(TAG, "Added data. Current count: ${sensorDataList.size}")
            
            // Keep only last 15 readings
            if (sensorDataList.size > 15) {
                sensorDataList.removeAt(0)
                Log.d(TAG, "Removed oldest reading. New count: ${sensorDataList.size}")
            }
            
            // Check if we should trigger analysis
            if (sensorDataList.size == 15 && !isAnalyzing && !needsMoreData) {
                Log.d(TAG, "=== TRIGGERING ANALYSIS ===")
                Log.d(TAG, "Data list size: ${sensorDataList.size}")
                Log.d(TAG, "isAnalyzing: $isAnalyzing")
                Log.d(TAG, "First reading: ${sensorDataList.first()}")
                Log.d(TAG, "Last reading: ${sensorDataList.last()}")
                
                // Store last 5 readings for next analysis
                lastAnalysisData.clear()
                lastAnalysisData.addAll(sensorDataList.takeLast(5))
                
                performAnalysis()
            }
        }
    }

    private fun performAnalysis() {
        if (isAnalyzing) {
            Log.d(TAG, "Already analyzing, skipping")
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "=== Starting Analysis ===")
                isAnalyzing = true
                _analysisState.value = AnalysisState.Analyzing
                
                val dataToAnalyze = sensorDataList.toList()
                Log.d(TAG, "Data to analyze size: ${dataToAnalyze.size}")
                
                val prompt = buildPrompt(dataToAnalyze)
                Log.d(TAG, "=== Sending API Request ===")
                Log.d(TAG, "Prompt length: ${prompt.length}")
                
                try {
                    Log.d(TAG, "Calling Gemini API...")
                    val response = generativeModel.generateContent(prompt)
                    Log.d(TAG, "API Response received")
                    
                    val responseText = response.text
                    if (responseText != null) {
                        Log.d(TAG, "Analysis result: $responseText")
                        _analysisState.value = AnalysisState.Result(responseText)
                        
                        // After successful analysis, set flag to collect more data
                        needsMoreData = true
                        dataCountAfterAnalysis = 0
                        Log.d(TAG, "=== Analysis complete, waiting for 10 more readings ===")
                    } else {
                        Log.e(TAG, "Null response from API")
                        _analysisState.value = AnalysisState.Error("No response from API")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "API call failed", e)
                    _analysisState.value = AnalysisState.Error("API Error: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
                _analysisState.value = AnalysisState.Error(e.message ?: "Unknown error")
            } finally {
                isAnalyzing = false
                Log.d(TAG, "=== Analysis Complete ===")
            }
        }
    }

    private fun buildPrompt(dataToAnalyze: List<SensorData>): String {
        Log.d(TAG, "Building prompt for ${dataToAnalyze.size} readings")
        val dataText = dataToAnalyze.joinToString("\n") { data ->
            when (data) {
                is SensorData.AllInOne -> "AllInOne: AX=${data.accelX}, AY=${data.accelY}, AZ=${data.accelZ}, " +
                        "GX=${data.gyroX}, GY=${data.gyroY}, GZ=${data.gyroZ}, " +
                        "DL=${data.distanceLeft}, DR=${data.distanceRight}, " +
                        "BPM=${data.bpm}"
                is SensorData.Accelerometer -> "Accel: X=${data.x}, Y=${data.y}, Z=${data.z}"
                is SensorData.Distance -> "Distance: L=${data.left}, R=${data.right}"
            }
        }
        
        return """
            Analyze the following sensor data from a bicycle IoT device and provide a short feedback of maximum 1 sentence.
            Focus on the most important insights about driving patterns, stability, heart rate, and potential improvements. 
            For heart rate (BPM): 60-100 is normal at rest, 100-170 is normal during exercise.
            If there is no problem, there are motivational phrases such as "you're doing well" or natural phrases such as "slow down" when you speed up too much, "let's go :)" when you slow down, "is everything ok" when you suddenly tip over, etc. 
            There are support and motivational answers for the cyclist. 
            The data includes acceleration, gyroscopic sensor, distance sensors on the left and right side, and heart rate (BPM). Use them in your answers.
            YOUR ANSWERS MUST BE IN TURKISH
            
            Data:
            $dataText
            
        """.trimIndent()
    }
}

sealed class AnalysisState {
    object Initial : AnalysisState()
    object Analyzing : AnalysisState()
    data class Result(val text: String) : AnalysisState()
    data class Error(val message: String) : AnalysisState()
} 