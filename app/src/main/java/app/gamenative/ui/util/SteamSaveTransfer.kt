package app.gamenative.ui.util

import android.content.Context
import android.net.Uri
import app.gamenative.R
import app.gamenative.data.SaveFilePattern
import app.gamenative.data.SteamApp
import app.gamenative.enums.PathType
import app.gamenative.service.SteamService
import app.gamenative.utils.FileUtils
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

object SteamSaveTransfer {
    private const val MANIFEST_ENTRY = "manifest.json"
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    private data class SaveArchiveManifest(
        val version: Int = 1,
        val steamAppId: Int,
        val gameName: String,
        val exportedAt: Long,
        val roots: List<SaveRoot>,
    )

    @Serializable
    private data class SaveRoot(
        val archiveRoot: String,
        val absolutePath: String,
    )

    private data class ResolvedSaveRoot(
        val archiveRoot: String,
        val absolutePath: Path,
        val files: List<Path>,
    )

    suspend fun exportSaves(
        context: Context,
        steamAppId: Int,
        uri: Uri,
    ): Boolean {
        return try {
            val app = SteamService.getAppInfoOf(steamAppId)
                ?: throw IOException("Steam app not found")
            val roots = withContext(Dispatchers.IO) { resolveExportRoots(context, app) }

            if (roots.isEmpty()) {
                SnackbarManager.show(context.getString(R.string.steam_save_export_no_saves_found))
                return false
            }

            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    ZipOutputStream(outputStream.buffered()).use { zip ->
                        val manifest = SaveArchiveManifest(
                            steamAppId = steamAppId,
                            gameName = app.name,
                            exportedAt = System.currentTimeMillis(),
                            roots = roots.map {
                                SaveRoot(
                                    archiveRoot = it.archiveRoot,
                                    absolutePath = it.absolutePath.toString(),
                                )
                            },
                        )

                        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                        zip.write(json.encodeToString(SaveArchiveManifest.serializer(), manifest).toByteArray())
                        zip.closeEntry()

                        roots.forEach { root ->
                            root.files.forEach { file ->
                                if (!Files.isRegularFile(file)) return@forEach
                                val relativePath = root.absolutePath.relativize(file).toString().replace('\\', '/')
                                zip.putNextEntry(ZipEntry("files/${root.archiveRoot}/$relativePath"))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
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

            val rootMap = withContext(Dispatchers.IO) {
                resolveImportRoots(context, app).associateBy { it.archiveRoot }
            }

            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream.buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            try {
                                if (entry.isDirectory || entry.name == MANIFEST_ENTRY || !entry.name.startsWith("files/")) {
                                    continue
                                }
                                writeArchiveEntry(zip, entry.name, rootMap)
                            } finally {
                                zip.closeEntry()
                            }
                        }
                    }
                } ?: throw IOException("Unable to open archive")
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

    private fun writeArchiveEntry(
        zip: ZipInputStream,
        entryName: String,
        rootMap: Map<String, ResolvedSaveRoot>,
    ) {
        val relativeEntry = entryName.removePrefix("files/").replace('\\', '/')
        val slashIndex = relativeEntry.indexOf('/')
        if (slashIndex <= 0) return

        val archiveRoot = relativeEntry.substring(0, slashIndex)
        val relativePath = relativeEntry.substring(slashIndex + 1)
        if (relativePath.isBlank()) return

        val destinationRoot = rootMap[archiveRoot]?.absolutePath ?: return
        val normalizedRoot = destinationRoot.normalize()
        val destination = normalizedRoot.resolve(relativePath).normalize()
        if (!destination.startsWith(normalizedRoot)) {
            throw IOException("Archive entry escapes save root: $entryName")
        }

        destination.parent?.createDirectories()
        Files.newOutputStream(destination).use { output ->
            zip.copyTo(output)
        }
    }

    private fun resolveExportRoots(
        context: Context,
        app: SteamApp,
    ): List<ResolvedSaveRoot> = resolveRoots(context, app, includeEmptyRoots = false)

    private fun resolveImportRoots(
        context: Context,
        app: SteamApp,
    ): List<ResolvedSaveRoot> = resolveRoots(context, app, includeEmptyRoots = true)

    private fun resolveRoots(
        context: Context,
        app: SteamApp,
        includeEmptyRoots: Boolean,
    ): List<ResolvedSaveRoot> {
        val accountId = SteamService.userSteamId?.accountID?.toLong()
            ?: throw IOException("Steam not logged in")

        val savePatterns = app.ufs.saveFilePatterns.filter { it.root.isWindows }
        if (savePatterns.isEmpty()) {
            val basePath = Paths.get(PathType.SteamUserData.toAbsPath(context, app.id, accountId))
            val files = FileUtils.findFilesRecursive(basePath, "*", maxDepth = 5)
                .filter { Files.isRegularFile(it) }
                .toList()
            return listOfNotNull(
                createResolvedRoot(
                    archiveRoot = "steam-userdata",
                    basePath = basePath,
                    files = files,
                    includeEmptyRoot = includeEmptyRoots,
                ),
            )
        }

        return savePatterns.mapIndexedNotNull { index, pattern ->
            val basePath = Paths.get(pattern.root.toAbsPath(context, app.id, accountId), pattern.substitutedPath)
            val files = findPatternFiles(basePath, pattern)
            createResolvedRoot(
                archiveRoot = patternArchiveRoot(pattern, index),
                basePath = basePath,
                files = files,
                includeEmptyRoot = includeEmptyRoots,
            )
        }
    }

    private fun findPatternFiles(basePath: Path, pattern: SaveFilePattern): List<Path> {
        if (!Files.exists(basePath)) return emptyList()
        val depth = if (pattern.recursive > 0) pattern.recursive else 5
        return FileUtils.findFilesRecursive(basePath, pattern.pattern, maxDepth = depth)
            .filter { Files.isRegularFile(it) }
            .toList()
    }

    private fun createResolvedRoot(
        archiveRoot: String,
        basePath: Path,
        files: List<Path>,
        includeEmptyRoot: Boolean,
    ): ResolvedSaveRoot? {
        val distinctFiles = files.distinct()
        if (!basePath.exists()) {
            return if (includeEmptyRoot) {
                ResolvedSaveRoot(
                    archiveRoot = archiveRoot,
                    absolutePath = basePath,
                    files = emptyList(),
                )
            } else {
                null
            }
        }

        if (!includeEmptyRoot && distinctFiles.isEmpty()) return null
        return ResolvedSaveRoot(
            archiveRoot = archiveRoot,
            absolutePath = basePath,
            files = distinctFiles,
        )
    }

    private fun patternArchiveRoot(pattern: SaveFilePattern, index: Int): String {
        val root = pattern.root.name.lowercase()
        val path = pattern.substitutedPath
            .replace('\\', '/')
            .replace('/', '_')
            .replace(' ', '_')
            .ifBlank { "root" }
        return "$index-$root-$path"
    }
}
