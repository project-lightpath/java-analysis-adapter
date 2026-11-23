package com.projectlightpath.analysis.java;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Python numeric formatting for the changed-coverage validator (#45 step 4): round(x, 2)
 * and f"{x:.2f}" are half-even on the exact binary double (never HALF_UP on the decimal
 * expansion), non-finite values pass through or render as inf/nan like Python, and the
 * float() grammar excludes Java-only forms (hex, d/f suffixes).
 */
final class PythonNumbers {

  private PythonNumbers() {}

  /** Python round(x, 2): half-even on the exact binary double; inf/nan pass through. */
  static double round2(double value) {
    if (!Double.isFinite(value)) {
      return value;
    }
    return new BigDecimal(value).setScale(2, RoundingMode.HALF_EVEN).doubleValue();
  }

  /** Python f"{x:.2f}": half-even on the binary double; non-finite renders as inf/nan. */
  static String format2(double value) {
    if (!Double.isFinite(value)) {
      if (Double.isNaN(value)) {
        return "nan";
      }
      return value > 0 ? "inf" : "-inf";
    }
    return new BigDecimal(value).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
  }

  /**
   * Python float() grammar: decimal forms with underscores between digits, exponents,
   * and inf/infinity/nan (signable, case-insensitive). Java-only forms (hex, d/f
   * suffixes) reject as argument errors; inf/nan map to the double constants directly
   * because Double.parseDouble accepts only the exact-case spellings.
   */
  static Double parsePythonFloat(String text) {
    String value = text.strip();
    if (value.matches("(?i)[+-]?inf(inity)?")) {
      return value.startsWith("-") ? Double.NEGATIVE_INFINITY
          : Double.POSITIVE_INFINITY;
    }
    if (value.matches("(?i)[+-]?nan")) {
      return Double.NaN;
    }
    if (!value.matches("[+-]?(\\d+(_\\d+)*(\\.(\\d+(_\\d+)*)?)?|\\.\\d+(_\\d+)*)"
        + "([eE][+-]?\\d+(_\\d+)*)?")) {
      return null;
    }
    return Double.parseDouble(value.replace("_", ""));
  }
}
