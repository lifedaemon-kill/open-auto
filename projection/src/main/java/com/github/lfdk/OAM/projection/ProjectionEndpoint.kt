package com.github.lfdk.OAM.projection

import java.net.InetAddress
import java.net.InetSocketAddress

/** A numeric, local IPv4 address. Parsing never resolves a hostname or opens a connection. */
data class ProjectionEndpoint internal constructor(val address: InetSocketAddress) {
    companion object {
        const val DEFAULT_PORT = 5277

        fun parse(host: String, port: Int = DEFAULT_PORT): ProjectionEndpoint {
            require(port in 1..65535) { "Порт должен быть от 1 до 65535." }
            val parts = host.trim().split('.')
            require(parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } &&
                    (part.length == 1 || part[0] != '0') && part.toInt() in 0..255
            }) { "Введи IPv4-адрес телефона, например 192.168.43.1." }
            val values = parts.map(String::toInt)
            val local = values[0] == 10 ||
                (values[0] == 172 && values[1] in 16..31) ||
                (values[0] == 192 && values[1] == 168) ||
                (values[0] == 169 && values[1] == 254)
            require(local && values[3] !in listOf(0, 255)) { "Для проверки нужен адрес телефона в локальной Wi-Fi сети." }
            val bytes = values.map(Int::toByte).toByteArray()
            return ProjectionEndpoint(InetSocketAddress(InetAddress.getByAddress(bytes), port))
        }
    }
}
