package ru.robotpost.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.Locale

/** Writes a complete media package through Android's Storage Access Framework. */
object ArchiveExporter {
    private fun safeName(value: String, fallback: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ").trim().trim('.').take(100).ifBlank { fallback }

    fun validDate(value: String): Boolean {
        if (!Regex("\\d{2}\\.\\d{2}\\.\\d{2}").matches(value)) return false
        return try {
            val parser = SimpleDateFormat("dd.MM.yy", Locale.ROOT)
            parser.isLenient = false
            parser.parse(value)?.let { parser.format(it) == value } == true
        } catch (_: Exception) { false }
    }

    private fun folder(resolver: ContentResolver, parent: Uri, name: String): Uri {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val display = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(display) == name && cursor.getString(mime) == DocumentsContract.Document.MIME_TYPE_DIR)
                    return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(id))
            }
        }
        return DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("Не удалось создать папку: $name")
    }

    private fun exists(resolver: ContentResolver, parent: Uri, name: String): Boolean {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) if (cursor.getString(index) == name) return true
        }
        return false
    }

    private fun copy(resolver: ContentResolver, parent: Uri, name: String, mime: String, source: Uri): Uri {
        val target = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: error("Не удалось создать $name")
        try {
            resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(target, "w")?.use { output ->
                    input.copyTo(output, 64 * 1024)
                } ?: error("Нет доступа для записи $name")
            } ?: error("Не удалось прочитать $name")
            return target
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw e
        }
    }

    fun export(
        context: Context,
        tree: Uri,
        channel: String,
        title: String,
        date: String,
        description: String,
        video: Uri?,
        audio: Uri?,
        audioFileName: String = "audio.mp3",
        includeDescription: Boolean = true,
        videoOnly: Boolean = false
    ): String {
        require(validDate(date)) { "Дата должна быть в формате ДД.ММ.ГГ" }
        require(video != null || audio != null) { "Выбери видео или аудио" }
        val resolver = context.contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val app = folder(resolver, root, "RobotPost")
        val channelFolder = folder(resolver, app, safeName(channel, "Без названия канала"))
        val base = "$date ${safeName(title, "Без названия")}"
        var name = base
        var suffix = 2
        while (exists(resolver, channelFolder, name)) {
            name = "$base ($suffix)"
            suffix++
            require(suffix < 10000) { "Слишком много папок с таким названием" }
        }
        val packageFolder = DocumentsContract.createDocument(resolver, channelFolder, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("Не удалось создать папку видео")
        try {
            if (video != null) copy(resolver, packageFolder, "video.mp4", "video/mp4", video)
            if (audio != null && !videoOnly) copy(resolver, packageFolder, audioFileName, if (audioFileName.endsWith(".m4a")) "audio/mp4" else "audio/mpeg", audio)
            if (includeDescription && !videoOnly) {
            val txt = DocumentsContract.createDocument(resolver, packageFolder, "text/plain", "description.txt")
                ?: error("Не удалось создать описание")
            resolver.openOutputStream(txt, "w")?.use { it.write(description.toByteArray(Charsets.UTF_8)) }
                ?: error("Не удалось записать описание")
            }
            return name
        } catch (e: Exception) {
            // Preserve partially written package for diagnosis rather than deleting user's files.
            throw e
        }
    }
}
