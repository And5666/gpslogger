package com.mendhak.gpslogger.common;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import androidx.test.filters.SmallTest;
import org.junit.Test;

@SmallTest
public class DeepSleepWindowTest {

    private static int at(int hour, int minute) {
        return hour * 3600 + minute * 60;
    }

    private static final int H23 = 23 * 60;
    private static final int H7 = 7 * 60;

    @Test
    public void windowAcrossMidnight() {
        assertThat(DeepSleepWindow.contains(H23, H7, at(22, 59)), is(false));
        assertThat(DeepSleepWindow.contains(H23, H7, at(23, 0)), is(true));
        assertThat(DeepSleepWindow.contains(H23, H7, at(0, 0)), is(true));
        assertThat(DeepSleepWindow.contains(H23, H7, at(6, 59)), is(true));
        assertThat(DeepSleepWindow.contains(H23, H7, at(7, 0)), is(false));
        assertThat(DeepSleepWindow.contains(H23, H7, at(12, 0)), is(false));
    }

    @Test
    public void windowWithinOneDay() {
        assertThat(DeepSleepWindow.contains(60, 300, at(0, 30)), is(false));
        assertThat(DeepSleepWindow.contains(60, 300, at(1, 0)), is(true));
        assertThat(DeepSleepWindow.contains(60, 300, at(4, 59)), is(true));
        assertThat(DeepSleepWindow.contains(60, 300, at(5, 0)), is(false));
    }

    @Test
    public void sameStartAndEnd_IsEmpty() {
        assertThat(DeepSleepWindow.contains(H7, H7, at(7, 0)), is(false));
        assertThat(DeepSleepWindow.contains(H7, H7, at(3, 0)), is(false));
    }

    @Test
    public void secondsUntil_WrapsPastMidnight() {
        assertThat(DeepSleepWindow.secondsUntil(H7, at(23, 0)), is(8 * 3600));
        assertThat(DeepSleepWindow.secondsUntil(H7, at(6, 0)), is(3600));
        assertThat(DeepSleepWindow.secondsUntil(H7, at(7, 0)), is(24 * 3600));
    }

    @Test
    public void interval_IsCutShortAtTheEndOfTheWindow() {
        // 5 h from 23:30 would be 04:30, fine
        assertThat(DeepSleepWindow.intervalSeconds(300, H7, at(23, 30)), is(300 * 60));
        // 5 h from 04:30 would be 09:30, so it's cut to just after 07:00
        assertThat(DeepSleepWindow.intervalSeconds(300, H7, at(4, 30)), is(150 * 60 + 5));
    }

    @Test
    public void format_PadsHoursAndMinutes() {
        assertThat(DeepSleepWindow.format(H7), is("07:00"));
        assertThat(DeepSleepWindow.format(23 * 60 + 5), is("23:05"));
    }
}
