package org.robolectric.util.inject;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.auto.service.AutoService;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Priority;
import javax.inject.Inject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class InjectorTest {

  private Injector injector;

  @Before
  public void setUp() throws Exception {
    injector = new Injector();
  }

  @Test
  public void whenImplSpecified_shouldProvideInstance() throws Exception {
    injector.register(Thing.class, MyThing.class);

    assertThat(injector.getInstance(Thing.class))
        .isInstanceOf(MyThing.class);
  }

  @Test
  public void whenImplSpecified_shouldUseSameInstance() throws Exception {
    injector.register(Thing.class, MyThing.class);

    Thing thing = injector.getInstance(Thing.class);
    assertThat(injector.getInstance(Thing.class))
        .isSameAs(thing);
  }

  // specified in resources/META-INF/services/org.robolectric.util.inject.Thing
  @Test
  public void whenServiceSpecified_shouldProvideInstance() throws Exception {
    assertThat(injector.getInstance(Thing.class))
        .isInstanceOf(ThingFromServiceConfig.class);
  }

  // specified in resources/META-INF/services/org.robolectric.util.inject.Thing
  @Test
  public void whenServiceSpecified_shouldUseSameInstance() throws Exception {
    Thing thing = injector.getInstance(Thing.class);
    assertThat(injector.getInstance(Thing.class))
        .isSameAs(thing);
  }

  @Test
  public void whenDefaultSpecified_shouldProvideInstance() throws Exception {
    injector.registerDefault(Umm.class, MyUmm.class);

    assertThat(injector.getInstance(Umm.class))
        .isInstanceOf(MyUmm.class);
  }

  @Test
  public void whenDefaultSpecified_shouldUseSameInstance() throws Exception {
    Thing thing = injector.getInstance(Thing.class);
    assertThat(injector.getInstance(Thing.class))
        .isSameAs(thing);
  }

  @Test
  public void whenNoImplOrServiceOrDefaultSpecified_shouldThrow() throws Exception {
    try {
      injector.getInstance(Umm.class);
      fail();
    } catch (InjectionException e) {
      // ok
    }
  }

  @Test
  public void registerDefaultService_providesFallbackImplOnlyIfNoServiceSpecified()
      throws Exception {
    injector.registerDefault(Thing.class, MyThing.class);

    assertThat(injector.getInstance(Thing.class))
        .isInstanceOf(ThingFromServiceConfig.class);

    injector.registerDefault(Umm.class, MyUmm.class);
    assertThat(injector.getInstance(Thing.class))
        .isInstanceOf(ThingFromServiceConfig.class);
  }

  @Test
  public void shouldInjectConstructor() throws Exception {
    injector.register(Thing.class, MyThing.class);
    injector.register(Umm.class, MyUmm.class);

    Umm umm = injector.getInstance(Umm.class);
    assertThat(umm).isNotNull();
    assertThat(umm).isInstanceOf(MyUmm.class);

    MyUmm myUmm = (MyUmm) umm;
    assertThat(myUmm.thing).isNotNull();
    assertThat(myUmm.thing).isInstanceOf(MyThing.class);

    assertThat(myUmm.thing).isSameAs(injector.getInstance(Thing.class));
  }

  @Test
  public void whenArrayRequested_mayReturnMultiplePlugins() throws Exception {
    MultiThing[] multiThings = new Injector().getInstance(MultiThing[].class);

    List<? extends Class<?>> pluginClasses =
        Arrays.stream(multiThings).map(Object::getClass)
        .collect(Collectors.toList());

    // X comes first because it has a higher priority
    assertThat(pluginClasses)
        .containsExactly(MultiThingX.class, MultiThingA.class).inOrder();
  }

  /////////////////////////////

  public static class MyThing implements Thing {

  }

  @AutoService(ThingFromServiceConfig.class)
  public static class ThingFromServiceConfig implements Thing {

  }

  private interface Umm {

  }

  public static class MyUmm implements Umm {

    private final Thing thing;

    @Inject
    MyUmm(Thing thing) {
      this.thing = thing;
    }
  }

  private interface MultiThing {

  }

  @Priority(-5)
  @AutoService(MultiThing.class)
  public static class MultiThingA implements MultiThing {
  }

  @AutoService(MultiThing.class)
  public static class MultiThingX implements MultiThing {
  }
}