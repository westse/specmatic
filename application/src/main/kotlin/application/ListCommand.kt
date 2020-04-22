package application

import run.qontract.core.versioning.ContractIdentifier
import picocli.CommandLine
import run.qontract.core.qontractCacheDirPath
import run.qontract.core.versioning.POINTER_EXTENSION
import java.io.File
import java.util.concurrent.Callable

@CommandLine.Command(name = "list", mixinStandardHelpOptions = true)
class ListCommand: Callable<Unit> {
    override fun call() {
        listOfFiles(File(qontractCacheDirPath), POINTER_EXTENSION)
            .map { toRelativePath(it.absolutePath, qontractCacheDirPath) }
            .map { toContractIdentifier(it, POINTER_EXTENSION) }
            .sortedWith(compareBy<ContractIdentifier> { it.name }.thenBy { it.version })
            .forEach {
                println(it.displayableString)
            }
    }
}

fun toRelativePath(filePath: String, qontractCacheDirPath: String): String =
        filePath.removePrefix(qontractCacheDirPath).removePrefix(File.separator)

fun toContractIdentifier(relativePath: String, extension: String = ""): ContractIdentifier =
        relativePath.removePrefix(File.separator).split(File.separator)
                .let { pieces ->
                    val namespace = pieces.dropLast(1).joinToString(".")
                    val versionToken = pieces.last()
                    val version = when {
                        extension.isNotBlank() -> versionToken.removeSuffix(".$extension")
                        else -> when {
                            versionToken.contains(".") -> versionToken.split(".").first()
                            else -> versionToken
                        }
                    }.toInt()

                    Pair(namespace, version)
                }.let { (name, version) ->
                    ContractIdentifier(name, version)
                }
