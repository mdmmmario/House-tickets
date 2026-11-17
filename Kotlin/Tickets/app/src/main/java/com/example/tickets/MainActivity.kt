package com.example.tickets // <-- Mantén tu package original

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var btnEscanear: Button
    private lateinit var tvResultadoOcr: TextView
    private lateinit var btnCopiarTexto: Button

    data class TrozoTexto(val texto: String, val centroY: Int, val izquierdaX: Int, val alto: Int)

    // Lanzador del escáner de documentos nativo (recorta y aplica filtros al ticket)
    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val imageUri = scanningResult?.pages?.firstOrNull()?.imageUri
            if (imageUri != null) {
                procesarImagenConOCR(imageUri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        btnEscanear = findViewById(R.id.btnEscanear)
        tvResultadoOcr = findViewById(R.id.tvResultadoOcr)
        btnCopiarTexto = findViewById(R.id.btnCopiarTexto)

        btnEscanear.setOnClickListener {
            abrirEscanerDeTickets()
        }

        btnCopiarTexto.setOnClickListener {
            val textoACopiar = tvResultadoOcr.text.toString()
            if (textoACopiar.isNotBlank() && !textoACopiar.startsWith("Pulsa el botón")) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Ticket OCR", textoACopiar)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Texto copiado al portapapeles", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No hay texto para copiar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun abrirEscanerDeTickets() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error al abrir la cámara: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun procesarImagenConOCR(imageUri: Uri) {
        val image = InputImage.fromFilePath(this, imageUri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        tvResultadoOcr.text = "Leyendo texto y ordenando por coordenadas..."

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                mostrarTextoReconstruido(visionText)
            }
            .addOnFailureListener { e ->
                tvResultadoOcr.text = "Error en OCR: ${e.message}"
            }
    }

    private fun mostrarTextoReconstruido(visionText: Text) {
        // 1. Extraer todas las líneas individuales con sus coordenadas físicas
        val todasLasLineas = visionText.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            TrozoTexto(
                texto = line.text.trim(),
                centroY = box.centerY(),
                izquierdaX = box.left,
                alto = box.height()
            )
        }.sortedBy { it.centroY }

        if (todasLasLineas.isEmpty()) {
            tvResultadoOcr.text = "No se detectó texto en la imagen."
            return
        }

        // 2. Agrupar líneas que comparten la misma altura vertical (eje Y)
        val alturaMedia = todasLasLineas.map { it.alto }.average().toInt().coerceAtLeast(15)
        val toleranciaY = (alturaMedia * 0.65).toInt()

        val filasAgrupadas = mutableListOf<MutableList<TrozoTexto>>()
        for (trozo in todasLasLineas) {
            val filaExistente = filasAgrupadas.find { fila ->
                abs(fila.map { it.centroY }.average() - trozo.centroY) <= toleranciaY
            }
            if (filaExistente != null) {
                filaExistente.add(trozo)
            } else {
                filasAgrupadas.add(mutableListOf(trozo))
            }
        }

        // 3. Ordenar cada fila de izquierda a derecha (coordenada X) para mantener el sentido de lectura
        val sb = StringBuilder()
        sb.appendLine("=== TICKET RECONSTRUIDO POR COORDENADAS ===")

        filasAgrupadas.forEachIndexed { index, fila ->
            val textoFila = fila.sortedBy { it.izquierdaX }.joinToString("   ") { it.texto }
            sb.appendLine(textoFila)
        }

        tvResultadoOcr.text = sb.toString()
    }
}