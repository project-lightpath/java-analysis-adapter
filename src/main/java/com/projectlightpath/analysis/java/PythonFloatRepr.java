package com.projectlightpath.analysis.java;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * CPython's float repr (#45 steps 3-4): the shortest decimal that round-trips to the
 * exact binary double, positional form for 1e-4 <= |x| < 1e16 (always with a fractional
 * part), exponent form {@code de+NN} outside that range. The digits come from rounding
 * the EXACT binary value (BigDecimal) to successive significant-digit counts and
 * checking the round-trip, never from re-parsing a mantissa: Java's Double.toString
 * digits on JDK 17 can differ from CPython's shortest repr, and a re-parse loses digits.
 * At each precision the adjacent decimals are also checked and the candidate closest to
 * the exact value wins — the boundary case CPython documents where the correctly rounded
 * candidate sits outside the double's rounding interval while an adjacent decimal sits
 * inside it.
 */
final class PythonFloatRepr {

  private PythonFloatRepr() {}

  /** Renders the value exactly as CPython's repr (NaN/Infinity in json.dumps form). */
  static String repr(double value) {
    if (Double.isNaN(value)) {
      return "NaN";
    }
    if (value == Double.POSITIVE_INFINITY) {
      return "Infinity";
    }
    if (value == Double.NEGATIVE_INFINITY) {
      return "-Infinity";
    }
    if (value == 0.0) {
      return 1.0 / value < 0 ? "-0.0" : "0.0";
    }
    String sign = value < 0 ? "-" : "";
    BigDecimal exact = new BigDecimal(value).abs();
    for (int digits = 1; digits <= 17; digits++) {
      BigDecimal rounded = exact.round(new MathContext(digits, RoundingMode.HALF_EVEN));
      String best = shortestRoundTrip(sign, value, exact, rounded);
      if (best != null) {
        return best;
      }
    }
    // Unreachable for finite doubles (17 significant digits always round-trip).
    return sign + exact.round(new MathContext(17)).toPlainString();
  }

  /**
   * The round-tripping candidate among the correctly rounded value and its two
   * adjacents that is closest to the exact value, or null when none round-trips.
   */
  private static String shortestRoundTrip(String sign, double value, BigDecimal exact,
      BigDecimal rounded) {
    BigDecimal step = BigDecimal.ONE.movePointRight(-rounded.scale());
    String best = null;
    BigDecimal bestDistance = null;
    for (BigDecimal candidate : new BigDecimal[] {
        rounded, rounded.add(step), rounded.subtract(step)}) {
      String formatted = formatRepr(sign, candidate);
      if (Double.parseDouble(formatted) == value) {
        BigDecimal distance = exact.subtract(candidate).abs();
        if (bestDistance == null || distance.compareTo(bestDistance) < 0) {
          best = formatted;
          bestDistance = distance;
        }
      }
    }
    return best;
  }

  /** Renders a rounded nonzero magnitude in Python's repr positional/exponent form. */
  private static String formatRepr(String sign, BigDecimal candidate) {
    BigDecimal stripped = candidate.stripTrailingZeros();
    int exponent = stripped.precision() - stripped.scale() - 1;
    if (exponent < -4 || exponent > 15) {
      String digits = stripped.unscaledValue().toString();
      String mantissa = digits.length() == 1 ? digits
          : digits.charAt(0) + "." + digits.substring(1);
      return sign + mantissa + "e" + (exponent < 0 ? "-" : "+")
          + String.format(java.util.Locale.ROOT, "%02d", Math.abs(exponent));
    }
    String positional = stripped.toPlainString();
    return sign + (positional.indexOf('.') < 0 ? positional + ".0" : positional);
  }
}
