package com.sukisu.ultra.data.repository

import com.sukisu.ultra.ui.util.ExtensionId
import com.sukisu.ultra.ui.util.ExtensionState
import com.sukisu.ultra.ui.util.getExtensionOverview

/**
 * User-facing contract for the extension subsystem.
 *
 * The overview screen is read-only: every extension that exposes a
 * runtime sticky bit (DynamicManager) is toggled from inside its own
 * sub-screen via dedicated ksud subcommands (e.g. `manager
 * set-enabled`), so this repository deliberately does not expose a
 * `setEnabled` operation.
 *
 * Probe strategy: a single `ksud extension list` invocation returns
 * the state of every extension at once. The kernel-side CLI prints a
 * stable `EXTENSION_LIST_V1 ...` machine block after the human
 * block; see `KsuCli.getExtensionOverview`. The batched design
 * pays libksud.so startup + root handshake + teardown once instead
 * of N times per refresh.
 */
interface ExtensionRepository {
    /**
     * Probe all extensions at once. The returned map is keyed by
     * `ExtensionId`; missing entries mean the kernel did not
     * report on that id (older kernels, or an id that was never
     * registered).
     */
    suspend fun probeAll(): Map<ExtensionId, ExtensionState>
}

class ExtensionRepositoryImpl : ExtensionRepository {

    override suspend fun probeAll(): Map<ExtensionId, ExtensionState> {
        val overview = getExtensionOverview()
        val out = LinkedHashMap<ExtensionId, ExtensionState>(ExtensionId.ALL.size)
        for (id in ExtensionId.ALL) {
            val entry = overview[id.wireName]
            out[id] = ExtensionState(
                id = id,
                value = entry?.value ?: 0L,
                supported = entry?.supported == true,
                forced = entry?.forced == true,
                managed = entry?.managed == true,
            )
        }
        return out
    }
}
