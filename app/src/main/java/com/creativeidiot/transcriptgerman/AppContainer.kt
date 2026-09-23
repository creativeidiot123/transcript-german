package com.creativeidiot.transcriptgerman

import com.creativeidiot.transcriptgerman.model.ModelRepository
import com.creativeidiot.transcriptgerman.session.CaptionSessionStore
import com.creativeidiot.transcriptgerman.translation.BergamotModelRepository
import java.io.File
import kotlinx.coroutines.sync.Mutex

class AppContainer(filesDir: File) {
    private val modelDownloadMutex = Mutex()

    val modelRepository = ModelRepository(
        filesDir = filesDir,
        downloadMutex = modelDownloadMutex,
    )
    val bergamotModelRepository = BergamotModelRepository(
        filesDir = filesDir,
        downloadMutex = modelDownloadMutex,
    )
    val sessionStore = CaptionSessionStore()
}
