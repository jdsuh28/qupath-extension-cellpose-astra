package qupath.ext.astra;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowBuilderTest {

    @Test
    void failureStopsAtOneStepAndMarksDownstreamStepsNotRun() {
        WorkflowBuilder.NotebookRunState state =
                new WorkflowBuilder.NotebookRunState();
        state.reset(4);
        state.markSucceeded(0);
        state.markFailed(1, "Channel was not found.");

        assertEquals(
                List.of(
                        WorkflowBuilder.StepStatus.SUCCESS,
                        WorkflowBuilder.StepStatus.FAILED,
                        WorkflowBuilder.StepStatus.SKIPPED,
                        WorkflowBuilder.StepStatus.SKIPPED),
                statuses(state));
        assertEquals("Channel was not found.", state.step(1).message());
        assertEquals("Not run", state.step(2).message());
        assertEquals(1, state.failedIndex());
    }

    @Test
    void resumePreservesCompletedUpstreamAndResetsOnlyTheRemainingSteps() {
        WorkflowBuilder.NotebookRunState state =
                new WorkflowBuilder.NotebookRunState();
        state.reset(4);
        state.markSucceeded(0);
        state.markFailed(1, "Channel was not found.");

        state.prepareFrom(4, 1);

        assertEquals(
                List.of(
                        WorkflowBuilder.StepStatus.SUCCESS,
                        WorkflowBuilder.StepStatus.PENDING,
                        WorkflowBuilder.StepStatus.PENDING,
                        WorkflowBuilder.StepStatus.PENDING),
                statuses(state));
        assertEquals(-1, state.failedIndex());
    }

    @Test
    void cancellationMarksTheActiveStepAndLeavesDownstreamUnrun() {
        WorkflowBuilder.NotebookRunState state =
                new WorkflowBuilder.NotebookRunState();
        state.reset(3);
        state.markSucceeded(0);
        state.markCancelled(1);

        assertEquals(
                List.of(
                        WorkflowBuilder.StepStatus.SUCCESS,
                        WorkflowBuilder.StepStatus.CANCELLED,
                        WorkflowBuilder.StepStatus.SKIPPED),
                statuses(state));
    }

    @Test
    void builderRendersInlineFailureAndResumesFromThatExactCard()
            throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/qupath/ext/astra/WorkflowBuilder.java"));
        String css = Files.readString(Path.of(
                "src/main/resources/qupath/ext/astra/launcher.css"));

        assertTrue(source.contains("resume.setOnAction(event -> runFrom(index))"));
        assertTrue(source.contains("notebook.markFailed(failedIndex, message)"));
        assertTrue(source.contains("Completed earlier steps will not run again."));
        assertTrue(source.contains("static void showFailurePreview"));
        assertTrue(css.contains(".astra-workflow-step-failed"));
        assertTrue(css.contains(".astra-workflow-step-message-failed"));
        assertTrue(css.contains(".astra-workflow-resume-button"));
    }

    private static List<WorkflowBuilder.StepStatus> statuses(
            WorkflowBuilder.NotebookRunState state) {
        return state.snapshot().stream()
                .map(WorkflowBuilder.NotebookStep::status)
                .toList();
    }
}
