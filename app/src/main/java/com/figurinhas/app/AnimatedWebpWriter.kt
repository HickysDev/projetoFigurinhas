package com.figurinhas.app

import java.io.ByteArrayOutputStream

/**
 * O Android só gera WebP estático, então este código junta vários quadros
 * WebP num único WebP animado (formato RIFF com chunks VP8X, ANIM e ANMF).
 * Especificação: https://developers.google.com/speed/webp/docs/riff_container
 */
object AnimatedWebpWriter {

    fun write(frames: List<ByteArray>, width: Int, height: Int, frameDurationMs: Int): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))

        // VP8X: flags (animação + transparência) e tamanho do canvas
        val vp8x = ByteArrayOutputStream()
        vp8x.write(0x02 or 0x10)
        vp8x.write(byteArrayOf(0, 0, 0))
        vp8x.write(uint24(width - 1))
        vp8x.write(uint24(height - 1))
        writeChunk(body, "VP8X", vp8x.toByteArray())

        // ANIM: cor de fundo transparente, repetição infinita
        writeChunk(body, "ANIM", byteArrayOf(0, 0, 0, 0, 0, 0))

        for (frame in frames) {
            val anmf = ByteArrayOutputStream()
            anmf.write(uint24(0)) // x
            anmf.write(uint24(0)) // y
            anmf.write(uint24(width - 1))
            anmf.write(uint24(height - 1))
            anmf.write(uint24(frameDurationMs))
            anmf.write(0x02) // sem mistura com o quadro anterior
            for ((id, data) in imageChunks(frame)) writeChunk(anmf, id, data)
            writeChunk(body, "ANMF", anmf.toByteArray())
        }

        val bytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(uint32(bytes.size))
        out.write(bytes)
        return out.toByteArray()
    }

    /** Lê um WebP animado e retorna (quantidade de quadros, duração total em ms). */
    fun readInfo(webp: ByteArray): Pair<Int, Int> {
        var frames = 0
        var durationMs = 0
        var pos = 12
        while (pos + 8 <= webp.size) {
            val id = String(webp, pos, 4, Charsets.US_ASCII)
            val size = readInt(webp, pos + 4, 4)
            if (id == "ANMF" && pos + 8 + 15 <= webp.size) {
                frames++
                durationMs += readInt(webp, pos + 8 + 12, 3)
            }
            pos += 8 + size + (size and 1)
        }
        return frames to durationMs
    }

    private fun readInt(bytes: ByteArray, pos: Int, len: Int): Int {
        var v = 0
        for (i in 0 until len) v = v or ((bytes[pos + i].toInt() and 0xFF) shl (8 * i))
        return v
    }

    /** Extrai os chunks de imagem (ALPH, VP8, VP8L) de um WebP estático. */
    private fun imageChunks(webp: ByteArray): List<Pair<String, ByteArray>> {
        val chunks = mutableListOf<Pair<String, ByteArray>>()
        var pos = 12 // pula "RIFF" + tamanho + "WEBP"
        while (pos + 8 <= webp.size) {
            val id = String(webp, pos, 4, Charsets.US_ASCII)
            val size = (webp[pos + 4].toInt() and 0xFF) or
                ((webp[pos + 5].toInt() and 0xFF) shl 8) or
                ((webp[pos + 6].toInt() and 0xFF) shl 16) or
                ((webp[pos + 7].toInt() and 0xFF) shl 24)
            if (id == "ALPH" || id == "VP8 " || id == "VP8L") {
                chunks += id to webp.copyOfRange(pos + 8, pos + 8 + size)
            }
            pos += 8 + size + (size and 1)
        }
        return chunks
    }

    private fun writeChunk(out: ByteArrayOutputStream, id: String, data: ByteArray) {
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write(uint32(data.size))
        out.write(data)
        if (data.size % 2 == 1) out.write(0)
    }

    private fun uint24(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())

    private fun uint32(v: Int) =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
