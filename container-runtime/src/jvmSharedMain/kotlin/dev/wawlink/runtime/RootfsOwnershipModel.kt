package dev.wawlink.runtime

import kotlinx.serialization.Serializable

/** Guest IDs are independent of the App's physical IDs used by mapped engines. */
@Serializable
internal data class LogicalOwner(val uid: Long, val gid: Long, val mode: Int)

@Serializable
internal data class OwnershipMigration(val from: ContainerEngineType, val to: ContainerEngineType, val identities: Map<String, String> = emptyMap())

@Serializable
internal data class OwnershipManifest(
    val entries: Map<String, LogicalOwner>,
    val version: Int = 1,
    val physical: ContainerEngineType = ContainerEngineType.PROOT,
    val origin: String = "archive",
    val pending: OwnershipMigration? = null,
)
