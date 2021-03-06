package com.svbio.workflow.forkedexecutor;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import xyz.cloudkeeper.dsl.Module;
import xyz.cloudkeeper.examples.modules.Decrease;
import xyz.cloudkeeper.executors.ForkedExecutors;
import xyz.cloudkeeper.filesystem.FileStagingArea;
import xyz.cloudkeeper.model.api.RuntimeContext;
import xyz.cloudkeeper.model.api.RuntimeContextFactory;
import xyz.cloudkeeper.model.api.RuntimeStateProvider;
import xyz.cloudkeeper.model.api.executor.SimpleModuleExecutorResult;
import xyz.cloudkeeper.model.api.staging.StagingArea;
import xyz.cloudkeeper.model.api.util.RecursiveDeleteVisitor;
import xyz.cloudkeeper.model.beans.element.module.MutableProxyModule;
import xyz.cloudkeeper.model.immutable.element.Name;
import xyz.cloudkeeper.model.immutable.element.SimpleName;
import xyz.cloudkeeper.model.immutable.execution.ExecutionTrace;
import xyz.cloudkeeper.model.runtime.execution.RuntimeAnnotatedExecutionTrace;
import xyz.cloudkeeper.simple.CharacterStreamCommunication.Splitter;
import xyz.cloudkeeper.simple.DSLRuntimeContextFactory;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ITForkedExecutor {
    private Path tempDir;
    private List<String> commandLine;
    private ExecutorService executorService;

    /**
     * Returns the command line for running {@link ForkedExecutor#main} in a new JVM.
     *
     * @param additionalJVMArguments additional JVM arguments, such as system properties
     * @return the command line
     */
    private static List<String> forkedCommandLine(List<String> additionalJVMArguments)
            throws IOException, URISyntaxException {
        Path javaPath = Paths.get(System.getProperty("java.home")).resolve("bin").resolve("java");
        List<String> command = new ArrayList<>();
        command.add(javaPath.toString());
        command.add("-enableassertions");
        command.add("-classpath");
        command.add(System.getProperty("java.class.path"));
        command.addAll(additionalJVMArguments);
        command.add(ForkedExecutor.class.getName());
        return command;
    }

    @BeforeClass
    public void setup() throws Exception {
        tempDir = Files.createTempDirectory(getClass().getName());

        // Generate configuration properties that point to the newly created Maven repository
        Map<String, Object> properties = new LinkedHashMap<>();
        // The following property is needed for workflow-forked-executor
        properties.put("com.svbio.workflow.workspacebasepath", tempDir.toString());

        executorService = Executors.newCachedThreadPool();

        // The current content of 'properties' needs to be used as configuration in the forked Java process, too. The
        // easiest way is to define the properties as system properties.
        List<String> jvmArgs = properties.entrySet().stream()
            .map(entry -> String.format("-D%s=%s", entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());

        // Prepare command line for forked Java process
        commandLine = forkedCommandLine(jvmArgs);
    }

    @AfterClass
    public void tearDown() throws IOException {
        executorService.shutdownNow();
        Files.walkFileTree(tempDir, RecursiveDeleteVisitor.getInstance());
    }

    @Test
    public void run() throws Exception {
        Path ioPath = Files.createDirectory(tempDir.resolve("io"));
        Path stagingBasePath = Files.createDirectory(tempDir.resolve("staging"));
        RuntimeContextFactory runtimeContextFactory = new DSLRuntimeContextFactory.Builder(executorService).build();
        URI bundleIdentifier = new URI(Module.URI_SCHEME, Decrease.class.getName(), null);
        try (
            RuntimeContext runtimeContext
                = runtimeContextFactory.newRuntimeContext(Collections.singletonList(bundleIdentifier)).get()
        ) {
            RuntimeAnnotatedExecutionTrace executionTrace = runtimeContext.newAnnotatedExecutionTrace(
                ExecutionTrace.empty(),
                new MutableProxyModule().setDeclaration(Decrease.class.getName()),
                Collections.emptyList()
            );
            StagingArea stagingArea = new FileStagingArea.Builder(
                    runtimeContext, executionTrace, stagingBasePath, executorService)
                .build();
            stagingArea.putObject(ExecutionTrace.empty().resolveInPort(SimpleName.identifier("num")), 5).join();

            RuntimeStateProvider runtimeStateProvider = RuntimeStateProvider.of(runtimeContext, stagingArea);
            Path stdinFile = ioPath.resolve("stdin");
            try (
                ObjectOutputStream outputStream = new ObjectOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(stdinFile)
                ))
            ) {
                outputStream.writeObject(runtimeStateProvider);
            }

            Path stderrFile = ioPath.resolve("stderr");
            Path stdoutFile = ioPath.resolve("stdout");
            int status = new ProcessBuilder(commandLine)
                .redirectInput(stdinFile.toFile())
                .redirectError(stderrFile.toFile())
                .redirectOutput(stdoutFile.toFile())
                .start()
                .waitFor();
            Assert.assertEquals(status, 0);

            SimpleModuleExecutorResult result;
            try (
                Splitter<SimpleModuleExecutorResult> splitter = new Splitter<>(
                    SimpleModuleExecutorResult.class,
                    new BufferedReader(new InputStreamReader(Files.newInputStream(stdoutFile)))
                )
            ) {
                splitter.consumeAll();
                result = splitter.getResult();
            }

            Assert.assertEquals(result.getExecutorName(), Name.qualifiedName(ForkedExecutors.class.getName()));
            Assert.assertEquals(
                stagingArea.getObject(ExecutionTrace.empty().resolveOutPort(SimpleName.identifier("result"))).get(),
                4
            );
        }
    }
}
