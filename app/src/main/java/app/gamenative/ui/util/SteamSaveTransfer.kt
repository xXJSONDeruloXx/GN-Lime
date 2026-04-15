package app.gamenative.ui.util

import android.content.Context
import android.net.Uri
import app.gamenative.R
import app.gamenative.service.SteamService
import java.io.IOException
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

object SteamSaveTransfer {
    private const val ARCHIVE_VERSION = 4
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val FILES_PREFIX = "files/"
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Serializable
    private data class SaveArchiveManifest(
        val version: Int = ARCHIVE_VERSION,
        val steamAppId: Int,
        val gameName: String,
        val exportedAt: Long,
        val files: List<String>,
    )

    suspend fun exportSaves(
        context: Context,
        steamAppId: Int,
        uri: Uri,
    ): Boolean {
        return try {
            val app = SteamService.getAppInfoOf(steamAppId)
                ?: throw IOException("Steam app not found")
            val saveRoot = withContext(Dispatchers.IO) { resolveSaveRoot(context, steamAppId) }
            val files = withContext(Dispatchers.IO) { findSaveFiles(saveRoot) }

            if (files.isEmpty()) {
                SnackbarManager.show(context.getString(R.string.steam_save_export_no_saves_found))
                return false
            }

            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    ZipOutputStream(outputStream.buffered()).use { zip ->
                        val relativeFiles = files.map { normalizeRelativePath(saveRoot.relativize(it).toString()) }
                        val manifest = SaveArchiveManifest(
                            steamAppId = steamAppId,
                            gameName = app.name,
                            exportedAt = System.currentTimeMillis(),
                            files = relativeFiles,
                        )

                        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                        zip.write(json.encodeToString(SaveArchiveManifest.serializer(), manifest).toByteArray())
                        zip.closeEntry()

                        files.zip(relativeFiles).forEach { (file, relativePath) ->
                            zip.putNextEntry(ZipEntry("$FILES_PREFIX$relativePath"))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                } ?: throw IOException("Unable to open destination")
            }

            SnackbarManager.show(context.getString(R.string.steam_save_export_success))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to export Steam saves for appId=$steamAppId")
            SnackbarManager.show(context.getString(R.string.steam_save_export_failed, e.message ?: "Unknown error"))
            false
        }
    }

    suspend fun importSaves(
        context: Context,
        steamAppId: Int,
        uri: Uri,
    ): Boolean {
        return try {
            val app = SteamService.getAppInfoOf(steamAppId)
                ?: throw IOException("Steam app not found")
            val archiveManifest = withContext(Dispatchers.IO) { readArchiveManifest(context, uri) }
            if (archiveManifest.steamAppId != steamAppId) {
                throw IOException("Archive is for Steam app ${archiveManifest.steamAppId}, expected $steamAppId")
            }
            if (archiveManifest.files.isEmpty()) {
                throw IOException("Archive does not declare any save files")
            }

            val importedFileCount = withContext(Dispatchers.IO) {
                val saveRoot = resolveSaveRoot(context, steamAppId)
                saveRoot.createDirectories()
                importArchive(context, uri, saveRoot, archiveManifest.files.toSet())
            }

            if (importedFileCount == 0) {
                throw IOException("No save files found in archive")
            }

            SnackbarManager.show(context.getString(R.string.steam_save_import_success))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to import Steam saves for appId=$steamAppId")
            SnackbarManager.show(context.getString(R.string.steam_save_import_failed, e.message ?: "Unknown error"))
            false
        }
    }

    private fun resolveSaveRoot(context: Context, steamAppId: Int): Path {
        val accountId = SteamService.userSteamId?.accountID?.toLong()
            ?: throw IOException("Steam not logged in")
        return Paths.get(
            app.gamenative.enums.PathType.SteamUserData.toAbsPath(context, steamAppId, accountId),
        )
    }

    private fun findSaveFiles(saveRoot: Path): List<Path> {
        if (!Files.exists(saveRoot)) return emptyList()
        Files.walk(saveRoot).use { paths ->
            return paths
                .filter { Files.isRegularFile(it) }
                .sorted()
                .toList()
        }
    }

    private fun readArchiveManifest(context: Context, uri: Uri): SaveArchiveManifest {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    try {
                        if (!entry.isDirectory && entry.name == MANIFEST_ENTRY) {
                            return json.decodeFromString(
                                SaveArchiveManifest.serializer(),
                                zip.readBytes().decodeToString(),
                            )
                        }
                    } finally {
                        zip.closeEntry()
                    }
                }
            }
        } ?: throw IOException("Unable to open archive")

        throw IOException("Missing save archive manifest")
    }

    private fun importArchive(
        context: Context,
        uri: Uri,
        saveRoot: Path,
        declaredFiles: Set<String>,
    ): Int {
        var importedFileCount = 0
        val seenFiles = mutableSetOf<String>()

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(inputStream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    try {
                        if (entry.isDirectory || entry.name == MANIFEST_ENTRY) {
                            continue
                        }
                        if (!entry.name.startsWith(FILES_PREFIX)) {
                            throw IOException("Unexpected archive entry: ${entry.name}")
                        }

                        val relativePath = normalizeRelativePath(entry.name.removePrefix(FILES_PREFIX))
                        if (relativePath !in declaredFiles) {
                            throw IOException("Archive entry missing from manifest: ${entry.name}")
                        }
                        if (writeArchiveEntry(zip, entry.name, saveRoot, relativePath)) {
                            seenFiles += relativePath
                            importedFileCount += 1
                        }
                    } finally {
                        zip.closeEntry()
                    }
                }
            }
        } ?: throw IOException("Unable to open archive")

        val missingFiles = declaredFiles - seenFiles
        if (missingFiles.isNotEmpty()) {
            throw IOException("Archive missing declared save files: ${missingFiles.joinToString()}")
        }

        return importedFileCount
    }

    private fun writeArchiveEntry(
        zip: ZipInputStream,
        entryName: String,
        saveRoot: Path,
        relativePath: String,
    ): Boolean {
        if (relativePath.isBlank()) return false

        val normalizedRoot = saveRoot.normalize()
        val destination = normalizedRoot.resolve(relativePath).normalize()
        if (!destination.startsWith(normalizedRoot)) {
            throw IOException("Archive entry escapes save root: $entryName")
        }

        destination.parent?.createDirectories()
        if (Files.isSymbolicLink(destination)) {
            throw IOException("Archive entry is a symlink: $entryName")
        }

        Files.newByteChannel(
            destination,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            Channels.newOutputStream(channel).use { output ->
                zip.copyTo(output)
            }
        }
        return true
    }

    private fun normalizeRelativePath(value: String): String =
        value.replace('\\', '/').trimStart('/').trim()
}
