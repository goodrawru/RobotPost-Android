package ru.robotpost.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Resolves previously exported packages using persisted SAF tree permission. */
object ArchiveBrowser {
    private fun children(context: Context, parent: Uri): List<Pair<String, Uri>> {
        val resolver = context.contentResolver
        val tree = DocumentsContract.getTreeDocumentId(parent)
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val results = mutableListOf<Pair<String, Uri>>()
        resolver.query(childUri, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        ), null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                results.add(cursor.getString(name) to
                    DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(id)))
            }
        }
        return results
    }

    /** Finds a package by its unique exported folder name across channels. */
    fun files(context: Context, tree: Uri, packageName: String): Map<String, Uri> {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val app = children(context, root).firstOrNull { it.first == "RobotPost" }?.second
            ?: return emptyMap()
        for ((_, channelUri) in children(context, app)) {
            val target = children(context, channelUri).firstOrNull { it.first == packageName }?.second
            if (target != null) return children(context, target).toMap()
        }
        return emptyMap()
    }
}
