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
import java.security.MessageDigest
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
    private const val ARCHIVE_VERSION = 2
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val STEAM_USERDATA_ROOT_ID = "steam-userdata"
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
        val roots: List<SaveRoot>,
    )

    @Serializable
    private data class SaveRoot(
        val rootId: String? = null,
        val archiveRoot: String? = null,
    )

    private data class ResolvedSaveRoot(
        val rootId: String,
        val absolutePath: Path,
        val files: List<Path>,
    )

    private data class MutableResolvedSaveRoot(
        var absolutePath: Path,
        val files: MutableSet<Path>,
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
                            roots = roots.map { SaveRoot(rootId = it.rootId) },
                        )

                        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                        zip.write(json.encodeToString(SaveArchiveManifest.serializer(), manifest).toByteArray())
                        zip.closeEntry()

                        roots.forEach { root ->
                            root.files.forEach { file ->
                                if (!Files.isRegularFile(file)) return@forEach
                                val relativePath = root.absolutePath.relativize(file).toString().replace('\\', '/')
                                zip.putNextEntry(ZipEntry("files/${root.rootId}/$relativePath"))
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

            val archiveRootIds = archiveManifest.roots.mapNotNull { it.effectiveRootId() }.toSet()
            if (archiveRootIds.isEmpty()) {
                throw IOException("Archive does not declare any save roots")
            }

            val rootMap = withContext(Dispatchers.IO) {
                resolveImportRoots(context, app).associateBy { it.rootId }
            }
            val unresolvedRootIds = archiveRootIds - rootMap.keys
            if (unresolvedRootIds.isNotEmpty()) {
                throw IOException("Archive save roots not available: ${unresolvedRootIds.joinToString()}")
            }

            var importedFileCount = 0
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream.buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            try {
                                if (entry.isDirectory || entry.name == MANIFEST_ENTRY || !entry.name.startsWith("files/")) {
                                    continue
                                }
                                if (writeArchiveEntry(zip, entry.name, archiveRootIds, rootMap)) {
                                    importedFileCount += 1
                                }
                            } finally {
                                zip.closeEntry()
                            }
                        }
                    }
                } ?: throw IOException("Unable to open archive")
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
        declaredRootIds: Set<String>,
        rootMap: Map<String, ResolvedSaveRoot>,
    ): Boolean {
        val relativeEntry = entryName.removePrefix("files/").replace('\\', '/')
        val slashIndex = relativeEntry.indexOf('/')
        if (slashIndex <= 0) return false

        val rootId = relativeEntry.substring(0, slashIndex)
        val relativePath = relativeEntry.substring(slashIndex + 1)
        if (relativePath.isBlank()) return false
        if (rootId !in declaredRootIds) {
            throw IOException("Archive entry missing from manifest: $entryName")
        }

        val destinationRoot = rootMap[rootId]?.absolutePath
            ?: throw IOException("Archive save root not available: $rootId")
        val normalizedRoot = destinationRoot.normalize()
        val destination = normalizedRoot.resolve(relativePath).normalize()
        if (!destination.startsWith(normalizedRoot)) {
            throw IOException("Archive entry escapes save root: $entryName")
        }

        destination.parent?.createDirectories()
        Files.newOutputStream(destination).use { output ->
            zip.copyTo(output)
        }
        return true
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
                    rootId = STEAM_USERDATA_ROOT_ID,
                    basePath = basePath,
                    files = files,
                    includeEmptyRoot = includeEmptyRoots,
                ),
            )
        }

        val mergedRoots = LinkedHashMap<String, MutableResolvedSaveRoot>()
        savePatterns.forEach { pattern ->
            val rootId = patternRootId(pattern)
            val basePath = Paths.get(pattern.root.toAbsPath(context, app.id, accountId), pattern.substitutedPath)
            val files = findPatternFiles(basePath, pattern)
            val existing = mergedRoots[rootId]
            if (existing == null) {
                mergedRoots[rootId] = MutableResolvedSaveRoot(basePath, files.toMutableSet())
            } else {
                if (!existing.absolutePath.exists() && basePath.exists()) {
                    existing.absolutePath = basePath
                }
                existing.files.addAll(files)
            }
        }

        return mergedRoots.mapNotNull { (rootId, root) ->
            createResolvedRoot(
                rootId = rootId,
                basePath = root.absolutePath,
                files = root.files.toList(),
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
        rootId: String,
        basePath: Path,
        files: List<Path>,
        includeEmptyRoot: Boolean,
    ): ResolvedSaveRoot? {
        val distinctFiles = files.distinct()
        if (!basePath.exists()) {
            return if (includeEmptyRoot) {
                ResolvedSaveRoot(
                    rootId = rootId,
                    absolutePath = basePath,
                    files = emptyList(),
                )
            } else {
                null
            }
        }

        if (!includeEmptyRoot && distinctFiles.isEmpty()) return null
        return ResolvedSaveRoot(
            rootId = rootId,
            absolutePath = basePath,
            files = distinctFiles,
        )
    }

    private fun patternRootId(pattern: SaveFilePattern): String {
        val root = pattern.root.name.lowercase()
        val normalizedPath = pattern.path
            .replace('\\', '/')
            .ifBlank { "root" }
            .lowercase()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$root:$normalizedPath".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$root-$digest"
    }

    private fun SaveRoot.effectiveRootId(): String? {
        return rootId?.takeIf { it.isNotBlank() }
            ?: archiveRoot?.takeIf { it.isNotBlank() }
    }
}
