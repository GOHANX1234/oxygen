package com.oxygens.app.stub

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Base for the manifest-declared stub ContentProvider pool (plan §4.2).
 *
 * Guest ContentProvider delegation is Phase 5 work and not implemented yet.
 * `onCreate` must still return true — the system calls it during app startup for
 * every declared provider regardless of whether a guest is bound to it yet, and
 * failing it would break process startup entirely. Every actual data operation fails
 * loudly instead of silently returning empty/success results, so a caller can't
 * mistake "not implemented" for "no data" (plan §9).
 */
open class StubProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun notImplemented(): Nothing = throw UnsupportedOperationException(
        "Guest ContentProvider delegation is not implemented (Phase 5, plan §4.2/§9)."
    )

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = notImplemented()
    override fun getType(uri: Uri): String? = notImplemented()
    override fun insert(uri: Uri, values: ContentValues?): Uri? = notImplemented()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = notImplemented()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = notImplemented()
}

class StubProvider0 : StubProvider()
class StubProvider1 : StubProvider()
