package nl.eschouten.scraper.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher

/** Dispatchers.Main.immediate where there is a main thread (apps), else Default (tests). */
internal fun MainCoroutineDispatcher.immediateOrDefault(): CoroutineDispatcher =
  runCatching { immediate.also { it.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext) } }.getOrDefault(Dispatchers.Default)
