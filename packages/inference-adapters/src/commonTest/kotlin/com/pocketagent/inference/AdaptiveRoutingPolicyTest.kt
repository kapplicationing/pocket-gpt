package com.pocketagent.inference

import kotlin.test.Test
import kotlin.test.assertEquals

class AdaptiveRoutingPolicyTest {
    private val routing = AdaptiveRoutingPolicy()

    @Test
    fun `routing matrix enforces battery thermal ram and task boundaries`() {
        val cases = listOf(
            RoutingCase(
                taskType = "long_text",
                deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 12),
                expectedModel = ModelCatalog.QWEN3_1_7B_Q4_K_M,
                expectedContextBudget = 8192,
            ),
            RoutingCase(
                taskType = "long_text",
                deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 11),
                expectedModel = ModelCatalog.QWEN3_1_7B_Q4_K_M,
                expectedContextBudget = 8192,
            ),
            RoutingCase(
                taskType = "reasoning",
                deviceState = DeviceState(batteryPercent = 80, thermalLevel = 3, ramClassGb = 8),
                expectedModel = ModelCatalog.QWEN3_1_7B_Q4_K_M,
                expectedContextBudget = 4096,
            ),
            RoutingCase(
                taskType = "short_text",
                deviceState = DeviceState(batteryPercent = 19, thermalLevel = 3, ramClassGb = 12),
                expectedModel = ModelCatalog.QWEN3_0_6B_Q4_K_M,
                expectedContextBudget = 2048,
            ),
            RoutingCase(
                taskType = "short_text",
                deviceState = DeviceState(batteryPercent = 20, thermalLevel = 3, ramClassGb = 12),
                expectedModel = ModelCatalog.QWEN3_0_6B_Q4_K_M,
                expectedContextBudget = 4096,
            ),
            RoutingCase(
                taskType = "short_text",
                deviceState = DeviceState(batteryPercent = 85, thermalLevel = 3, ramClassGb = 8),
                expectedModel = ModelCatalog.QWEN3_0_6B_Q4_K_M,
                expectedContextBudget = 4096,
            ),
            RoutingCase(
                taskType = "short_text",
                deviceState = DeviceState(batteryPercent = 85, thermalLevel = 3, ramClassGb = 12),
                expectedModel = ModelCatalog.QWEN3_1_7B_Q4_K_M,
                expectedContextBudget = 4096,
            ),
            RoutingCase(
                taskType = "short_text",
                deviceState = DeviceState(batteryPercent = 90, thermalLevel = 6, ramClassGb = 12),
                expectedModel = ModelCatalog.QWEN3_0_6B_Q4_K_M,
                expectedContextBudget = 4096,
            ),
            RoutingCase(
                taskType = "image",
                deviceState = DeviceState(batteryPercent = 80, thermalLevel = 7, ramClassGb = 12),
                expectedModel = ModelCatalog.QWEN_3_5_0_8B_Q4,
                expectedContextBudget = 2048,
            ),
            RoutingCase(
                taskType = "image",
                deviceState = DeviceState(batteryPercent = 80, thermalLevel = 6, ramClassGb = 12),
                expectedModel = ModelCatalog.QWEN_3_5_0_8B_Q4,
                expectedContextBudget = 4096,
            ),
        )

        cases.forEach { testCase ->
            val selected = routing.selectModel(
                taskType = testCase.taskType,
                deviceState = testCase.deviceState,
            )
            val contextBudget = routing.selectContextBudget(
                taskType = testCase.taskType,
                deviceState = testCase.deviceState,
            )

            assertEquals(testCase.expectedModel, selected, "model mismatch for case: $testCase")
            assertEquals(testCase.expectedContextBudget, contextBudget, "context budget mismatch for case: $testCase")
        }
    }
}

private data class RoutingCase(
    val taskType: String,
    val deviceState: DeviceState,
    val expectedModel: String,
    val expectedContextBudget: Int,
)
