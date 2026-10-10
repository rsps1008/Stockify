package com.rsps1008.stockify

import com.google.api.client.http.GenericUrl
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.services.drive.Drive
import com.rsps1008.stockify.data.queryGoogleDriveBackupModifiedTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class GoogleDriveBackupModifiedTimeTest {
    private fun createDrive(json: String): Pair<Drive, MockHttpTransport> {
        val transport = MockHttpTransport.Builder()
            .setLowLevelHttpResponse(MockLowLevelHttpResponse().setContentType("application/json").setContent(json))
            .build()
        val drive = Drive.Builder(transport, GsonFactory(), null).setApplicationName("Stockify test").build()
        return drive to transport
    }

    @Test
    fun bothDevicesReadTheSameCloudTimestampAndExcludeTrashedFiles() {
        val json = """{"files":[{"id":"backup-id","name":"$AUTO_CLOUD_BACKUP_FILE_NAME","modifiedTime":"2026-10-10T08:12:34.567Z"}]}"""
        val (deviceA, transport) = createDrive(json)
        val (deviceB, _) = createDrive(json)
        val expected = Instant.parse("2026-10-10T08:12:34.567Z").toEpochMilli()
        assertEquals(expected, queryGoogleDriveBackupModifiedTime(deviceA, AUTO_CLOUD_BACKUP_FILE_NAME))
        assertEquals(expected, queryGoogleDriveBackupModifiedTime(deviceB, AUTO_CLOUD_BACKUP_FILE_NAME))

        val url = GenericUrl(transport.lowLevelHttpRequest.url)
        assertEquals("name='$AUTO_CLOUD_BACKUP_FILE_NAME' and 'appDataFolder' in parents and trashed = false", url.getFirst("q"))
        assertEquals("files(id, name, modifiedTime)", url.getFirst("fields"))
        assertEquals("modifiedTime desc", url.getFirst("orderBy"))
        assertEquals("appDataFolder", url.getFirst("spaces"))
    }

    @Test
    fun noCloudFileReturnsNull() {
        val (drive, _) = createDrive("""{"files":[]}""")
        assertNull(queryGoogleDriveBackupModifiedTime(drive, AUTO_CLOUD_BACKUP_FILE_NAME))
    }

    @Test(expected = IllegalStateException::class)
    fun existingFileWithoutModifiedTimeDoesNotInventDeviceTimeOrReportNoFile() {
        val (drive, _) = createDrive("""{"files":[{"id":"backup-id"}]}""")
        queryGoogleDriveBackupModifiedTime(drive, AUTO_CLOUD_BACKUP_FILE_NAME)
    }
}
