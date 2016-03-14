package com.svbio.workflow.base;

import akka.dispatch.ExecutionContexts;
import dagger.Module;
import dagger.Provides;
import scala.concurrent.ExecutionContext;
import scala.concurrent.ExecutionContextExecutor;

import javax.inject.Singleton;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Dagger module that provides an {@link ExecutionContextExecutor}.
 */
@Module
public final class ExecutionContextModule {
    @Override
    public String toString() {
        return String.format("Dagger module '%s'", getClass().getSimpleName());
    }

    @Provides
    @Singleton
    static ExecutionContextExecutor provideShortLivedExecutor(LifecycleManager lifecycleManager) {
        final ExecutorService executorService = Executors.newCachedThreadPool();
        lifecycleManager.addLifecyclePhaseListener(
            new LifecyclePhaseListener(ScheduledExecutorService.class.getSimpleName(), LifecyclePhase.STARTED) {
                @Override
                protected void onStop() {
                    executorService.shutdownNow();
                }
            }
        );
        return ExecutionContexts.fromExecutorService(executorService);
    }

    @Provides
    static Executor getExecutor(ExecutionContextExecutor executionContextExecutor) {
        return executionContextExecutor;
    }

    @Provides
    static ExecutionContext getExecutionContext(ExecutionContextExecutor executionContextExecutor) {
        return executionContextExecutor;
    }
}
