package com.figurinhas.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Interface que o WhatsApp usa para ler os pacotes.
 * Segue o contrato do app de exemplo oficial: https://github.com/WhatsApp/stickers
 */
class StickerContentProvider : ContentProvider() {

    companion object {
        private const val METADATA = "metadata"
        private const val STICKERS = "stickers"
        private const val STICKERS_ASSET = "stickers_asset"

        private const val ALL_PACKS = 1
        private const val ONE_PACK = 2
        private const val PACK_STICKERS = 3
        private const val STICKER_FILE = 4

        fun authority(packageName: String) = "$packageName.stickercontentprovider"
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH)
    private val authority by lazy { authority(context!!.packageName) }

    override fun onCreate(): Boolean {
        matcher.addURI(authority, METADATA, ALL_PACKS)
        matcher.addURI(authority, "$METADATA/*", ONE_PACK)
        matcher.addURI(authority, "$STICKERS/*", PACK_STICKERS)
        matcher.addURI(authority, "$STICKERS_ASSET/*/*", STICKER_FILE)
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor = when (matcher.match(uri)) {
        ALL_PACKS -> packsCursor(Pack.entries)
        ONE_PACK -> packsCursor(listOfNotNull(Pack.fromId(uri.lastPathSegment!!)))
        PACK_STICKERS -> stickersCursor(Pack.fromId(uri.lastPathSegment!!))
        else -> throw IllegalArgumentException("URI desconhecida: $uri")
    }

    private fun packsCursor(packs: List<Pack>): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher",
                "sticker_pack_icon", "android_play_store_link", "ios_app_download_link",
                "sticker_pack_publisher_email", "sticker_pack_publisher_website",
                "sticker_pack_privacy_policy_website", "sticker_pack_license_agreement_website",
                "image_data_version", "whatsapp_will_not_cache_stickers", "animated_sticker_pack"
            )
        )
        for (pack in packs) {
            cursor.addRow(
                arrayOf<Any>(
                    pack.id, pack.title, StickerStore.PUBLISHER, StickerStore.TRAY_FILE,
                    "", "", "", "", "", "",
                    StickerStore.dataVersion(context!!, pack).toString(),
                    0, if (pack.animated) 1 else 0
                )
            )
        }
        return cursor
    }

    private fun stickersCursor(pack: Pack?): Cursor {
        val cursor = MatrixCursor(arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text"))
        if (pack != null) {
            for (file in StickerStore.stickers(context!!, pack)) {
                cursor.addRow(arrayOf(file.name, StickerStore.EMOJIS, "Figurinha"))
            }
        }
        return cursor
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val pfd = openFile(uri, mode) ?: return null
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = resolveFile(uri) ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun resolveFile(uri: Uri): File? {
        if (matcher.match(uri) != STICKER_FILE) return null
        val segments = uri.pathSegments
        val pack = Pack.fromId(segments[segments.size - 2]) ?: return null
        val name = segments.last()
        // Impede acesso a arquivos fora da pasta do pacote
        if (name.contains('/') || name.contains("..")) return null
        return File(StickerStore.packDir(context!!, pack), name).takeIf { it.exists() }
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        ALL_PACKS -> "vnd.android.cursor.dir/vnd.$authority.$METADATA"
        ONE_PACK -> "vnd.android.cursor.item/vnd.$authority.$METADATA"
        PACK_STICKERS -> "vnd.android.cursor.dir/vnd.$authority.$STICKERS"
        STICKER_FILE -> if (uri.lastPathSegment!!.endsWith(".png")) "image/png" else "image/webp"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException()
}
