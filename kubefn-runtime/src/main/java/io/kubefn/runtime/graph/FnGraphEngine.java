package io.kubefn.runtime.graph;

import io.kubefn.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * The FnGraph Engine — runtime-owned composable execution pipelines.
 *
 * <p>Functions chain together at heap speed. The runtime owns the graph,
 * can trace it, and in future versions optimize it (fuse hot paths,
 * elide allocations, memoize subgraphs).
 *
 * <p>All pipeline steps execute on virtual threads. Parallel steps
 * run concurrently. Sequential steps pass the request through the chain.
 */
public class FnGraphEngine implements FnPipeline {

    private static final Logger log = LoggerFactory.getLogger(FnGraphEngine.class);

    private final Map<Class<? extends KubeFnHandler>, KubeFnHandler> functionRegistry;
    private final List<PipelineStep> steps = new ArrayList<>();

    public FnGraphEngine(Map<Class<? extends KubeFnHandler>, KubeFnHandler> functionRegistry) {
        this.functionRegistry = functionRegistry;
    }

    @Override
    public FnPipeline step(Class<? extends KubeFnHandler> handlerClass) {
        steps.add(new SequentialStep(handlerClass));
        return this;
    }

    @Override
    @SafeVarargs
    @SuppressWarnings("unchecked")
    public final FnPipeline parallel(Class<? extends KubeFnHandler>... handlerClasses) {
        steps.add(new ParallelStep(List.of(handlerClasses)));
        return this;
    }

    @Override
    public ExecutablePipeline build() {
        List<PipelineStep> frozenSteps = List.copyOf(steps);
        return new ExecutablePipelineImpl(frozenSteps, functionRegistry);
    }

    // --- Internal step types ---

    sealed interface PipelineStep permits SequentialStep, ParallelStep {}

    record SequentialStep(Class<? extends KubeFnHandler> handlerClass) implements PipelineStep {}
    record ParallelStep(List<Class<? extends KubeFnHandler>> handlerClasses) implements PipelineStep {}

    // --- Executable pipeline ---

    static class ExecutablePipelineImpl implements ExecutablePipeline {

        private final List<PipelineStep> steps;
        private final Map<Class<? extends KubeFnHandler>, KubeFnHandler> registry;

        ExecutablePipelineImpl(List<PipelineStep> steps,
                              Map<Class<? extends KubeFnHandler>, KubeFnHandler> registry) {
            this.steps = steps;
            this.registry = registry;
        }

        @Override
        public KubeFnResponse execute(KubeFnRequest request) {
            long startNanos = System.nanoTime();
            KubeFnResponse lastResponse = null;

            for (PipelineStep step : steps) {
                try {
                    if (step instanceof SequentialStep seq) {
                        lastResponse = executeHandler(seq.handlerClass(), request);
                    } else if (step instanceof ParallelStep par) {
                        lastResponse = executeParallel(par.handlerClasses(), request);
                    }
                } catch (Exception e) {
                    log.error("Pipeline step failed: {}", step, e);
                    return KubeFnResponse.error(Map.of(
                            "error", "Pipeline step failed: " + e.getMessage(),
                            "step", step.toString()
                    ));
                }
            }

            long durationMicros = (System.nanoTime() - startNanos) / 1000;
            log.debug("Pipeline executed {} steps in {}μs", steps.size(), durationMicros);

            return lastResponse != null ? lastResponse : KubeFnResponse.noContent();
        }

        @Override
        public List<String> steps() {
            return steps.stream().map(step -> {
                if (step instanceof SequentialStep seq) {
                    return seq.handlerClass().getSimpleName();
                } else if (step instanceof ParallelStep par) {
                    return "parallel(" + par.handlerClasses().stream()
                            .map(Class::getSimpleName)
                            .collect(Collectors.joining(", ")) + ")";
                }
                return "unknown";
            }).toList();
        }

        private KubeFnResponse executeHandler(Class<? extends KubeFnHandler> handlerClass,
                                              KubeFnRequest request) throws Exception {
            KubeFnHandler handler = registry.get(handlerClass);
            if (handler == null) {
                throw new IllegalStateException(
                        "No handler registered for: " + handlerClass.getName());
            }
            return handler.handle(request);
        }

        private KubeFnResponse executeParallel(List<Class<? extends KubeFnHandler>> handlerClasses,
                                               KubeFnRequest request) throws Exception {
            // Execute all parallel steps on virtual threads
            try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
                List<StructuredTaskScope.Subtask<KubeFnResponse>> subtasks = new ArrayList<>();

                for (Class<? extends KubeFnHandler> handlerClass : handlerClasses) {
                    subtasks.add(scope.fork(() -> executeHandler(handlerClass, request)));
                }

                scope.join();
                scope.throwIfFailed();

                // Collect all responses — return the last one
                // (in future: merge strategy, response combiner)
                KubeFnResponse last = null;
                for (var subtask : subtasks) {
                    last = subtask.get();
                }
                return last;
            }
        }
    }
}
