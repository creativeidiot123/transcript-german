package com.creativeidiot.transcriptgerman.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrBackendTest {
    @Test
    fun wireValues_roundTripAndRejectUnknownValues() {
        AsrBackend.entries.forEach { backend ->
            assertEquals(backend, AsrBackend.fromWireValue(backend.wireValue))
        }

        assertNull(AsrBackend.fromWireValue(null))
        assertNull(AsrBackend.fromWireValue(""))
        assertNull(AsrBackend.fromWireValue("PRIMELINE"))
        assertNull(AsrBackend.fromWireValue("other"))
    }

    @Test
    fun onlyGemini_skipsLocalModelInstallation() {
        assertTrue(AsrBackend.PRIMELINE.requiresLocalModel)
        assertTrue(AsrBackend.NEMOTRON.requiresLocalModel)
        assertTrue(AsrBackend.CANARY.requiresLocalModel)
        assertFalse(AsrBackend.GEMINI.requiresLocalModel)
    }
}
