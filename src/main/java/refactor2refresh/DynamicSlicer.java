package refactor2refresh;

import soot.*;
import soot.jimple.*;
import soot.options.Options;
import soot.toolkits.graph.*;
import soot.toolkits.scalar.*;
import java.util.*;
import java.util.stream.Collectors;

public class DynamicSlicer {
    private final Map<String, UnitGraph> methodToGraph = new HashMap<>();
    private final Map<Unit, Set<Unit>> dependencyCache = new HashMap<>();
    private final List<SlicingEvent> executionTrace = new ArrayList<>();

    public static class SlicingEvent {
        final String className;
        final String methodName;
        final int lineNumber;
        final long timestamp;
        final String eventType;  // "methodEntry", "methodExit", "varWrite", "varRead"
        final String varName;    // for variable events
        final Object value;      // for variable events

        public SlicingEvent(String className, String methodName, int lineNumber,
                            String eventType, String varName, Object value) {
            this.className = className;
            this.methodName = methodName;
            this.lineNumber = lineNumber;
            this.timestamp = System.nanoTime();
            this.eventType = eventType;
            this.varName = varName;
            this.value = value;
        }
    }

    // Initialize Soot and set up analysis
    public void initialize(String classPath, String className) {
        G.reset();
        Options.v().set_prepend_classpath(true);
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_soot_classpath(classPath);
        Options.v().set_output_format(Options.output_format_none);
        Scene.v().loadNecessaryClasses();

        SootClass sc = Scene.v().loadClassAndSupport(className);
        sc.setApplicationClass();

        // Build dependency graphs for each method
        for (SootMethod method : sc.getMethods()) {
            if (method.hasActiveBody()) {
                Body body = method.retrieveActiveBody();
                UnitGraph graph = new ExceptionalUnitGraph(body);
                methodToGraph.put(method.getSignature(), graph);
                buildDependencyCache(graph);
            }
        }
    }

    // Build static dependency information
    private void buildDependencyCache(UnitGraph graph) {
        for (Unit unit : graph) {
            Set<Unit> dependencies = new HashSet<>();

            // Data dependencies
            for (ValueBox valueBox : unit.getUseBoxes()) {
                Value value = valueBox.getValue();
                if (value instanceof Local) {
                    Local local = (Local) value;
                    dependencies.addAll(findLastDefinition(graph, unit, local));
                }
            }

            // Control dependencies
            if (unit instanceof IfStmt || unit instanceof SwitchStmt) {
                dependencies.addAll(getControlDependencies(graph, unit));
            }

            dependencyCache.put(unit, dependencies);
        }
    }

    // Find the last definition of a variable
    private Set<Unit> findLastDefinition(UnitGraph graph, Unit current, Local local) {
        Set<Unit> definitions = new HashSet<>();
        Set<Unit> visited = new HashSet<>();
        Queue<Unit> worklist = new LinkedList<>();

        worklist.add(current);
        visited.add(current);

        while (!worklist.isEmpty()) {
            Unit unit = worklist.poll();

            for (Unit pred : graph.getPredsOf(unit)) {
                if (pred instanceof DefinitionStmt) {
                    DefinitionStmt def = (DefinitionStmt) pred;
                    if (def.getLeftOp().equals(local)) {
                        definitions.add(pred);
                        continue;
                    }
                }

                if (!visited.contains(pred)) {
                    visited.add(pred);
                    worklist.add(pred);
                }
            }
        }

        return definitions;
    }

    // Get control dependencies
    private Set<Unit> getControlDependencies(UnitGraph graph, Unit unit) {
        Set<Unit> dependencies = new HashSet<>();

        if (unit instanceof IfStmt) {
            IfStmt ifStmt = (IfStmt) unit;
            dependencies.addAll(ifStmt.getCondition().getUseBoxes()
                    .stream()
                    .map(ValueBox::getValue)
                    .filter(v -> v instanceof Local)
                    .map(v -> findLastDefinition(graph, unit, (Local) v))
                    .flatMap(Set::stream)
                    .collect(Collectors.toSet()));
        }

        return dependencies;
    }

    // Record execution event
    public void recordEvent(SlicingEvent event) {
        executionTrace.add(event);
    }

    // Compute backward slice from a given criterion
    public Set<SlicingEvent> computeBackwardSlice(SlicingEvent criterion) {
        Set<SlicingEvent> slice = new HashSet<>();
        Queue<SlicingEvent> worklist = new LinkedList<>();

        slice.add(criterion);
        worklist.add(criterion);

        while (!worklist.isEmpty()) {
            SlicingEvent current = worklist.poll();

            // Find relevant method
            String methodSig = getMethodSignature(current);
            UnitGraph graph = methodToGraph.get(methodSig);

            if (graph != null) {
                // Find corresponding unit
                Unit unit = findUnitByLineNumber(graph, current.lineNumber);

                if (unit != null) {
                    // Get dependencies
                    Set<Unit> dependencies = dependencyCache.get(unit);

                    if (dependencies != null) {
                        // Find corresponding events in execution trace
                        for (Unit dep : dependencies) {
                            SlicingEvent depEvent = findMatchingEvent(dep);
                            if (depEvent != null && !slice.contains(depEvent)) {
                                slice.add(depEvent);
                                worklist.add(depEvent);
                            }
                        }
                    }
                }
            }
        }

        return slice;
    }

    private String getMethodSignature(SlicingEvent event) {
        return event.className + ": " + event.methodName;
    }

    private Unit findUnitByLineNumber(UnitGraph graph, int lineNumber) {
        for (Unit unit : graph) {
            if (unit.getJavaSourceStartLineNumber() == lineNumber) {
                return unit;
            }
        }
        return null;
    }

    private SlicingEvent findMatchingEvent(Unit unit) {
        // Find the most recent event that matches this unit
        return executionTrace.stream()
                .filter(e -> e.lineNumber == unit.getJavaSourceStartLineNumber())
                .reduce((first, second) -> second)
                .orElse(null);
    }
}