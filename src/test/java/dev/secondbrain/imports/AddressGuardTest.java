package dev.secondbrain.imports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

class AddressGuardTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "127.0.0.1",
                "127.10.0.1",
                "0.0.0.0",
                "10.1.2.3",
                "172.16.0.1",
                "172.31.255.255",
                "192.168.1.1",
                "169.254.169.254",
                "100.64.0.1",
                "192.0.0.10",
                "198.18.0.1",
                "224.0.0.1",
                "255.255.255.255",
                "::1",
                "::",
                "fe80::1",
                "fc00::1",
                "fd12:3456::1",
                "fec0::1",
                "::ffff:127.0.0.1",
                "::ffff:169.254.169.254",
                "::ffff:10.0.0.1",
                "64:ff9b::a00:1",
                "ff02::1"
            })
    void refusesEveryInternalOrSpecialAddress(String address) throws Exception {
        assertThat(AddressGuard.isPublic(InetAddress.getByName(address))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.215.14", "8.8.8.8", "172.32.0.1", "2606:4700::1111"})
    void allowsPublicAddresses(String address) throws Exception {
        assertThat(AddressGuard.isPublic(InetAddress.getByName(address))).isTrue();
    }
}
