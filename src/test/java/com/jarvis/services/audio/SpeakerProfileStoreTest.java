package com.jarvis.services.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Speaker profile persistence: enrollment merge, reload and failure handling. No audio involved. */
class SpeakerProfileStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyStoreHasNoProfiles() throws Exception {
        SpeakerProfileStore store = new SpeakerProfileStore(tempDir.resolve("speakers"));
        assertNull(store.meanVector(SpeakerIdentity.SOHAM));
        assertNull(store.meanVector(SpeakerIdentity.VED));
    }

    @Test
    void enrollmentPersistsMeanAndSurvivesReload() throws Exception {
        Path dir = tempDir.resolve("speakers");
        SpeakerProfileStore store = new SpeakerProfileStore(dir);
        store.mergeVectors(SpeakerIdentity.SOHAM, List.of(new double[]{1.0, 0.0, 0.5}));

        double[] mean = store.meanVector(SpeakerIdentity.SOHAM);
        assertNotNull(mean);
        assertEquals(3, mean.length);
        assertEquals(1.0, mean[0]);

        // a fresh store instance over the same directory reloads the profile
        SpeakerProfileStore reloaded = new SpeakerProfileStore(dir);
        double[] reloadedMean = reloaded.meanVector(SpeakerIdentity.SOHAM);
        assertNotNull(reloadedMean);
        assertEquals(1.0, reloadedMean[0]);
    }

    @Test
    void repeatedEnrollmentMergesIntoRunningMean() throws Exception {
        SpeakerProfileStore store = new SpeakerProfileStore(tempDir.resolve("speakers"));
        store.mergeVectors(SpeakerIdentity.VED, List.of(new double[]{2.0, 0.0}));
        store.mergeVectors(SpeakerIdentity.VED, List.of(new double[]{4.0, 2.0}));
        double[] mean = store.meanVector(SpeakerIdentity.VED);
        assertEquals(3.0, mean[0], 1e-9);
        assertEquals(1.0, mean[1], 1e-9);
    }

    @Test
    void emptyEnrollmentIsRejected() {
        SpeakerProfileStore store = new SpeakerProfileStore(tempDir.resolve("speakers"));
        assertThrows(AudioException.class,
                () -> store.mergeVectors(SpeakerIdentity.SOHAM, List.of()));
    }

    @Test
    void inconsistentDimensionsAreRejected() {
        SpeakerProfileStore store = new SpeakerProfileStore(tempDir.resolve("speakers"));
        assertThrows(AudioException.class, () -> store.mergeVectors(SpeakerIdentity.SOHAM,
                List.of(new double[]{1.0, 2.0}, new double[]{1.0, 2.0, 3.0})));
    }
}
