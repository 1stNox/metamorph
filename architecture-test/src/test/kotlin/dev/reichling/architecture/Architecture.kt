package dev.reichling.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Shared definition of the hexagonal layers and the production sources the rules look at.
 *
 * The layer packages live here and nowhere else. If a package is renamed, change it here; the
 * non-vacuity test fails if a layer ends up without files.
 */
object Architecture {
    const val ROOT_PACKAGE = "dev.reichling"
    const val CORE_PACKAGE = "dev.reichling.core"
    const val PORT_PACKAGE = "dev.reichling.core.port"
    const val ADAPTER_PACKAGE = "dev.reichling.adapter"

    class LayerSpec(val name: String, val basePackage: String) {
        /** The same layer in Konsist's own terms (`..` matches the package and all subpackages). */
        val konsist = Layer(name, "$basePackage..")

        fun contains(file: KoFileDeclaration): Boolean = file.packagee?.name.isIn(basePackage)

        override fun toString(): String = "$name layer ($basePackage..)"
    }

    val model = LayerSpec("Model", "dev.reichling.core.model")
    val port = LayerSpec("Port", PORT_PACKAGE)
    val adapter = LayerSpec("Adapter", ADAPTER_PACKAGE)
    val app = LayerSpec("App", "dev.reichling.app")
    val layers = listOf(model, port, adapter, app)

    /**
     * Which layers each layer may depend on. Anything not listed is forbidden, so nothing may
     * depend on App (it appears in no other layer's set) and Model depends on nothing.
     */
    val allowedDependencies: Map<LayerSpec, Set<LayerSpec>> = mapOf(
        model to emptySet(),
        port to setOf(model),
        adapter to setOf(port, model),
        app to setOf(model, port, adapter),
    )

    /**
     * For every adapter package: the package prefixes of the platform it adapts. A new adapter must
     * be registered here, otherwise [PlatformFreeAdapterApiTest] fails.
     */
    val adapterPlatforms: Map<String, List<String>> = mapOf(
        "dev.reichling.adapter.kafka" to listOf("org.apache.kafka"),
    )

    /**
     * Production sources of every module (test sources excluded), found without per-module wiring.
     * Only files under [ROOT_PACKAGE] are kept.
     */
    val productionFiles: List<KoFileDeclaration> by lazy {
        Konsist.scopeFromProduction().files.filter { it.packagee?.name.isIn(ROOT_PACKAGE) }
    }

    fun filesIn(layer: LayerSpec): List<KoFileDeclaration> = productionFiles.filter(layer::contains)

    fun filesIn(basePackage: String): List<KoFileDeclaration> =
        productionFiles.filter { it.packagee?.name.isIn(basePackage) }
}

/** True if this package is [base] or one of its subpackages. */
fun String?.isIn(base: String): Boolean = this != null && (this == base || startsWith("$base."))

/** True if this fully qualified name is [prefix] itself or something inside it. */
fun String.isUnder(prefix: String): Boolean = isIn(prefix)

/** Fails with [rule] and one line per violation, so the message says which rule broke and where. */
fun assertNoViolations(rule: String, violations: List<String>) {
    assertTrue(violations.isEmpty()) {
        "$rule\n" + violations.joinToString(separator = "\n") { "  - $it" }
    }
}
