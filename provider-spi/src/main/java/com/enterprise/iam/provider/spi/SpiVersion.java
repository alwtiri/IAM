package com.enterprise.iam.provider.spi;

/** Version of the provider SPI contract. Major changes are breaking; minor changes are additive. */
public final class SpiVersion {

    public static final int MAJOR = 1;
    public static final int MINOR = 0;

    private SpiVersion() {
    }

    public static String current() {
        return MAJOR + "." + MINOR;
    }

    /** A provider built against {@code major.minor} is loadable if the major matches and its minor is not newer. */
    public static boolean isCompatible(int providerMajor, int providerMinor) {
        return providerMajor == MAJOR && providerMinor <= MINOR;
    }
}
