package b;

public final class Tools {
  private Tools() { }

  public static final class ServiceLoader<T> {
    private ServiceLoader() { }

    public static <S> ServiceLoader<S> load(Class<S> type) {
      return new ServiceLoader<>();
    }
  }
}
