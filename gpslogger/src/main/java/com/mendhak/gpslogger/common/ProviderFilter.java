package com.mendhak.gpslogger.common;

/**
 * Decides whether a fix comes from a location provider the user selected.
 * <p>
 * Fixes from GPSLogger's own listeners are judged by the listener they arrived on, not by
 * {@code Location.getProvider()}. Android's fused provider can pass on the underlying GPS or
 * network fix with that provider's name, which used to get every fused fix discarded when
 * only Fused was selected.
 * <p>
 * Passive fixes come from other apps, so for those only the provider name tells us the source.
 * Kept free of Android classes so it can be unit tested.
 */
public final class ProviderFilter {

    // Listener names given to GeneralLocationListener in GpsLoggingService
    public static final String LISTENER_GPS = "GPS";
    public static final String LISTENER_CELL = "CELL";
    public static final String LISTENER_FUSED = "FUSED";

    // Same values as LocationManager.GPS_PROVIDER, NETWORK_PROVIDER and FUSED_PROVIDER
    static final String PROVIDER_GPS = "gps";
    static final String PROVIDER_NETWORK = "network";
    static final String PROVIDER_FUSED = "fused";

    private ProviderFilter() { }

    public static boolean isFromSelectedListener(String listenerName, String providerName, boolean isPassive,
                                                 boolean logGps, boolean logNetwork, boolean logFused,
                                                 boolean logPassive, boolean fusedSupported) {

        if (!logGps && !logNetwork && !logFused) {
            // Special case - if satellite, fused, and network are deselected, but passive is selected, then accept all passive types!
            return logPassive;
        }

        if (!isPassive && listenerName != null) {
            if (listenerName.equalsIgnoreCase(LISTENER_FUSED)) {
                return fusedSupported && logFused;
            }
            if (listenerName.equalsIgnoreCase(LISTENER_GPS)) {
                return logGps;
            }
            if (listenerName.equalsIgnoreCase(LISTENER_CELL)) {
                return logNetwork;
            }
        }

        if (providerName == null) {
            return false;
        }
        if (fusedSupported && providerName.equalsIgnoreCase(PROVIDER_FUSED) && logFused) {
            return true;
        }
        if (providerName.equalsIgnoreCase(PROVIDER_GPS) && logGps) {
            return true;
        }
        return providerName.equalsIgnoreCase(PROVIDER_NETWORK) && logNetwork;
    }
}
