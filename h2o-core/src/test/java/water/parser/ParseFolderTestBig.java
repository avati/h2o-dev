package water.parser;

import static org.junit.Assert.assertTrue;
import org.junit.Ignore;
import org.junit.Test;
import water.TestUtil;
import water.fvec.Frame;

public class ParseFolderTestBig extends TestUtil {

  @Test @Ignore("dataset directory is not usually available") public void testCovtype() {
    Frame k1 = null, k2 = null;
    try {
      k2 = parse_test_folder("datasets/parse_folder_test");
      k1 = parse_test_file  ("datasets/UCI/UCI-large/covtype/covtype.data");
      assertTrue("parsed values do not match!",isBitIdentical(k1,k2));
    } finally {
      if( k1 != null ) k1.delete();
      if( k2 != null ) k2.delete();
    }
  }
}
