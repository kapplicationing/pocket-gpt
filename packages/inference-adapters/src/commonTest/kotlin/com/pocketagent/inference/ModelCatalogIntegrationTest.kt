package com.pocketagent.inference

import com.pocketagent.core.RoutingMode
import com.pocketagent.core.model.PromptProfileRegistry
import com.pocketagent.core.model.ThinkingStrategyId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelCatalogIntegrationTest {
    @Test
    fun `every routing mode except auto is bound exactly once`() {
        val bindings = ModelCatalog.modelDescriptors()
            .flatMap { descriptor -> descriptor.explicitRoutingModes.map { routingMode -> routingMode to descriptor.modelId } }

        RoutingMode.entries
            .filterNot { routingMode -> routingMode == RoutingMode.AUTO }
            .forEach { routingMode ->
                val matches = bindings.filter { (boundMode, _) -> boundMode == routingMode }
                assertEquals(1, matches.size, "routing mode $routingMode should be bound exactly once")
            }
    }

    @Test
    fun `every descriptor template family has a prompt profile registry entry`() {
        ModelCatalog.modelDescriptors().forEach { descriptor ->
            val template = PromptProfileRegistry.templateFor(descriptor.templateFamily)
            val spec = ModelCatalog.normalizedSpecFor(descriptor.modelId)

            assertEquals(descriptor.templateFamily, spec?.promptProfile?.templateFamily)
            assertEquals(template.profileId, spec?.promptProfile?.profileId)
            assertEquals(template.assistantRoleName, spec?.promptProfile?.assistantRoleName)
            assertEquals(template.stopSequences, spec?.promptProfile?.stopSequences)
        }
    }

    @Test
    fun `every bridge supported model has a distribution catalog entry`() {
        val distributionModelIds = loadDistributionCatalogModelIds()

        ModelCatalog.bridgeSupportedModels().forEach { modelId ->
            assertTrue(
                distributionModelIds.contains(modelId),
                "bridge-supported model $modelId missing from distribution catalog",
            )
        }
    }

    @Test
    fun `interaction features stay within the allowlist`() {
        val allowed = setOf("THINKING_TAGS", "TOOL_CALL_XML")

        ModelCatalog.modelDescriptors().forEach { descriptor ->
            descriptor.interactionFeatures.forEach { value ->
                assertTrue(
                    allowed.contains(value),
                    "unsupported interaction feature '$value' for ${descriptor.modelId}",
                )
            }
        }
    }

    @Test
    fun `thinking strategy is driven only by thinking tags feature`() {
        ModelCatalog.modelDescriptors().forEach { descriptor ->
            val expected = if (descriptor.interactionFeatures.contains("THINKING_TAGS")) {
                ThinkingStrategyId.THINK_TAGS
            } else {
                ThinkingStrategyId.NONE
            }
            assertEquals(
                expected,
                ModelCatalog.normalizedSpecFor(descriptor.modelId)?.promptProfile?.thinkingStrategy,
                "thinking strategy mismatch for ${descriptor.modelId}",
            )
        }
    }

    private fun loadDistributionCatalogModelIds(): Set<String> {
        val repoRoot = findRepoRoot()
        val catalogPath = repoRoot.resolve("apps/mobile-android/src/main/assets/model-distribution-catalog.json")
        val text = Files.readString(catalogPath)
        return Regex(""""modelId"\s*:\s*"([^"]+)"""")
            .findAll(text)
            .map { match -> match.groupValues[1] }
            .toSet()
    }

    private fun findRepoRoot(): Path {
        var current: Path? = Path.of("").toAbsolutePath().normalize()
        while (current != null) {
            val settings = current.resolve("settings.gradle.kts")
            if (Files.exists(settings) && Files.exists(current.resolve("packages/inference-adapters"))) {
                return current
            }
            current = current.parent
        }
        error("Could not locate pocket-gpt repo root from ${Path.of("").absolute()}")
    }
}
