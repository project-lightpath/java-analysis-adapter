package demo;

import java.util.*;

public final class App {
  public static void audit() {
    ServiceLoader.load(Provider.class).findFirst()
        .ifPresent(provider -> provider.record());
  }
}
