package com.sukisu.ultra.data.registry

/**
 * Base type for registry-document problems.
 *
 * Kept separate from the registry parser: this fork reads the same registry documents
 * through its own index readers, but shares [RegistryUrls] and the download-integrity
 * plumbing with upstream, so the exception types those files throw live here.
 */
open class RegistryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The document is not valid JSON or violates the protocol. */
class RegistryFormatException(message: String, cause: Throwable? = null) : RegistryException(message, cause)
