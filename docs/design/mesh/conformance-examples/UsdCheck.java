import java.math.BigDecimal; import java.math.RoundingMode;
public class UsdCheck { public static void main(String[] a) {
  double[] v = {0.00012, 1.25e-9, 1.25e-7, 2.5e-8, 123.456789125, 0.1+0.2};
  for (double d : v) System.out.println(Double.toString(d) + " -> " + BigDecimal.valueOf(d).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
}}
