package utils

class IPAddressFilter {
    companion object{
        // "IPv4":1/"IPv6":2/"IPv4 with Port":3/"IPv6 with Port":4/"Invalid IP":5
        fun getIpType(ip: String): Number {
            return when {
                isValidIPv4(ip) -> 1
                isValidIPv6(ip) -> 2
                isValidIPv4AddressWithPort(ip) -> 3
                isValidIPv6AddressWithPort(ip) -> 4
                else -> 5
            }
        }

        // 其他方法保持不变
        fun isValidIPv4(ipv4Address: String): Boolean {
            val ipv4Pattern = "^(25[0-5]|2[0-4][0-9]|[0-1]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[0-1]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[0-1]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[0-1]?[0-9][0-9]?)$"
            return ipv4Address.matches(ipv4Pattern.toRegex())
        }

        fun isValidIPv6(ipv6Address: String): Boolean {
            val ipv6Pattern = "^\\[?([0-9a-fA-F]{0,4}:){1,7}([0-9a-fA-F]{0,4})?\\]?$"
            return ipv6Address.matches(ipv6Pattern.toRegex())
        }

        fun isValidIPv4AddressWithPort(ipWithPort: String): Boolean {
            val ipWithPortPattern = "^(\\d{1,3}(?:\\.\\d{1,3}){3}):([0-9]{1,5})$"
            return ipWithPort.matches(ipWithPortPattern.toRegex())
        }

        fun isValidIPv6AddressWithPort(ipWithPort: String): Boolean {
            val ipWithPortPattern = "^(?:\\[(.+?)\\])?(?::(\\d{1,5}))?$"
            return ipWithPort.matches(ipWithPortPattern.toRegex())
        }

        fun splitIpAndPort(ipAddressWithPort:String):Pair<String, String>?{
            // 判断是 IPv4 还是 IPv6
            if (getIpType(ipAddressWithPort) == 4) {
                // IPv6 地址
                val (ip, port) = ipAddressWithPort.split("]:")
                return Pair(ip.removePrefix("["), port)
            } else if(getIpType(ipAddressWithPort) == 3){
                // IPv4 地址
                val (ip, port) = ipAddressWithPort.split(":")
                return Pair(ip, port)
            }else{
                return null
            }
        }
    }
}