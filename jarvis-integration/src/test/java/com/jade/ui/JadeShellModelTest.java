package com.jade.ui;

import com.jade.api.ProgressStage;
import com.jade.services.audio.VoiceState;
import com.jade.ui.shell.CoreMotionProfile;
import com.jade.ui.shell.JadeShellModel;
import com.jade.ui.shell.MotionPreferences;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JadeShellModelTest {
    @Test
    void navigationAndStatusReflectObservedState() {
        JadeShellModel model = new JadeShellModel();
        assertEquals(JadeShellModel.Destination.HOME, model.navigation());
        model.navigate(JadeShellModel.Destination.PROJECT);
        model.setAppState(VoiceState.EXECUTING);
        model.setVoiceAvailable(false);
        assertEquals(JadeShellModel.Destination.PROJECT, model.navigation());
        assertEquals("UNAVAILABLE", model.statusChips().get(0).value());
        assertEquals(3, model.statusChips().size());
        assertEquals("EXECUTING", model.motionProfile().stateLabel());
    }

    @Test
    void errorStateProducesErrorChip() {
        JadeShellModel model = new JadeShellModel();
        model.setVoiceState(VoiceState.ERROR);
        JadeShellModel.StatusChip voice = model.statusChips().get(0);
        assertEquals("ERROR", voice.value());
        assertEquals("error", voice.tone());
    }

    @Test
    void projectChipOnlyReportsObservedProjects() {
        JadeShellModel model = new JadeShellModel();
        JadeShellModel.StatusChip none = model.statusChips().get(1);
        assertEquals("PROJECT", none.name());
        assertEquals("NONE", none.value());
        assertEquals("muted", none.tone());
        model.setObservedProject("jarvis-integration");
        JadeShellModel.StatusChip observed = model.statusChips().get(1);
        assertEquals("JARVIS-INTEGRATION", observed.value());
        assertEquals("accent", observed.tone());
        model.setObservedProject("   ");
        assertEquals("NONE", model.statusChips().get(1).value());
    }

    @Test
    void realProgressMapsToCoreStates() {
        assertEquals(VoiceState.PROCESSING, JadeShellModel.forProgress(ProgressStage.PARSING));
        assertEquals(VoiceState.PROCESSING, JadeShellModel.forProgress(ProgressStage.PLANNING));
        assertEquals(VoiceState.EXECUTING, JadeShellModel.forProgress(ProgressStage.AWAITING_CONFIRMATION));
        assertEquals(VoiceState.EXECUTING, JadeShellModel.forProgress(ProgressStage.EXECUTING));
        assertTrue(CoreMotionProfile.forState(VoiceState.ERROR).settle());
    }

    @Test
    void typedActivityDoesNotClaimVoiceActivity() {
        JadeShellModel model = new JadeShellModel();
        model.setAppState(VoiceState.EXECUTING);
        assertEquals("READY", model.statusChips().get(0).value());
        model.setVoiceState(VoiceState.LISTENING);
        assertEquals("LISTENING", model.statusChips().get(0).value());
    }

    @Test
    void shortcutsUseExistingSupportedPlans() throws Exception {
        var parser = new com.jade.core.CommandParser();
        assertInstanceOf(com.jade.core.CommandPlan.SystemStatus.class, parser.parse(JadeShellModel.Suggestion.SYSTEM.command()));
        assertInstanceOf(com.jade.core.CommandPlan.FindFiles.class, parser.parse(JadeShellModel.Suggestion.FILES.command()));
        assertInstanceOf(com.jade.core.CommandPlan.InspectProject.class, parser.parse(JadeShellModel.Suggestion.PROJECT.command()));
    }

    @Test
    void reducedMotionHasDistinctStaticSegmentsForActiveStates() {
        assertEquals(1, CoreMotionProfile.forState(VoiceState.IDLE).emphasizedSegments());
        assertEquals(2, CoreMotionProfile.forState(VoiceState.LISTENING).emphasizedSegments());
        assertEquals(7, CoreMotionProfile.forState(VoiceState.PROCESSING).emphasizedSegments());
        assertEquals(4, CoreMotionProfile.forState(VoiceState.EXECUTING).emphasizedSegments());
        assertTrue(CoreMotionProfile.forState(VoiceState.SPEAKING).waveform());
        assertTrue(CoreMotionProfile.forState(VoiceState.ERROR).settle());
    }

    @Test
    void reducedMotionStopsContinuousAnimationButRetainsState() {
        MotionPreferences motion = new MotionPreferences();
        motion.setMode(MotionPreferences.Mode.REDUCED);
        assertFalse(motion.animationsEnabled());
        assertEquals(0, motion.effectiveTick(40));
        assertEquals("LISTENING", CoreMotionProfile.forState(VoiceState.LISTENING).stateLabel());
    }
}
