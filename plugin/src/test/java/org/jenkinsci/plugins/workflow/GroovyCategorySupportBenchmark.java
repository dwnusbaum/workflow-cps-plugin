/*
 * The MIT License
 *
 * Copyright 2024 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow;

import groovy.lang.Closure;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jenkins.util.InterceptingExecutorService;
import org.codehaus.groovy.runtime.GroovyCategorySupport;
import org.junit.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.profile.AsyncProfiler;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class GroovyCategorySupportBenchmark {
    @Test
    public void runJmhBenchmarks() throws Exception {
        ChainedOptionsBuilder options = new OptionsBuilder()
                .mode(Mode.AverageTime)
                .forks(1)
                // Various profilers may be used if desired. JFR does not require additional dependencies.
                .addProfiler(AsyncProfiler.class, "libPath=/Users/dnusbaum/Downloads/async-profiler-2.9-macos/build/libasyncProfiler.so;output=flamegraph;dir=target/jfr-snapshots")
                .warmupIterations(2)
                .resultFormat(ResultFormatType.JSON)
                .result("target/jmh-report.json")
                .include(getClass().getName());
        // Comment out or modify these lines to benchmark various parameter combinations.
        options.param("threads", "100");
        options.param("tasks", "10000");
        new Runner(options.build()).run();
    }

    @State(Scope.Benchmark)
    public static class MyState {
        @Param({"10", "100"})
        int threads;

        @Param({"10000"})
        int tasks;
        
        List<Script> categoryScripts = new ArrayList<>();
        List<Script> controlScripts = new ArrayList<>();

        @Setup
        public void prepareScripts() throws IOException, InterruptedException {
            for (int i = 0; i < tasks; i++) {
                categoryScripts.add(new GroovyShell().parse("'myString'.categoryToUpper()"));
                controlScripts.add(new GroovyShell().parse("'myString'.toUpperCase(Locale.ENGLISH)"));
            }
        }
    }

    @Benchmark
    public Object categoryPerThread(MyState state) throws Exception {
        ExecutorService service = Executors.newFixedThreadPool(state.threads, r -> {
            return new Thread(() -> {
                GroovyCategorySupport.use(MyCategory.class, new Closure<Void>(null) {
                    @Override
                    public Void call() {
                        r.run();
                        return null;
                    }
                });
            });
        });
        for (Script script : state.categoryScripts) {
            service.submit(() -> { script.run(); });
        }
        service.shutdown();
        return service.awaitTermination(60, TimeUnit.SECONDS);
    }

    @Benchmark
    public Object categoryPerTask(MyState state) throws Exception {
        ExecutorService service = new InterceptingExecutorService(Executors.newFixedThreadPool(state.threads)) {
            @Override
            protected Runnable wrap(Runnable r) {
                return () -> {
                    GroovyCategorySupport.use(MyCategory.class, new Closure<Void>(null) {
                        @Override
                        public Void call() {
                            r.run();
                            return null;
                        }
                    });
                };
            }

            @Override
            protected <V> Callable<V> wrap(Callable<V> r) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        };
        for (Script script : state.categoryScripts) {
            service.submit(() -> { script.run(); });
        }
        service.shutdown();
        return service.awaitTermination(60, TimeUnit.SECONDS);
    }

    @Benchmark
    public Object control(MyState state) throws Exception {
        ExecutorService service = Executors.newFixedThreadPool(state.threads);
        for (Script script : state.controlScripts) {
            service.submit(() -> { script.run(); });
        }
        service.shutdown();
        return service.awaitTermination(60, TimeUnit.SECONDS);
    }

    public static class MyCategory {
        public static String categoryToUpper(String value) {
            return value.toUpperCase(Locale.ENGLISH);
        }
    }
}
