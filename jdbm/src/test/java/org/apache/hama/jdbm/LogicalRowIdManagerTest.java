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

/**
 * This class contains all Unit tests for {@link LogicalRowIdManager}.
 */
public class LogicalRowIdManagerTest extends TestCaseWithTestFile {

  /**
   * Test constructor
   */
  public void testCtor() throws Exception {
    PageFile f = newRecordFile();
    PageManager pm = new PageManager(f);
    PageFile free = newRecordFile();
    PageManager pmfree = new PageManager(free);

    LogicalRowIdManager logMgr = new LogicalRowIdManager(f, pm);

    f.forceClose();
  }

  /**
   * Test basics
   */
  public void testBasics() throws Exception {
    PageFile f = newRecordFile();
    PageManager pm = new PageManager(f);
    PageFile free = newRecordFile();
    PageManager pmfree = new PageManager(free);
    LogicalRowIdManager logMgr = new LogicalRowIdManager(f, pm);
    long physid = 20 << Storage.PAGE_SIZE_SHIFT + 234;

    long logid = logMgr.insert(physid);
    assertEquals("check one", physid, logMgr.fetch(logid));

    physid = 10 << Storage.PAGE_SIZE_SHIFT + 567;
    logMgr.update(logid, physid);
    assertEquals("check two", physid, logMgr.fetch(logid));

    logMgr.delete(logid);

    f.forceClose();
  }

  public void testFreeBasics() throws Exception {
    PageFile f = newRecordFile();
    PageManager pm = new PageManager(f);
    LogicalRowIdManager freeMgr = new LogicalRowIdManager(f, pm);

    // allocate a rowid - should fail on an empty file
    long loc = freeMgr.getFreeSlot();
    assertTrue("loc is not null?", loc == 0);

    pm.close();
    f.close();
  }

}
