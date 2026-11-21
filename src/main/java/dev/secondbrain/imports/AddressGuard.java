package dev.secondbrain.imports;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/** Decides which resolved addresses a web import may connect to. */
@FunctionalInterface
interface AddressGuard {
    boolean allowed(InetAddress address);

    /**
     * Public internet only: refuses loopback, private, link-local (including cloud metadata),
     * carrier-grade NAT, multicast, unspecified, IPv6 unique-local and IPv4-mapped forms of these.
     */
    static AddressGuard publicOnly() {
        return AddressGuard::isPublic;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return false;
        var bytes = address.getAddress();
        if (address instanceof Inet4Address) return isPublicIpv4(bytes);
        if (address instanceof Inet6Address) {
            // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible forms hide an IPv4 target.
            boolean mapped = true;
            for (int i = 0; i < 10; i++) mapped &= bytes[i] == 0;
            if (mapped
                    && ((bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff)
                            || (bytes[10] == 0 && bytes[11] == 0)))
                return isPublicIpv4(new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]});
            // fc00::/7 unique local; fec0::/10 deprecated site local; 64:ff9b::/96 NAT64.
            if ((bytes[0] & 0xfe) == 0xfc) return false;
            if ((bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xc0) == 0xc0) return false;
            if (bytes[0] == 0
                    && bytes[1] == 0x64
                    && (bytes[2] & 0xff) == 0xff
                    && (bytes[3] & 0xff) == 0x9b) return false;
            return true;
        }
        return false;
    }

    private static boolean isPublicIpv4(byte[] b) {
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        if (first == 0 || first == 10 || first == 127) return false;
        if (first == 169 && second == 254) return false;
        if (first == 172 && second >= 16 && second <= 31) return false;
        if (first == 192 && second == 168) return false;
        if (first == 100 && second >= 64 && second <= 127) return false;
        if (first == 192 && second == 0 && (b[2] & 0xff) == 0) return false;
        if (first == 198 && (second == 18 || second == 19)) return false;
        return first < 224;
    }
}
