package dev.reichling.architecture

import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.declaration.KoFunctionDeclaration
import com.lemonappdev.konsist.api.declaration.KoParameterDeclaration
import com.lemonappdev.konsist.api.declaration.KoPropertyDeclaration
import com.lemonappdev.konsist.api.declaration.combined.KoClassAndInterfaceAndObjectDeclaration
import com.lemonappdev.konsist.api.provider.modifier.KoVisibilityModifierProvider
import org.junit.jupiter.api.Test

/**
 * The public API of an adapter package must not expose the types of the platform it adapts.
 * `internal` and `private` declarations are exempt.
 *
 * Konsist reads source, not bytecode, so a platform type is recognised by its fully qualified name
 * or by an explicit import in the same file. A wildcard import of a platform package in a file with
 * exposed declarations is reported too, because the names it brings in cannot be told apart.
 */
class PlatformFreeAdapterApiTest {
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")

    @Test
    fun `every adapter package is registered with its platform prefixes`() {
        val adapterPackages =
            Architecture
                .filesIn(Architecture.adapter)
                .map { adapterPackageOf(it.packagee?.name.orEmpty()) }
                .toSortedSet()

        val violations =
            adapterPackages
                .filter { it !in Architecture.adapterPlatforms }
                .map {
                    "Adapter package '$it' has sources but is not registered in Architecture.adapterPlatforms " +
                        "(add it with the package prefixes of its platform)"
                }

        assertNoViolations(
            "Every adapter package must be registered so its public API can be checked for platform types.",
            violations,
        )
    }

    @Test
    fun `adapter public API exposes no platform types`() {
        val violations =
            Architecture.adapterPlatforms.flatMap { (adapterPackage, prefixes) ->
                Architecture.filesIn(adapterPackage).flatMap { file -> exposedPlatformTypes(file, prefixes) }
            }

        assertNoViolations(
            "Public and default-visibility declarations of an adapter must not use the platform's types in " +
                "parameters, return types, properties or supertypes. Make the declaration internal.",
            violations,
        )
    }

    private fun adapterPackageOf(packageName: String): String {
        val name = packageName.removePrefix("${Architecture.ADAPTER_PACKAGE}.").substringBefore('.')
        return "${Architecture.ADAPTER_PACKAGE}.$name"
    }

    private fun isExposed(declaration: KoVisibilityModifierProvider) =
        !declaration.hasInternalModifier && !declaration.hasPrivateModifier

    /** Descriptions of exposed declarations in [file] that mention a type from one of [prefixes]. */
    private fun exposedPlatformTypes(
        file: KoFileDeclaration,
        prefixes: List<String>,
    ): List<String> = ExposedTypeCollector(file, prefixes).collect()

    /** Walks the declarations of one [file] and records those that expose a type from one of [prefixes]. */
    private inner class ExposedTypeCollector(
        private val file: KoFileDeclaration,
        private val prefixes: List<String>,
    ) {
        private val found = mutableListOf<String>()

        private val importedPlatformNames =
            file.imports
                .filter { import -> !import.isWildcard && prefixes.any { import.name.isUnder(it) } }
                .map { it.alias?.name ?: it.name.substringAfterLast('.') }
                .toSet()

        fun collect(): List<String> {
            file.functions(includeNested = false).forEach { checkFunction("", it) }
            file.properties(includeNested = false).forEach { checkProperty("", it) }
            file.classesAndInterfacesAndObjects(includeNested = false).forEach { checkDeclaration("", it) }

            val hasExposedDeclarations =
                file.declarations(includeNested = true).any {
                    it is KoVisibilityModifierProvider && isExposed(it)
                }
            if (hasExposedDeclarations &&
                file.imports.any { it.isWildcard && prefixes.any { p -> it.name.isUnder(p) } }
            ) {
                found +=
                    where("wildcard import of a platform package; use explicit imports so the API check can see them")
            }
            return found
        }

        private fun mentionsPlatform(typeText: String): Boolean =
            identifier.findAll(typeText).any { match ->
                val name = match.value
                prefixes.any { name.isUnder(it) } || name.substringBefore('.') in importedPlatformNames
            }

        private fun where(description: String) = "${file.path}: $description"

        private fun checkParameters(
            owner: String,
            parameters: List<KoParameterDeclaration>,
        ) {
            parameters
                .filter { mentionsPlatform(it.type.text) }
                .forEach { found += where("$owner has parameter '${it.name}' of platform type ${it.type.text}") }
        }

        private fun checkFunction(
            owner: String,
            function: KoFunctionDeclaration,
        ) {
            if (!isExposed(function)) return
            val name = "$owner${function.name}()"
            function.receiverType
                ?.takeIf { mentionsPlatform(it.text) }
                ?.let { found += where("$name has platform receiver type ${it.text}") }
            checkParameters(name, function.parameters)
            val returnType = function.returnType
            // Without a declared return type the type is inferred, so look at the expression itself.
            val returnText = returnType?.text ?: function.text.takeIf { function.hasExpressionBody }
            if (returnText != null && mentionsPlatform(returnText)) {
                found += where("$name returns a platform type (${returnText.take(60)})")
            }
        }

        private fun checkProperty(
            owner: String,
            property: KoPropertyDeclaration,
        ) {
            if (!isExposed(property)) return
            val typeText = property.type?.text ?: property.text
            if (mentionsPlatform(typeText)) {
                found += where("property $owner${property.name} has platform type (${typeText.take(60)})")
            }
        }

        private fun checkDeclaration(
            owner: String,
            declaration: KoClassAndInterfaceAndObjectDeclaration,
        ) {
            if (!isExposed(declaration)) return
            val name = "$owner${declaration.name}"
            declaration
                .parents()
                .filter { mentionsPlatform(it.text) }
                .forEach { found += where("$name has platform supertype ${it.text}") }
            if (declaration is KoClassDeclaration) {
                declaration.primaryConstructor
                    ?.takeIf { isExposed(it) }
                    ?.let { checkParameters("$name constructor", it.parameters) }
                declaration.secondaryConstructors
                    .filter { isExposed(it) }
                    .forEach { checkParameters("$name constructor", it.parameters) }
            }
            declaration.functions(includeNested = false).forEach { checkFunction("$name.", it) }
            declaration.properties(includeNested = false).forEach { checkProperty("$name.", it) }
            declaration.classesAndInterfacesAndObjects(includeNested = false).forEach { checkDeclaration("$name.", it) }
        }
    }
}
