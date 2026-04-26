package com.pocketagent.android.ui

internal fun launchSafeSingleImagePaths(
    imagePaths: List<String>,
    legacyImagePath: String? = null,
): List<String> {
    val firstImage = (imagePaths + listOfNotNull(legacyImagePath))
        .asSequence()
        .map(String::trim)
        .firstOrNull { it.isNotBlank() }
        ?: return emptyList()
    return listOf(firstImage)
}

