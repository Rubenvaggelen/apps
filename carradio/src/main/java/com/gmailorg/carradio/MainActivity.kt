package com.gmailorg.carradio

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var messageContainer: LinearLayout
    private var speechRecognizer: SpeechRecognizer? = null
    private var lastPartialSpeech: String? = null

    private val messageListener: (String) -> Unit = { text -> addMessage(text) }
    private val statusListener: (String) -> Unit = { text -> statusText.text = text }

    private val requestBluetoothPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startService()
    }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startLocalVoiceReply()
        } else {
            MessageBus.postMessage("⚠️ Microfoontoestemming is nodig om je antwoord in de auto in te spreken.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        messageContainer = findViewById(R.id.messageContainer)

        MessageBus.addListener(messageListener)
        MessageBus.addStatusListener(statusListener)

        findViewById<View>(R.id.choosePhoneButton).setOnClickListener {
            showPhonePickerDialog()
        }

        findViewById<View>(R.id.replyButton).setOnClickListener {
            startVoiceReply()
        }

        UpdateChecker.checkForUpdate(this)

        ensurePermissionThenStart()
    }

    private fun ensurePermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }
        startService()
    }

    private fun startService() {
        startForegroundService(Intent(this, BluetoothListenerService::class.java))
    }


    /**
     * Luister primair op de autoradio zelf. Dat is betrouwbaarder dan de microfoon
     * van een vergrendelde telefoon vanuit een achtergrondservice te openen.
     */
    private fun startVoiceReply() {
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!micGranted) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startLocalVoiceReply()
    }

    private fun startLocalVoiceReply() {
        // Sommige Android-autoradio-ROMs melden ten onrechte dat er geen
        // SpeechRecognizer beschikbaar is, terwijl Google/Gboard en de microfoon
        // wel aanwezig zijn. Daarom blokkeren we niet meer op
        // SpeechRecognizer.isRecognitionAvailable(). We proberen de recognizer
        // direct te starten en vallen alleen terug op de telefoon als dat echt faalt.
        speechRecognizer?.destroy()
        lastPartialSpeech = null

        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            fallbackToPhoneSpeech("Lokale spraakherkenning kon niet worden geopend")
            return
        }
        speechRecognizer = recognizer

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "nl-NL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                MessageBus.postMessage("🎙️ Spreek nu je antwoord in...")
            }

            override fun onBeginningOfSpeech() {
                MessageBus.postStatus("Luisteren naar je antwoord...")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                if (!partial.isNullOrBlank()) lastPartialSpeech = partial
            }

            override fun onResults(results: Bundle) {
                val finalText = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .takeUnless { it.isNullOrBlank() }
                    ?: lastPartialSpeech

                if (finalText.isNullOrBlank()) {
                    MessageBus.postMessage("⚠️ Ik kon je antwoord niet verstaan. Probeer het opnieuw.")
                } else {
                    val sent = BluetoothListenerService.sendVoiceReply(finalText)
                    if (sent) {
                        MessageBus.postMessage("📤 Versturen: $finalText")
                    } else {
                        MessageBus.postMessage("⚠️ Spraak verstaan, maar de verbinding met je telefoon is weg.")
                    }
                }
                finishSpeechRecognition()
            }

            override fun onError(error: Int) {
                val reason = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "microfoonfout"
                    SpeechRecognizer.ERROR_CLIENT -> "spraakservicefout"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "geen microfoontoestemming"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "netwerkfout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "niets herkend"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "spraakherkenning is al bezig"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "geen spraak gehoord"
                    SpeechRecognizer.ERROR_SERVER -> "spraakserverfout"
                    else -> "foutcode $error"
                }

                // Als we al bruikbare partial speech kregen, verlies die niet door een late error.
                val partial = lastPartialSpeech
                if (!partial.isNullOrBlank() && BluetoothListenerService.sendVoiceReply(partial)) {
                    MessageBus.postMessage("📤 Versturen: $partial")
                    finishSpeechRecognition()
                    return
                }

                MessageBus.postMessage("⚠️ Spraakherkenning: $reason. Probeer het opnieuw.")
                finishSpeechRecognition()
            }

            override fun onEndOfSpeech() {
                MessageBus.postStatus("Antwoord verwerken...")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            finishSpeechRecognition()
            fallbackToPhoneSpeech("Lokale spraakherkenning kon niet starten")
        }
    }

    private fun finishSpeechRecognition() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        lastPartialSpeech = null
    }

    private fun fallbackToPhoneSpeech(reason: String) {
        MessageBus.postMessage("ℹ️ $reason — ik probeer de oude telefoonmethode.")
        Thread {
            val sent = BluetoothListenerService.requestVoiceReply()
            if (!sent) {
                MessageBus.postMessage("⚠️ Geen verbinding met je telefoon — kan geen antwoord vragen.")
            }
        }.start()
    }

    /** Toont een lijst van al gekoppelde Bluetooth-apparaten, zodat de gebruiker kan aanwijzen welke de telefoon is. */
    private fun showPhonePickerDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Toast.makeText(this, "Geef eerst Bluetooth-toestemming.", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val devices = adapter?.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) {
            Toast.makeText(this, "Geen gekoppelde apparaten gevonden. Koppel eerst je telefoon via Bluetooth-instellingen.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = devices.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Kies je telefoon")
            .setItems(labels) { _, which ->
                val device = devices[which]
                PairedPhoneStore.setSelected(this, device.address, device.name ?: device.address)
                Toast.makeText(this, "Gekozen: ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun addMessage(text: String) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val row = TextView(this).apply {
            this.text = "$time  •  $text"
            setTextColor(ContextCompat.getColor(context, R.color.text_main))
            textSize = 18f
            setPadding(0, 0, 0, 16)
        }
        messageContainer.addView(row, 0)
    }

    override fun onDestroy() {
        finishSpeechRecognition()
        MessageBus.removeListener(messageListener)
        MessageBus.removeStatusListener(statusListener)
        super.onDestroy()
    }
}
