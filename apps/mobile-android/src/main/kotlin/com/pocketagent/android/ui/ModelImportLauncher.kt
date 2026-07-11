package com.pocketagent.android.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.pocketagent.android.R

@Composable
internal fun rememberModelImportLauncher(
    context: Context,
    appViewModel: ChatAppViewModel,
    provisioningViewModel: ModelProvisioningViewModel,
): () -> Unit {
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request = appViewModel.consumeModelImportRequest() ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            provisioningViewModel.setStatusMessage(context.getString(R.string.ui_model_import_cancelled))
            return@rememberLauncherForActivityResult
        }
        val documentDescription = selectedDocumentDescription(uri)
        provisioningViewModel.setStatusMessage(
            if (documentDescription == null) {
                context.getString(R.string.ui_model_import_in_progress)
            } else {
                context.getString(R.string.ui_model_import_in_progress_named, documentDescription)
            },
        )
        if (!provisioningViewModel.startModelImport(
                operationId = request.operationId,
                modelId = request.modelId,
                sourceUri = uri,
                documentDescription = documentDescription,
            )
        ) {
            provisioningViewModel.setStatusMessage(
                context.getString(R.string.ui_model_operation_already_in_progress),
            )
        }
    }
    return { modelPicker.launch(arrayOf("*/*")) }
}

internal fun selectedDocumentDescription(uri: Uri): String? {
    val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
    return normalizedDocumentDescription(
        documentId = documentId,
        fallbackPathSegment = uri.lastPathSegment,
    )
}

internal fun normalizedDocumentDescription(
    documentId: String?,
    fallbackPathSegment: String?,
): String? {
    documentId?.safeGgufDocumentDescription()?.let { return it }
    return fallbackPathSegment?.safeGgufDocumentDescription()
}

private fun String.safeGgufDocumentDescription(): String? {
    val pathSegment = substringAfterLast('/')
    val fileName = pathSegment.withoutKnownDocumentVolumePrefix()
        .withoutUnsafeDocumentCharacters()
        .replace(DOCUMENT_WHITESPACE_REGEX, " ")
        .trim()
        .takeIf { candidate ->
            candidate.isNotBlank() && candidate.endsWith(GGUF_FILE_SUFFIX, ignoreCase = true)
        }
        ?: return null
    val codePointCount = fileName.codePointCount(0, fileName.length)
    return if (codePointCount <= MAX_DOCUMENT_DESCRIPTION_CODE_POINTS) {
        fileName
    } else {
        val stem = fileName.dropLast(GGUF_FILE_SUFFIX.length)
        val preservedCodePoints = MAX_DOCUMENT_DESCRIPTION_CODE_POINTS -
            GGUF_FILE_SUFFIX.length -
            1
        val endIndex = stem.offsetByCodePoints(0, preservedCodePoints)
        stem.substring(0, endIndex) + "…" + GGUF_FILE_SUFFIX
    }
}

private fun String.withoutKnownDocumentVolumePrefix(): String {
    return replaceFirst(DOCUMENT_VOLUME_PREFIX_REGEX, "")
}

private fun String.withoutUnsafeDocumentCharacters(): String {
    return buildString(length) {
        var index = 0
        while (index < this@withoutUnsafeDocumentCharacters.length) {
            val codePoint = this@withoutUnsafeDocumentCharacters.codePointAt(index)
            val isUnsafe = Character.isISOControl(codePoint) ||
                Character.getType(codePoint) == Character.FORMAT.toInt()
            val isWhitespace = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
            appendCodePoint(if (isUnsafe || isWhitespace) ' '.code else codePoint)
            index += Character.charCount(codePoint)
        }
    }
}

private const val GGUF_FILE_SUFFIX = ".gguf"
private const val MAX_DOCUMENT_DESCRIPTION_CODE_POINTS = 120
private val DOCUMENT_WHITESPACE_REGEX = Regex(" +")
private val DOCUMENT_VOLUME_PREFIX_REGEX = Regex(
    pattern = "^(?i:primary|home|raw|[0-9a-f]{4}-[0-9a-f]{4}):",
)
