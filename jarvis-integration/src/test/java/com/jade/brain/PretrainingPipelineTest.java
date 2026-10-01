package com.jade.brain;

import com.jade.brain.tokenizer.*;
import com.jade.brain.training.PretrainingDataset;
import com.jade.brain.training.EnglishCorpus;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PretrainingPipelineTest {
    @TempDir Path temp;
    private Path artifact() { return Path.of("data/tokenizer/jade-bpe-1024.v1"); }

    @Test void tokenizerArtifactAndGoldenRoundTrip() throws Exception {
        var loaded = TokenizerArtifact.load(artifact());
        assertEquals(1024, loaded.model().vocabularySize());
        var tokenizer = new ByteBpeTokenizer(loaded.model());
        var lines = Files.readAllLines(Path.of("docs/pretraining-v1/tokenizer-golden.tsv"));
        assertTrue(lines.size() >= 13);
        for (String line : lines.subList(1, lines.size())) {
            String[] columns = line.split("\t", -1);
            String text = columns[0].replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
            int[] expected = Arrays.stream(columns[1].split(",")).mapToInt(Integer::parseInt).toArray();
            assertArrayEquals(expected, tokenizer.encode(text));
            assertEquals(text, tokenizer.decode(expected));
        }
        Path copy = temp.resolve("copy.bpe");
        assertEquals(loaded.digest(), TokenizerArtifact.save(loaded.model(), loaded.corpusDigest(), copy));
        byte[] changed = Files.readAllBytes(copy); changed[20] ^= 1; Files.write(copy, changed);
        assertThrows(java.io.IOException.class, () -> TokenizerArtifact.load(copy));
    }

    @Test void artifactMatchesOriginalTrainOnlyFit() throws Exception {
        var limits = new EnglishCorpus.Limits(1_048_576, 8_388_608, 4096, 128, 65_536, 2_000_000);
        var manifest = EnglishCorpus.scan(Path.of("src/test/resources/brain/diverse-english"), limits);
        var split = EnglishCorpus.split(manifest, .82, .09);
        assertEquals(TokenizerArtifact.load(artifact()).corpusDigest(), manifest.digest());
        var refit = new BpeTrainer().train(EnglishCorpus.balancedFittingText(manifest, split, limits), 1024);
        assertEquals(refit.merges(), TokenizerArtifact.load(artifact()).model().merges());
    }

    @Test void manifestSplitDuplicatesAndRejections() throws Exception {
        Path raw = Files.createDirectory(temp.resolve("raw"));
        String prose = "The small machine stores words and reads them in order.\n".repeat(12);
        for (int i = 1; i <= 3; i++) Files.writeString(raw.resolve("0" + i + ".txt"),
                prose + "Document " + i + " has a distinct final sentence.\n");
        Files.writeString(raw.resolve("04-duplicate.txt"), prose + "Document 1 has a distinct final sentence.\n");
        Files.write(raw.resolve("05-invalid.txt"), new byte[] {(byte) 0xc3, (byte) 0x28});
        Files.writeString(raw.resolve("06-control.txt"), prose + "\u0001");
        Files.writeString(raw.resolve("07-long.txt"), "The long line has English words. ".repeat(1000));
        var result = PretrainingDataset.prepare(raw, artifact(), temp.resolve("processed"));
        assertArrayEquals(new long[] {1, 1, 1}, result.documents());
        assertEquals(1, result.duplicateDocuments());
        assertEquals(4, result.entries().stream().filter(e -> !e.accepted()).count());
        assertTrue(result.crossPartitionLines() > 0);
        assertTrue(result.tokens()[0] > 32 && result.tokens()[1] > 32 && result.tokens()[2] > 32);
        String manifest = Files.readString(temp.resolve("processed/manifest.tsv"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("duplicate document") && manifest.contains("invalid UTF-8") &&
                manifest.contains("disallowed control character") && manifest.contains("line length exceeded"));
        var again = PretrainingDataset.prepare(raw, artifact(), temp.resolve("processed-again"));
        assertEquals(result.datasetSha256(), again.datasetSha256());
    }

    @Test void overDepthTreeIsExplicitlyRejected() throws Exception {
        Path raw = Files.createDirectory(temp.resolve("deep-raw")), directory = raw;
        for (int i = 0; i < 9; i++) directory = Files.createDirectory(directory.resolve("level" + i));
        Files.writeString(directory.resolve("sample.txt"), "The document is hidden too deep.\n");
        assertTrue(assertThrows(java.io.IOException.class,
                () -> PretrainingDataset.prepare(raw, artifact(), temp.resolve("deep-processed")))
                .getMessage().contains("depth limit"));
    }
}
