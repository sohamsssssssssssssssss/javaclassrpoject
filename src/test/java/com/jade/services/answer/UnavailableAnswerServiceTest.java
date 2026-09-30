package com.jade.services.answer;

import com.jade.api.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UnavailableAnswerServiceTest {
    @Test void unavailableIsHonestAndCancellationIsHonored() throws Exception {
        var service = new UnavailableAnswerService();
        var request = new AnswerRequest(new GeneralQuestion("what is recursion?"));
        var result = service.answer(request, CancellationToken.NONE);
        assertEquals(AnswerStatus.UNAVAILABLE, result.status());
        assertEquals("Knowledge answering is not configured yet.", result.answerText());
        assertFalse(result.usedWeb()); assertTrue(result.evidence().isEmpty());
        assertEquals(ErrorCode.CANCELLED, assertThrows(ServiceException.class,
                () -> service.answer(request, () -> true)).error().code());
    }

    @Test void domainValuesValidateAndEvidenceIsImmutable() {
        assertThrows(IllegalArgumentException.class, () -> new GeneralQuestion(" "));
        assertThrows(IllegalArgumentException.class, () -> new GeneralQuestion("x".repeat(2049)));
        assertThrows(NullPointerException.class, () -> new AnswerRequest(null));
        assertThrows(IllegalArgumentException.class, () -> new AnswerResult(" ", AnswerStatus.ANSWERED, List.of(), false));
        var evidence = new ArrayList<AnswerResult.Evidence>();
        evidence.add(new AnswerResult.Evidence("Reference", "local:reference"));
        var result = new AnswerResult("Answer", AnswerStatus.ANSWERED, evidence, false);
        evidence.clear(); assertEquals(1, result.evidence().size());
        assertThrows(UnsupportedOperationException.class, () -> result.evidence().clear());
    }
}
