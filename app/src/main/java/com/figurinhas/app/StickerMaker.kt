package com.figurinhas.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Converte fotos e vídeos no formato exigido pelo WhatsApp:
 *  - estática: WebP 512x512, até 100 KB
 *  - animada:  WebP animado 512x512, até 500 KB, no máximo 10 s
 */
object StickerMaker {
    private const val SIZE = 512
    private const val STATIC_MAX_BYTES = 100 * 1024
    private const val ANIMATED_MAX_BYTES = 500 * 1024
    private const val MAX_DURATION_MS = 10_000L
    private const val TRAY_SIZE = 96

    fun makeStatic(context: Context, uri: Uri, out: File) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // Reduz já na decodificação para não estourar memória com fotos grandes
            val scale = SIZE.toFloat() / maxOf(info.size.width, info.size.height)
            if (scale < 1f) {
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        }
        val square = fitToSquare(bitmap)
        var quality = 95
        var bytes: ByteArray
        do {
            bytes = encodeWebp(square, quality)
            quality -= 10
        } while (bytes.size > STATIC_MAX_BYTES && quality > 5)
        if (bytes.size > STATIC_MAX_BYTES) error("Não foi possível deixar a imagem abaixo de 100 KB")
        out.writeBytes(bytes)
    }

    fun makeAnimated(context: Context, uri: Uri, out: File, onProgress: (Int) -> Unit = {}) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: error("Não foi possível ler o vídeo")
            val clipMs = minOf(durationMs, MAX_DURATION_MS)

            // Extrai os quadros uma vez só (parte mais lenta)
            val fps = 10
            val frameMs = 1000L / fps
            val bitmaps = mutableListOf<Bitmap>()
            var t = 0L
            while (t < clipMs) {
                retriever.getScaledFrameAtTime(
                    t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, SIZE, SIZE
                )?.let { bitmaps += fitToSquare(it) }
                t += frameMs
                onProgress((t * 80 / clipMs).toInt())
            }
            if (bitmaps.isEmpty()) error("Não foi possível extrair quadros do vídeo")

            // Vai reduzindo qualidade e quadros até caber em 500 KB
            val attempts = listOf(1 to 70, 1 to 50, 1 to 35, 2 to 35, 2 to 20, 3 to 15)
            for ((index, attempt) in attempts.withIndex()) {
                val (step, quality) = attempt
                val frames = bitmaps.filterIndexed { i, _ -> i % step == 0 }
                    .map { encodeWebp(it, quality) }
                // Ao pular quadros, limita a duração para o total não passar de 10 s
                val duration = minOf(frameMs * step, MAX_DURATION_MS / frames.size).toInt()
                val webp = AnimatedWebpWriter.write(frames, SIZE, SIZE, duration)
                onProgress(80 + (index + 1) * 20 / attempts.size)
                if (webp.size <= ANIMATED_MAX_BYTES) {
                    out.writeBytes(webp)
                    return
                }
            }
            error("O vídeo ficou grande demais. Tente um vídeo mais curto.")
        } finally {
            retriever.release()
        }
    }

    /** Ícone da bandeja: PNG 96x96, gerado a partir da primeira figurinha. */
    fun makeTrayIcon(sticker: File, out: File) {
        // BitmapFactory lê o primeiro quadro, inclusive de WebP animado
        val bitmap = BitmapFactory.decodeFile(sticker.absolutePath) ?: return
        val tray = Bitmap.createScaledBitmap(bitmap, TRAY_SIZE, TRAY_SIZE, true)
        out.outputStream().use { tray.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Centraliza a imagem num quadrado 512x512 com fundo transparente. */
    private fun fitToSquare(src: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val scale = SIZE.toFloat() / maxOf(src.width, src.height)
        val w = src.width * scale
        val h = src.height * scale
        val left = (SIZE - w) / 2
        val top = (SIZE - h) / 2
        Canvas(result).drawBitmap(
            src, null, RectF(left, top, left + w, top + h),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        return result
    }

    @Suppress("DEPRECATION")
    private fun encodeWebp(bitmap: Bitmap, quality: Int): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP
        return ByteArrayOutputStream().use {
            bitmap.compress(format, quality, it)
            it.toByteArray()
        }
    }
}
