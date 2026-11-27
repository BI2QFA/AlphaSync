package io.github.bi2qfa.alphasync.remote

/** A deliberate camera address, never an arbitrary proxy destination supplied by web content. */
internal data class RemoteControlTarget(val host: String, val port: Int = 8080) {
    val origin: String get() = "http://$host:$port"

    companion object {
        fun parse(value: String): RemoteControlTarget? {
            val address = value.trim().removePrefix("http://").removeSuffix("/")
            val parts = address.split(':')
            if (parts.size !in 1..2) return null
            val octets = parts[0].split('.')
            if (octets.size != 4 || octets.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } || (it.toIntOrNull() ?: -1) !in 0..255 }) return null
            val port = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else 8080
            if (port !in 1..65535) return null
            return RemoteControlTarget(octets.joinToString(".") { it.toInt().toString() }, port)
        }
    }
}
