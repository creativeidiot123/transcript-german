package com.creativeidiot.transcriptgerman

import com.creativeidiot.transcriptgerman.model.ModelRepository
import com.creativeidiot.transcriptgerman.session.CaptionSessionStore
import java.io.File

class AppContainer(filesDir: File) {
    val modelRepository = ModelRepository(filesDir)
    val sessionStore = CaptionSessionStore()
}
