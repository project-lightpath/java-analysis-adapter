package demo;

import java.util.Optional;

public final class ServiceLoader<T> {
  private ServiceLoader() { }

  public static <S> Loader<S> load(Class<S> type) {
    return new Loader<>();
  }

  public static final class Loader<S> {
    public Optional<S> findFirst() {
      return Optional.empty();
    }
  }
}
