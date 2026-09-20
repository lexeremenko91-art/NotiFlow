package com.lexlebeau.notiflow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.ktx.Firebase
import com.google.firebase.database.ktx.database
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import java.util.UUID
import javax.crypto.KeyGenerator

class PairActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var copyCodeButton: Button
    private lateinit var scanButton: Button
    private lateinit var manualEntryButton: Button
    private lateinit var pairedIcon: TextView
    private lateinit var pairedLabel: TextView
    private lateinit var pairedCode: TextView
    private lateinit var unpairButton: Button
    private var receiverOnlineListener: ValueEventListener? = null
    private var currentPairCode: String? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        if (result.contents != null) {
            if (!tryPair(result.contents)) {
                statusText.text = getString(R.string.pair_error)
            }
        }
    }

    /** Парсит код вида "XXXXXXXX:КЛЮЧ" (как в QR) и спаривает устройства. */
    private fun tryPair(content: String): Boolean {
        val parts = content.split(":")
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return false
        }
        val pairCode = parts[0]
        val aesKey = parts[1]
        val mode = prefs.getString("mode", "sender")

        if (mode == "receiver") {
            val added = PairedDevices.add(prefs, pairCode, aesKey, pairCode)
            if (!added) {
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.pair_limit_reached, PairedDevices.MAX_DEVICES)
                return true
            }
            currentPairCode = pairCode
            showPairedState(pairCode)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            val serviceIntent = android.content.Intent(this, ReceiverService::class.java)
            startForegroundService(serviceIntent)

            showDeviceLabelDialog(this, pairCode) { newLabel ->
                PairedDevices.add(prefs, pairCode, aesKey, newLabel)
            }
        } else {
            prefs.edit()
                .putString("pairCode", pairCode)
                .putString("aesKey", aesKey)
                .apply()
            showPairedState(pairCode)
        }
        return true
    }

    private fun showManualEntryDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.manual_entry_hint)
        val padding = (20 * resources.displayMetrics.density).toInt()
        input.setPadding(padding, padding, padding, padding)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.manual_entry_title))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_pair)) { _, _ ->
                val code = input.text.toString().trim()
                if (!tryPair(code)) {
                    statusText.text = getString(R.string.pair_error)
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_pair)

        prefs = getSharedPreferences("notiflow", MODE_PRIVATE)
        statusText = findViewById(R.id.statusText)
        qrImage = findViewById(R.id.qrImage)
        copyCodeButton = findViewById(R.id.copyCodeButton)
        scanButton = findViewById(R.id.scanButton)
        manualEntryButton = findViewById(R.id.manualEntryButton)
        pairedIcon = findViewById(R.id.pairedIcon)
        pairedLabel = findViewById(R.id.pairedLabel)
        pairedCode = findViewById(R.id.pairedCode)
        unpairButton = findViewById(R.id.unpairButton)

        val mode = prefs.getString("mode", "sender")
        val existingPairCode = prefs.getString("pairCode", null)
        val existingAesKey = prefs.getString("aesKey", null)

        if (mode == "receiver") {
            // Каждый заход в PairActivity в режиме ресивера — это добавление нового сендера,
            // а не замена уже спаренных устройств (см. PairedDevices).
            qrImage.visibility = View.GONE
            statusText.text = getString(R.string.pair_receiver_hint)
        } else {
            var pairCode = existingPairCode
            var aesKey = existingAesKey

            if (pairCode == null || aesKey == null) {
                pairCode = UUID.randomUUID().toString().take(8).uppercase()
                val keyGen = KeyGenerator.getInstance("AES")
                keyGen.init(256)
                aesKey = android.util.Base64.encodeToString(
                    keyGen.generateKey().encoded,
                    android.util.Base64.NO_WRAP
                )
                prefs.edit()
                    .putString("pairCode", pairCode)
                    .putString("aesKey", aesKey)
                    .apply()
            }

            currentPairCode = pairCode

            val qrContent = "$pairCode:$aesKey"
            try {
                val writer = MultiFormatWriter()
                val matrix = writer.encode(qrContent, BarcodeFormat.QR_CODE, 400, 400)
                val encoder = BarcodeEncoder()
                val bitmap = encoder.createBitmap(matrix)
                qrImage.setImageBitmap(bitmap)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            statusText.text = getString(R.string.pair_sender_hint, pairCode)
            copyCodeButton.visibility = View.VISIBLE
            scanButton.visibility = View.GONE
            manualEntryButton.visibility = View.GONE

            // Слушаем receiverOnline в реальном времени
            val finalPairCode = pairCode
            receiverOnlineListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val timestamp = snapshot.getValue(Long::class.java) ?: 0L
                    val isOnline = System.currentTimeMillis() - timestamp < 90000
                    if (isOnline) {
                        showPairedState(finalPairCode!!)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            Firebase.database.reference
                .child("pairs").child(pairCode!!).child("receiverOnline")
                .addValueEventListener(receiverOnlineListener!!)
        }

        scanButton.setOnClickListener {
            val options = ScanOptions()
            options.setPrompt(getString(R.string.pair_scan_prompt))
            options.setBeepEnabled(false)
            options.setOrientationLocked(true)
            scanLauncher.launch(options)
        }

        manualEntryButton.setOnClickListener {
            showManualEntryDialog()
        }

        copyCodeButton.setOnClickListener {
            val pairCode = prefs.getString("pairCode", null)
            val aesKey = prefs.getString("aesKey", null)
            if (pairCode != null && aesKey != null) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("pairCode", "$pairCode:$aesKey"))
                Toast.makeText(this, getString(R.string.pair_code_copied), Toast.LENGTH_SHORT).show()
            }
        }

        unpairButton.setOnClickListener {
            val currentMode = prefs.getString("mode", "sender")
            if (currentMode == "receiver") {
                currentPairCode?.let { code ->
                    PairedDevices.remove(prefs, code)
                    val intent = android.content.Intent(this, ReceiverService::class.java).apply {
                        action = ReceiverService.ACTION_UNPAIR_DEVICE
                        putExtra(ReceiverService.EXTRA_PAIR_CODE, code)
                    }
                    androidx.core.content.ContextCompat.startForegroundService(this, intent)
                }
            } else {
                prefs.edit()
                    .remove("pairCode")
                    .remove("aesKey")
                    .apply()
            }
            showUnpairedState()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Снимаем слушатель чтобы не утекал
        currentPairCode?.let { pairCode ->
            receiverOnlineListener?.let { listener ->
                Firebase.database.reference
                    .child("pairs").child(pairCode).child("receiverOnline")
                    .removeEventListener(listener)
            }
        }
    }

    private fun showPairedState(pairCode: String) {
        qrImage.visibility = View.GONE
        statusText.visibility = View.GONE
        copyCodeButton.visibility = View.GONE
        scanButton.visibility = View.GONE
        manualEntryButton.visibility = View.GONE

        pairedIcon.visibility = View.VISIBLE
        pairedLabel.visibility = View.VISIBLE
        pairedCode.visibility = View.VISIBLE
        unpairButton.visibility = View.VISIBLE

        pairedCode.text = getString(R.string.paired_code, pairCode)
    }

    private fun showUnpairedState() {
        pairedIcon.visibility = View.GONE
        pairedLabel.visibility = View.GONE
        pairedCode.visibility = View.GONE
        unpairButton.visibility = View.GONE

        val mode = prefs.getString("mode", "sender")
        if (mode == "receiver") {
            scanButton.visibility = View.VISIBLE
            manualEntryButton.visibility = View.VISIBLE
            statusText.visibility = View.VISIBLE
            statusText.text = getString(R.string.pair_receiver_hint)
        } else {
            qrImage.visibility = View.VISIBLE
            statusText.visibility = View.VISIBLE
            val pairCode = UUID.randomUUID().toString().take(8).uppercase()
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            val aesKey = android.util.Base64.encodeToString(
                keyGen.generateKey().encoded,
                android.util.Base64.NO_WRAP
            )
            prefs.edit()
                .putString("pairCode", pairCode)
                .putString("aesKey", aesKey)
                .apply()

            val qrContent = "$pairCode:$aesKey"
            try {
                val writer = MultiFormatWriter()
                val matrix = writer.encode(qrContent, BarcodeFormat.QR_CODE, 400, 400)
                val encoder = BarcodeEncoder()
                val bitmap = encoder.createBitmap(matrix)
                qrImage.setImageBitmap(bitmap)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            statusText.text = getString(R.string.pair_sender_hint, pairCode)
            copyCodeButton.visibility = View.VISIBLE
        }
    }
}