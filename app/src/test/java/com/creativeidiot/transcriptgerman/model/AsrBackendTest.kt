package com.creativeidiot.transcriptgerman.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrBackendTest {
    @Test
    fun defaultBackend_isNemotron() {
        assertEquals(AsrBackend.NEMOTRON, DEFAULT_ASR_BACKEND)
    }
}
