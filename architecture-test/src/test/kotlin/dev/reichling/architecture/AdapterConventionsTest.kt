package dev.reichling.architecture

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Test

class AdapterConventionsTest {
    @Test
    fun `classes implementing a port live in the adapter layer and end with Adapter`() {
        val portInterfaces =
            Architecture
                .filesIn(Architecture.port)
                .flatMap { file ->
                    file.interfaces(includeNested = true).map { "${Architecture.PORT_PACKAGE}.${it.name}" }
                }.toSet()

        val violations =
            Architecture.productionFiles.flatMap { file ->
                file
                    .classes(includeNested = true)
                    .filter { klass -> klass.parents().any { parent -> resolve(file, parent.name) in portInterfaces } }
                    .flatMap { klass ->
                        listOfNotNull(
                            "${file.path}: ${klass.name} implements a port but is not in ${Architecture.ADAPTER_PACKAGE}.."
                                .takeUnless { Architecture.adapter.contains(file) },
                            "${file.path}: ${klass.name} implements a port but its name does not end with 'Adapter'"
                                .takeUnless { klass.name.endsWith("Adapter") },
                        )
                    }
            }

        assertNoViolations(
            "Production classes that implement a ${Architecture.PORT_PACKAGE} interface must live in " +
                "${Architecture.ADAPTER_PACKAGE}.. and be named *Adapter.",
            violations,
        )
    }

    /**
     * Best-effort fully qualified name of the supertype [name] used in [file]: as written if already
     * qualified, else through an explicit import, else through a wildcard import of the port package,
     * else from the file's own package.
     */
    private fun resolve(
        file: KoFileDeclaration,
        name: String,
    ): String {
        val simple = name.substringBefore('<').trim()
        if ('.' in simple) return simple
        val explicit =
            file.imports.firstOrNull {
                !it.isWildcard &&
                    (it.alias?.name ?: it.name.substringAfterLast('.')) == simple
            }
        val wildcard =
            file.imports
                .filter { it.isWildcard }
                .map { it.name.removeSuffix(".*") + "." + simple }
                .firstOrNull { it.startsWith("${Architecture.PORT_PACKAGE}.") }
        return explicit?.name ?: wildcard ?: "${file.packagee?.name}.$simple"
    }
}
