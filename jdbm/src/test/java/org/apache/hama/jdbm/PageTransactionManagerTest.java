/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hama.jdbm;

import java.io.File;

/**
 * This class contains all Unit tests for {@link PageTransactionManager}. TODO
 * sort out this testcase
 */
public class PageTransactionManagerTest extends TestCaseWithTestFile {

  String file = newTestFile();

  /**
   * Test constructor. Oops - can only be done indirectly :-)
   */
  public void testCtor() throws Exception {
    PageFile file2 = new PageFile(file);

    file2.forceClose();
  }

  /**
   * Test recovery
   */
  public void XtestRecovery() throws Exception {
    PageFile file1 = new PageFile(file);

    // Do three transactions.
    for (int i = 0; i < 3; i++) {
      PageIo node = file1.get(i);
      node.setDirty();
      file1.release(node);
      file1.commit();
    }
    assertDataSizeEquals("len1", 0);
    assertLogSizeNotZero("len1");

    file1.forceClose();

    // Leave the old record file in flux, and open it again.
    // The second instance should start recovery.
    PageFile file2 = new PageFile(file);

    assertDataSizeEquals("len2", 3 * Storage.PAGE_SIZE);
    assertLogSizeEquals("len2", 8);

    file2.forceClose();

    // assure we can recover this log file
    PageFile file3 = new PageFile(file);

    file3.forceClose();
  }

  /**
   * Test background synching
   */
  public void XtestSynching() throws Exception {
    PageFile file1 = new PageFile(file);

    // Do enough transactions to fill the first slot
    int txnCount = 1;
    for (int i = 0; i < txnCount; i++) {
      PageIo node = file1.get(i);
      node.setDirty();
      file1.release(node);
      file1.commit();
    }
    file1.forceClose();

    // The data file now has the first slotfull
    assertDataSizeEquals("len1", 1 * Storage.PAGE_SIZE + 6);
    assertLogSizeNotZero("len1");

    // Leave the old record file in flux, and open it again.
    // The second instance should start recovery.
    PageFile file2 = new PageFile(file);

    assertDataSizeEquals("len2", txnCount * Storage.PAGE_SIZE);
    assertLogSizeEquals("len2", 8);

    file2.forceClose();
  }

  // Helpers

  void assertDataSizeEquals(String msg, long size) {
    assertEquals(msg + " data size", size, new File(file + ".t").length());
  }

  void assertLogSizeEquals(String msg, long size) {
    assertEquals(msg + " log size", size, new File(file
        + StorageDisk.transaction_log_file_extension).length());
  }

  void assertLogSizeNotZero(String msg) {
    assertTrue(msg + " log size", new File(file
        + StorageDisk.transaction_log_file_extension).length() != 0);
  }

}
