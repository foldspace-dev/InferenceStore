package dev.mattramotar.inferencestore.core

import kotlin.test.Test
import kotlin.test.assertEquals

class InferenceStoreTest {
    @Test
    fun coreModuleExposesDevVersion() {
        assertEquals("0.1.0-dev", InferenceStore.VERSION)
    }
}
