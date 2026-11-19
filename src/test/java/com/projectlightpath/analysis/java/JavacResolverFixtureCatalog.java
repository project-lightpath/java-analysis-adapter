package com.projectlightpath.analysis.java;

import static com.projectlightpath.analysis.java.JavacResolverCell.Expectation.AMBIGUOUS;
import static com.projectlightpath.analysis.java.JavacResolverCell.Expectation.DEFINITE;
import static com.projectlightpath.analysis.java.JavacResolverCell.Expectation.MISSING_INPUT;
import static com.projectlightpath.analysis.java.JavacResolverCell.Expectation.NO_FACT;
import static com.projectlightpath.analysis.java.JavacResolverCell.Expectation.POSSIBLE;
import static com.projectlightpath.analysis.java.JavacResolverCell.Expectation.SHADOWED;
import static com.projectlightpath.analysis.java.JavacResolverCell.Family.DOLLAR_OWNER;
import static com.projectlightpath.analysis.java.JavacResolverCell.Family.INCOMPLETE;
import static com.projectlightpath.analysis.java.JavacResolverCell.Family.PRECEDENCE;
import static com.projectlightpath.analysis.java.JavacResolverCell.Family.STATIC_OWNER;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixture catalog for the javac resolver spike. Derived from JLS and PLAN-16. */
final class JavacResolverFixtureCatalog {
  private static final String SERVICE_LOADER_MEMBER = """
      public static class ServiceLoader<T> {
        public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
      }
      """;
  private static final String PACKAGE_SERVICE_LOADER_MEMBER = """
      static class ServiceLoader<T> {
        public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
      }
      """;

  private JavacResolverFixtureCatalog() {}

  static List<JavacResolverCell> cells() {
    List<JavacResolverCell> cells = new ArrayList<>();
    cells.addAll(precedenceCells());
    cells.addAll(staticOwnerCells());
    cells.addAll(dollarOwnerCells());
    cells.addAll(incompleteCells());
    return List.copyOf(cells);
  }

  private static List<JavacResolverCell> precedenceCells() {
    return List.of(
        cell("N1", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge { void audit() { ServiceLoader.load(Object.class); } }
                """),
            site(DEFINITE, "java.lang.Object")),
        cell("N2", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources(
                "demo/Bridge.java", """
                    package demo;
                    import java.util.*;
                    class Bridge { void audit() { ServiceLoader.load(Object.class); } }
                    """,
                "demo/Helpers.java", """
                    package demo;
                    class ServiceLoader<T> {
                      static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                    }
                    """),
            site(SHADOWED, "java.lang.Object")),
        cell("N3", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.ServiceLoader;
                class Bridge { void audit() { ServiceLoader.load(Object.class); } }
                """),
            site(DEFINITE, "java.lang.Object")),
        cell("N4", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources(
                "demo/Bridge.java", """
                    package demo;
                    import other.ServiceLoader;
                    class Bridge { void audit() { ServiceLoader.load(Object.class); } }
                    """,
                "other/ServiceLoader.java", """
                    package other;
                    public class ServiceLoader<T> {
                      public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                    }
                    """),
            site(SHADOWED, "java.lang.Object")),
        cell("N5", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources(
                "demo/Bridge.java", """
                    package demo;
                    import java.util.*;
                    import other.ServiceLoader;
                    class Bridge { void audit() { ServiceLoader.load(Object.class); } }
                    """,
                "other/ServiceLoader.java", """
                    package other;
                    public class ServiceLoader<T> {
                      public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                    }
                    """),
            site(SHADOWED, "java.lang.Object")),
        cell("N6", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge { void audit() { ServiceLoader.load(Object.class); } }
                class ServiceLoader<T> {
                  static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                }
                """),
            site(SHADOWED, "java.lang.Object")),
        cell("N7", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  static class ServiceLoader<T> {
                    static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                  }
                  void audit() { ServiceLoader.load(Object.class); }
                }
                """),
            site(SHADOWED, "java.lang.Object")),
        cell("N8", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  void audit() {
                    class ServiceLoader<T> {
                      static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                    }
                    ServiceLoader.load(Object.class);
                  }
                }
                """),
            site(SHADOWED, "java.lang.Object")),
        cell("N9", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  static Object ServiceLoader = null;
                  void audit() { ServiceLoader.load(Object.class); }
                }
                """),
            site(SHADOWED, "java.lang.Object")),
        cell("N10", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  void audit(Object ServiceLoader) { ServiceLoader.load(Object.class); }
                }
                """),
            site(SHADOWED, "java.lang.Object")),
        cell("N11", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                import java.util.function.Consumer;
                class Bridge {
                  void audit() {
                    Consumer<Object> consumer = ServiceLoader -> ServiceLoader.load(Object.class);
                  }
                }
                """),
            site(SHADOWED, "java.lang.Object")),
        cell("N12", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  void audit() {
                    try { } catch (RuntimeException ServiceLoader) {
                      ServiceLoader.load(Object.class);
                    }
                  }
                }
                """),
            site(SHADOWED, "java.lang.Object")),
        cell("N13", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  void audit(Object value) {
                    if (value instanceof String ServiceLoader) {
                      ServiceLoader.load(Object.class);
                    }
                  }
                }
                """),
            site(SHADOWED, "java.lang.Object")),
        cell("N14", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  void audit() {
                    try (java.io.StringReader ServiceLoader = new java.io.StringReader("x")) {
                      ServiceLoader.load(Object.class);
                    } catch (java.io.IOException ignored) { }
                  }
                }
                """),
            site(SHADOWED, "java.lang.Object")),
        cell("N15", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  void audit() {
                    for (String ServiceLoader : List.of("x")) {
                      ServiceLoader.load(Object.class);
                    }
                  }
                }
                """),
            site(SHADOWED, "java.lang.Object")),
        cell("N16", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge extends Base {
                  void audit() { ServiceLoader.load(Object.class); }
                }
                class Base {
                  static class ServiceLoader<T> {
                    static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                  }
                }
                """),
            site(SHADOWED, "java.lang.Object")),
        cell("N17", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources(
                "demo/Bridge.java", """
                    package demo;
                    import java.util.*;
                    class Bridge { void audit() { ServiceLoader.load(Object.class); } }
                    """,
                "other/ServiceLoader.java", """
                    package other;
                    public class ServiceLoader<T> {
                      public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                    }
                    """),
            site(DEFINITE, "java.lang.Object")),
        cell("N18", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge extends Base {
                  void audit() { java.util.ServiceLoader.load(Object.class); }
                }
                class Base {
                  static class ServiceLoader<T> {
                    static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                  }
                }
                """),
            site(DEFINITE, "java.lang.Object")),
        cell("N19", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources(
                "demo/Bridge.java", """
                    package demo;
                    import java.util.*;
                    class Bridge { void audit() { ServiceLoader.load(Object.class); } }
                    """,
                "demo/Helpers.java", """
                    package demo;
                    class Helpers { }
                    class ServiceLoader<T> {
                      static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                    }
                    """),
            site(SHADOWED, "java.lang.Object")),
        cell("N20", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  void audit() {
                    Runnable body = () -> {
                      class ServiceLoader<T> {
                        static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                      }
                      ServiceLoader.load(Object.class);
                    };
                    ServiceLoader.load(Object.class);
                  }
                }
                """),
            site(SHADOWED, "java.lang.Object"), site(DEFINITE, "java.lang.Object")),
        cell("N21", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  void audit() {
                    try { } catch (RuntimeException error) {
                      class ServiceLoader<T> {
                        static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                      }
                      ServiceLoader.load(Object.class);
                    }
                    ServiceLoader.load(Object.class);
                  }
                }
                """),
            site(SHADOWED, "java.lang.Object"), site(DEFINITE, "java.lang.Object")),
        cell("N22", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 21,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  void audit(Object value) {
                    switch (value) {
                      case String ServiceLoader -> ServiceLoader.load(Object.class);
                      default -> { }
                    }
                    ServiceLoader.load(Object.class);
                  }
                }
                """),
            site(SHADOWED, "java.lang.Object"), site(DEFINITE, "java.lang.Object")),
        cell("N23", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 21,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  void audit(Object value) {
                    Runnable body = () -> {
                      switch (value) {
                        case String ServiceLoader -> ServiceLoader.load(Object.class);
                        default -> { }
                      }
                    };
                    ServiceLoader.load(Object.class);
                  }
                }
                """),
            site(SHADOWED, "java.lang.Object"), site(DEFINITE, "java.lang.Object")),
        cell("N24a", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class ServiceLoader<T> {
                  static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                }
                class Bridge {
                  void audit() { ServiceLoader.load(Object.class); }
                }
                """),
            site(SHADOWED, "java.lang.Object")),
        cell("N24b", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class ServiceLoader<T> {
                  static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                }
                class Bridge extends Base {
                  void audit() { ServiceLoader.load(Object.class); }
                }
                class Base {
                  static class ServiceLoader<T> {
                    static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                  }
                }
                """),
            site(SHADOWED, "java.lang.Object")),
        cell("N25", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources(
                "demo/Bridge.java", """
                    package demo;
                    import java.util.*;
                    import other.Base;
                    class Bridge extends Base {
                      void audit() { ServiceLoader.load(Object.class); }
                    }
                    """,
                "other/Base.java", """
                    package other;
                    public class Base {
                      public static class ServiceLoader<T> {
                        public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                      }
                    }
                    """),
            site(SHADOWED, "java.lang.Object")),
        cell("N26", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources(
                "demo/Bridge.java", """
                    package demo;
                    import java.util.*;
                    import other.*;
                    class Bridge extends Base {
                      void audit() { ServiceLoader.load(Object.class); }
                    }
                    """,
                "other/Base.java", """
                    package other;
                    public class Base {
                      public static class ServiceLoader<T> {
                        public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                      }
                    }
                    """),
            site(SHADOWED, "java.lang.Object")),
        cell("N27", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources(
                "demo/Bridge.java", """
                    package demo;
                    import java.util.*;
                    class Bridge extends other.Base {
                      void audit() { ServiceLoader.load(Object.class); }
                    }
                    """,
                "other/Base.java", """
                    package other;
                    public class Base {
                      public static class ServiceLoader<T> {
                        public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                      }
                    }
                    """),
            site(SHADOWED, "java.lang.Object")),
        cell("N28", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources(
                "demo/Bridge.java", """
                    package demo;
                    import java.util.*;
                    class Bridge extends other.Base {
                      void audit() { ServiceLoader.load(Object.class); }
                    }
                    """,
                "other/Base.java", """
                    package other;
                    public class Base {
                      static class ServiceLoader<T> {
                        static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                      }
                    }
                    """),
            site(DEFINITE, "java.lang.Object")),
        cell("N29", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge extends Base {
                  void audit() { ServiceLoader.load(Object.class); }
                }
                class Base {
                  static class ServiceLoader<T> {
                    static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                  }
                }
                """),
            site(SHADOWED, "java.lang.Object")),
        cell("N30", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources(
                "demo/Bridge.java", """
                    package demo;
                    import java.util.*;
                    class Bridge extends other.Base {
                      void audit() { ServiceLoader.load(Object.class); }
                    }
                    """,
                "other/Base.java", """
                    package other;
                    public class Base {
                      protected static class ServiceLoader<T> {
                        static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                      }
                    }
                    """),
            site(SHADOWED, "java.lang.Object")),
        cell("N31", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources(
                "demo/Bridge.java", """
                    package demo;
                    import java.util.*;
                    class Bridge extends other.Base {
                      void audit() { ServiceLoader.load(Object.class); }
                    }
                    """,
                "other/Base.java", """
                    package other;
                    public class Base {
                      private static class ServiceLoader<T> {
                        static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                      }
                    }
                    """),
            site(DEFINITE, "java.lang.Object")),
        cell("N32", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import static java.util.ServiceLoader.load;
                class Bridge { void audit() { load(Object.class); } }
                """),
            site(DEFINITE, "java.lang.Object")),
        cell("N33", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  void audit(Class<Object> type) { ServiceLoader.load(type); }
                }
                """),
            site(NO_FACT, "java.lang.Object")),
        cell("N34", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  void audit() { ServiceLoader.load(int.class); }
                }
                """),
            site(NO_FACT, "int")),
        cell("N35", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  void audit() { ServiceLoader.load(Object[].class); }
                }
                """),
            site(NO_FACT, "java.lang.Object[]")),
        cell("N36", PRECEDENCE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  void audit() {
                    class Local { }
                    ServiceLoader.load(Local.class);
                  }
                }
                """),
            site(NO_FACT, "Local")));
  }

  private static List<JavacResolverCell> staticOwnerCells() {
    return List.of(
        staticCell("A1", "demo/Bridge.java", "demo.Bridge", Map.of(
            "a/Tools.java", "package a; public class Tools { }",
            "b/Tools.java", "package b; public class Tools {" + SERVICE_LOADER_MEMBER + "}"),
            site(DEFINITE, "java.lang.Object")),
        staticCell("A2", "demo/Bridge.java", "demo.Bridge", Map.of(
            "a/Tools.java", "package a; public class Tools {" + SERVICE_LOADER_MEMBER + "}"),
            site(AMBIGUOUS, "java.lang.Object")),
        cell("A3", STATIC_OWNER, "demo/Bridge.java", "demo.Bridge", 17,
            sources(
                "demo/Bridge.java", """
                    package demo;
                    import java.util.*;
                    import static a.Tools.ServiceLoader;
                    class Bridge { void audit() { ServiceLoader.load(Object.class); } }
                    """,
                "a/Tools.java", "package a; public class Tools {" + SERVICE_LOADER_MEMBER + "}"),
            site(SHADOWED, "java.lang.Object")),
        staticCell("A4", "demo/Bridge.java", "demo.Bridge", Map.of(
            "a/Tools.java", "package a; public class Tools {" + PACKAGE_SERVICE_LOADER_MEMBER + "}"),
            site(DEFINITE, "java.lang.Object")),
        staticCellIn("A5", "a", "a.Bridge", Map.of(
            "a/Tools.java", "package a; public class Tools {" + PACKAGE_SERVICE_LOADER_MEMBER + "}"),
            site(AMBIGUOUS, "java.lang.Object")),
        staticCell("A6", "demo/Bridge.java", "demo.Bridge", Map.of(
            "a/Tools.java", """
                package a;
                public class Tools {
                  private static class ServiceLoader<T> {
                    static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                  }
                }
                """),
            site(DEFINITE, "java.lang.Object")),
        staticCell("A7", "demo/Bridge.java", "demo.Bridge", Map.of(
            "a/Tools.java", """
                package a;
                public class Tools {
                  protected static class ServiceLoader<T> {
                    static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                  }
                }
                """),
            site(DEFINITE, "java.lang.Object")),
        cell("A8", STATIC_OWNER, "demo/Bridge.java", "demo.Bridge", 17,
            sources(
                "demo/Bridge.java", staticSite("demo"),
                "b/Tools.java", "package b; public class Tools {" + SERVICE_LOADER_MEMBER + "}"),
            site(POSSIBLE, "java.lang.Object")),
        cell("A9", STATIC_OWNER, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", staticSite("demo")),
            site(POSSIBLE, "java.lang.Object")),
        staticCell("A10", "demo/Bridge.java", "demo.Bridge", Map.of(
            "a/Tools.java", """
                package a;
                public class Tools extends Base { }
                """,
            "a/Base.java", "package a; public class Base {" + SERVICE_LOADER_MEMBER + "}"),
            site(AMBIGUOUS, "java.lang.Object")),
        staticCell("A11", "demo/Bridge.java", "demo.Bridge", Map.of(
            "a/Tools.java", "package a; public class Tools extends external.Base { }"),
            site(POSSIBLE, "java.lang.Object")),
        staticCell("A12", "demo/Bridge.java", "demo.Bridge", Map.of(
            "a/Tools.java", "package a; public class Tools extends Plain { }",
            "a/Plain.java", "package a; public class Plain { }"),
            site(DEFINITE, "java.lang.Object")),
        staticCell("A13", "demo/Bridge.java", "demo.Bridge", Map.of(
            "a/Tools.java", "package a; public class Tools implements Iface { }",
            "a/Iface.java", "package a; public interface Iface {" + SERVICE_LOADER_MEMBER + "}"),
            site(AMBIGUOUS, "java.lang.Object")),
        staticCell("A14", "demo/Bridge.java", "demo.Bridge", Map.of(
            "a/Tools.java", "package a; public interface Tools extends First, Second { }",
            "a/First.java", "package a; public interface First { }",
            "a/Second.java", "package a; public interface Second {" + SERVICE_LOADER_MEMBER + "}"),
            site(AMBIGUOUS, "java.lang.Object")),
        staticCell("A15", "demo/Bridge.java", "demo.Bridge", Map.of(
            "a/Tools.java", "package a; public class Tools extends Base { }",
            "a/Base.java", "package a; public class Base extends external.X { }"),
            site(POSSIBLE, "java.lang.Object")),
        staticCell("A16", "demo/Bridge.java", "demo.Bridge", Map.of(
            "a/Tools.java", "package a; public class Tools extends Base { }",
            "a/Base.java", "package a; public class Base {" + PACKAGE_SERVICE_LOADER_MEMBER + "}"),
            site(DEFINITE, "java.lang.Object")),
        staticCellIn("A16b", "a", "a.Bridge", Map.of(
            "a/Tools.java", "package a; public class Tools extends Base { }",
            "a/Base.java", "package a; public class Base {" + PACKAGE_SERVICE_LOADER_MEMBER + "}"),
            site(AMBIGUOUS, "java.lang.Object")),
        staticCell("A17", "demo/Bridge.java", "demo.Bridge", Map.of(
            "a/Tools.java", "package a; public class Tools extends Base { }",
            "a/Base.java", """
                package a;
                public class Base {
                  public @$Outer.Ann({Object.class}) static class ServiceLoader<T> {
                    public static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                  }
                }
                """,
            "a/$Outer.java", """
                package a;
                public class $Outer {
                  public @interface Ann { Class<?>[] value(); }
                }
                """),
            site(AMBIGUOUS, "java.lang.Object")),
        staticCell("A20", "demo/Bridge.java", "demo.Bridge", Map.of(
            "a/Tools.java", "package a; public class Tools extends @Mark Base { }",
            "a/Mark.java", """
                package a;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE_USE)
                public @interface Mark { }
                """,
            "a/Base.java", "package a; public class Base {" + SERVICE_LOADER_MEMBER + "}"),
            site(AMBIGUOUS, "java.lang.Object")),
        staticCell("A21", "demo/Bridge.java", "demo.Bridge", Map.of(
            "a/Tools.java", """
                package a;
                public class Tools extends Base {
                  private static class ServiceLoader<T> {
                    static <S> ServiceLoader<S> load(Class<S> type) { return null; }
                  }
                }
                """,
            "a/Base.java", "package a; public class Base {" + SERVICE_LOADER_MEMBER + "}"),
            site(DEFINITE, "java.lang.Object")));
  }

  private static List<JavacResolverCell> dollarOwnerCells() {
    return List.of(
        cell("D1", DOLLAR_OWNER, "demo/Cash$Flow.java", "demo.Cash$Flow", 17,
            sources(
                "demo/Cash$Flow.java", dollarSite("demo", "Cash$Flow"),
                "demo/Cash.java", "package demo; class Cash { }"),
            site(DEFINITE, "java.lang.Object")),
        cell("D2", DOLLAR_OWNER, "demo/Outer.java", "demo.Outer$Inner", 17,
            sources("demo/Outer.java", """
                package demo;
                import java.util.*;
                class Outer {
                  static class Inner { void audit() { ServiceLoader.load(Object.class); } }
                }
                """),
            site(DEFINITE, "java.lang.Object")),
        cell("D3", DOLLAR_OWNER, "demo/Outer$Inner.java", "demo.Outer$Inner", 17,
            sources(
                "demo/Outer$Inner.java", dollarSite("demo", "Outer$Inner"),
                "demo/Outer.java", "package demo; class Outer { }"),
            site(DEFINITE, "java.lang.Object")),
        cell("D4", DOLLAR_OWNER, "a$b/Outer.java", "a$b.Outer$Inner", 17,
            sources(
                "a$b/Outer.java", """
                    package a$b;
                    import java.util.*;
                    class Outer {
                      static class Inner { void audit() { ServiceLoader.load(Object.class); } }
                    }
                    """,
                "a.java", "class a { }"),
            site(DEFINITE, "java.lang.Object")),
        cell("D5", DOLLAR_OWNER, "demo/Bridge.java", "demo.Bridge$1", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge {
                  void audit() {
                    new Object() { void run() { ServiceLoader.load(Object.class); } }.run();
                  }
                }
                """),
            site(DEFINITE, "java.lang.Object")),
        cell("D6", DOLLAR_OWNER, "demo/$Flow.java", "demo.$Flow", 17,
            sources("demo/$Flow.java", dollarSite("demo", "$Flow")),
            site(DEFINITE, "java.lang.Object")),
        cell("D7", DOLLAR_OWNER, "demo/Cash$$Flow.java", "demo.Cash$$Flow", 17,
            sources(
                "demo/Cash$$Flow.java", dollarSite("demo", "Cash$$Flow"),
                "demo/Cash.java", "package demo; class Cash { }"),
            site(DEFINITE, "java.lang.Object")),
        cell("D8", DOLLAR_OWNER, "demo/$.java", "demo.$", 17,
            sources("demo/$.java", dollarSite("demo", "$")),
            site(DEFINITE, "java.lang.Object")),
        cell("D9", DOLLAR_OWNER, "a$b/Outer.java", "a$b.Outer", 17,
            sources(
                "a$b/Outer.java", dollarSite("a$b", "Outer"),
                "a.java", "class a { }"),
            site(DEFINITE, "java.lang.Object")));
  }

  private static List<JavacResolverCell> incompleteCells() {
    return List.of(
        cell("I1", INCOMPLETE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge { void audit() { ServiceLoader.load(Missing.class); } }
                """),
            site(MISSING_INPUT, "Missing")),
        cell("I2", INCOMPLETE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                import missing.Dep;
                class Bridge { void audit() { ServiceLoader.load(Object.class); } }
                """),
            site(MISSING_INPUT, "java.lang.Object")),
        cell("I3", INCOMPLETE, "demo/Bridge.java", "demo.Bridge", 17,
            sources("demo/Bridge.java", """
                package demo;
                import java.util.*;
                class Bridge extends missing.Base {
                  void audit() { ServiceLoader.load(Object.class); }
                }
                """),
            site(MISSING_INPUT, "java.lang.Object")),
        cell("I4", INCOMPLETE, "r1/demo/Dup.java", "demo.Dup", 17,
            sources(
                "r1/demo/Dup.java", """
                    package demo;
                    import java.util.*;
                    class Dup { void audit() { ServiceLoader.load(Object.class); } }
                    """),
            List.of(Map.of("r2/demo/Dup.java", """
                package demo;
                import java.util.*;
                class Dup { void audit() { ServiceLoader.load(Object.class); } }
                """)),
            false,
            site(MISSING_INPUT, "java.lang.Object")),
        cell("I5", INCOMPLETE, "demo/Bridge.java", "demo.Bridge", 17,
            sources(
                "demo/Bridge.java", staticSite("demo"),
                "a/Tools.java", "package a; public class Tools extends Base { }",
                "a/Base.java", "package a; public class Base { Missing payload; }"),
            site(MISSING_INPUT, "java.lang.Object")));
  }

  private static String staticSite(String bridgePackage) {
    return "package " + bridgePackage + ";\n"
        + "import java.util.*;\n"
        + "import static a.Tools.*;\n"
        + "class Bridge { void audit() { ServiceLoader.load(Object.class); } }\n";
  }

  private static String dollarSite(String pkg, String type) {
    return "package " + pkg + ";\n"
        + "import java.util.*;\n"
        + "class " + type + " { void audit() { ServiceLoader.load(Object.class); } }\n";
  }

  private static JavacResolverCell staticCell(
      String id, String siteFile, String owner, Map<String, String> dependencies,
      JavacResolverCell.ExpectedSite... expected) {
    Map<String, String> sources = new LinkedHashMap<>();
    sources.put(siteFile, staticSite(owner.substring(0, owner.lastIndexOf('.'))));
    sources.putAll(dependencies);
    return cell(id, STATIC_OWNER, siteFile, owner, 17, sources, expected);
  }

  private static JavacResolverCell staticCellIn(
      String id, String bridgePackage, String owner, Map<String, String> dependencies,
      JavacResolverCell.ExpectedSite... expected) {
    String siteFile = bridgePackage + "/Bridge.java";
    Map<String, String> sources = new LinkedHashMap<>();
    sources.put(siteFile, staticSite(bridgePackage));
    sources.putAll(dependencies);
    return cell(id, STATIC_OWNER, siteFile, owner, 17, sources, expected);
  }

  private static JavacResolverCell cell(
      String id, JavacResolverCell.Family family, String siteFile, String owner,
      int release, Map<String, String> sources,
      JavacResolverCell.ExpectedSite... expected) {
    return cell(id, family, siteFile, owner, release, sources, List.of(), true, expected);
  }

  private static JavacResolverCell cell(
      String id, JavacResolverCell.Family family, String siteFile, String owner,
      int release, Map<String, String> sources, List<Map<String, String>> extraUnits,
      boolean baselineAvailable, JavacResolverCell.ExpectedSite... expected) {
    return new JavacResolverCell(
        id, family, siteFile, owner, "audit", release, sources, extraUnits,
        baselineAvailable, List.of(expected));
  }

  private static JavacResolverCell.ExpectedSite site(
      JavacResolverCell.Expectation expectation, String serviceType) {
    return new JavacResolverCell.ExpectedSite(expectation, serviceType);
  }

  private static Map<String, String> sources(String... entries) {
    Map<String, String> result = new LinkedHashMap<>();
    for (int index = 0; index < entries.length; index += 2) {
      result.put(entries[index], entries[index + 1]);
    }
    return result;
  }
}
