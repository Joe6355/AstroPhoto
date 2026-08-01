package com.example.astrophoto

import java.io.File

internal object UrbanWindow30ReplayFixture {
    val directory: File by lazy {
        val resource = requireNotNull(
            UrbanWindow30ReplayFixture::class.java.classLoader
                ?.getResource("jpeg-stage6/urban-window-30/manifest.properties")
        )
        requireNotNull(File(resource.toURI()).parentFile)
    }

    val fixture: Stage6RegressionFixture by lazy {
        Stage6RegressionFixtureLoader.load(directory)
    }
}
