package com.company.rtdad.consumer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class RollingWindowTest {

    @Test
    void evictsOldestWhenOverCapacity() {
        RollingWindow window = new RollingWindow(3, 1);

        window.add(1);
        window.add(2);
        window.add(3);
        window.add(4);

        assertThat(window.size()).isEqualTo(3);
        assertThat(window.maxSamples()).isEqualTo(3);
    }

    @Test
    void meanAndStddevMatchReferenceComputation() {
        RollingWindow window = new RollingWindow(8, 1);
        double[] values = {2, 4, 4, 4, 5, 5, 7, 9};
        for (double v : values) {
            window.add(v);
        }

        assertThat(window.mean()).isCloseTo(5.0, within(1e-9));
        assertThat(window.stddev()).isCloseTo(2.138, within(1e-3));
    }

    @Test
    void isWarmReflectsMinSamplesThreshold() {
        RollingWindow window = new RollingWindow(10, 3);

        window.add(1);
        window.add(2);
        assertThat(window.isWarm()).isFalse();

        window.add(3);
        assertThat(window.isWarm()).isTrue();
    }

    @Test
    void flatWindowHasZeroStddev() {
        RollingWindow window = new RollingWindow(3, 1);
        window.add(5);
        window.add(5);
        window.add(5);

        assertThat(window.stddev()).isEqualTo(0.0);
    }

    @Test
    void reseedClearsPriorStateAndAdmitsNewValues() {
        RollingWindow window = new RollingWindow(3, 1);
        window.add(1);
        window.add(2);
        window.add(3);

        window.reseed(new double[] {7.0, 7.0, 7.0}, 3);

        assertThat(window.size()).isEqualTo(3);
        assertThat(window.mean()).isEqualTo(7.0);
    }
}
