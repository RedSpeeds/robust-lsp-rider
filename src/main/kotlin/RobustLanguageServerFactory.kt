package com.redspeeds

import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.installation.LanguageServerInstallerBase
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import com.intellij.openapi.progress.ProgressIndicator
import org.jetbrains.annotations.NotNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.WorkspaceFolder

/** Starts the Rust robust-lsp executable over its standard input/output streams. */
class RobustLanguageServerFactory : LanguageServerFactory {
    override fun createConnectionProvider(@NotNull project: Project): StreamConnectionProvider {
        val executable = configuredExecutable()
        return RobustProcessConnectionProvider(
            listOf(executable),
            project.basePath,
        )
    }

    override fun createLanguageClient(@NotNull project: Project): LanguageClientImpl =
        RobustLanguageClient(project)

    override fun createClientFeatures(): LSPClientFeatures = RobustClientFeatures()

    override fun createServerInstaller() = RobustLanguageServerInstaller()

    private fun configuredExecutable(): String {
        val configured = System.getenv("LSP_SERVER_PATH")?.trim()
        if (!configured.isNullOrEmpty()) {
            val path = Path.of(configured)
            if (System.getProperty("os.name").startsWith("Windows") &&
                path.fileName.toString().substringAfterLast('.', "") != "exe"
            ) {
                return "$configured.exe"
            }
            return configured
        }
        // The installer places the binary here. Launching by name would require
        // the user to also add it to PATH and can leave LSP4IJ with an
        // uninitialized server after installation succeeds.
        return RobustLanguageServerInstallation.executable().toString()
    }



    private class RobustProcessConnectionProvider(
        commands: List<String>,
        workingDirectory: String?
    ) : ProcessStreamConnectionProvider(commands, workingDirectory)
}

/** LSP4IJ client used for Robust-specific initialization and client behavior. */
private class RobustLanguageClient(project: Project) : LanguageClientImpl(project)

/**
 * LSP4IJ derives rootUri from the IntelliJ project. Rider can sometimes expose
 * its .idea project directory instead of the solution directory, so normalize
 * it to the nearest directory containing a Robust solution before initialize.
 */
private class RobustClientFeatures : LSPClientFeatures() {
    override fun initializeParams(initializeParams: InitializeParams) {
        val root = findRobustRoot(getProject().basePath?.let { Path.of(it) }) ?: return
        val uri = root.toUri().toString()
        initializeParams.workspaceFolders = listOf(WorkspaceFolder(uri, root.fileName.toString()))
        // robust-lsp 0.12.1 still reads root_uri. Keep this compatibility field
        // until the server is updated to read workspaceFolders instead.
        initializeParams.rootUri = uri
    }

    private fun findRobustRoot(start: Path?): Path? {
        var current = start?.toAbsolutePath()?.normalize()
        while (current != null) {
            if (Files.isRegularFile(current.resolve("SpaceStation14.sln")) ||
                Files.isRegularFile(current.resolve("SpaceStation14.slnx")) ||
                Files.isRegularFile(current.resolve("RobustToolbox/RobustToolbox.sln")) ||
                Files.isRegularFile(current.resolve("RobustToolbox/RobustToolbox.slnx"))
            ) {
                return current
            }
            current = current.parent
        }
        return null
    }
}

/** Downloads the same platform binaries used by the upstream VS Code extension. */
class RobustLanguageServerInstaller : LanguageServerInstallerBase() {
    override fun checkServerInstalled(indicator: ProgressIndicator): Boolean =
        RobustLanguageServerInstallation.executable().let { Files.isRegularFile(it) && Files.isExecutable(it) }

    override fun install(indicator: ProgressIndicator) {
        val asset = RobustLanguageServerInstallation.releaseAsset()
        val target = RobustLanguageServerInstallation.executable()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, "robust-lsp-", ".download")

        try {
            indicator.text = "Downloading Robust LSP ${asset.name}"
            val connection = URI.create(asset.url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("User-Agent", "robust-lsp-rider-plugin")
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("GitHub returned HTTP ${connection.responseCode} for ${asset.url}")
            }
            connection.inputStream.use { input -> Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING) }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            makeExecutable(target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun makeExecutable(path: Path) {
        if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            runCatching {
                Files.setPosixFilePermissions(
                    path,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE
                    )
                )
            }
        }
    }
}

private object RobustLanguageServerInstallation {
    private const val RELEASES_API = "https://api.github.com/repos/Ertanic/robust-lsp/releases/latest"

    data class Asset(val name: String, val url: String)

    fun executable(): Path {
        val filename = if (isWindows()) "robust-lsp.exe" else "robust-lsp"
        return Path.of(System.getProperty("user.home"), ".robust-lsp", filename)
    }

    fun releaseAsset(): Asset {
        val expected = when {
            isWindows() -> "robust-lsp-win-x86_64.exe"
            isLinux() -> "robust-lsp-linux-x86_64"
            else -> throw IOException("Robust LSP does not publish a binary for this operating system")
        }
        val apiResponse = URI.create(RELEASES_API).toURL().openStream().use { it.bufferedReader().readText() }
        val assetPattern = Regex(
            "\\{[^{}]*\\\"name\\\"\\s*:\\s*\\\"($expected)\\\"[^{}]*" +
                "\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"[^{}]*\\}"
        )
        val match = assetPattern.find(apiResponse)
            ?: throw IOException("No $expected asset was found in the latest Robust LSP release")
        return Asset(match.groupValues[1], match.groupValues[2])
    }

    private fun isWindows() = System.getProperty("os.name").contains("Windows", ignoreCase = true)
    private fun isLinux() = System.getProperty("os.name").contains("Linux", ignoreCase = true)
}
