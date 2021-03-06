package water;

import org.junit.BeforeClass;
import org.junit.Test;
import water.fvec.Frame;

public class ConcurrentKeyTest extends TestUtil {

  @BeforeClass public static void stall() { stall_till_cloudsize(2); }

  // Test rapid key create/delete during parse
  @Test public void testParse() {
    for( int i=0; i<25; i++ ) { // Small data to keep it fast
      Frame k1 = parse_test_file("smalldata/iris/iris.csv");
      k1.delete();
    }
  }
}
