package com.tom.rv2ide.fragments.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.editor.EditorHandlerActivity
import java.io.File

internal object AIAssistantLocalLinkSupport {
    private const val apkMimeType = "application/vnd.android.package-archive"

    fun rewriteLocalLinks(spanned: Spanned): Spanned {
        val urlSpans = spanned.getSpans(0, spanned.length, URLSpan::class.java)
        if (urlSpans.isEmpty()) {
            return spanned
        }

        var builder: SpannableStringBuilder? = null
        urlSpans.forEach { span ->
            val localFile = resolveLocalFile(span.url) ?: return@forEach
            val mutableText = builder ?: SpannableStringBuilder(spanned).also { builder = it }
            val start = mutableText.getSpanStart(span)
            val end = mutableText.getSpanEnd(span)
            val flags = mutableText.getSpanFlags(span)
            if (start < 0 || end <= start) {
                return@forEach
            }
            mutableText.removeSpan(span)
            mutableText.setSpan(
                LocalFileClickableSpan(originalSpan = span, localFile = localFile),
                start,
                end,
                flags
            )
        }
        return builder ?: spanned
    }

    private fun resolveLocalFile(rawUrl: String?): File? {
        val normalizedPath = normalizeLocalPath(rawUrl) ?: return null
        return File(normalizedPath)
    }

    private fun normalizeLocalPath(rawUrl: String?): String? {
        val candidate = rawUrl?.trim().orEmpty()
        if (candidate.isBlank()) {
            return null
        }

        val directPath = candidate.substringBefore('?').substringBefore('#')
        if (directPath.isSupportedLocalPath()) {
            return directPath
        }

        val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        val normalized = when {
            uri.scheme.equals("file", ignoreCase = true) -> uri.path
            uri.scheme.equals("http", ignoreCase = true) ||
                uri.scheme.equals("https", ignoreCase = true) -> {
                if (uri.host.isNullOrBlank()) {
                    uri.path
                } else {
                    null
                }
            }
            else -> null
        }?.substringBefore('?')?.substringBefore('#')

        return normalized?.takeIf { it.isSupportedLocalPath() }
    }

    private fun String.isSupportedLocalPath(): Boolean {
        val isLocalStoragePath = startsWith("/storage/", ignoreCase = true) ||
            startsWith("/sdcard/", ignoreCase = true) ||
            startsWith("/mnt/sdcard/", ignoreCase = true)
        if (!isLocalStoragePath) {
            return false
        }
        return !endsWith("/")
    }

    private class LocalFileClickableSpan(
        private val originalSpan: URLSpan,
        private val localFile: File
    ) : ClickableSpan() {

        override fun onClick(widget: View) {
            openLocalFile(widget.context, localFile)
        }

        override fun updateDrawState(ds: TextPaint) {
            originalSpan.updateDrawState(ds)
        }
    }

    private fun openLocalFile(
        context: Context,
        localFile: File
    ) {
        if (!localFile.exists() || !localFile.isFile) {
            Toast.makeText(
                context,
                context.getString(R.string.ai_assistant_local_file_not_found, localFile.name),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (localFile.extension.equals("apk", ignoreCase = true)) {
            launchApkInstaller(context, localFile)
            return
        }

        val editorActivity = context.findEditorHandlerActivity()
        if (editorActivity == null) {
            Toast.makeText(
                context,
                localFile.absolutePath,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        editorActivity.openFile(localFile)
    }

    private fun launchApkInstaller(
        context: Context,
        apkFile: File
    ) {
        if (!apkFile.exists() || !apkFile.isFile) {
            Toast.makeText(
                context,
                context.getString(R.string.ai_assistant_apk_not_found, apkFile.name),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(permissionIntent) }
            Toast.makeText(
                context,
                context.getString(R.string.ai_assistant_enable_unknown_app_installs),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val authority = "${context.packageName}.providers.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(apkUri, apkMimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            context.startActivity(installIntent)
        } catch (_: ActivityNotFoundException) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, apkMimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallbackIntent)
        } catch (error: Throwable) {
            Toast.makeText(
                context,
                error.message ?: context.getString(R.string.ai_assistant_unable_to_open_apk_installer),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private tailrec fun Context.findEditorHandlerActivity(): EditorHandlerActivity? {
        return when (this) {
            is EditorHandlerActivity -> this
            is ContextWrapper -> baseContext.findEditorHandlerActivity()
            else -> null
        }
    }
}
