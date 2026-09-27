package com.jade.ui.shell;

import com.jade.services.audio.VoiceState;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The abstract "computational core": concentric rings, slow rotating arc
 * geometry, orbiting node dots, a breathing center and a listening pulse —
 * built only from JavaFX primitives. It reacts exclusively to the real
 * application state handed to {@link #setState}: every motion decision comes
 * from the state's {@link CoreMotionProfile} gated by {@link MotionPreferences}
 * (reduced motion stops all continuous animation). Only transforms and
 * opacity change per frame; geometry is built once.
 */
public final class VisualCore extends StackPane {

    private static final double RING_RADII = 112.0;

    private final MotionPreferences motion;
    private final Group geometry = new Group();
    private final Group ringGroup = new Group();
    private final Group arcGroup = new Group();
    private final Group dotGroup = new Group();
    private final Circle pulseRing = new Circle(RING_RADII);
    private final Circle center = new Circle(14);
    private final List<Circle> dots = new ArrayList<>();

    private Timeline rotationTimeline;
    private Timeline breathTimeline;
    private Timeline pulseTimeline;
    private Timeline waveTimeline;
    private CoreMotionProfile profile = CoreMotionProfile.forState(VoiceState.IDLE);
    private double breathPhase;
    private double wavePhase;

    public VisualCore(MotionPreferences motion) {
        this.motion = Objects.requireNonNull(motion, "motion");
        getStyleClass().add("visual-core");

        buildRings();
        buildArcs();
        buildDots();
        pulseRing.getStyleClass().add("core-pulse");
        pulseRing.setFill(Color.TRANSPARENT);
        pulseRing.setOpacity(0);
        center.getStyleClass().add("core-center");

        geometry.getChildren().addAll(ringGroup, pulseRing, arcGroup, dotGroup, center);
        geometry.setManaged(false);
        getChildren().add(geometry);
        widthProperty().addListener(ignored -> applyResponsiveScale());
        heightProperty().addListener(ignored -> applyResponsiveScale());
        setState(VoiceState.IDLE);
    }

    /**
     * Scales the fixed geometry down when the core is given less room than
     * its natural size (small windows). Uses one scale transform updated only
     * on resize — never per frame — so the scene graph stays static.
     */
    private void applyResponsiveScale() {
        double natural = RING_RADII * 2 + 28;
        double available = Math.min(getWidth(), getHeight());
        double scale = available <= 0 || Double.isNaN(available) ? 1.0 : Math.min(1.0, available / natural);
        geometry.setLayoutX(getWidth() / 2.0);
        geometry.setLayoutY(getHeight() / 2.0);
        geometry.setScaleX(scale);
        geometry.setScaleY(scale);
    }

    private void buildRings() {
        for (double radius : new double[] {RING_RADII, RING_RADII * 0.72, RING_RADII * 0.46}) {
            Circle ring = new Circle(radius);
            ring.setFill(Color.TRANSPARENT);
            ring.getStyleClass().add(radius == RING_RADII ? "core-ring-outer" : "core-ring");
            ringGroup.getChildren().add(ring);
        }
    }

    private void buildArcs() {
        double[][] specs = { {RING_RADII * 0.86, 100, 0}, {RING_RADII * 0.60, 140, 120}, {RING_RADII * 0.33, 80, 240} };
        for (double[] spec : specs) {
            javafx.scene.shape.Arc arc = new javafx.scene.shape.Arc(0, 0, spec[0], spec[0], spec[2], spec[1]);
            arc.setFill(Color.TRANSPARENT);
            arc.getStyleClass().add("core-arc");
            arc.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            arcGroup.getChildren().add(arc);
        }
    }

    private void buildDots() {
        int count = 12;
        for (int index = 0; index < count; index += 1) {
            double angle = Math.toRadians(index * (360.0 / count));
            double radius = RING_RADII * 0.72;
            Circle dot = new Circle(Math.cos(angle) * radius, Math.sin(angle) * radius, 1.8);
            dot.getStyleClass().add("core-dot");
            dots.add(dot);
            dotGroup.getChildren().add(dot);
        }
    }

    /** Applies one real application state to the core's motion and tone. */
    public void setState(VoiceState state) {
        VoiceState safe = state == null ? VoiceState.IDLE : state;
        this.profile = CoreMotionProfile.forState(safe);
        getStyleClass().removeIf(c -> c.startsWith("core-state-"));
        getStyleClass().add("core-state-" + safe.toString().toLowerCase(java.util.Locale.ROOT));
        applyMotion();
    }

    /** The profile currently driving the core (exposed for tests/debug). */
    public CoreMotionProfile profile() {
        return profile;
    }

    private void applyMotion() {
        stopTimelines();
        boolean enabled = motion.animationsEnabled();
        if (!enabled) arcGroup.setRotate(0);
        center.setOpacity(profile.state() == VoiceState.IDLE ? 0.78 : 1.0);
        if (!enabled && profile.state() == VoiceState.SPEAKING) {
            center.setScaleX(1.18);
            center.setScaleY(1.18);
        }
        for (int index = 0; index < dots.size(); index++) {
            dots.get(index).setOpacity(switch (profile.state()) {
                case IDLE -> 0.35;
                case LISTENING -> index < 4 ? 1.0 : 0.3;
                case PROCESSING -> 0.9;
                case EXECUTING -> index % 3 == 0 ? 1.0 : 0.25;
                case SPEAKING -> 0.6;
                case ERROR -> 0.25;
            });
        }
        for (int index = 0; index < arcGroup.getChildren().size(); index++) {
            Node segment = arcGroup.getChildren().get(index);
            segment.getStyleClass().remove("core-segment-active");
            if ((profile.emphasizedSegments() & (1 << index)) != 0) {
                segment.getStyleClass().add("core-segment-active");
            }
        }

        if (enabled && !profile.settle() && profile.rotationStepDeg() > 0) {
            rotationTimeline = new Timeline(new KeyFrame(
                    Duration.millis(motion.effectiveTick(com.jade.ui.shell.MotionPreferences.CORE_ROTATE_TICK_MILLIS)),
                    event -> arcGroup.setRotate(arcGroup.getRotate() + profile.rotationStepDeg())));
            rotationTimeline.setCycleCount(Animation.INDEFINITE);
            rotationTimeline.play();
        }

        if (enabled && !profile.settle() && profile.breatheAmplitude() > 0) {
            breathPhase = 0;
            breathTimeline = new Timeline(new KeyFrame(
                    Duration.millis(motion.effectiveTick(com.jade.ui.shell.MotionPreferences.CORE_BREATH_TICK_MILLIS)),
                    event -> {
                        breathPhase += 0.09;
                        double scale = 1.0 + profile.breatheAmplitude() * Math.sin(breathPhase);
                        ringGroup.setScaleX(scale);
                        ringGroup.setScaleY(scale);
                        center.setScaleX(scale);
                        center.setScaleY(scale);
                    }));
            breathTimeline.setCycleCount(Animation.INDEFINITE);
            breathTimeline.play();
        }

        if (enabled && profile.listeningPulse()) {
            pulseRing.setOpacity(0.4);
            pulseTimeline = new Timeline(new KeyFrame(
                    Duration.millis(motion.effectiveTick(com.jade.ui.shell.MotionPreferences.CORE_LISTEN_TICK_MILLIS)),
                    event -> {
                        double phase = (pulseRing.getScaleX() - 1.0) / 0.30;
                        double next = phase >= 1.0 ? 0.0 : phase + 0.08;
                        pulseRing.setScaleX(1.0 + next * 0.30);
                        pulseRing.setScaleY(1.0 + next * 0.30);
                        pulseRing.setOpacity(0.45 * (1.0 - next));
                    }));
            pulseTimeline.setCycleCount(Animation.INDEFINITE);
            pulseTimeline.play();
        } else {
            pulseRing.setOpacity(profile.listeningPulse() ? 0.45 : 0);
        }

        if (enabled && profile.waveform()) {
            wavePhase = 0;
            waveTimeline = new Timeline(new KeyFrame(
                    Duration.millis(motion.effectiveTick(com.jade.ui.shell.MotionPreferences.CORE_SPEAK_TICK_MILLIS)),
                    event -> {
                        wavePhase += 0.35;
                        for (int index = 0; index < dots.size(); index += 1) {
                            double wave = Math.sin(wavePhase + index * 0.7);
                            dots.get(index).setOpacity(0.35 + 0.55 * (0.5 + 0.5 * wave));
                        }
                    }));
            waveTimeline.setCycleCount(Animation.INDEFINITE);
            waveTimeline.play();
        }
    }

    private void stopTimelines() {
        for (Timeline timeline : new Timeline[] {rotationTimeline, breathTimeline, pulseTimeline, waveTimeline}) {
            if (timeline != null) {
                timeline.stop();
            }
        }
        rotationTimeline = null;
        breathTimeline = null;
        pulseTimeline = null;
        waveTimeline = null;
        ringGroup.setScaleX(1.0);
        ringGroup.setScaleY(1.0);
        center.setScaleX(1.0);
        center.setScaleY(1.0);
        pulseRing.setScaleX(1.0);
        pulseRing.setScaleY(1.0);
        pulseRing.setOpacity(0);
        for (Circle dot : dots) {
            dot.setOpacity(0.5);
        }
    }

    /** Stops all continuous animation (called when the shell is torn down). */
    public void shutdown() {
        stopTimelines();
    }

    /** The node count of the core, kept small and known for performance. */
    public static int budgetedNodeCount() {
        return 3 + 3 + 12 + 2 + 1;
    }

    /** Static accessor used by the shell to size the core consistently. */
    public static double preferredRadius() {
        return RING_RADII;
    }

    /** Visible nodes of this core (tests/debug). */
    public List<Node> layers() {
        return List.of(ringGroup, pulseRing, arcGroup, dotGroup, center);
    }
}
