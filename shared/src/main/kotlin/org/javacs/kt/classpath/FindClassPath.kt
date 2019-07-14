package org.javacs.kt.classpath

import org.javacs.kt.LOG
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

fun findClassPath(workspaceRoots: Collection<Path>): Set<Path> {
    val resolver = WithStdlibResolver(
        ShellClassPathResolver.global(workspaceRoots.firstOrNull()).or(
            workspaceRoots.asSequence()
                .flatMap(::workspaceResolvers)
                .fold(ClassPathResolver.empty) { accum, next -> accum + next }
        )
    ).or(BackupClassPathResolver)

    LOG.info("Resolving classpath using ${resolver.resolverType}")
    return resolver.maybeClasspath
}

/** A source for creating class paths */
internal interface ClassPathResolver {
    val resolverType: String
    val classpath: Set<Path> // may throw exceptions
    val maybeClasspath: Set<Path> // does not throw exceptions
        get() = try {
            classpath
        } catch (e: Exception) {
            LOG.warn("Could not resolve classpath using $resolverType: ${e.message}")
            emptySet<Path>()
        }

    companion object {
        /** A default empty classpath implementation */
        val empty = object : ClassPathResolver {
            override val resolverType = "[]"
            override val classpath = emptySet<Path>()
        }
    }
}

/** Combines two classpath resolvers */
internal operator fun ClassPathResolver.plus(other: ClassPathResolver): ClassPathResolver =
    object : ClassPathResolver {
        override val resolverType: String get() = "${this@plus.resolverType} + ${other.resolverType}"
        override val classpath get() = this@plus.classpath + other.classpath
        override val maybeClasspath get() = this@plus.maybeClasspath + other.maybeClasspath
    }

/** Uses the left-hand classpath if not empty, otherwise uses the right */
internal fun ClassPathResolver.or(other: ClassPathResolver): ClassPathResolver =
    object : ClassPathResolver {
        override val resolverType: String get() = "${this@or.resolverType} or ${other.resolverType}"
        override val classpath get() = this@or.classpath.takeIf { it.isNotEmpty() } ?: other.classpath
        override val maybeClasspath get() = this@or.maybeClasspath.takeIf { it.isNotEmpty() } ?: other.maybeClasspath
    }

/** Searches the workspace for all files that could provide classpath info */
private fun workspaceResolvers(workspaceRoot: Path): Sequence<ClassPathResolver> =
    workspaceRoot.toFile().walk().mapNotNull { asClassPathProvider(it.toPath()) }

/** Tries to create a classpath resolver from a file using as many sources as possible */
private fun asClassPathProvider(path: Path): ClassPathResolver? =
    MavenClassPathResolver.maybeCreate(path)
        ?: GradleClassPathResolver.maybeCreate(path)
        ?: ShellClassPathResolver.maybeCreate(path)

internal val userHome = Paths.get(System.getProperty("user.home"))

internal val mavenHome = userHome.resolve(".m2")

internal fun isOSWindows() = (File.separatorChar == '\\')

internal fun findCommandOnPath(name: String): Path? =
        if (isOSWindows()) windowsCommand(name)
        else unixCommand(name)

private fun windowsCommand(name: String) =
        findExecutableOnPath("$name.cmd")
        ?: findExecutableOnPath("$name.bat")
        ?: findExecutableOnPath("$name.exe")

private fun unixCommand(name: String) = findExecutableOnPath(name)

private fun findExecutableOnPath(fileName: String): Path? {
    for (dir in System.getenv("PATH").split(File.pathSeparator)) {
        val file = File(dir, fileName)

        if (file.isFile && file.canExecute()) {
            LOG.info("Found {} at {}", fileName, file.absolutePath)

            return Paths.get(file.absolutePath)
        }
    }

    return null
}
