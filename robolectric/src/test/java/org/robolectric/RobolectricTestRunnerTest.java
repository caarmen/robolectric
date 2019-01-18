package org.robolectric;

import static com.google.common.truth.Truth.assertThat;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.robolectric.RobolectricTestRunner.defaultInjector;
import static org.robolectric.util.ReflectionHelpers.callConstructor;

import android.annotation.SuppressLint;
import android.app.Application;
import android.os.Build;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.annotation.Nonnull;
import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.RunWith;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.JUnit4;
import org.junit.runners.MethodSorters;
import org.robolectric.RobolectricTestRunner.ResourcesMode;
import org.robolectric.RobolectricTestRunner.RobolectricFrameworkMethod;
import org.robolectric.android.internal.ParallelUniverse;
import org.robolectric.annotation.Config;
import org.robolectric.internal.ParallelUniverseInterface;
import org.robolectric.internal.SdkEnvironment;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.pluginapi.SdkProvider;
import org.robolectric.plugins.DefaultSdkProvider;
import org.robolectric.plugins.SdkCollection;
import org.robolectric.plugins.StubSdk;
import org.robolectric.util.PerfStatsCollector.Metric;
import org.robolectric.util.PerfStatsReporter;
import org.robolectric.util.TempDirectory;
import org.robolectric.util.TestUtil;

@RunWith(JUnit4.class)
public class RobolectricTestRunnerTest {

  private RunNotifier notifier;
  private List<String> events;
  private String priorEnabledSdks;
  private String priorAlwaysInclude;
  private SdkCollection sdkCollection;

  @Before
  public void setUp() throws Exception {
    notifier = new RunNotifier();
    events = new ArrayList<>();
    notifier.addListener(new RunListener() {
      @Override
      public void testRunStarted(Description description) {
        events.add("run started: " + description.getMethodName());
      }

      @Override
      public void testRunFinished(Result result) {
        events.add("run finished: " + result);
      }

      @Override
      public void testStarted(Description description) {
        events.add("started: " + description.getMethodName());
      }

      @Override
      public void testFinished(Description description) {
        events.add("finished: " + description.getMethodName());
      }

      @Override
      public void testAssumptionFailure(Failure failure) {
        events.add(
            "ignored: " + failure.getDescription().getMethodName() + ": " + failure.getMessage());
      }

      @Override
      public void testIgnored(Description description) {
        events.add("ignored: " + description.getMethodName());
      }

      @Override
      public void testFailure(Failure failure) {
        events.add("failure: " + failure.getMessage());
      }
    });

    priorEnabledSdks = System.getProperty("robolectric.enabledSdks");
    System.clearProperty("robolectric.enabledSdks");

    priorAlwaysInclude = System.getProperty("robolectric.alwaysIncludeVariantMarkersInTestName");
    System.clearProperty("robolectric.alwaysIncludeVariantMarkersInTestName");

    sdkCollection = new SdkCollection(new DefaultSdkProvider(null));
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.resetSystemProperty(
        "robolectric.alwaysIncludeVariantMarkersInTestName", priorAlwaysInclude);
    TestUtil.resetSystemProperty("robolectric.enabledSdks", priorEnabledSdks);
  }

  @Test
  public void ignoredTestCanSpecifyUnsupportedSdkWithoutExploding() throws Exception {
    RobolectricTestRunner runner = new RobolectricTestRunner(TestWithOldSdk.class);
    runner.run(notifier);
    assertThat(events).containsExactly(
        "started: oldSdkMethod",
        "failure: API level 11 is not available",
        "finished: oldSdkMethod",
        "ignored: ignoredOldSdkMethod"
    ).inOrder();
  }

  @Test
  public void testsWithUnsupportedSdkShouldBeIgnored() throws Exception {
    RobolectricTestRunner runner = new RobolectricTestRunner(
        TestWithTwoMethods.class,
        defaultInjector()
            .bind(SdkProvider.class, () ->
                Arrays.asList(TestUtil.getSdkCollection().getSdk(17),
                    new StubSdk(18, false)))
            .build());
    runner.run(notifier);
    assertThat(events).containsExactly(
        "started: first[17]", "finished: first[17]",
        "started: first",
        "ignored: first: Failed to create a Robolectric sandbox: unsupported",
        "finished: first",
        "started: second[17]", "finished: second[17]",
        "started: second",
        "ignored: second: Failed to create a Robolectric sandbox: unsupported",
        "finished: second"
    ).inOrder();
  }

  @Test
  public void failureInResetterDoesntBreakAllTests() throws Exception {
    RobolectricTestRunner runner =
        new SingleSdkRobolectricTestRunner(TestWithTwoMethods.class) {
          @Override
          ParallelUniverseInterface getHooksInterface(SdkEnvironment sdkEnvironment) {
            Class<? extends ParallelUniverseInterface> clazz =
                sdkEnvironment.bootstrappedClass(MyParallelUniverseWithFailingSetUp.class);
            return callConstructor(clazz);
          }
        };
    runner.run(notifier);
    assertThat(events).containsExactly(
        "started: first",
        "failure: fake error in setUpApplicationState",
        "finished: first",
        "started: second",
        "failure: fake error in setUpApplicationState",
        "finished: second"
    ).inOrder();
  }

  @Test
  public void failureInAppOnCreateDoesntBreakAllTests() throws Exception {
    RobolectricTestRunner runner = new SingleSdkRobolectricTestRunner(TestWithBrokenAppCreate.class);
    runner.run(notifier);
    assertThat(events)
        .containsExactly(
            "started: first",
            "failure: fake error in application.onCreate",
            "finished: first",
            "started: second",
            "failure: fake error in application.onCreate",
            "finished: second"
        ).inOrder();
  }

  @Test
  public void failureInAppOnTerminateDoesntBreakAllTests() throws Exception {
    RobolectricTestRunner runner = new SingleSdkRobolectricTestRunner(TestWithBrokenAppTerminate.class);
    runner.run(notifier);
    assertThat(events)
        .containsExactly(
            "started: first",
            "failure: fake error in application.onTerminate",
            "finished: first",
            "started: second",
            "failure: fake error in application.onTerminate",
            "finished: second"
        ).inOrder();
  }

  @Test
  public void equalityOfRobolectricFrameworkMethod() throws Exception {
    Method method = TestWithTwoMethods.class.getMethod("first");
    RobolectricFrameworkMethod rfm16 =
        new RobolectricFrameworkMethod(
            method,
            mock(AndroidManifest.class),
            sdkCollection.getSdk(16),
            mock(Config.class),
            ResourcesMode.legacy,
            ResourcesMode.legacy,
            false);
    RobolectricFrameworkMethod rfm17 =
        new RobolectricFrameworkMethod(
            method,
            mock(AndroidManifest.class),
            sdkCollection.getSdk(17),
            mock(Config.class),
            ResourcesMode.legacy,
            ResourcesMode.legacy,
            false);
    RobolectricFrameworkMethod rfm16b =
        new RobolectricFrameworkMethod(
            method,
            mock(AndroidManifest.class),
            sdkCollection.getSdk(16),
            mock(Config.class),
            ResourcesMode.legacy,
            ResourcesMode.legacy,
            false);
    RobolectricFrameworkMethod rfm16c =
        new RobolectricFrameworkMethod(
            method,
            mock(AndroidManifest.class),
            sdkCollection.getSdk(16),
            mock(Config.class),
            ResourcesMode.binary,
            ResourcesMode.legacy,
            false);

    assertThat(rfm16).isNotEqualTo(rfm17);
    assertThat(rfm16).isEqualTo(rfm16b);
    assertThat(rfm16).isNotEqualTo(rfm16c);

    assertThat(rfm16.hashCode()).isEqualTo((rfm16b.hashCode()));
  }

  @Test
  public void shouldReportPerfStats() throws Exception {
    List<Metric> metrics = new ArrayList<>();
    PerfStatsReporter reporter = (metadata, metrics1) -> metrics.addAll(metrics1);

    RobolectricTestRunner runner = new SingleSdkRobolectricTestRunner(TestWithTwoMethods.class) {
      @Nonnull
      @Override
      protected Iterable<PerfStatsReporter> getPerfStatsReporters() {
        return singletonList(reporter);
      }
    };

    runner.run(notifier);

    Set<String> metricNames = metrics.stream().map(Metric::getName).collect(toSet());
    assertThat(metricNames).contains("initialization");
  }

  @Test
  public void shouldResetThreadInterrupted() throws Exception {
    RobolectricTestRunner runner = new SingleSdkRobolectricTestRunner(TestWithInterrupt.class);
    runner.run(notifier);
    assertThat(events).containsExactly(
        "started: first",
        "finished: first",
        "started: second",
        "failure: failed for the right reason",
        "finished: second"
    );
  }

  /////////////////////////////

  public static class MyParallelUniverseWithFailingSetUp extends ParallelUniverse {

    @Override
    public void setUpApplicationState(ApkLoader apkLoader, Method method,
        Config config, AndroidManifest appManifest, SdkEnvironment environment) {
      throw new RuntimeException("fake error in setUpApplicationState");
    }
  }

  @Ignore
  public static class TestWithOldSdk {
    @Config(sdk = Build.VERSION_CODES.HONEYCOMB)
    @Test
    public void oldSdkMethod() throws Exception {
      fail("I should not be run!");
    }

    @Ignore("This test shouldn't run, and shouldn't cause the test runner to fail")
    @Config(sdk = Build.VERSION_CODES.HONEYCOMB)
    @Test
    public void ignoredOldSdkMethod() throws Exception {
      fail("I should not be run!");
    }
  }

  @Ignore
  @FixMethodOrder(MethodSorters.NAME_ASCENDING)
  public static class TestWithTwoMethods {
    @Test
    public void first() throws Exception {
    }

    @Test
    public void second() throws Exception {
    }
  }

  @Ignore
  @FixMethodOrder(MethodSorters.NAME_ASCENDING)
  @Config(application = TestWithBrokenAppCreate.MyTestApplication.class)
  public static class TestWithBrokenAppCreate {
    @Test
    public void first() throws Exception {}

    @Test
    public void second() throws Exception {}

    public static class MyTestApplication extends Application {
      @SuppressLint("MissingSuperCall")
      @Override
      public void onCreate() {
        throw new RuntimeException("fake error in application.onCreate");
      }
    }
  }

  @Ignore
  @FixMethodOrder(MethodSorters.NAME_ASCENDING)
  @Config(application = TestWithBrokenAppTerminate.MyTestApplication.class)
  public static class TestWithBrokenAppTerminate {
    @Test
    public void first() throws Exception {}

    @Test
    public void second() throws Exception {}

    public static class MyTestApplication extends Application {
      @SuppressLint("MissingSuperCall")
      @Override
      public void onTerminate() {
        throw new RuntimeException("fake error in application.onTerminate");
      }
    }
  }

  @Ignore
  @FixMethodOrder(MethodSorters.NAME_ASCENDING)
  public static class TestWithInterrupt {
    @Test
    public void first() throws Exception {
      Thread.currentThread().interrupt();
    }

    @Test
    public void second() throws Exception {
      TempDirectory tempDirectory = new TempDirectory("test");

      try {
        Path jarPath = tempDirectory.create("some-jar").resolve("some.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
          out.putNextEntry(new JarEntry("README.txt"));
          out.write("hi!".getBytes());
        }

        FileSystemProvider jarFSP = FileSystemProvider.installedProviders().stream()
            .filter(p -> p.getScheme().equals("jar")).findFirst().get();
        Path fakeJarFile = Paths.get(jarPath.toUri());

        // if Thread.interrupted() was true, this would fail in AbstractInterruptibleChannel:
        jarFSP.newFileSystem(fakeJarFile, new HashMap<>());
      } finally {
        tempDirectory.destroy();
      }

      fail("failed for the right reason");
    }
  }

}
