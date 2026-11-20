package demo;

public final class App {
  public static void audit() {
    ServiceLoader.load(Provider.class).findFirst()
        .ifPresent(provider -> provider.record());
  }
}
