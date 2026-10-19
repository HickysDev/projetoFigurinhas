package com.figurinhas.app

import android.content.Context
import java.io.File

/**
 * Guarda os pacotes de figurinhas na memória interna do app.
 * O WhatsApp exige que um pacote seja só de figurinhas estáticas ou só animadas,
 * por isso existem dois pacotes fixos.
 */
enum class Pack(val id: String, val title: String, val animated: Boolean) {
    STATIC("estaticas", "Minhas Figurinhas", false),
    ANIMATED("animadas", "Minhas Figurinhas Animadas", true);

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id }
    }
}

object StickerStore {
    const val MIN_STICKERS = 3
    const val MAX_STICKERS = 30
    const val TRAY_FILE = "tray.png"
    const val PUBLISHER = "Hickanda 🐱❤️"
    const val EMOJIS = "🐱,❤️"

    fun packDir(context: Context, pack: Pack): File =
        File(context.filesDir, "packs/${pack.id}").apply { mkdirs() }

    fun stickers(context: Context, pack: Pack): List<File> =
        packDir(context, pack).listFiles { f -> f.name.endsWith(".webp") }
            ?.sortedBy { it.name } ?: emptyList()

    fun newStickerFile(context: Context, pack: Pack): File {
        var time = System.currentTimeMillis()
        // Garante nome único mesmo convertendo várias fotos no mesmo milissegundo
        while (File(packDir(context, pack), "$time.webp").exists()) time++
        return File(packDir(context, pack), "$time.webp")
    }

    fun trayFile(context: Context, pack: Pack) = File(packDir(context, pack), TRAY_FILE)

    /** Muda sempre que o pacote é alterado, para o WhatsApp recarregar as imagens. */
    fun dataVersion(context: Context, pack: Pack): Int =
        prefs(context).getInt("version_${pack.id}", 1)

    fun bumpVersion(context: Context, pack: Pack) {
        prefs(context).edit().putInt("version_${pack.id}", dataVersion(context, pack) + 1).apply()
    }

    fun delete(context: Context, pack: Pack, file: File) {
        file.delete()
        val remaining = stickers(context, pack)
        if (remaining.isEmpty()) trayFile(context, pack).delete()
        else StickerMaker.makeTrayIcon(remaining.first(), trayFile(context, pack))
        bumpVersion(context, pack)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("stickers", Context.MODE_PRIVATE)
}
