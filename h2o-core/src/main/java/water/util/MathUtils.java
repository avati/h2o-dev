package water.util;

public class MathUtils {
  /**
   * Fast approximate sqrt
   * @param x
   * @return sqrt(x) with up to 5% relative error
   */
  final public static double approxSqrt(double x) {
    return Double.longBitsToDouble(((Double.doubleToLongBits(x) >> 32) + 1072632448) << 31);
  }
  /**
   * Fast approximate sqrt
   * @param x
   * @return sqrt(x) with up to 5% relative error
   */
  final public static float approxSqrt(float x) {
    return Float.intBitsToFloat(532483686 + (Float.floatToRawIntBits(x) >> 1));
  }
  /**
   * Fast approximate 1./sqrt
   * @param x
   * @return 1./sqrt(x) with up to 2% relative error
   */
  final public static double approxInvSqrt(double x) {
    double xhalf = 0.5d*x; x = Double.longBitsToDouble(0x5fe6ec85e7de30daL - (Double.doubleToLongBits(x)>>1)); return x*(1.5d - xhalf*x*x);
  }
  /**
   * Fast approximate 1./sqrt
   * @param x
   * @return 1./sqrt(x) with up to 2% relative error
   */
  final public static float approxInvSqrt(float x) {
    float xhalf = 0.5f*x; x = Float.intBitsToFloat(0x5f3759df - (Float.floatToIntBits(x)>>1)); return x*(1.5f - xhalf*x*x);
  }
  /**
   * Fast approximate exp
   * @param x
   * @return exp(x) with up to 5% relative error
   */
  final public static double approxExp(double x) {
    return Double.longBitsToDouble(((long)(1512775 * x + 1072632447)) << 32);
  }
  /**
   * Fast approximate log for values greater than 1, otherwise exact
   * @param x
   * @return log(x) with up to 0.1% relative error
   */
  final public static double approxLog(double x){
    if (x > 1) return ((Double.doubleToLongBits(x) >> 32) - 1072632447d) / 1512775d;
    else return Math.log(x);
  }

  public static float sumSquares(final float[] a) {
    return sumSquares(a, 0, a.length);
  }

  /**
   * Approximate sumSquares
   * @param a Array with numbers
   * @param from starting index (inclusive)
   * @param to ending index (exclusive)
   * @return approximate sum of squares based on a sample somewhere in the middle of the array (pos determined by bits of a[0])
   */
  public static float approxSumSquares(final float[] a, int from, int to) {
    final int len = to-from;
    final int samples = Math.max(len / 16, 1);
    final int offset = from + Math.abs(Float.floatToIntBits(a[0])) % (len-samples);
    assert(offset+samples <= to);
    return sumSquares(a, offset, offset + samples) * (float)len / (float)samples;
  }

  public static float sumSquares(final float[] a, int from, int to) {
    float result = 0;
    final int cols = to-from;
    final int extra=cols-cols%8;
    final int multiple = (cols/8)*8-1;
    float psum1 = 0, psum2 = 0, psum3 = 0, psum4 = 0;
    float psum5 = 0, psum6 = 0, psum7 = 0, psum8 = 0;
    for (int c = from; c < from + multiple; c += 8) {
      psum1 += a[c  ]*a[c  ];
      psum2 += a[c+1]*a[c+1];
      psum3 += a[c+2]*a[c+2];
      psum4 += a[c+3]*a[c+3];
      psum5 += a[c+4]*a[c+4];
      psum6 += a[c+5]*a[c+5];
      psum7 += a[c+6]*a[c+6];
      psum8 += a[c+7]*a[c+7];
    }
    result += psum1 + psum2 + psum3 + psum4;
    result += psum5 + psum6 + psum7 + psum8;
    for (int c = from + extra; c < to; ++c) {
      result += a[c]*a[c];
    }
    return result;
  }

}
