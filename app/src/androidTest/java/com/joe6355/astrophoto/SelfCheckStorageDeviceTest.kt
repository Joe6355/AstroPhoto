package com.joe6355.astrophoto

import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class SelfCheckStorageDeviceTest {
    @Test fun storageAndExportProbesSucceedAndRemoveOnlyTheirTemporaryFiles() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("selfCheckAudit") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun probeFiles(): Set<Long> = buildSet {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("AstroPhoto_selfcheck_%"), null
            )!!.use { cursor -> while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        val before = probeFiles()
        val sessionBefore = ShootingSessionStore(context).load()
        repeat(2) {
            val report = SelfCheckRunner(context).run(cameraPermissionGranted = true, onUpdate = {})
            for (id in listOf(SelfCheckId.STORAGE_FOLDER, SelfCheckId.ZIP_EXPORT)) {
                val item = report.items.single { it.id == id }
                assertEquals(item.details, SelfCheckStatus.OK, item.status)
            }
            assertEquals("Temporary probe files must be removed", before, probeFiles())
            assertEquals("Active session must be unchanged", sessionBefore, ShootingSessionStore(context).load())
            android.util.Log.i("AstroPhotoDeviceQA", "QA_SELFCHECK_PASS run=${it + 1} storage=OK export=OK cleanup=OK")
        }
    }
}
