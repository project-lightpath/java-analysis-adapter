package demo;

import java.util.*;
import static a.Tools.*;

public final class App {
  public static void audit() {
    ServiceLoader.load(Provider.class).findFirst()
        .ifPresent(provider -> provider.record());
  }
}
