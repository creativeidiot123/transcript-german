package com.creativeidiot.transcriptgerman.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AsrBackendTest {
    @Test
    fun wireValues_roundTripAndRejectUnknownValues() {
        AsrBackend.entries.forEach { backend ->
            assertEquals(backend, AsrBackend.fromWireValue(backend.wireValue))
        }

        assertNull(AsrBackend.fromWireValue(null))
        assertNull(AsrBackend.fromWireValue(""))
        assertNull(AsrBackend.fromWireValue("other"))
    }

    @Test
    fun eachBackendMapsToItsOwnPinnedBundle() {
        val primeline = modelBundleFor(AsrBackend.PRIMELINE)
        val nemotron = modelBundleFor(AsrBackend.NEMOTRON)

        assertEquals(PrimelineModelSpec.REVISION, primeline.revision)
        assertEquals(NemotronModelSpec.REVISION, nemotron.revision)
        assertEquals(false, primeline.directoryName == nemotron.directoryName)
    }
}
