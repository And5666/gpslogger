package com.mendhak.gpslogger.common;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import androidx.test.filters.SmallTest;
import org.junit.Test;

@SmallTest
public class ProviderFilterTest {

    private static boolean accept(String listener, String provider, boolean passive,
                                  boolean gps, boolean network, boolean fused, boolean logPassive) {
        return ProviderFilter.isFromSelectedListener(listener, provider, passive, gps, network, fused, logPassive, true);
    }

    @Test
    public void fusedOnly_FusedListenerWithGpsProviderName_Accepted() {
        // The fused provider can pass on the underlying GPS fix with the "gps" provider name
        assertThat(accept("FUSED", "gps", false, false, false, true, false), is(true));
        assertThat(accept("FUSED", "network", false, false, false, true, false), is(true));
        assertThat(accept("FUSED", "fused", false, false, false, true, false), is(true));
    }

    @Test
    public void fusedListener_FusedNotSelected_Rejected() {
        assertThat(accept("FUSED", "fused", false, true, false, false, false), is(false));
    }

    @Test
    public void fusedListener_BelowAndroid12_Rejected() {
        assertThat(ProviderFilter.isFromSelectedListener("FUSED", "fused", false,
                false, false, true, false, false), is(false));
    }

    @Test
    public void gpsAndCellListeners_FollowTheirOwnSetting() {
        assertThat(accept("GPS", "gps", false, true, false, false, false), is(true));
        assertThat(accept("GPS", "gps", false, false, true, false, false), is(false));
        assertThat(accept("CELL", "network", false, false, true, false, false), is(true));
        assertThat(accept("CELL", "network", false, true, false, false, false), is(false));
    }

    @Test
    public void passive_JudgedByProviderName() {
        // Passive fixes come from other apps, so the listener name says nothing about the source
        assertThat(accept("PASSIVE", "gps", true, true, false, false, true), is(true));
        assertThat(accept("PASSIVE", "network", true, true, false, false, true), is(false));
        assertThat(accept("PASSIVE", "fused", true, false, false, true, true), is(true));
    }

    @Test
    public void onlyPassiveSelected_AcceptsEverything() {
        assertThat(accept("PASSIVE", "network", true, false, false, false, true), is(true));
        assertThat(accept("PASSIVE", "network", true, false, false, false, false), is(false));
    }

    @Test
    public void unknownListener_FallsBackToProviderName() {
        assertThat(accept(null, "gps", false, true, false, false, false), is(true));
        assertThat(accept(null, null, false, true, false, false, false), is(false));
    }
}
