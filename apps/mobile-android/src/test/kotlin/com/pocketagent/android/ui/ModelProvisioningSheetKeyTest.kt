package com.pocketagent.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ModelProvisioningSheetKeyTest {
    @Test
    fun `download and installed keys are distinct for same model version`() {
        val modelId = "qwen3.5-0.8b-q4"
        val version = "1.0.0"

        val downloadKey = downloadVersionItemKey(modelId = modelId, version = version)
        val installedKey = installedVersionItemKey(modelId = modelId, version = version)
        val importButtonTag = modelLibraryImportButtonTag(modelId = modelId, version = version)
        val modelContentTag = modelLibraryModelContentTag(modelId = modelId, version = version)

        assertNotEquals(downloadKey, installedKey)
        assertEquals("download:$modelId:$version", downloadKey)
        assertEquals("installed:$modelId:$version", installedKey)
        assertEquals("model_library_import_${modelId}_${version}", importButtonTag)
        assertEquals("model_library_model_content_${modelId}_${version}", modelContentTag)
    }
}
