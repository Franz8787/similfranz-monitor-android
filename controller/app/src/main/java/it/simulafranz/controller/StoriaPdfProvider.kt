// ANDROID (storia sim 22/11): nessun file corrispondente sull'iPad.
// Senza AndroidX non c'è FileProvider: questo piccolo ContentProvider serve in SOLA LETTURA i PDF della cartella
// /sdcard/Documents/Storia Sim, con URI content://it.simulafranz.controller.storia/pdf/<nome del file>.
// Non esportato: le app che ricevono un PDF condiviso lo leggono solo con il permesso temporaneo sull'URI (grantUriPermissions).
package it.simulafranz.controller

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class StoriaPdfProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    /** solo un file .pdf direttamente dentro la cartella (niente percorsi, niente «..») */
    private fun fileOf(uri: Uri): File? {
        val seg = uri.pathSegments
        if (seg.size != 2 || seg[0] != "pdf") return null
        val name = seg[1]
        if (name.contains('/') || name.contains('\\') || name.startsWith(".") || !name.endsWith(".pdf", true)) return null
        val dir = StoriaPdfDir()
        val f = File(dir, name)
        return try { if (f.canonicalFile.parentFile == dir.canonicalFile && f.isFile) f else null } catch (e: Exception) { null }
    }

    override fun getType(uri: Uri): String? = if (fileOf(uri) != null) "application/pdf" else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode.contains('w') || mode.contains('t') || mode.contains('a')) throw SecurityException("sola lettura")
        val f = fileOf(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = fileOf(uri) ?: return null
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else f.length() }.toTypedArray())
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("sola lettura")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("sola lettura")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("sola lettura")
}
