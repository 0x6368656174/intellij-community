package com.intellij.searchEverywhereMl

import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SearchEverywhereMlExperimentTest : BasePlatformTestCase() {
  private val EXPERIMENT_GROUP_REG_KEY = "search.everywhere.ml.experiment.group"

  fun `test experiment group registry value is clamped between -1 and the number of experiment groups`() {
    val mlExperiment = SearchEverywhereMlExperiment().apply { isExperimentalMode = true }

    // Upper boundary
    Registry.get(EXPERIMENT_GROUP_REG_KEY).setValue(SearchEverywhereMlExperiment.NUMBER_OF_GROUPS - 1)
    assertEquals(SearchEverywhereMlExperiment.NUMBER_OF_GROUPS - 1, mlExperiment.experimentGroup)

    // Clamp - NUMBER_OF_GROUPS is used as modulo, so the max allowed value is NUMBER_OF_GROUPS - 1
    Registry.get(EXPERIMENT_GROUP_REG_KEY).setValue(SearchEverywhereMlExperiment.NUMBER_OF_GROUPS)
    assertEquals(SearchEverywhereMlExperiment.NUMBER_OF_GROUPS - 1, mlExperiment.experimentGroup)
  }
}