package com.example.dailyaudionews

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

object FileDownloader {

    private const val TAG = "FileDownloader"

    /**
     * Downloads the meta file and returns a Pair:
     *  - First: true if download succeeded, false otherwise.
     *  - Second: the server’s Last-Modified timestamp (in millis), or null if unavailable.
     */
    fun downloadMetaFile(
        context: Context,
        metaFileUrl: String,
        metaFileName: String
    ): Pair<Boolean, Long?> {
        val localMetaFile = File(context.filesDir, metaFileName)
        val tempMetaFile = File(context.cacheDir, "temp_$metaFileName")

        return try {
            val url = URL(metaFileUrl)
            val httpsConn = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                doInput = true
            }
            httpsConn.connect()
            if (httpsConn.responseCode != HttpsURLConnection.HTTP_OK) {
                Log.e(TAG, "Meta file: Server returned HTTP ${httpsConn.responseCode}")
                httpsConn.disconnect()
                return Pair(false, null)
            }

            // Capture the server's Last-Modified header.
            val serverLastModified = httpsConn.getHeaderFieldDate("Last-Modified", 0L)

            httpsConn.inputStream.use { inputStream ->
                FileOutputStream(tempMetaFile).use { outputStream ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            }
            httpsConn.disconnect()

            // Overwrite local meta file.
            tempMetaFile.copyTo(localMetaFile, overwrite = true)
            tempMetaFile.delete()

            Pair(true, serverLastModified)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading meta file: ${e.message}", e)
            Pair(false, null)
        }
    }

    /**
     * Downloads the main file (MP3 or APK).
     * Overwrites local copy. Returns true if successful.
     */
    fun downloadMainFile(
        context: Context,
        fileUrl: String,
        destinationFileName: String
    ): Boolean {
        val localFile = File(context.filesDir, destinationFileName)
        val tempFile = File(context.cacheDir, "temp_$destinationFileName")

        return try {
            val url = URL(fileUrl)
            val httpsConn = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                doInput = true
            }
            httpsConn.connect()
            if (httpsConn.responseCode != HttpsURLConnection.HTTP_OK) {
                Log.e(TAG, "Main file: Server returned HTTP ${httpsConn.responseCode}")
                httpsConn.disconnect()
                return false
            }
            httpsConn.inputStream.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            }
            httpsConn.disconnect()

            tempFile.copyTo(localFile, overwrite = true)
            tempFile.delete()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading file: ${e.message}", e)
            false
        }
    }
}
