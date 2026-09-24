package com.creativeidiot.transcriptgerman

import android.content.Context
import com.creativeidiot.transcriptgerman.gemini.GeminiApiKeyStore
import com.creativeidiot.transcriptgerman.model.ModelRepository
import com.creativeidiot.transcriptgerman.session.CaptionSessionStore
import com.creativeidiot.transcriptgerman.translation.BergamotModelRepository
import java.io.File
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient

class AppContainer(
    context: Context,
    filesDir: File,
) {
    private val modelDownloadMutex = Mutex()

    val modelRepository = ModelRepository(
        filesDir = filesDir,
        downloadMutex = modelDownloadMutex,
    )
    val bergamotModelRepository = BergamotModelRepository(
        filesDir = filesDir,
        downloadMutex = modelDownloadMutex,
    )
    val geminiApiKeyStore = GeminiApiKeyStore(context)
    val geminiHttpClient = OkHttpClient()
    val sessionStore = CaptionSessionStore()
}
