package com.nervesparks.iris.docs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

object DocumentUriPermission {
    private const val TAG = "DocumentUriPermission"

    fun persistReadPermission(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            true
        } catch (t: Throwable) {
            Log.w(TAG, "persistReadPermission failed uri=$uri", t)
            false
        }
    }
}
