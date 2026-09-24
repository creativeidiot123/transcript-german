package com.creativeidiot.transcriptgerman.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiStartReadinessTest {
    @Test
    fun configuredStableCredential_allowsStart() {
        assertTrue(
            isGeminiStartAllowed(
                configured = true,
                mutationInProgress = false,
            ),
        )
    }

    @Test
    fun credentialMutation_blocksStartEvenWhenOldKeyIsConfigured() {
        assertFalse(
            isGeminiStartAllowed(
                configured = true,
                mutationInProgress = true,
            ),
        )
    }

    @Test
    fun missingCredential_blocksStart() {
        assertFalse(
            isGeminiStartAllowed(
                configured = false,
                mutationInProgress = false,
            ),
        )
    }
}
