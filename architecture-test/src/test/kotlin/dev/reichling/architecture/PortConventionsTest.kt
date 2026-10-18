package dev.reichling.architecture

import org.junit.jupiter.api.Test

class PortConventionsTest {
    private val portFiles = Architecture.filesIn(Architecture.port)

    @Test
    fun `port package declares only interfaces and exceptions`() {
        val violations =
            portFiles.flatMap { file ->
                val notExceptions =
                    file
                        .classes(includeNested = true)
                        .filterNot { it.name.endsWith("Exception") }
                        .map { "${file.path}: class ${it.name} is neither an interface nor named *Exception" }
                val objects =
                    file
                        .objects(includeNested = true)
                        .map { "${file.path}: object ${it.name} is neither an interface nor an exception class" }
                val dataExceptions =
                    file
                        .classes(includeNested = true)
                        .filter { it.name.endsWith("Exception") && it.hasDataModifier }
                        .map { "${file.path}: exception ${it.name} must not be a data class" }
                notExceptions + objects + dataExceptions
            }

        assertNoViolations(
            "${Architecture.PORT_PACKAGE} may only declare port interfaces and exception classes named *Exception.",
            violations,
        )
    }

    @Test
    fun `port interfaces and their functions have KDoc`() {
        val violations =
            portFiles.flatMap { file ->
                file.interfaces(includeNested = true).flatMap { port ->
                    val missingOnInterface =
                        if (port.hasKDoc) emptyList() else listOf("${file.path}: interface ${port.name} has no KDoc")
                    val missingOnFunctions =
                        port
                            .functions()
                            .filterNot { it.hasKDoc }
                            .map { "${file.path}: function ${port.name}.${it.name}() has no KDoc" }
                    missingOnInterface + missingOnFunctions
                }
            }

        assertNoViolations(
            "Port interfaces and each of their functions need KDoc: it states the contract adapters must meet.",
            violations,
        )
    }
}
