package com.jarvis.services.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * File-backed speaker profile store: one text file per identity, each line
 * {@code <weight>:<value>:<weight>:<value>…}, holding the running mean
 * x-vector. Enrollment merges new samples into the stored mean; loading has
 * a clean seam (this class) so a future format change stays local. Profiles
 * live under the JARVIS data directory and are never committed to Git.
 */
public final class SpeakerProfileStore {

    private final Path directory;

    public SpeakerProfileStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /** Adds utterance vectors to an identity's running mean and persists it atomically. */
    public void mergeVectors(SpeakerIdentity identity, List<double[]> vectors) throws AudioException {
        Objects.requireNonNull(identity, "identity");
        if (vectors.isEmpty()) {
            throw new AudioException("No speaker vectors were extracted; the samples may be silent or too short");
        }
        int dimension = vectors.getFirst().length;
        for (double[] v : vectors) {
            if (v.length != dimension) {
                throw new AudioException("Inconsistent speaker vector dimensions: " + v.length + " vs " + dimension);
            }
        }
        double[] mean = mergedMean(readVectors(identity), vectors);
        writeMean(identity, mean);
    }

    /** Stored mean vector for an identity, or empty when not enrolled. */
    public double[] meanVector(SpeakerIdentity identity) throws AudioException {
        List<double[]> stored = readVectors(identity);
        if (stored.isEmpty()) {
            return null;
        }
        return mergedMean(stored, List.of());
    }

    private double[] mergedMean(List<double[]> existing, List<double[]> added) {
        if (existing.isEmpty() && added.isEmpty()) {
            return null;
        }
        List<double[]> all = new ArrayList<>(existing);
        all.addAll(added);
        int dimension = all.getFirst().length;
        double[] out = new double[dimension];
        for (double[] v : all) {
            for (int i = 0; i < dimension; i++) {
                out[i] += v[i];
            }
        }
        for (int i = 0; i < dimension; i++) {
            out[i] /= all.size();
        }
        return out;
    }

    private List<double[]> readVectors(SpeakerIdentity identity) throws AudioException {
        Path file = directory.resolve(identity.name() + ".spk");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            String line = Files.readString(file, StandardCharsets.ISO_8859_1).strip();
            if (line.isEmpty()) {
                return List.of();
            }
            String[] tokens = line.split(":");
            if (tokens.length < 2 || tokens.length % 2 != 0) {
                throw new AudioException("Corrupt speaker profile for " + identity + ": " + file);
            }
            int dimension = tokens.length / 2;
            double[] vector = new double[dimension];
            for (int i = 0; i < dimension; i++) {
                vector[i] = Double.parseDouble(tokens[2 * i + 1]);
            }
            return List.of(vector);
        } catch (IOException | NumberFormatException e) {
            throw new AudioException("Could not read speaker profile for " + identity + ": " + e.getMessage(), e);
        }
    }

    private void writeMean(SpeakerIdentity identity, double[] mean) throws AudioException {
        Path file = directory.resolve(identity.name() + ".spk");
        try {
            Files.createDirectories(directory);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mean.length; i++) {
                sb.append("1:").append(mean[i]);
                if (i < mean.length - 1) {
                    sb.append(':');
                }
            }
            Path temp = directory.resolve(identity.name() + ".spk.tmp");
            Files.writeString(temp, sb.toString(), StandardCharsets.ISO_8859_1);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new AudioException("Could not persist speaker profile for " + identity + ": " + e.getMessage(), e);
        }
    }
}
