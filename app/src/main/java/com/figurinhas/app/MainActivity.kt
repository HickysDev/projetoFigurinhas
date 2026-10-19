package com.figurinhas.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.figurinhas.app.databinding.ActivityMainBinding
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val pickPhoto = registerForActivityResult(PickMultipleVisualMedia(StickerStore.MAX_STICKERS)) { uris ->
        cropThenCreate(uris)
    }

    /** Fotos esperando recorte (uma tela de recorte por vez) e as já recortadas. */
    private val cropQueue = ArrayDeque<Uri>()
    private val cropped = mutableListOf<Uri>()

    private val cropImage = registerForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) result.uriContent?.let { cropped += it }
        cropNext()
    }

    private val pickVideo = registerForActivityResult(PickMultipleVisualMedia(StickerStore.MAX_STICKERS)) { uris ->
        createStickers(uris.map { it to Pack.ANIMATED })
    }

    private val addToWhatsApp =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_CANCELED) {
                val error = result.data?.getStringExtra("validation_error")
                if (error != null) toast("WhatsApp recusou o pacote: $error")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPhoto.setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        }
        binding.btnVideo.setOnClickListener {
            pickVideo.launch(PickVisualMediaRequest(PickVisualMedia.VideoOnly))
        }

        refreshPacks()
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Recebe fotos/vídeos enviados pelo botão "Compartilhar" de outro app (um ou vários). */
    private fun handleShare(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: emptyList()
            else -> return
        }
        val (animated, photos) = uris.partition { uri ->
            val type = contentResolver.getType(uri) ?: intent.type ?: ""
            type.startsWith("video") || type == "image/gif"
        }
        createStickers(animated.map { it to Pack.ANIMATED })
        cropThenCreate(photos)
    }

    private fun cropThenCreate(uris: List<Uri>) {
        if (uris.isEmpty()) return
        cropQueue.addAll(uris)
        if (cropQueue.size == uris.size) cropNext() // só inicia se não havia fila em andamento
    }

    private fun cropNext() {
        val next = cropQueue.removeFirstOrNull()
        if (next == null) {
            createStickers(cropped.map { it to Pack.STATIC })
            cropped.clear()
            return
        }
        cropImage.launch(
            CropImageContractOptions(
                next,
                CropImageOptions(
                    guidelines = CropImageView.Guidelines.ON,
                    outputCompressFormat = Bitmap.CompressFormat.PNG, // mantém transparência
                    activityTitle = "Cortar foto",
                    cropMenuCropButtonTitle = "Pronto",
                    activityMenuIconColor = Color.WHITE,
                    toolbarColor = Color.parseColor("#25D366"),
                    activityBackgroundColor = Color.BLACK, // fundo escuro em volta da foto
                )
            )
        )
    }

    /** Converte os arquivos um por vez, na ordem em que foram escolhidos. */
    private fun createStickers(items: List<Pair<Uri, Pack>>) {
        if (items.isEmpty()) return
        setBusy(true)
        lifecycleScope.launch {
            var created = 0
            var failed = 0
            var full = 0
            for ((index, item) in items.withIndex()) {
                val (uri, pack) = item
                if (StickerStore.stickers(this@MainActivity, pack).size >= StickerStore.MAX_STICKERS) {
                    full++
                    continue
                }
                binding.progressText.text = "Convertendo ${index + 1} de ${items.size}..."
                binding.progress.setProgressCompat(0, false)
                val out = StickerStore.newStickerFile(this@MainActivity, pack)
                val result = withContext(Dispatchers.Default) {
                    runCatching {
                        if (pack.animated) {
                            StickerMaker.makeAnimated(this@MainActivity, uri, out) { p ->
                                runOnUiThread { binding.progress.setProgressCompat(p, true) }
                            }
                        } else {
                            StickerMaker.makeStatic(this@MainActivity, uri, out)
                        }
                        StickerMaker.makeTrayIcon(
                            StickerStore.stickers(this@MainActivity, pack).first(),
                            StickerStore.trayFile(this@MainActivity, pack)
                        )
                        StickerStore.bumpVersion(this@MainActivity, pack)
                    }
                }
                result.onSuccess { created++ }.onFailure {
                    out.delete()
                    failed++
                    toast("Erro no arquivo ${index + 1}: ${it.message}")
                }
                refreshPacks()
            }
            setBusy(false)
            val msg = buildString {
                append(if (created == 1) "1 figurinha criada" else "$created figurinhas criadas")
                if (failed > 0) append(", $failed com erro")
                if (full > 0) append(", $full ignoradas (pacote cheio)")
            }
            toast(msg)
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnPhoto.isEnabled = !busy
        binding.btnVideo.isEnabled = !busy
        binding.progressBox.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun refreshPacks() {
        binding.packs.removeAllViews()
        for (pack in Pack.entries) {
            val stickers = StickerStore.stickers(this, pack)

            binding.packs.addView(TextView(this).apply {
                text = "${pack.title} (${stickers.size}/${StickerStore.MAX_STICKERS})"
                textSize = 18f
                setPadding(0, dp(16), 0, dp(8))
            })

            val row = LinearLayout(this)
            for (file in stickers) row.addView(thumbnail(pack, file))
            if (stickers.isEmpty()) {
                row.addView(TextView(this).apply { text = "Nenhuma figurinha ainda." })
            }
            binding.packs.addView(HorizontalScrollView(this).apply { addView(row) })

            binding.packs.addView(MaterialButton(this).apply {
                val enough = stickers.size >= StickerStore.MIN_STICKERS
                text = if (enough) "Adicionar ao WhatsApp"
                else "Mínimo de ${StickerStore.MIN_STICKERS} figurinhas para enviar"
                isEnabled = enough
                setOnClickListener { sendToWhatsApp(pack) }
            })
        }
    }

    private fun thumbnail(pack: Pack, file: File) = ImageView(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginEnd = dp(8) }
        setBackgroundColor(0x22888888)
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
        setImageDrawable(drawable)
        (drawable as? AnimatedImageDrawable)?.start()
        setOnClickListener { showPreview(pack, file) }
        setOnLongClickListener {
            AlertDialog.Builder(this@MainActivity)
                .setMessage("Apagar esta figurinha?")
                .setPositiveButton("Apagar") { _, _ ->
                    StickerStore.delete(this@MainActivity, pack, file)
                    refreshPacks()
                }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }
    }

    /** Mostra a figurinha grande; se for animada, mostra também a duração. */
    private fun showPreview(pack: Pack, file: File) {
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(280), dp(280))
            setImageDrawable(drawable)
            (drawable as? AnimatedImageDrawable)?.start()
        }
        val info = buildString {
            if (pack.animated) {
                val (_, durationMs) = AnimatedWebpWriter.readInfo(file.readBytes())
                append("Duração: %.1f segundos\n".format(durationMs / 1000f))
            }
            append("Tamanho: ${file.length() / 1024} KB")
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), 0)
            addView(image)
            addView(TextView(this@MainActivity).apply {
                text = info
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            })
        }
        AlertDialog.Builder(this)
            .setView(content)
            .setPositiveButton("Fechar", null)
            .show()
    }

    private fun sendToWhatsApp(pack: Pack) {
        val installed = listOf("com.whatsapp", "com.whatsapp.w4b").filter { isInstalled(it) }
        if (installed.isEmpty()) {
            toast("WhatsApp não encontrado")
            return
        }
        val intent = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
            putExtra("sticker_pack_id", pack.id)
            putExtra("sticker_pack_authority", StickerContentProvider.authority(packageName))
            putExtra("sticker_pack_name", pack.title)
            // Com WhatsApp e WhatsApp Business instalados, deixa o sistema perguntar qual usar
            if (installed.size == 1) setPackage(installed.first())
        }
        try {
            addToWhatsApp.launch(intent)
        } catch (e: ActivityNotFoundException) {
            toast("Sua versão do WhatsApp não aceita pacotes de figurinhas. Atualize o app.")
        }
    }

    private fun isInstalled(pkg: String) =
        runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
