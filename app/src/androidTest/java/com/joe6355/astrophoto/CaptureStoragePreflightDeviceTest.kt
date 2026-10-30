package com.joe6355.astrophoto

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Local fake provider only: no permission changes, full disk, or writes to real sessions. */
@SdkSuppress(minSdkVersion = 30)
class CaptureStoragePreflightDeviceTest {
    @Test fun unreadableFrameMetadataKeepsFreeSpaceAndUsesDefaultEstimate() = runBlocking {
        val context = auditContext()
        for (failure in listOf(SecurityException("JPEG metadata denied"), IllegalStateException("MediaStore unavailable"))) {
            val provider = UnreadableFrameSizes(failure)
            val checker = StorageSpaceChecker(contextWithProvider(context, provider))
            val info = checker.estimate(ownSession(), StorageCaptureFormat.JPEG, 3)
            assertEquals(1, provider.queries.get())
            assertNotNull("A metadata failure must not discard StatFs free space", info.availableBytes)
            assertTrue(info.availableBytes!! > 0L)
            assertEquals(5L * 1024L * 1024L, info.averageFrameBytes)
            assertEquals(18L * 1024L * 1024L, info.estimatedBytes)
        }
    }

    @Test fun cancellationDuringMetadataQueryStillPropagates() {
        val context = auditContext()
        val failure = CancellationException("QA cancelled preflight")
        val provider = UnreadableFrameSizes(failure)
        val error = assertThrows(CancellationException::class.java) {
            runBlocking {
                StorageSpaceChecker(contextWithProvider(context, provider))
                    .estimate(ownSession(), StorageCaptureFormat.JPEG, 3)
            }
        }
        assertSame(failure, error)
        assertEquals(1, provider.queries.get())
    }

    private fun auditContext(): Context {
        assumeTrue(InstrumentationRegistry.getArguments().getString("preflightStorageAudit") == "true")
        return InstrumentationRegistry.getInstrumentation().targetContext.also {
            require(it.packageName == "com.joe6355.astrophoto.qa20261017")
        }
    }

    private fun ownSession() = ShootingSession("QA metadata failure", "QA_storage_provider_fixture", 1, "not a real session")

    private fun contextWithProvider(context: Context, provider: ContentProvider): Context {
        provider.attachInfo(context, ProviderInfo().apply {
            authority = "media"
            applicationInfo = context.applicationInfo
        })
        val resolver = ContentResolver.wrap(provider)
        return object : ContextWrapper(context) {
            override fun getContentResolver(): ContentResolver = resolver
        }
    }
}

private class UnreadableFrameSizes(private val failure: RuntimeException) : ContentProvider() {
    val queries = AtomicInteger()
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        queries.incrementAndGet()
        throw failure
    }
    override fun getType(uri: Uri) = "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri = error("Read-only fake provider")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        error("Read-only fake provider")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        error("Read-only fake provider")
}
